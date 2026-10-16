package io.github.scf37.jpaq;

import java.time.Instant;

/**
 * Which version of an archive to read, and from where.
 *
 * @param index read the file list from this index instead of the archive; the archive files
 *     (tomes, parts) are then only read for the data of files. Required to read a sequential
 *     archive from a storage without {@link Capability#PROBE}. Null to read the archive.
 * @param version read up to this version (1 = the first update); negative counts back from the
 *     latest (-1 = all but the last update); 0 for all versions
 * @param date read the updates made up to this time; null for all
 */
public record ReadOptions(String index, int version, Instant date) {
  /** The latest version, read from the archive. */
  public static final ReadOptions LATEST = new ReadOptions(null, 0, null);

  public ReadOptions withIndex(String index) {
    return new ReadOptions(index, version, date);
  }

  public ReadOptions withVersion(int version) {
    return new ReadOptions(index, version, date);
  }

  public ReadOptions withDate(Instant date) {
    return new ReadOptions(index, version, date);
  }
}
