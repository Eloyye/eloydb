package org.eloydb.kv;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Order-preserving encoders for primitive key fields. */
public final class KeyEncoding {
  private KeyEncoding() {}

  public static byte[] bool(boolean value) {
    return new byte[] {(byte) (value ? 1 : 0)};
  }

  public static byte[] int16(short value) {
    return ByteBuffer.allocate(Short.BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putShort((short) (value ^ 0x8000))
        .array();
  }

  public static byte[] int32(int value) {
    return ByteBuffer.allocate(Integer.BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value ^ 0x8000_0000)
        .array();
  }

  public static byte[] int64(long value) {
    return ByteBuffer.allocate(Long.BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value ^ 0x8000_0000_0000_0000L)
        .array();
  }

  public static byte[] float32(float value) {
    int bits = Float.floatToIntBits(value);
    int encoded = bits < 0 ? ~bits : bits ^ 0x8000_0000;
    return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN).putInt(encoded).array();
  }

  public static byte[] float64(double value) {
    long bits = Double.doubleToLongBits(value);
    long encoded = bits < 0 ? ~bits : bits ^ 0x8000_0000_0000_0000L;
    return ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN).putLong(encoded).array();
  }

  public static byte[] text(String value) {
    return escaped(value.getBytes(StandardCharsets.UTF_8));
  }

  public static byte[] bytea(byte[] value) {
    return escaped(value);
  }

  /** Concatenates already-encoded fields into a composite key. */
  public static byte[] composite(byte[]... fields) {
    int size = 0;
    for (byte[] field : fields) {
      size += field.length;
    }
    ByteBuffer buffer = ByteBuffer.allocate(size);
    for (byte[] field : fields) {
      buffer.put(field);
    }
    return buffer.array();
  }

  private static byte[] escaped(byte[] value) {
    var out = new ByteArrayOutputStream(value.length + 2);
    for (byte b : value) {
      out.write(b);
      if (b == 0) {
        out.write(0xff);
      }
    }
    out.write(0);
    out.write(0);
    return out.toByteArray();
  }
}
