package org.eloydb.kv.storage;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.eloydb.kv.Config;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KvException;
import org.eloydb.kv.Metrics;
import org.jspecify.annotations.Nullable;

/** Sharded off-heap page cache with CLOCK eviction and explicit pin/unpin handles. */
@SuppressWarnings("NonApiType")
public final class BufferPool implements AutoCloseable {
  private final PageStore store;
  private final Shard[] shards;
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong evictions = new AtomicLong();
  private final AtomicLong dirtyPages = new AtomicLong();

  public BufferPool(Config config, PageStore store, Metrics metrics) {
    this.store = store;
    int capacity = Math.max(1, Math.toIntExact(config.bufferPoolBytes() / Page.PAGE_SIZE));
    int shardCount = Math.max(1, Math.min(config.bufferPoolShards(), capacity));
    this.shards = new Shard[shardCount];
    int baseCapacity = capacity / shardCount;
    int extra = capacity % shardCount;
    for (int i = 0; i < shardCount; i++) {
      shards[i] = new Shard(baseCapacity + (i < extra ? 1 : 0));
    }
    metrics.gauge("bufferpool.hits", hits::get);
    metrics.gauge("bufferpool.misses", misses::get);
    metrics.gauge("bufferpool.evictions", evictions::get);
    metrics.gauge("bufferpool.dirty_pages", dirtyPages::get);
  }

  /** Allocates a stable page id through the backing store. */
  public long allocate(PageType type) {
    return store.allocate(type);
  }

  /** Frees a page id through the backing store and invalidates any cached clean copy. */
  public void free(long pageId) {
    invalidate(pageId);
    store.free(pageId);
  }

  /** Pins an existing page, loading it from the page store on a cache miss. */
  public PageHandle pin(long pageId) {
    Shard shard = shard(pageId);
    shard.lock.lock();
    try {
      Frame existing = shard.framesByPageId.get(pageId);
      if (existing != null) {
        hits.incrementAndGet();
        existing.pin();
        return new PageHandle(this, existing);
      }

      misses.incrementAndGet();
      Frame frame = shard.claimFrame();
      if (frame.occupied()) {
        evict(frame, shard);
      }
      Page page = store.read(pageId);
      copySerialized(page, frame.buffer);
      shard.framesByPageId.put(pageId, frame);
      frame.load(pageId, page.type(), false);
      frame.pin();
      return new PageHandle(this, frame);
    } finally {
      shard.lock.unlock();
    }
  }

  /** Reads a page through the cache. */
  public Page read(long pageId) {
    try (PageHandle handle = pin(pageId)) {
      return handle.page();
    }
  }

  /** Writes a page into the cache and marks it dirty for flush/eviction. */
  public void write(Page page) {
    Shard shard = shard(page.pageId());
    shard.lock.lock();
    try {
      Frame frame = shard.framesByPageId.get(page.pageId());
      if (frame == null) {
        frame = shard.claimFrame();
        if (frame.occupied()) {
          evict(frame, shard);
        }
        shard.framesByPageId.put(page.pageId(), frame);
        frame.load(page.pageId(), page.type(), false);
      }
      copySerialized(page, frame.buffer);
      frame.type = page.type();
      frame.referenced = true;
      if (!frame.dirty()) {
        frame.markDirty();
        dirtyPages.incrementAndGet();
      }
    } finally {
      shard.lock.unlock();
    }
  }

  /** Allocates, initializes, and pins a new empty page of {@code type}. */
  public PageHandle newPage(PageType type) {
    long pageId = allocate(type);
    Page page = new Page(type, 0L, pageId, new byte[0]);
    write(page);
    return pin(pageId);
  }

  /** Releases a page handle. Dirty pages remain cached until flush or eviction. */
  public void unpin(PageHandle handle, boolean dirty) {
    handle.ensureOpen();
    Frame frame = handle.frame();
    Shard shard = shard(frame.pageId());
    shard.lock.lock();
    try {
      if (!frame.occupied() || shard.framesByPageId.get(frame.pageId()) != frame) {
        throw new IllegalStateException("page handle no longer belongs to this buffer pool");
      }
      if (dirty && !frame.dirty()) {
        frame.markDirty();
        dirtyPages.incrementAndGet();
      }
      frame.unpin();
      handle.markClosed();
    } finally {
      shard.lock.unlock();
    }
  }

  /** Flushes every dirty cached page and keeps clean pages resident. */
  public void flushDirtyPages() {
    for (Shard shard : shards) {
      shard.lock.lock();
      try {
        for (Frame frame : shard.ring) {
          if (frame.dirty()) {
            store.write(frame.page());
            frame.clearDirty();
            dirtyPages.decrementAndGet();
          }
        }
      } finally {
        shard.lock.unlock();
      }
    }
  }

  public long hits() {
    return hits.get();
  }

  public long misses() {
    return misses.get();
  }

  public long evictions() {
    return evictions.get();
  }

  public long dirtyPages() {
    return dirtyPages.get();
  }

  @Override
  public void close() {
    flushDirtyPages();
  }

  private void invalidate(long pageId) {
    Shard shard = shard(pageId);
    shard.lock.lock();
    try {
      Frame frame = shard.framesByPageId.remove(pageId);
      if (frame != null) {
        if (frame.pins != 0) {
          shard.framesByPageId.put(pageId, frame);
          throw new KvException(ErrorCode.INSUFFICIENT_RESOURCES, "cannot free pinned page");
        }
        if (frame.dirty()) {
          dirtyPages.decrementAndGet();
        }
        frame.clear();
      }
    } finally {
      shard.lock.unlock();
    }
  }

  private Shard shard(long pageId) {
    return shards[Math.floorMod(pageId, shards.length)];
  }

  private void evict(Frame frame, Shard shard) {
    if (frame.dirty()) {
      store.write(frame.page());
      frame.clearDirty();
      dirtyPages.decrementAndGet();
    }
    shard.framesByPageId.remove(frame.pageId());
    evictions.incrementAndGet();
    frame.clear();
  }

  static void copySerialized(Page page, ByteBuffer destination) {
    try (Arena arena = Arena.ofConfined()) {
      ByteBuffer source = page.serialize(arena).asByteBuffer();
      destination.clear();
      destination.put(source);
      destination.clear();
    }
  }

  static final class Frame {
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(Page.PAGE_SIZE);
    private long pageId = -1;
    private PageType type = PageType.META;
    private int pins;
    private boolean occupied;
    private boolean referenced;
    private boolean dirty;

    ByteBuffer buffer() {
      return buffer;
    }

    long pageId() {
      return pageId;
    }

    PageType type() {
      return type;
    }

    boolean occupied() {
      return occupied;
    }

    boolean dirty() {
      return dirty;
    }

    Page page() {
      ByteBuffer source = buffer.duplicate();
      source.clear();
      byte[] bytes = new byte[Page.PAGE_SIZE];
      source.get(bytes);
      return Page.deserialize(pageId, MemorySegment.ofArray(bytes));
    }

    void load(long pageId, PageType type, boolean dirty) {
      this.pageId = pageId;
      this.type = type;
      this.dirty = dirty;
      this.occupied = true;
      this.referenced = true;
      this.pins = 0;
    }

    void pin() {
      pins++;
      referenced = true;
    }

    void unpin() {
      if (pins <= 0) {
        throw new IllegalStateException("page is not pinned");
      }
      pins--;
    }

    void markDirty() {
      dirty = true;
    }

    void clearDirty() {
      dirty = false;
    }

    void clear() {
      pageId = -1;
      type = PageType.META;
      pins = 0;
      occupied = false;
      referenced = false;
      dirty = false;
    }
  }

  private static final class Shard {
    private final ReentrantLock lock = new ReentrantLock();
    private final HashMap<Long, Frame> framesByPageId = new HashMap<>();
    private final Frame[] ring;
    private int clockHand;

    Shard(int capacity) {
      this.ring = new Frame[capacity];
      for (int i = 0; i < capacity; i++) {
        ring[i] = new Frame();
      }
    }

    Frame claimFrame() {
      @Nullable Frame candidate = null;
      int scanned = 0;
      while (scanned < ring.length * 2) {
        Frame frame = ring[clockHand];
        clockHand = (clockHand + 1) % ring.length;
        if (!frame.occupied()) {
          return frame;
        }
        if (frame.pins == 0) {
          if (!frame.referenced) {
            return frame;
          }
          frame.referenced = false;
          candidate = frame;
        }
        scanned++;
      }
      if (candidate != null) {
        return candidate;
      }
      throw new KvException(ErrorCode.INSUFFICIENT_RESOURCES, "all buffer-pool pages are pinned");
    }
  }
}
