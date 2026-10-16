package io.github.scf37.jpaq;

/**
 * What an update did.
 *
 * @param added entries added or changed
 * @param removed entries deleted
 * @param inputBytes size of the files compressed
 * @param dedupedBytes the part of it that was not already in the archive
 * @param addedBytes bytes written to the archive (nothing if the update changed nothing)
 * @param archiveBytes size of the archive after the update
 * @param unreadable files that could not be read (reported as {@link Event.FileError}, not added)
 */
public record AddResult(int added, int removed, long inputBytes, long dedupedBytes,
    long addedBytes, long archiveBytes, int unreadable) {
  /** True if the update changed the archive. */
  public boolean changed() {
    return added + removed > 0;
  }
}
