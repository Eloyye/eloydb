# EloyDB KV Storage Design

Milestone 1 now has a readable storage foundation under `org.eloydb.kv`.

## Current Shape

- `KvEngine` is the embeddable entry point.
- `Txn` is single-writer and publishes only through `commit()`.
- `Snapshot` is an immutable read view. It is invalidated when it exceeds `Config.maxSnapshotAge()`.
- `Wal` is append-only and CRC-protected. Recovery applies only transactions with an intact `COMMIT` record.
- `Page` implements the fixed 8 KiB page header, page type tags, page id sanity check, and CRC32C validation.
- `KeyEncoding` provides order-preserving primitive encoders for future SQL/index keys.

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

## Follow-up Work

This implementation intentionally separates public API, log recovery, page integrity, and key encoding so a true page-backed Copy-on-Write B+ tree can replace the current in-memory `TreeMap` view without changing callers.
