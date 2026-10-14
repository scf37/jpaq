package io.github.scf37.jpaq.api;

/** Output about problems; the command line prints it to stderr. */
public sealed interface ErrorLine extends Line {
  @Override
  default Channel channel() {
    return Channel.STDERR;
  }

  /** Problem without a type of its own. */
  record Message(String text) implements ErrorLine {}

  /**
   * A file could not be read, written or found. {@code name} is the file, {@code reason} the
   * system's message ("No such file or directory"), {@code cause} the exception if there was one.
   */
  record FileError(String name, String reason, Throwable cause, String text)
      implements ErrorLine {}

  /** The archive or index to read does not exist. */
  record ArchiveNotFound(String name, String text) implements ErrorLine {}

  /** The command failed; it did not get to finish. */
  record Fatal(String message, Throwable cause, String text) implements ErrorLine {}

  /** A block of the archive could not be used and was skipped. */
  record BlockSkipped(long offset, String reason, String text) implements ErrorLine {}

  /** The date of an update was changed to keep versions in order. */
  record DateAdjusted(long from, long to, String text) implements ErrorLine {}

  /** A file could not be extracted completely. */
  record ExtractFailed(String name, int fragmentsExtracted, int fragmentsTotal, String text)
      implements ErrorLine {}

  /** Summary of failed extractions. */
  record ExtractFailures(int files, int errors, String text) implements ErrorLine {}

  /** A file name in the archive points to fragments that do not exist. */
  record BadFragments(String name, String text) implements ErrorLine {}
}
