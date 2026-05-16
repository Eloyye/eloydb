package org.eloydb.kv.storage;

/** Raised when a page fails magic, identity, or CRC validation. */
public final class PageCorruptedException extends RuntimeException {
  private final long pageId;

  public PageCorruptedException(long pageId, String message) {
    super(message + " (pageId=" + pageId + ")");
    this.pageId = pageId;
  }

  public long pageId() {
    return pageId;
  }
}
