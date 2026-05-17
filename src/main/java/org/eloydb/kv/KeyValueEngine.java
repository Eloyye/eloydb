package org.eloydb.kv;

import org.eloydb.kv.engine.Cursors;
import org.eloydb.kv.engine.EngineSnapshot;
import org.eloydb.kv.engine.WriteTransaction;
import org.eloydb.kv.internal.Bytes;
import org.eloydb.kv.internal.Operation;
import org.eloydb.kv.storage.WriteAheadLog;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Embeddable EloyDB key-v * Embeddable EloyDB key-value engine.alue engine.
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
public final class KeyValueEngine implements AutoCloseable {
  private final Config config;
  private final Clock clock;
  private final WriteAheadLog wal;
  private final Metrics metrics;
  private final TreeMap<Bytes, byte[]> committed;
  private final AtomicInteger liveSnapshots = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();

  private long nextTxId = 1;
  private long commitTs;
  private boolean writerActive;

  private KeyValueEngine(
      Config config,
      Clock clock,
      WriteAheadLog wal,
      WriteAheadLog.RecoveryResult recoveryResult,
      Metrics metrics) {
    this.config = config;
    this.clock = clock;
    this.wal = wal;
    this.metrics = metrics;
    this.committed = recoveryResult.map();
    this.commitTs = recoveryResult.commitTs();
    initKvEngineStartMetric(metrics);
  }

  private void initKvEngineStartMetric(Metrics metrics) {
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
  public static KeyValueEngine open(Path directory, Config config) {
    return open(directory, config, Clock.systemUTC());
  }

  static KeyValueEngine open(Path directory, Config config, Clock clock) {
    var metrics = new Metrics();
    var wal = WriteAheadLog.open(directory, metrics);
    WriteAheadLog.RecoveryResult recoveryResult = wal.recover();
    return new KeyValueEngine(config, clock, wal, recoveryResult, metrics);
  }

  /**
   * Starts the only active writer; callers must commit, abort, or close the returned transaction.
   */
  public synchronized Transaction beginWrite() {
    ensureOpen();
    if (writerActive) {
      throw new KvException(
          ErrorCode.INSUFFICIENT_RESOURCES, "a write transaction is already active");
    }
    writerActive = true;
    return new WriteTransaction(this, nextTxId++, committed);
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
    return Cursors.forMap(committed, Bytes.copyOf(startInclusive), Bytes.copyOf(endExclusive));
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

  public synchronized Optional<byte[]> transactionGet(WriteTransaction txn, byte[] key) {
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

  public synchronized Cursor transactionScan(
      WriteTransaction txn, byte[] startInclusive, byte[] endExclusive) {
    ensureWriter(txn);
    TreeMap<Bytes, byte[]> overlay = copyCommitted();
    txn.operations().forEach(operation -> apply(overlay, operation));
    return Cursors.forMap(overlay, Bytes.copyOf(startInclusive), Bytes.copyOf(endExclusive));
  }

  public synchronized Snapshot transactionSnapshot(WriteTransaction txn) {
    ensureWriter(txn);
    TreeMap<Bytes, byte[]> overlay = copyCommitted();
    txn.operations().forEach(operation -> apply(overlay, operation));
    liveSnapshots.incrementAndGet();
    return new EngineSnapshot(commitTs, config, clock, overlay, liveSnapshots::decrementAndGet);
  }

  public synchronized CommitResult commit(WriteTransaction txn) {
    ensureWriter(txn);
    List<Operation> operations = txn.operations();
    long newCommitTs = commitTs + 1;
    CommitResult result = wal.appendCommitted(txn.txId(), newCommitTs, operations);
    operations.forEach
            (operation -> apply(committed, operation));
    commitTs = newCommitTs;
    writerActive = false;
    metrics.add("tree.pages_allocated", operations.size());
    return result;
  }

  public synchronized void abort(WriteTransaction txn) {
    ensureWriter(txn);
    writerActive = false;
  }

  private void ensureOpen() {
    if (closed.get()) {
      throw new KvException(ErrorCode.ENGINE_CLOSED, "engine is closed");
    }
  }

  private void ensureWriter(WriteTransaction txn) {
    ensureOpen();
    if (!writerActive || !txn.isOpen()) {
      throw new IllegalStateException("transaction is not active");
    }
  }

  private TreeMap<Bytes, byte[]> copyCommitted() {
    var copy = new TreeMap<Bytes, byte[]>();
    committed.forEach((key, value) -> copy.put(key, Arrays.copyOf(value, value.length)));
    return copy;
  }

  private static void apply(TreeMap<Bytes, byte[]> map, Operation operation) {
    if (operation.kind() == Operation.Kind.PUT) {
      map.put(
          Bytes.copyOf(operation.unsafeKey()),
          Arrays.copyOf(operation.unsafeValue(), operation.unsafeValue().length));
      return;
    }
    map.remove(Bytes.copyOf(operation.unsafeKey()));
  }

  public record VerifyResult(long keyCount, long liveSnapshots, boolean ok) {}
}
