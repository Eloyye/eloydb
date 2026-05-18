package org.eloydb.kv.engine;

import java.util.ArrayList;
import java.util.Optional;
import java.util.TreeMap;
import org.eloydb.kv.Cursor;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KeyValue;
import org.eloydb.kv.KvException;
import org.eloydb.kv.btree.CowBTree;
import org.eloydb.kv.internal.Bytes;
import org.eloydb.kv.internal.Operation;

/** Utilities for materialised, ordered key/value overlays. */
@SuppressWarnings("NonApiType")
public final class OverlayView {
  private OverlayView() {}

  public static TreeMap<Bytes, byte[]> materializeAll(CowBTree tree, long root) {
    return materializeRange(tree, root, new byte[0], new byte[] {(byte) 0xff});
  }

  public static TreeMap<Bytes, byte[]> materializeRange(
      CowBTree tree, long root, byte[] startInclusive, byte[] endExclusive) {
    var overlay = new TreeMap<Bytes, byte[]>();
    for (KeyValue row : tree.scan(root, startInclusive, endExclusive)) {
      overlay.put(Bytes.copyOf(row.key()), row.value());
    }
    return overlay;
  }

  public static void apply(TreeMap<Bytes, byte[]> overlay, Operation operation) {
    if (operation.kind() == Operation.Kind.PUT) {
      overlay.put(Bytes.copyOf(operation.unsafeKey()), operation.value());
      return;
    }
    overlay.remove(Bytes.copyOf(operation.unsafeKey()));
  }

  public static Optional<byte[]> get(TreeMap<Bytes, byte[]> overlay, byte[] key) {
    byte[] value = overlay.get(Bytes.copyOf(key));
    return value == null
        ? Optional.empty()
        : Optional.of(java.util.Arrays.copyOf(value, value.length));
  }

  public static Cursor cursorFor(TreeMap<Bytes, byte[]> overlay, byte[] start, byte[] end) {
    Bytes lo = Bytes.copyOf(start);
    Bytes hi = Bytes.copyOf(end);
    if (lo.compareTo(hi) > 0) {
      throw new KvException(ErrorCode.INVALID_ARGUMENT, "scan start must be <= end");
    }
    var rows = new ArrayList<KeyValue>();
    overlay
        .subMap(lo, true, hi, false)
        .forEach((key, value) -> rows.add(new KeyValue(key.copy(), value)));
    return new ListCursor(rows);
  }
}
