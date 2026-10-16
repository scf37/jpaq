package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Ctx;
import io.github.scf37.jpaq.engine.Jidac;
import io.github.scf37.jpaq.engine.Lines;
import io.github.scf37.jpaq.engine.Strs;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One operation on an archive: its engine context, the output lines it produced (as real text)
 * and the conversion of engine failures to exceptions of the API.
 */
final class Run {
  private final Consumer<Line> listener;
  private final List<Line> lines = new ArrayList<>();
  private final List<ErrorLine> problems = new ArrayList<>();
  private RuntimeException listenerFailure;
  final Ctx ctx;

  Run(ZpaqArchive archive, Instant clock) {
    this.listener = archive.listener();
    this.ctx = new Ctx(new Ctx.Sink() {
      @Override
      public void emit(Line line) {
        accept(Lines.decode(line));
      }
    }, null, clock);
  }

  private void accept(Line line) {
    if (line instanceof ErrorLine e) problems.add(e);
    else if (!(line instanceof InfoLine.Text || line instanceof InfoLine.Progress)) lines.add(line);
    if (listener == null || listenerFailure != null) return;
    try {
      listener.accept(line);
    } catch (RuntimeException e) {
      listenerFailure = e;  // stop delivering; thrown when the operation is over
    }
  }

  /** A new engine command on the archive with its key and thread count. */
  Jidac jidac(ZpaqArchive a, Format format) {
    Jidac j = new Jidac(ctx, a.storage(), format);
    j.archive = Strs.toBytes(a.name());
    if (a.key() != null) j.password = Jidac.hashPassword(Strs.toBytes(a.key()));
    j.threads = a.threads();
    j.date = ctx.now();
    return j;
  }

  /** The problems reported so far (files that could not be read, damaged blocks...). */
  List<ErrorLine> problems() {
    return List.copyOf(problems);
  }

  /** The last line of a type, or null. */
  <T extends Line> T last(Class<T> type) {
    for (int i = lines.size() - 1; i >= 0; i--)
      if (type.isInstance(lines.get(i))) return type.cast(lines.get(i));
    return null;
  }

  <T extends Line> int count(Class<T> type) {
    int n = 0;
    for (Line l : lines) if (type.isInstance(l)) n++;
    return n;
  }

  /** Throws what the listener threw, if it did. */
  void rethrowListenerFailure() {
    if (listenerFailure != null) throw listenerFailure;
  }

  /**
   * The exception to throw for a failure of the engine: I/O errors as they are, a missing
   * archive file as {@link java.nio.file.NoSuchFileException}, other failures (damaged archive,
   * wrong password) as {@link ArchiveException}. Programming errors are rethrown.
   */
  IOException failure(RuntimeException e) {
    if (e instanceof UncheckedIOException u) return u.getCause();
    if (e instanceof ZpaqException) {
      String m = Jidac.what(e);
      for (int i = problems.size() - 1; i >= 0; i--) {
        if (problems.get(i) instanceof ErrorLine.FileError f && f.name().equals(m)) {
          if (f.cause() instanceof java.nio.file.NoSuchFileException)
            return new java.nio.file.NoSuchFileException(m);
          if (f.cause() instanceof IOException io) return io;
          return new ArchiveException(m + ": " + f.reason(), e);
        }
      }
      return new ArchiveException(m, e);
    }
    throw e;
  }

  /** Runs an engine action, converting its failures. */
  <T> T call(java.util.function.Supplier<T> action) throws IOException {
    try {
      T r = action.get();
      rethrowListenerFailure();
      return r;
    } catch (RuntimeException e) {
      rethrowListenerFailure();
      throw failure(e);
    }
  }

  static String bytes(String name) {
    return Strs.toBytes(name);
  }

  static String text(String bytes) {
    return Strs.fromBytes(bytes);
  }

  static String bytes(Path p) {
    String s = p.toString();
    if (java.io.File.separatorChar != '/') s = s.replace(java.io.File.separatorChar, '/');
    return Strs.toBytes(s);
  }
}
