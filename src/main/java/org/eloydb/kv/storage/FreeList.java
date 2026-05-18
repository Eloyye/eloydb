package org.eloydb.kv.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KvException;

final class FreeList {
  private static final int HEADER_BYTES = Long.BYTES + Integer.BYTES;
  private static final int ENTRY_BYTES = Long.BYTES;
  private static final int CAPACITY = (SlottedPage.PAYLOAD_BYTES - HEADER_BYTES) / ENTRY_BYTES;

  private final LongFunction<Page> readPage;
  private final Consumer<Page> writePage;
  private final LongSupplier allocateFreelistPageId;

  private long headPid;
  private int pageCount;

  FreeList(
      long headPid,
      int pageCount,
      LongFunction<Page> readPage,
      Consumer<Page> writePage,
      LongSupplier allocateFreelistPageId) {
    this.headPid = headPid;
    this.pageCount = pageCount;
    this.readPage = readPage;
    this.writePage = writePage;
    this.allocateFreelistPageId = allocateFreelistPageId;
  }

  long headPid() {
    return headPid;
  }

  int pageCount() {
    return pageCount;
  }

  boolean canPopFor(PageType type) {
    return headPid != 0L && type != PageType.FREELIST;
  }

  long pop() {
    Page head = readHead();
    byte[] payload = head.payload();
    ByteBuffer buf = wrap(payload);
    long next = buf.getLong(0);
    int count = buf.getInt(8);
    if (count <= 0) {
      long reused = headPid;
      headPid = next;
      decrementCount();
      return reused;
    }

    int slot = count - 1;
    long pid = buf.getLong(HEADER_BYTES + slot * ENTRY_BYTES);
    buf.putInt(8, count - 1);
    decrementCount();
    writePage.accept(new Page(PageType.FREELIST, head.lsn(), headPid, payload));
    return pid;
  }

  void push(long pageId) {
    if (headPid == 0L) {
      prependHead(0L, pageId);
      return;
    }

    Page head = readHead();
    byte[] payload = head.payload();
    ByteBuffer buf = wrap(payload);
    int count = buf.getInt(8);
    if (count >= CAPACITY) {
      prependHead(headPid, pageId);
      return;
    }

    buf.putLong(HEADER_BYTES + count * ENTRY_BYTES, pageId);
    buf.putInt(8, count + 1);
    writePage.accept(new Page(PageType.FREELIST, head.lsn(), headPid, payload));
    pageCount++;
  }

  List<Long> contents() {
    List<Long> ids = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    long cursor = headPid;
    while (cursor != 0L) {
      if (!seen.add(cursor)) {
        throw new KvException(ErrorCode.CORRUPTED_PAGE, "free-list cycle at " + cursor);
      }
      Page page = readPage.apply(cursor);
      ByteBuffer buf = wrap(page.payload());
      long next = buf.getLong(0);
      int count = buf.getInt(8);
      for (int i = 0; i < count; i++) {
        ids.add(buf.getLong(HEADER_BYTES + i * ENTRY_BYTES));
      }
      cursor = next;
    }
    return ids;
  }

  private Page readHead() {
    Page head = readPage.apply(headPid);
    if (head.type() != PageType.FREELIST) {
      throw new KvException(
          ErrorCode.CORRUPTED_PAGE, "free-list page " + headPid + " has wrong type");
    }
    return head;
  }

  private void prependHead(long nextHeadPid, long pageId) {
    long newHeadPid = allocateFreelistPageId.getAsLong();
    byte[] payload = new byte[SlottedPage.PAYLOAD_BYTES];
    ByteBuffer buf = wrap(payload);
    buf.putLong(0, nextHeadPid);
    buf.putInt(8, 1);
    buf.putLong(HEADER_BYTES, pageId);
    writePage.accept(new Page(PageType.FREELIST, 0L, newHeadPid, payload));
    headPid = newHeadPid;
    pageCount++;
  }

  private void decrementCount() {
    pageCount = Math.max(0, pageCount - 1);
  }

  private static ByteBuffer wrap(byte[] payload) {
    return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
  }
}
