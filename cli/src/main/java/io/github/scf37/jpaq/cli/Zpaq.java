package io.github.scf37.jpaq.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Programmatic entry point to the zpaq command line, for applications that create or read
 * archives without starting a process. Takes the same arguments as the command line (without the
 * program name), captures the console output and never calls {@code System.exit}.
 *
 * <p>Calls are serialized (the program keeps global state), so concurrent callers wait for each
 * other. Argument and output text are Java strings in UTF-8.
 */
public final class Zpaq {
  private Zpaq() {}

  private static final Object LOCK = new Object();

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

  /** Runs {@code zpaq args...} with relative file names resolved against {@code workDir}. */
  public static Result run(Path workDir, String... args) {
    synchronized (LOCK) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteArrayOutputStream err = new ByteArrayOutputStream();
      PrintStream oldOut = Console.out;
      PrintStream oldErr = Console.err;
      PrintStream o = new PrintStream(out, false, StandardCharsets.ISO_8859_1);
      PrintStream e = new PrintStream(err, false, StandardCharsets.ISO_8859_1);
      Console.setStreams(o, e);
      int rc;
      try {
        rc = Main.run(workDir, args);
      } finally {
        o.flush();
        e.flush();
        Console.setStreams(oldOut, oldErr);
      }
      return new Result(rc, decode(out), decode(err));
    }
  }

  // Console text is byte strings (ISO-8859-1 chars holding UTF-8 bytes); return real text.
  private static String decode(ByteArrayOutputStream b) {
    return new String(b.toByteArray(), StandardCharsets.UTF_8);
  }
}
