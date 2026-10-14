package io.github.scf37.jpaq.cli;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Standard output and error. File names inside the program are "byte strings" (a Java String
 * whose chars are the bytes 0..255 of the UTF-8 name), so both streams use ISO-8859-1 to write the
 * bytes unchanged.
 */
final class Console {
  private Console() {}

  static volatile PrintStream out = new PrintStream(
      new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false,
      StandardCharsets.ISO_8859_1);
  static volatile PrintStream err = new PrintStream(
      new FileOutputStream(FileDescriptor.err), true, StandardCharsets.ISO_8859_1);

  /** Redirects standard output and error (used by tests and embedders). */
  static void setStreams(PrintStream newOut, PrintStream newErr) {
    out = newOut;
    err = newErr;
  }

  /** printf to stdout. */
  static void printf(String format, Object... args) {
    out.print(String.format(java.util.Locale.ROOT, format, args));
  }

  static void print(String s) {
    out.print(s);
  }

  static void flush() {
    out.flush();
  }

  /** Flushes output and terminates the JVM at once (exit() called from any thread). */
  static void exit(int code) {
    out.flush();
    err.flush();
    Runtime.getRuntime().halt(code);
  }

  /** Flushes stdout then prints to stderr (the C++ fflush(stdout); fprintf(stderr, ...)). */
  static void eprint(String s) {
    out.flush();
    err.print(s);
    err.flush();
  }

  static void eprintf(String format, Object... args) {
    eprint(String.format(java.util.Locale.ROOT, format, args));
  }
}
