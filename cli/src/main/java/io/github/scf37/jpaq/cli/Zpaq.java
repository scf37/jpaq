package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.api.Command;
import io.github.scf37.jpaq.api.Format;
import io.github.scf37.jpaq.api.Line;
import io.github.scf37.jpaq.api.LocalFsStorage;
import io.github.scf37.jpaq.api.Storage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Programmatic entry point to the zpaq command line, for applications that create or read
 * archives without starting a process. Never calls {@code System.exit}.
 *
 * <p>Two ways to call: with the arguments of the command line ({@link #run(Path, String...)},
 * output collected as text), or with a typed {@link Command}, a {@link Storage} that holds the
 * archive files and a receiver for the typed output {@link Line}s ({@link #run(Storage, Path,
 * Command, Consumer)}, {@link #stream}).
 *
 * <p>Calls are serialized (the program keeps global state), so concurrent callers wait for each
 * other. Text is Java strings in UTF-8.
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
    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    int rc;
    synchronized (LOCK) {
      Console.setSink(line -> {
        StringBuilder b = line.channel() == Line.Channel.STDOUT ? out : err;
        synchronized (b) {
          b.append(line.text());
        }
      });
      try {
        rc = Main.run(workDir, args);
      } finally {
        Console.setSink(null);
      }
    }
    return new Result(rc, out.toString(), err.toString());
  }

  /**
   * Runs a command; every output line is passed to {@code sink} (from any thread, but never
   * concurrently). Archive files are read from and written to {@code storage}; files to add and
   * extracted files are on the local file system, relative to {@code workDir}.
   *
   * @return the exit code: 0 OK, 1 warnings, 2 errors
   * @throws io.github.scf37.jpaq.api.UnsupportedStorageException before anything is done, if the
   *     storage can't do what the command's format needs
   */
  public static int run(Storage storage, Path workDir, Command command, Consumer<Line> sink) {
    List<String> args = new ArrayList<>();
    Format format = Format.SEQUENTIAL;
    java.util.function.Consumer<Jidac> configure;
    final String archive = command.archive();
    switch (command) {
      case Command.Add c -> {
        format = c.format();
        args.addAll(List.of("a", archive));
        args.addAll(c.files());
        if (c.index() != null) args.addAll(List.of("-index", c.index()));
        args.addAll(c.options().toArgs());
        configure = j -> {
          j.archive = archive;
          j.indexOut = c.indexOut();
        };
        if (c.files().isEmpty()) throw new IllegalArgumentException("nothing to add");
      }
      case Command.Extract c -> {
        args.addAll(List.of("x", archive));
        args.addAll(c.files());
        if (!c.to().isEmpty()) {
          args.add("-to");
          args.addAll(c.to());
        }
        if (c.test()) args.add("-test");
        args.addAll(c.options().toArgs());
        configure = j -> {
          j.archive = archive;
          j.readIndex = c.index();
        };
      }
      case Command.ListArchive c -> {
        args.addAll(List.of("l", archive));
        args.addAll(c.files());
        args.addAll(c.options().toArgs());
        configure = j -> {
          j.archive = archive;
          j.readIndex = c.index();
        };
      }
      case Command.RebuildIndex c -> {
        args.addAll(List.of("x", archive, "-index", c.indexOut()));
        args.addAll(c.options().toArgs());
        configure = j -> j.archive = archive;
      }
    }
    ArchiveIo io = new ArchiveIo(storage, format);
    if (command instanceof Command.Add) io.checkWritable();  // before anything is read
    Throwable[] failure = new Throwable[1];
    int rc;
    synchronized (LOCK) {
      Console.setSink(line -> {
        synchronized (failure) {
          if (failure[0] != null) return;
          try {
            sink.accept(line);
          } catch (RuntimeException | Error e) {
            failure[0] = e;  // stop delivering, finish the command, then report
          }
        }
      });
      try {
        rc = Main.run(workDir, io, configure, args.toArray(new String[0]));
      } finally {
        Console.setSink(null);
      }
    }
    if (failure[0] instanceof RuntimeException e) throw e;
    if (failure[0] instanceof Error e) throw e;
    return rc;
  }

  /** Same with the archive files in a directory on the local file system. */
  public static int run(Path dir, Command command, Consumer<Line> sink) {
    return run(new LocalFsStorage(dir), dir, command, sink);
  }

  /**
   * Runs a command in a background thread and returns its output lines as a stream, which ends
   * when the command is finished (the last line is a {@link io.github.scf37.jpaq.api.InfoLine.Finished}
   * unless the command failed to start). Closing the stream early stops the delivery and waits
   * for the command to finish. Errors that {@link #run(Storage, Path, Command, Consumer)} would
   * throw surface as an exception when the stream is read to its end.
   */
  public static Stream<Line> stream(Storage storage, Path workDir, Command command) {
    BlockingQueue<Object> q = new ArrayBlockingQueue<>(256);
    final Object end = new Object();
    final boolean[] closed = {false};
    Thread t = new Thread(() -> {
      try {
        run(storage, workDir, command, line -> {
          try {
            while (!closed[0]) {
              if (q.offer(line, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
        put(q, end, closed);
      } catch (RuntimeException | Error e) {
        put(q, e, closed);
      }
    }, "zpaq-stream");
    t.setDaemon(true);
    t.start();
    java.util.Iterator<Line> it = new java.util.Iterator<>() {
      private Line next;
      private boolean done;

      @Override
      public boolean hasNext() {
        if (next != null) return true;
        if (done) return false;
        try {
          Object o = q.take();
          if (o == end) {
            done = true;
            return false;
          }
          if (o instanceof RuntimeException e) {
            done = true;
            throw e;
          }
          if (o instanceof Error e) {
            done = true;
            throw e;
          }
          next = (Line) o;
          return true;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        }
      }

      @Override
      public Line next() {
        if (!hasNext()) throw new NoSuchElementException();
        Line l = next;
        next = null;
        return l;
      }
    };
    return StreamSupport.stream(
        java.util.Spliterators.spliteratorUnknownSize(it, java.util.Spliterator.ORDERED), false)
        .onClose(() -> {
          closed[0] = true;
          try {
            t.join();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
  }

  private static void put(BlockingQueue<Object> q, Object o, boolean[] closed) {
    try {
      while (!closed[0]) {
        if (q.offer(o, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
