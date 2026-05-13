# Milestone 1 — KV Engine Works

> Detailed specification for Milestone 1 (M1) of eloydb.
> Last updated: 2026-05-12
> Based on: [PLAN.md](../plan/PLAN.md), [SPEC_V1.MD](./SPEC_V1.MD)

## 1. Goal

Deliver a single-node, single-process, **embeddable key-value engine** built on a Copy-on-Write (CoW) B+ tree, off-heap page storage, write-ahead logging, crash recovery, and snapshot-isolated MVCC reads. No SQL, no network, no concurrency across writers, no distribution.

This milestone is the load-bearing foundation for everything that follows: every subsequent milestone (SQL, planner, distribution, vectors) sits on top of pages, the buffer pool, the CoW B+ tree, the WAL, and snapshots delivered here. M1 is "done" when the engine is *boring* — i.e. survives weeks of crash-injection torture without a data integrity failure.

## 2. Scope

### 2.1 In Scope

- Fixed-size 8 KiB page format with CRC, type tag, LSN, and slotted layout.
- Off-heap page storage via `MemorySegment` (Foreign Function & Memory API).
- Sharded buffer pool with CLOCK eviction, pin/unpin, dirty tracking.
- Single-file (or single-directory) page store with stable `PageId` allocation and a free-page list.
- Single-threaded **Copy-on-Write B+ tree**: `put`, `get`, `delete`, `range scan`, `snapshot`.
- Write-ahead log: append-only segments, group-commit fsync, CRC per record, full-page writes for torn-page protection.
- Force-at-commit durability + checkpointing; crash recovery that replays WAL into a consistent root.
- Variable-length **key encoding** that preserves lexicographic order for primitive types (`bool`, `int{16,32,64}`, `float{32,64}`, `text`, `bytea`).
- MVCC via **snapshot roots**: `(commit_ts, root_page_id)` pairs; reference-counted; reclaimer frees unreachable pages.
- Background **page reclaimer** that walks dropped roots and returns unreferenced pages to the free list.
- An embeddable Java API (`KvEngine`) usable from a `main()` and from JUnit tests.
- A CLI/REPL harness (`eloydb-kv`) for manual `put/get/scan/snapshot` exploration.
- Property-based tests, crash-injection tests, and a stress harness.
- Minimum metrics surface (counters/gauges) sufficient to debug performance regressions.

### 2.2 Out of Scope (Deferred)

- SQL parsing, planning, execution (M2–M3).
- Wire protocol, sessions, authentication, TLS (M2).
- Concurrent writers; multiple writers per tree (single-writer is sufficient — concurrency arrives with the leaseholder in M4).
- Distributed transactions, Raft, gossip, range descriptors (M4).
- Secondary indexes as a first-class concept (the engine just stores opaque KV — schema lives one layer up).
- HNSW / vector storage (M5).
- Compression (LZ4/Zstd) — page format reserves the byte but the codec stays a no-op.
- Online schema change, follower reads, parallel commit (later).
- Cross-table / cross-tree atomicity beyond a single batch on a single tree.

## 3. Component Breakdown

### 3.1 Page Layer

A page is a fixed 8 KiB byte buffer with a typed header.

| Field | Bytes | Notes |
|---|---|---|
| `magic` | 4 | Identifies eloydb page; mismatch = corruption. |
| `page_type` | 1 | `META`, `INTERNAL`, `LEAF`, `OVERFLOW`, `FREELIST`, `WAL_INDEX`. |
| `flags` | 1 | Reserved (compression bit, etc.) — must be `0` in M1. |
| `version` | 2 | Page format version; M1 = `1`. |
| `lsn` | 8 | LSN of the WAL record that produced this page image. |
| `page_id` | 8 | Self-id; redundant with location, used for sanity checks. |
| `crc32c` | 4 | Computed over the rest of the page; verified on read-from-disk. |
| `payload` | rest | Slotted layout for B+ tree pages; opaque for others. |

Internal/leaf pages use a slotted layout: a forward-growing slot array of (offset, length) pairs and a backward-growing key/value heap. Keys are stored in encoded form.

**Acceptance:**
- A round-trip of every page type through `serialize → bytes → deserialize` is byte-identical.
- A flipped bit anywhere in the payload causes `read()` to throw `PageCorruptedException` with the page id.

### 3.2 Page Store

A single file (`store.0001`) plus future segment rollover (`store.0002`, ...). M1 may use a single growing file; segmenting can be deferred *if* the file APIs allow truncation/extension cleanly.

- `PageId` is a 64-bit monotonically allocated id that maps to a `(segment, offset)` pair.
- A persisted free-list page chain holds reusable `PageId`s; allocation prefers reuse before extending the file.
- All file IO goes through `FileChannel` + direct `MemorySegment` buffers; no `byte[]` in the hot path.

**Acceptance:**
- Allocate 1M pages, free 500K random ones, reallocate — no leaks, free-list count matches expectation.
- After process restart, the free-list is recovered and allocation continues without reusing live pages.

### 3.3 Buffer Pool

Off-heap, sharded by `(page_id mod N)` where N defaults to 16.

- Each shard owns its own hash table, CLOCK ring, and `ReentrantLock`.
- API: `pin(pageId) → PageHandle`, `unpin(handle, dirty?)`, `newPage(type) → PageHandle`.
- Eviction: CLOCK-second-chance over unpinned pages. Pinned pages are never evicted. Dirty pages are written via the WAL/checkpoint path before eviction.
- Configurable size in bytes; minimum useful size = 16 MiB.

**Acceptance:**
- Eviction never returns a still-pinned page.
- Hot-set workload fits in pool → 100% hit rate after warmup.
- Cold scan over 10× pool size produces near-100% miss rate (CLOCK doesn't degenerate to LRU-2 needs).

### 3.4 CoW B+ Tree

The core data structure. Single writer, many readers.

- Order chosen so that an internal node holds ≥128 children at typical key sizes (final number falls out of the page format).
- Variable-length keys; values up to ~½ page inline; larger values spill into `OVERFLOW` page chains.
- Every modification clones the path from leaf to root, returning a **new root `PageId`**. Old pages remain valid for any snapshot still pointing at the prior root.
- A node-split or merge is a cloning operation — no in-place mutation of pages reachable from a published root.
- Writer maintains a transient `pending_root`; on commit, the new root is published to the metadata page atomically (one-page WAL record + fsync).

**API:**
```java
interface CowBTree {
  Optional<byte[]> get(byte[] key);
  void put(byte[] key, byte[] value);              // batches into the writer's pending root
  void delete(byte[] key);
  Cursor scan(byte[] startInclusive, byte[] endExclusive);
  Snapshot snapshot();                              // captures current published root
  CommitResult commit();                            // publishes pending root via WAL
  void abort();                                     // discards pending pages
}
```

**Acceptance:**
- Property-based test (1M randomized runs) with a `TreeMap<ByteBuffer,ByteBuffer>` oracle: every sequence of `put/get/delete/scan/snapshot` matches the oracle exactly.
- Snapshot taken at root R₀ continues to return R₀'s view after ≥100 GiB of subsequent writes.
- No published page is ever mutated in place (verified by a debug mode that hashes pages on publish and rechecks on read).

### 3.5 Write-Ahead Log

- Append-only segment files (`wal.000001`, ...), default 64 MiB each.
- Records: `BEGIN`, `PAGE_IMAGE` (full-page write since last checkpoint), `PAGE_DELTA` (slot-level), `ROOT_PUBLISH`, `COMMIT`, `CHECKPOINT_BEGIN`, `CHECKPOINT_END`, `FREELIST_UPDATE`.
- Each record carries an LSN (file-position-based) and a CRC32C. A torn tail record is detected and truncated on recovery.
- **Group commit:** writers append to an in-memory ring; a flusher thread fsyncs at most once per `commit_flush_interval` (default 2 ms) or when the ring crosses a byte threshold.
- Checkpoint cadence: time-based (default 30 s) or WAL-bytes-based (default 256 MiB), whichever fires first. A checkpoint flushes all dirty buffer pool pages and writes `CHECKPOINT_END` with the current published root LSN.
- Full-page writes are emitted for any page modified since the last `CHECKPOINT_END`, mitigating torn pages.

**Acceptance:**
- After `kill -9` at any point during a write workload, recovery converges to a state that contains exactly the transactions whose `COMMIT` record was successfully fsynced.
- A torn final WAL record is truncated, not crash-looped.
- Group commit reduces fsyncs/sec by ≥10× vs. per-commit fsync at 1k commits/sec.

### 3.6 Recovery

On startup:

1. Read the metadata page to find the last checkpoint LSN and the root at checkpoint time.
2. Replay WAL records from `CHECKPOINT_BEGIN` forward.
3. For each `PAGE_IMAGE` / `PAGE_DELTA`, apply to the buffer pool keyed by page id; do **not** fsync per record.
4. On `ROOT_PUBLISH`, advance the in-memory current root.
5. Discard any uncommitted writer state at the tail (no transaction record means abort).
6. Fsync the resulting state, write a new `CHECKPOINT_BEGIN`, and resume.

**Acceptance:**
- A 24-hour `kill -9` torture test (random kill every 1–60 s) converges every restart and never loses an acknowledged commit.
- Recovery time on a 256 MiB WAL completes in <5 s on the developer's box.

### 3.7 Key Encoding

Order-preserving, length-prefixed encoding for primitive types so that lexicographic byte comparison matches semantic ordering. Required for M2 indexes to "just work" on top of the engine.

- `bool` → 1 byte.
- `int{16,32,64}` → big-endian, sign-bit flipped (so negatives sort before positives).
- `float{32,64}` → IEEE 754 with sign-bit flip and (for negatives) full bitwise inversion.
- `text` → UTF-8 bytes terminated by a `0x00` escape sequence (`0x00 → 0x00 0xFF`, terminator `0x00 0x00`).
- `bytea` → same escape scheme as `text`.
- Composite keys → concatenation of the per-field encoded forms.

**Acceptance:**
- Round-trip every primitive type with a randomized test; encoded order matches semantic order for ≥10⁶ randomized pairs per type.

### 3.8 MVCC & Snapshots

- Each successful `commit()` produces a `(commit_ts, root_page_id)` snapshot and bumps a refcount.
- Readers acquire a `Snapshot` handle; pages reachable from any live snapshot must not be reclaimed.
- A `system.snapshots` page (or in-memory table persisted at checkpoint) tracks live snapshot refcounts.
- A configurable `max_snapshot_age` (default 1 hour) lets the reclaimer make progress; older snapshots are forcibly invalidated and subsequent reads through them throw `SnapshotTooOld`.
- `commit_ts` in M1 is a monotonic local counter; HLC arrives in M4. The interface is defined now so the migration is local.

**Acceptance:**
- Holding a snapshot while overwriting every key 100× still returns the original values via the snapshot.
- Releasing the snapshot eventually returns its pages to the free list (reclaimer-driven, verified within N seconds).

### 3.9 Page Reclaimer

A background daemon thread that:

1. Reads the set of live snapshots' root LSNs.
2. Walks pages dirtied between the oldest live snapshot and the current root.
3. For pages no longer reachable from any live root, returns them to the free list via a `FREELIST_UPDATE` WAL record.

**Acceptance:**
- After dropping all snapshots and quiescing writes, the free-page count converges to "all pages not reachable from current root" within `reclaim_interval × 2`.
- Reclaimer never frees a page reachable from any live snapshot (verified by a debug invariant check that runs on every reclamation batch).

### 3.10 Embeddable API

```java
KvEngine engine = KvEngine.open(Path.of("/var/lib/eloydb"), Config.defaults());
try (Txn txn = engine.beginWrite()) {
  txn.put(key, value);
  txn.commit();
}
try (Snapshot snap = engine.snapshot();
     Cursor c = snap.scan(start, end)) {
  while (c.next()) { ... }
}
engine.close();
```

- `KvEngine` is `AutoCloseable`. Close drains the WAL, runs a final checkpoint, and releases off-heap memory.
- All exceptions extend `KvException` with a typed `ErrorCode` enum (`CORRUPTED_PAGE`, `SNAPSHOT_TOO_OLD`, `INSUFFICIENT_RESOURCES`, `IO_ERROR`).

### 3.11 Tooling

- `eloydb-kv` CLI: `init`, `put`, `get`, `scan`, `snapshot`, `stats`, `compact`, `verify`.
- `verify` walks the tree from the current root, recomputes every page CRC, and reports leaks (pages reachable from neither root nor freelist).

## 4. Non-Functional Requirements

### 4.1 Performance Targets (developer box, warm cache)

- Point `get` p50: <50 µs; p99: <500 µs.
- Sequential `put` throughput (1 KiB values, group-commit on): ≥50k ops/sec.
- Range scan: ≥500 MiB/s of decoded value bytes.
- Recovery of a 256 MiB WAL: <5 s.
- GC pauses: ≤10 ms p99 under sustained 50k ops/sec write load (validated under ZGC).

### 4.2 Resource Discipline

- All page memory is off-heap. The on-heap surface per page is a fixed-size `PageHandle` (≈64 bytes).
- No allocation in the steady-state read path (verified by JFR allocation profiling — zero TLAB allocations per `get` after warmup).

### 4.3 Observability

A minimum set of counters/gauges, exposed via a simple in-process `Metrics` registry (Prometheus wiring lives in M2):

- `bufferpool.hits`, `.misses`, `.evictions`, `.dirty_pages`.
- `wal.bytes_written`, `.fsyncs`, `.group_commit_size`.
- `tree.pages_allocated`, `.pages_freed`, `.height`.
- `snapshots.live`, `.oldest_age_seconds`.
- `reclaimer.pages_reclaimed`, `.last_run_duration`.

### 4.4 Configuration

A `Config` record with documented defaults; loadable from `eloydb.toml`.

| Key | Default | Notes |
|---|---|---|
| `page_size` | 8192 | Fixed in M1; field exists for future. |
| `buffer_pool.bytes` | 256 MiB | |
| `buffer_pool.shards` | 16 | |
| `wal.segment_bytes` | 64 MiB | |
| `wal.flush_interval_ms` | 2 | Group-commit window. |
| `checkpoint.interval_ms` | 30000 | |
| `checkpoint.wal_bytes` | 256 MiB | |
| `snapshot.max_age_ms` | 3600000 | |
| `reclaimer.interval_ms` | 5000 | |

## 5. Acceptance Criteria

M1 is **done** when *all* of the following hold simultaneously on a clean build:

### 5.1 Functional

- [ ] **F1** — A property-based test of `{put, get, delete, scan, snapshot}` against a `TreeMap` oracle passes 1,000,000 randomized runs with no diff.
- [ ] **F2** — 1M random KV writes followed by 1M random reads produce zero data-integrity failures.
- [ ] **F3** — A snapshot held during a workload that overwrites every key ≥100× still returns the original values.
- [ ] **F4** — Releasing all snapshots and quiescing writes drains the free-list to expected steady state within 2× `reclaimer.interval_ms`.
- [ ] **F5** — `eloydb-kv verify` reports zero unreachable pages (no leaks) after a mixed workload.

### 5.2 Durability & Recovery

- [ ] **D1** — A 24-hour `kill -9` torture test (random kill every 1–60 s, continuous writes) converges every restart with zero acknowledged-commit losses and zero corruption.
- [ ] **D2** — Torn final WAL records are detected, truncated, and reported once at startup; the engine then resumes normally.
- [ ] **D3** — A simulated bit-flip in any committed page is detected on read with a `CORRUPTED_PAGE` error referencing the page id.
- [ ] **D4** — Recovery of a 256 MiB WAL completes within 5 s on the developer's box.

### 5.3 Performance

- [ ] **P1** — Point `get` p99 <500 µs warm.
- [ ] **P2** — Sequential `put` ≥50k ops/sec sustained (1 KiB values, group commit on).
- [ ] **P3** — Steady-state read path produces zero on-heap allocations per op (JFR/async-profiler verified).
- [ ] **P4** — GC pauses ≤10 ms p99 under sustained write load with ZGC.

### 5.4 Engineering Hygiene

- [ ] **E1** — All public API has Javadoc with at least one usage example.
- [ ] **E2** — Crash-injection harness, property-based test suite, and benchmark harness all run from a single Maven goal each.
- [ ] **E3** — A 5-minute "smoke" CI job runs F1 (10k iterations), F2 (100k ops), and a 30-second crash-injection cycle on every commit.
- [ ] **E4** — A "soak" job (D1, F1@1M, P1–P2) runs nightly with a fail-on-regression budget.

## 6. Test Strategy

| Level | What it covers | Tool / Approach |
|---|---|---|
| Unit | Slot encoding, key encoding, CRC, free-list math. | JUnit 5. |
| Property | B+ tree vs. `TreeMap` oracle; key encoding round-trips; WAL replay determinism. | jqwik. |
| Integration | Open/close cycles, recovery, checkpoint, reclamation. | JUnit 5 + temp dirs. |
| Crash injection | `kill -9` at random points; bit-flip in page/WAL; truncated WAL tail. | Custom harness driving a child process. |
| Stress / soak | 24h continuous writes with random kills; held-snapshot durability. | Nightly job. |
| Benchmark | Latency/throughput targets P1–P4. | JMH. |

## 7. Deliverables

- Maven module `eloydb-kv` containing the engine and CLI.
- Source under `src/main/java`, tests under `src/test/java`, JMH benchmarks under `src/jmh/java`.
- `docs/design/STORAGE.md` — internal design notes (page layout, WAL record formats, recovery algorithm).
- `docs/runbook/KV_TORTURE.md` — how to run the crash-injection harness locally.
- A short demo script (`scripts/m1-demo.sh`) that creates a store, runs 1M ops, takes a snapshot, kills the process mid-write, recovers, and prints `verify` output.

## 8. Risks & Mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Subtle CoW invariants violated under load (in-place mutation of a published page). | High | Debug-mode page hashing on publish + recheck on read; property tests. |
| WAL group-commit introduces a latency cliff. | Medium | Tunable flush interval; benchmark P2 covers it. |
| Off-heap memory leaks via unreleased `MemorySegment`. | Medium | All `MemorySegment` lifetimes tied to `Arena`; close-on-engine-close test verified with JFR. |
| Reclaimer races with snapshot acquisition (frees a still-needed page). | High | Snapshot acquisition increments refcount under the same lock the reclaimer reads; integration test that exercises the race deliberately. |
| 24h soak rarely runs because it's painful. | Medium | Nightly CI job, not "run when you remember." |

## 9. Exit Checklist

When the boxes in §5 are checked and the deliverables in §7 land on `main`, M1 is closed. The next milestone (M2 — Single-Node SQL) consumes this engine through the `KvEngine` API only; any deficiency surfaced during M2 that requires reaching back into the engine is treated as an M1 bug, not new M2 scope.
