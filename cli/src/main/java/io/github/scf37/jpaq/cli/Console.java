package io.github.scf37.jpaq.cli;

import static io.github.scf37.jpaq.cli.Text.bytes;
import static io.github.scf37.jpaq.cli.Text.f;

import io.github.scf37.jpaq.Event;
import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * Prints what a command does as zpaq 7.15 does: progress and results to standard output, problems
 * to standard error (after flushing standard output). The streams take byte strings (ISO-8859-1).
 */
final class Console implements Consumer<Event> {
  private final PrintStream out;
  private final PrintStream err;
  int summary;              // -summary: > 0 brief progress only
  private boolean failures;  // the header of the failed extractions was printed

  Console(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  synchronized void print(String s) {
    out.print(s);
  }

  synchronized void eprint(String s) {
    out.flush();
    err.print(s);
    err.flush();
  }

  synchronized void flush() {
    out.flush();
  }

  private static double mb(long bytes) {
    return bytes / 1000000.0;
  }

  @Override
  public synchronized void accept(Event event) {
    final boolean details = summary <= 0;
    switch (event) {
      // Reading
      case Event.ArchiveReading e -> {
        print(bytes(e.name()) + (e.version() == -1 ? ": " : f(" -until %1.0f: ", e.version() + 0.0)));
        flush();
      }
      case Event.ArchiveNotFound e -> eprint(bytes(e.name()) + " not found.\n");
      case Event.VersionSelected e -> print(f("Version %1.0f\n", e.version() + .0));
      case Event.IncompleteTransaction e -> print("Incomplete transaction ignored\n");
      case Event.BlockSkipped e ->
          eprint(f("Skipping block at %1.0f: %s\n", (double) e.offset(), bytes(e.reason())));
      case Event.ArchiveRead e -> print(f("%d versions, %d files, %d fragments, %1.6f MB\n",
          e.versions(), e.files(), e.fragments(), mb(e.bytes())));
      case Event.FileError e -> eprint(bytes(e.name()) + ": " + bytes(e.reason()) + "\n");
      case Event.Diagnostic e -> eprint(bytes(e.message()));
      case Event.Note e -> print(bytes(e.message()));

      // Adding
      case Event.AddTarget e -> print((e.creating() ? "Creating " : "Updating ") + bytes(e.part())
          + f(" at offset %1.0f + %1.0f\n", (double) e.headerOffset(), (double) e.precedingBytes()));
      case Event.AddPlan e -> print(f("Adding %1.6f MB in %d files -method %s -threads %d at %s.\n",
          mb(e.bytes()), e.files(), bytes(e.method()), e.threads(), Text.dateToString(e.date())));
      case Event.DateAdjusted e -> eprint(f("Warning: adjusting date from %s to %s\n",
          Text.dateToString(e.from()), Text.dateToString(e.to())));
      case Event.Progress e -> {
        final int eta = e.etaSeconds();
        print(f("%5.2f%% %d:%02d:%02d %s", e.percent(), eta / 3600, eta / 60 % 60, eta % 60,
            summary > 0 ? "\r" : ""));
        if (summary > 0) flush();
      }
      case Event.BlockCompressed e -> {
        if (details) print(f("[%d..%d] %d -method %s\n", e.first(), e.last(), e.size(), bytes(e.method())));
      }
      case Event.FileAdded e -> {
        if (!details) return;
        StringBuilder t = new StringBuilder(e.updated() ? "# " : "+ ").append(bytes(e.name()));
        if (!e.archiveName().equals(e.name())) t.append(" -> ").append(bytes(e.archiveName()));
        if (e.size() >= 0) t.append(f(" %1.0f", e.size() + 0.0));
        if (e.storedSize() >= 0 && e.storedSize() != e.size())
          t.append(f(" -> %1.0f", e.storedSize() + 0.0));
        print(t.append('\n').toString());
      }
      case Event.FileRemoved e -> {
        if (details) print("- " + bytes(e.name()) + "\n");
      }
      case Event.AddSummary e -> print(f("%d +added, %d -removed.\n", e.added(), e.removed()));
      case Event.AddTotals e -> eprint(f("\n%1.6f + (%1.6f -> %1.6f -> %1.6f) = %1.6f MB\n",
          mb(e.headerOffset()), mb(e.inputBytes()), mb(e.dedupedBytes()), mb(e.addedBytes()),
          mb(e.archiveBytes())));
      case Event.StreamingAddTotals e -> print(f("%1.0f + (%1.0f -> %1.0f) = %1.0f\n",
          (double) e.headerOffset(), (double) e.inputBytes(), (double) e.addedBytes(),
          (double) e.archiveBytes()));
      case Event.ArchiveTruncated e -> print(f("truncating archive from %1.0f to %1.0f\n",
          (double) e.from(), (double) e.to()));
      case Event.ArchiveDeleted e -> print("deleted " + bytes(e.name()) + "\n");

      // Extracting
      case Event.FileSkipped e -> {
        if (details) print((e.identical() ? "= " : "? ") + bytes(e.name()) + "\n");
      }
      case Event.SkippedSummary e -> print(e.identical()
          ? e.count() + " =identical files skipped.\n"
          : e.count() + " ?existing files skipped (-force overwrites).\n");
      case Event.BadFragments e -> eprint(bytes(e.name()) + ": bad frag IDs, skipping...\n");
      case Event.ExtractPlan e -> print(f("Extracting %1.6f MB in %d files -threads %d\n",
          mb(e.bytes()), e.files(), e.threads()));
      case Event.FileExtracted e -> {
        if (details) print("> " + bytes(e.name()) + "\n");
      }
      case Event.BlockExtracted e -> {
        if (details) print(f("[%d..%d] -> %1.0f\n", e.first(), e.last(), e.size() + 0.0));
      }
      case Event.StreamingSegments e -> print(f("%d streaming segments extracted\n", e.count()));
      case Event.ExtractFailed e -> {
        if (!failures) eprint("\nFailed (extracted/total fragments, file):\n");
        failures = true;
        eprint(e.fragmentsExtracted() + "/" + e.fragmentsTotal() + " " + bytes(e.name()) + "\n");
      }
      case Event.ExtractFailures e -> eprint(f(
          "\nExtracted %d of %d files OK (%d errors) using %1.3f MB x %d threads\n",
          e.files() - e.failed(), e.files(), e.failed(), e.megabytes(), e.threads()));
      case Event.IndexWritten e -> print(bytes(e.name()) + f(" -> %1.0f\n", e.bytes() + .0));
      case Event.ArchiveCopied e -> print(bytes(e.source()) + f(" %1.0f ", e.sourceBytes() + .0)
          + bytes(e.target()) + f(" -> %1.0f\n", e.targetBytes() + .0));
      case Event.RepackData e -> print(f("Data %1.0f -> %1.0f\n", e.before() + .0, e.after() + .0));
      case Event.RepackWritten e -> print(bytes(e.source()) + f(" %1.0f -> ", e.sourceBytes() + .0)
          + bytes(e.target()) + f(" %1.0f\n", e.targetBytes() + .0));
    }
  }
}
