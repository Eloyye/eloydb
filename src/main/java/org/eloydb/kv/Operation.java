package org.eloydb.kv;

import java.util.Arrays;

final class Operation {
  enum Kind {
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

  static Operation put(byte[] key, byte[] value) {
    return new Operation(Kind.PUT, key, value);
  }

  static Operation delete(byte[] key) {
    return new Operation(Kind.DELETE, key, new byte[0]);
  }

  Kind kind() {
    return kind;
  }

  byte[] key() {
    return Arrays.copyOf(key, key.length);
  }

  byte[] value() {
    return Arrays.copyOf(value, value.length);
  }

  byte[] unsafeKey() {
    return key;
  }

  byte[] unsafeValue() {
    return value;
  }
}
