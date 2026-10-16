package io.github.scf37.jpaq;

import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * An update: which local files, how they are named in the archive, and how to store them.
 *
 * <p>The update adds the local {@code files} (directories with their contents) that are new or
 * whose date, size or attributes changed, and deletes the entries in its {@code scope} whose
 * local file is gone. Local names are '/' separated, as given in {@code files} plus the names of
 * the directory contents, relative to the directory of the archive handle.
 *
 * @param files local files and directories to add
 * @param archiveName local name to name in the archive; null keeps the local name
 * @param prune local names not looked at (a directory is not entered); null for none
 * @param accept local names to add, of those looked at; null for all
 * @param scope archive names the update is about: of these, those whose local file was not
 *     found are deleted. Null for the names of {@code files} and everything under them.
 * @param method compression: "0" (store, deduplicate only) .. "5" (best), optionally followed by
 *     the block size 2^n MiB ("16": level 1, 64 MiB blocks), "x..." (see libzpaq.h), "s..." for
 *     the streaming format (one block per file, no deduplication), "i..." to write the file list
 *     only (an index of local files)
 * @param fragment average fragment size for deduplication, 2^n KiB
 * @param force add files whose date and size did not change too
 * @param noAttributes do not store permissions or Windows attributes
 * @param index the index of the archive so far. {@link Format#CLASSIC}: optional; with an index
 *     each update is a new part ({@code "arc???.zpaq"}) and the index is updated in place.
 *     {@link Format#SEQUENTIAL}: required unless the archive is new.
 * @param indexOut {@link Format#SEQUENTIAL}: the updated index, a new name (the old index is not
 *     touched); required
 * @param date the date of the update; null for the current time
 * @param rollbackVersion classic format: remove the updates after this version first (0: all;
 *     negative counts back from the latest); null for none
 * @param rollbackDate classic format: remove the updates made after this time first; null for none
 */
public record AddOptions(List<String> files, UnaryOperator<String> archiveName,
    Predicate<String> prune, Predicate<String> accept, Predicate<String> scope, String method,
    int fragment, boolean force, boolean noAttributes, String index, String indexOut,
    Instant date, Long rollbackVersion, Instant rollbackDate) {
  public AddOptions {
    files = List.copyOf(files);
    if (method == null || method.isEmpty()) method = "1";
    if (rollbackVersion != null && rollbackDate != null)
      throw new IllegalArgumentException("both a rollback version and date");
  }

  /** Adds {@code files} under their own names, method 1. */
  public static AddOptions of(String... files) {
    return new AddOptions(List.of(files), null, null, null, null, "1", 6, false, false, null, null,
        null, null, null);
  }

  public AddOptions withArchiveName(UnaryOperator<String> archiveName) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withPrune(Predicate<String> prune) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withAccept(Predicate<String> accept) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withScope(Predicate<String> scope) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withMethod(String method) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withFragment(int fragment) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withForce(boolean force) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withNoAttributes(boolean noAttributes) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withIndex(String index) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withIndexOut(String indexOut) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withDate(Instant date) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, rollbackVersion, rollbackDate);
  }

  public AddOptions withRollbackVersion(Long version) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, date, version, null);
  }

  public AddOptions withRollbackDate(Instant date) {
    return new AddOptions(files, archiveName, prune, accept, scope, method, fragment, force,
        noAttributes, index, indexOut, this.date, null, date);
  }
}
