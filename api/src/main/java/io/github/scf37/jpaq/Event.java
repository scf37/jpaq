package io.github.scf37.jpaq;

/**
 * Progress and problems of an operation, delivered to the listener of a {@link ZpaqArchive}.
 * Events carry data only; how to show them is up to the receiver. Sizes are in bytes, fragment
 * numbers index the fragment table of the archive.
 */
public sealed interface Event {
  /** A problem: something was skipped or could not be done, the operation went on. */
  sealed interface Problem extends Event {}

  // ---------------------------------------------------------------------------------------
  // Reading an archive

  /** The archive (or index) is about to be read, up to {@code version} (-1: the latest). */
  record ArchiveReading(String name, long version) implements Event {}

  /** The archive to read does not exist. */
  record ArchiveNotFound(String name) implements Problem {}

  /** A version counted from the end was resolved to this version number. */
  record VersionSelected(long version) implements Event {}

  /** An update that was not completed is ignored. */
  record IncompleteTransaction() implements Event {}

  /** A damaged block of the archive at {@code offset} was skipped. */
  record BlockSkipped(long offset, String reason) implements Problem {}

  /** The archive has been read: {@code bytes} is where the last version read ends. */
  record ArchiveRead(int versions, int files, int fragments, long bytes) implements Event {}

  /** A local file or archive file could not be read, written or found. */
  record FileError(String name, String reason, Throwable cause) implements Problem {}

  /** A diagnostic of the archive structure or of a worker thread, not tied to one file. */
  record Diagnostic(String message) implements Problem {}

  /** Information about the archive structure (unusual blocks and the like). */
  record Note(String message) implements Event {}

  // ---------------------------------------------------------------------------------------
  // Adding

  /** An archive file (or part) is created or appended to at an offset of the archive. */
  record AddTarget(String part, boolean creating, long headerOffset, long precedingBytes)
      implements Event {}

  /** What the update is going to do; {@code date} is decimal YYYYMMDDHHMMSS (UT). */
  record AddPlan(long bytes, int files, String method, int threads, long date)
      implements Event {}

  /** The date of the update (decimal) was moved past the latest version. */
  record DateAdjusted(long from, long to) implements Problem {}

  /** Done so far, and the estimated time to go. */
  record Progress(double percent, int etaSeconds) implements Event {}

  /** A block of fragments {@code first..last} of {@code size} bytes is being compressed. */
  record BlockCompressed(int first, int last, long size, String method) implements Event {}

  /**
   * A local file is added ({@code updated}: replaces a file of the archive) as {@code
   * archiveName}. {@code size} is its size and {@code storedSize} what was not deduplicated;
   * either is -1 if not known.
   */
  record FileAdded(String name, String archiveName, boolean updated, long size, long storedSize)
      implements Event {}

  /** An entry is deleted from the archive. */
  record FileRemoved(String name) implements Event {}

  /** Number of entries added (or changed) and deleted by the update. */
  record AddSummary(int added, int removed) implements Event {}

  /** Sizes at the end of an update in the journaling format. */
  record AddTotals(long headerOffset, long inputBytes, long dedupedBytes, long addedBytes,
      long archiveBytes) implements Event {}

  /** Sizes at the end of an update in the streaming format. */
  record StreamingAddTotals(long headerOffset, long inputBytes, long addedBytes,
      long archiveBytes) implements Event {}

  /** An update that changed nothing was removed from the end of the archive. */
  record ArchiveTruncated(long from, long to) implements Event {}

  /** An archive file that held only an empty update was deleted. */
  record ArchiveDeleted(String name) implements Event {}

  // ---------------------------------------------------------------------------------------
  // Extracting

  /** A file was not extracted: it exists ({@code identical}: and has the same content). */
  record FileSkipped(String name, boolean identical) implements Event {}

  /** Number of files skipped because they exist. */
  record SkippedSummary(int count, boolean identical) implements Event {}

  /** A file of the archive refers to fragments that do not exist; it is skipped. */
  record BadFragments(String name) implements Problem {}

  /** What is going to be extracted. */
  record ExtractPlan(long bytes, int files, int threads) implements Event {}

  /** A file is being extracted (or tested). */
  record FileExtracted(String name) implements Event {}

  /** A block of fragments was decompressed. */
  record BlockExtracted(int first, int last, long size) implements Event {}

  /** Number of segments of streaming format blocks extracted. */
  record StreamingSegments(int count) implements Event {}

  /** A file could not be extracted completely. */
  record ExtractFailed(String name, int fragmentsExtracted, int fragmentsTotal) implements Problem {}

  /** Summary of failed extractions (with the largest block memory and the threads used). */
  record ExtractFailures(int files, int failed, double megabytes, int threads) implements Problem {}

  /** An index was written. */
  record IndexWritten(String name, long bytes) implements Event {}

  /** An archive was copied as it is (encrypted or decrypted on the way). */
  record ArchiveCopied(String source, long sourceBytes, String target, long targetBytes)
      implements Event {}

  /** Repack: size of the data blocks before and after copying the needed ones. */
  record RepackData(long before, long after) implements Event {}

  /** Repack: the new archive was written. */
  record RepackWritten(String source, long sourceBytes, String target, long targetBytes)
      implements Event {}
}
