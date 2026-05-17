package org.eloydb.kv.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import org.eloydb.kv.*;

/** Command-line harness for manual M1 KV exploration. */
public final class EloydbKvCLI {
  private EloydbKvCLI() {}

  public static void main(String[] args) {
    execute(args, new EloydbKvRenderer(System.out));
  }

  static void execute(String[] args, EloydbKvRenderer renderer) {
    if (args.length < 2) {
      renderer.usage();
      return;
    }

    Path directory = Path.of(args[0]);
    Command command = Command.from(args[1]);
    if (command == Command.UNKNOWN) {
      renderer.usage();
      return;
    }
    requireArgs(args, command.minArgs(), renderer);

    try (KeyValueEngine engine = KeyValueEngine.open(directory, Config.defaults())) {
      switch (command) {
        case INIT -> renderer.initialized(directory);
        case PUT -> {
          try (Transaction txn = engine.beginWrite()) {
            txn.put(bytes(args[2]), bytes(args[3]));
            txn.commit();
          }
          renderer.ok();
        }
        case GET -> renderer.value(engine.get(bytes(args[2])).map(EloydbKvCLI::text));
        case DELETE -> {
          try (Transaction txn = engine.beginWrite()) {
            txn.delete(bytes(args[2]));
            txn.commit();
          }
          renderer.ok();
        }
        case SCAN -> {
          try (Cursor cursor = engine.scan(bytes(args[2]), bytes(args[3]))) {
            while (cursor.next()) {
              KeyValue row = cursor.current();
              renderer.row(text(row.key()), text(row.value()));
            }
          }
        }
        case SNAPSHOT -> {
          try (Snapshot snapshot = engine.snapshot()) {
            renderer.snapshot(snapshot.commitTs());
          }
        }
        case STATS -> engine.metrics().snapshot().forEach(renderer::metric);
        case VERIFY -> {
          KeyValueEngine.VerifyResult result = engine.verify();
          renderer.verify(result);
        }
        case UNKNOWN -> throw new AssertionError("unknown command already rejected");
      }
    }
  }

  private static void requireArgs(String[] args, int count, EloydbKvRenderer renderer) {
    if (args.length < count) {
      renderer.usage();
      throw new IllegalArgumentException(
          "expected at least "
              + count
              + " arguments for: "
              + String.join(" ", Arrays.copyOf(args, args.length)));
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }

  private enum Command {
    INIT("init", 2),
    PUT("put", 4),
    GET("get", 3),
    DELETE("delete", 3),
    SCAN("scan", 4),
    SNAPSHOT("snapshot", 2),
    STATS("stats", 2),
    VERIFY("verify", 2),
    UNKNOWN("", 0);

    private final String token;
    private final int minArgs;

    Command(String token, int minArgs) {
      this.token = token;
      this.minArgs = minArgs;
    }

    int minArgs() {
      return minArgs;
    }

    static Command from(String token) {
      for (Command command : values()) {
        if (command.token.equals(token)) {
          return command;
        }
      }
      return UNKNOWN;
    }
  }
}
