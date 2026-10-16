package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Dates;
import io.github.scf37.jpaq.engine.EntryReader;
import io.github.scf37.jpaq.engine.Jidac;
import io.github.scf37.jpaq.engine.Updater;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A zpaq (or jpaq) journaling archive: a set of files in a {@link Storage} that holds every
 * version of a set of files, deduplicated and compressed. This object is only a handle (where the
 * archive is, its password and how to work on it); every operation reads or writes the archive
 * files anew. Handles are immutable and thread safe, and operations on different archives run in
 * parallel. Two operations that write the same archive at the same time corrupt it.
 *
 * <pre>{@code
 * ZpaqArchive archive = ZpaqArchive.of(Path.of("backup.zpaq")).withKey("secret");
 * try (ArchiveUpdate u = archive.update()) {
 *   u.addTree(Path.of("/home/me/docs"), "docs");        // new and changed files, deletions
 *   u.add("db/dump.sql", dumpProcess.getInputStream()); // any stream
 *   u.commit();
 * }
 * try (ArchiveReader r = archive.read()) {
 *   for (ArchiveEntry e : r.entries()) System.out.println(e);
 *   try (InputStream in = r.open("db/dump.sql")) { ... }
 * }
 * archive.extract(Path.of("/restore"), ExtractOptions.DEFAULT.withInclude("docs"));
 * }</pre>
 *
 * <p>Names in archives are '/' separated; directories end with '/'. The archive name is given to
 * the storage as it is; {@code *} and {@code ?} in it stand for the number of the part of a multi
 * part archive ({@code "arc???.zpaq"}: arc001.zpaq, arc002.zpaq...).
 *
 * <p>Formats: {@link Format#CLASSIC} archives are those of zpaq 7.15, byte for byte; {@link
 * Format#SEQUENTIAL} ones are written front to back once, for storages that can only stream.
 * Reading accepts both; the format matters for writing.
 */
public final class ZpaqArchive {
  private final Storage storage;
  private final String name;
  private final String key;
  private final Format format;
  private final int threads;
  private final Consumer<Line> listener;

  private ZpaqArchive(Storage storage, String name, String key, Format format, int threads,
      Consumer<Line> listener) {
    this.storage = Objects.requireNonNull(storage, "storage");
    this.name = Objects.requireNonNull(name, "name");
    this.key = key;
    this.format = Objects.requireNonNull(format, "format");
    this.threads = threads;
    this.listener = listener;
  }

  /** The archive {@code name} in {@code storage}, classic format, no password. */
  public static ZpaqArchive of(Storage storage, String name) {
    return new ZpaqArchive(storage, name, null, Format.CLASSIC, 0, null);
  }

  /** An archive file on the local file system, classic format, no password. */
  public static ZpaqArchive of(Path file) {
    Path abs = file.toAbsolutePath();
    return of(new LocalFsStorage(abs.getParent()), abs.getFileName().toString());
  }

  /** Same archive with a password (AES-256 encryption); null for none. */
  public ZpaqArchive withKey(String key) {
    return new ZpaqArchive(storage, name, key, format, threads, listener);
  }

  /** Same archive written in another format (reading accepts both). */
  public ZpaqArchive withFormat(Format format) {
    return new ZpaqArchive(storage, name, key, format, threads, listener);
  }

  /** Same archive using {@code threads} threads to compress and decompress; 0 for all cores. */
  public ZpaqArchive withThreads(int threads) {
    if (threads < 0) throw new IllegalArgumentException("threads < 0");
    return new ZpaqArchive(storage, name, key, format, threads, listener);
  }

  /**
   * Same archive with a receiver for the progress and problems of operations, the lines that the
   * zpaq command line prints (see {@link InfoLine}, {@link ErrorLine}). Called from any thread of
   * an operation, but never concurrently; if it throws, the operation completes without calling
   * it again and then throws that exception.
   */
  public ZpaqArchive withListener(Consumer<Line> listener) {
    return new ZpaqArchive(storage, name, key, format, threads, listener);
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

  String key() {
    return key;
  }

  int threads() {
    return threads;
  }

  Consumer<Line> listener() {
    return listener;
  }

  /** Reads the latest version. See {@link #read(ReadOptions)}. */
  public ArchiveReader read() throws IOException {
    return read(ReadOptions.LATEST);
  }

  /**
   * Reads the list of files of a version of the archive; their contents are read when asked for.
   *
   * @throws java.nio.file.NoSuchFileException if the archive (or index) does not exist
   * @throws ArchiveException if it is not an archive, the password is wrong, or it is damaged
   *     beyond what can be skipped (damaged blocks are skipped and reported as problems)
   */
  public ArchiveReader read(ReadOptions options) throws IOException {
    Run run = new Run(this, null);
    Jidac j = run.jidac(this, format);
    applyRead(j, options);
    EntryReader r = run.call(() -> EntryReader.open(j));
    return new ArchiveReader(r, run.problems());
  }

  private static void applyRead(Jidac j, ReadOptions o) {
    if (o.version() != 0 && o.date() != null)
      throw new IllegalArgumentException("both a version and a date");
    if (o.index() != null) j.readIndex = Run.bytes(o.index());
    if (o.version() != 0) j.version = o.version();
    if (o.date() != null) j.version = Dates.decimalTime(o.date().getEpochSecond());
  }

  /** Begins an update with the default options. See {@link #update(UpdateOptions)}. */
  public ArchiveUpdate update() throws IOException {
    return update(UpdateOptions.DEFAULT);
  }

  /**
   * Begins an update: reads the archive (or index) and opens the output. Add files and delete
   * entries, then {@link ArchiveUpdate#commit}; closing without committing leaves the archive as
   * it was. A new archive is created if there is none (classic format) or no index is given
   * (sequential format).
   *
   * @throws UnsupportedStorageException if the storage cannot do what the format needs
   */
  public ArchiveUpdate update(UpdateOptions options) throws IOException {
    if (format == Format.SEQUENTIAL && options.indexOut() == null)
      throw new IllegalArgumentException("the sequential format needs indexOut");
    if (format == Format.SEQUENTIAL && name.indexOf('?') < 0 && name.indexOf('*') < 0)
      throw new IllegalArgumentException(
          "a sequential archive is a set of tomes: its name needs a part number, like arc???.jpaq");
    if (options.indexOut() != null && format != Format.SEQUENTIAL)
      throw new IllegalArgumentException("indexOut is for the sequential format");
    Run run = new Run(this, options.date());
    Jidac j = run.jidac(this, format);
    j.method = options.method();
    j.fragment = options.fragment();
    if (options.index() != null) j.index = Run.bytes(options.index());
    if (options.indexOut() != null) j.indexOut = Run.bytes(options.indexOut());
    j.force = options.force();
    j.noattributes = options.noAttributes();
    for (String e : options.exclude()) j.notfiles.add(Run.bytes(e));
    Updater u = new Updater(j);
    run.call(() -> {
      u.begin();
      return null;
    });
    return new ArchiveUpdate(run, u);
  }

  /** Extracts the latest version into {@code targetDir}. See {@link #extract(Path, ExtractOptions)}. */
  public ExtractResult extract(Path targetDir) throws IOException {
    return extract(targetDir, ExtractOptions.DEFAULT);
  }

  /**
   * Extracts files into {@code targetDir} under their names in the archive (a leading '/' or
   * drive letter is dropped), with their dates and attributes. Names with ".." components are
   * not extracted. Problems with single files (damaged data, files that can't be written) are
   * reported in the result; the others are extracted.
   */
  public ExtractResult extract(Path targetDir, ExtractOptions options) throws IOException {
    Run run = new Run(this, null);
    Jidac j = run.jidac(this, format);
    j.command = 'x';
    for (String s : options.include()) j.onlyfiles.add(Run.bytes(s));
    for (String s : options.exclude()) j.notfiles.add(Run.bytes(s));
    j.force = options.overwrite();
    j.noattributes = options.noAttributes();
    applyRead(j, options.read());
    j.tofiles.add(Run.bytes(targetDir.toAbsolutePath()));
    j.safeNames = true;
    run.call(j::run);
    return extractResult(run);
  }

  /** Decompresses the latest version and checks all checksums; writes nothing. */
  public ExtractResult verify() throws IOException {
    return verify(ReadOptions.LATEST);
  }

  /** Decompresses a version and checks all checksums; writes nothing. */
  public ExtractResult verify(ReadOptions options) throws IOException {
    Run run = new Run(this, null);
    Jidac j = run.jidac(this, format);
    j.command = 'x';
    j.dotest = true;
    applyRead(j, options);
    run.call(j::run);
    return extractResult(run);
  }

  private static ExtractResult extractResult(Run run) {
    InfoLine.ExtractPlan plan = run.last(InfoLine.ExtractPlan.class);
    InfoLine.SkippedSummary s = run.last(InfoLine.SkippedSummary.class);
    final int skipped = s != null ? s.count() : 0;
    int failed = 0;
    for (ErrorLine l : run.problems()) if (l instanceof ErrorLine.ExtractFailed) failed++;
    return new ExtractResult(plan != null ? plan.files() : 0, skipped, failed, run.problems());
  }

  /**
   * Writes an index of the archive: a small file with its file list, which {@link
   * ReadOptions#withIndex} and {@link UpdateOptions#withIndex} use instead of reading the whole
   * archive (zpaq "extract -index"). For a sequential archive whose index was lost, this
   * recovers it (the storage needs {@link Capability#PROBE} to find the tomes).
   */
  public void writeIndex(String indexName) throws IOException {
    Run run = new Run(this, null);
    Jidac j = run.jidac(this, format);
    j.command = 'x';
    j.index = Run.bytes(indexName);
    run.call(j::run);
  }

  @Override
  public String toString() {
    return "ZpaqArchive[" + name + ", " + format + (key != null ? ", encrypted" : "") + "]";
  }
}
