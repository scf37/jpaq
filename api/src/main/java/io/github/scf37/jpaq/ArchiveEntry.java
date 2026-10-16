package io.github.scf37.jpaq;

import java.time.Instant;

/**
 * A file or directory of an archive version.
 *
 * @param name '/' separated; directories end with '/'
 * @param date modification date as stored, decimal YYYYMMDDHHMMSS (UT); 0 for a deletion
 * @param size size in bytes, -1 if unknown (streaming format)
 * @param attributes permissions or Windows attributes
 * @param fragments the fragments of the content, in order (deduplicated content shares them)
 */
public record ArchiveEntry(String name, long date, long size, Attributes attributes,
    int[] fragments) {
  public boolean isDirectory() {
    return name.endsWith("/");
  }

  /** The modification date. */
  public Instant lastModified() {
    return Dates.instant(date);
  }
}
