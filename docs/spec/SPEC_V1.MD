# Specification

> Stage 2 of 3 — Last updated: 2026-05-12
> Based on: [docs/PLANNING.md](./PLANNING.md)

## 1. System Overview

The project is a distributed relational database engine implemented in Java. It is composed of identical server nodes that communicate with clients over a custom binary wire protocol and with each other over an internal RPC. Each node hosts a slice of a globally-sharded keyspace, where shards are **ranges** of contiguous keys, and each range is replicated to N nodes via the **Raft** consensus protocol. One replica per range is the **leaseholder** and serves reads and coordinates writes for that range.

A SQL query enters via the wire protocol, is parsed into an AST, planned into a distributed physical plan, and executed across the relevant leaseholders. Storage on each node uses a **Copy-on-Write B+ tree**: every write creates a new path from root to leaf, leaving prior trees intact for snapshot-consistent reads. This gives MVCC, follower reads, and `AS OF SYSTEM TIME` essentially for free.

### 1.1 Subsystems

The system consists of seven layered subsystems:

1. **Transport layer** — connection management, framing, authentication, session state.
2. **SQL frontend** — lexer, parser, AST, type checker, name resolver.
3. **Planner** — logical plan, rule-based rewrites, cost-based optimization, distributed physical plan.
4. **Executor** — vectorized operators, intra-node parallelism, exchange operators across nodes.
5. **Transaction layer** — transaction lifecycle, HLC, write intents, conflict detection.
6. **Distribution layer** — Raft groups per range, gossip, leaseholders, rebalancer.
7. **Storage engine** — CoW B+ tree, buffer pool, WAL, page reclamation, MVCC snapshot management.

### 1.2 Document scope

This specification defines **what** the system does, its observable behavior, and its contracts. Implementation choices (byte-level layouts, specific algorithms, internal class structure) are deferred to the Design stage (`DESIGN.md`).

## 2. Functional Requirements

### 2.1 Transport & Sessions

1. The server accepts TCP connections on a configurable port using a custom binary wire protocol consisting of length-prefixed messages with type tags.
2. Sessions authenticate using SCRAM-SHA-256.
3. TLS is supported via the standard JDK TLS stack.
4. Multiple logical sessions can be multiplexed over a single TCP connection.
5. Clients may pipeline queries — subsequent statements can be sent before earlier results are consumed.
6. Out-of-band query cancellation is supported on a dedicated control channel.
7. An optional plain-text REPL protocol runs on a separate port for debugging and inspection.
8. Each connection is handled by one virtual thread. Hot paths must use `ReentrantLock` rather than `synchronized` blocks to avoid pinning.

### 2.2 SQL Surface

9. The system parses and executes the following PostgreSQL-compatible subset:
    - **SELECT** with `FROM`, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`, `OFFSET`, `DISTINCT`, `DISTINCT ON`.
    - **Joins:** `INNER`, `LEFT`, `RIGHT`, `FULL OUTER`, `LATERAL`, `CROSS`.
    - **Subqueries:** scalar, `IN`, `EXISTS`, and correlated.
    - **Common Table Expressions** (`WITH`), including recursive CTEs.
    - **Window functions:** `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG`, `LEAD`, `SUM`/`AVG`/`COUNT` `OVER (...)`.
    - **Set operations:** `UNION`, `INTERSECT`, `EXCEPT` (with and without `ALL`).
    - **DML:** `INSERT ... VALUES`, `INSERT ... SELECT`, `INSERT ... ON CONFLICT DO {NOTHING|UPDATE}`, `UPDATE ... FROM`, `DELETE ... USING`, `RETURNING`.
10. **DDL** supports:
    - `CREATE TABLE` with `PRIMARY KEY`, `FOREIGN KEY`, `CHECK`, `UNIQUE`, `NOT NULL`, `DEFAULT`.
    - `CREATE INDEX` for B-tree (default) and HNSW (`USING hnsw`).
    - `CREATE VIEW`, `DROP VIEW`.
    - `ALTER TABLE ADD/DROP COLUMN`, `ADD/DROP CONSTRAINT`.
    - `CREATE / DROP SCHEMA`, `CREATE / DROP USER`, `GRANT`, `REVOKE`.
11. **Transactions:**
    - `BEGIN`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `RELEASE SAVEPOINT`, `ROLLBACK TO SAVEPOINT`.
    - `SET TRANSACTION ISOLATION LEVEL` with `SERIALIZABLE` (default) and `SNAPSHOT`.
    - `SELECT ... AS OF SYSTEM TIME '<timestamp>'` for historical reads.
12. **Type system:** `BOOL`, `INT2/4/8`, `FLOAT4/8`, `DECIMAL`, `TEXT`, `BYTEA`, `DATE`, `TIME`, `TIMESTAMP`, `TIMESTAMPTZ`, `UUID`, `JSONB`, `VECTOR(N)`, and arrays of all primitive types.
13. **Explicitly excluded:** stored procedures, `PL/pgSQL`, triggers, table inheritance, materialized views, `LISTEN/NOTIFY`, foreign data wrappers.

### 2.3 Planner

14. The planner produces a typed AST from the parser, then a logical plan in relational algebra form.
15. Rule-based rewrites include: predicate pushdown, projection pruning, constant folding, simple subquery unnesting, and obvious join reorderings.
16. Cost-based optimization uses statistics: per-column equi-height histograms, most-common-value lists, distinct counts via HyperLogLog.
17. Join enumeration uses dynamic programming for queries with ≤12 base relations and a greedy / IKKBZ fallback above that threshold.
18. The planner emits a distributed physical plan with explicit **exchange operators** for repartitioning, broadcast, or gather. Aggregates and top-K are split into partial+final pairs around exchanges.
19. `EXPLAIN` and `EXPLAIN ANALYZE` produce a tree showing operator types, estimated and actual row counts, execution time, memory usage, and network bytes per operator.

### 2.4 Executor

20. The execution model is **vectorized**: tuples flow in column-oriented batches of approximately 2,048 rows, backed by `MemorySegment`s allocated off-heap.
21. The operator catalog includes: sequential scan, index scan, index-only scan, filter, project, hash join, sort-merge join, nested-loop join (with optional index), hash aggregate, sort, top-K (heap), limit, window, set operations, and CTE materialization.
22. Hash join and sort operators spill to disk when their working set exceeds a configurable memory budget.
23. Intra-node parallelism is provided by exchange operators that repartition or broadcast batches between worker pools.
24. The JDK Vector API is used to accelerate filter and arithmetic expressions where feasible, falling back to scalar execution when unavailable.

### 2.5 Storage Engine

25. Storage is page-based with **fixed 8 KB pages**.
26. Pages are stored **off-heap** via `MemorySegment` (Foreign Function & Memory API). The on-heap surface is references and metadata only.
27. The storage structure is a **Copy-on-Write B+ tree**: every modification produces a new path from root to leaf. Old pages remain reachable as long as a live snapshot references them.
28. The **buffer pool** is sharded for concurrency, off-heap, sized to most of host RAM, and uses CLOCK replacement.
29. A **write-ahead log** records every modification before the corresponding pages are made durable. WAL segments are append-only, fsync'd via group commit, and CRC-protected.
30. **Recovery** replays the WAL from the last checkpoint into the current root, producing a consistent state.
31. **MVCC** is realized via snapshot roots: each transaction reads from a snapshot identified by a `(timestamp, root_page_id)` pair. Snapshots are reference-counted; pages reachable only from no-longer-referenced snapshots are reclaimed by a background page reclaimer.
32. **Single writer per range** (serialized by the leaseholder). Concurrent readers per snapshot are unbounded.
33. **Secondary indexes** are independent CoW B+ trees, keyed by `(indexed_columns…, primary_key)`.
34. **HNSW vector indexes** share the buffer pool but use their own page layout (graph nodes, adjacency lists, level pointers).

### 2.6 Transactions & Concurrency

35. Default isolation is `SERIALIZABLE`, implemented via snapshot reads plus write intents plus commit-time conflict detection.
36. Timestamps are **Hybrid Logical Clock (HLC)** values. A configurable maximum clock offset (default 500 ms) bounds the uncertainty window.
37. **Write intents** are provisional values written into the storage engine alongside committed values. Readers encountering an intent either resolve it (if the writer committed or aborted) or wait for its outcome.
38. Distributed transactions are coordinated by a **transaction record** stored on the same range as the first write. Participants reference the record by ID.
39. The initial commit protocol is **two-phase commit (2PC)**. Parallel commits are deferred to a later phase.
40. Aborted transactions return a structured error indicating retryable vs. non-retryable. Serialization failures are retryable; constraint violations are not.

### 2.7 Distribution

41. The keyspace is partitioned into **ranges** of contiguous keys. Each range is replicated to N replicas (default N=3) via its own Raft group.
42. Each range has a **leaseholder**: the single replica that handles reads and coordinates writes during its lease term. Leases are time-bounded and transferable.
43. **Range splits** occur when a range exceeds a size threshold (default 64 MB). Range **merges** are deferred to a later phase.
44. Cluster membership and metadata propagate via **SWIM-style gossip**.
45. A **range descriptor table** (itself a replicated range) maps key spans to replica sets and current leaseholders. Clients cache descriptors and refresh on routing errors.
46. The **rebalancer** continuously moves replicas to balance per-node range size, leaseholder count, and capacity.
47. **Follower reads** are served from non-leaseholder replicas at a bounded-staleness timestamp using closed-timestamp signaling propagated through Raft.

### 2.8 AI Features

48. The `VECTOR(N)` type stores a fixed-dimensional `float32` array. Dimensionality is type-checked at column declaration and bind time.
49. Distance operators: `<->` (Euclidean / L2), `<=>` (cosine distance), `<#>` (negative inner product).
50. `CREATE INDEX ... USING hnsw (column vector_l2_ops)` builds an HNSW index over a vector column. Build is incremental; bulk-build is supported via `REINDEX`.
51. The planner can select an HNSW index scan for queries of the form `ORDER BY <column> <distance_op> $1 LIMIT k`. For predicates combined with vector ordering, the planner chooses between **pre-filter** and **post-filter** strategies based on predicate selectivity.
52. Distributed vector queries fan out: each range produces a local top-k, the coordinator merges into a global top-k.
53. The **Index Advisor** records anonymized query workload (predicates and join keys, no row data) into `system.workload_log` and produces recommended `CREATE INDEX` statements with estimated cost reduction. Recommendations are exposed via `SHOW INDEX RECOMMENDATIONS`.

## 3. Non-Functional Requirements

### 3.1 Performance (learning targets, not SLAs)

- A single-node `SELECT` by primary key completes in <1 ms on a warm buffer pool.
- A three-node cluster sustains at least 5,000 simple transactions/second on commodity hardware.
- GC pauses do not exceed 10 ms p99 under normal load (ZGC + off-heap storage).
- Vector top-10 cosine query over 1M 768-dim vectors completes in <50 ms warm with an HNSW index.

### 3.2 Durability

- Committed transactions survive the crash of any one replica.
- Group commit is enabled by default. Per-transaction `SET LOCAL synchronous_commit = OFF` opts into an intentionally weaker async durability tier.
- After `kill -9` of any node, recovery completes within a bounded time and produces a state consistent with all committed transactions.

### 3.3 Consistency

- Distributed transactions are **serializable** across the cluster.
- Reads via `AS OF SYSTEM TIME` are consistent at the specified timestamp.
- Follower reads are consistent at the closed timestamp (≤ wall-clock − configurable staleness, default 1 s).

### 3.4 Observability

- Each node exposes a Prometheus-compatible `/metrics` HTTP endpoint.
- OpenTelemetry distributed tracing spans cover query execution end-to-end, including cross-node RPCs.
- A `/status` HTTP endpoint exposes node health, range map, hot ranges, and replica lag.
- `EXPLAIN ANALYZE` output includes the trace ID for correlation with stored traces.

### 3.5 Security

- TLS is required for all transport (client-server and inter-node), using the standard JDK TLS stack.
- Client authentication is SCRAM-SHA-256.
- Inter-node authentication is certificate-based mTLS.
- Role-based access control: `CREATE ROLE`, `GRANT`, `REVOKE` for `SELECT`, `INSERT`, `UPDATE`, `DELETE` on tables.

### 3.6 Operational

- Each node is a single self-contained JAR.
- Cluster bootstrap: `--init` on the first node, `--join host:port` on subsequent nodes.
- Graceful shutdown: leaseholder transfer, in-flight transactions drained, then process exits.

## 4. Actors

| Actor | Description |
|-------|-------------|
| **Application client** | Connects via the wire protocol; runs SQL queries on behalf of an end user or service. |
| **Database administrator** | Connects with admin privileges; issues DDL, manages users, triggers cluster operations. |
| **Cluster node** | Peer node; exchanges Raft messages, gossip, and SQL distribution RPCs with other nodes. |
| **Page reclaimer** | Background worker that frees pages no longer reachable from any live snapshot. |
| **Statistics collector** | Background worker that samples tables and refreshes histograms/MCV lists. |
| **Rebalancer** | Background worker that proposes replica moves and leaseholder transfers. |
| **Index advisor analyzer** | Background worker that processes the workload log and updates `system.recommendations`. |
| **Gossip broadcaster** | Background worker that disseminates membership and capacity information. |

## 5. Data Model (High-Level)

The system manages the following categories of data.

| Entity | Description |
|--------|-------------|
| **User tables** | Created by users via `CREATE TABLE`. Stored as primary-key CoW B+ trees (rows live in the leaves) per table per range. |
| **Secondary indexes** | Independent CoW B+ trees keyed by indexed columns plus primary key. V1 is local-only (per range); global indexes deferred. |
| **HNSW indexes** | Page-backed graph structure. Shares the buffer pool with the B+ trees but uses a different page layout. |
| **System tables** | `system.range_descriptors`, `system.nodes`, `system.users`, `system.roles`, `system.privileges`, `system.statistics`, `system.workload_log`, `system.recommendations`. Stored as ordinary tables on dedicated system ranges. |
| **Cluster metadata** | Disseminated via gossip: node liveness, store capacities, leaseholder map cache, closed timestamps. |
| **WAL** | Per-node, per-store; an ordered sequence of physical-logical log records. |
| **Snapshots** | Reference-counted root pointers at specific HLC timestamps; held by readers, follower-read mechanism, and `AS OF SYSTEM TIME` queries. |
| **Transaction records** | One per active distributed transaction; record the participant set, intent locations, and final status. |

## 6. Integrations & Dependencies

The database has **no external service dependencies at runtime**. Runtime/library dependencies are:

- **JDK 22+** — runtime; required for Foreign Function & Memory API, Vector API (incubator), virtual threads.
- **Netty** — TCP transport, framing, TLS plumbing.
- **ANTLR4** *(or a hand-written recursive-descent parser)* — SQL grammar.
- **Micrometer + Prometheus simpleclient** — metrics.
- **OpenTelemetry Java SDK** — distributed tracing.
- **LZ4-java**, **Zstd-jni** — optional page compression.
- **JFR + async-profiler** — profiling (development only).

Reference client libraries will be provided in Java and Python.

## 7. Edge Cases & Error Handling

### 7.1 Storage

- **Torn pages on crash.** Mitigated by full-page writes in the WAL until the next checkpoint completes.
- **Disk full.** New write transactions fail with `INSUFFICIENT_RESOURCES`. In-flight transactions may commit if their remaining log records fit; otherwise they abort cleanly.
- **Corrupted page (CRC mismatch).** The read fails. The affected range enters a degraded state and rejects reads/writes locally; the leaseholder may serve from a different replica. Operator intervention is required to repair the local store.
- **Snapshot of unbounded age.** A snapshot held too long causes page reclamation backlog. A configurable maximum snapshot age aborts the owning transaction with `SNAPSHOT_TOO_OLD`.

### 7.2 Concurrency

- **Write-write conflict.** Detected at commit time; the later transaction aborts with `SERIALIZATION_FAILURE`.
- **Read-write conflict.** The snapshot reader either sees the pre-write version (if its snapshot precedes the writer) or waits on the writer's intent until commit/abort.
- **Deadlock.** Detected by a transaction wait-for graph maintained at the leaseholder; the youngest transaction in the cycle is aborted.

### 7.3 Distribution

- **Leaseholder unavailable.** Clients receive a retryable error; the routing layer redirects to the new leaseholder once elected.
- **Network partition (minority).** The minority side cannot make progress on writes. Reads succeed at the closed timestamp until the staleness bound is exceeded.
- **Network partition (majority).** Writes continue on the majority side. Partitioned replicas catch up via Raft log on rejoin.
- **Clock skew exceeds configured maximum.** Transactions on the affected node refuse to commit; the node logs a warning, fails health checks, and continues serving stale reads only.
- **Range descriptor mismatch.** Client retries with a refreshed descriptor from the descriptor cache or `system.range_descriptors`.

### 7.4 SQL

- **Type mismatch.** Caught at parse or bind time with a structured error referencing column and expected type.
- **Unique-constraint violation.** Statement returns `CONSTRAINT_VIOLATION`. Within a transaction, the statement aborts but the transaction may continue (matching PostgreSQL behavior).
- **Foreign-key violation.** Rejected at the offending statement.
- **Query timeout.** Per-statement `statement_timeout` (configurable, default unset). On timeout the executor receives an interrupt signal and unwinds; resources are released; the client receives `QUERY_CANCELED`.
- **Out-of-memory in operator.** The operator spills to disk if supported; otherwise the query is aborted with `RESOURCE_EXHAUSTED`.

### 7.5 Vector

- **Dimension mismatch.** Rejected at parse or bind time with a clear message naming the column and expected dimensionality.
- **HNSW index update under concurrent reads.** Graph mutations are versioned; readers see a consistent snapshot of the graph at the start of their query.
- **NaN / infinite values in vectors.** Rejected at insert with `INVALID_VECTOR_VALUE`.

## 8. Acceptance Criteria

### 8.1 M1 — Storage Engine

- 1 million random KV writes followed by 1 million reads completes without data loss.
- After `kill -9` at any point during a 24-hour write-only torture test, the engine recovers and passes a full data-integrity scan.
- A held snapshot continues to read its consistent view after >100 GB of subsequent writes have landed in the tree.
- A property-based test of B+ tree operations (insert, lookup, scan, delete, snapshot) passes 1 million randomized runs.

### 8.2 M2 — Single-Node SQL

- A `psql`-style CLI can connect, run `CREATE TABLE`, `INSERT`, `SELECT`, `UPDATE`, `DELETE`, and disconnect cleanly.
- Transactional semantics are verified by a custom subset of the PostgreSQL `isolation` test suite.
- A simple TPC-C-like workload completes against a single node without integrity errors.

### 8.3 M3 — Query Sophistication

- A 5-way join over a realistic schema picks a near-optimal join order under cost-based planning (compared against a hand-derived optimal plan).
- A vectorized scan over 100M rows is at least 3× faster than the Volcano equivalent on the same hardware.
- Subquery unnesting transforms an `EXISTS` correlated subquery into a semi-join in the plan output.

### 8.4 M4 — Distributed Cluster

- A three-node cluster bootstraps, replicates a table to 3 replicas, survives one node being killed, and continues to serve reads and writes after a leaseholder transfer.
- A distributed transaction touching two ranges on different nodes commits atomically (verified by injection of failures between phases).
- A custom simplified Jepsen-style consistency test finds no violations of serializability over a 10-minute run with concurrent writers.

### 8.5 M5 — Vector Search

- An HNSW index over 1M 768-dimensional vectors answers a top-10 cosine similarity query in <50 ms warm.
- A hybrid query `WHERE category = 'X' ORDER BY embedding <-> $1 LIMIT 10` with ~1% selectivity picks pre-filter; with ~50% selectivity picks post-filter; verified via `EXPLAIN`.
- HNSW index recall@10 against brute-force ground truth is ≥0.95 on a representative dataset.

### 8.6 M6 — Auto-Tuning

- After a 10-minute mixed workload, the index advisor identifies at least one beneficial index that, when created, reduces total query time on a replay of the same workload by ≥20%.
- `SHOW INDEX RECOMMENDATIONS` returns the recommended index with an estimated cost-reduction figure.

## 9. Open Questions

- HNSW dynamic updates — incremental insertion only, or also deletion via tombstones-and-rebuild?
- Closed-timestamp interval — aggressive (~100 ms) for responsive follower reads, or conservative (~1 s) for less Raft chatter?
- Range descriptor lookup — single-level local cache, or two-level (local cache + system range with TTL)?
- Statistics-collection trigger — row-count delta threshold, time-based, or both?
- JSONB indexing — defer entirely to a later phase, or include path-expression indexes in v1?
- Should `AS OF SYSTEM TIME` be limited to a configurable maximum lookback (e.g., 24 hours) tied to the snapshot reclamation horizon?
- Pure-Java Raft from scratch, or accept Apache Ratis / port etcd-raft as a starting point? (Carried over from PLANNING.md.)
- Online schema change protocol — full F1-style multi-step rollout, or restricted set of "easy" schema changes only?