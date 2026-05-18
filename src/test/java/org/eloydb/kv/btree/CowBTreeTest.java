package org.eloydb.kv.btree;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import org.eloydb.kv.Config;
import org.eloydb.kv.KeyValue;
import org.eloydb.kv.Metrics;
import org.eloydb.kv.storage.BufferPool;
import org.eloydb.kv.storage.Page;
import org.eloydb.kv.storage.PageStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CowBTreeTest {
  @TempDir Path tempDir;

  @Test
  void putGetDeleteSinglePage() {
    Metrics metrics = new Metrics();
    try (PageStore store = PageStore.open(tempDir, metrics);
        BufferPool pages = new BufferPool(testConfig(), store, metrics)) {
      long root = CowBTree.createEmpty(pages, 0L);
      CowBTree tree = new CowBTree(pages, 0L);

      root =
          tree.put(
              root, "a".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8));
      root =
          tree.put(
              root, "b".getBytes(StandardCharsets.UTF_8), "2".getBytes(StandardCharsets.UTF_8));
      root =
          tree.put(
              root, "c".getBytes(StandardCharsets.UTF_8), "3".getBytes(StandardCharsets.UTF_8));

      assertThat(tree.get(root, "a".getBytes(StandardCharsets.UTF_8)))
          .contains("1".getBytes(StandardCharsets.UTF_8));
      assertThat(tree.get(root, "b".getBytes(StandardCharsets.UTF_8)))
          .contains("2".getBytes(StandardCharsets.UTF_8));
      assertThat(tree.get(root, "c".getBytes(StandardCharsets.UTF_8)))
          .contains("3".getBytes(StandardCharsets.UTF_8));
      assertThat(tree.get(root, "z".getBytes(StandardCharsets.UTF_8))).isEmpty();

      root = tree.delete(root, "b".getBytes(StandardCharsets.UTF_8));
      assertThat(tree.get(root, "b".getBytes(StandardCharsets.UTF_8))).isEmpty();
      assertThat(tree.get(root, "a".getBytes(StandardCharsets.UTF_8)))
          .contains("1".getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void manyInsertsForceSplitsAndStillRetrievable() {
    Metrics metrics = new Metrics();
    try (PageStore store = PageStore.open(tempDir, metrics);
        BufferPool pages = new BufferPool(testConfig(), store, metrics)) {
      long root = CowBTree.createEmpty(pages, 0L);
      CowBTree tree = new CowBTree(pages, 0L);

      var oracle = new TreeMap<String, String>();
      // 1000 entries with ~500-byte values forces multiple splits.
      byte[] padded = "x".repeat(500).getBytes(StandardCharsets.UTF_8);
      for (int i = 0; i < 1000; i++) {
        String key = String.format("k%06d", i);
        oracle.put(key, "v" + i);
        root = tree.put(root, key.getBytes(StandardCharsets.UTF_8), padded);
      }
      // Read them all back.
      for (int i = 0; i < 1000; i++) {
        String key = String.format("k%06d", i);
        Optional<byte[]> got = tree.get(root, key.getBytes(StandardCharsets.UTF_8));
        assertThat(got).isPresent();
        assertThat(got.get()).isEqualTo(padded);
      }
      // Scan should be sorted and complete.
      List<KeyValue> scanned = tree.scan(root, new byte[0], new byte[] {(byte) 0xff, (byte) 0xff});
      List<String> keys = new ArrayList<>();
      for (KeyValue kv : scanned) keys.add(new String(kv.key(), StandardCharsets.UTF_8));
      assertThat(keys).containsExactlyElementsOf(oracle.keySet());
    }
  }

  @Test
  void overflowValuesRoundTrip() {
    Metrics metrics = new Metrics();
    try (PageStore store = PageStore.open(tempDir, metrics);
        BufferPool pages = new BufferPool(testConfig(), store, metrics)) {
      long root = CowBTree.createEmpty(pages, 0L);
      CowBTree tree = new CowBTree(pages, 0L);

      byte[] huge = new byte[40_000];
      new Random(42).nextBytes(huge);
      root = tree.put(root, "big".getBytes(StandardCharsets.UTF_8), huge);

      assertThat(tree.get(root, "big".getBytes(StandardCharsets.UTF_8)))
          .hasValueSatisfying(v -> assertThat(v).isEqualTo(huge));
    }
  }

  @Test
  void randomizedOracle() {
    Metrics metrics = new Metrics();
    try (PageStore store = PageStore.open(tempDir, metrics);
        BufferPool pages = new BufferPool(testConfig(), store, metrics)) {
      long root = CowBTree.createEmpty(pages, 0L);
      CowBTree tree = new CowBTree(pages, 0L);

      var random = new Random(7);
      var oracle = new TreeMap<String, String>();

      for (int i = 0; i < 5_000; i++) {
        String key = "k" + random.nextInt(500);
        if (random.nextInt(5) == 0) {
          root = tree.delete(root, key.getBytes(StandardCharsets.UTF_8));
          oracle.remove(key);
        } else {
          String value = "v" + random.nextInt(1_000_000);
          root =
              tree.put(
                  root,
                  key.getBytes(StandardCharsets.UTF_8),
                  value.getBytes(StandardCharsets.UTF_8));
          oracle.put(key, value);
        }
      }
      for (var entry : oracle.entrySet()) {
        Optional<byte[]> got = tree.get(root, entry.getKey().getBytes(StandardCharsets.UTF_8));
        assertThat(got)
            .as("key %s", entry.getKey())
            .hasValueSatisfying(
                v -> assertThat(new String(v, StandardCharsets.UTF_8)).isEqualTo(entry.getValue()));
      }
    }
  }

  private static Config testConfig() {
    return new Config(
        Page.PAGE_SIZE,
        64L * Page.PAGE_SIZE,
        4,
        64L * 1024L * 1024L,
        java.time.Duration.ofMillis(2),
        java.time.Duration.ofSeconds(30),
        256L * 1024L * 1024L,
        java.time.Duration.ofHours(1),
        java.time.Duration.ofSeconds(5));
  }
}
