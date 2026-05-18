package org.eloydb.kv.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.eloydb.kv.Config;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KvException;
import org.eloydb.kv.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BufferPoolTest {
  @TempDir java.nio.file.Path tempDir;

  @Test
  void pinHitMissAndUnpin() {
    Metrics metrics = new Metrics();
    try (PageStore store = PageStore.open(tempDir, metrics);
        BufferPool pool = new BufferPool(config(2, 1), store, metrics)) {
      long pageId = store.allocate(PageType.LEAF);
      store.write(new Page(PageType.LEAF, 1L, pageId, payload(12)));

      try (PageHandle first = pool.pin(pageId)) {
        assertThat(first.page().payload()[0]).isEqualTo((byte) 12);
      }
      try (PageHandle second = pool.pin(pageId)) {
        assertThat(second.page().payload()[0]).isEqualTo((byte) 12);
      }

      assertThat(metrics.snapshot())
          .containsEntry("bufferpool.misses", 1L)
          .containsEntry("bufferpool.hits", 1L);
    }
  }

  @Test
  void evictionNeverSelectsPinnedPages() {
    Metrics metrics = new Metrics();
    try (PageStore store = PageStore.open(tempDir, metrics);
        BufferPool pool = new BufferPool(config(1, 1), store, metrics)) {
      long page1 = store.allocate(PageType.LEAF);
      long page2 = store.allocate(PageType.LEAF);
      store.write(new Page(PageType.LEAF, 1L, page1, payload(1)));
      store.write(new Page(PageType.LEAF, 1L, page2, payload(2)));

      try (PageHandle pinned = pool.pin(page1)) {
        assertThatThrownBy(() -> pool.pin(page2))
            .isInstanceOf(KvException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INSUFFICIENT_RESOURCES);
        assertThat(pinned.pageId()).isEqualTo(page1);
      }
    }
  }

  @Test
  void clockEvictsUnpinnedPageAndFlushesDirtyPage() {
    Metrics metrics = new Metrics();
    try (PageStore store = PageStore.open(tempDir, metrics);
        BufferPool pool = new BufferPool(config(1, 1), store, metrics)) {
      long page1 = pool.allocate(PageType.LEAF);
      pool.write(new Page(PageType.LEAF, 1L, page1, payload(44)));

      long page2 = pool.allocate(PageType.INTERNAL);
      pool.write(new Page(PageType.INTERNAL, 2L, page2, payload(55)));

      assertThat(store.read(page1).payload()[0]).isEqualTo((byte) 44);
      assertThat(metrics.snapshot())
          .containsEntry("bufferpool.evictions", 1L)
          .containsEntry("bufferpool.dirty_pages", 1L);
    }
  }

  @Test
  void explicitFlushWritesDirtyPagesWithoutEvicting() {
    Metrics metrics = new Metrics();
    try (PageStore store = PageStore.open(tempDir, metrics);
        BufferPool pool = new BufferPool(config(2, 1), store, metrics)) {
      long pageId = pool.allocate(PageType.LEAF);
      pool.write(new Page(PageType.LEAF, 1L, pageId, payload(99)));

      pool.flushDirtyPages();
      assertThat(store.read(pageId).payload()[0]).isEqualTo((byte) 99);
      assertThat(metrics.snapshot()).containsEntry("bufferpool.dirty_pages", 0L);

      try (PageHandle cached = pool.pin(pageId)) {
        assertThat(cached.page().payload()[0]).isEqualTo((byte) 99);
      }
      assertThat(metrics.snapshot()).containsEntry("bufferpool.hits", 1L);
    }
  }

  private static byte[] payload(int firstByte) {
    byte[] payload = new byte[SlottedPage.PAYLOAD_BYTES];
    payload[0] = (byte) firstByte;
    return payload;
  }

  private static Config config(int pages, int shards) {
    return new Config(
        Page.PAGE_SIZE,
        (long) pages * Page.PAGE_SIZE,
        shards,
        64L * 1024L * 1024L,
        Duration.ofMillis(2),
        Duration.ofSeconds(30),
        256L * 1024L * 1024L,
        Duration.ofHours(1),
        Duration.ofSeconds(5));
  }
}
