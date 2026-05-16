package org.eloydb.kv;

import java.nio.ByteBuffer;
import java.util.Arrays;

final class Bytes implements Comparable<Bytes> {
  static final Bytes EMPTY = new Bytes(new byte[0]);

  private final byte[] value;

  private Bytes(byte[] value) {
    this.value = value;
  }

  static Bytes copyOf(byte[] value) {
    return value.length == 0 ? EMPTY : new Bytes(Arrays.copyOf(value, value.length));
  }

  byte[] copy() {
    return Arrays.copyOf(value, value.length);
  }

  ByteBuffer asReadOnlyBuffer() {
    return ByteBuffer.wrap(value).asReadOnlyBuffer();
  }

  int length() {
    return value.length;
  }

  byte[] unsafeArray() {
    return value;
  }

  @Override
  public int compareTo(Bytes other) {
    return Arrays.compareUnsigned(value, other.value);
  }

  @Override
  public boolean equals(Object object) {
    return object instanceof Bytes other && Arrays.equals(value, other.value);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(value);
  }
}
