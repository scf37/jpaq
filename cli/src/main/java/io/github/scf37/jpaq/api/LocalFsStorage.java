package io.github.scf37.jpaq.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Set;

/** Archive files in a directory of the local file system, with all capabilities. */
public final class LocalFsStorage implements Storage {
  private final Path base;
  private final boolean atomicWrite;

  /** Relative names resolve against {@code base} (null = the process working directory). */
  public LocalFsStorage(Path base) {
    this(base, false);
  }

  /**
   * @param atomicWrite write into a temporary file and move it into place on close, so a reader
   *     never sees a half written file (and a failed write leaves the old file)
   */
  public LocalFsStorage(Path base, boolean atomicWrite) {
    this.base = base;
    this.atomicWrite = atomicWrite;
  }

  private Path path(String name) {
    Path p = Path.of(name);
    return base != null ? base.resolve(p) : p;
  }

  private void checkNotDirectory(Path p, String name) throws IOException {
    if (Files.isDirectory(p))
      throw new java.nio.file.FileSystemException(name, null, "Is a directory");
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.allOf(Capability.class);
  }

  @Override
  public InputStream read(String name) throws IOException {
    Path p = path(name);
    checkNotDirectory(p, name);
    return Files.newInputStream(p);
  }

  @Override
  public OutputStream write(String name) throws IOException {
    Path p = path(name);
    checkNotDirectory(p, name);
    if (!atomicWrite) return Files.newOutputStream(p);
    Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
    OutputStream o = Files.newOutputStream(tmp);
    return new OutputStream() {
      private boolean closed;

      @Override
      public void write(int b) throws IOException {
        o.write(b);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        o.write(b, off, len);
      }

      @Override
      public void close() throws IOException {
        if (closed) return;
        closed = true;
        o.close();
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      }
    };
  }

  @Override
  public void delete(String name) throws IOException {
    Files.deleteIfExists(path(name));
  }

  @Override
  public boolean exists(String name) {
    return Files.exists(path(name), java.nio.file.LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public long length(String name) throws IOException {
    Path p = path(name);
    checkNotDirectory(p, name);
    return Files.size(p);
  }

  @Override
  public InputStream read(String name, long offset) throws IOException {
    InputStream in = read(name);
    try {
      in.skipNBytes(offset);
    } catch (IOException | RuntimeException e) {
      in.close();
      throw e;
    }
    return in;
  }

  @Override
  public RandomFile open(String name, boolean write, boolean create) throws IOException {
    Path p = path(name);
    if (!(write && create) && !Files.exists(p)) throw new NoSuchFileException(name);
    checkNotDirectory(p, name);
    RandomAccessFile raf = new RandomAccessFile(p.toFile(), write ? "rw" : "r");
    return new RandomFile() {
      @Override
      public int read(byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
          int r = raf.read(buf, off + total, len - total);
          if (r <= 0) break;
          total += r;
        }
        return total;
      }

      @Override
      public void write(byte[] buf, int off, int len) throws IOException {
        raf.write(buf, off, len);
      }

      @Override
      public void seek(long pos) throws IOException {
        raf.seek(pos);
      }

      @Override
      public long length() throws IOException {
        return raf.length();
      }

      @Override
      public void setLength(long len) throws IOException {
        raf.setLength(len);
      }

      @Override
      public void close() throws IOException {
        raf.close();
      }
    };
  }
}
