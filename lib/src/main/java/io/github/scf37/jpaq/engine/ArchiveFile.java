package io.github.scf37.jpaq.engine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A minimal stdio-like random access file ("rb", "wb", "rb+") with an explicit position. */
final class ArchiveFile implements Source, AutoCloseable {
  private final RandomAccessFile raf;
  private long pos;

  private ArchiveFile(RandomAccessFile raf) {
    this.raf = raf;
  }

  /** Opens an existing file for reading, or null if it cannot be opened. */
  static ArchiveFile openRead(Fs fs, String name, Exception[] err) {
    try {
      Path p = fs.path(name);
      if (Files.isDirectory(p)) throw new java.nio.file.FileSystemException(name, null, "Is a directory");
      return new ArchiveFile(new RandomAccessFile(p.toFile(), "r"));
    } catch (IOException | RuntimeException e) {
      if (err != null) err[0] = e;
      return null;
    }
  }

  /** Opens an existing file for reading and writing, or null if it does not exist. */
  static ArchiveFile openUpdate(Fs fs, String name, Exception[] err) {
    try {
      Path p = fs.path(name);
      if (!Files.exists(p)) throw new java.nio.file.NoSuchFileException(name);
      if (Files.isDirectory(p)) throw new java.nio.file.FileSystemException(name, null, "Is a directory");
      return new ArchiveFile(new RandomAccessFile(p.toFile(), "rw"));
    } catch (IOException | RuntimeException e) {
      if (err != null) err[0] = e;
      return null;
    }
  }

  /** Creates or truncates a file for writing, or returns null on failure. */
  static ArchiveFile create(Fs fs, String name, Exception[] err) {
    try {
      Path p = fs.path(name);
      if (Files.isDirectory(p)) throw new java.nio.file.FileSystemException(name, null, "Is a directory");
      RandomAccessFile r = new RandomAccessFile(p.toFile(), "rw");
      r.setLength(0);
      return new ArchiveFile(r);
    } catch (IOException | RuntimeException e) {
      if (err != null) err[0] = e;
      return null;
    }
  }

  /** Reads up to len bytes at the current position. Returns the number read (0 at EOF). */
  @Override
  public int read(byte[] buf, int off, int len) {
    try {
      if (len <= 0) return 0;
      int total = 0;
      while (total < len) {
        int r = raf.read(buf, off + total, len - total);
        if (r <= 0) break;
        total += r;
      }
      pos += total;
      return total;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void write(byte[] buf, int off, int len) {
    try {
      raf.write(buf, off, len);
      pos += len;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Positions at {@code p} bytes from the start. */
  void seekSet(long p) {
    try {
      if (p < 0) p = 0;
      if (p != pos) raf.seek(p);
      pos = p;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Positions at {@code p} bytes relative to the end. */
  void seekEnd(long p) {
    try {
      seekSet(raf.length() + p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  long tell() {
    return pos;
  }

  long length() {
    try {
      return raf.length();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void truncate(long len) {
    try {
      raf.setLength(len);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      raf.close();
    } catch (IOException e) {
      // ignored like fclose() failures
    }
  }
}
