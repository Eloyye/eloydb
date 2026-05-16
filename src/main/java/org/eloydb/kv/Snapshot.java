package org.eloydb.kv;

import java.util.Optional;

/**
 * Stable read view of the KV tree.
 *
 * <p>Example:
 *
 * <pre>{@code
 * try (Snapshot snapshot = engine.snapshot()) {
 *   Optional<byte[]> value = snapshot.get(key);
 * }
 * }</pre>
 */
public interface Snapshot extends AutoCloseable {
  /** Commit timestamp captured by this read view. */
  long commitTs();

  /** Returns a defensive copy of the value visible in this snapshot, if present. */
  Optional<byte[]> get(byte[] key);

  /** Scans visible keys in unsigned byte order over {@code [startInclusive, endExclusive)}. */
  Cursor scan(byte[] startInclusive, byte[] endExclusive);

  /** Releases this snapshot's live-reader slot. */
  @Override
  void close();
}
