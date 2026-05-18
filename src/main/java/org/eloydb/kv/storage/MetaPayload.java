package org.eloydb.kv.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encodes the engine-wide bookkeeping fields stored in {@link PageType#META} (page id 0).
 *
 * <p>Layout (within the page payload):
 *
 * <pre>
 *   u64 root_pid           // current published B+ tree root, 0 if no root yet
 *   u64 commit_ts          // monotonic commit counter reflected in the store
 *   u64 next_alloc_pid     // next page id to hand out when extending the file
 *   u64 freelist_head_pid  // head of the persisted free-list chain, 0 if empty
 *   u32 free_page_count    // total free pages reachable through the chain
 *   u32 reserved
 * </pre>
 */
public record MetaPayload(
    long rootPid, long commitTs, long nextAllocPid, long freelistHeadPid, int freePageCount) {

  static final int BYTES = 8 + 8 + 8 + 8 + 4 + 4;

  public byte[] encode() {
    byte[] payload = new byte[SlottedPage.PAYLOAD_BYTES];
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    buf.putLong(0, rootPid);
    buf.putLong(8, commitTs);
    buf.putLong(16, nextAllocPid);
    buf.putLong(24, freelistHeadPid);
    buf.putInt(32, freePageCount);
    return payload;
  }

  public static MetaPayload decode(byte[] payload) {
    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
    return new MetaPayload(
        buf.getLong(0), buf.getLong(8), buf.getLong(16), buf.getLong(24), buf.getInt(32));
  }
}
