package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.libzpaq.Decompresser;
import io.github.scf37.jpaq.libzpaq.MemoryBuffer;
import io.github.scf37.jpaq.libzpaq.Sha1;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contents of an archive as read up to a version, with the data of each file available as a
 * stream. A file is made of fragments stored in compressed blocks; a block is decompressed whole,
 * its fragments are checked against their SHA-1, and the last few blocks are kept, so that the
 * files of a block are read with one decompression. Names are byte strings (see {@link Strs}).
 *
 * <p>Safe for use by several threads; reads are serialized.
 */
public final class EntryReader implements AutoCloseable {
  /** A file or directory of the archive. Date decimal YYYYMMDDHHMMSS (UT), size -1 if unknown. */
  public record Info(String name, long date, long size, long attr, int firstFragment) {}

  /** A version (update) of the archive. */
  public record VersionInfo(int number, long date, int updates, int deletes) {}

  private static final int CACHED_BLOCKS = 2;

  private final Jidac j;
  private InputArchive in;  // opened when data is first read
  private boolean closed;
  private final Map<Integer, Decoded> cache = new LinkedHashMap<>(4, 0.75f, true);

  /** A decompressed block: its data and the offset of each of its fragments. */
  private record Decoded(byte[] data, long[] fragOffset) {}

  private EntryReader(Jidac j) {
    this.j = j;
  }

  /** Reads the file list of the archive (or of {@code j.readIndex}) with the options of j. */
  public static EntryReader open(Jidac j) {
    j.command = 'x';
    j.prepare();
    j.readArchive(j.fromIndex() ? j.readIndex : j.archive, null);
    return new EntryReader(j);
  }

  /** The files and directories, in the order of their names. */
  public List<Info> files() {
    List<Info> r = new ArrayList<>();
    for (DT p : j.dt.values()) {
      if (p.date() == 0 || p.name().isEmpty()) continue;
      r.add(new Info(p.name(), p.date(), p.size(), p.attr(), p.ptrSize() > 0 ? p.ptr(0) : 0));
    }
    return r;
  }

  /** The versions, oldest first. */
  public List<VersionInfo> versions() {
    List<VersionInfo> r = new ArrayList<>();
    for (int i = 1; i < j.ver.size(); i++) {
      Ver v = j.ver.get(i);
      r.add(new VersionInfo(i, v.date, v.updates, v.deletes));
    }
    return r;
  }

  /**
   * The content of the file {@code name}. The stream verifies every fragment it returns and
   * throws an IOException at the first one that is missing or damaged.
   */
  public synchronized InputStream open(String name) throws IOException {
    if (closed) throw new IOException("closed");
    DT p = j.dt.get(name);
    if (p == null || p.date() == 0) throw new java.nio.file.NoSuchFileException(Strs.fromBytes(name));
    if (p.isDir()) throw new IOException(Strs.fromBytes(name) + ": is a directory");
    int[] ptr = new int[p.ptrSize()];
    for (int i = 0; i < ptr.length; i++) ptr[i] = p.ptr(i);
    return new Content(Strs.fromBytes(name), ptr);
  }

  /** Index of the block that holds fragment f, -1 if none. */
  private int blockOf(int f) {
    final List<Block> block = j.block;
    int lo = 0;
    int hi = block.size() - 1;
    if (hi < 0 || Integer.compareUnsigned(f, block.get(0).start) < 0) return -1;
    while (lo < hi) {
      int mid = (lo + hi + 1) / 2;
      if (Integer.compareUnsigned(f, block.get(mid).start) < 0) hi = mid - 1;
      else lo = mid;
    }
    return lo;
  }

  /** Number of fragments of block bi (up to the next block or the end of the table). */
  private int fragsOf(int bi) {
    int end = bi + 1 < j.block.size() ? j.block.get(bi + 1).start : j.ht.size();
    return end - j.block.get(bi).start;
  }

  private Decoded decoded(int bi) throws IOException {
    Decoded d = cache.get(bi);
    if (d != null) return d;
    try {
      d = decode(bi);
    } catch (ZpaqException | java.io.UncheckedIOException e) {
      Block b = j.block.get(bi);
      String why = e instanceof java.io.UncheckedIOException u ? u.getCause().toString()
          : Jidac.what(e);
      throw new IOException(String.format(java.util.Locale.ROOT,
          "block of fragments %d..%d at %d: %s", b.start, b.start + fragsOf(bi) - 1, b.offset,
          why), e);
    }
    cache.put(bi, d);
    while (cache.size() > CACHED_BLOCKS) cache.remove(cache.keySet().iterator().next());
    return d;
  }

  /** Decompresses block bi and checks the hashes of its fragments. */
  private Decoded decode(int bi) {
    final Block b = j.block.get(bi);
    if (b.usize < 0) throw ZpaqException.error("streaming format block (extract instead)");
    final int n = fragsOf(bi);
    long[] off = new long[n + 1];
    for (int k = 0; k < n; k++) {
      int u = j.ht.usize(b.start + k);
      if (u < 0) throw ZpaqException.error("fragment of unknown size");
      off[k + 1] = off[k] + u;
    }
    final long outputSize = off[n];
    if (outputSize > Integer.MAX_VALUE - 16) throw ZpaqException.error("block too big");
    if (in == null) in = j.openArchive();
    in.seek(b.offset, InputArchive.Whence.SET);
    Decompresser d = new Decompresser();
    d.setInput(in);
    MemoryBuffer out = new MemoryBuffer();
    out.setLimit(b.usize);
    d.setOutput(out);
    if (!d.findBlock()) throw ZpaqException.error("archive block not found");
    while (d.findFilename()) {
      d.readComment();
      while (out.size() < outputSize && d.decompress(1 << 14)) { }
      if (out.size() >= outputSize) break;
      d.readSegmentEnd();
    }
    if (out.size() < outputSize) throw ZpaqException.error("unexpected end of compressed data");
    byte[] data = Arrays.copyOf(out.data(), (int) outputSize);
    byte[] want = new byte[20];
    for (int k = 0; k < n; k++) {
      Sha1 sha1 = new Sha1();
      sha1.write(data, (int) off[k], (int) (off[k + 1] - off[k]));
      j.ht.copySha1(b.start + k, want, 0);
      if (!Arrays.equals(sha1.result(), want))
        throw ZpaqException.error("fragment " + (b.start + k) + " checksum failed");
    }
    return new Decoded(data, off);
  }

  /** The data of a file: its fragments in order. */
  private final class Content extends InputStream {
    private final String name;
    private final int[] ptr;
    private int next;         // next fragment to load
    private byte[] buf;       // data of the current fragment
    private int pos;
    private int end;
    private boolean closed;

    Content(String name, int[] ptr) {
      this.name = name;
      this.ptr = ptr;
    }

    /** Loads the next fragment; false at the end of the file. */
    private boolean load() throws IOException {
      while (pos >= end) {
        if (next >= ptr.length) return false;
        final int f = ptr[next++];
        if (f < 1 || Integer.compareUnsigned(f, j.ht.size()) >= 0)
          throw new IOException(name + ": bad fragment pointer " + Integer.toUnsignedString(f));
        final int bi = blockOf(f);
        if (bi < 0 || f - j.block.get(bi).start >= fragsOf(bi))
          throw new IOException(name + ": fragment " + f + " is in no block");
        Decoded d;
        try {
          d = decoded(bi);
        } catch (IOException e) {
          throw new IOException(name + ": " + e.getMessage(), e);
        }
        final int k = f - j.block.get(bi).start;
        buf = d.data();
        pos = (int) d.fragOffset()[k];
        end = (int) d.fragOffset()[k + 1];
      }
      return true;
    }

    @Override
    public int read() throws IOException {
      synchronized (EntryReader.this) {
        check();
        if (!load()) return -1;
        return buf[pos++] & 255;
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      java.util.Objects.checkFromIndexSize(off, len, b.length);
      if (len == 0) return 0;
      synchronized (EntryReader.this) {
        check();
        if (!load()) return -1;
        int n = Math.min(len, end - pos);
        System.arraycopy(buf, pos, b, off, n);
        pos += n;
        return n;
      }
    }

    private void check() throws IOException {
      if (closed || EntryReader.this.closed) throw new IOException(name + ": stream closed");
    }

    @Override
    public void close() {
      closed = true;
      buf = null;
    }
  }

  @Override
  public synchronized void close() {
    closed = true;
    cache.clear();
    if (in != null) in.close();
    in = null;
  }
}
