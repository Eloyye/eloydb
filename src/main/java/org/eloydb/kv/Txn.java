package org.eloydb.kv;

import java.util.Optional;

/**
 * Single-writer transaction.
 *
 * <p>Example:
 *
 * <pre>{@code
 * try (Txn txn = engine.beginWrite()) {
 *   txn.put(key, value);
 *   txn.commit();
 * }
 * }</pre>
 */
public interface Txn extends AutoCloseable {
  /** Reads through this transaction, including its uncommitted writes. */
  Optional<byte[]> get(byte[] key);

  void put(byte[] key, byte[] value);

  void delete(byte[] key);

  /**
   * Scans through this transaction's pending writes over {@code [startInclusive, endExclusive)}.
   */
  Cursor scan(byte[] startInclusive, byte[] endExclusive);

  /** Returns a stable read view that includes this transaction's pending writes. */
  Snapshot snapshot();

  /** Durably commits all pending writes and closes the transaction. */
  CommitResult commit();

  /** Discards pending writes and closes the transaction. */
  void abort();

  /** Aborts the transaction when it is still open. */
  @Override
  void close();
}
