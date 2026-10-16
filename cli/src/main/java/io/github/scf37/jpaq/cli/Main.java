package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.ArchiveException;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** The zpaq command line archiver. */
public final class Main {
  private Main() {}

  /**
   * Returns the arguments as byte strings (UTF-8 bytes as chars), recovering the raw bytes from
   * /proc/self/cmdline if possible so that names that are not valid UTF-8 are preserved.
   */
  static String[] byteArgs(String[] args) {
    String[] r = new String[args.length + 1];
    r[0] = "zpaq";
    String[] raw = rawArgs(args.length);
    if (raw != null) {
      for (int i = 0; i < args.length; i++) {
        boolean ascii = args[i].chars().allMatch(c -> c < 128);
        if (ascii && !args[i].equals(raw[i])) {
          raw = null;  // not aligned with the Java arguments; do not trust
          break;
        }
      }
    }
    for (int i = 0; i < args.length; i++) r[i + 1] = raw != null ? raw[i] : Text.bytes(args[i]);
    return r;
  }

  private static String[] rawArgs(int n) {
    try {
      Path p = Path.of("/proc/self/cmdline");
      if (!Files.isReadable(p)) return null;
      byte[] b = Files.readAllBytes(p);
      java.util.List<String> parts = new java.util.ArrayList<>();
      int s = 0;
      for (int i = 0; i < b.length; i++) {
        if (b[i] == 0) {
          parts.add(new String(b, s, i - s, StandardCharsets.ISO_8859_1));
          s = i + 1;
        }
      }
      if (s < b.length) parts.add(new String(b, s, b.length - s, StandardCharsets.ISO_8859_1));
      if (parts.size() < n) return null;
      return parts.subList(parts.size() - n, parts.size()).toArray(new String[0]);
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The clock of the command line: the system clock, unless the environment variable
   * SOURCE_DATE_EPOCH (seconds since 1970) is set, which makes archives reproducible. Null means
   * the system clock.
   */
  static Instant sourceDateEpoch() {
    String e = System.getenv("SOURCE_DATE_EPOCH");
    if (e != null && !e.isBlank()) {
      try {
        return Instant.ofEpochSecond(Long.parseLong(e.trim()));
      } catch (NumberFormatException ex) {
        // fall through to the system clock
      }
    }
    return null;
  }

  /** The message of a failure as the C++ program prints it (e.what()). */
  private static String what(Throwable e) {
    if (e instanceof OutOfMemoryError) return "std::bad_alloc";
    if (e instanceof ArchiveException || e instanceof CommandLine.Fatal) return e.getMessage();
    if (e instanceof IOException) return e.toString();
    return e.getMessage() != null ? e.getMessage() : e.toString();
  }

  /**
   * Runs a command line whose arguments are byte strings (argv[0] is the program name), with
   * relative file names resolved against {@code workDir} (null: the process directory) and the
   * output written to {@code out} and {@code err} as bytes (ISO-8859-1 streams). Returns the exit
   * code: 0 OK, 1 warnings, 2 errors.
   */
  static int run(String[] argv, Path workDir, Instant clock, PrintStream out, PrintStream err) {
    final long start = System.currentTimeMillis();
    Console console = new Console(out, err);
    int errorcode;
    try {
      errorcode = CommandLine.run(console, workDir, clock, argv);
    } catch (ExitException e) {
      console.flush();
      return e.code;
    } catch (IOException | RuntimeException | OutOfMemoryError e) {
      console.eprint("zpaq error: " + Text.bytes(what(e)) + "\n");
      errorcode = 2;
    }
    console.flush();
    final double seconds = (System.currentTimeMillis() - start) / 1000.0;
    console.eprint(String.format(java.util.Locale.ROOT, "%1.3f seconds %s\n", seconds,
        errorcode > 1 ? "(with errors)" : errorcode > 0 ? "(with warnings)" : "(all OK)"));
    return errorcode;
  }

  /** Same with Java strings as arguments (without the program name). */
  static int run(Path workDir, Instant clock, PrintStream out, PrintStream err, String... args) {
    String[] r = new String[args.length + 1];
    r[0] = "zpaq";
    for (int i = 0; i < args.length; i++) r[i + 1] = Text.bytes(args[i]);
    return run(r, workDir, clock, out, err);
  }

  public static void main(String[] args) {
    System.exit(runProcess(byteArgs(args)));
  }

  /** Runs a command line with the standard streams of the process; returns the exit code. */
  static int runProcess(String[] argv) {
    PrintStream out = new PrintStream(
        new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false,
        StandardCharsets.ISO_8859_1);
    PrintStream err = new PrintStream(
        new FileOutputStream(FileDescriptor.err), true, StandardCharsets.ISO_8859_1);
    int rc = run(argv, null, sourceDateEpoch(), out, err);
    out.flush();
    err.flush();
    return rc;
  }
}
