package org.eloydb.kv;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Fixed-size 8 KiB page with an M1 header and CRC32C protection. */
public final class Page {
  public static final int PAGE_SIZE = 8192;
  public static final int HEADER_SIZE = 4 + 1 + 1 + 2 + 8 + 8 + 4;
  private static final int MAGIC = 0x454c5047;
  private static final short VERSION = 1;
  private static final int CRC_OFFSET = 4 + 1 + 1 + 2 + 8 + 8;

  private final PageType type;
  private final long lsn;
  private final long pageId;
  private final byte[] payload;

  public Page(PageType type, long lsn, long pageId, byte[] payload) {
    if (payload.length > PAGE_SIZE - HEADER_SIZE) {
      throw new IllegalArgumentException("payload exceeds page capacity");
    }
    this.type = type;
    this.lsn = lsn;
    this.pageId = pageId;
    this.payload = Arrays.copyOf(payload, payload.length);
  }

  public PageType type() {
    return type;
  }

  public long lsn() {
    return lsn;
  }

  public long pageId() {
    return pageId;
  }

  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }

  /** Serializes this page into an arena-owned 8 KiB segment with header and CRC populated. */
  public MemorySegment serialize(Arena arena) {
    byte[] bytes = new byte[PAGE_SIZE];
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    buffer
        .putInt(MAGIC)
        .put(type.code())
        .put((byte) 0)
        .putShort(VERSION)
        .putLong(lsn)
        .putLong(pageId);
    System.arraycopy(payload, 0, bytes, HEADER_SIZE, payload.length);
    buffer.putInt(CRC_OFFSET, crc(bytes));
    MemorySegment segment = arena.allocate(PAGE_SIZE, 8);
    segment.copyFrom(MemorySegment.ofArray(bytes));
    return segment;
  }

  /** Validates and deserializes an 8 KiB segment for {@code expectedPageId}. */
  public static Page deserialize(long expectedPageId, MemorySegment segment) {
    if (segment.byteSize() != PAGE_SIZE) {
      throw new PageCorruptedException(expectedPageId, "invalid page size");
    }
    byte[] bytes = segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    int magic = buffer.getInt();
    PageType type = PageType.fromCode(buffer.get());
    byte flags = buffer.get();
    short version = buffer.getShort();
    long lsn = buffer.getLong();
    long pageId = buffer.getLong();
    int expectedCrc = buffer.getInt();
    if (magic != MAGIC || flags != 0 || version != VERSION || pageId != expectedPageId) {
      throw new PageCorruptedException(expectedPageId, "page header validation failed");
    }
    int actualCrc = crc(bytes);
    if (actualCrc != expectedCrc) {
      throw new PageCorruptedException(expectedPageId, "page CRC mismatch");
    }
    return new Page(type, lsn, pageId, Arrays.copyOfRange(bytes, HEADER_SIZE, PAGE_SIZE));
  }

  private static int crc(byte[] source) {
    byte[] bytes = Arrays.copyOf(source, source.length);
    ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(CRC_OFFSET, 0);
    CRC32C crc = new CRC32C();
    crc.update(bytes, 0, bytes.length);
    return (int) crc.getValue();
  }
}
