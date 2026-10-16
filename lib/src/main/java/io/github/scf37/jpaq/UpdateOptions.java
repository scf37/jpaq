package io.github.scf37.jpaq;

import java.time.Instant;
import java.util.List;

/**
 * How to update an archive.
 *
 * @param method compression: "0" (store, deduplicate only) .. "5" (best), optionally followed by
 *     the block size 2^n MiB ("16": level 1, 64 MiB blocks), or an advanced method "x..." (see
 *     libzpaq.h). Streaming methods ("s...") are not supported here.
 * @param fragment average fragment size for deduplication, 2^n KiB (default 6: 64 KiB)
 * @param index the index of the archive so far. {@link Format#SEQUENTIAL}: required unless the
 *     archive is new (null). {@link Format#CLASSIC}: optional; with an index each update is
 *     written as a new archive part ({@code "arc???.zpaq"}) and the index is updated in place
 * @param indexOut {@link Format#SEQUENTIAL}: where to write the updated index, a new name (the
 *     old index is not touched, so a failed update leaves it valid); required
 * @param date the date of the update (the version date); null for the current time
 * @param force {@link ArchiveUpdate#addTree}: add files whose date and size did not change too
 * @param noAttributes do not store permissions or Windows attributes
 * @param exclude names (and patterns with {@code *} and {@code ?}) that are neither added nor
 *     deleted by {@link ArchiveUpdate#addTree}
 */
public record UpdateOptions(String method, int fragment, String index, String indexOut,
    Instant date, boolean force, boolean noAttributes, List<String> exclude) {
  /** Method 1, 64 KiB fragments, no index. */
  public static final UpdateOptions DEFAULT =
      new UpdateOptions("1", 6, null, null, null, false, false, List.of());

  public UpdateOptions {
    if (method == null || method.isEmpty()) throw new IllegalArgumentException("method");
    if (method.charAt(0) == 's')
      throw new IllegalArgumentException("streaming methods (s...) are not supported");
    if (fragment < 0 || fragment > 22) throw new IllegalArgumentException("fragment 0..22");
    exclude = List.copyOf(exclude);
  }

  public UpdateOptions withMethod(String method) {
    return new UpdateOptions(method, fragment, index, indexOut, date, force, noAttributes, exclude);
  }

  public UpdateOptions withFragment(int fragment) {
    return new UpdateOptions(method, fragment, index, indexOut, date, force, noAttributes, exclude);
  }

  public UpdateOptions withIndex(String index) {
    return new UpdateOptions(method, fragment, index, indexOut, date, force, noAttributes, exclude);
  }

  public UpdateOptions withIndexOut(String indexOut) {
    return new UpdateOptions(method, fragment, index, indexOut, date, force, noAttributes, exclude);
  }

  public UpdateOptions withDate(Instant date) {
    return new UpdateOptions(method, fragment, index, indexOut, date, force, noAttributes, exclude);
  }

  public UpdateOptions withForce(boolean force) {
    return new UpdateOptions(method, fragment, index, indexOut, date, force, noAttributes, exclude);
  }

  public UpdateOptions withNoAttributes(boolean noAttributes) {
    return new UpdateOptions(method, fragment, index, indexOut, date, force, noAttributes, exclude);
  }

  public UpdateOptions withExclude(String... patterns) {
    return new UpdateOptions(method, fragment, index, indexOut, date, force, noAttributes,
        List.of(patterns));
  }
}
