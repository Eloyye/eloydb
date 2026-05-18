# EloyDB KV Storage Design

Milestone 1 now has a readable storage foundation under `org.eloydb.kv`.

## Current Shape

- `KeyValueEngine` is the embeddable entry point.
- Single-writer `Transaction` publishes only through `commit()`.
- `Snapshot` is either root-backed (pins `(commit_ts, root_pid)` against the live store) or
  overlay-backed (writer-visible view that includes uncommitted ops).
- `WriteAheadLog` is append-only and CRC-protected. Recovery applies only transactions with an
  intact `COMMIT` record; the result is a list of `CommittedTransaction` records replayed onto the
  page-backed tree.
- `Page` implements the fixed 8 KiB page header, page type tags, page id sanity check, and CRC32C
  validation.
- `PageStore` owns `store.0001`: 8 KiB pages addressed by `pid * 8192`, allocation through a
  persisted `FREELIST` page chain, file extension via `FileChannel.write` to a fresh offset, all IO
  buffered through arena-owned `MemorySegment` instances.
- `MetaPayload` (page id 0) records `root_pid`, `commit_ts`, `next_alloc_pid`, `freelist_head_pid`,
  and `free_page_count`.
- `SlottedPage` is the codec for the slotted leaf and internal payloads.
- `CowBTree` is the single-writer Copy-on-Write B+ tree. `put` and `delete` always allocate fresh
  page ids along the modified path. Leaf and internal splits propagate up to the root; a root
  split synthesises a new internal root.
- `OverflowChain` spills leaf values > `SlottedPage.MAX_INLINE_VALUE_LEN` (1500 B) across linked
  `OVERFLOW` pages.
- `KeyEncoding` provides order-preserving primitive encoders for future SQL/index keys.

## Commit Path

1. Buffer `put`/`delete` operations in `WriteTransaction`.
2. On `commit()`, append the WAL records and `force(true)` — this is the durability boundary.
3. Apply the operations to the CoW tree, computing a new `root_pid`.
4. Write the dirtied pages to `store.0001` and rewrite the meta page with `(root_pid, commit_ts)`.
5. `force(true)` the store.

A crash between step 2 and step 5 is recovered by reading the meta page, then replaying any WAL
records with `commit_ts > meta.commit_ts` onto the persisted tree.

## WAL Record Format

Each record is:

```text
magic:int32 | payload_length:int32 | type:uint8 | tx_id:int64 | payload | crc32c:int32
```

Operation payloads carry key and value lengths followed by bytes. Commit payloads carry the monotonic commit timestamp. On startup, the engine groups records by transaction id and applies a group only when its commit record is present and CRC-valid. A torn or corrupt tail is truncated.

## Page Format

`Page` serializes to one 8192-byte `MemorySegment`:

```text
magic:int32 | type:uint8 | flags:uint8 | version:uint16 | lsn:int64 | page_id:int64 | crc32c:int32 | payload...
```

CRC is computed over the full page with the CRC field zeroed.

## Slotted Page Layout

Both leaf and internal pages share the slotted layout: a forward-growing slot array of cell
offsets at the top of the payload and a backward-growing cell heap at the bottom.

**Leaf payload** (`8164` bytes after the page header):

```text
u16 slot_count
u16 cell_heap_start
slots[slot_count]: u16 cell_offset           // sorted by encoded key
... free space ...
cells (grown backward from end):
  u16 key_len
  u16 value_marker                           // high bit set ⇒ overflow
  if overflow: u32 total_value_len, u64 overflow_head_pid
  key bytes
  if inline: value bytes
```

**Internal payload**:

```text
u16 slot_count
u16 cell_heap_start
u32 reserved
u64 leftmost_child_pid
slots[slot_count]: u16 cell_offset
... free space ...
cells:
  u16 key_len
  u64 child_pid
  separator key bytes
```

The internal node represents `child_0 = leftmost, (sep_1, child_1), (sep_2, child_2), ...`, where
`sep_i` is the minimum key in `child_i`'s subtree. Lookups binary-search for the rightmost
separator ≤ key and descend into that child (or `leftmost` if all separators exceed the key).

## Free List

`PageStore` maintains a linked list of `PageType.FREELIST` pages rooted at
`MetaPayload.freelistHeadPid`. Each free-list page payload is:

```text
u64 next_pid                                  // 0 ⇒ tail
u32 count
... reserved ...
entries[count]: u64 freed_page_id
```

`allocate(type)` pops the most recently freed id off the head page; if the head empties it is
itself reused. `free(pid)` pushes onto the head, allocating a new head page when the current one
fills (`FREELIST_CAPACITY` ≈ 1019 entries per page).

## Follow-up Work

- Merge / rebalance on leaf and internal under-fill (currently absent).
- Free pages from the previous CoW path once they are no longer reachable from any live snapshot
  (page reclaimer, MVCC refcounts).
- Zero-copy `Page` reads against the underlying `MemorySegment` so the steady-state read path
  produces no on-heap allocations.
- Page-image WAL records, group commit, and checkpoints (currently the WAL still logs logical
  `PUT`/`DELETE`/`COMMIT` records and fsyncs per commit).
