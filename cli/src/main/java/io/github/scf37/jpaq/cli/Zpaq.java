package io.github.scf37.jpaq.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Runs the zpaq command line in process, with the output collected as text. Never calls {@code
 * System.exit}; calls may run in parallel. For a typed API to archives see {@code
 * io.github.scf37.jpaq.ZpaqArchive}.
 */
public final class Zpaq {
  private Zpaq() {}

  /**
   * Outcome of a command.
   *
   * @param exitCode 0 OK, 1 warnings, 2 errors
   * @param stdout text the program wrote to standard output (listings, progress)
   * @param stderr text the program wrote to standard error (warnings, errors, timing)
   */
  public record Result(int exitCode, String stdout, String stderr) {
    public boolean ok() {
      return exitCode == 0;
    }
  }

  /** Runs {@code zpaq args...} with relative file names resolved against {@code workDir}. */
  public static Result run(Path workDir, List<String> args) {
    return run(workDir, args.toArray(new String[0]));
  }

  /**
   * Runs {@code zpaq args...} with relative file names resolved against {@code workDir}. The
   * date of an update is the current time, or the environment variable SOURCE_DATE_EPOCH if set,
   * like the command line.
   */
  public static Result run(Path workDir, String... args) {
    return run(workDir, Main.sourceDateEpoch(), args);
  }

  /** Same with the given current time (null: system clock). */
  static Result run(Path workDir, Instant clock, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int rc;
    try (PrintStream po = new PrintStream(out, false, StandardCharsets.ISO_8859_1);
        PrintStream pe = new PrintStream(err, false, StandardCharsets.ISO_8859_1)) {
      rc = Main.run(workDir, clock, po, pe, args);
    }
    return new Result(rc, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }
}
