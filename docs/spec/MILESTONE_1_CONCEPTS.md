# Milestone 1 Concepts: First-Principles Guide

> Companion learning guide for [MILESTONE_1.md](./MILESTONE_1.md).
> Audience: someone who can program, but is new to database internals.

## 1. What Milestone 1 Is Building

Milestone 1 is not a SQL database yet. It is the storage engine underneath one.

At the smallest useful level, a storage engine answers:

- Where are bytes stored on disk?
- How do we find the bytes for a key quickly?
- How do we avoid losing acknowledged writes if the process or machine crashes?
- How do readers see a stable view while writes continue?
- How do we know the files are not corrupted?
- How do we reuse disk space safely?

The milestone chooses these answers:

- Store data in fixed-size 8 KiB pages.
- Cache pages in an off-heap buffer pool.
- Organize key/value records in a Copy-on-Write B+ tree.
- Record changes in a write-ahead log before treating commits as durable.
- Recover after crash by replaying the log.
- Give readers snapshot isolation by keeping old tree roots alive.
- Reclaim pages only when no live snapshot can reach them.

The engine is "boring" when it can be killed at arbitrary points and restart into a consistent state without losing committed data.

## 2. Recommended Learning Resources

Start with courses and visual material before books. The first goal is intuition: pages, trees, logs, and snapshots.

### Beginner Path

1. [CMU 15-445/645 Database Systems](https://15445.courses.cs.cmu.edu/)

   Best overall course for building database internals. Focus first on lectures about storage, buffer pools, tree indexes, concurrency control, logging, and recovery.

2. [Berkeley CS 186](https://cs186berkeley.net/)

   More beginner-friendly notes. Read the notes on disks/files, B+ trees, buffer management, transactions, and recovery.

3. [B+ Tree visualization from the University of San Francisco](https://www.cs.usfca.edu/~galles/visualization/BTree.html)

   Use this to see insertions, splits, search paths, and tree height. The milestone uses a B+ tree rather than a plain B-tree, but the visualization is still useful for understanding multiway search trees.

4. [PostgreSQL documentation: Write-Ahead Logging](https://www.postgresql.org/docs/current/wal.html)

   Good practical explanation of why databases write log records before flushing data pages.

5. [PostgreSQL documentation: MVCC introduction](https://www.postgresql.org/docs/current/mvcc-intro.html)

   PostgreSQL uses row-version MVCC, while this milestone uses root/snapshot MVCC. The first principle is the same: readers should see a consistent historical view.

### Deeper Path

6. [Database Internals by Alex Petrov](https://www.databass.dev/)

   The closest book match for this milestone. The relevant parts are storage engines, B-trees, page layouts, buffer management, WAL, and recovery.

7. [Designing Data-Intensive Applications by Martin Kleppmann](https://dataintensive.net/)

   Best for mental models around logs, durability, transactions, isolation, and tradeoffs. It is broader than this milestone.

8. [Architecture of a Database System](https://dsf.berkeley.edu/papers/fntdb07-architecture.pdf)

   A classic systems paper. Read later, after the basic page/tree/log concepts feel concrete.

9. [SQLite file format documentation](https://www.sqlite.org/fileformat.html)

   Useful because SQLite is a small, embeddable, page-oriented database. EloyDB's design is not SQLite's design, but the page-level thinking is similar.

10. [SQLite atomic commit documentation](https://www.sqlite.org/atomiccommit.html)

    Good practical reading for crash safety, filesystem assumptions, fsync, and why durability is subtle.

## 3. The Core Mental Model

Think of a database as a library.

- Disk is the library building. It is large, persistent, and slow to access.
- Memory is your desk. It is small, fast, and erased when the process exits.
- Pages are books or sheets you move between the library and your desk.
- A B+ tree is the catalog that tells you where a key lives.
- The WAL is the librarian's notebook: write down what changed before rearranging the shelves.
- A checkpoint is cleaning up after enough notebook entries have been applied to the real shelves.
- MVCC snapshots are old catalog editions that readers can keep using while a new edition is published.
- Reclamation is recycling old pages once nobody can still find them from any live catalog edition.

The important constraint is that crashes can happen between any two machine instructions. Good database design assumes this from the start.

## 4. Key-Value Engine

A key-value engine stores opaque byte keys and byte values:

```text
put("user:42", "{...}")
get("user:42") -> "{...}"
delete("user:42")
scan("user:", "user;") -> all keys in sorted order in that range
```

There are no tables yet. SQL can later map tables, rows, and indexes into keys and values. For M1, sorted key/value storage is enough.

The word "embeddable" means the engine is a library used by a Java process, not a separate server. There is no network protocol, authentication, or multi-client session layer in this milestone.

## 5. Pages

Disks and SSDs do not work efficiently one byte at a time. Databases group bytes into fixed-size pages. M1 uses 8 KiB pages.

A page has two jobs:

- Store records or metadata in a predictable byte format.
- Be independently readable, writable, checksummable, and recoverable.

M1 page headers contain fields like:

- `magic`: proves the bytes look like an EloyDB page.
- `page_type`: says whether this is a B+ tree leaf, internal node, metadata page, free-list page, and so on.
- `lsn`: identifies the WAL position that produced the page.
- `page_id`: the page's durable identity.
- `crc32c`: detects corruption.

The payload is the rest of the 8 KiB page. Leaf and internal B+ tree pages use a slotted layout.

## 6. Slotted Page Layout

A slotted page solves a simple problem: records are variable length, but a page is fixed length.

The common layout is:

```text
+----------------------+----------------------+----------------------+
| page header          | slot array grows ->  |  <- cell heap grows  |
+----------------------+----------------------+----------------------+
```

The slot array stores small pointers such as `(offset, length)`. The actual key/value bytes live in the heap area at the other end of the page.

Benefits:

- Moving variable-length records only requires updating slot metadata.
- Scanning slots is compact.
- Free space is easy to compute.
- The page can keep keys in sorted slot order without physically sorting the byte payload every time.

## 7. Page Store

The page store maps a logical `PageId` to bytes on disk.

At first principles, a `PageId` is just a stable address. If page 123 exists today, page 123 should still mean the same page after restart unless it was freed and safely reused.

The page store handles:

- Allocating new page ids.
- Reading page bytes by id.
- Writing page bytes by id.
- Tracking free pages that can be reused.
- Growing the file when no free page exists.

The free list matters because databases update data for years. Without page reuse, deletes and rewrites would grow the file forever.

## 8. Buffer Pool

Disk is much slower than memory, so the database caches pages in memory. This cache is the buffer pool.

A buffer pool is not just a hash map. It must also track:

- Which page id is loaded into each frame.
- Whether a page is pinned.
- Whether a page is dirty.
- Which unpinned page should be evicted when memory is full.

Pinned means "someone is currently using this page, so eviction is forbidden." Dirty means "memory has a newer version than disk."

M1 uses CLOCK eviction. CLOCK is a practical approximation of least-recently-used caching. Each page gets a second-chance bit. The eviction hand moves around a ring until it finds an unpinned page that has not been used recently.

The milestone shards the buffer pool so different page ids are managed by different locks. Even though M1 has one writer, readers and background tasks still benefit from avoiding one giant shared lock.

## 9. Off-Heap Memory

Java normally stores objects on the garbage-collected heap. M1 instead stores page bytes off-heap using `MemorySegment`.

The reason is resource discipline:

- Page memory has fixed-size lifetimes controlled by the engine.
- Hot reads should not allocate Java objects.
- Large page caches should not create GC pressure.
- Closing the engine can release page memory predictably.

The tradeoff is that off-heap memory has fewer safety rails. The design needs strict ownership and close/release rules.

## 10. B+ Tree

A B+ tree is a sorted, balanced, multiway search tree tuned for pages.

Binary trees branch two ways. B+ tree nodes can branch hundreds of ways because each node is one page and contains many keys. That keeps tree height small.

Example with three levels:

```text
                 [root]
              /    |    \
       [internal] [internal] [internal]
         / | \       |          / | \
      [leaf][leaf][leaf] ... [leaf][leaf]
```

Internal pages guide the search. Leaf pages hold the actual key/value entries. Leaves are sorted and usually linked, which makes range scans efficient.

Lookup:

1. Start at the root page.
2. Compare the search key with separator keys.
3. Follow the child pointer for the right key range.
4. Repeat until a leaf.
5. Search the leaf for the key.

Range scan:

1. Find the first leaf containing `startInclusive`.
2. Walk entries in sorted order.
3. Continue across leaf links until `endExclusive`.

Splits happen when a page is full. The tree divides records between two pages and pushes a separator key upward. If the parent is full, the split can cascade to the root, increasing tree height by one.

## 11. Copy-on-Write B+ Tree

A normal B+ tree might mutate pages in place. M1 deliberately avoids mutating any page reachable from a published root.

Instead, each write clones the path from root to leaf:

```text
old root -> old internal -> old leaf

new root -> new internal -> new leaf
```

Pages not on the modified path can be shared between the old root and new root.

This has a powerful consequence: a snapshot can keep the old root and still read the old tree while the writer publishes a new root.

The cost is extra write amplification. Updating one key writes a new leaf and each ancestor page up to the root. M1 accepts that cost to get simple snapshot semantics and crash recovery.

## 12. Root Publishing

The current tree is identified by its root page id. Publishing a commit means making a new root durable and visible.

The root is the small piece of metadata that makes a whole tree version reachable. If the engine publishes root `R2`, then future readers start from `R2`. Readers that already captured `R1` still start from `R1`.

This is why the milestone treats `(commit_ts, root_page_id)` as the snapshot identity.

## 13. Write-Ahead Log

The write-ahead log is the durability backbone.

The first principle is:

> Before a database page change is allowed to matter, the description of that change must be safely written to the log.

This lets the engine recover if it crashes after acknowledging a commit but before all changed pages reach their final disk locations.

M1 WAL records include:

- `PAGE_IMAGE`: a full page image.
- `PAGE_DELTA`: a smaller change to a page.
- `ROOT_PUBLISH`: the new tree root.
- `COMMIT`: the transaction is durable.
- `CHECKPOINT_*`: recovery can start from a later point.
- `FREELIST_UPDATE`: page reuse metadata changed.

Each record has an LSN and CRC. The LSN is a durable ordering position. The CRC detects torn or corrupted records.

## 14. Fsync and Group Commit

Writing bytes to a file is not the same as forcing them to durable storage. Operating systems and drives buffer writes.

`fsync` asks the OS to flush file contents to stable storage. It is expensive, so doing one fsync per commit can limit throughput.

Group commit batches multiple commits behind one fsync:

```text
commit A arrives
commit B arrives
commit C arrives
fsync once
acknowledge A, B, C
```

The tradeoff is a tiny latency window, controlled by `wal.flush_interval_ms`, in exchange for much higher throughput.

## 15. Torn Pages and Full-Page Writes

A page write is not always atomic. If the process or machine crashes while writing an 8 KiB page, the page may contain a mix of old and new bytes. This is a torn page.

M1 mitigates this with:

- Page CRCs to detect bad page images.
- WAL CRCs to detect bad log records.
- Full-page writes after checkpoints so recovery can restore a complete page image.

The key idea is that recovery should never have to trust a half-written page without enough log information to repair or reject it.

## 16. Checkpoints

Without checkpoints, startup would need to replay the entire WAL from the beginning of time.

A checkpoint says:

- Dirty pages up to a known point have been flushed.
- The root at that point is known.
- Recovery can start from this LSN instead of the first WAL segment.

Checkpoints trade runtime IO for faster restart and bounded WAL growth.

## 17. Crash Recovery

Recovery is what happens on startup after an unclean shutdown.

M1 recovery roughly does:

1. Read metadata to find the last checkpoint.
2. Replay WAL records from that point.
3. Apply page images and deltas in LSN order.
4. Advance the current root when committed root-publish records are found.
5. Ignore incomplete or uncommitted tail state.
6. Flush the recovered state and resume normal service.

The acceptance tests define the real contract: after `kill -9`, the engine must contain exactly the commits whose commit records were durably fsynced.

## 18. MVCC and Snapshots

MVCC means Multi-Version Concurrency Control.

The basic idea is that the database keeps multiple versions so readers do not need to block writers.

Many databases implement MVCC by keeping old row versions. M1 implements it at the tree-root level:

```text
commit 10 -> root page 500
commit 11 -> root page 812
commit 12 -> root page 940
```

A snapshot captures one root. All reads through that snapshot traverse from that root and therefore see that version of the database.

This is snapshot isolation for reads. If a snapshot starts at commit 10, later commits do not change what that snapshot sees.

## 19. Reference Counts and Snapshot Lifetime

Old pages cannot be freed while a snapshot can still reach them.

M1 tracks live snapshots with reference counts. When a reader opens a snapshot, the engine records that the root is still in use. When the reader closes the snapshot, the reference can be released.

Long-lived snapshots are dangerous because they keep old pages alive and prevent space reuse. That is why the milestone includes `max_snapshot_age` and `SnapshotTooOld`.

## 20. Page Reclaimer

Copy-on-write creates old pages. Some are still needed by snapshots. Some are garbage.

The reclaimer's job is to find pages no live root can reach and return them to the free list.

The hard part is correctness, not the traversal itself. Freeing a page too late wastes space. Freeing a page too early corrupts a live snapshot.

The safe rule is:

> A page can be reused only after proving no live snapshot root can reach it.

## 21. Key Encoding

B+ trees compare keys as bytes. If the engine wants SQL-style primitive values later, their byte encodings must sort the same way the values sort.

Simple cases:

- Booleans can use one byte.
- Unsigned big-endian integers sort correctly as bytes.

Signed integers need adjustment because normal two's-complement bytes put negative values after positive values. M1 flips the sign bit so byte ordering matches numeric ordering.

Floating-point values are trickier because IEEE 754 sign/exponent/mantissa layout does not naturally match numeric order across negative and positive values. The milestone uses a standard transform: flip the sign bit for positives and invert all bits for negatives.

Text and bytes need terminators and escaping so composite keys can be concatenated without ambiguity.

Composite keys matter because future SQL indexes may encode something like:

```text
(table_id, index_id, last_name, first_name, row_id)
```

If each field preserves ordering, the concatenated key can support efficient range scans.

## 22. CRCs and Corruption Detection

A CRC is not encryption and not a proof of correctness. It is a fast checksum that catches accidental corruption with high probability.

M1 uses CRCs in two places:

- Page CRCs detect damaged page bytes.
- WAL record CRCs detect damaged or torn log records.

This changes failure behavior. Instead of silently returning wrong data, the engine reports a typed corruption error with the page id or log position.

## 23. Property-Based Testing

Example-based tests check a few hand-written cases. Property-based tests generate many random cases and check broad invariants.

For M1, the B+ tree can be tested against Java's `TreeMap`:

- Generate random operations: `put`, `get`, `delete`, `scan`, `snapshot`.
- Apply each operation to EloyDB.
- Apply the same operation to a simple oracle.
- Compare results after every step.

This is useful because tree bugs often appear only after rare split, merge, delete, and snapshot combinations.

## 24. Crash Injection

Crash injection tests the assumption that the process can die anywhere.

A harness repeatedly:

1. Starts a child process running writes.
2. Kills it with `kill -9` at random times.
3. Restarts the engine.
4. Verifies committed data and structural invariants.

This catches bugs that normal unit tests miss, especially missing fsyncs, bad WAL ordering, torn-page handling, and incomplete recovery rules.

## 25. Metrics

Metrics are not just operational polish. For a storage engine, they make performance bugs diagnosable.

Useful examples:

- Buffer pool hit/miss counts explain whether reads are going to memory or disk.
- WAL fsync counts reveal whether group commit is working.
- Tree height shows whether fanout and splits are healthy.
- Live snapshot count explains why space is not being reclaimed.
- Reclaimer duration shows whether cleanup is falling behind.

## 26. How The Pieces Fit Together During A Write

For a `put(key, value)` followed by `commit()`:

1. The writer searches the current B+ tree root.
2. It clones the path to the target leaf.
3. It inserts or replaces the key/value in the new leaf.
4. Splits clone and update parent pages as needed.
5. The writer now has a pending new root.
6. WAL records are appended for changed pages and root publication.
7. The commit record is fsynced, possibly with other commits via group commit.
8. The new root becomes the published root.
9. Old roots remain readable by snapshots.
10. Later, checkpoints flush dirty pages.
11. Later still, the reclaimer frees unreachable old pages.

## 27. How The Pieces Fit Together During A Read

For `get(key)`:

1. The reader chooses a snapshot root.
2. It pins the root page in the buffer pool.
3. It descends through internal pages by comparing encoded keys.
4. It pins the target leaf page.
5. It searches the leaf's slots.
6. It returns the value bytes if found.
7. It unpins pages.

If the pages are already in the buffer pool, the read should avoid disk IO. M1 also aims for zero steady-state Java heap allocations on this path.

## 28. What To Study In Order

Recommended order:

1. Files, pages, and page ids.
2. Slotted pages.
3. B+ tree lookup and range scan.
4. B+ tree insertion and splitting.
5. Buffer pools and eviction.
6. Dirty pages, fsync, and WAL.
7. Checkpoints and crash recovery.
8. Copy-on-write trees.
9. MVCC snapshots.
10. Reclamation and free lists.
11. Key encoding.
12. Property-based and crash-injection testing.

Do not start with MVCC or recovery. They become much easier after pages, trees, and WAL are concrete.

## 29. Glossary

- ACID: Database transaction properties: atomicity, consistency, isolation, durability.
- Buffer pool: The database-managed in-memory cache of disk pages.
- Checkpoint: A durable point that lets recovery start later in the WAL.
- Commit: The point where a transaction is considered durable and visible.
- Copy-on-Write: Updating by writing new copies instead of mutating existing shared pages.
- CRC: Fast checksum used to detect accidental corruption.
- Dirty page: A cached page changed in memory but not yet flushed to disk.
- Fsync: System call used to force file data to durable storage.
- Key-value engine: Storage layer that maps sorted byte keys to byte values.
- Leaf page: B+ tree page containing actual key/value entries.
- LSN: Log Sequence Number; position/order identity in the WAL.
- MVCC: Multi-Version Concurrency Control; keeping versions so readers and writers can coexist.
- Page: Fixed-size block of database storage.
- Page id: Stable logical identifier for a page.
- Pinned page: Buffer-pool page currently in use and not eligible for eviction.
- Reclaimer: Background process that frees pages no live snapshot can reach.
- Root page: Entry point to a B+ tree version.
- Snapshot: Stable read view of the database at one commit/root.
- Slotted page: Page layout for variable-length records using slot pointers.
- Torn page: Partially written page after crash or power loss.
- WAL: Write-Ahead Log; append-only durability log written before data-page changes are relied on.

