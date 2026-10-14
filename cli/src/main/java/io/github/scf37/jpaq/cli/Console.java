package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.api.ErrorLine;
import io.github.scf37.jpaq.api.InfoLine;
import io.github.scf37.jpaq.api.Line;
import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Output of a command as {@link Line}s. Lines go to the sink if one is set (embedders), otherwise
 * their text is written to standard output or error. File names inside the program are "byte
 * strings" (a Java String whose chars are the bytes 0..255 of the UTF-8 name), so the streams use
 * ISO-8859-1 to write the bytes unchanged; lines handed to a sink have real text.
 */
final class Console {
  private Console() {}

  static volatile PrintStream out = new PrintStream(
      new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16), false,
      StandardCharsets.ISO_8859_1);
  static volatile PrintStream err = new PrintStream(
      new FileOutputStream(FileDescriptor.err), true, StandardCharsets.ISO_8859_1);

  private static volatile Consumer<Line> sink;

  /** Redirects standard output and error (used by tests and embedders). */
  static void setStreams(PrintStream newOut, PrintStream newErr) {
    out = newOut;
    err = newErr;
  }

  /** Sends all lines to {@code s} instead of the streams (null = back to the streams). */
  static void setSink(Consumer<Line> s) {
    sink = s;
  }

  /**
   * Emits a line. Its text is a byte string like all text in the program; for a sink the line is
   * converted to real text first.
   */
  static void emit(Line line) {
    Consumer<Line> s = sink;
    if (s != null) {
      s.accept(Lines.decode(line));
    } else if (line.channel() == Line.Channel.STDOUT) {
      out.print(line.text());
    } else {
      out.flush();
      err.print(line.text());
      err.flush();
    }
  }

  /** printf to stdout. */
  static void printf(String format, Object... args) {
    print(String.format(java.util.Locale.ROOT, format, args));
  }

  static void print(String s) {
    emit(new InfoLine.Text(s));
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
    emit(new ErrorLine.Message(s));
  }

  static void eprintf(String format, Object... args) {
    eprint(String.format(java.util.Locale.ROOT, format, args));
  }
}
