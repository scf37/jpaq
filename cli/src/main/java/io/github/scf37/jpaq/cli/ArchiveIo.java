package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.api.Capability;
import io.github.scf37.jpaq.api.Format;
import io.github.scf37.jpaq.api.RandomFile;
import io.github.scf37.jpaq.api.Storage;
import io.github.scf37.jpaq.api.UnsupportedStorageException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * Archive file access on top of a {@link Storage}: the positional files that InputArchive and
 * OutputArchive are written against. Names are byte strings (see Strs) and are converted to real
 * text for the storage. Uses random access where the storage has it and plain streams otherwise,
 * so the same code reads sequential archives from a pipe-like storage and classic ones from disk.
 */
final class ArchiveIo {
  final Storage st;
  final Format format;
  final boolean random;  // positional read/write usable
  final boolean probe;   // exists(), length() usable
  final boolean ranged;  // read(name, offset) usable

  ArchiveIo(Storage st, Format format) {
    this.st = st;
    this.format = format;
    var caps = st.capabilities();
    this.probe = caps.contains(Capability.PROBE);
    this.ranged = caps.contains(Capability.RANGED_READ);
    this.random = caps.contains(Capability.RANDOM_WRITE);
  }

  /** Throws if a command writing the given format can't work on this storage. */
  void checkWritable() {
    if (format == Format.CLASSIC) {
      if (!random)
        throw new UnsupportedStorageException(Capability.RANDOM_WRITE,
            "the classic format patches the transaction header and truncates empty updates");
      if (!probe)
        throw new UnsupportedStorageException(Capability.PROBE,
            "the classic format looks for existing archive parts");
    }
  }

  private static String real(String name) {
    return Strs.fromBytes(name);
  }

  /** True if the blob exists. Only PROBE can tell; exceptions are failures, never "no". */
  boolean exists(String name) {
    if (name.isEmpty()) return false;
    if (!probe)
      throw new UnsupportedStorageException(Capability.PROBE, "needed to tell whether '" + name
          + "' exists");
    try {
      return st.exists(real(name));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Size of the blob. Reads it through if the storage can't probe. */
  long length(String name) {
    try {
      if (probe) return st.length(real(name));
      try (InputStream in = st.read(real(name))) {
        long n = 0;
        byte[] b = new byte[1 << 16];
        int r;
        while ((r = in.read(b)) > 0) n += r;
        return n;
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** True if the number and sizes of archive parts can be had without reading them. */
  boolean canMeasure() {
    return probe;
  }

  boolean delete(String name) {
    try {
      st.delete(real(name));
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  /** Opens for reading, or returns null and stores the cause in {@code err[0]}. */
  In openRead(String name, Exception[] err) {
    try {
      if (random) return new RandIn(st.open(real(name), false, false));
      return new StreamIn(this, name);
    } catch (IOException | RuntimeException e) {
      if (err != null) err[0] = e;
      return null;
    }
  }

  /** Opens an existing blob for reading and writing (classic format), or null if missing. */
  Out openUpdate(String name, Exception[] err) {
    if (!random) return null;
    try {
      return new RandOut(st.open(real(name), true, false), false);
    } catch (IOException | RuntimeException e) {
      if (err != null) err[0] = e;
      return null;
    }
  }

  /** Creates or replaces a blob, or returns null and stores the cause. */
  Out create(String name, Exception[] err) {
    try {
      if (random && format == Format.CLASSIC) return new RandOut(st.open(real(name), true, true), true);
      return new StreamOut(this, name);
    } catch (IOException | RuntimeException e) {
      if (err != null) err[0] = e;
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------

  /** A positional input file. */
  abstract static class In implements AutoCloseable {
    /** Reads up to len bytes (fewer only at the end of the file). */
    abstract int read(byte[] buf, int off, int len);

    /** Moves to byte p; if the file is shorter, the position is at its end. */
    abstract void seekSet(long p);

    abstract long tell();

    abstract long length();

    @Override
    public abstract void close();
  }

  private static final class RandIn extends In {
    private final RandomFile f;
    private long pos;

    RandIn(RandomFile f) {
      this.f = f;
    }

    @Override
    int read(byte[] buf, int off, int len) {
      try {
        if (len <= 0) return 0;
        int n = f.read(buf, off, len);
        pos += n;
        return n;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    void seekSet(long p) {
      try {
        if (p < 0) p = 0;
        if (p != pos) f.seek(p);
        pos = p;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    long tell() {
      return pos;
    }

    @Override
    long length() {
      try {
        return f.length();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void close() {
      try {
        f.close();
      } catch (IOException e) {
        // ignored like fclose() failures
      }
    }
  }

  /** Input from a stream; seeking forward discards, seeking back (or far ahead) reopens. */
  private static final class StreamIn extends In {
    private static final long REOPEN_GAP = 1 << 20;  // ranged reads pay off above this skip
    private final ArchiveIo io;
    private final String name;
    private InputStream in;
    private long pos;

    StreamIn(ArchiveIo io, String name) throws IOException {
      this.io = io;
      this.name = name;
      this.in = io.st.read(real(name));
    }

    @Override
    int read(byte[] buf, int off, int len) {
      try {
        int total = 0;
        while (total < len) {
          int r = in.read(buf, off + total, len - total);
          if (r <= 0) break;
          total += r;
        }
        pos += total;
        return total;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    void seekSet(long p) {
      try {
        if (p < 0) p = 0;
        if (p == pos) return;
        if (p < pos || (io.ranged && p - pos > REOPEN_GAP)) {
          in.close();
          if (io.ranged) {
            in = io.st.read(real(name), p);
            pos = p;
            return;
          }
          in = io.st.read(real(name));
          pos = 0;
        }
        byte[] skip = new byte[1 << 16];
        while (pos < p) {
          int r = in.read(skip, 0, (int) Math.min(skip.length, p - pos));
          if (r <= 0) break;  // end of file: stay at its end
          pos += r;
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    long tell() {
      return pos;
    }

    @Override
    long length() {
      return io.length(name);
    }

    @Override
    public void close() {
      try {
        in.close();
      } catch (IOException e) {
        // ignored
      }
    }
  }

  /** A positional output file. */
  abstract static class Out implements AutoCloseable {
    abstract void write(byte[] buf, int off, int len);

    /** Reads at the current position (existing files only). */
    abstract int read(byte[] buf, int off, int len);

    abstract void seekSet(long p);

    abstract void seekEnd(long p);

    abstract long tell();

    abstract void truncate(long len);

    @Override
    public abstract void close();

    /**
     * Drops everything written if nothing reached the storage yet. Returns true if so (the blob
     * was never created).
     */
    boolean discard() {
      return false;
    }

    /** Stops writing after an error; deletes what was written if possible. */
    void abort() {
      close();
    }
  }

  private static final class RandOut extends Out {
    private final RandomFile f;
    private long pos;

    RandOut(RandomFile f, boolean truncate) throws IOException {
      this.f = f;
      if (truncate) f.setLength(0);
    }

    @Override
    void write(byte[] buf, int off, int len) {
      try {
        f.write(buf, off, len);
        pos += len;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    int read(byte[] buf, int off, int len) {
      try {
        int n = f.read(buf, off, len);
        pos += n;
        return n;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    void seekSet(long p) {
      try {
        if (p < 0) p = 0;
        if (p != pos) f.seek(p);
        pos = p;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    void seekEnd(long p) {
      try {
        seekSet(f.length() + p);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    long tell() {
      return pos;
    }

    @Override
    void truncate(long len) {
      try {
        f.setLength(len);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void close() {
      try {
        f.close();
      } catch (IOException e) {
        // ignored like fclose() failures
      }
    }
  }

  /**
   * Output to a stream: strictly sequential. The blob is only created when more than a little has
   * been written (or at close), so an update that turns out to be empty can be dropped.
   */
  private static final class StreamOut extends Out {
    private static final int HOLD = 1 << 20;
    private final ArchiveIo io;
    private final String name;
    private ByteArrayOutputStream hold = new ByteArrayOutputStream();
    private OutputStream out;
    private long pos;
    private boolean closed;

    StreamOut(ArchiveIo io, String name) {
      this.io = io;
      this.name = name;
    }

    private void open() throws IOException {
      out = io.st.write(real(name));
      hold.writeTo(out);
      hold = null;
    }

    @Override
    void write(byte[] buf, int off, int len) {
      try {
        if (out == null) {
          hold.write(buf, off, len);
          if (hold.size() >= HOLD) open();
        } else {
          out.write(buf, off, len);
        }
        pos += len;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    int read(byte[] buf, int off, int len) {
      throw new IllegalStateException("cannot read a stream being written");
    }

    @Override
    void seekSet(long p) {
      if (p != pos) throw new IllegalStateException("cannot seek in a sequentially written file");
    }

    @Override
    void seekEnd(long p) {
      if (p != 0) throw new IllegalStateException("cannot seek in a sequentially written file");
    }

    @Override
    long tell() {
      return pos;
    }

    @Override
    void truncate(long len) {
      if (len != pos) throw new IllegalStateException("cannot truncate a sequentially written file");
    }

    @Override
    boolean discard() {
      if (out != null || closed) return false;
      closed = true;
      hold = null;
      return true;
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      try {
        if (out == null) open();
        out.close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    void abort() {
      if (closed) return;
      closed = true;
      try {
        if (out != null) out.close();
      } catch (IOException e) {
        // ignored
      }
      if (out != null) io.delete(name);
    }
  }
}
