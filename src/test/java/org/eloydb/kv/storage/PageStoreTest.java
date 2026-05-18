package org.eloydb.kv.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.eloydb.kv.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PageStoreTest {
  @TempDir Path tempDir;

  @Test
  void allocateAndFreeRoundTripsThroughFreelist() {
    try (PageStore store = PageStore.open(tempDir, new Metrics())) {
      // Bootstrap a meta so reopens work.
      store.writeMeta(0, 0, 0);
      Set<Long> ids = new HashSet<>();
      for (int i = 0; i < 32; i++) {
        long pid = store.allocate(PageType.LEAF);
        assertThat(ids.add(pid)).isTrue();
        byte[] payload =
            SlottedPage.PAYLOAD_BYTES > 0 ? new byte[SlottedPage.PAYLOAD_BYTES] : new byte[0];
        store.write(new Page(PageType.LEAF, i, pid, payload));
      }
      // Free 10 of them.
      int freed = 0;
      for (long id : ids) {
        if (freed >= 10) break;
        store.free(id);
        freed++;
      }
      assertThat(store.freePageCount()).isEqualTo(10);

      // Allocate 10 more; they should come from the freelist.
      for (int i = 0; i < 10; i++) {
        long pid = store.allocate(PageType.LEAF);
        store.write(new Page(PageType.LEAF, 100 + i, pid, new byte[SlottedPage.PAYLOAD_BYTES]));
      }
      assertThat(store.freePageCount()).isZero();
    }
  }

  @Test
  void persistedFreelistSurvivesRestart() {
    try (PageStore store = PageStore.open(tempDir, new Metrics())) {
      store.writeMeta(0, 0, 0);
      long a = store.allocate(PageType.LEAF);
      long b = store.allocate(PageType.LEAF);
      long c = store.allocate(PageType.LEAF);
      for (long pid : new long[] {a, b, c}) {
        store.write(new Page(PageType.LEAF, 0, pid, new byte[SlottedPage.PAYLOAD_BYTES]));
      }
      store.free(a);
      store.free(c);
      store.writeMeta(0, 0, 0);
      store.sync();
    }

    try (PageStore reopened = PageStore.open(tempDir, new Metrics())) {
      assertThat(reopened.freePageCount()).isEqualTo(2);
      assertThat(reopened.freelistContents()).contains(1L, 3L);
    }
  }

  @Test
  void corruptedMetaPageThrows() throws Exception {
    try (PageStore store = PageStore.open(tempDir, new Metrics())) {
      store.writeMeta(0, 0, 0);
      store.sync();
    }
    Path storePath = tempDir.resolve("store.0001");
    byte[] bytes = java.nio.file.Files.readAllBytes(storePath);
    bytes[Page.HEADER_SIZE + 1] ^= 0x01;
    java.nio.file.Files.write(storePath, bytes);

    assertThatThrownBy(() -> PageStore.open(tempDir, new Metrics()))
        .isInstanceOf(PageCorruptedException.class);
  }
}
