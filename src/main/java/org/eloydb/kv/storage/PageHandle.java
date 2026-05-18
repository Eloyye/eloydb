package org.eloydb.kv.storage;

import java.nio.ByteBuffer;

/** A pinned buffer-pool page. The page buffer is valid until the handle is unpinned. */
public final class PageHandle implements AutoCloseable {
  private final BufferPool owner;
  private final BufferPool.Frame frame;
  private final ByteBuffer buffer;
  private boolean dirty;
  private boolean closed;

  PageHandle(BufferPool owner, BufferPool.Frame frame) {
    this.owner = owner;
    this.frame = frame;
    this.buffer = frame.buffer().duplicate();
    this.buffer.clear();
  }

  /** Stable page id for this pinned page. */
  public long pageId() {
    return frame.pageId();
  }

  /** Page type known to the buffer pool. */
  public PageType type() {
    return frame.type();
  }

  /** Mutable off-heap page bytes. Only use while the handle remains pinned. */
  public ByteBuffer buffer() {
    ensureOpen();
    return buffer;
  }

  /** Decodes the pinned frame into a validated page. */
  public Page page() {
    ensureOpen();
    return frame.page();
  }

  /** Replaces the pinned frame with {@code page} and marks it dirty. */
  public void write(Page page) {
    ensureOpen();
    if (page.pageId() != frame.pageId()) {
      throw new IllegalArgumentException("page id mismatch");
    }
    BufferPool.copySerialized(page, frame.buffer());
    dirty = true;
  }

  /** Marks this page dirty so close/unpin schedules it for checkpoint flushing. */
  public void markDirty() {
    ensureOpen();
    dirty = true;
  }

  @Override
  public void close() {
    if (!closed) {
      owner.unpin(this, dirty);
    }
  }

  BufferPool.Frame frame() {
    return frame;
  }

  void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("page handle is already closed");
    }
  }

  void markClosed() {
    closed = true;
  }
}
