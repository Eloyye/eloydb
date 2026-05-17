package org.eloydb.kv.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class EloydbKvCLITest {
  @TempDir private java.nio.file.Path tempDir;

  @Test
  void putGetAndScanUseRendererOutput() {
    assertThat(run("put", "b", "2")).isEqualTo("ok%n".formatted());
    assertThat(run("put", "a", "1")).isEqualTo("ok%n".formatted());

    assertThat(run("get", "a")).isEqualTo("1%n".formatted());
    assertThat(run("get", "missing")).isEqualTo("(missing)%n".formatted());
    assertThat(run("scan", "a", "z")).isEqualTo("a\t1%nb\t2%n".formatted());
  }

  @Test
  void unknownCommandPrintsUsageWithoutOpeningStore() {
    assertThat(run("wat")).contains("usage: eloydb-kv <dir> <command> [args]");
  }

  private String run(String... command) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    String[] args = new String[command.length + 1];
    args[0] = tempDir.toString();
    System.arraycopy(command, 0, args, 1, command.length);

    EloydbKvCLI.execute(
        args, new EloydbKvRenderer(new PrintStream(output, true, StandardCharsets.UTF_8)));
    return output.toString(StandardCharsets.UTF_8);
  }
}
