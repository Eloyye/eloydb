package org.eloydb.kv;

import java.time.Duration;

/**
 * Engine configuration.
 *
 * @param pageSize fixed M1 page size in bytes
 * @param bufferPoolBytes target buffer-pool capacity in bytes
 * @param bufferPoolShards number of buffer-pool shards
 * @param walSegmentBytes target WAL segment size in bytes
 * @param walFlushInterval maximum interval before WAL writes should be flushed
 * @param checkpointInterval background checkpoint cadence
 * @param checkpointWalBytes WAL bytes that can accumulate before checkpointing
 * @param maxSnapshotAge maximum usable age for an open snapshot
 * @param reclaimerInterval background page-reclaimer cadence
 *     <p>Example:
 *     <pre>{@code
 * Config config = Config.defaults().withMaxSnapshotAge(Duration.ofMinutes(10));
 * try (KvEngine engine = KvEngine.open(path, config)) {
 *   // use engine
 * }
 * }</pre>
 */
public record Config(
    int pageSize,
    long bufferPoolBytes,
    int bufferPoolShards,
    long walSegmentBytes,
    Duration walFlushInterval,
    Duration checkpointInterval,
    long checkpointWalBytes,
    Duration maxSnapshotAge,
    Duration reclaimerInterval) {
  public static Config defaults() {
    return new Config(
        8192,
        256L * 1024L * 1024L,
        16,
        64L * 1024L * 1024L,
        Duration.ofMillis(2),
        Duration.ofSeconds(30),
        256L * 1024L * 1024L,
        Duration.ofHours(1),
        Duration.ofSeconds(5));
  }

  public Config {
    if (pageSize != 8192) {
      throw new IllegalArgumentException("M1 page size is fixed at 8192 bytes");
    }
    if (bufferPoolBytes <= 0 || bufferPoolShards <= 0 || walSegmentBytes <= 0) {
      throw new IllegalArgumentException("buffer and WAL sizes must be positive");
    }
    walFlushInterval = requirePositive(walFlushInterval, "walFlushInterval");
    checkpointInterval = requirePositive(checkpointInterval, "checkpointInterval");
    maxSnapshotAge = requirePositive(maxSnapshotAge, "maxSnapshotAge");
    reclaimerInterval = requirePositive(reclaimerInterval, "reclaimerInterval");
  }

  public Config withMaxSnapshotAge(Duration age) {
    return new Config(
        pageSize,
        bufferPoolBytes,
        bufferPoolShards,
        walSegmentBytes,
        walFlushInterval,
        checkpointInterval,
        checkpointWalBytes,
        age,
        reclaimerInterval);
  }

  private static Duration requirePositive(Duration value, String name) {
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }
}
