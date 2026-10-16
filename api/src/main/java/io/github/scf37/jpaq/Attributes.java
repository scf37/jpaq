package io.github.scf37.jpaq;

import java.util.OptionalInt;

/**
 * File attributes as zpaq stores them: unix permission bits, Windows attributes, or none.
 *
 * @param raw the stored value: {@code 'u' + (mode << 8)}, {@code 'w' + (attributes << 8)}, or 0
 */
public record Attributes(long raw) {
  /** No attributes; extraction leaves the defaults of the file system. */
  public static final Attributes NONE = new Attributes(0);

  /** Unix mode bits (file type and permissions, as in {@code st_mode}). */
  public static Attributes unix(int mode) {
    return new Attributes('u' + ((mode & 0xFFFFFFFFL) << 8));
  }

  /** Windows attributes (FILE_ATTRIBUTE_READONLY = 1, HIDDEN = 2, SYSTEM = 4, ARCHIVE = 0x20...). */
  public static Attributes windows(int attributes) {
    return new Attributes('w' + ((attributes & 0xFFFFFFFFL) << 8));
  }

  /** The unix mode, if these are unix attributes. */
  public OptionalInt unixMode() {
    return (raw & 255) == 'u' ? OptionalInt.of((int) (raw >>> 8)) : OptionalInt.empty();
  }

  /** The Windows attributes, if these are Windows attributes. */
  public OptionalInt windowsAttributes() {
    return (raw & 255) == 'w' ? OptionalInt.of((int) (raw >>> 8)) : OptionalInt.empty();
  }
}
