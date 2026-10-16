package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.ErrorLine;
import io.github.scf37.jpaq.InfoLine;
import io.github.scf37.jpaq.Line;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * What one run of a command needs from its surroundings: where its output goes, the directory
 * that relative file names are resolved against, and the clock. Nothing in the engine is global,
 * so runs with their own contexts can proceed in parallel.
 *
 * <p>The text of the lines given to the sink is a byte string (see {@link Strs}) like all text
 * inside the engine; {@link Lines#decode} converts a line to real text.
 */
public final class Ctx {
  /** Receives the output of a run, from any of its threads but never concurrently. */
  public interface Sink {
    void emit(Line line);

    /** Called where the command line flushes standard output. */
    default void flush() {}
  }

  private final Sink sink;
  private final Instant clock;
  private final long start = System.currentTimeMillis();
  final Fs fs;

  /**
   * @param workDir directory against which relative file names are resolved, null for the
   *     process working directory
   * @param clock the current time for the dates of updates, null for the system clock
   */
  public Ctx(Sink sink, Path workDir, Instant clock) {
    this.sink = sink;
    this.clock = clock;
    this.fs = new Fs(this, workDir);
  }

  public synchronized void emit(Line line) {
    sink.emit(line);
  }

  public synchronized void flush() {
    sink.flush();
  }

  /** printf to stdout. */
  void printf(String format, Object... args) {
    print(String.format(java.util.Locale.ROOT, format, args));
  }

  public void print(String s) {
    emit(new InfoLine.Text(s));
  }

  /** Text to stderr. */
  public void eprint(String s) {
    emit(new ErrorLine.Message(s));
  }

  void eprintf(String format, Object... args) {
    eprint(String.format(java.util.Locale.ROOT, format, args));
  }

  /** Milliseconds since the run started. */
  public long elapsedMillis() {
    return System.currentTimeMillis() - start;
  }

  /** The current time: the clock of the run if it has one, else the system clock. */
  public Instant currentTime() {
    return clock != null ? clock : Instant.now();
  }

  /** Current time as a decimal date YYYYMMDDHHMMSS (UT). */
  public long now() {
    ZonedDateTime t = currentTime().atZone(ZoneOffset.UTC);
    return t.getYear() * 10000000000L + t.getMonthValue() * 100000000L
        + t.getDayOfMonth() * 1000000L + t.getHour() * 10000L + t.getMinute() * 100L
        + t.getSecond();
  }

  /** Prints percent done (td/ts) and estimated time remaining. */
  void printProgress(long ts, long td, int sum) {
    if (td > ts) td = ts;
    if (td >= 1000000) {
      double eta = 0.001 * elapsedMillis() * (ts - td) / (td + 1.0);
      final double percent = td * 100.0 / (ts + 0.5);
      emit(new InfoLine.Progress(percent, (int) eta,
          String.format(java.util.Locale.ROOT, "%5.2f%% %d:%02d:%02d %s", percent,
              (int) (eta / 3600), (int) (eta / 60) % 60, (int) eta % 60, sum > 0 ? "\r" : "")));
      if (sum > 0) flush();
    }
  }

  /** Reports a file that could not be opened and returns the exception to throw. */
  ZpaqException ioerr(String name, Exception e) {
    if (e != null) fs.printerr(name, e);
    else fs.printerr(name, "I/O error");
    return new ZpaqException(name);
  }
}
