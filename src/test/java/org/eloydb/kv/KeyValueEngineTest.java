package org.eloydb.kv;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import org.eloydb.kv.cli.EloydbKvCLI;
import org.eloydb.kv.storage.Page;
import org.eloydb.kv.storage.PageCorruptedException;
import org.eloydb.kv.storage.PageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KeyValueEngineTest {
  @TempDir private java.nio.file.Path tempDir;

  @Test
  void putGetDeleteScanAndRecover() {
    try (KeyValueEngine engine = KeyValueEngine.open(tempDir, Config.defaults())) {
      try (Transaction txn = engine.beginWrite()) {
        txn.put(bytes("b"), bytes("2"));
        txn.put(bytes("a"), bytes("1"));
        txn.put(bytes("c"), bytes("3"));
        txn.delete(bytes("b"));
        txn.commit();
      }

      assertThat(engine.get(bytes("a"))).contains(bytes("1"));
      assertThat(engine.get(bytes("b"))).isEmpty();
      assertThat(scanKeys(engine, "a", "z")).containsExactly("a", "c");
    }

    try (KeyValueEngine recovered = KeyValueEngine.open(tempDir, Config.defaults())) {
      assertThat(recovered.get(bytes("a"))).contains(bytes("1"));
      assertThat(recovered.get(bytes("b"))).isEmpty();
      assertThat(scanKeys(recovered, "a", "z")).containsExactly("a", "c");
      try (Snapshot snapshot = recovered.snapshot()) {
        assertThat(snapshot.commitTs()).isEqualTo(1);
      }
    }
  }

  @Test
  void emptyCommitAdvancesTimestampAcrossRecovery() {
    try (KeyValueEngine engine = KeyValueEngine.open(tempDir, Config.defaults())) {
      try (Transaction txn = engine.beginWrite()) {
        txn.commit();
      }
    }

    try (KeyValueEngine recovered = KeyValueEngine.open(tempDir, Config.defaults());
        Snapshot snapshot = recovered.snapshot()) {
      assertThat(snapshot.commitTs()).isEqualTo(1);
    }
  }

  @Test
  void walCrcMismatchFailsRecovery() throws Exception {
    try (KeyValueEngine engine = KeyValueEngine.open(tempDir, Config.defaults())) {
      try (Transaction txn = engine.beginWrite()) {
        txn.put(bytes("key"), bytes("value"));
        txn.commit();
      }
    }

    java.nio.file.Path wal = tempDir.resolve("wal.000001");
    byte[] bytes = Files.readAllBytes(wal);
    bytes[bytes.length / 2] ^= 0x01;
    Files.write(wal, bytes, StandardOpenOption.TRUNCATE_EXISTING);

    assertThatThrownBy(() -> KeyValueEngine.open(tempDir, Config.defaults()))
        .isInstanceOf(KvException.class)
        .extracting("errorCode")
        .isEqualTo(ErrorCode.CORRUPTED_PAGE);
  }

  @Test
  void tornWalTailIsTruncatedAndReported() throws Exception {
    try (KeyValueEngine engine = KeyValueEngine.open(tempDir, Config.defaults())) {
      try (Transaction txn = engine.beginWrite()) {
        txn.put(bytes("key"), bytes("value"));
        txn.commit();
      }
    }

    java.nio.file.Path wal = tempDir.resolve("wal.000001");
    long cleanSize = Files.size(wal);
    Files.write(wal, new byte[] {0x45, 0x4c}, StandardOpenOption.APPEND);

    try (KeyValueEngine recovered = KeyValueEngine.open(tempDir, Config.defaults())) {
      assertThat(recovered.get(bytes("key"))).contains(bytes("value"));
      assertThat(recovered.metrics().snapshot()).containsEntry("wal.torn_tail_truncations", 1L);
    }
    assertThat(Files.size(wal)).isEqualTo(cleanSize);
  }

  @Test
  void snapshotKeepsStableViewAfterWrites() {
    try (KeyValueEngine engine = KeyValueEngine.open(tempDir, Config.defaults())) {
      try (Transaction txn = engine.beginWrite()) {
        txn.put(bytes("key"), bytes("v1"));
        txn.commit();
      }

      try (Snapshot snapshot = engine.snapshot()) {
        try (Transaction txn = engine.beginWrite()) {
          txn.put(bytes("key"), bytes("v2"));
          txn.commit();
        }

        assertThat(snapshot.get(bytes("key"))).contains(bytes("v1"));
        assertThat(engine.get(bytes("key"))).contains(bytes("v2"));
      }
    }
  }

  @Test
  void oldSnapshotFailsAfterConfiguredAge() {
    MutableClock clock = new MutableClock();
    Config config = Config.defaults().withMaxSnapshotAge(Duration.ofSeconds(1));
    try (KeyValueEngine engine = KeyValueEngine.open(tempDir, config, clock);
        Snapshot snapshot = engine.snapshot()) {
      clock.advance(Duration.ofSeconds(2));
      assertThatThrownBy(() -> snapshot.get(bytes("x")))
          .isInstanceOf(KvException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.SNAPSHOT_TOO_OLD);
    }
  }

  @Test
  void randomizedOperationsMatchTreeMapOracle() {
    Random random = new Random(1);
    TreeMap<String, String> oracle = new TreeMap<>();

    try (KeyValueEngine engine = KeyValueEngine.open(tempDir, Config.defaults())) {
      for (int i = 0; i < 10_000; i++) {
        String key = "k" + random.nextInt(200);
        int op = random.nextInt(4);
        if (op == 0) {
          try (Transaction txn = engine.beginWrite()) {
            txn.delete(bytes(key));
            txn.commit();
          }
          oracle.remove(key);
        } else {
          String value = "v" + random.nextInt(1_000_000);
          try (Transaction txn = engine.beginWrite()) {
            txn.put(bytes(key), bytes(value));
            txn.commit();
          }
          oracle.put(key, value);
        }

        Optional<byte[]> actual = engine.get(bytes(key));
        assertThat(actual.map(value -> new String(value, UTF_8)))
            .isEqualTo(Optional.ofNullable(oracle.get(key)));
      }

      assertThat(scanKeys(engine, "k0", "k999")).containsExactlyElementsOf(oracle.keySet());
    }
  }

  @Test
  void keyEncodingPreservesIntegerAndTextOrder() {
    assertThat(Arrays.compareUnsigned(KeyEncoding.int64(-2), KeyEncoding.int64(1))).isLessThan(0);
    assertThat(Arrays.compareUnsigned(KeyEncoding.int64(-1), KeyEncoding.int64(0))).isLessThan(0);
    assertThat(Arrays.compareUnsigned(KeyEncoding.text("a"), KeyEncoding.text("aa"))).isLessThan(0);
    assertThat(
            Arrays.compareUnsigned(
                KeyEncoding.bytea(new byte[] {0}), KeyEncoding.bytea(new byte[] {1})))
        .isLessThan(0);
  }

  @Test
  void pageRoundTripAndCorruptionDetection() {
    byte[] payload = bytes("payload");
    Page page = new Page(PageType.LEAF, 7, 42, payload);
    try (Arena arena = Arena.ofConfined()) {
      var segment = page.serialize(arena);
      Page decoded = Page.deserialize(42, segment);
      assertThat(decoded.type()).isEqualTo(PageType.LEAF);
      assertThat(decoded.lsn()).isEqualTo(7);
      assertThat(decoded.payload()).startsWith(payload);

      segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, Page.HEADER_SIZE + 1, (byte) 99);
      assertThatThrownBy(() -> Page.deserialize(42, segment))
          .isInstanceOf(PageCorruptedException.class)
          .isInstanceOf(KvException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.CORRUPTED_PAGE);
    }
  }

  @Test
  void cliMainIsPublic() throws Exception {
    var main = EloydbKvCLI.class.getMethod("main", String[].class);
    assertThat(Modifier.isPublic(main.getModifiers())).isTrue();
    assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
  }

  private static List<String> scanKeys(KeyValueEngine engine, String start, String end) {
    var keys = new ArrayList<String>();
    try (Cursor cursor = engine.scan(bytes(start), bytes(end))) {
      while (cursor.next()) {
        keys.add(new String(cursor.current().key(), UTF_8));
      }
    }
    return keys;
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static final class MutableClock extends Clock {
    private Instant now = Instant.EPOCH;

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
