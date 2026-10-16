package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Dates;
import io.github.scf37.jpaq.engine.EntryReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A version of an archive: its files and directories, and their contents as streams. The file
 * list is read when the reader is created; contents are read and verified (SHA-1 of every
 * fragment) when a stream is read. Holds the archive open until closed. Thread safe; reads of
 * contents are serialized.
 */
public final class ArchiveReader implements Closeable {
  /** Receives the files of an archive with their contents; see {@link #forEach}. */
  @FunctionalInterface
  public interface ContentVisitor {
    /** {@code content} is valid until this method returns. */
    void visit(ArchiveEntry entry, InputStream content) throws IOException;
  }

  private final EntryReader reader;
  private final List<ArchiveEntry> entries = new ArrayList<>();
  private final Map<String, ArchiveEntry> byName = new HashMap<>();
  private final Map<String, Integer> firstFragment = new HashMap<>();
  private final List<ArchiveVersion> versions = new ArrayList<>();
  private final List<ErrorLine> problems;

  ArchiveReader(EntryReader reader, List<ErrorLine> problems) {
    this.reader = reader;
    this.problems = List.copyOf(problems);
    for (EntryReader.Info i : reader.files()) {
      ArchiveEntry e = new ArchiveEntry(Run.text(i.name()), i.size(), instant(i.date()),
          new Attributes(i.attr()));
      entries.add(e);
      byName.put(e.name(), e);
      firstFragment.put(e.name(), i.firstFragment());
    }
    for (EntryReader.VersionInfo v : reader.versions())
      versions.add(new ArchiveVersion(v.number(), instant(v.date()), v.updates(), v.deletes()));
  }

  private static Instant instant(long decimalDate) {
    return Instant.ofEpochSecond(Dates.unixTime(decimalDate));
  }

  /** The versions of the archive up to the one read, oldest first. */
  public List<ArchiveVersion> versions() {
    return List.copyOf(versions);
  }

  /** The files and directories of the version, ordered by name. */
  public List<ArchiveEntry> entries() {
    return List.copyOf(entries);
  }

  /** The file or directory {@code name} (directories end with '/'). */
  public Optional<ArchiveEntry> entry(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /**
   * Problems met while reading the file list: damaged blocks that were skipped. Files whose data
   * was in them may be missing or fail to read.
   */
  public List<ErrorLine> problems() {
    return problems;
  }

  /**
   * The content of a file. The stream throws an IOException if the data is damaged or missing;
   * what it returned before that is correct.
   *
   * @throws java.nio.file.NoSuchFileException if there is no such file in this version
   */
  public InputStream open(String name) throws IOException {
    return reader.open(Run.bytes(name));
  }

  public InputStream open(ArchiveEntry entry) throws IOException {
    return open(entry.name());
  }

  /**
   * Visits the files (not the directories) with their contents, in the order in which their data
   * is stored, which reads each compressed block only once. The fastest way to read everything,
   * for example to copy an archive into another format.
   */
  public void forEach(ContentVisitor visitor) throws IOException {
    List<ArchiveEntry> files = new ArrayList<>();
    for (ArchiveEntry e : entries) if (!e.isDirectory()) files.add(e);
    files.sort(Comparator.comparingInt((ArchiveEntry e) -> firstFragment.get(e.name()))
        .thenComparing(ArchiveEntry::name));
    for (ArchiveEntry e : files) {
      try (InputStream in = open(e)) {
        visitor.visit(e, in);
      }
    }
  }

  /** Closes the archive files. Streams still open fail when read. */
  @Override
  public void close() {
    reader.close();
  }
}
