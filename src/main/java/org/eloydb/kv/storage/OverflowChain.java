package org.eloydb.kv.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KvException;

/**
 * Read/write helper for {@link PageType#OVERFLOW} page chains.
 *
 * <p>A value too large to fit inline in a leaf cell is split across linked overflow pages. Each
 * overflow page payload is:
 *
 * <pre>
 *   u64 next_pid     // 0 ⇒ tail
 *   u32 chunk_len    // bytes of value held in this page
 *   chunk_len bytes  // payload chunk
 * </pre>
 */
public final class OverflowChain {
  private static final int HEADER_BYTES = 12;
  public static final int CHUNK_CAPACITY = SlottedPage.PAYLOAD_BYTES - HEADER_BYTES;

  private OverflowChain() {}

  /** Writes {@code value} as a chain of overflow pages, returning the head page id. */
  public static long write(PageStore store, long lsn, byte[] value) {
    if (value.length == 0) {
      throw new IllegalArgumentException("overflow chains must hold ≥1 byte");
    }
    int chunks = (value.length + CHUNK_CAPACITY - 1) / CHUNK_CAPACITY;
    long[] ids = new long[chunks];
    for (int i = 0; i < chunks; i++) {
      ids[i] = store.allocate(PageType.OVERFLOW);
    }
    for (int i = 0; i < chunks; i++) {
      int start = i * CHUNK_CAPACITY;
      int end = Math.min(value.length, start + CHUNK_CAPACITY);
      int chunkLen = end - start;
      byte[] payload = new byte[SlottedPage.PAYLOAD_BYTES];
      ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
      buf.putLong(0, i + 1 < chunks ? ids[i + 1] : 0L);
      buf.putInt(8, chunkLen);
      System.arraycopy(value, start, payload, HEADER_BYTES, chunkLen);
      store.write(new Page(PageType.OVERFLOW, lsn, ids[i], payload));
    }
    return ids[0];
  }

  /** Reads a value previously written by {@link #write(PageStore, long, byte[])}. */
  public static byte[] read(PageStore store, long head, int totalLength) {
    byte[] out = new byte[totalLength];
    int written = 0;
    long cursor = head;
    while (cursor != 0L) {
      Page page = store.read(cursor);
      if (page.type() != PageType.OVERFLOW) {
        throw new KvException(
            ErrorCode.CORRUPTED_PAGE,
            "expected OVERFLOW page at " + cursor + " got " + page.type());
      }
      byte[] payload = page.payload();
      ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
      long next = buf.getLong(0);
      int chunkLen = buf.getInt(8);
      if (chunkLen < 0 || chunkLen > CHUNK_CAPACITY || written + chunkLen > totalLength) {
        throw new KvException(
            ErrorCode.CORRUPTED_PAGE,
            "overflow chunk length " + chunkLen + " invalid at " + cursor);
      }
      System.arraycopy(payload, HEADER_BYTES, out, written, chunkLen);
      written += chunkLen;
      cursor = next;
    }
    if (written != totalLength) {
      throw new KvException(
          ErrorCode.CORRUPTED_PAGE,
          "overflow chain produced " + written + " bytes; expected " + totalLength);
    }
    return out;
  }

  /** Frees every page in the chain rooted at {@code head}. */
  public static void freeChain(PageStore store, long head) {
    long cursor = head;
    while (cursor != 0L) {
      Page page = store.read(cursor);
      long next = ByteBuffer.wrap(page.payload()).order(ByteOrder.BIG_ENDIAN).getLong(0);
      store.free(cursor);
      cursor = next;
    }
  }
}
