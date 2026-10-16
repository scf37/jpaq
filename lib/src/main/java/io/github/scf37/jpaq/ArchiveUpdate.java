package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Dates;
import io.github.scf37.jpaq.engine.Updater;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * An update of an archive in progress (see {@link ZpaqArchive#update}). Files are compressed as
 * they are added, by background threads; the new version exists once {@link #commit} returns.
 * Closing without committing abandons the update and leaves the archive as it was. Entries that
 * the update does not mention stay as they are.
 *
 * <p>Not thread safe: add from one thread at a time. If adding fails midway (a stream that
 * throws, a storage that fails), the update can only be closed.
 */
public final class ArchiveUpdate implements Closeable {
  private final Run run;
  private final Updater u;
  private boolean committed;

  ArchiveUpdate(Run run, Updater u) {
    this.run = run;
    this.u = u;
  }

  /** The date of the update: the date of the new version, and of files added without one. */
  public Instant date() {
    return instant(u.date());
  }

  private static Instant instant(long decimalDate) {
    return Instant.ofEpochSecond(Dates.unixTime(decimalDate));
  }

  private static long decimal(Instant t) {
    if (t == null) return 0;
    long d = Dates.decimalTime(t.getEpochSecond());
    if (d < 19000101000000L || d > 29991231235959L)
      throw new IllegalArgumentException("date out of range 1900..2999: " + t);
    return d;
  }

  /**
   * Adds a file with the content of {@code data}, read to its end (the stream is not closed),
   * dated with the date of the update and without attributes.
   */
  public void add(String name, InputStream data) throws IOException {
    add(name, data, null, Attributes.NONE);
  }

  /**
   * Adds a file with the content of {@code data}, read to its end (the stream is not closed). A
   * file of that name that is in the archive is replaced in the new version; one added before in
   * this update is replaced too. Content that is already in the archive is not stored again.
   *
   * @param name '/' separated, not ending with '/'
   * @param lastModified null for the date of the update
   */
  public void add(String name, InputStream data, Instant lastModified, Attributes attributes)
      throws IOException {
    Objects.requireNonNull(data, "data");
    final long date = decimal(lastModified);
    final long attr = attributes != null ? attributes.raw() : 0;
    run.call(() -> {
      u.addStream(Run.bytes(name), data, date, attr);
      return null;
    });
  }

  /** Adds a directory entry (an empty directory, or the date and attributes of one). */
  public void addDirectory(String name, Instant lastModified, Attributes attributes)
      throws IOException {
    final long date = decimal(lastModified);
    final long attr = attributes != null ? attributes.raw() : 0;
    run.call(() -> {
      u.addDirectory(Run.bytes(name), date, attr);
      return null;
    });
  }

  /**
   * Adds the local file {@code file} as {@code name}, with its date and attributes.
   *
   * @throws NoSuchFileException if it does not exist
   * @throws IOException if it is not a regular file or can't be read
   */
  public void addFile(String name, Path file) throws IOException {
    final int before = run.problems().size();
    boolean ok = run.call(() -> u.addFile(Run.bytes(name), Run.bytes(file.toAbsolutePath())));
    if (ok) return;
    var p = run.problems();
    for (int i = p.size() - 1; i >= before; i--) {
      if (p.get(i) instanceof ErrorLine.FileError f) {
        if (f.cause() instanceof IOException io) throw io;
        throw new IOException(file + ": " + f.reason());
      }
    }
    throw new IOException(file + ": not added");
  }

  /**
   * Makes the archive under {@code name} like the local directory {@code dir}, as the zpaq add
   * command does: adds the files that are new or whose date or size changed (all files with
   * {@link UpdateOptions#force}), and deletes the entries under {@code name} whose files are gone.
   * Files and directories that can't be read are skipped and reported in the result. Symbolic
   * links and other special files are ignored.
   *
   * @param dir a directory (or a single file)
   * @param name where it goes in the archive; "" for the top
   * @throws NoSuchFileException if {@code dir} does not exist (rather than deleting everything)
   */
  public void addTree(Path dir, String name) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(dir.toString());
    run.call(() -> {
      u.addTree(Run.bytes(dir.toAbsolutePath()), Run.bytes(name));
      return null;
    });
  }

  /**
   * Deletes {@code name} from the archive (from the new version; older versions keep it), with
   * everything under it if it is a directory. Returns the number of entries deleted.
   */
  public int delete(String name) throws IOException {
    return run.call(() -> u.delete(Run.bytes(name)));
  }

  /**
   * Writes the new version. If nothing changed, nothing is written and {@link
   * UpdateResult#changed} is false.
   */
  public UpdateResult commit() throws IOException {
    run.call(u::commit);
    committed = true;
    InfoLine.AddSummary s = run.last(InfoLine.AddSummary.class);
    InfoLine.AddResult r = run.last(InfoLine.AddResult.class);
    final int added = s != null ? s.added() : 0;
    final int removed = s != null ? s.removed() : 0;
    final boolean changed = added + removed > 0;
    return new UpdateResult(changed, u.versions() + (changed ? 1 : 0), date(), added, removed,
        r != null ? r.inputBytes() : 0, r != null ? r.dedupedBytes() : 0,
        r != null ? r.addedBytes() : 0, run.problems());
  }

  /** Abandons the update unless it was committed. */
  @Override
  public void close() {
    if (!committed) u.close();
  }
}
