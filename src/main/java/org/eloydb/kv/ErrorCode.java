package org.eloydb.kv;

/** Stable error categories surfaced by the embeddable KV API. */
public enum ErrorCode {
  CORRUPTED_PAGE,
  SNAPSHOT_TOO_OLD,
  INSUFFICIENT_RESOURCES,
  IO_ERROR,
  INVALID_ARGUMENT,
  ENGINE_CLOSED
}
