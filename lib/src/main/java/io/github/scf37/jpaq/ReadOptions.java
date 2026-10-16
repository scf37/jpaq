package io.github.scf37.jpaq;

import java.time.Instant;
import java.util.function.Predicate;

/**
 * Which version of an archive to read, which entries, and from where.
 *
 * @param index read the file list from this index instead of the archive; the archive files are
 *     then only read for data. Needed to read a sequential archive from a storage without {@link
 *     Capability#PROBE}. Null to read the archive.
 * @param version read up to this version (1 = the first update, 0 = none); negative counts back
 *     from the latest (-1 = all but the last update); null for the latest
 * @param date read the updates made up to this time; null for all. Not with {@code version}.
 * @param versionDigits 0 for the files of the version read; n &gt; 0 for every version of every
 *     file, named {@code NNNN/name} with the version number in n digits (deletions are entries
 *     with date 0)
 * @param select names of the entries to read (with the version directory when {@code
 *     versionDigits} &gt; 0); others are not loaded. Null for all.
 * @param noAttributes read attributes as none
 */
public record ReadOptions(String index, Long version, Instant date, int versionDigits,
    Predicate<String> select, boolean noAttributes) {
  /** The latest version, every entry, from the archive. */
  public static final ReadOptions LATEST = new ReadOptions(null, null, null, 0, null, false);

  public ReadOptions {
    if (version != null && date != null) throw new IllegalArgumentException("both a version and a date");
    if (versionDigits < 0 || versionDigits > 18) throw new IllegalArgumentException("versionDigits");
  }

  public ReadOptions withIndex(String index) {
    return new ReadOptions(index, version, date, versionDigits, select, noAttributes);
  }

  public ReadOptions withVersion(Long version) {
    return new ReadOptions(index, version, null, versionDigits, select, noAttributes);
  }

  public ReadOptions withDate(Instant date) {
    return new ReadOptions(index, null, date, versionDigits, select, noAttributes);
  }

  public ReadOptions withVersionDigits(int versionDigits) {
    return new ReadOptions(index, version, date, versionDigits, select, noAttributes);
  }

  public ReadOptions withSelect(Predicate<String> select) {
    return new ReadOptions(index, version, date, versionDigits, select, noAttributes);
  }

  public ReadOptions withNoAttributes(boolean noAttributes) {
    return new ReadOptions(index, version, date, versionDigits, select, noAttributes);
  }
}
