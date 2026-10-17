package io.github.scf37.jpaq;

import io.github.scf37.jpaq.ZpaqArchive.Attributes;
import java.time.Instant;
import java.util.List;

/**
 * The file list of an archive version (see {@link ZpaqArchive#read}): its entries, versions and
 * fragment table. A snapshot: it does not change when the archive does.
 */
public sealed interface ArchiveContents permits ArchiveImpl.Contents {
  /**
   * The entries, in name order. With {@link ZpaqArchive.ArchiveSelector#allVersions} every
   * version of every entry, deletions included (date 0).
   */
  List<Entry> entries();

  /** The versions up to the one read, oldest first. */
  List<Version> versions();

  /** Number of fragment IDs; valid IDs are 1 .. fragmentCount() - 1. */
  int fragmentCount();

  /** Size of a fragment, -1 if unknown (streaming format). */
  int fragmentSize(int id);

  /** Where the last version read ends in the archive (parts counted as one). */
  long end();

  /** Compressed size of the data of the versions read, according to their fragment tables. */
  long dataSize();

  /**
   * Compressed size of the data that is in the files read: less than {@link #dataSize} if the
   * file list was read from an index.
   */
  long storedDataSize();

  /**
   * True if the local file has the content of the entry (same size and fragment hashes), or
   * neither exists. Directories match if the local one exists.
   *
   * @param localName resolved against the directory of the archive handle
   */
  boolean sameContent(Entry entry, String localName);

  /** A file or directory of an archive version. */
  sealed interface Entry permits ArchiveImpl.ContentsEntry {
    /** '/' separated; directories end with '/'. */
    String name();

    /** Modification date as stored, decimal YYYYMMDDHHMMSS (UT); 0 for a deletion. */
    long date();

    /** Size in bytes, -1 if unknown (streaming format). */
    long size();

    Attributes attributes();

    /** The fragments of the content, in order (deduplicated content shares them); a copy. */
    int[] fragments();

    default boolean isDirectory() {
      return name().endsWith("/");
    }

    /** True for the entry of a deletion (only listed with every version). */
    default boolean isDeleted() {
      return date() == 0;
    }

    /** The modification date. */
    default Instant lastModified() {
      return ArchiveImpl.instant(date());
    }
  }

  /** A version of an archive: the result of one update. */
  sealed interface Version permits ArchiveImpl.ContentsVersion {
    /** 1 for the first update. */
    int number();

    /** Its date, decimal YYYYMMDDHHMMSS (UT). */
    long date();

    /** Entries added or changed. */
    int updates();

    /** Entries deleted. */
    int deletes();

    /** Where it starts in the archive (counting the parts as one). */
    long offset();

    /** The first fragment it added. */
    int firstFragment();

    default Instant time() {
      return ArchiveImpl.instant(date());
    }
  }
}
