package io.github.scf37.jpaq;

/**
 * Progress and problems of an operation, delivered to the {@linkplain ZpaqArchive.Option#listener
 * listener} of a {@link ZpaqArchive}. Events carry data only; how to show them is up to the
 * receiver. Sizes are in bytes, fragment numbers index the fragment table of the archive.
 *
 * <p>Every kind of event is an interface with accessors and a static {@code of} factory, so that
 * events can be matched exhaustively ({@code switch (event) { case Event.FileAdded e -> ... }})
 * and new data can be added to them later without breaking callers.
 */
public sealed interface Event {
  /** A problem: something was skipped or could not be done, the operation went on. */
  sealed interface Problem extends Event {}

  // ---------------------------------------------------------------------------------------
  // Reading an archive

  /** The archive (or index) is about to be read, up to {@code version} (-1: the latest). */
  sealed interface ArchiveReading extends Event permits EventRecords.ArchiveReading {
    String name();
    long version();

    static ArchiveReading of(String name, long version) {
      return new EventRecords.ArchiveReading(name, version);
    }
  }

  /** The archive to read does not exist. */
  sealed interface ArchiveNotFound extends Problem permits EventRecords.ArchiveNotFound {
    String name();

    static ArchiveNotFound of(String name) {
      return new EventRecords.ArchiveNotFound(name);
    }
  }

  /** A version counted from the end was resolved to this version number. */
  sealed interface VersionSelected extends Event permits EventRecords.VersionSelected {
    long version();

    static VersionSelected of(long version) {
      return new EventRecords.VersionSelected(version);
    }
  }

  /** An update that was not completed is ignored. */
  sealed interface IncompleteTransaction extends Event permits EventRecords.IncompleteTransaction {
    static IncompleteTransaction of() {
      return new EventRecords.IncompleteTransaction();
    }
  }

  /** A damaged block of the archive at {@code offset} was skipped. */
  sealed interface BlockSkipped extends Problem permits EventRecords.BlockSkipped {
    long offset();
    String reason();

    static BlockSkipped of(long offset, String reason) {
      return new EventRecords.BlockSkipped(offset, reason);
    }
  }

  /** The archive has been read: {@code bytes} is where the last version read ends. */
  sealed interface ArchiveRead extends Event permits EventRecords.ArchiveRead {
    int versions();
    int files();
    int fragments();
    long bytes();

    static ArchiveRead of(int versions, int files, int fragments, long bytes) {
      return new EventRecords.ArchiveRead(versions, files, fragments, bytes);
    }
  }

  /** A local file or archive file could not be read, written or found ({@code cause}: null if
   * there is no exception). */
  sealed interface FileError extends Problem permits EventRecords.FileError {
    String name();
    String reason();
    Throwable cause();

    static FileError of(String name, String reason, Throwable cause) {
      return new EventRecords.FileError(name, reason, cause);
    }
  }

  /** A diagnostic of the archive structure or of a worker thread, not tied to one file. */
  sealed interface Diagnostic extends Problem permits EventRecords.Diagnostic {
    String message();

    static Diagnostic of(String message) {
      return new EventRecords.Diagnostic(message);
    }
  }

  /** Information about the archive structure (unusual blocks and the like). */
  sealed interface Note extends Event permits EventRecords.Note {
    String message();

    static Note of(String message) {
      return new EventRecords.Note(message);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Adding

  /** An archive file (or part) is created or appended to at an offset of the archive. */
  sealed interface AddTarget extends Event permits EventRecords.AddTarget {
    String part();
    boolean creating();
    long headerOffset();
    long precedingBytes();

    static AddTarget of(String part, boolean creating, long headerOffset, long precedingBytes) {
      return new EventRecords.AddTarget(part, creating, headerOffset, precedingBytes);
    }
  }

  /** What the update is going to do; {@code date} is decimal YYYYMMDDHHMMSS (UT). */
  sealed interface AddPlan extends Event permits EventRecords.AddPlan {
    long bytes();
    int files();
    String method();
    int threads();
    long date();

    static AddPlan of(long bytes, int files, String method, int threads, long date) {
      return new EventRecords.AddPlan(bytes, files, method, threads, date);
    }
  }

  /** The date of the update (decimal) was moved past the latest version. */
  sealed interface DateAdjusted extends Problem permits EventRecords.DateAdjusted {
    long from();
    long to();

    static DateAdjusted of(long from, long to) {
      return new EventRecords.DateAdjusted(from, to);
    }
  }

  /** Done so far, and the estimated time to go. */
  sealed interface Progress extends Event permits EventRecords.Progress {
    double percent();
    int etaSeconds();

    static Progress of(double percent, int etaSeconds) {
      return new EventRecords.Progress(percent, etaSeconds);
    }
  }

  /** A block of fragments {@code first..last} of {@code size} bytes is being compressed. */
  sealed interface BlockCompressed extends Event permits EventRecords.BlockCompressed {
    int first();
    int last();
    long size();
    String method();

    static BlockCompressed of(int first, int last, long size, String method) {
      return new EventRecords.BlockCompressed(first, last, size, method);
    }
  }

  /** A local file is added ({@code updated}: replaces a file of the archive) as {@code
   * archiveName}. {@code size} is its size and {@code storedSize} what was not deduplicated;
   * either is -1 if not known. */
  sealed interface FileAdded extends Event permits EventRecords.FileAdded {
    String name();
    String archiveName();
    boolean updated();
    long size();
    long storedSize();

    static FileAdded of(String name, String archiveName, boolean updated, long size,
        long storedSize) {
      return new EventRecords.FileAdded(name, archiveName, updated, size, storedSize);
    }
  }

  /** An entry is deleted from the archive. */
  sealed interface FileRemoved extends Event permits EventRecords.FileRemoved {
    String name();

    static FileRemoved of(String name) {
      return new EventRecords.FileRemoved(name);
    }
  }

  /** Number of entries added (or changed) and deleted by the update. */
  sealed interface AddSummary extends Event permits EventRecords.AddSummary {
    int added();
    int removed();

    static AddSummary of(int added, int removed) {
      return new EventRecords.AddSummary(added, removed);
    }
  }

  /** Sizes at the end of an update. {@code streaming}: the streaming format, which does not
   * deduplicate ({@code dedupedBytes} is {@code inputBytes}). */
  sealed interface AddTotals extends Event permits EventRecords.AddTotals {
    boolean streaming();
    long headerOffset();
    long inputBytes();
    long dedupedBytes();
    long addedBytes();
    long archiveBytes();

    static AddTotals of(boolean streaming, long headerOffset, long inputBytes, long dedupedBytes,
        long addedBytes, long archiveBytes) {
      return new EventRecords.AddTotals(streaming, headerOffset, inputBytes, dedupedBytes,
          addedBytes, archiveBytes);
    }
  }

  /** An update that changed nothing was removed from the end of the archive. */
  sealed interface ArchiveTruncated extends Event permits EventRecords.ArchiveTruncated {
    long from();
    long to();

    static ArchiveTruncated of(long from, long to) {
      return new EventRecords.ArchiveTruncated(from, to);
    }
  }

  /** An archive file that held only an empty update was deleted. */
  sealed interface ArchiveDeleted extends Event permits EventRecords.ArchiveDeleted {
    String name();

    static ArchiveDeleted of(String name) {
      return new EventRecords.ArchiveDeleted(name);
    }
  }

  // ---------------------------------------------------------------------------------------
  // Extracting

  /** A file was not extracted: it exists ({@code identical}: and has the same content). */
  sealed interface FileSkipped extends Event permits EventRecords.FileSkipped {
    String name();
    boolean identical();

    static FileSkipped of(String name, boolean identical) {
      return new EventRecords.FileSkipped(name, identical);
    }
  }

  /** Number of files skipped because they exist. */
  sealed interface SkippedSummary extends Event permits EventRecords.SkippedSummary {
    int count();
    boolean identical();

    static SkippedSummary of(int count, boolean identical) {
      return new EventRecords.SkippedSummary(count, identical);
    }
  }

  /** A file of the archive refers to fragments that do not exist; it is skipped. */
  sealed interface BadFragments extends Problem permits EventRecords.BadFragments {
    String name();

    static BadFragments of(String name) {
      return new EventRecords.BadFragments(name);
    }
  }

  /** What is going to be extracted. */
  sealed interface ExtractPlan extends Event permits EventRecords.ExtractPlan {
    long bytes();
    int files();
    int threads();

    static ExtractPlan of(long bytes, int files, int threads) {
      return new EventRecords.ExtractPlan(bytes, files, threads);
    }
  }

  /** A file is being extracted (or tested). */
  sealed interface FileExtracted extends Event permits EventRecords.FileExtracted {
    String name();

    static FileExtracted of(String name) {
      return new EventRecords.FileExtracted(name);
    }
  }

  /** A block of fragments was decompressed. */
  sealed interface BlockExtracted extends Event permits EventRecords.BlockExtracted {
    int first();
    int last();
    long size();

    static BlockExtracted of(int first, int last, long size) {
      return new EventRecords.BlockExtracted(first, last, size);
    }
  }

  /** Number of segments of streaming format blocks extracted. */
  sealed interface StreamingSegments extends Event permits EventRecords.StreamingSegments {
    int count();

    static StreamingSegments of(int count) {
      return new EventRecords.StreamingSegments(count);
    }
  }

  /** A file could not be extracted completely. */
  sealed interface ExtractFailed extends Problem permits EventRecords.ExtractFailed {
    String name();
    int fragmentsExtracted();
    int fragmentsTotal();

    static ExtractFailed of(String name, int fragmentsExtracted, int fragmentsTotal) {
      return new EventRecords.ExtractFailed(name, fragmentsExtracted, fragmentsTotal);
    }
  }

  /** Summary of failed extractions (with the largest block memory and the threads used). */
  sealed interface ExtractFailures extends Problem permits EventRecords.ExtractFailures {
    int files();
    int failed();
    double megabytes();
    int threads();

    static ExtractFailures of(int files, int failed, double megabytes, int threads) {
      return new EventRecords.ExtractFailures(files, failed, megabytes, threads);
    }
  }

  /** An index was written. */
  sealed interface IndexWritten extends Event permits EventRecords.IndexWritten {
    String name();
    long bytes();

    static IndexWritten of(String name, long bytes) {
      return new EventRecords.IndexWritten(name, bytes);
    }
  }

  /** An archive was copied as it is (encrypted or decrypted on the way). */
  sealed interface ArchiveCopied extends Event permits EventRecords.ArchiveCopied {
    String source();
    long sourceBytes();
    String target();
    long targetBytes();

    static ArchiveCopied of(String source, long sourceBytes, String target, long targetBytes) {
      return new EventRecords.ArchiveCopied(source, sourceBytes, target, targetBytes);
    }
  }

  /** Repack: size of the data blocks before and after copying the needed ones. */
  sealed interface RepackData extends Event permits EventRecords.RepackData {
    long before();
    long after();

    static RepackData of(long before, long after) {
      return new EventRecords.RepackData(before, after);
    }
  }

  /** Repack: the new archive was written. */
  sealed interface RepackWritten extends Event permits EventRecords.RepackWritten {
    String source();
    long sourceBytes();
    String target();
    long targetBytes();

    static RepackWritten of(String source, long sourceBytes, String target, long targetBytes) {
      return new EventRecords.RepackWritten(source, sourceBytes, target, targetBytes);
    }
  }
}

/** The implementations of the events. */
final class EventRecords {
  private EventRecords() {}

  record ArchiveReading(String name, long version) implements Event.ArchiveReading {}
  record ArchiveNotFound(String name) implements Event.ArchiveNotFound {}
  record VersionSelected(long version) implements Event.VersionSelected {}
  record IncompleteTransaction() implements Event.IncompleteTransaction {}
  record BlockSkipped(long offset, String reason) implements Event.BlockSkipped {}
  record ArchiveRead(int versions, int files, int fragments, long bytes)
      implements Event.ArchiveRead {}
  record FileError(String name, String reason, Throwable cause) implements Event.FileError {}
  record Diagnostic(String message) implements Event.Diagnostic {}
  record Note(String message) implements Event.Note {}
  record AddTarget(String part, boolean creating, long headerOffset, long precedingBytes)
      implements Event.AddTarget {}
  record AddPlan(long bytes, int files, String method, int threads, long date)
      implements Event.AddPlan {}
  record DateAdjusted(long from, long to) implements Event.DateAdjusted {}
  record Progress(double percent, int etaSeconds) implements Event.Progress {}
  record BlockCompressed(int first, int last, long size, String method)
      implements Event.BlockCompressed {}
  record FileAdded(String name, String archiveName, boolean updated, long size, long storedSize)
      implements Event.FileAdded {}
  record FileRemoved(String name) implements Event.FileRemoved {}
  record AddSummary(int added, int removed) implements Event.AddSummary {}
  record AddTotals(boolean streaming, long headerOffset, long inputBytes, long dedupedBytes,
      long addedBytes, long archiveBytes)
      implements Event.AddTotals {}
  record ArchiveTruncated(long from, long to) implements Event.ArchiveTruncated {}
  record ArchiveDeleted(String name) implements Event.ArchiveDeleted {}
  record FileSkipped(String name, boolean identical) implements Event.FileSkipped {}
  record SkippedSummary(int count, boolean identical) implements Event.SkippedSummary {}
  record BadFragments(String name) implements Event.BadFragments {}
  record ExtractPlan(long bytes, int files, int threads) implements Event.ExtractPlan {}
  record FileExtracted(String name) implements Event.FileExtracted {}
  record BlockExtracted(int first, int last, long size) implements Event.BlockExtracted {}
  record StreamingSegments(int count) implements Event.StreamingSegments {}
  record ExtractFailed(String name, int fragmentsExtracted, int fragmentsTotal)
      implements Event.ExtractFailed {}
  record ExtractFailures(int files, int failed, double megabytes, int threads)
      implements Event.ExtractFailures {}
  record IndexWritten(String name, long bytes) implements Event.IndexWritten {}
  record ArchiveCopied(String source, long sourceBytes, String target, long targetBytes)
      implements Event.ArchiveCopied {}
  record RepackData(long before, long after) implements Event.RepackData {}
  record RepackWritten(String source, long sourceBytes, String target, long targetBytes)
      implements Event.RepackWritten {}
}
