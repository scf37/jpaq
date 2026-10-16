package io.github.scf37.jpaq;

import java.util.List;

/**
 * What to extract and how.
 *
 * @param include names (and patterns with {@code *} and {@code ?}) to extract; a directory name
 *     includes its contents; empty for everything
 * @param exclude names and patterns not to extract
 * @param overwrite replace existing files (identical ones are left alone); otherwise existing
 *     files are skipped
 * @param noAttributes do not restore permissions or Windows attributes
 * @param read the version to extract and where to read the file list from
 */
public record ExtractOptions(List<String> include, List<String> exclude, boolean overwrite,
    boolean noAttributes, ReadOptions read) {
  /** Everything of the latest version; existing files are skipped. */
  public static final ExtractOptions DEFAULT =
      new ExtractOptions(List.of(), List.of(), false, false, ReadOptions.LATEST);

  public ExtractOptions {
    include = List.copyOf(include);
    exclude = List.copyOf(exclude);
    if (read == null) read = ReadOptions.LATEST;
  }

  public ExtractOptions withInclude(String... names) {
    return new ExtractOptions(List.of(names), exclude, overwrite, noAttributes, read);
  }

  public ExtractOptions withExclude(String... names) {
    return new ExtractOptions(include, List.of(names), overwrite, noAttributes, read);
  }

  public ExtractOptions withOverwrite(boolean overwrite) {
    return new ExtractOptions(include, exclude, overwrite, noAttributes, read);
  }

  public ExtractOptions withNoAttributes(boolean noAttributes) {
    return new ExtractOptions(include, exclude, overwrite, noAttributes, read);
  }

  public ExtractOptions withRead(ReadOptions read) {
    return new ExtractOptions(include, exclude, overwrite, noAttributes, read);
  }
}
