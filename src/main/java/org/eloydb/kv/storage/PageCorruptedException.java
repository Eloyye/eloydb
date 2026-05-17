package org.eloydb.kv.storage;

import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KvException;

/** Raised when a page fails magic, identity, or CRC validation. */
public final class PageCorruptedException extends KvException {
  private final long pageId;

  public PageCorruptedException(long pageId, String message) {
    super(ErrorCode.CORRUPTED_PAGE, message + " (pageId=" + pageId + ")");
    this.pageId = pageId;
  }

  public long pageId() {
    return pageId;
  }
}
