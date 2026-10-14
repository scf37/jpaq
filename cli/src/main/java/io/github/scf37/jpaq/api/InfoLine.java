package io.github.scf37.jpaq.api;

/**
 * Output that is not a problem. Dates are decimal YYYYMMDDHHMMSS (UTC) as in zpaq, sizes are in
 * bytes and file names are as stored in the archive. Everything a caller might want to react to
 * has its own type; {@link Text} is the rest.
 */
public sealed interface InfoLine extends Line {
  @Override
  default Channel channel() {
    return Channel.STDOUT;
  }

  /** Output without a type of its own. */
  record Text(String text) implements InfoLine {}

  /** Same, printed to stderr by the command line. */
  record StatusText(String text) implements InfoLine {
    @Override
    public Channel channel() {
      return Channel.STDERR;
    }
  }

  /** First line of every command. */
  record Banner(String version, String text) implements InfoLine {}

  /** An archive (or index) is about to be read. {@code untilVersion} is -1 for the latest. */
  record ArchiveReading(String name, long untilVersion, String text) implements InfoLine {}

  /** The archive has been read. */
  record ArchiveRead(int versions, int files, int fragments, double megabytes, String text)
      implements InfoLine {}

  /** Version number chosen by a negative -until. */
  record VersionSelected(long version, String text) implements InfoLine {}

  /** An archive part is created or updated by add, at an offset in the archive. */
  record AddTarget(String part, boolean creating, long headerOffset, long precedingBytes,
      String text) implements InfoLine {}

  /** What add is going to do. */
  record AddPlan(double megabytes, int files, String method, int threads, long date, String text)
      implements InfoLine {}

  /** Percent done and estimated time to go, printed in front of the next line. */
  record Progress(double percent, int etaSeconds, String text) implements InfoLine {}

  /**
   * A file is added ({@code updated} false) or a changed file is added again ({@code updated}
   * true). {@code size} is the size of the file or -1 if not known at this point, {@code
   * storedSize} the size after deduplication or -1. {@code archiveName} differs from {@code name}
   * if the file is renamed.
   */
  record FileAdded(String name, String archiveName, boolean updated, long size, long storedSize,
      String text) implements InfoLine {}

  /** A file is deleted from the archive. */
  record FileRemoved(String name, String text) implements InfoLine {}

  /** A block of fragments {@code first..last} of {@code size} bytes is compressed. */
  record BlockCompressed(int first, int last, long size, String method, String text)
      implements InfoLine {}

  /** Number of files added and removed by an add. */
  record AddSummary(int added, int removed, String text) implements InfoLine {}

  /** Sizes at the end of an add (stderr). */
  record AddResult(long headerOffset, long inputBytes, long dedupedBytes, long addedBytes,
      long archiveBytes, String text) implements InfoLine {
    @Override
    public Channel channel() {
      return Channel.STDERR;
    }
  }

  /** Nothing was added, so the transaction was dropped. */
  record ArchiveTruncated(long from, long to, String text) implements InfoLine {}

  record ArchiveDeleted(String name, String text) implements InfoLine {}

  /** A transaction that was not completed is ignored by readers. */
  record IncompleteTransaction(String text) implements InfoLine {}

  /** extract: what is going to be extracted. */
  record ExtractPlan(double megabytes, int files, int threads, String text) implements InfoLine {}

  /** A file is being extracted. */
  record FileExtracted(String name, String text) implements InfoLine {}

  /** extract: a file was not extracted. */
  record FileSkipped(String name, Reason reason, String text) implements InfoLine {
    public enum Reason { EXISTS, IDENTICAL }
  }

  /** Number of files skipped. */
  record SkippedSummary(int count, boolean identical, String text) implements InfoLine {}

  /** A block of fragments was decompressed. */
  record BlockExtracted(int first, int last, long size, String text) implements InfoLine {}

  /** An index file was written for an archive (extract -index). */
  record IndexWritten(String name, long bytes, String text) implements InfoLine {}

  /**
   * A line of a listing. {@code status} is ' ' for files only in the archive, '+' only external,
   * '-' in the archive but listed with its external counterpart, '=' same, '#' different, '^'
   * duplicate. {@code attributes} is as stored ('u' and unix mode, 'w' and Windows attributes,
   * or 0); {@code fragments} is the fragment list text when asked for.
   */
  record ListEntry(char status, long date, long size, long attributes, String name,
      String fragments, String text) implements InfoLine {}

  /** Totals at the end of a listing. */
  record ListSummary(double megabytesShown, double megabytesTotal, int files, String text)
      implements InfoLine {}

  /** The command is finished (stderr): seconds taken and the exit code. */
  record Finished(int exitCode, double seconds, String text) implements InfoLine {
    @Override
    public Channel channel() {
      return Channel.STDERR;
    }

    public boolean ok() {
      return exitCode == 0;
    }
  }
}
