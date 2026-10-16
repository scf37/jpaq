package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Ctx;
import io.github.scf37.jpaq.engine.Jidac;
import io.github.scf37.jpaq.engine.Strs;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A zpaq journaling archive: a set of files in a {@link Storage} holding every version of a set
 * of local files, deduplicated and compressed. This object is a handle (where the archive is, its
 * password, how to work on it); each operation reads or writes the archive anew. Handles are
 * immutable and thread safe; operations on different archives run in parallel. Two operations
 * writing one archive at the same time corrupt it.
 *
 * <pre>{@code
 * ZpaqArchive a = ZpaqArchive.of(Path.of("backup.zpaq")).withKey("secret");
 * a.add(AddOptions.of("/home/me/docs").withMethod("2"));              // new version
 * ArchiveContents c = a.read(ReadOptions.LATEST);                     // file list
 * a.extract(ExtractOptions.DEFAULT.withOutput(ExtractOptions.under("/restore")));
 * }</pre>
 *
 * <p>Formats: {@link Format#CLASSIC} archives are those of zpaq 7.15, byte for byte. {@link
 * Format#SEQUENTIAL} ones (jpaq) are written front to back once, for storages that can only
 * stream (see docs/SEQUENTIAL.md). Reading accepts both; the format matters for writing.
 *
 * <p>Archive names are given to the storage as they are; {@code *} and {@code ?} in them stand
 * for the number of the part of a multi-part archive ({@code "arc???.zpaq"}). Names of entries
 * are '/' separated, directories end with '/' (see {@link Names} for bytes that are not UTF-8).
 *
 * <p>Failures: {@link ArchiveException} (damaged archive, wrong password, not an archive, an
 * operation that does not fit the archive), other {@link IOException}s from the storage or local
 * files, {@link UnsupportedStorageException} if the storage cannot do what the format needs.
 * Problems with single files are reported as {@link Event.Problem}s and counted in the result.
 */
public final class ZpaqArchive {
  private final Storage storage;
  private final String name;
  private final String key;
  private final Format format;
  private final int threads;
  private final Path directory;
  private final Clock clock;
  private final Consumer<Event> listener;

  private ZpaqArchive(Storage storage, String name, String key, Format format, int threads,
      Path directory, Clock clock, Consumer<Event> listener) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.name = Objects.requireNonNull(name, "name");
    this.key = key;
    this.format = Objects.requireNonNull(format, "format");
    this.threads = threads;
    this.directory = directory;
    this.clock = clock;
    this.listener = listener;
  }

  /** The archive {@code name} in {@code storage}: classic format, no password. */
  public static ZpaqArchive of(Storage storage, String name) {
    return new ZpaqArchive(storage, name, null, Format.CLASSIC, 0, null, null, null);
  }

  /** An archive file on the local file system: classic format, no password. */
  public static ZpaqArchive of(Path file) {
    Path abs = file.toAbsolutePath();
    return of(new LocalFsStorage(abs.getParent()), abs.getFileName().toString());
  }

  /** Same with a password (AES-256 encryption), null for none. */
  public ZpaqArchive withKey(String key) {
    return new ZpaqArchive(storage, name, key, format, threads, directory, clock, listener);
  }

  /** Same written in another format (reading accepts both). */
  public ZpaqArchive withFormat(Format format) {
    return new ZpaqArchive(storage, name, key, format, threads, directory, clock, listener);
  }

  /** Same using {@code threads} threads to compress and decompress; 0 for all cores. */
  public ZpaqArchive withThreads(int threads) {
    if (threads < 0) throw new IllegalArgumentException("threads < 0");
    return new ZpaqArchive(storage, name, key, format, threads, directory, clock, listener);
  }

  /** Same with relative local file names resolved against {@code directory} (null: current). */
  public ZpaqArchive withDirectory(Path directory) {
    return new ZpaqArchive(storage, name, key, format, threads, directory, clock, listener);
  }

  /**
   * Same with another clock: the current time is the date of an update (unless {@link
   * AddOptions#date} says otherwise) and of the entries of streaming format archives, which store
   * none. Null for the system clock.
   */
  public ZpaqArchive withClock(Clock clock) {
    return new ZpaqArchive(storage, name, key, format, threads, directory, clock, listener);
  }

  /**
   * Same with a receiver for the progress and problems of operations. Called from any thread of
   * an operation, never concurrently. If it throws, the operation fails with that exception.
   */
  public ZpaqArchive withListener(Consumer<Event> listener) {
    return new ZpaqArchive(storage, name, key, format, threads, directory, clock, listener);
  }

  public Storage storage() {
    return storage;
  }

  public String name() {
    return name;
  }

  public Format format() {
    return format;
  }

  // ---------------------------------------------------------------------------------------

  /**
   * Reads the file list of a version.
   *
   * @throws ArchiveException if it is not an archive or the password is wrong (damaged blocks are
   *     skipped and reported as {@link Event.BlockSkipped})
   */
  public ArchiveContents read(ReadOptions options) throws IOException {
    Op op = op(null);
    Jidac j = op.j();
    applyRead(j, options);
    op.call(() -> {
      j.readContents();
      return null;
    });
    return new ArchiveContents(j);
  }

  /** Adds a new version: see {@link AddOptions}. */
  public AddResult add(AddOptions o) throws IOException {
    if (format == Format.SEQUENTIAL) {
      if (o.indexOut() == null)
        throw new IllegalArgumentException("the sequential format needs indexOut");
      if (name.indexOf('?') < 0 && name.indexOf('*') < 0)
        throw new IllegalArgumentException(
            "a sequential archive is a set of tomes: its name needs a part number, like arc???.jpaq");
    } else if (o.indexOut() != null) {
      throw new IllegalArgumentException("indexOut is for the sequential format");
    }
    final Event.AddSummary[] summary = {null};
    final Object[] totals = {null};
    Op op = op(e -> {
      if (e instanceof Event.AddSummary s) summary[0] = s;
      if (e instanceof Event.AddTotals || e instanceof Event.StreamingAddTotals) totals[0] = e;
    }, o.date());
    Jidac j = op.j();
    j.command = 'a';
    for (String f : o.files()) j.roots.add(Strs.toBytes(f));
    if (o.archiveName() != null) j.toArchive = Names.wrap(o.archiveName());
    if (o.prune() != null) j.prune = Names.wrap(o.prune());
    if (o.accept() != null) j.accept = Names.wrap(o.accept());
    j.select = o.scope() != null ? Names.wrap(o.scope()) : defaultScope(j);
    j.method = o.method();
    j.fragment = o.fragment();
    j.force = o.force();
    j.noattributes = o.noAttributes();
    if (o.index() != null) j.index = Strs.toBytes(o.index());
    if (o.indexOut() != null) j.indexOut = Strs.toBytes(o.indexOut());
    if (o.rollbackVersion() != null) j.version = o.rollbackVersion();
    if (o.rollbackDate() != null) j.version = decimal(o.rollbackDate());
    op.call(j::run);
    int added = summary[0] != null ? summary[0].added() : 0;
    int removed = summary[0] != null ? summary[0].removed() : 0;
    if (totals[0] instanceof Event.AddTotals t)
      return new AddResult(added, removed, t.inputBytes(), t.dedupedBytes(), t.addedBytes(),
          t.archiveBytes(), j.errors);
    if (totals[0] instanceof Event.StreamingAddTotals t)
      return new AddResult(added, removed, t.inputBytes(), t.inputBytes(), t.addedBytes(),
          t.archiveBytes(), j.errors);
    return new AddResult(added, removed, 0, 0, 0, 0, j.errors);
  }

  /** The archive names of the roots and everything under them. */
  private static java.util.function.Predicate<String> defaultScope(Jidac j) {
    java.util.List<String> names = new java.util.ArrayList<>();
    for (String r : j.roots) {
      String n = j.toArchive.apply(r);
      while (n.length() > 1 && n.endsWith("/")) n = n.substring(0, n.length() - 1);
      names.add(n);
    }
    return n -> {
      for (String r : names) {
        if (n.startsWith(r) && (n.length() == r.length() || n.charAt(r.length()) == '/'
            || r.endsWith("/"))) return true;
      }
      return false;
    };
  }

  /**
   * Extracts (or with {@link ExtractOptions#test} verifies) files: see {@link ExtractOptions}.
   * Damaged files and files that cannot be written are reported and counted; the others are
   * extracted.
   */
  public ExtractResult extract(ExtractOptions o) throws IOException {
    final int[] counts = {0, 0};  // files planned, skipped
    Op op = op(e -> {
      if (e instanceof Event.ExtractPlan p) counts[0] = p.files();
      if (e instanceof Event.SkippedSummary s) counts[1] = s.count();
    });
    Jidac j = op.j();
    j.command = 'x';
    applyRead(j, o.read());
    if (o.output() != null) j.toOutput = Names.wrap(o.output());
    j.force = o.overwrite();
    j.dotest = o.test();
    op.call(j::run);
    return new ExtractResult(counts[0], counts[1], j.errors);
  }

  /**
   * Writes the selected files of a version to a new archive {@code target} in the same storage
   * (classic format), with the key {@code targetKey} (null: not encrypted). The data blocks that
   * hold them are copied as they are; {@link ExtractOptions#output} renames, {@link
   * ExtractOptions#overwrite} replaces an existing target.
   */
  public void repack(String target, String targetKey, ExtractOptions o) throws IOException {
    Op op = op(null);
    Jidac j = op.j();
    j.command = 'x';
    applyRead(j, o.read());
    if (o.output() != null) j.toOutput = Names.wrap(o.output());
    j.force = o.overwrite();
    j.repack = Strs.toBytes(target);
    if (targetKey != null) j.newPassword = Jidac.hashPassword(Strs.toBytes(targetKey));
    op.call(j::run);
  }

  /**
   * Copies the whole archive to {@code target} in the same storage, as it is, but encrypted with
   * {@code targetKey} (null: decrypted).
   */
  public void copy(String target, String targetKey, boolean overwrite) throws IOException {
    Op op = op(null);
    Jidac j = op.j();
    j.command = 'x';
    j.all = 4;
    j.force = overwrite;
    j.repack = Strs.toBytes(target);
    if (targetKey != null) j.newPassword = Jidac.hashPassword(Strs.toBytes(targetKey));
    op.call(j::run);
  }

  /**
   * Writes an index of the archive (up to a version): a small file with its file list, which
   * {@link ReadOptions#index} and {@link AddOptions#index} use instead of the archive. For a
   * sequential archive whose index was lost, this recovers it (the storage needs {@link
   * Capability#PROBE} to find the tomes).
   *
   * @param overwrite replace an existing index (storages with {@link Capability#PROBE} refuse to
   *     overwrite otherwise)
   */
  public void writeIndex(String index, boolean overwrite, ReadOptions read) throws IOException {
    Op op = op(null);
    Jidac j = op.j();
    j.command = 'x';
    applyRead(j, read);
    j.index = Strs.toBytes(index);
    j.force = overwrite;
    op.call(j::run);
  }

  // ---------------------------------------------------------------------------------------

  /** One command on the archive: the engine with its options, and the guard of its events. */
  private record Op(Jidac j, ListenerGuard guard) {
    /** Runs the command; its failures become the exceptions of the API. */
    <T> T call(Supplier<T> action) throws IOException {
      T r;
      try {
        r = action.get();
      } catch (ZpaqException e) {
        guard.rethrow();
        throw new ArchiveException(Strs.fromBytes(Jidac.what(e)), e);
      } catch (UncheckedIOException e) {
        guard.rethrow();
        throw e.getCause();
      } catch (RuntimeException e) {
        guard.rethrow();
        throw e;
      }
      guard.rethrow();
      return r;
    }
  }

  private Op op(Consumer<Event> spy) {
    return op(spy, null);
  }

  /** A command on this archive; its events go to spy (if any) and then to the listener. */
  private Op op(Consumer<Event> spy, Instant now) {
    ListenerGuard guard = new ListenerGuard(spy, listener);
    if (now == null && clock != null) now = clock.instant();
    Jidac j = new Jidac(new Ctx(guard, directory, now), storage, format);
    j.archive = Strs.toBytes(name);
    if (key != null) j.password = Jidac.hashPassword(Strs.toBytes(key));
    j.threads = threads;
    j.date = j.ctx.now();
    return new Op(j, guard);
  }

  /** Delivers events; remembers the first exception of the listener and delivers no more. */
  private static final class ListenerGuard implements Consumer<Event> {
    private final Consumer<Event> spy;
    private final Consumer<Event> listener;
    private volatile RuntimeException failure;

    ListenerGuard(Consumer<Event> spy, Consumer<Event> listener) {
      this.spy = spy;
      this.listener = listener;
    }

    @Override
    public void accept(Event e) {
      if (spy != null) spy.accept(e);
      if (listener == null || failure != null) return;
      try {
        listener.accept(e);
      } catch (RuntimeException ex) {
        failure = ex;
      }
    }

    void rethrow() {
      if (failure != null) throw failure;
    }
  }

  private static void applyRead(Jidac j, ReadOptions o) {
    if (o.index() != null) j.readIndex = Strs.toBytes(o.index());
    if (o.version() != null) j.version = o.version();
    if (o.date() != null) j.version = decimal(o.date());
    j.all = o.versionDigits();
    if (o.select() != null) j.select = Names.wrap(o.select());
    j.noattributes = o.noAttributes();
  }

  /** Decimal date YYYYMMDDHHMMSS (UT). */
  private static long decimal(Instant t) {
    ZonedDateTime z = t.atZone(ZoneOffset.UTC);
    return z.getYear() * 10000000000L + z.getMonthValue() * 100000000L
        + z.getDayOfMonth() * 1000000L + z.getHour() * 10000L + z.getMinute() * 100L
        + z.getSecond();
  }

  @Override
  public String toString() {
    return "ZpaqArchive[" + name + ", " + format + (key != null ? ", encrypted" : "") + "]";
  }
}
