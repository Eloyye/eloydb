package org.eloydb.kv.engine;

import java.util.List;
import org.eloydb.kv.Cursor;
import org.eloydb.kv.KeyValue;

public final class ListCursor implements Cursor {
  private final List<KeyValue> rows;
  private int index = -1;
  private boolean closed;

  public ListCursor(List<KeyValue> rows) {
    this.rows = List.copyOf(rows);
  }

  @Override
  public boolean next() {
    ensureOpen();
    if (index + 1 >= rows.size()) {
      return false;
    }
    index++;
    return true;
  }

  @Override
  public KeyValue current() {
    ensureOpen();
    if (index < 0 || index >= rows.size()) {
      throw new IllegalStateException("cursor is not positioned on a row");
    }
    return rows.get(index);
  }

  @Override
  public void close() {
    closed = true;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("cursor is closed");
    }
  }
}
