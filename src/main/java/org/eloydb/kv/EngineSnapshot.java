package org.eloydb.kv;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("NonApiType")
final class EngineSnapshot implements Snapshot {
  private final long commitTs;
  private final Instant openedAt;
  private final Config config;
  private final Clock clock;
  private final TreeMap<Bytes, byte[]> view;
  private final Runnable onClose;
  private final AtomicBoolean closed = new AtomicBoolean();

  EngineSnapshot(
      long commitTs, Config config, Clock clock, TreeMap<Bytes, byte[]> source, Runnable onClose) {
    this.commitTs = commitTs;
    this.config = config;
    this.clock = clock;
    this.openedAt = clock.instant();
    this.view = copyMap(source);
    this.onClose = onClose;
  }

  @Override
  public long commitTs() {
    ensureUsable();
    return commitTs;
  }

  @Override
  public Optional<byte[]> get(byte[] key) {
    ensureUsable();
    byte[] value = view.get(Bytes.copyOf(key));
    return value == null
        ? Optional.empty()
        : Optional.of(java.util.Arrays.copyOf(value, value.length));
  }

  @Override
  public Cursor scan(byte[] startInclusive, byte[] endExclusive) {
    ensureUsable();
    return KvEngine.cursorFor(view, Bytes.copyOf(startInclusive), Bytes.copyOf(endExclusive));
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

  private static TreeMap<Bytes, byte[]> copyMap(TreeMap<Bytes, byte[]> source) {
    var copy = new TreeMap<Bytes, byte[]>();
    for (Map.Entry<Bytes, byte[]> entry : source.entrySet()) {
      byte[] value = entry.getValue();
      copy.put(entry.getKey(), java.util.Arrays.copyOf(value, value.length));
    }
    return copy;
  }
}
