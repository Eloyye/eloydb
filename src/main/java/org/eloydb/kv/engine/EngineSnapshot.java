package org.eloydb.kv.engine;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eloydb.kv.Config;
import org.eloydb.kv.Cursor;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KeyValue;
import org.eloydb.kv.KvException;
import org.eloydb.kv.Snapshot;
import org.eloydb.kv.btree.CowBTree;
import org.eloydb.kv.internal.Bytes;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of the engine pinned to a specific commit timestamp.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li><strong>Root-backed:</strong> snapshot pins a {@code root_pid} in the page store. Reads
 *       descend the live tree at that root. No copy is made; this is the cheap, persistent path.
 *   <li><strong>Overlay-backed:</strong> snapshot owns a materialised {@link TreeMap} (used when
 *       the snapshot must include uncommitted writer state).
 * </ul>
 */
@SuppressWarnings("NonApiType")
public final class EngineSnapshot implements Snapshot {
  private final long commitTs;
  private final Instant openedAt;
  private final Config config;
  private final Clock clock;
  private final @Nullable CowBTree tree;
  private final long rootPid;
  private final @Nullable TreeMap<Bytes, byte[]> overlay;
  private final Runnable onClose;
  private final AtomicBoolean closed = new AtomicBoolean();

  /** Creates a root-backed snapshot. */
  public EngineSnapshot(
      long commitTs, long rootPid, CowBTree tree, Config config, Clock clock, Runnable onClose) {
    this.commitTs = commitTs;
    this.rootPid = rootPid;
    this.tree = tree;
    this.overlay = null;
    this.config = config;
    this.clock = clock;
    this.openedAt = clock.instant();
    this.onClose = onClose;
  }

  private EngineSnapshot(
      long commitTs, TreeMap<Bytes, byte[]> overlay, Config config, Clock clock, Runnable onClose) {
    this.commitTs = commitTs;
    this.rootPid = 0L;
    this.tree = null;
    this.overlay = overlay;
    this.config = config;
    this.clock = clock;
    this.openedAt = clock.instant();
    this.onClose = onClose;
  }

  /** Creates an overlay-backed snapshot (caller hands over a defensively-copied map). */
  public static EngineSnapshot overlay(
      long commitTs, TreeMap<Bytes, byte[]> view, Config config, Clock clock, Runnable onClose) {
    return new EngineSnapshot(commitTs, view, config, clock, onClose);
  }

  @Override
  public long commitTs() {
    ensureUsable();
    return commitTs;
  }

  @Override
  public Optional<byte[]> get(byte[] key) {
    ensureUsable();
    if (overlay != null) {
      byte[] value = overlay.get(Bytes.copyOf(key));
      return value == null
          ? Optional.empty()
          : Optional.of(java.util.Arrays.copyOf(value, value.length));
    }
    return Objects.requireNonNull(tree).get(rootPid, key);
  }

  @Override
  public Cursor scan(byte[] startInclusive, byte[] endExclusive) {
    ensureUsable();
    Bytes lo = Bytes.copyOf(startInclusive);
    Bytes hi = Bytes.copyOf(endExclusive);
    if (lo.compareTo(hi) > 0) {
      throw new KvException(ErrorCode.INVALID_ARGUMENT, "scan start must be <= end");
    }
    if (overlay != null) {
      var rows = new ArrayList<KeyValue>();
      for (Map.Entry<Bytes, byte[]> entry : overlay.subMap(lo, true, hi, false).entrySet()) {
        rows.add(new KeyValue(entry.getKey().copy(), entry.getValue()));
      }
      return new ListCursor(rows);
    }
    return new ListCursor(Objects.requireNonNull(tree).scan(rootPid, startInclusive, endExclusive));
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      onClose.run();
    }
  }

  private void ensureUsable() {
    if (closed.get()) {
      throw new IllegalStateException("snapshot is closed");
    }
    if (openedAt.plus(config.maxSnapshotAge()).isBefore(clock.instant())) {
      throw new KvException(ErrorCode.SNAPSHOT_TOO_OLD, "snapshot " + commitTs + " is too old");
    }
  }
}
