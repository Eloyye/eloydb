package org.eloydb.kv.btree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.eloydb.kv.KeyValue;
import org.eloydb.kv.storage.OverflowChain;
import org.eloydb.kv.storage.Page;
import org.eloydb.kv.storage.PageStore;
import org.eloydb.kv.storage.PageType;
import org.eloydb.kv.storage.SlottedPage;
import org.eloydb.kv.storage.SlottedPage.InternalBuilder;
import org.eloydb.kv.storage.SlottedPage.InternalEntry;
import org.eloydb.kv.storage.SlottedPage.LeafBuilder;
import org.eloydb.kv.storage.SlottedPage.LeafEntry;
import org.jspecify.annotations.Nullable;

/**
 * Single-writer Copy-on-Write B+ tree backed by a {@link PageStore}.
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

  private final PageStore store;
  private long nextLsn;

  public CowBTree(PageStore store, long startingLsn) {
    this.store = store;
    this.nextLsn = startingLsn;
  }

  /** Allocates an empty leaf and returns its page id; used to bootstrap a new tree. */
  public static long createEmpty(PageStore store, long lsn) {
    long pid = store.allocate(PageType.LEAF);
    byte[] payload = new LeafBuilder().build();
    store.write(new Page(PageType.LEAF, lsn, pid, payload));
    return pid;
  }

  /** Returns the value for {@code key} read through {@code root}, or empty if not present. */
  public Optional<byte[]> get(long root, byte[] key) {
    long pid = descendToLeaf(root, key);
    Page leaf = store.read(pid);
    List<LeafEntry> entries = SlottedPage.decodeLeaf(leaf.payload());
    int slot = findLeafSlot(entries, key);
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
    Page page = store.read(root);
    visitor.accept(root);
    if (page.type() == PageType.LEAF) {
      for (LeafEntry e : SlottedPage.decodeLeaf(page.payload())) {
        if (e.overflow()) {
          long cursor = e.overflowHead();
          while (cursor != 0L) {
            visitor.accept(cursor);
            Page ov = store.read(cursor);
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
    Page page = store.read(pid);
    if (page.type() == PageType.LEAF) {
      return insertLeaf(page, key, value);
    }
    return insertInternal(page, key, value);
  }

  private ApplyResult insertLeaf(Page page, byte[] key, byte[] value) {
    List<LeafEntry> entries = new ArrayList<>(SlottedPage.decodeLeaf(page.payload()));
    int slot = findLeafSlot(entries, key);
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
    return packLeaf(entries);
  }

  private ApplyResult insertInternal(Page page, byte[] key, byte[] value) {
    List<InternalEntry> entries = new ArrayList<>(SlottedPage.decodeInternal(page.payload()));
    long leftmost = SlottedPage.internalLeftmostChild(page.payload());
    int idx = findChildIndex(entries, key);
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
    return packInternal(newLeftmost, newEntries);
  }

  /* --------------------------------------------------------------------- *
   *                              Delete                                   *
   * --------------------------------------------------------------------- */

  private long removeFromNode(long pid, byte[] key) {
    Page page = store.read(pid);
    if (page.type() == PageType.LEAF) {
      return removeFromLeaf(page, key);
    }
    return removeFromInternal(page, key);
  }

  private long removeFromLeaf(Page page, byte[] key) {
    List<LeafEntry> entries = new ArrayList<>(SlottedPage.decodeLeaf(page.payload()));
    int slot = findLeafSlot(entries, key);
    if (slot < 0) {
      // No change: return original pid so callers don't churn.
      return page.pageId();
    }
    entries.remove(slot);
    ApplyResult result = packLeaf(entries);
    // Delete should never split (we only shrink).
    if (result instanceof Split) {
      throw new IllegalStateException("delete unexpectedly produced a split");
    }
    return result.pid();
  }

  private long removeFromInternal(Page page, byte[] key) {
    List<InternalEntry> entries = new ArrayList<>(SlottedPage.decodeInternal(page.payload()));
    long leftmost = SlottedPage.internalLeftmostChild(page.payload());
    int idx = findChildIndex(entries, key);
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
    ApplyResult repacked = packInternal(newLeftmost, newEntries);
    if (repacked instanceof Split) {
      throw new IllegalStateException("delete unexpectedly produced a split");
    }
    return repacked.pid();
  }

  /* --------------------------------------------------------------------- *
   *                              Packing                                  *
   * --------------------------------------------------------------------- */

  private ApplyResult packLeaf(List<LeafEntry> entries) {
    LeafBuilder builder = new LeafBuilder();
    for (LeafEntry e : entries) {
      builder.add(e);
    }
    if (builder.fits()) {
      long pid = store.allocate(PageType.LEAF);
      store.write(new Page(PageType.LEAF, allocLsn(), pid, builder.build()));
      return new Replaced(pid);
    }
    // Split: choose mid such that both halves fit.
    int midpoint = chooseLeafSplit(entries);
    List<LeafEntry> left = entries.subList(0, midpoint);
    List<LeafEntry> right = entries.subList(midpoint, entries.size());

    LeafBuilder lb = new LeafBuilder();
    left.forEach(lb::add);
    if (!lb.fits()) {
      throw new IllegalStateException("leaf left half still overflows after split");
    }
    long leftPid = store.allocate(PageType.LEAF);
    store.write(new Page(PageType.LEAF, allocLsn(), leftPid, lb.build()));

    LeafBuilder rb = new LeafBuilder();
    right.forEach(rb::add);
    if (!rb.fits()) {
      throw new IllegalStateException("leaf right half still overflows after split");
    }
    long rightPid = store.allocate(PageType.LEAF);
    store.write(new Page(PageType.LEAF, allocLsn(), rightPid, rb.build()));

    byte[] sep = Arrays.copyOf(right.get(0).key(), right.get(0).key().length);
    return new Split(leftPid, sep, rightPid);
  }

  private ApplyResult packInternal(long leftmost, List<InternalEntry> entries) {
    InternalBuilder b = new InternalBuilder(leftmost);
    for (InternalEntry e : entries) {
      b.add(e);
    }
    if (b.fits()) {
      long pid = store.allocate(PageType.INTERNAL);
      store.write(new Page(PageType.INTERNAL, allocLsn(), pid, b.build()));
      return new Replaced(pid);
    }
    int midpoint = chooseInternalSplit(entries);
    // entries[midpoint] is the promoted separator. Its child becomes leftmost of the right node.
    InternalEntry promoted = entries.get(midpoint);
    List<InternalEntry> leftEntries = entries.subList(0, midpoint);
    List<InternalEntry> rightEntries = entries.subList(midpoint + 1, entries.size());

    InternalBuilder lb = new InternalBuilder(leftmost);
    leftEntries.forEach(lb::add);
    if (!lb.fits()) {
      throw new IllegalStateException("internal left half still overflows after split");
    }
    long leftPid = store.allocate(PageType.INTERNAL);
    store.write(new Page(PageType.INTERNAL, allocLsn(), leftPid, lb.build()));

    InternalBuilder rb = new InternalBuilder(promoted.childPid());
    rightEntries.forEach(rb::add);
    if (!rb.fits()) {
      throw new IllegalStateException("internal right half still overflows after split");
    }
    long rightPid = store.allocate(PageType.INTERNAL);
    store.write(new Page(PageType.INTERNAL, allocLsn(), rightPid, rb.build()));

    return new Split(
        leftPid, Arrays.copyOf(promoted.separator(), promoted.separator().length), rightPid);
  }

  private int chooseLeafSplit(List<LeafEntry> entries) {
    int total = 0;
    for (LeafEntry e : entries) {
      total += LeafBuilder.costOf(e);
    }
    int target = total / 2;
    int running = 0;
    for (int i = 0; i < entries.size(); i++) {
      running += LeafBuilder.costOf(entries.get(i));
      if (running >= target && i > 0) {
        return i;
      }
    }
    return Math.max(1, entries.size() / 2);
  }

  private int chooseInternalSplit(List<InternalEntry> entries) {
    int total = 0;
    for (InternalEntry e : entries) {
      total += InternalBuilder.costOf(e);
    }
    int target = total / 2;
    int running = 0;
    for (int i = 0; i < entries.size(); i++) {
      running += InternalBuilder.costOf(entries.get(i));
      if (running >= target && i > 0) {
        return i;
      }
    }
    return Math.max(1, entries.size() / 2);
  }

  /* --------------------------------------------------------------------- *
   *                              Helpers                                  *
   * --------------------------------------------------------------------- */

  private long descendToLeaf(long pid, byte[] key) {
    long cursor = pid;
    while (true) {
      Page page = store.read(cursor);
      if (page.type() == PageType.LEAF) {
        return cursor;
      }
      List<InternalEntry> entries = SlottedPage.decodeInternal(page.payload());
      long leftmost = SlottedPage.internalLeftmostChild(page.payload());
      int idx = findChildIndex(entries, key);
      cursor = idx == -1 ? leftmost : entries.get(idx).childPid();
    }
  }

  private long finalizeRoot(ApplyResult result) {
    if (result instanceof Replaced r) {
      return r.pid();
    }
    Split split = (Split) result;
    long newRootPid = store.allocate(PageType.INTERNAL);
    InternalBuilder b = new InternalBuilder(split.pid());
    b.add(new InternalEntry(split.separator(), split.rightPid()));
    store.write(new Page(PageType.INTERNAL, allocLsn(), newRootPid, b.build()));
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
    long head = OverflowChain.write(store, allocLsn(), value);
    return LeafEntry.overflow(key, head, value.length);
  }

  private byte[] materializeValue(LeafEntry entry) {
    if (!entry.overflow()) {
      return entry.value();
    }
    return OverflowChain.read(store, entry.overflowHead(), entry.totalValueLength());
  }

  private static int findLeafSlot(List<LeafEntry> entries, byte[] key) {
    int lo = 0;
    int hi = entries.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      int cmp = Arrays.compareUnsigned(entries.get(mid).key(), key);
      if (cmp < 0) {
        lo = mid + 1;
      } else if (cmp > 0) {
        hi = mid;
      } else {
        return mid;
      }
    }
    return -(lo + 1);
  }

  private static int findChildIndex(List<InternalEntry> entries, byte[] key) {
    int lo = 0;
    int hi = entries.size();
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (Arrays.compareUnsigned(entries.get(mid).separator(), key) <= 0) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo - 1;
  }

  private void walkRange(long pid, byte[] start, byte[] end, List<KeyValue> rows) {
    Page page = store.read(pid);
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
    @Nullable byte[] firstSep = entries.isEmpty() ? null : entries.get(0).separator();
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
