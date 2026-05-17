package org.eloydb.kv.storage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.CRC32C;
import org.eloydb.kv.CommitResult;
import org.eloydb.kv.ErrorCode;
import org.eloydb.kv.KvException;
import org.eloydb.kv.Metrics;
import org.eloydb.kv.internal.Bytes;
import org.eloydb.kv.internal.Operation;

@SuppressWarnings("NonApiType")
public final class WriteAheadLog implements AutoCloseable {
  private static final int MAGIC = 0x454c5741;
  private static final int HEADER_BYTES = Integer.BYTES + Integer.BYTES + Byte.BYTES + Long.BYTES;
  private static final int CRC_BYTES = Integer.BYTES;
  private static final byte PUT = 1;
  private static final byte DELETE = 2;
  private static final byte COMMIT = 3;

  private final Path path;
  private final FileChannel channel;
  private final Metrics metrics;

  private WriteAheadLog(Path path, FileChannel channel, Metrics metrics) {
    this.path = path;
    this.channel = channel;
    this.metrics = metrics;
  }

  public static WriteAheadLog open(Path directory, Metrics metrics) {
    try {
      Files.createDirectories(directory);
      Path path = directory.resolve("wal.000001");
      var options =
          EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
      return new WriteAheadLog(path, FileChannel.open(path, options), metrics);
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot open WAL in " + directory, e);
    }
  }

  public RecoveryResult recover() {
    var committed = new TreeMap<Bytes, byte[]>();
    var pending = new java.util.HashMap<Long, List<Operation>>();
    long lastGoodPosition = 0;
    long recoveredCommitTs = 0;

    try (FileChannel reader = FileChannel.open(path, StandardOpenOption.READ)) {
      while (true) {
        long recordStart = reader.position();
        try {
          WalRecord record = readRecord(reader);
          lastGoodPosition = reader.position();
          if (record.type == COMMIT) {
            List<Operation> operations = pending.remove(record.txId);
            if (operations != null) {
              for (Operation operation : operations) {
                apply(committed, operation);
              }
            }
            recoveredCommitTs = record.commitTs();
          } else {
            pending
                .computeIfAbsent(record.txId, ignored -> new ArrayList<>())
                .add(record.operation());
          }
        } catch (EOFException e) {
          truncateIfNeeded(reader.size(), lastGoodPosition);
          positionForAppend(lastGoodPosition);
          return new RecoveryResult(committed, recordStart, recoveredCommitTs);
        } catch (TornWalTailException e) {
          truncateIfNeeded(reader.size(), lastGoodPosition);
          metrics.increment("wal.torn_tail_truncations");
          positionForAppend(lastGoodPosition);
          return new RecoveryResult(committed, lastGoodPosition, recoveredCommitTs);
        }
      }
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot recover WAL " + path, e);
    }
  }

  public CommitResult appendCommitted(long txId, long commitTs, List<Operation> operations) {
    try {
      for (Operation operation : operations) {
        appendOperation(txId, operation);
      }
      appendCommit(txId, commitTs);
      channel.force(true);
      metrics.increment("wal.fsyncs");
      return new CommitResult(commitTs, channel.position(), operations.size());
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot append WAL transaction", e);
    }
  }

  public VerificationResult verify() {
    try (FileChannel reader = FileChannel.open(path, StandardOpenOption.READ)) {
      while (true) {
        try {
          readRecord(reader);
        } catch (EOFException e) {
          return new VerificationResult(true);
        }
      }
    } catch (IOException | KvException e) {
      return new VerificationResult(false);
    }
  }

  @Override
  public void close() {
    try {
      channel.force(true);
      channel.close();
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot close WAL", e);
    }
  }

  private void appendOperation(long txId, Operation operation) throws IOException {
    byte type = operation.kind() == Operation.Kind.PUT ? PUT : DELETE;
    byte[] key = operation.unsafeKey();
    byte[] value = operation.unsafeValue();
    int payloadLength = Integer.BYTES + key.length + Integer.BYTES + value.length;
    ByteBuffer record = allocateRecord(type, txId, payloadLength);
    record.putInt(key.length).put(key).putInt(value.length).put(value);
    finishAndWrite(record);
  }

  private void appendCommit(long txId, long commitTs) throws IOException {
    ByteBuffer record = allocateRecord(COMMIT, txId, Long.BYTES);
    record.putLong(commitTs);
    finishAndWrite(record);
  }

  private ByteBuffer allocateRecord(byte type, long txId, int payloadLength) {
    ByteBuffer record =
        ByteBuffer.allocate(HEADER_BYTES + payloadLength + CRC_BYTES).order(ByteOrder.BIG_ENDIAN);
    record.putInt(MAGIC).putInt(payloadLength).put(type).putLong(txId);
    return record;
  }

  private void finishAndWrite(ByteBuffer record) throws IOException {
    CRC32C crc = new CRC32C();
    byte[] bytes = record.array();
    int arrayOffset = record.arrayOffset();
    crc.update(bytes, arrayOffset, record.position());
    record.putInt((int) crc.getValue());
    record.flip();
    while (record.hasRemaining()) {
      channel.write(record);
    }
    metrics.add("wal.bytes_written", bytes.length);
  }

  private WalRecord readRecord(FileChannel reader) throws IOException {
    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
    long recordStart = reader.position();
    readFully(reader, header, true);
    header.flip();
    int magic = header.getInt();
    if (magic != MAGIC) {
      throw new KvException(ErrorCode.CORRUPTED_PAGE, "invalid WAL record magic at " + recordStart);
    }
    int payloadLength = header.getInt();
    if (payloadLength < 0 || payloadLength > 128 * 1024 * 1024) {
      throw new KvException(
          ErrorCode.CORRUPTED_PAGE, "invalid WAL payload length " + payloadLength);
    }
    byte type = header.get();
    long txId = header.getLong();

    ByteBuffer payloadAndCrc =
        ByteBuffer.allocate(payloadLength + CRC_BYTES).order(ByteOrder.BIG_ENDIAN);
    readFully(reader, payloadAndCrc, false);
    payloadAndCrc.flip();

    CRC32C crc = new CRC32C();
    crc.update(header.array(), 0, HEADER_BYTES);
    crc.update(payloadAndCrc.array(), 0, payloadLength);
    int expected = payloadAndCrc.getInt(payloadLength);
    if ((int) crc.getValue() != expected) {
      throw new KvException(ErrorCode.CORRUPTED_PAGE, "WAL CRC mismatch at " + recordStart);
    }

    byte[] payload = new byte[payloadLength];
    payloadAndCrc.get(payload);
    return new WalRecord(type, txId, payload);
  }

  private static void readFully(FileChannel reader, ByteBuffer buffer, boolean cleanEofAllowed)
      throws IOException {
    while (buffer.hasRemaining()) {
      int read = reader.read(buffer);
      if (read < 0) {
        if (cleanEofAllowed && buffer.position() == 0) {
          throw new EOFException();
        }
        throw new TornWalTailException();
      }
    }
  }

  private void truncateIfNeeded(long currentSize, long lastGoodPosition) {
    if (currentSize == lastGoodPosition) {
      return;
    }
    try {
      channel.truncate(lastGoodPosition);
      channel.position(lastGoodPosition);
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot truncate torn WAL tail", e);
    }
  }

  private void positionForAppend(long position) {
    try {
      channel.position(position);
    } catch (IOException e) {
      throw new KvException(ErrorCode.IO_ERROR, "cannot position WAL for append", e);
    }
  }

  private static void apply(TreeMap<Bytes, byte[]> map, Operation operation) {
    if (operation.kind() == Operation.Kind.PUT) {
      map.put(Bytes.copyOf(operation.unsafeKey()), operation.unsafeValue());
    } else {
      map.remove(Bytes.copyOf(operation.unsafeKey()));
    }
  }

  public record RecoveryResult(
      TreeMap<Bytes, byte[]> map, long recoveredWalPosition, long commitTs) {}

  public record VerificationResult(boolean ok) {}

  private static final class TornWalTailException extends IOException {
    TornWalTailException() {
      super("torn WAL tail");
    }
  }

  private static final class WalRecord {
    private final byte type;
    private final long txId;
    private final byte[] payload;

    WalRecord(byte type, long txId, byte[] payload) {
      this.type = type;
      this.txId = txId;
      this.payload = java.util.Arrays.copyOf(payload, payload.length);
    }

    Operation operation() {
      if (type == COMMIT) {
        throw new IllegalStateException("commit records do not carry an operation");
      }
      ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
      byte[] key = new byte[buffer.getInt()];
      buffer.get(key);
      byte[] value = new byte[buffer.getInt()];
      buffer.get(value);
      return type == PUT ? Operation.put(key, value) : Operation.delete(key);
    }

    long commitTs() {
      if (type != COMMIT) {
        throw new IllegalStateException("operation records do not carry a commit timestamp");
      }
      return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getLong();
    }
  }
}
