package org.eloydb.kv.internal;

import java.util.Arrays;

public final class Operation {
  public enum Kind {
    PUT,
    DELETE
  }

  private final Kind kind;
  private final byte[] key;
  private final byte[] value;

  private Operation(Kind kind, byte[] key, byte[] value) {
    this.kind = kind;
    this.key = Arrays.copyOf(key, key.length);
    this.value = Arrays.copyOf(value, value.length);
  }

  public static Operation put(byte[] key, byte[] value) {
    return new Operation(Kind.PUT, key, value);
  }

  public static Operation delete(byte[] key) {
    return new Operation(Kind.DELETE, key, new byte[0]);
  }

  public Kind kind() {
    return kind;
  }

  public byte[] key() {
    return Arrays.copyOf(key, key.length);
  }

  public byte[] value() {
    return Arrays.copyOf(value, value.length);
  }

  public byte[] unsafeKey() {
    return key;
  }

  public byte[] unsafeValue() {
    return value;
  }
}
