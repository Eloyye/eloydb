package org.eloydb.kv.cli;

import org.eloydb.kv.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Command-line harness for manual M1 KV exploration. */
public final class EloydbKvCLI {
  private EloydbKvCLI() {}

  static void main(String[] args) {
    if (args.length < 2) {
      usage();
      return;
    }

    Path directory = Path.of(args[0]);
    String command = args[1];
    try (KeyValueEngine engine = KeyValueEngine.open(directory, Config.defaults())) {
      switch (command) {
        case "init" -> System.out.println("initialized " + directory);
        case "put" -> {
          requireArgs(args, 4);
          try (Transaction txn = engine.beginWrite()) {
            txn.put(bytes(args[2]), bytes(args[3]));
            txn.commit();
          }
          System.out.println("ok");
        }
        case "get" -> {
          requireArgs(args, 3);
          System.out.println(engine.get(bytes(args[2])).map(EloydbKvCLI::text).orElse("(missing)"));
        }
        case "delete" -> {
          requireArgs(args, 3);
          try (Transaction txn = engine.beginWrite()) {
            txn.delete(bytes(args[2]));
            txn.commit();
          }
          System.out.println("ok");
        }
        case "scan" -> {
          requireArgs(args, 4);
          try (Cursor cursor = engine.scan(bytes(args[2]), bytes(args[3]))) {
            while (cursor.next()) {
              KeyValue row = cursor.current();
              System.out.println(text(row.key()) + "\t" + text(row.value()));
            }
          }
        }
        case "snapshot" -> {
          try (Snapshot snapshot = engine.snapshot()) {
            System.out.println("snapshot " + snapshot.commitTs());
          }
        }
        case "stats" ->
            engine
                .metrics()
                .snapshot()
                .forEach((name, value) -> System.out.println(name + "=" + value));
        case "verify" -> {
          KeyValueEngine.VerifyResult result = engine.verify();
          System.out.println(
              "ok="
                  + result.ok()
                  + " keys="
                  + result.keyCount()
                  + " liveSnapshots="
                  + result.liveSnapshots());
        }
        default -> usage();
      }
    }
  }

  private static void requireArgs(String[] args, int count) {
    if (args.length < count) {
      usage();
      throw new IllegalArgumentException("expected at least " + count + " arguments");
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(byte[] value) {
    return new String(value, StandardCharsets.UTF_8);
  }

  private static void usage() {
    System.out.println(
        """
        usage: eloydb-kv <dir> <command> [args]
          init
          put <key> <value>
          get <key>
          delete <key>
          scan <start-inclusive> <end-exclusive>
          snapshot
          stats
          verify
        """);
  }
}
