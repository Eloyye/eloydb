package org.eloydb.kv.cli;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;
import org.eloydb.kv.KeyValueEngine;

/** Formats command-line responses for the KV harness. */
final class EloydbKvRenderer {
  private final PrintStream out;

  EloydbKvRenderer(PrintStream out) {
    this.out = out;
  }

  void usage() {
    out.println(
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

  void initialized(Path directory) {
    out.println("initialized " + directory);
  }

  void ok() {
    out.println("ok");
  }

  void value(Optional<String> value) {
    out.println(value.orElse("(missing)"));
  }

  void row(String key, String value) {
    out.println(key + "\t" + value);
  }

  void snapshot(long commitTs) {
    out.println("snapshot " + commitTs);
  }

  void metric(String name, long value) {
    out.println(name + "=" + value);
  }

  void verify(KeyValueEngine.VerifyResult result) {
    out.println(
        "ok="
            + result.ok()
            + " keys="
            + result.keyCount()
            + " liveSnapshots="
            + result.liveSnapshots());
  }
}
