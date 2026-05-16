package org.eloydb.kv;

import java.util.Arrays;

/** Immutable key/value pair returned by scans. */
public final class KeyValue {
  private final byte[] key;
  private final byte[] value;

  public KeyValue(byte[] key, byte[] value) {
    this.key = Arrays.copyOf(key, key.length);
    this.value = Arrays.copyOf(value, value.length);
  }

  /** Returns a defensive copy of the key. */
  public byte[] key() {
    return Arrays.copyOf(key, key.length);
  }

  /** Returns a defensive copy of the value. */
  public byte[] value() {
    return Arrays.copyOf(value, value.length);
  }
}
