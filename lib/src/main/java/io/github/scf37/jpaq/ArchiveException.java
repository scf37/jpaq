package io.github.scf37.jpaq;

import java.io.IOException;

/**
 * An archive could not be read or written: it is damaged, the password is wrong, it is not an
 * archive, or the operation does not fit it (for example adding in one format to an archive of
 * the other).
 */
public class ArchiveException extends IOException {
  private static final long serialVersionUID = 1L;

  public ArchiveException(String message) {
    super(message);
  }

  public ArchiveException(String message, Throwable cause) {
    super(message, cause);
  }
}
