package org.eloydb.kv.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KvException;

/**
 * Slotted-payload codec for {@link PageType#LEAF} and {@link PageType#INTERNAL} pages.
 *
 * <p>Each page payload (8 KiB minus the {@link Page} header) is split into a forward-growing slot
 * array and a backward-growing cell heap. Slots are kept sorted by encoded key so binary search is
 * possible directly on the serialized payload.
 *
 * <p>Leaf layout (within payload):
 *
 * <ul>
 *   <li>{@code u16 slot_count}
 *   <li>{@code u16 cell_heap_start}
 *   <li>{@code slots[slot_count]} of {@code u16 cell_offset}
 *   <li>free space
 *   <li>cells (grown backward from end). Each leaf cell:
 *       <ul>
 *         <li>{@code u16 key_len}
 *         <li>{@code u16 value_marker}: high bit set ⇒ overflow; otherwise low-15 bits = inline
 *             value length
 *         <li>if overflow: {@code u32 total_value_len}, {@code u64 overflow_head_pid}
 *         <li>{@code key_len} key bytes
 *         <li>if inline: {@code value_len} value bytes
 *       </ul>
 * </ul>
 *
 * <p>Internal layout (within payload):
 *
 * <ul>
 *   <li>{@code u16 slot_count}
 *   <li>{@code u16 cell_heap_start}
 *   <li>{@code u32 reserved} (alignment padding)
 *   <li>{@code u64 leftmost_child_pid}
 *   <li>{@code slots[slot_count]} of {@code u16 cell_offset}
 *   <li>free space
 *   <li>cells. Each internal cell:
 *       <ul>
 *         <li>{@code u16 key_len}
 *         <li>{@code u64 child_pid}
 *         <li>{@code key_len} key bytes
 *       </ul>
 * </ul>
 *
 * <p>Single-shot encode/decode: callers build a {@link LeafBuilder} or {@link InternalBuilder} and
 * call {@code build()} to obtain the payload bytes; lookups call the static {@code decode...}
 * helpers.
 */
public final class SlottedPage {
  public static final int PAYLOAD_BYTES = Page.PAGE_SIZE - Page.HEADER_SIZE;

  /** Maximum size of an inline (non-overflow) leaf value. */
  public static final int MAX_INLINE_VALUE_LEN = 1500;

  /** Maximum supported key length. Keys must always fit inline. */
  public static final int MAX_KEY_LEN = 1024;

  /** Largest cell size that should ever be packed into a single page before forcing a split. */
  private static final int MAX_LEAF_CELL_BYTES = 4 + MAX_KEY_LEN + MAX_INLINE_VALUE_LEN;

  private static final int LEAF_HEADER_BYTES = 4;
  private static final int INTERNAL_HEADER_BYTES = 16;
  private static final int SLOT_BYTES = 2;
  private static final int OVERFLOW_MARKER_MASK = 0x8000;

  private SlottedPage() {}

  /* --------------------------------------------------------------------- *
   *                              Leaf                                     *
   * --------------------------------------------------------------------- */

  public record LeafEntry(
      byte[] key, byte[] value, boolean overflow, long overflowHead, int totalValueLength) {
    public LeafEntry {
      key = Arrays.copyOf(key, key.length);
      value = Arrays.copyOf(value, value.length);
    }

    public static LeafEntry inline(byte[] key, byte[] value) {
      return new LeafEntry(key, value, false, 0L, value.length);
    }

    public static LeafEntry overflow(byte[] key, long head, int totalLength) {
      return new LeafEntry(key, new byte[0], true, head, totalLength);
    }
  }

  /** Builds and serializes a leaf payload from a sorted list of entries. */
  public static final class LeafBuilder {
    private final List<LeafEntry> entries = new ArrayList<>();

    public LeafBuilder add(LeafEntry entry) {
      entries.add(entry);
      return this;
    }

    public int size() {
      return entries.size();
    }

    public List<LeafEntry> entries() {
      return List.copyOf(entries);
    }

    /** Returns the byte cost of a single leaf entry (cell + slot). */
    public static int costOf(LeafEntry entry) {
      return leafCellBytes(entry) + SLOT_BYTES;
    }

    /** Returns whether this builder's contents fit in a single leaf payload. */
    public boolean fits() {
      return computeBytes() <= PAYLOAD_BYTES;
    }

    private int computeBytes() {
      int total = LEAF_HEADER_BYTES;
      for (LeafEntry e : entries) {
        total += leafCellBytes(e) + SLOT_BYTES;
      }
      return total;
    }

    public byte[] build() {
      if (!fits()) {
        throw new IllegalStateException("leaf entries exceed page capacity");
      }
      byte[] payload = new byte[PAYLOAD_BYTES];
      ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
      int slotCount = entries.size();
      int heapEnd = PAYLOAD_BYTES;
      for (int i = slotCount - 1; i >= 0; i--) {
        LeafEntry e = entries.get(i);
        int cellBytes = leafCellBytes(e);
        heapEnd -= cellBytes;
        writeLeafCell(payload, heapEnd, e);
        buf.putShort(LEAF_HEADER_BYTES + i * SLOT_BYTES, (short) heapEnd);
      }
      buf.putShort(0, (short) slotCount);
      buf.putShort(2, (short) heapEnd);
      return payload;
    }
  }

  public static int leafCellBytes(LeafEntry entry) {
    int base = 2 /* key_len */ + 2 /* value_marker */ + entry.key().length;
    if (entry.overflow()) {
      return base + 4 /* total_len */ + 8 /* overflow_pid */;
    }
    return base + entry.value().length;
  }

  /** Decodes all leaf entries from a payload. */
  public static List<LeafEntry> decodeLeaf(byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    int slotCount = Short.toUnsignedInt(buf.getShort(0));
    List<LeafEntry> out = new ArrayList<>(slotCount);
    for (int i = 0; i < slotCount; i++) {
      int slotOffset = LEAF_HEADER_BYTES + i * SLOT_BYTES;
      int cellOffset = Short.toUnsignedInt(buf.getShort(slotOffset));
      out.add(readLeafCell(payload, cellOffset));
    }
    return out;
  }

  /** Returns the smallest (i.e. first) key in a leaf payload. */
  public static byte[] firstLeafKey(byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    int slotCount = Short.toUnsignedInt(buf.getShort(0));
    if (slotCount == 0) {
      throw new KvException(ErrorCode.CORRUPTED_PAGE, "leaf is empty");
    }
    int cellOffset = Short.toUnsignedInt(buf.getShort(LEAF_HEADER_BYTES));
    int keyLen = Short.toUnsignedInt(buf.getShort(cellOffset));
    int keyStart = cellOffset + 4;
    boolean overflow =
        (Short.toUnsignedInt(buf.getShort(cellOffset + 2)) & OVERFLOW_MARKER_MASK) != 0;
    if (overflow) {
      keyStart += 12;
    }
    return Arrays.copyOfRange(payload, keyStart, keyStart + keyLen);
  }

  private static void writeLeafCell(byte[] payload, int offset, LeafEntry entry) {
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    byte[] key = entry.key();
    if (key.length > MAX_KEY_LEN) {
      throw new IllegalArgumentException("key length " + key.length + " exceeds " + MAX_KEY_LEN);
    }
    buf.putShort(offset, (short) key.length);
    int cursor = offset + 2;
    if (entry.overflow()) {
      buf.putShort(cursor, (short) OVERFLOW_MARKER_MASK);
      cursor += 2;
      buf.putInt(cursor, entry.totalValueLength());
      cursor += 4;
      buf.putLong(cursor, entry.overflowHead());
      cursor += 8;
      System.arraycopy(key, 0, payload, cursor, key.length);
    } else {
      byte[] value = entry.value();
      if (value.length > MAX_INLINE_VALUE_LEN) {
        throw new IllegalArgumentException(
            "inline value length " + value.length + " exceeds " + MAX_INLINE_VALUE_LEN);
      }
      buf.putShort(cursor, (short) value.length);
      cursor += 2;
      System.arraycopy(key, 0, payload, cursor, key.length);
      cursor += key.length;
      System.arraycopy(value, 0, payload, cursor, value.length);
    }
  }

  private static LeafEntry readLeafCell(byte[] payload, int offset) {
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    int keyLen = Short.toUnsignedInt(buf.getShort(offset));
    int marker = Short.toUnsignedInt(buf.getShort(offset + 2));
    int cursor = offset + 4;
    if ((marker & OVERFLOW_MARKER_MASK) != 0) {
      int totalLen = buf.getInt(cursor);
      cursor += 4;
      long head = buf.getLong(cursor);
      cursor += 8;
      byte[] key = Arrays.copyOfRange(payload, cursor, cursor + keyLen);
      return LeafEntry.overflow(key, head, totalLen);
    }
    int valueLen = marker;
    byte[] key = Arrays.copyOfRange(payload, cursor, cursor + keyLen);
    cursor += keyLen;
    byte[] value = Arrays.copyOfRange(payload, cursor, cursor + valueLen);
    return LeafEntry.inline(key, value);
  }

  /** Maximum addressable size of a leaf cell. */
  public static int maxLeafCellBytes() {
    return MAX_LEAF_CELL_BYTES;
  }

  /* --------------------------------------------------------------------- *
   *                            Internal                                   *
   * --------------------------------------------------------------------- */

  public record InternalEntry(byte[] separator, long childPid) {
    public InternalEntry {
      separator = Arrays.copyOf(separator, separator.length);
    }
  }

  /** Builds and serializes an internal payload from a leftmost child + sorted (sep, child) list. */
  public static final class InternalBuilder {
    private final long leftmostChild;
    private final List<InternalEntry> entries = new ArrayList<>();

    public InternalBuilder(long leftmostChild) {
      this.leftmostChild = leftmostChild;
    }

    public InternalBuilder add(InternalEntry entry) {
      entries.add(entry);
      return this;
    }

    public int size() {
      return entries.size();
    }

    public long leftmostChild() {
      return leftmostChild;
    }

    public List<InternalEntry> entries() {
      return List.copyOf(entries);
    }

    public static int costOf(InternalEntry entry) {
      return internalCellBytes(entry) + SLOT_BYTES;
    }

    public boolean fits() {
      int total = INTERNAL_HEADER_BYTES;
      for (InternalEntry e : entries) {
        total += internalCellBytes(e) + SLOT_BYTES;
      }
      return total <= PAYLOAD_BYTES;
    }

    public byte[] build() {
      if (!fits()) {
        throw new IllegalStateException("internal entries exceed page capacity");
      }
      byte[] payload = new byte[PAYLOAD_BYTES];
      ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
      int slotCount = entries.size();
      int heapEnd = PAYLOAD_BYTES;
      for (int i = slotCount - 1; i >= 0; i--) {
        InternalEntry e = entries.get(i);
        int cellBytes = internalCellBytes(e);
        heapEnd -= cellBytes;
        writeInternalCell(payload, heapEnd, e);
        buf.putShort(INTERNAL_HEADER_BYTES + i * SLOT_BYTES, (short) heapEnd);
      }
      buf.putShort(0, (short) slotCount);
      buf.putShort(2, (short) heapEnd);
      buf.putLong(8, leftmostChild);
      return payload;
    }
  }

  public static int internalCellBytes(InternalEntry entry) {
    return 2 /* key_len */ + 8 /* child_pid */ + entry.separator().length;
  }

  public static long internalLeftmostChild(byte[] payload) {
    return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getLong(8);
  }

  public static List<InternalEntry> decodeInternal(byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    int slotCount = Short.toUnsignedInt(buf.getShort(0));
    List<InternalEntry> out = new ArrayList<>(slotCount);
    for (int i = 0; i < slotCount; i++) {
      int cellOffset = Short.toUnsignedInt(buf.getShort(INTERNAL_HEADER_BYTES + i * SLOT_BYTES));
      int keyLen = Short.toUnsignedInt(buf.getShort(cellOffset));
      long childPid = buf.getLong(cellOffset + 2);
      byte[] sep = Arrays.copyOfRange(payload, cellOffset + 10, cellOffset + 10 + keyLen);
      out.add(new InternalEntry(sep, childPid));
    }
    return out;
  }

  private static void writeInternalCell(byte[] payload, int offset, InternalEntry entry) {
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    byte[] sep = entry.separator();
    if (sep.length > MAX_KEY_LEN) {
      throw new IllegalArgumentException(
          "separator length " + sep.length + " exceeds " + MAX_KEY_LEN);
    }
    buf.putShort(offset, (short) sep.length);
    buf.putLong(offset + 2, entry.childPid());
    System.arraycopy(sep, 0, payload, offset + 10, sep.length);
  }
}
