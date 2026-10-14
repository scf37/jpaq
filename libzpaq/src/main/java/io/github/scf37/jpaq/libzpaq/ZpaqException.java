package io.github.scf37.jpaq.libzpaq;

/**
 * Unrecoverable libzpaq error such as badly formatted compressed input or an invalid
 * configuration. Corresponds to libzpaq::error() in the C++ library.
 */
public class ZpaqException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ZpaqException(String message) {
    super(message);
  }

  public ZpaqException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Throws a {@link ZpaqException} with the given message; never returns normally. */
  static ZpaqException error(String message) {
    return new ZpaqException(message);
  }
}
