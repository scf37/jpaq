package io.github.scf37.jpaq;

import java.time.Instant;

/**
 * A local file or directory as an update sees it.
 *
 * @param name '/' separated; directories end with '/'
 * @param date modification date, decimal YYYYMMDDHHMMSS (UT)
 * @param size size in bytes (0 for directories)
 */
public record LocalFile(String name, long date, long size, Attributes attributes) {
  public boolean isDirectory() {
    return name.endsWith("/");
  }

  public Instant lastModified() {
    return Dates.instant(date);
  }
}
