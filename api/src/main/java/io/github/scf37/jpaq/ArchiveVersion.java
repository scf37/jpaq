package io.github.scf37.jpaq;

import java.time.Instant;

/**
 * A version of an archive: the result of one update.
 *
 * @param number 1 for the first update
 * @param date its date, decimal YYYYMMDDHHMMSS (UT)
 * @param updates entries added or changed
 * @param deletes entries deleted
 * @param offset where it starts in the archive (counting the parts as one)
 * @param firstFragment the first fragment it added
 */
public record ArchiveVersion(int number, long date, int updates, int deletes, long offset,
    int firstFragment) {
  public Instant instant() {
    return Dates.instant(date);
  }
}
