package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.api.ErrorLine;
import io.github.scf37.jpaq.api.Format;
import io.github.scf37.jpaq.api.InfoLine;
import io.github.scf37.jpaq.api.LocalFsStorage;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
    for (int i = 0; i < args.length; i++) r[i + 1] = raw != null ? raw[i] : Strs.toBytes(args[i]);
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
   * Runs the command line (without the program name) with relative file names resolved against
   * {@code workDir}. Not safe to call concurrently from several threads. Returns the exit code.
   */
  public static int run(Path workDir, String[] args) {
    Fs.setWorkingDirectory(workDir);
    try {
      return run(args);
    } finally {
      Fs.setWorkingDirectory(null);
    }
  }

  /**
   * Runs the command line (without the program name). Returns the exit code: 0 OK, 1 warnings, 2
   * errors.
   */
  public static int run(String[] args) {
    String[] r = new String[args.length + 1];
    r[0] = "zpaq";
    for (int i = 0; i < args.length; i++) r[i + 1] = Strs.toBytes(args[i]);
    return runBytes(r);
  }

  /**
   * Runs the command line with archive files in {@code io} and options set by {@code configure}
   * after the arguments were parsed. Not safe to call concurrently.
   */
  static int run(Path workDir, ArchiveIo io, java.util.function.Consumer<Jidac> configure,
      String[] args) {
    Fs.setWorkingDirectory(workDir);
    try {
      String[] r = new String[args.length + 1];
      r[0] = "zpaq";
      for (int i = 0; i < args.length; i++) r[i + 1] = Strs.toBytes(args[i]);
      return runBytes(r, io, configure);
    } finally {
      Fs.setWorkingDirectory(null);
    }
  }

  private static int runBytes(String[] argv) {
    return runBytes(argv, null, null);
  }

  /** Runs with arguments as byte strings; argv[0] is the program name. */
  private static int runBytes(String[] argv, ArchiveIo io, java.util.function.Consumer<Jidac> cfg) {
    Jidac.globalStart = Jidac.mtime();
    int errorcode;
    try {
      Jidac jidac = new Jidac(io != null ? io
          : new ArchiveIo(new LocalFsStorage(Fs.workingDirectory()), Format.CLASSIC));
      jidac.configure = cfg;
      errorcode = jidac.doCommand(argv);
    } catch (ExitException e) {
      Console.flush();
      return e.code;
    } catch (ZpaqException | OutOfMemoryError | java.io.UncheckedIOException
        | IllegalStateException e) {
      fatal(e);
      errorcode = 2;
    } catch (RuntimeException e) {
      fatal(e);
      errorcode = 2;
    }
    Console.flush();
    final double seconds = (Jidac.mtime() - Jidac.globalStart) / 1000.0;
    Console.emit(new InfoLine.Finished(errorcode, seconds,
        String.format(java.util.Locale.ROOT, "%1.3f seconds %s\n", seconds,
            errorcode > 1 ? "(with errors)" : errorcode > 0 ? "(with warnings)" : "(all OK)")));
    return errorcode;
  }

  private static void fatal(Throwable e) {
    Console.emit(new ErrorLine.Fatal(Jidac.what(e), e, "zpaq error: " + Jidac.what(e) + "\n"));
  }

  public static void main(String[] args) {
    int rc = runBytes(byteArgs(args));
    Console.flush();
    Console.err.flush();
    System.exit(rc);
  }
}
