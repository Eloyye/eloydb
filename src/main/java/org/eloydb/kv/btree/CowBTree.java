package org.eloydb.kv.btree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.eloydb.kv.KeyValue;
import org.eloydb.kv.storage.BufferPool;
import org.eloydb.kv.storage.OverflowChain;
import org.eloydb.kv.storage.Page;
import org.eloydb.kv.storage.PageType;
import org.eloydb.kv.storage.SlottedPage;
import org.eloydb.kv.storage.SlottedPage.InternalBuilder;
import org.eloydb.kv.storage.SlottedPage.InternalEntry;
import org.eloydb.kv.storage.SlottedPage.LeafBuilder;
import org.eloydb.kv.storage.SlottedPage.LeafEntry;
import org.jspecify.annotations.Nullable;

/**
 * Single-writer Copy-on-Write B+ tree backed by a {@link BufferPool}.
 *
 * <p>Every modification clones the path from root to leaf, allocating fresh page ids for the new
 * pages. The previous root remains valid and is the durable root until the engine publishes the new
 * root in the meta page. The implementation is single-threaded — concurrency is the engine's
 * responsibility.
 *
 * <p><strong>M1 scope:</strong> insert/replace/delete with leaf and internal splits and overflow
 * value support. Page merges/rebalancing on under-fill are <em>not</em> implemented — the page
 * reclaimer that will reclaim them is also out of scope (see the milestone leftover document).
 */
public final class CowBTree {

  /** Result of applying an operation to a subtree. */
  public sealed interface ApplyResult {
    long pid();
  }

  /** Subtree replaced by a single new page. */
  public record Replaced(long pid) implements ApplyResult {}

  /** Subtree split into two pages with {@code separator} = min key of {@code rightPid}. */
  public record Split(long pid, byte[] separator, long rightPid) implements ApplyResult {
    @Override
    public long pid() {
      return pid;
    }
  }

  private final BufferPool pages;
  private final NodePacker packer;
  private long nextLsn;

  public CowBTree(BufferPool pages, long startingLsn) {
    this.pages = pages;
    this.packer = new NodePacker(pages, this::allocLsn);
    this.nextLsn = startingLsn;
  }

  /** Allocates an empty leaf and returns its page id; used to bootstrap a new tree. */
  public static long createEmpty(BufferPool pages, long lsn) {
    long pid = pages.allocate(PageType.LEAF);
    byte[] payload = new LeafBuilder().build();
    pages.write(new Page(PageType.LEAF, lsn, pid, payload));
    return pid;
  }

  /** Returns the value for {@code key} read through {@code root}, or empty if not present. */
  public Optional<byte[]> get(long root, byte[] key) {
    long pid = descendToLeaf(root, key);
    Page leaf = pages.read(pid);
    List<LeafEntry> entries = SlottedPage.decodeLeaf(leaf.payload());
    int slot = NodeSearch.findLeafSlot(entries, key);
    if (slot < 0) {
      return Optional.empty();
    }
    return Optional.of(materializeValue(entries.get(slot)));
  }

  /** Collects {@code [startInclusive, endExclusive)} as a list of {@link KeyValue}. */
  public List<KeyValue> scan(long root, byte[] startInclusive, byte[] endExclusive) {
    List<KeyValue> rows = new ArrayList<>();
    walkRange(root, startInclusive, endExclusive, rows);
    return rows;
  }

  /** Inserts or replaces {@code key} → {@code value}, returning the new root pid. */
  public long put(long root, byte[] key, byte[] value) {
    ApplyResult result = insert(root, key, value);
    return finalizeRoot(result);
  }

  /** Removes {@code key} (if present), returning the new root pid. */
  public long delete(long root, byte[] key) {
    long newRoot = removeFromNode(root, key);
    return newRoot;
  }

  /** Walks every page reachable from {@code root}, invoking {@code visitor} on each id. */
  public void walkAllPages(long root, java.util.function.LongConsumer visitor) {
    Page page = pages.read(root);
    visitor.accept(root);
    if (page.type() == PageType.LEAF) {
      for (LeafEntry e : SlottedPage.decodeLeaf(page.payload())) {
        if (e.overflow()) {
          long cursor = e.overflowHead();
          while (cursor != 0L) {
            visitor.accept(cursor);
            Page ov = pages.read(cursor);
            cursor =
                java.nio.ByteBuffer.wrap(ov.payload())
                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                    .getLong(0);
          }
        }
      }
      return;
    }
    walkAllPages(SlottedPage.internalLeftmostChild(page.payload()), visitor);
    for (InternalEntry e : SlottedPage.decodeInternal(page.payload())) {
      walkAllPages(e.childPid(), visitor);
    }
  }

  /* --------------------------------------------------------------------- *
   *                              Insert                                   *
   * --------------------------------------------------------------------- */

  private ApplyResult insert(long pid, byte[] key, byte[] value) {
    Page page = pages.read(pid);
    if (page.type() == PageType.LEAF) {
      return insertLeaf(page, key, value);
    }
    return insertInternal(page, key, value);
  }

  private ApplyResult insertLeaf(Page page, byte[] key, byte[] value) {
    List<LeafEntry> entries = new ArrayList<>(SlottedPage.decodeLeaf(page.payload()));
    int slot = NodeSearch.findLeafSlot(entries, key);
    LeafEntry newEntry = createLeafEntry(key, value);
    if (slot >= 0) {
      LeafEntry old = entries.get(slot);
      if (old.overflow()) {
        // Leak overflow pages from the replaced value (reclaimer will handle).
        // TODO(reclaimer): free overflow chain when MVCC reclamation lands.
      }
      entries.set(slot, newEntry);
    } else {
      entries.add(-slot - 1, newEntry);
    }
    return packer.packLeaf(entries);
  }

  private ApplyResult insertInternal(Page page, byte[] key, byte[] value) {
    List<InternalEntry> entries = new ArrayList<>(SlottedPage.decodeInternal(page.payload()));
    long leftmost = SlottedPage.internalLeftmostChild(page.payload());
    int idx = NodeSearch.findChildIndex(entries, key);
    long childPid = idx == -1 ? leftmost : entries.get(idx).childPid();
    ApplyResult childResult = insert(childPid, key, value);

    long newLeftmost = leftmost;
    List<InternalEntry> newEntries = new ArrayList<>(entries);
    if (idx == -1) {
      newLeftmost = childResult.pid();
    } else {
      InternalEntry existing = entries.get(idx);
      newEntries.set(idx, new InternalEntry(existing.separator(), childResult.pid()));
    }
    if (childResult instanceof Split split) {
      newEntries.add(idx + 1, new InternalEntry(split.separator(), split.rightPid()));
    }
    return packer.packInternal(newLeftmost, newEntries);
  }

  /* --------------------------------------------------------------------- *
   *                              Delete                                   *
   * --------------------------------------------------------------------- */

  private long removeFromNode(long pid, byte[] key) {
    Page page = pages.read(pid);
    if (page.type() == PageType.LEAF) {
      return removeFromLeaf(page, key);
    }
    return removeFromInternal(page, key);
  }

  private long removeFromLeaf(Page page, byte[] key) {
    List<LeafEntry> entries = new ArrayList<>(SlottedPage.decodeLeaf(page.payload()));
    int slot = NodeSearch.findLeafSlot(entries, key);
    if (slot < 0) {
      // No change: return original pid so callers don't churn.
      return page.pageId();
    }
    entries.remove(slot);
    ApplyResult result = packer.packLeaf(entries);
    // Delete should never split (we only shrink).
    if (result instanceof Split) {
      throw new IllegalStateException("delete unexpectedly produced a split");
    }
    return result.pid();
  }

  private long removeFromInternal(Page page, byte[] key) {
    List<InternalEntry> entries = new ArrayList<>(SlottedPage.decodeInternal(page.payload()));
    long leftmost = SlottedPage.internalLeftmostChild(page.payload());
    int idx = NodeSearch.findChildIndex(entries, key);
    long childPid = idx == -1 ? leftmost : entries.get(idx).childPid();
    long newChildPid = removeFromNode(childPid, key);
    if (newChildPid == childPid) {
      return page.pageId();
    }
    List<InternalEntry> newEntries = new ArrayList<>(entries);
    long newLeftmost = leftmost;
    if (idx == -1) {
      newLeftmost = newChildPid;
    } else {
      InternalEntry existing = entries.get(idx);
      newEntries.set(idx, new InternalEntry(existing.separator(), newChildPid));
    }
    ApplyResult repacked = packer.packInternal(newLeftmost, newEntries);
    if (repacked instanceof Split) {
      throw new IllegalStateException("delete unexpectedly produced a split");
    }
    return repacked.pid();
  }

  /* --------------------------------------------------------------------- *
   *                              Helpers                                  *
   * --------------------------------------------------------------------- */

  private long descendToLeaf(long pid, byte[] key) {
    long cursor = pid;
    while (true) {
      Page page = pages.read(cursor);
      if (page.type() == PageType.LEAF) {
        return cursor;
      }
      List<InternalEntry> entries = SlottedPage.decodeInternal(page.payload());
      long leftmost = SlottedPage.internalLeftmostChild(page.payload());
      int idx = NodeSearch.findChildIndex(entries, key);
      cursor = idx == -1 ? leftmost : entries.get(idx).childPid();
    }
  }

  private long finalizeRoot(ApplyResult result) {
    if (result instanceof Replaced r) {
      return r.pid();
    }
    Split split = (Split) result;
    long newRootPid = pages.allocate(PageType.INTERNAL);
    InternalBuilder b = new InternalBuilder(split.pid());
    b.add(new InternalEntry(split.separator(), split.rightPid()));
    pages.write(new Page(PageType.INTERNAL, allocLsn(), newRootPid, b.build()));
    return newRootPid;
  }

  private long allocLsn() {
    return ++nextLsn;
  }

  public long currentLsn() {
    return nextLsn;
  }

  public void setLsn(long lsn) {
    this.nextLsn = lsn;
  }

  private LeafEntry createLeafEntry(byte[] key, byte[] value) {
    if (value.length <= SlottedPage.MAX_INLINE_VALUE_LEN) {
      return LeafEntry.inline(key, value);
    }
    long head = OverflowChain.write(pages, allocLsn(), value);
    return LeafEntry.overflow(key, head, value.length);
  }

  private byte[] materializeValue(LeafEntry entry) {
    if (!entry.overflow()) {
      return entry.value();
    }
    return OverflowChain.read(pages, entry.overflowHead(), entry.totalValueLength());
  }

  private void walkRange(long pid, byte[] start, byte[] end, List<KeyValue> rows) {
    Page page = pages.read(pid);
    if (page.type() == PageType.LEAF) {
      for (LeafEntry e : SlottedPage.decodeLeaf(page.payload())) {
        byte[] key = e.key();
        if (Arrays.compareUnsigned(key, start) < 0) {
          continue;
        }
        if (Arrays.compareUnsigned(key, end) >= 0) {
          return;
        }
        rows.add(new KeyValue(key, materializeValue(e)));
      }
      return;
    }
    List<InternalEntry> entries = SlottedPage.decodeInternal(page.payload());
    long leftmost = SlottedPage.internalLeftmostChild(page.payload());
    byte @Nullable [] firstSep = entries.isEmpty() ? null : entries.get(0).separator();
    if (firstSep == null || Arrays.compareUnsigned(start, firstSep) < 0) {
      walkRange(leftmost, start, end, rows);
    }
    for (int i = 0; i < entries.size(); i++) {
      InternalEntry e = entries.get(i);
      if (Arrays.compareUnsigned(e.separator(), end) >= 0) {
        return;
      }
      // Subtree spans [e.separator(), nextSep) - overlap with [start, end) iff e.separator() < end.
      walkRange(e.childPid(), start, end, rows);
    }
  }
}
