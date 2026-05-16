package org.eloydb.kv.engine;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import org.eloydb.kv.Cursor;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KeyValue;
import org.eloydb.kv.KvException;
import org.eloydb.kv.internal.Bytes;

/** Cursor factories for engine-owned sorted views. */
@SuppressWarnings("NonApiType")
public final class Cursors {
  private Cursors() {}

  public static Cursor forMap(
      TreeMap<Bytes, byte[]> map, Bytes startInclusive, Bytes endExclusive) {
    if (startInclusive.compareTo(endExclusive) > 0) {
      throw new KvException(ErrorCode.INVALID_ARGUMENT, "scan start must be <= end");
    }
    var rows = new ArrayList<KeyValue>();
    for (Map.Entry<Bytes, byte[]> entry :
        map.subMap(startInclusive, true, endExclusive, false).entrySet()) {
      rows.add(new KeyValue(entry.getKey().copy(), entry.getValue()));
    }
    return new ListCursor(rows);
  }
}
