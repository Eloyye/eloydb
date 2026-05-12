# Planning

> Stage 1 of 3 — Last updated: 2026-05-12

## Overview

A from-scratch distributed relational database engine built in Java, in the spirit of PostgreSQL (rich SQL surface) and CockroachDB (range-sharded, Raft-replicated, distributed SQL). The project's primary purpose is **learning**: building every layer of a modern RDBMS — wire protocol, parser, planner, execution engine, MVCC, storage engine, replication, distributed transactions — without depending on existing database components. The storage engine is a **Copy-on-Write B+ tree**. AI-adjacent capabilities are scoped to a `VECTOR(N)` type with HNSW similarity search and one ML-driven auto-tuning feature (index advisor).

## Goals

- Implement every major subsystem of an RDBMS from scratch — no third-party database engines or B-tree libraries.
- Use a Copy-on-Write B+ tree as the storage engine, leveraging its elegant fit with MVCC and snapshot reads.
- Support a usable subset of PostgreSQL SQL through a custom binary wire protocol and parser.
- Run as a multi-node cluster with range-based sharding, per-range Raft replication, HLC timestamps, and serializable distributed transactions.
- Provide a `VECTOR(N)` type with an HNSW index, integrated into the cost-based planner so hybrid (filter + similarity) queries are optimized.
- Ship at least one ML-driven auto-tuning feature (index advisor as the primary target).
- Reach end-to-end SQL on a single node as the first major milestone; a working three-node cluster as the second.

## Constraints

- Single developer, evening/weekend pace.
- **Java 22+** required (Foreign Function & Memory API for off-heap storage, Vector API for SIMD, virtual threads).
- No reliance on external database engines (no RocksDB, no embedded SQLite, no third-party B-tree). Infrastructure libraries (Netty, ANTLR, Micrometer, OpenTelemetry) are permitted.
- Learning project only — production deployment is explicitly not a goal.

## Out of Scope

- Production-grade availability, performance, or operational tooling.
- Multi-tenancy (resource isolation, per-tenant quotas).
- Stored procedures, `PL/pgSQL`, triggers, table inheritance, materialized views.
- Full PostgreSQL wire compatibility — the project uses a custom wire protocol.
- Encryption at rest.
- Backup/restore beyond cold cluster shutdown.
- Multi-region / geo-distributed awareness (single-DC only).
- AI directions other than vector search and one auto-tuning feature (no natural-language-to-SQL, no learned indexes, no learned optimizer).

## Risks & Assumptions

- **Concurrency correctness on a CoW B+ tree is subtle.** Mitigation: property-based testing and crash-injection testing from day one.
- **Distributed transactions are the hardest subsystem.** Mitigation: only attempted after the single-node engine is rock-solid; first version is plain 2PC.
- **Scope is multi-year.** Mitigation: each phase ends at a satisfying, demonstrable milestone so motivation is sustained even if later phases are deferred.
- **Java GC is acceptable if off-heap storage is used aggressively.** Assumes ZGC + `MemorySegment`-backed pages and operator state keep pauses below 10 ms p99.
- **A pure-Java Raft implementation is feasible within ~2–3 weeks.** Fallback: adopt Apache Ratis or a port of etcd-raft if the from-scratch attempt stalls.

## Build Timeline (evening/weekend pace, single developer)

| Phase | Scope | Estimate |
|-------|-------|----------|
| **1. Storage foundation** | Page format, off-heap buffer pool, single-threaded CoW B+ tree, WAL, force-at-commit recovery, key encoding, MVCC via snapshot roots | 4–6 months |
| **2. Single-node SQL** | Lexer, parser, AST, basic planner, Volcano executor, Netty wire protocol, end-to-end SELECT/INSERT/UPDATE/DELETE | 3–4 months |
| **3. Query sophistication** | Cost-based planner with statistics, join algorithms, subquery unnesting, secondary indexes, vectorized executor on `MemorySegment` columns | 3–5 months |
| **4. Distribution** | Raft per range, HLC, leaseholders, distributed transactions (2PC then Percolator-style intents), gossip, range splits, rebalancer | 6–9 months |
| **5. Vector + AI** | `VECTOR(N)` type, HNSW index, planner integration for hybrid queries, distributed top-k | 2–3 months |
| **6. Auto-tuning + polish** | Index advisor, follower reads, online schema changes, `EXPLAIN ANALYZE` polish, admin status endpoint | 2–4 months |

**Total estimate:** ~20–31 months for the full survey. Roughly 2–3 years calendar time at evening/weekend pace.

## Milestones

- **M1 — KV Engine Works.** Single-threaded CoW B+ tree survives crash-injection testing on a synthetic KV workload.
- **M2 — First SQL Query.** A single-node server accepts a connection over the wire protocol and returns rows for `SELECT * FROM t WHERE id = ?`.
- **M3 — Real Database.** Cost-based planner, joins, transactions, secondary indexes. Looks and feels like a small Postgres.
- **M4 — First Cluster.** Three nodes, range-replicated via Raft, distributed transactions working.
- **M5 — Vector Search.** HNSW index integrated into the planner; hybrid filter + vector queries demonstrably faster than brute force.
- **M6 — Self-Tuning.** Index advisor produces useful recommendations from a recorded workload and the recommended index actually improves replayed query time.

## Open Questions

- Which MVCC garbage collection trigger — periodic, watermark-driven by the lowest live snapshot, or hybrid?
- Should the CoW snapshot abstraction be exposed at the SQL layer only via `AS OF SYSTEM TIME`, or also via long-running read-only transactions?
- HNSW only, or also a flat IVF index for smaller datasets where graph build time matters?
- Index advisor alone, or also include adaptive plan correction (persisting runtime cardinality errors back into the planner)?
- Pure-Java Raft from scratch, or accept Apache Ratis / a port of etcd-raft as a starting point?
- Closed-timestamp interval for follower reads: aggressive (~100 ms) or conservative (~1 s)?