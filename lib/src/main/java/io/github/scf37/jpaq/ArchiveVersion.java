package io.github.scf37.jpaq;

import java.time.Instant;

/**
 * A version of an archive: the result of one update.
 *
 * @param number 1 for the first update
 * @param date when the update was made (the date given to it)
 * @param updates number of files and directories added or changed
 * @param deletes number of entries deleted
 */
public record ArchiveVersion(int number, Instant date, int updates, int deletes) {}
