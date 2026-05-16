package org.eloydb.kv;

/**
 * Result returned after a write transaction is durably committed.
 *
 * @param commitTs commit timestamp assigned to the transaction
 * @param walPosition WAL byte position after the commit record
 * @param operationCount number of put/delete operations committed
 */
public record CommitResult(long commitTs, long walPosition, int operationCount) {}
