package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Jidac;
import io.github.scf37.jpaq.engine.Strs;
import java.util.List;

/**
 * The file list of an archive version (see {@link ZpaqArchive#read}): its entries, versions and
 * fragment table.
 */
public final class ArchiveContents {
  private final Jidac j;
  private final List<ArchiveEntry> entries;
  private final List<ArchiveVersion> versions;

  ArchiveContents(Jidac j) {
    this.j = j;
    this.entries = j.entries();
    this.versions = j.versions();
  }

  /**
   * The entries, in name order. With {@link ReadOptions#versionDigits} every version of every
   * entry, deletions included (date 0).
   */
  public List<ArchiveEntry> entries() {
    return entries;
  }

  /** The versions up to the one read, oldest first. */
  public List<ArchiveVersion> versions() {
    return versions;
  }

  /** Number of fragment IDs; valid IDs are 1 .. fragmentCount() - 1. */
  public int fragmentCount() {
    return j.fragmentCount();
  }

  /** Size of a fragment, -1 if unknown (streaming format). */
  public int fragmentSize(int id) {
    return j.fragmentSize(id);
  }

  /** Where the last version read ends in the archive (parts counted as one). */
  public long end() {
    return j.end();
  }

  /** Compressed size of the data of the versions read, according to their fragment tables. */
  public long dataSize() {
    return j.indexedDataSize();
  }

  /**
   * Compressed size of the data that is in the files read: less than {@link #dataSize} if the
   * file list was read from an index.
   */
  public long storedDataSize() {
    return j.storedDataSize();
  }

  /**
   * True if the local file has the content of the entry (same size and fragment hashes), or
   * neither exists. Directories match if the local one exists.
   *
   * @param localName resolved against the directory of the archive handle
   */
  public boolean sameContent(ArchiveEntry entry, String localName) {
    return j.sameContent(Strs.toBytes(entry.name()), Strs.toBytes(localName));
  }
}
