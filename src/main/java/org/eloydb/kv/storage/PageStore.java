package org.eloydb.kv.storage;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KvException;
import org.eloydb.kv.Metrics;

/**
 * Single-file page-backed store with 8 KiB fixed pages.
 *
 * <p>Pages are addressed by stable 64-bit {@link Long} ids that map directly to file offset {@code
 * pid * 8192}. Page {@code 0} is reserved for {@link PageType#META}.
 *
 * <p>The store supports:
 *
 * <ul>
 *   <li>Reading and writing pages through a {@link FileChannel} and off-heap {@link MemorySegment}
 *       buffers so the operational path no longer goes through heap {@code byte[]}.
 *   <li>Stable allocation: {@link #allocate(PageType)} returns a free-list reuse first, otherwise
 *       grows the file. {@link #free(long)} returns a page id to a persisted chain of {@link
 *       PageType#FREELIST} pages so that allocation survives restart.
 *   <li>Debug-mode page hashing to assert that a published page is never mutated in place (verified
 *       on every read).
 * </ul>
 *
 * <p>Concurrency: an instance is not thread-safe; the engine wraps access in its writer lock.
 */
@SuppressWarnings("NonApiType")
public final class PageStore implements AutoCloseable {
  public static final long META_PAGE_ID = 0L;

  private static final int FREELIST_HEADER_BYTES = 8 /* next */ + 4 /* count */;
  private static final int FREELIST_ENTRY_BYTES = 8;
  private static final int FREELIST_CAPACITY =
      (SlottedPage.PAYLOAD_BYTES - FREELIST_HEADER_BYTES) / FREELIST_ENTRY_BYTES;

  private final Path path;
  private final FileChannel channel;
  private final Arena arena;
  private final Metrics metrics;
  private final boolean debugVerifyPublished;
  private final Map<Long, byte[]> publishedHashes;

  private long nextAllocPid;
  private long freelistHead;
  private int freePageCount;

  private PageStore(
      Path path,
      FileChannel channel,
      Arena arena,
      Metrics metrics,
      boolean debugVerifyPublished,
      long nextAllocPid,
      long freelistHead,
      int freePageCount) {
    this.path = path;
    this.channel = channel;
    this.arena = arena;
    this.metrics = metrics;
    this.debugVerifyPublished = debugVerifyPublished;
    this.publishedHashes = debugVerifyPublished ? new HashMap<>() : Map.of();
    this.nextAllocPid = nextAllocPid;
    this.freelistHead = freelistHead;
    this.freePageCount = freePageCount;
  }

  /** Opens or creates the store at {@code directory/store.0001}, bootstrapping the meta page. */
  public static PageStore open(Path directory, Metrics metrics) {
    return open(directory, metrics, false);
  }

  /** Opens the store and optionally enables debug verification of published-page immutability. */
  public static PageStore open(Path directory, Metrics metrics, boolean debugVerifyPublished) {
    try {
      Files.createDirectories(directory);
      Path path = directory.resolve("store.0001");
      var options =
          EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
      FileChannel channel = FileChannel.open(path, options);
      Arena arena = Arena.ofShared();
      long size = channel.size();
      long nextAllocPid;
      long freelistHead = 0L;
      int freePageCount = 0;
      if (size == 0) {
        nextAllocPid = 1L; // page 0 reserved for meta
      } else {
        if (size % Page.PAGE_SIZE != 0L) {
          throw new KvException(
              ErrorCode.CORRUPTED_PAGE,
              "store size " + size + " is not a multiple of " + Page.PAGE_SIZE);
        }
        // Recover meta to discover next_alloc_pid / freelist head.
        MetaPayload meta = readMetaInternal(channel, arena);
        nextAllocPid = Math.max(1L, meta.nextAllocPid());
        freelistHead = meta.freelistHeadPid();
        freePageCount = meta.freePageCount();
      }
      return new PageStore(
          path,
          channel,
          arena,
          metrics,
          debugVerifyPublished,
          nextAllocPid,
          freelistHead,
          freePageCount);
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot open page store in " + directory, e);
    }
  }

  public Path path() {
    return path;
  }

  public long nextAllocPid() {
    return nextAllocPid;
  }

  public long freelistHead() {
    return freelistHead;
  }

  public int freePageCount() {
    return freePageCount;
  }

  /** Allocates a fresh page id, reusing the free list when possible. */
  public long allocate(PageType type) {
    if (freelistHead != 0L && type != PageType.FREELIST) {
      long pid = popFreelistEntry();
      metrics.increment("tree.pages_allocated");
      return pid;
    }
    long pid = nextAllocPid++;
    metrics.increment("tree.pages_allocated");
    return pid;
  }

  /** Releases a previously allocated page id back to the persisted free list. */
  public void free(long pageId) {
    if (pageId == META_PAGE_ID) {
      throw new IllegalArgumentException("cannot free meta page");
    }
    pushFreelistEntry(pageId);
    if (debugVerifyPublished) {
      publishedHashes.remove(pageId);
    }
    metrics.increment("tree.pages_freed");
  }

  /** Writes a page through the channel; updates the published-page hash in debug mode. */
  public void write(Page page) {
    try {
      MemorySegment segment = page.serialize(arena);
      ByteBuffer buf = segment.asByteBuffer();
      long offset = page.pageId() * (long) Page.PAGE_SIZE;
      while (buf.hasRemaining()) {
        channel.write(buf, offset + buf.position());
      }
      if (debugVerifyPublished && page.type() != PageType.META) {
        publishedHashes.put(page.pageId(), sha256(segment));
      }
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot write page " + page.pageId(), e);
    }
  }

  /** Reads a page from the store; verifies its CRC and (in debug mode) its publish hash. */
  public Page read(long pageId) {
    if (pageId < 0) {
      throw new IllegalArgumentException("negative page id");
    }
    try {
      long offset = pageId * (long) Page.PAGE_SIZE;
      if (offset + Page.PAGE_SIZE > channel.size()) {
        throw new PageCorruptedException(pageId, "page beyond end of store");
      }
      MemorySegment segment = arena.allocate(Page.PAGE_SIZE, 8);
      ByteBuffer buf = segment.asByteBuffer();
      while (buf.hasRemaining()) {
        int read = channel.read(buf, offset + buf.position());
        if (read < 0) {
          throw new PageCorruptedException(pageId, "unexpected EOF while reading page");
        }
      }
      if (debugVerifyPublished) {
        byte[] expected = publishedHashes.get(pageId);
        if (expected != null) {
          byte[] actual = sha256(segment);
          if (!Arrays.equals(expected, actual)) {
            throw new PageCorruptedException(pageId, "published page mutated in place");
          }
        }
      }
      return Page.deserialize(pageId, segment);
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot read page " + pageId, e);
    }
  }

  /** Flushes the underlying file to disk. */
  public void sync() {
    try {
      channel.force(true);
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot fsync store", e);
    }
  }

  /** Returns the current size of the store file in bytes. */
  public long fileSize() {
    try {
      return channel.size();
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot stat store", e);
    }
  }

  /** Number of pages currently extending the file (including the meta page and free pages). */
  public long allocatedPages() {
    return nextAllocPid;
  }

  /** Reads the meta page payload. Returns {@code null} if the store has never been initialised. */
  public MetaPayload readMeta() {
    try {
      if (channel.size() < Page.PAGE_SIZE) {
        return null;
      }
      return readMetaInternal(channel, arena);
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot read meta page", e);
    }
  }

  /** Writes a meta page with the given LSN and the current free-list bookkeeping. */
  public void writeMeta(long lsn, long rootPid, long commitTs) {
    MetaPayload payload =
        new MetaPayload(rootPid, commitTs, nextAllocPid, freelistHead, freePageCount);
    Page page = new Page(PageType.META, lsn, META_PAGE_ID, payload.encode());
    write(page);
  }

  @Override
  public void close() {
    try {
      channel.close();
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot close page store", e);
    } finally {
      arena.close();
    }
  }

  private long popFreelistEntry() {
    Page head = read(freelistHead);
    if (head.type() != PageType.FREELIST) {
      throw new KvException(
          ErrorCode.CORRUPTED_PAGE, "free-list page " + freelistHead + " has wrong type");
    }
    byte[] payload = head.payload();
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    long next = buf.getLong(0);
    int count = buf.getInt(8);
    if (count <= 0) {
      // Empty page - reuse it directly and unlink it.
      long reused = freelistHead;
      freelistHead = next;
      freePageCount = Math.max(0, freePageCount - 1);
      return reused;
    }
    int slot = count - 1;
    long pid = buf.getLong(FREELIST_HEADER_BYTES + slot * FREELIST_ENTRY_BYTES);
    buf.putInt(8, count - 1);
    freePageCount = Math.max(0, freePageCount - 1);
    Page rewritten = new Page(PageType.FREELIST, head.lsn(), freelistHead, payload);
    write(rewritten);
    return pid;
  }

  private void pushFreelistEntry(long pageId) {
    if (freelistHead == 0L) {
      long headPid = nextAllocPid++;
      byte[] payload = new byte[SlottedPage.PAYLOAD_BYTES];
      ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
      buf.putLong(0, 0L);
      buf.putInt(8, 1);
      buf.putLong(FREELIST_HEADER_BYTES, pageId);
      write(new Page(PageType.FREELIST, 0L, headPid, payload));
      freelistHead = headPid;
      freePageCount++;
      return;
    }
    Page head = read(freelistHead);
    byte[] payload = head.payload();
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    int count = buf.getInt(8);
    if (count >= FREELIST_CAPACITY) {
      long newHeadPid = nextAllocPid++;
      byte[] newPayload = new byte[SlottedPage.PAYLOAD_BYTES];
      ByteBuffer nbuf = ByteBuffer.wrap(newPayload).order(ByteOrder.BIG_ENDIAN);
      nbuf.putLong(0, freelistHead);
      nbuf.putInt(8, 1);
      nbuf.putLong(FREELIST_HEADER_BYTES, pageId);
      write(new Page(PageType.FREELIST, 0L, newHeadPid, newPayload));
      freelistHead = newHeadPid;
      freePageCount++;
      return;
    }
    buf.putLong(FREELIST_HEADER_BYTES + count * FREELIST_ENTRY_BYTES, pageId);
    buf.putInt(8, count + 1);
    write(new Page(PageType.FREELIST, head.lsn(), freelistHead, payload));
    freePageCount++;
  }

  /** Walks the persisted free-list and returns every page id it lists. */
  public List<Long> freelistContents() {
    List<Long> ids = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    long cursor = freelistHead;
    while (cursor != 0L) {
      if (!seen.add(cursor)) {
        throw new KvException(ErrorCode.CORRUPTED_PAGE, "free-list cycle at " + cursor);
      }
      Page page = read(cursor);
      byte[] payload = page.payload();
      ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
      long next = buf.getLong(0);
      int count = buf.getInt(8);
      for (int i = 0; i < count; i++) {
        ids.add(buf.getLong(FREELIST_HEADER_BYTES + i * FREELIST_ENTRY_BYTES));
      }
      cursor = next;
    }
    return ids;
  }

  private static MetaPayload readMetaInternal(FileChannel channel, Arena arena) throws IOException {
    MemorySegment segment = arena.allocate(Page.PAGE_SIZE, 8);
    ByteBuffer buf = segment.asByteBuffer();
    while (buf.hasRemaining()) {
      int read = channel.read(buf, buf.position());
      if (read < 0) {
        throw new PageCorruptedException(META_PAGE_ID, "unexpected EOF reading meta");
      }
    }
    Page page = Page.deserialize(META_PAGE_ID, segment);
    if (page.type() != PageType.META) {
      throw new PageCorruptedException(META_PAGE_ID, "page 0 is not a meta page");
    }
    return MetaPayload.decode(page.payload());
  }

  private static byte[] sha256(MemorySegment segment) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(segment.asByteBuffer());
      return md.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
