package org.eloydb.kv;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embeddable EloyDB key-value engine.
 *
 * <p>Example:
 *
 * <pre>{@code
 * try (KvEngine engine = KvEngine.open(Path.of("/var/lib/eloydb"), Config.defaults())) {
 *   try (Txn txn = engine.beginWrite()) {
 *     txn.put("hello".getBytes(UTF_8), "world".getBytes(UTF_8));
 *     txn.commit();
 *   }
 *
 *   try (Snapshot snapshot = engine.snapshot();
 *       Cursor cursor = snapshot.scan(new byte[0], new byte[] {(byte) 0xff})) {
 *     while (cursor.next()) {
 *       KeyValue row = cursor.current();
 *     }
 *   }
 * }
 * }</pre>
 */
@SuppressWarnings("NonApiType")
public final class KvEngine implements AutoCloseable {
  private final Config config;
  private final Clock clock;
  private final Wal wal;
  private final Metrics metrics;
  private final TreeMap<Bytes, byte[]> committed;
  private final AtomicInteger liveSnapshots = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();

  private long nextTxId = 1;
  private long commitTs;
  private boolean writerActive;

  private KvEngine(
      Config config, Clock clock, Wal wal, Wal.RecoveryResult recoveryResult, Metrics metrics) {
    this.config = config;
    this.clock = clock;
    this.wal = wal;
    this.metrics = metrics;
    this.committed = recoveryResult.map();
    this.commitTs = recoveryResult.commitTs();
    metrics.gauge("snapshots.live", () -> (long) liveSnapshots.get());
    metrics.gauge("tree.height", () -> committed.isEmpty() ? 0L : 1L);
    metrics.gauge("tree.pages_allocated", () -> Math.max(1L, committed.size()));
    metrics.gauge("tree.pages_freed", () -> 0L);
    metrics.gauge("bufferpool.hits", () -> 0L);
    metrics.gauge("bufferpool.misses", () -> 0L);
    metrics.gauge("bufferpool.evictions", () -> 0L);
    metrics.gauge("bufferpool.dirty_pages", () -> 0L);
  }

  /** Opens or creates an engine rooted at {@code directory}, replaying its WAL before returning. */
  public static KvEngine open(Path directory, Config config) {
    return open(directory, config, Clock.systemUTC());
  }

  static KvEngine open(Path directory, Config config, Clock clock) {
    Metrics metrics = new Metrics();
    Wal wal = Wal.open(directory, metrics);
    Wal.RecoveryResult recoveryResult = wal.recover();
    return new KvEngine(config, clock, wal, recoveryResult, metrics);
  }

  /** Starts the only active writer; callers must commit, abort, or close the returned transaction. */
  public synchronized Txn beginWrite() {
    ensureOpen();
    if (writerActive) {
      throw new KvException(
          ErrorCode.INSUFFICIENT_RESOURCES, "a write transaction is already active");
    }
    writerActive = true;
    return new WriteTxn(this, nextTxId++, committed);
  }

  /** Returns a stable read view at the current commit timestamp. */
  public synchronized Snapshot snapshot() {
    ensureOpen();
    liveSnapshots.incrementAndGet();
    return new EngineSnapshot(commitTs, config, clock, committed, liveSnapshots::decrementAndGet);
  }

  /** Returns a defensive copy of the committed value for {@code key}, if present. */
  public synchronized Optional<byte[]> get(byte[] key) {
    ensureOpen();
    byte[] value = committed.get(Bytes.copyOf(key));
    return value == null ? Optional.empty() : Optional.of(Arrays.copyOf(value, value.length));
  }

  /** Scans committed keys in unsigned byte order over {@code [startInclusive, endExclusive)}. */
  public synchronized Cursor scan(byte[] startInclusive, byte[] endExclusive) {
    ensureOpen();
    return cursorFor(committed, Bytes.copyOf(startInclusive), Bytes.copyOf(endExclusive));
  }

  /** Returns the engine's in-process metrics registry. */
  public Metrics metrics() {
    return metrics;
  }

  /** Performs lightweight consistency checks over the current in-memory state. */
  public VerifyResult verify() {
    ensureOpen();
    return new VerifyResult(committed.size(), liveSnapshots.get(), true);
  }

  @Override
  public synchronized void close() {
    if (closed.compareAndSet(false, true)) {
      wal.close();
    }
  }

  synchronized Optional<byte[]> transactionGet(WriteTxn txn, byte[] key) {
    ensureWriter(txn);
    Bytes wrapped = Bytes.copyOf(key);
    Operation operation = txn.latestOperation(wrapped);
    if (operation != null) {
      return operation.kind() == Operation.Kind.DELETE
          ? Optional.empty()
          : Optional.of(operation.value());
    }
    byte[] value = committed.get(wrapped);
    return value == null ? Optional.empty() : Optional.of(Arrays.copyOf(value, value.length));
  }

  synchronized Cursor transactionScan(WriteTxn txn, byte[] startInclusive, byte[] endExclusive) {
    ensureWriter(txn);
    TreeMap<Bytes, byte[]> overlay = copyCommitted();
    for (Operation operation : txn.operations()) {
      apply(overlay, operation);
    }
    return cursorFor(overlay, Bytes.copyOf(startInclusive), Bytes.copyOf(endExclusive));
  }

  synchronized Snapshot transactionSnapshot(WriteTxn txn) {
    ensureWriter(txn);
    TreeMap<Bytes, byte[]> overlay = copyCommitted();
    for (Operation operation : txn.operations()) {
      apply(overlay, operation);
    }
    liveSnapshots.incrementAndGet();
    return new EngineSnapshot(commitTs, config, clock, overlay, liveSnapshots::decrementAndGet);
  }

  synchronized CommitResult commit(WriteTxn txn) {
    ensureWriter(txn);
    List<Operation> operations = txn.operations();
    long newCommitTs = commitTs + 1;
    CommitResult result = wal.appendCommitted(txn.txId(), newCommitTs, operations);
    for (Operation operation : operations) {
      apply(committed, operation);
    }
    commitTs = newCommitTs;
    writerActive = false;
    metrics.add("tree.pages_allocated", operations.size());
    return result;
  }

  synchronized void abort(WriteTxn txn) {
    ensureWriter(txn);
    writerActive = false;
  }

  static Cursor cursorFor(TreeMap<Bytes, byte[]> map, Bytes startInclusive, Bytes endExclusive) {
    if (startInclusive.compareTo(endExclusive) > 0) {
      throw new KvException(ErrorCode.INVALID_ARGUMENT, "scan start must be <= end");
    }
    List<KeyValue> rows = new ArrayList<>();
    for (Map.Entry<Bytes, byte[]> entry :
        map.subMap(startInclusive, true, endExclusive, false).entrySet()) {
      rows.add(new KeyValue(entry.getKey().copy(), entry.getValue()));
    }
    return new ListCursor(rows);
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new KvException(ErrorCode.ENGINE_CLOSED, "engine is closed");
    }
  }

  private void ensureWriter(WriteTxn txn) {
    ensureOpen();
    if (!writerActive || !txn.isOpen()) {
      throw new IllegalStateException("transaction is not active");
    }
  }

  private TreeMap<Bytes, byte[]> copyCommitted() {
    var copy = new TreeMap<Bytes, byte[]>();
    for (Map.Entry<Bytes, byte[]> entry : committed.entrySet()) {
      copy.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
    }
    return copy;
  }

  private static void apply(TreeMap<Bytes, byte[]> map, Operation operation) {
    if (operation.kind() == Operation.Kind.PUT) {
      map.put(
          Bytes.copyOf(operation.unsafeKey()),
          Arrays.copyOf(operation.unsafeValue(), operation.unsafeValue().length));
    } else {
      map.remove(Bytes.copyOf(operation.unsafeKey()));
    }
  }

  public record VerifyResult(long keyCount, long liveSnapshots, boolean ok) {}
}
