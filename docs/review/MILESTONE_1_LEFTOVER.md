# Milestone 1 Leftover Work

Status as of this review: M1 has a working API scaffold and a small durable in-memory KV engine, but it is not yet the page-backed CoW storage engine described in `docs/spec/MILESTONE_1.md`.

## Already Present

- Embeddable `KeyValueEngine` API with single active writer, `put`, `get`, `delete`, `scan`, `snapshot`, `commit`, and `abort`.
- Append-only `wal.000001` with operation records, commit records, CRC validation, per-commit `force(true)`, recovery of committed transactions, and torn-tail truncation.
- Snapshot read views backed by copied `TreeMap` state, plus max-age invalidation.
- Fixed 8 KiB `Page` primitive with header fields, page type tag, page id check, and CRC32C validation.
- Order-preserving encoders for several primitive key types.
- Basic CLI commands: `init`, `put`, `get`, `delete`, `scan`, `snapshot`, `stats`, and `verify`.
- Focused JUnit coverage for basic recovery, snapshot stability, torn WAL tail handling, page CRC detection, key ordering smoke tests, and a 10k randomized `TreeMap` oracle test.

## Core Storage Still Unimplemented

- Replace the current committed `TreeMap` with an actual page-backed Copy-on-Write B+ tree.
- Implement slotted internal and leaf pages, variable-length keys, page splits, page merges/rebalancing, and root publication by `PageId`.
- Support values larger than the inline page threshold through `OVERFLOW` page chains.
- Enforce the no-in-place-mutation invariant for all published pages, ideally with the spec's debug page hashing.
- Add a real page store (`store.0001`) with stable 64-bit page allocation, page id to file offset mapping, persisted free-list pages, restart-safe allocation, truncation/extension behavior, and live-page verification.
- Move hot page IO to `FileChannel` plus direct/off-heap buffers rather than heap arrays in the operational path.

## Buffer Pool Still Unimplemented

- Add the sharded off-heap buffer pool described by M1: shard-local hash tables, CLOCK rings, pin/unpin handles, dirty tracking, and `newPage(type)`.
- Ensure eviction never selects pinned pages.
- Wire dirty page flushing through WAL/checkpoint rules.
- Replace placeholder buffer-pool metrics with real hit, miss, eviction, and dirty-page values.
- Validate warm hot-set and cold-scan behavior against the acceptance targets.

## WAL, Checkpointing, And Recovery Gaps

- Expand WAL record types from the current `PUT`, `DELETE`, `COMMIT` model to the M1 record set: `BEGIN`, `PAGE_IMAGE`, `PAGE_DELTA`, `ROOT_PUBLISH`, `COMMIT`, `CHECKPOINT_BEGIN`, `CHECKPOINT_END`, and `FREELIST_UPDATE`.
- Add WAL segment rollover beyond `wal.000001`.
- Implement group commit with the configured flush interval and byte threshold. The current path fsyncs every commit.
- Implement checkpoints that flush dirty pages, record checkpoint roots/LSNs, and bound replay time.
- Emit full-page writes after checkpoints for torn-page protection.
- Recover from metadata/checkpoint state by replaying page images/deltas and root publishes, not by rebuilding a `TreeMap` from logical operations.
- Preserve the spec's durability semantics: only acknowledged fsynced commits survive, uncommitted tail state aborts, and a torn final WAL record is reported once then truncated.

## MVCC And Reclamation Gaps

- Represent snapshots as `(commit_ts, root_page_id)` roots instead of copied maps.
- Track live snapshot refcounts in a persisted or checkpointed snapshot table.
- Make snapshot acquisition and reclamation share the necessary synchronization so the reclaimer cannot free reachable pages.
- Implement the background page reclaimer that walks obsolete roots, identifies pages unreachable from live roots, and returns them to the persisted free list through WAL records.
- Replace placeholder `tree.pages_freed`, `snapshots.oldest_age_seconds`, and reclaimer metrics with real values.

## CLI And Verification Gaps

- Add the M1 `compact` command.
- Make `verify` walk the actual tree from the current root, recompute page CRCs, validate free-list consistency, and report leaked pages reachable from neither roots nor free list.
- Make `stats` reflect real storage, WAL, buffer-pool, snapshot, and reclaimer state.
- Update `scripts/m1-demo.sh` so it performs the promised 1M-op demo, snapshot check, mid-write kill/recovery, and final verify output against the real engine.

## Configuration And Packaging Gaps

- Add `eloydb.toml` loading for the `Config` record.
- Ensure all config fields are wired into runtime behavior. Several values currently exist but do not drive implementation yet, including buffer pool size/shards, WAL segment size, WAL flush interval, checkpoint cadence/bytes, and reclaimer interval.
- Confirm the deliverable shape expected by the spec: Maven module/package naming, CLI entrypoint packaging, and future `src/jmh/java` benchmark location.

## Testing And Harness Gaps

- Expand the randomized oracle test from the current 10k loop to the M1 property target of 1,000,000 randomized runs, including snapshots and range scans.
- Add property tests for key encoding round trips and encoded order across at least the required primitive types and randomized pairs.
- Add WAL replay determinism tests.
- Add free-list allocation/reuse tests, including restart recovery after freeing and reallocating large page sets.
- Add buffer-pool pin/eviction and hit/miss behavior tests.
- Add B+ tree split/merge, overflow value, snapshot-root, and no-published-page-mutation tests.
- Add crash-injection harnesses for random `kill -9`, torn WAL tails, bit-flipped committed pages, and restart convergence.
- Add benchmark harnesses for point `get`, sequential `put`, range scan throughput, recovery time, read-path allocation, and GC pause targets.
- Add CI jobs requested by M1: five-minute smoke job and nightly soak/regression job.

## Acceptance Criteria Still Open

All M1 acceptance criteria in `docs/spec/MILESTONE_1.md` should still be considered open until verified against the real page-backed engine:

- Functional: F1, F2, F3, F4, F5.
- Durability and recovery: D1, D2, D3, D4.
- Performance: P1, P2, P3, P4.
- Engineering hygiene: E1, E2, E3, E4.

The current tests cover small slices of F1, F3, D2, and D3, but not at the required scale or against the final M1 architecture.
