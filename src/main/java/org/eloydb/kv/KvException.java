package org.eloydb.kv;

/** Base exception for EloyDB KV failures. */
public final class KvException extends RuntimeException {
  private final ErrorCode errorCode;

  public KvException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public KvException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  /** Stable category for programmatic error handling. */
  public ErrorCode errorCode() {
    return errorCode;
  }
}
