package io.github.scf37.jpaq.api;

import java.util.List;

/**
 * A zpaq command with its arguments. Archive and index names are given to the {@link Storage}
 * as they are (nothing is appended); {@code *} and {@code ?} in an archive name stand for the
 * number of the part ("arc???.jpaq"). Files to add, and where to extract, are on the local file
 * system, relative to the working directory of the run.
 */
public sealed interface Command {
  /** The archive (the tomes) the command works on. */
  String archive();

  Options options();

  /**
   * Adds files to an archive, or deletes from it files that are no longer there.
   *
   * @param archive archive name or pattern of the parts
   * @param files files and directories to add
   * @param format {@link Format#CLASSIC}: zpaq format, an existing {@code index} is updated in
   *     place (null: no index, one archive file or one part per update); {@link
   *     Format#SEQUENTIAL}: sequential format, every file is written once
   * @param index the index of the archive so far, null for a new archive
   * @param indexOut {@link Format#SEQUENTIAL}: where to write the updated index (a new name; the
   *     old index is not touched), required
   */
  record Add(String archive, List<String> files, Options options, Format format, String index,
      String indexOut) implements Command {
    public Add {
      files = List.copyOf(files);
      if (format == null) format = Format.CLASSIC;
    }
  }

  /**
   * Extracts files.
   *
   * @param files files and directories to extract, empty for all
   * @param to where to extract to ("-to"): one name per entry of {@code files} or one directory
   *     for all; empty for the original names
   * @param index list of files taken from this index, so that only the tomes with the wanted data
   *     are read (any format); null to read the whole archive
   * @param test only verify the data, write no files
   */
  record Extract(String archive, List<String> files, List<String> to, Options options,
      String index, boolean test) implements Command {
    public Extract {
      files = List.copyOf(files);
      to = List.copyOf(to);
    }
  }

  /**
   * Lists the archive, or compares it with files on disk.
   *
   * @param files external files to compare with, empty to just list
   * @param index list taken from this index instead of from the archive
   */
  record ListArchive(String archive, List<String> files, Options options, String index)
      implements Command {
    public ListArchive {
      files = List.copyOf(files);
    }
  }

  /**
   * Creates an index from the tomes of an archive (zpaq "extract -index"), which then can be used
   * instead of reading the archive.
   *
   * @param indexOut name of the index to write
   */
  record RebuildIndex(String archive, String indexOut, Options options) implements Command {}
}
