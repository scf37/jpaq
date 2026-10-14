package io.github.scf37.jpaq.cli;

/** Thrown to terminate the program with an exit code (the C++ exit()). */
final class ExitException extends RuntimeException {
  private static final long serialVersionUID = 1L;
  final int code;

  ExitException(int code) {
    super(null, null, false, false);
    this.code = code;
  }
}
