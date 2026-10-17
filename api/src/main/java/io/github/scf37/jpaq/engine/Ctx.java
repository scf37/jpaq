package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.Event;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Consumer;

/**
 * What one operation needs from its surroundings: where its events go, the directory that
 * relative local file names are resolved against, and the clock. Nothing in the engine is
 * global, so operations with their own contexts run in parallel.
 */
public final class Ctx {
  private final Consumer<Event> sink;
  private final Instant clock;
  private final long start = System.currentTimeMillis();
  final Fs fs;

  /**
   * @param sink receives the events, from any thread of the operation but never concurrently
   * @param workDir directory against which relative local names are resolved, null for the
   *     process working directory
   * @param clock the current time for the date of an update, null for the system clock
   */
  public Ctx(Consumer<Event> sink, Path workDir, Instant clock) {
    this.sink = sink;
    this.clock = clock;
    this.fs = new Fs(this, workDir);
  }

  public synchronized void emit(Event e) {
    sink.accept(e);
  }

  /** Text of a byte string, for events. */
  static String t(String bytes) {
    return Strs.fromBytes(bytes);
  }

  /** Information to show (stdout of zpaq). */
  void note(String text) {
    emit(Event.Note.of(t(text)));
  }

  /** A diagnostic (stderr of zpaq). */
  void diagnostic(String text) {
    emit(Event.Diagnostic.of(t(text)));
  }

  /** Milliseconds since the operation started. */
  long elapsedMillis() {
    return System.currentTimeMillis() - start;
  }

  /** The current time: the clock of the operation if it has one, else the system clock. */
  Instant currentTime() {
    return clock != null ? clock : Instant.now();
  }

  /** Current time as a decimal date YYYYMMDDHHMMSS (UT). */
  public long now() {
    ZonedDateTime t = currentTime().atZone(ZoneOffset.UTC);
    return t.getYear() * 10000000000L + t.getMonthValue() * 100000000L
        + t.getDayOfMonth() * 1000000L + t.getHour() * 10000L + t.getMinute() * 100L
        + t.getSecond();
  }

  /** Reports percent done (td/ts) and estimated time remaining, once there is something done. */
  void printProgress(long ts, long td) {
    if (td > ts) td = ts;
    if (td >= 1000000) {
      double eta = 0.001 * elapsedMillis() * (ts - td) / (td + 1.0);
      emit(Event.Progress.of(td * 100.0 / (ts + 0.5), (int) eta));
    }
  }

  /** Reports a file that could not be opened and returns the exception to throw. */
  ZpaqException ioerr(String name, Exception e) {
    if (e != null) fs.printerr(name, e);
    else fs.printerr(name, "I/O error");
    return new ZpaqException(name);
  }
}
