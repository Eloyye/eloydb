# Milestone 1 Leftover Work

Status as of this review: M1 now has a page-backed Copy-on-Write B+ tree, a single-file
`PageStore` with a persisted free list, overflow page chains for large values, and slotted
leaf/internal pages. The in-memory `TreeMap` engine state has been removed. WAL, MVCC reclamation,
zero-copy page decoding, and most of the M1 acceptance criteria still remain open.

## Changelog For This Pass

Date: 2026-05-18.

- Added `PageStore` (single-file 8 KiB page allocator with persisted free list and meta page),
  `SlottedPage` (codec for leaf/internal payloads), `OverflowChain` (large-value spillover),
  `MetaPayload` (encode/decode the meta page).
- Added `CowBTree` under a new `org.eloydb.kv.btree` package, plus `CowBTreeTest` (split, overflow,
  randomised oracle) and `PageStoreTest` (alloc/free, persisted free list, corrupted meta).
- Rewrote `KeyValueEngine` to drive the new page-backed tree: open replays the WAL into the
  on-disk tree, every commit allocates new pages along the CoW path, writes them, fsyncs the store,
  and rewrites the meta page with the new `root_pid` and `commit_ts`. The in-memory `TreeMap`
  representation was deleted.
- `EngineSnapshot` now has two modes: root-backed (pins a `(commit_ts, root_pid)` pair, no copy)
  and overlay-backed (used by `Transaction.snapshot()` to layer uncommitted writes on top of the
  committed tree).
- `WriteAheadLog.RecoveryResult` now returns `List<CommittedTransaction>` instead of a `TreeMap`,
  which the engine replays onto the tree.
- `engine/Cursors.java` was removed; `Cursor` instances are now constructed via `ListCursor` from
  either `tree.scan(...)` or an overlay submap.
- `Metrics` gains a `freelist.pages` gauge and re-uses `tree.pages_allocated` /
  `tree.pages_freed` as real counters incremented by `PageStore`.
- Added `BufferPool` and `PageHandle`; `KeyValueEngine`, `CowBTree`, and overflow chains now route
  page reads/writes through the buffer pool. Dirty frames flush before meta-root publication and
  on close.

Tests: `mvn verify` is green across 23 JUnit tests. The 5,000-op randomised oracle in
`CowBTreeTest` plus the 1,000-entry split test in `manyInsertsForceSplitsAndStillRetrievable`
exercise leaf splits, internal splits, and a root split; `BufferPoolTest` covers pinning, CLOCK
eviction, dirty flush, and hit/miss metrics.

## Known Blockers Carried Forward

- **Stale pages from CoW path are never freed.** Every commit allocates fresh pages along the
  modified path and the previous pages remain reachable from no live root once the new root is
  published. They sit at their original file offsets and are not added to the persisted free list,
  because the page reclaimer is itself out of scope for this milestone. Consequence: the
  `store.0001` file grows monotonically with each commit; long-running engines will leak. Tracked
  under "MVCC And Reclamation Gaps".
- **Overflow chains from replaced values leak.** When an inline `put` replaces an existing value
  that lives in an overflow chain, the old chain stays allocated for the same reason as above.
- **WAL replay re-creates pages on every restart.** Recovery currently replays all WAL records
  with `commit_ts > meta.commit_ts` onto the tree, allocating fresh page ids. After a crash mid
  commit, every page allocated by the original commit becomes orphaned. The WAL/checkpoint rewrite
  in the next section is the fix.
- **`Page.payload()` allocates a `byte[]` on read.** The slotted-page decoders need a zero-copy
  view onto the underlying `MemorySegment` before P3 (zero on-heap allocation per `get`) can be
  met.
- **No file truncation.** `PageStore` never calls `FileChannel.truncate`; reuse goes through the
  free list. After many deletes the freed pages will be reused but the file high-water mark only
  ever grows.

## Already Present

- Embeddable `KeyValueEngine` API with single active writer, `put`, `get`, `delete`, `scan`,
  `snapshot`, `commit`, and `abort`.
- Append-only `wal.000001` with operation records, commit records, CRC validation, per-commit
  `force(true)`, recovery of committed transactions, and torn-tail truncation.
- Root-backed snapshots that pin `(commit_ts, root_pid)`; max-age invalidation.
- Fixed 8 KiB `Page` primitive with header fields, page type tag, page id check, and CRC32C
  validation.
- Slotted leaf/internal page codecs, page-backed CoW B+ tree, overflow chains, and a free-list
  enabled `PageStore` (see the "Core Storage Status" section above).
- Sharded off-heap `BufferPool` with shard-local hash maps, CLOCK rings, pin/unpin handles, dirty
  tracking, `newPage(type)`, `flushDirtyPages()`, and real buffer-pool metrics.
- Order-preserving encoders for several primitive key types.
- Basic CLI commands: `init`, `put`, `get`, `delete`, `scan`, `snapshot`, `stats`, and `verify`.
- JUnit coverage for basic recovery, snapshot stability, torn WAL tail handling, page CRC
  detection, key ordering smoke tests, the 10k randomised `TreeMap` oracle, plus the new tree-
  split / overflow / page-store tests added with this change.

## Core Storage Status

Landed in this change (see `src/main/java/org/eloydb/kv/btree/` and
`src/main/java/org/eloydb/kv/storage/`):

- **Page-backed CoW B+ tree** (`CowBTree`): every `put`/`delete` clones the path from root to leaf
  and returns a new root `PageId`. The previous root remains valid for any reader still pointing at
  it.
- **Slotted leaf and internal pages** (`SlottedPage`): variable-length keys, forward-growing slot
  array, backward-growing cell heap, single-shot builders for cheap CoW writes.
- **Leaf and internal page splits**: written through `SlottedPage.LeafBuilder.fits()` /
  `InternalBuilder.fits()`; mid-point chosen by running byte cost. Root split synthesises a new
  internal node.
- **Overflow page chains** (`OverflowChain`, `PageType.OVERFLOW`): leaf values larger than
  `SlottedPage.MAX_INLINE_VALUE_LEN` (1500 B) spill into a linked chain of overflow pages; up to
  `OverflowChain.CHUNK_CAPACITY` (~8152 B) per page.
- **`store.0001` page store** (`PageStore`): single file, 8 KiB pages, page id ⇒ offset is
  `pid * 8192`. Uses `FileChannel` + `MemorySegment` for IO; allocation prefers the persisted
  free-list (`PageType.FREELIST` chain) before extending the file; allocation, free count, and
  free-list head survive restart through the meta page (`MetaPayload`).
- **Debug "no in-place mutation" check**: `PageStore.open(dir, metrics, true)` records a SHA-256
  on every published non-meta page and re-checks it on read; mismatches raise
  `PageCorruptedException`.

Still **not** done in this section:

- **Page merges / rebalancing on under-fill.** `delete` removes from a leaf but never merges; trees
  shrink only when the only entry in the only leaf is removed. The reclaimer that would reclaim the
  resulting under-utilised pages is also out of scope (see "MVCC And Reclamation Gaps").
- **Truncation of the store file** when many pages are freed; the file currently grows
  monotonically.
- **Live-page verification in `eloydb-kv verify`.** `verify` now walks the tree and counts reachable
  pages but does not yet diff against the free list or detect leaks.
- **Off-heap-only hot path.** The buffer pool stores pages in direct buffers, but reads still decode
  a `Page` whose `payload()` returns a `byte[]`. Downstream slotted-page decoders also consume
  `byte[]`. Eliminating per-`get` allocations needs zero-copy decoding from pinned frames.

## Buffer Pool Status

Landed in this change:

- Sharded off-heap buffer pool with shard-local hash tables, CLOCK rings, pin/unpin handles, dirty
  tracking, and `newPage(type)`.
- Eviction skips pinned pages and fails with `INSUFFICIENT_RESOURCES` if every frame in the shard
  is pinned.
- `KeyValueEngine`, `CowBTree`, and `OverflowChain` now use the buffer pool. Commits flush dirty
  buffer-pool pages before rewriting the meta root and fsyncing `store.0001`.
- Real `bufferpool.hits`, `bufferpool.misses`, `bufferpool.evictions`, and
  `bufferpool.dirty_pages` metrics are registered by `BufferPool`.

Still open:

- Full checkpoint/WAL page-image integration. The current commit path flushes dirty pages before
  root publication, but M1's `PAGE_IMAGE`, `ROOT_PUBLISH`, and checkpoint LSN rules are not in
  place yet.
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
