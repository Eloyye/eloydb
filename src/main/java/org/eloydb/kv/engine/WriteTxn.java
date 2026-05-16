package org.eloydb.kv.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.eloydb.kv.CommitResult;
import org.eloydb.kv.Cursor;
import org.eloydb.kv.KvEngine;
import org.eloydb.kv.Snapshot;
import org.eloydb.kv.Txn;
import org.eloydb.kv.internal.Bytes;
import org.eloydb.kv.internal.Operation;
import org.jspecify.annotations.Nullable;

@SuppressWarnings("NonApiType")
public final class WriteTxn implements Txn {
  private final KvEngine engine;
  private final long txId;
  private final ArrayList<Operation> operations = new ArrayList<>();
  private final TreeMap<Bytes, Operation> latestByKey = new TreeMap<>();
  private boolean open = true;

  public WriteTxn(KvEngine engine, long txId, TreeMap<Bytes, byte[]> ignoredCommittedView) {
    this.engine = engine;
    this.txId = txId;
  }

  public long txId() {
    return txId;
  }

  public boolean isOpen() {
    return open;
  }

  public List<Operation> operations() {
    return List.copyOf(operations);
  }

  public @Nullable Operation latestOperation(Bytes key) {
    return latestByKey.get(key);
  }

  @Override
  public Optional<byte[]> get(byte[] key) {
    ensureOpen();
    return engine.transactionGet(this, key);
  }

  @Override
  public void put(byte[] key, byte[] value) {
    ensureOpen();
    Operation operation = Operation.put(key, value);
    operations.add(operation);
    latestByKey.put(Bytes.copyOf(key), operation);
  }

  @Override
  public void delete(byte[] key) {
    ensureOpen();
    Operation operation = Operation.delete(key);
    operations.add(operation);
    latestByKey.put(Bytes.copyOf(key), operation);
  }

  @Override
  public Cursor scan(byte[] startInclusive, byte[] endExclusive) {
    ensureOpen();
    return engine.transactionScan(this, startInclusive, endExclusive);
  }

  @Override
  public Snapshot snapshot() {
    ensureOpen();
    return engine.transactionSnapshot(this);
  }

  @Override
  public CommitResult commit() {
    ensureOpen();
    CommitResult result = engine.commit(this);
    open = false;
    return result;
  }

  @Override
  public void abort() {
    if (open) {
      engine.abort(this);
      open = false;
    }
  }

  @Override
  public void close() {
    abort();
  }

  private void ensureOpen() {
    if (!open) {
      throw new IllegalStateException("transaction is closed");
    }
  }
}
