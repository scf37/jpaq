package io.github.scf37.jpaq;

import java.util.function.UnaryOperator;

/**
 * What to extract (or test, or repack) and where to.
 *
 * @param read the version and entries to extract
 * @param output archive name to output name: the local file (relative names are resolved against
 *     the directory of the archive handle), or the name in the new archive of a repack. Null
 *     keeps the archive names, absolute ones included; returning null skips the entry. See
 *     {@link #under}.
 * @param overwrite replace existing files (identical ones are left as they are, with their date
 *     and attributes set); otherwise existing files are skipped. Repack: replace the target.
 * @param test decompress and verify only, write nothing
 */
public record ExtractOptions(ReadOptions read, UnaryOperator<String> output, boolean overwrite,
    boolean test) {
  /** The latest version of everything, under the archive names; existing files are skipped. */
  public static final ExtractOptions DEFAULT = new ExtractOptions(ReadOptions.LATEST, null, false,
      false);

  public ExtractOptions {
    if (read == null) read = ReadOptions.LATEST;
  }

  /**
   * An output mapping that puts every entry under {@code dir}: a leading '/' or drive letter is
   * dropped, and names with a ".." component are skipped.
   */
  public static UnaryOperator<String> under(String dir) {
    return name -> {
      for (String c : name.split("[/\\\\]")) if (c.equals("..")) return null;
      String n = name;
      if (n.length() > 1 && n.charAt(1) == ':') n = n.substring(2);
      while (n.startsWith("/")) n = n.substring(1);
      return dir.endsWith("/") ? dir + n : dir + "/" + n;
    };
  }

  public ExtractOptions withRead(ReadOptions read) {
    return new ExtractOptions(read, output, overwrite, test);
  }

  public ExtractOptions withOutput(UnaryOperator<String> output) {
    return new ExtractOptions(read, output, overwrite, test);
  }

  public ExtractOptions withOverwrite(boolean overwrite) {
    return new ExtractOptions(read, output, overwrite, test);
  }

  public ExtractOptions withTest(boolean test) {
    return new ExtractOptions(read, output, overwrite, test);
  }
}
