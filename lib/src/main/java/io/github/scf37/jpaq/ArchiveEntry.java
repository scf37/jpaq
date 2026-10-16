package io.github.scf37.jpaq;

import java.time.Instant;

/**
 * A file or directory in an archive version.
 *
 * @param name the name as stored: '/' separated, directories end with '/'
 * @param size size in bytes, -1 if unknown (streaming format archives)
 * @param lastModified modification time (whole seconds)
 * @param attributes permissions or Windows attributes
 */
public record ArchiveEntry(String name, long size, Instant lastModified, Attributes attributes) {
  public boolean isDirectory() {
    return name.endsWith("/");
  }
}
