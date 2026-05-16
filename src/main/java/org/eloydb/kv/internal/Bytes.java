package org.eloydb.kv.internal;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class Bytes implements Comparable<Bytes> {
  public static final Bytes EMPTY = new Bytes(new byte[0]);

  private final byte[] value;

  private Bytes(byte[] value) {
    this.value = value;
  }

  public static Bytes copyOf(byte[] value) {
    return value.length == 0 ? EMPTY : new Bytes(Arrays.copyOf(value, value.length));
  }

  public byte[] copy() {
    return Arrays.copyOf(value, value.length);
  }

  public ByteBuffer asReadOnlyBuffer() {
    return ByteBuffer.wrap(value).asReadOnlyBuffer();
  }

  public int length() {
    return value.length;
  }

  public byte[] unsafeArray() {
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
