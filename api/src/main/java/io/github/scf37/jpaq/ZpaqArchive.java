package io.github.scf37.jpaq;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A zpaq journaling archive: a set of files in a {@link Storage} holding every version of a set
 * of local files, deduplicated and compressed.
 *
 * <p>A {@code ZpaqArchive} is an immutable, thread safe handle: where the archive is and the
 * {@link Option}s to work on it with (password, format, threads, ...). Every operation reads or
 * writes the archive anew. What an operation does is described by a builder that the operation
 * hands to a lambda; the builder is only valid during that call. Operations on different
 * archives run in parallel; two operations writing one archive at the same time corrupt it.
 *
 * <pre>{@code
 * ZpaqArchive a = ZpaqArchive.of(Path.of("backup.zpaq"), Option.key("secret"));
 * a.add(b -> b.files("/home/me/docs").method("2"));            // a new version
 * ArchiveContents c = a.read();                                // file list of the latest
 * a.extract(x -> x.version(3).under("/restore"));              // an older version
 * }</pre>
 *
 * <p>Specifications are plain {@link Consumer}s, so they compose: {@code Consumer<ArchiveSelector<?>>
 * old = s -> s.version(3)} selects for {@link #read}, {@link #extract}, {@link #repack} and
 * {@link #writeIndex} alike, and {@code spec.andThen(b -> b.force(true))} adds to one.
 *
 * <p>Formats: {@link Format#CLASSIC} archives are those of zpaq 7.15, byte for byte. {@link
 * Format#SEQUENTIAL} ones (jpaq) are written front to back once, for storages that can only
 * stream (see docs/SEQUENTIAL.md). Reading accepts both; the format matters for writing.
 *
 * <p>Archive names are given to the storage as they are; {@code *} and {@code ?} in them stand
 * for the number of the part of a multi-part archive ({@code "arc???.zpaq"}). Names of entries
 * are '/' separated, directories end with '/'. Names are bytes in an archive: valid UTF-8 is
 * decoded, other bytes become the chars U+DC80..U+DCFF so that every name converts back to the
 * bytes it came from ({@link #decodeName}, {@link #encodeName}).
 *
 * <p>Failures: {@link ArchiveException} (damaged archive, wrong password, not an archive, an
 * operation that does not fit the archive), other {@link IOException}s from the storage or local
 * files, {@link UnsupportedStorageException} if the storage cannot do what the format needs.
 * Problems with single files are reported as {@link Event.Problem}s and counted in the result.
 */
public sealed interface ZpaqArchive permits ArchiveImpl {

  /** The archive {@code name} in {@code storage}; without options: classic format, no password. */
  static ZpaqArchive of(Storage storage, String name, Option... options) {
    return ArchiveImpl.of(Objects.requireNonNull(storage, "storage"),
        Objects.requireNonNull(name, "name"), options);
  }

  /** An archive file on the local file system. */
  static ZpaqArchive of(Path file, Option... options) {
    Path abs = file.toAbsolutePath();
    return of(Storage.ofDirectory(abs.getParent()), abs.getFileName().toString(), options);
  }

  /** The same archive with more options; they replace options of the same kind. */
  ZpaqArchive withOptions(Option... options);

  Storage storage();

  String name();

  /** The format that updates write. */
  Format format();

  // ---------------------------------------------------------------------------------------
  // Operations

  /** Reads the file list of the latest version. */
  default ArchiveContents read() throws IOException {
    return read(r -> { });
  }

  /**
   * Reads the file list of a version.
   *
   * @throws ArchiveException if it is not an archive or the password is wrong (damaged blocks are
   *     skipped and reported as {@link Event.BlockSkipped})
   */
  ArchiveContents read(Consumer<? super ReadBuilder> spec) throws IOException;

  /**
   * Adds a new version. It adds the local files (directories with their contents) that are new or
   * whose date, size or attributes changed, and deletes the entries in its {@linkplain
   * AddBuilder#scope scope} whose local file is gone. A failed update leaves the archive (and its
   * index) as it was.
   */
  AddResult add(Consumer<? super AddBuilder> spec) throws IOException;

  /**
   * Extracts (or {@linkplain ExtractBuilder#test tests}) files. Damaged files and files that cannot
   * be written are reported and counted; the others are extracted.
   */
  ExtractResult extract(Consumer<? super ExtractBuilder> spec) throws IOException;

  /**
   * Writes the selected files of a version to a new archive {@code target} in the same storage
   * (classic format). The data blocks that hold them are copied as they are.
   */
  void repack(String target, Consumer<? super RepackBuilder> spec) throws IOException;

  /** Copies the whole archive to {@code target} in the same storage, as it is. */
  void copy(String target, Consumer<? super CopyBuilder> spec) throws IOException;

  /**
   * Writes an index of the archive (up to a version): a small file with its file list, which
   * {@link ArchiveSelector#index} and {@link AddBuilder#index} use instead of the archive. For a
   * sequential archive whose index was lost, this recovers it (the storage needs {@link
   * Storage.Capability#PROBE} to find the tomes).
   */
  void writeIndex(String index, Consumer<? super IndexBuilder> spec) throws IOException;

  /**
   * The local files and directories as {@link #add} sees them (directories end with '/'), in name
   * order, with the dates, sizes and attributes an update would store. Files that can't be read
   * are reported to the listener as {@link Event.FileError}s.
   */
  List<LocalFile> scan(Consumer<? super ScanBuilder> spec);

  // ---------------------------------------------------------------------------------------
  // Names

  /** The string for a name given as bytes. */
  static String decodeName(byte[] name) {
    return ArchiveImpl.decodeName(name);
  }

  /** The bytes of a name. */
  static byte[] encodeName(String name) {
    return ArchiveImpl.encodeName(name);
  }

  // ---------------------------------------------------------------------------------------
  // Options

  /**
   * How to work on an archive. Options of a kind replace each other: the last one given wins.
   * {@link Format} values are options too.
   */
  sealed interface Option permits Format, ArchiveImpl.Opt {
    /** Password for AES-256 encryption; null for none (the default). */
    static Option key(String password) {
      return new ArchiveImpl.Opt.Key(password);
    }

    /** Threads to compress and decompress with; 0 for all cores (the default). */
    static Option threads(int threads) {
      if (threads < 0) throw new IllegalArgumentException("threads < 0");
      return new ArchiveImpl.Opt.Threads(threads);
    }

    /** Directory that relative local file names are resolved against; null for the current one. */
    static Option directory(Path directory) {
      return new ArchiveImpl.Opt.Directory(directory);
    }

    /**
     * The clock: the current time is the date of an update (unless {@link AddBuilder#date} says
     * otherwise) and of the entries of streaming format archives, which store none. Null for
     * the system clock.
     */
    static Option clock(Clock clock) {
      return new ArchiveImpl.Opt.Time(clock);
    }

    /**
     * Receiver for the progress and problems of operations. Called from any thread of an
     * operation, never concurrently. If it throws, no more events are delivered and the
     * operation fails with that exception when it is done.
     */
    static Option listener(Consumer<? super Event> listener) {
      return new ArchiveImpl.Opt.Listener(listener);
    }
  }

  /** Archive format written by updates (reading accepts both). */
  enum Format implements Option {
    /**
     * The zpaq 7.15 format, byte identical to the C++ program, files ".zpaq". Writing needs
     * {@link Storage.Capability#RANDOM_WRITE} (the transaction header is patched at the end) and,
     * for multi part archives without an index, {@link Storage.Capability#PROBE}.
     */
    CLASSIC,
    /**
     * Append-only variant ("sequential tome", files ".jpaq"): blocks carry a different magic so
     * zpaq cannot read them, every file is written front to back exactly once and nothing is
     * patched. Needs only streaming read, write and delete, and an index. The archive name needs a
     * part number ({@code "arc???.jpaq"}).
     */
    SEQUENTIAL
  }

  // ---------------------------------------------------------------------------------------
  // Builders

  /**
   * Which version of an archive to read, which entries, and from where. By default the latest
   * version, every entry, read from the archive.
   *
   * @param <B> the builder type
   */
  sealed interface ArchiveSelector<B extends ArchiveSelector<B>>
      permits ReadBuilder, ExtractBuilder, RepackBuilder, IndexBuilder {
    /**
     * Read the file list from this index instead of the archive; the archive files are then only
     * read for data. Needed to read a sequential archive from a storage without {@link
     * Storage.Capability#PROBE}. Null to read the archive.
     */
    B index(String index);

    /**
     * Read up to this version (1 = the first update, 0 = none); negative counts back from the
     * latest (-1 = all but the last update). Replaces {@link #until}.
     */
    B version(long version);

    /** Read the updates made up to this time. Replaces {@link #version}. */
    B until(Instant time);

    /**
     * Every version of every entry, named {@code NNNN/name} with the version number in {@code
     * digits} digits (deletions are entries with date 0); 0 for the entries of the version read.
     */
    B allVersions(int digits);

    /**
     * Names of the entries to read (with the version directory with {@link #allVersions}); others
     * are not loaded. Null for all.
     */
    B select(Predicate<String> names);

    /** Read attributes as none. */
    B noAttributes(boolean noAttributes);
  }

  /** Specifies {@link #read}. */
  sealed interface ReadBuilder extends ArchiveSelector<ReadBuilder> permits ArchiveImpl.ReadSpec {}

  /** Specifies {@link #extract}: the version and entries, and where to. */
  sealed interface ExtractBuilder extends ArchiveSelector<ExtractBuilder>
      permits ArchiveImpl.ExtractSpec {
    /**
     * Archive name to local file name (relative names are resolved against the {@linkplain
     * Option#directory directory}); returning null skips the entry. By default the archive names,
     * absolute ones included.
     */
    ExtractBuilder output(UnaryOperator<String> output);

    /**
     * Puts every entry under {@code directory}: a leading '/' or drive letter is dropped, and
     * names with a ".." component are skipped. Replaces {@link #output}.
     */
    ExtractBuilder under(String directory);

    /**
     * Replace existing files (identical ones are left as they are, with their date and attributes
     * set); otherwise existing files are skipped.
     */
    ExtractBuilder overwrite(boolean overwrite);

    /** Decompress and verify only, write nothing. */
    ExtractBuilder test(boolean test);
  }

  /** Specifies {@link #repack}: the version and entries, and the new archive. */
  sealed interface RepackBuilder extends ArchiveSelector<RepackBuilder>
      permits ArchiveImpl.RepackSpec {
    /** Archive name to name in the new archive; returning null leaves the entry out. */
    RepackBuilder rename(UnaryOperator<String> rename);

    /** Password of the new archive; null (the default) for none. */
    RepackBuilder key(String password);

    /** Replace an existing target. */
    RepackBuilder overwrite(boolean overwrite);
  }

  /** Specifies {@link #copy}. */
  sealed interface CopyBuilder permits ArchiveImpl.CopySpec {
    /** Password of the copy; null (the default) for none: the copy is decrypted. */
    CopyBuilder key(String password);

    /** Replace an existing target. */
    CopyBuilder overwrite(boolean overwrite);
  }

  /** Specifies {@link #writeIndex}: the version the index is of. */
  sealed interface IndexBuilder extends ArchiveSelector<IndexBuilder>
      permits ArchiveImpl.IndexSpec {
    /**
     * Replace an existing index (storages with {@link Storage.Capability#PROBE} refuse to
     * overwrite otherwise).
     */
    IndexBuilder overwrite(boolean overwrite);
  }

  /**
   * Which local files to look at. Local names are '/' separated, as given in {@link #files} plus
   * the names of the directory contents, relative to the {@linkplain Option#directory directory}.
   *
   * @param <B> the builder type
   */
  sealed interface LocalSelector<B extends LocalSelector<B>> permits ScanBuilder, AddBuilder {
    /** Local files and directories to add (to those given before). */
    B files(String... files);

    /** Local files and directories to add (to those given before). */
    B files(Collection<String> files);

    /** Local names not looked at (a directory is not entered); null for none. */
    B prune(Predicate<String> prune);

    /** Local names to take, of those looked at; null for all. */
    B accept(Predicate<String> accept);

    /** Do not read (or store) permissions or Windows attributes. */
    B noAttributes(boolean noAttributes);
  }

  /** Specifies {@link #scan}. */
  sealed interface ScanBuilder extends LocalSelector<ScanBuilder> permits ArchiveImpl.ScanSpec {}

  /** Specifies {@link #add}: which local files, how they are named, and how to store them. */
  sealed interface AddBuilder extends LocalSelector<AddBuilder> permits ArchiveImpl.AddSpec {
    /** Local name to name in the archive; by default the local name. */
    AddBuilder archiveName(UnaryOperator<String> archiveName);

    /**
     * Archive names the update is about: of these, those whose local file was not found are
     * deleted. Null (the default) for the names of the {@link #files} and everything under them.
     */
    AddBuilder scope(Predicate<String> scope);

    /**
     * Compression: "0" (store, deduplicate only) .. "5" (best), optionally followed by the block
     * size 2^n MiB ("16": level 1, 64 MiB blocks), "x..." (see libzpaq.h), "s..." for the
     * streaming format (one block per file, no deduplication), "i..." to write the file list only
     * (an index of local files). The default is "1".
     */
    AddBuilder method(String method);

    /** Average fragment size for deduplication, 2^n KiB (default 6). */
    AddBuilder fragment(int log2Kib);

    /** Add files whose date and size did not change too. */
    AddBuilder force(boolean force);

    /**
     * The index of the archive so far. {@link Format#CLASSIC}: optional; with an index each update
     * is a new part ({@code "arc???.zpaq"}) and the index is updated in place. {@link
     * Format#SEQUENTIAL}: required unless the archive is new.
     */
    AddBuilder index(String index);

    /**
     * {@link Format#SEQUENTIAL}: the updated index, a new name (the old index is not touched);
     * required.
     */
    AddBuilder indexOut(String indexOut);

    /** The date of the update; by default the time of the {@linkplain Option#clock clock}. */
    AddBuilder date(Instant date);

    /**
     * Classic format: remove the updates after this version first (0: all; negative counts back
     * from the latest). Replaces {@link #rollback(Instant)}.
     */
    AddBuilder rollback(long version);

    /** Classic format: remove the updates made after this time first. */
    AddBuilder rollback(Instant time);
  }

  // ---------------------------------------------------------------------------------------
  // Results

  /** What an update did. */
  sealed interface AddResult permits ArchiveImpl.Added {
    /** Entries added or changed. */
    int added();

    /** Entries deleted. */
    int removed();

    /** Size of the files compressed. */
    long inputBytes();

    /** The part of {@link #inputBytes} that was not already in the archive. */
    long dedupedBytes();

    /** Bytes written to the archive (nothing if the update changed nothing). */
    long addedBytes();

    /** Size of the archive after the update. */
    long archiveBytes();

    /** Files that could not be read (reported as {@link Event.FileError}, not added). */
    int unreadable();

    /** True if the update changed the archive. */
    default boolean changed() {
      return added() + removed() > 0;
    }
  }

  /** What an extraction (or test) did. */
  sealed interface ExtractResult permits ArchiveImpl.Extracted {
    /** Files selected (without those skipped). */
    int files();

    /** Existing files left as they are. */
    int skipped();

    /** Files not extracted completely (reported as {@link Event.ExtractFailed}). */
    int failed();

    default boolean ok() {
      return failed() == 0;
    }
  }

  /** A local file or directory as an update sees it (see {@link #scan}). */
  sealed interface LocalFile permits ArchiveImpl.Local {
    /** '/' separated; directories end with '/'. */
    String name();

    /** Modification date, decimal YYYYMMDDHHMMSS (UT). */
    long date();

    /** Size in bytes (0 for directories). */
    long size();

    Attributes attributes();

    default boolean isDirectory() {
      return name().endsWith("/");
    }

    default Instant lastModified() {
      return ArchiveImpl.instant(date());
    }
  }

  /**
   * File attributes as zpaq stores them: unix permission bits, Windows attributes, or none. A
   * value: equal attributes are {@code equals}.
   */
  sealed interface Attributes permits ArchiveImpl.Attrs {
    /** No attributes; extraction leaves the defaults of the file system. */
    Attributes NONE = new ArchiveImpl.Attrs(0);

    /** Attributes as stored: {@code 'u' + (mode << 8)}, {@code 'w' + (attributes << 8)}, or 0. */
    static Attributes of(long raw) {
      return raw == 0 ? NONE : new ArchiveImpl.Attrs(raw);
    }

    /** Unix mode bits (file type and permissions, as in {@code st_mode}). */
    static Attributes unix(int mode) {
      return of('u' + ((mode & 0xFFFFFFFFL) << 8));
    }

    /** Windows attributes (READONLY = 1, HIDDEN = 2, SYSTEM = 4, ARCHIVE = 0x20, ...). */
    static Attributes windows(int attributes) {
      return of('w' + ((attributes & 0xFFFFFFFFL) << 8));
    }

    /** The stored value. */
    long raw();

    /** The unix mode, if these are unix attributes. */
    default OptionalInt unixMode() {
      return (raw() & 255) == 'u' ? OptionalInt.of((int) (raw() >>> 8)) : OptionalInt.empty();
    }

    /** The Windows attributes, if these are Windows attributes. */
    default OptionalInt windowsAttributes() {
      return (raw() & 255) == 'w' ? OptionalInt.of((int) (raw() >>> 8)) : OptionalInt.empty();
    }
  }
}
