package org.eloydb.kv;

/**
 * Forward-only cursor over sorted key/value pairs.
 *
 * <p>Example:
 *
 * <pre>{@code
 * try (Snapshot snapshot = engine.snapshot();
 *     Cursor cursor = snapshot.scan(start, end)) {
 *   while (cursor.next()) {
 *     KeyValue row = cursor.current();
 *   }
 * }
 * }</pre>
 */
public interface Cursor extends AutoCloseable {
  /** Advances to the next row, returning {@code false} after the last row. */
  boolean next();

  /** Returns the row at the current cursor position. */
  KeyValue current();

  @Override
  void close();
}
