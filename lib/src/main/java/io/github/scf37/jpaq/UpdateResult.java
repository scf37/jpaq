package io.github.scf37.jpaq;

import java.time.Instant;
import java.util.List;

/**
 * What an update did.
 *
 * @param changed false if the update changed nothing, in which case nothing was written
 * @param version the number of the new version, or of the latest one if nothing changed
 * @param date the date of the update
 * @param added files and directories added or changed
 * @param removed entries deleted
 * @param inputBytes size of the files added
 * @param dedupedBytes the part of it that was not already in the archive
 * @param compressedBytes bytes written to the archive by this update
 * @param problems files that could not be read (they are not in the update)
 */
public record UpdateResult(boolean changed, int version, Instant date, int added, int removed,
    long inputBytes, long dedupedBytes, long compressedBytes, List<ErrorLine> problems) {
  public UpdateResult {
    problems = List.copyOf(problems);
  }

  /** True if every file could be added. */
  public boolean ok() {
    return problems.isEmpty();
  }
}
