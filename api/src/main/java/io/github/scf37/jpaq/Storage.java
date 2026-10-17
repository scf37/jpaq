package io.github.scf37.jpaq;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * Where archive files (tomes, parts and index files) live. Names are the archive and index names
 * given to the command, with {@code *}/{@code ?} replaced by the part number. The archiver never
 * keeps a file open longer than a command and never lists a directory.
 *
 * <p><b>Errors.</b> A failed operation is just a failure: the archiver never looks at the type of
 * the exception, because a streaming storage (a shell script) cannot tell a missing blob from an
 * I/O error or a rejected login. Whether a blob exists is only ever asked through {@link
 * Capability#PROBE}. Without it the sequential format needs no such knowledge: the caller says
 * whether an index exists by passing its name, tomes are written without looking, and the number
 * of tomes comes from the index. The classic format and index-less multi-part archives need PROBE.
 *
 * <p>The core is what a shell script can provide: stream a named blob out, stream one in, delete
 * one. Everything else is declared in {@link #capabilities()}. Implement this interface for other
 * places; {@link #ofDirectory} and {@link #ofShell} are the ones that come with the library.
 */
public interface Storage {
  /**
   * Archive files in a directory of the local file system, with all capabilities. Relative names
   * resolve against {@code directory} (null: the process working directory).
   */
  static Storage ofDirectory(Path directory) {
    return new DirectoryStorage(directory);
  }

  /**
   * A storage made of three shell commands, as simple as a backup script: each is run with {@code
   * sh -c} in {@code directory} (null: the current one) and gets the blob name as {@code $1}.
   *
   * <ul>
   *   <li>read: writes the blob to its stdout, e.g. {@code cat "/backup/$1"}
   *   <li>write: reads the new blob from its stdin, e.g. {@code cat > "/backup/$1"} or {@code aws
   *       s3 cp - "s3://bucket/$1"}
   *   <li>delete: removes the blob, e.g. {@code rm -f "/backup/$1"}
   * </ul>
   *
   * A command that fails (exit code not 0) makes the operation fail; the reason is not known,
   * which is all the archiver needs. No capabilities beyond streaming.
   */
  static Storage ofShell(Path directory, String read, String write, String delete) {
    return new ShellStorage(read, write, delete, directory);
  }

  /** Streams the blob; throws if it can't be read, for whatever reason. */
  InputStream read(String name) throws IOException;

  /**
   * Streams a new blob. The content may become visible only when the stream is closed; an existing
   * blob of that name is replaced. Closing without having written a byte may create an empty blob.
   */
  OutputStream write(String name) throws IOException;

  /** Deletes the blob if it exists. */
  void delete(String name) throws IOException;

  /** What this storage can do beyond the core operations. */
  default Set<Capability> capabilities() {
    return Set.of();
  }

  /** {@link Capability#PROBE}. */
  default boolean exists(String name) throws IOException {
    throw new UnsupportedOperationException("exists");
  }

  /** {@link Capability#PROBE}: size in bytes of an existing blob. */
  default long length(String name) throws IOException {
    throw new UnsupportedOperationException("length");
  }

  /** {@link Capability#RANGED_READ}: streams the blob starting at byte {@code offset}. */
  default InputStream read(String name, long offset) throws IOException {
    throw new UnsupportedOperationException("ranged read");
  }

  /**
   * {@link Capability#RANDOM_WRITE}: opens a blob for reading at any position, and for writing if
   * {@code write}. With {@code create} (only with {@code write}) a missing blob is created empty,
   * otherwise it must exist.
   */
  default RandomFile open(String name, boolean write, boolean create) throws IOException {
    throw new UnsupportedOperationException("open");
  }

  /** Optional abilities of a storage, beyond streaming read, write and delete. */
  enum Capability {
    /** {@link #exists} and {@link #length} work (needed to find archive parts). */
    PROBE,
    /**
     * {@link #read(String, long)} starts reading at an offset without transferring the skipped
     * bytes.
     */
    RANGED_READ,
    /** {@link #open} gives positional read, write, truncate (needed by the classic format). */
    RANDOM_WRITE
  }

  /** A blob with a position, like a C stdio stream opened "rb+" (see {@link #open}). */
  interface RandomFile extends Closeable {
    /** Reads up to {@code len} bytes at the current position; returns 0 at the end of the file. */
    int read(byte[] buf, int off, int len) throws IOException;

    void write(byte[] buf, int off, int len) throws IOException;

    /** Sets the position, which may be past the end of the file. */
    void seek(long pos) throws IOException;

    long length() throws IOException;

    void setLength(long len) throws IOException;
  }
}
