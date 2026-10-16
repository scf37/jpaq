package io.github.scf37.jpaq;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * one. Everything else is declared in {@link #capabilities()}.
 */
public interface Storage {
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
}
