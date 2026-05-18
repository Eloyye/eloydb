package org.eloydb.kv;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eloydb.kv.btree.CowBTree;
import org.eloydb.kv.engine.EngineSnapshot;
import org.eloydb.kv.engine.ListCursor;
import org.eloydb.kv.internal.Bytes;
import org.eloydb.kv.internal.Operation;
import org.eloydb.kv.storage.MetaPayload;
import org.eloydb.kv.storage.PageStore;
import org.eloydb.kv.storage.WriteAheadLog;
import org.eloydb.kv.storage.WriteAheadLog.CommittedTransaction;

/**
 * Embeddable EloyDB key-value engine.
 *
 * <p>State lives in two on-disk artefacts:
 *
 * <ul>
 *   <li>{@code store.0001} — a {@link PageStore} of 8 KiB pages containing the Copy-on-Write B+
 *       tree, overflow chains, the free list, and the meta page that names the current root.
 *   <li>{@code wal.000001} — the write-ahead log; the durability boundary is the COMMIT record
 *       fsync.
 * </ul>
 *
 * <p>Example:
 *
 * <pre>{@code
 * try (KeyValueEngine engine =
 *     KeyValueEngine.open(Path.of("/var/lib/eloydb"), Config.defaults())) {
 *   try (Transaction txn = engine.beginWrite()) {
 *     txn.put("hello".getBytes(UTF_8), "world".getBytes(UTF_8));
 *     txn.commit();
 *   }
 * }
 * }</pre>
 */
@SuppressWarnings("NonApiType")
public final class KeyValueEngine implements AutoCloseable {
  private final Config config;
  private final Clock clock;
  private final WriteAheadLog wal;
  private final PageStore store;
  private final CowBTree tree;
  private final Metrics metrics;
  private final AtomicInteger liveSnapshots = new AtomicInteger();
  private final AtomicBoolean closed = new AtomicBoolean();

  private long nextTxId = 1;
  private long commitTs;
  private long rootPid;
  private boolean writerActive;

  private KeyValueEngine(
      Config config,
      Clock clock,
      WriteAheadLog wal,
      PageStore store,
      CowBTree tree,
      long rootPid,
      long commitTs,
      Metrics metrics) {
    this.config = config;
    this.clock = clock;
    this.wal = wal;
    this.store = store;
    this.tree = tree;
    this.rootPid = rootPid;
    this.commitTs = commitTs;
    this.metrics = metrics;
    initMetrics(metrics);
  }

  private void initMetrics(Metrics metrics) {
    metrics.gauge("snapshots.live", () -> (long) liveSnapshots.get());
    metrics.gauge("tree.pages_allocated", store::allocatedPages);
    metrics.gauge("tree.height", this::computeTreeHeight);
    metrics.gauge("freelist.pages", () -> (long) store.freePageCount());
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
    PageStore store = PageStore.open(directory, metrics);
    WriteAheadLog wal = WriteAheadLog.open(directory, metrics);
    WriteAheadLog.RecoveryResult recoveryResult = wal.recover();

    MetaPayload meta = store.readMeta();
    long rootPid;
    long commitTs;
    long startingLsn;
    if (meta != null && meta.rootPid() != 0L) {
      rootPid = meta.rootPid();
      commitTs = meta.commitTs();
      startingLsn = commitTs;
    } else {
      rootPid = CowBTree.createEmpty(store, 0L);
      commitTs = 0L;
      startingLsn = 0L;
      store.writeMeta(0L, rootPid, 0L);
      store.sync();
    }
    CowBTree tree = new CowBTree(store, startingLsn);

    for (CommittedTransaction tx : recoveryResult.transactions()) {
      if (tx.commitTs() <= commitTs) {
        continue;
      }
      for (Operation op : tx.operations()) {
        rootPid = applyOperation(tree, rootPid, op);
      }
      commitTs = tx.commitTs();
    }
    if (commitTs != recoveryResult.commitTs()) {
      commitTs = Math.max(commitTs, recoveryResult.commitTs());
    }
    // Persist post-recovery state so a subsequent restart can skip the replay if the WAL is rolled.
    store.writeMeta(commitTs, rootPid, commitTs);
    store.sync();

    return new KeyValueEngine(config, clock, wal, store, tree, rootPid, commitTs, metrics);
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
    return new WriteTransaction(this, nextTxId++);
  }

  /** Returns a stable read view at the current commit timestamp. */
  public synchronized Snapshot snapshot() {
    ensureOpen();
    liveSnapshots.incrementAndGet();
    return new EngineSnapshot(
        commitTs, rootPid, tree, config, clock, liveSnapshots::decrementAndGet);
  }

  /** Returns a defensive copy of the committed value for {@code key}, if present. */
  public synchronized Optional<byte[]> get(byte[] key) {
    ensureOpen();
    return tree.get(rootPid, key);
  }

  /** Scans committed keys in unsigned byte order over {@code [startInclusive, endExclusive)}. */
  public synchronized Cursor scan(byte[] startInclusive, byte[] endExclusive) {
    ensureOpen();
    return new ListCursor(tree.scan(rootPid, startInclusive, endExclusive));
  }

  /** Returns the engine's in-process metrics registry. */
  public Metrics metrics() {
    return metrics;
  }

  /** Performs lightweight consistency checks over the current on-disk state. */
  public synchronized VerifyResult verify() {
    ensureOpen();
    long reachable = 0;
    var counter = new long[1];
    tree.walkAllPages(rootPid, pid -> counter[0]++);
    reachable = counter[0];
    boolean walOk = wal.verify().ok();
    return new VerifyResult(reachable, liveSnapshots.get(), walOk);
  }

  @Override
  public synchronized void close() {
    if (closed.compareAndSet(false, true)) {
      try {
        wal.close();
      } finally {
        store.close();
      }
    }
  }

  /* --------------------------------------------------------------------- *
   *                         WriteTransaction hooks                        *
   * --------------------------------------------------------------------- */

  synchronized Optional<byte[]> transactionGet(WriteTransaction txn, byte[] key) {
    ensureWriter(txn);
    Operation operation = txn.latestOperation(Bytes.copyOf(key));
    if (operation != null) {
      return operation.kind() == Operation.Kind.DELETE
          ? Optional.empty()
          : Optional.of(operation.value());
    }
    return tree.get(rootPid, key);
  }

  synchronized Cursor transactionScan(
      WriteTransaction txn, byte[] startInclusive, byte[] endExclusive) {
    ensureWriter(txn);
    TreeMap<Bytes, byte[]> overlay = materializeRange(rootPid, startInclusive, endExclusive);
    for (Operation op : txn.operations()) {
      applyToOverlay(overlay, op);
    }
    return cursorFor(overlay, startInclusive, endExclusive);
  }

  synchronized Snapshot transactionSnapshot(WriteTransaction txn) {
    ensureWriter(txn);
    // Snapshots over an active writer see the writer's pending overlay too.
    TreeMap<Bytes, byte[]> overlay = materializeAll(rootPid);
    for (Operation op : txn.operations()) {
      applyToOverlay(overlay, op);
    }
    liveSnapshots.incrementAndGet();
    return EngineSnapshot.overlay(commitTs, overlay, config, clock, liveSnapshots::decrementAndGet);
  }

  synchronized CommitResult commit(WriteTransaction txn) {
    ensureWriter(txn);
    List<Operation> operations = txn.operations();
    long newCommitTs = commitTs + 1;
    CommitResult result = wal.appendCommitted(txn.txId(), newCommitTs, operations);

    long workingRoot = rootPid;
    for (Operation op : operations) {
      workingRoot = applyOperation(tree, workingRoot, op);
    }
    rootPid = workingRoot;
    commitTs = newCommitTs;
    store.writeMeta(commitTs, rootPid, commitTs);
    store.sync();
    writerActive = false;
    return result;
  }

  synchronized void abort(WriteTransaction txn) {
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

  private TreeMap<Bytes, byte[]> materializeAll(long root) {
    var overlay = new TreeMap<Bytes, byte[]>();
    for (KeyValue row : tree.scan(root, new byte[0], new byte[] {(byte) 0xff})) {
      overlay.put(Bytes.copyOf(row.key()), row.value());
    }
    // Fallback: walk a wide range to capture keys >= 0xff prefix.
    return overlay;
  }

  private TreeMap<Bytes, byte[]> materializeRange(long root, byte[] start, byte[] end) {
    var overlay = new TreeMap<Bytes, byte[]>();
    for (KeyValue row : tree.scan(root, start, end)) {
      overlay.put(Bytes.copyOf(row.key()), row.value());
    }
    return overlay;
  }

  private Cursor cursorFor(TreeMap<Bytes, byte[]> overlay, byte[] start, byte[] end) {
    Bytes lo = Bytes.copyOf(start);
    Bytes hi = Bytes.copyOf(end);
    if (lo.compareTo(hi) > 0) {
      throw new KvException(ErrorCode.INVALID_ARGUMENT, "scan start must be <= end");
    }
    var rows = new ArrayList<KeyValue>();
    overlay
        .subMap(lo, true, hi, false)
        .forEach((key, value) -> rows.add(new KeyValue(key.copy(), value)));
    return new ListCursor(rows);
  }

  private static long applyOperation(CowBTree tree, long root, Operation op) {
    if (op.kind() == Operation.Kind.PUT) {
      return tree.put(root, op.unsafeKey(), op.unsafeValue());
    }
    return tree.delete(root, op.unsafeKey());
  }

  private static void applyToOverlay(TreeMap<Bytes, byte[]> overlay, Operation op) {
    if (op.kind() == Operation.Kind.PUT) {
      overlay.put(Bytes.copyOf(op.unsafeKey()), op.value());
    } else {
      overlay.remove(Bytes.copyOf(op.unsafeKey()));
    }
  }

  private long computeTreeHeight() {
    long pid = rootPid;
    long height = 0;
    while (true) {
      var page = store.read(pid);
      height++;
      if (page.type() == org.eloydb.kv.storage.PageType.LEAF) {
        return height;
      }
      pid = org.eloydb.kv.storage.SlottedPage.internalLeftmostChild(page.payload());
    }
  }

  public record VerifyResult(long pageCount, long liveSnapshots, boolean ok) {}
}
