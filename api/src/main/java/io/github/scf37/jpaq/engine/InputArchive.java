package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.Storage.Capability;
import io.github.scf37.jpaq.UnsupportedStorageException;
import io.github.scf37.jpaq.libzpaq.AesCtr;
import io.github.scf37.jpaq.libzpaq.Crypto;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An InputArchive supports encrypted reading of single or multi-part archives. A name containing
 * '*' or '?' is a multi-part archive whose parts are read as one concatenation.
 *
 * <p>Part sizes are needed to seek across parts. They come from the caller (an index knows them),
 * from measuring the parts if the storage can, or are learned while reading forward.
 */
final class InputArchive implements ZpaqReader, AutoCloseable {
  private final ArchiveIo io;
  private AesCtr aes;                  // null if not encrypted
  private ArchiveIo.In fp;             // currently open part or null
  private final List<Long> sz = new ArrayList<>();  // sizes of the first parts; -1 = unknown
  private int nparts = -1;             // number of parts if known
  private int partIdx;                 // index of the open part
  private long partStart;              // offset of the open part in the concatenation
  private long off;                    // current offset
  private final String fn;             // filename, possibly multi-part with wildcards
  private final String part0;

  /** Opens filename. If password (32 byte key hash) is not null then decrypts input. */
  InputArchive(ArchiveIo io, String filename, byte[] password) {
    this(io, filename, password, null);
  }

  /**
   * Same, with the sizes of the parts if known (an index tells them), so that nothing has to be
   * measured or read through to seek.
   */
  InputArchive(ArchiveIo io, String filename, byte[] password, List<Long> partSizes) {
    this.io = io;
    this.fn = filename;
    this.part0 = Strs.subpart(filename, 0);
    final boolean multi = !part0.equals(filename);
    if (!multi) nparts = 1;

    // Get file sizes
    if (partSizes != null) {
      sz.addAll(partSizes);
      nparts = partSizes.size();
    } else if (io.canMeasure()) {
      for (int i = 1; ; ++i) {
        final String parti = Strs.subpart(filename, i);
        if (i > 1 && parti.equals(part0)) break;
        if (i > 1 && !io.exists(parti)) break;  // part 1 is opened (and reported) below
        if (i == 1 && !io.exists(parti)) break;
        sz.add(io.length(parti));
      }
      nparts = sz.size();
    } else if (!multi) {
      sz.add(-1L);
    } else {
      throw new UnsupportedStorageException(Capability.PROBE,
          "needed to find the parts of '" + filename + "'; read through an index instead");
    }

    // Open first part
    final String part1 = Strs.subpart(filename, 1);
    Exception[] err = new Exception[1];
    fp = io.openRead(part1, err);
    if (fp == null) throw io.ctx.ioerr(part1, err[0]);

    // Get encryption salt
    if (password != null) {
      byte[] salt = new byte[32];
      if (fp.read(salt, 0, 32) != 32) throw ZpaqException.error("cannot read salt");
      byte[] key = Crypto.stretchKey(password, salt);
      aes = new AesCtr(key, 32, salt);
      off = 32;
    }
  }

  boolean isOpen() {
    return fp != null;
  }

  /** Not implemented: use {@link #read}. */
  @Override
  public int get() {
    throw ZpaqException.error("get() not implemented");
  }

  /** Reads up to len bytes into obuf at the current offset. Returns 0 at EOF. */
  @Override
  public int read(byte[] obuf, int boff, int len) {
    int nr = fp.read(obuf, boff, len);
    while (nr == 0 && len > 0 && nextPart()) nr = fp.read(obuf, boff, len);
    if (nr == 0) return 0;
    if (aes != null) aes.encrypt(obuf, boff, nr, off);
    off += nr;
    return nr;
  }

  private void setSize(int idx, long size) {
    while (sz.size() <= idx) sz.add(-1L);
    sz.set(idx, size);
  }

  private long knownSize(int idx) {
    return idx < sz.size() ? sz.get(idx) : -1;
  }

  /** Size of part idx (0 based), measured or read through if not known yet. */
  private long partSize(int idx) {
    long s = knownSize(idx);
    if (s >= 0) return s;
    if (idx == partIdx && fp != null) {
      long save = fp.tell();
      if (io.canMeasure()) {
        s = fp.length();
      } else {
        fp.seekSet(Long.MAX_VALUE / 2);  // reads to the end
        fp.settle();
        s = fp.tell();
        fp.seekSet(save);
      }
    } else {
      ArchiveIo.In f = io.openRead(Strs.subpart(fn, idx + 1), null);
      if (f == null) return -1;
      s = io.canMeasure() ? f.length() : readThrough(f);
      f.close();
    }
    setSize(idx, s);
    return s;
  }

  private static long readThrough(ArchiveIo.In f) {
    f.seekSet(Long.MAX_VALUE / 2);
    f.settle();
    return f.tell();
  }

  /** At the end of the open part: continues with the next one if there is one. */
  private boolean nextPart() {
    if (nparts >= 0 && partIdx + 1 >= nparts) return false;
    final String next = Strs.subpart(fn, partIdx + 2);
    if (next.equals(part0)) return false;
    ArchiveIo.In f = openNext(next, nparts >= 0);
    if (f == null) {
      nparts = partIdx + 1;
      return false;
    }
    setSize(partIdx, off - partStart);
    fp.close();
    fp = f;
    ++partIdx;
    partStart = off;
    return true;
  }

  /** Opens the next part if it exists; failures to open an existing part are errors. */
  private ArchiveIo.In openNext(String name) {
    return openNext(name, false);
  }

  /** @param known the part is known to exist (it is counted in nparts), so no probing */
  private ArchiveIo.In openNext(String name, boolean known) {
    if (!known && !io.exists(name)) return null;
    Exception[] err = new Exception[1];
    ArchiveIo.In f = io.openRead(name, err);
    if (f == null) throw io.ctx.ioerr(name, err[0]);
    return f;
  }

  private void openPart(int idx, long start) {
    final String name = Strs.subpart(fn, idx + 1);
    Exception[] err = new Exception[1];
    ArchiveIo.In f = io.openRead(name, err);
    if (f == null) throw io.ctx.ioerr(name, err[0]);
    fp.close();
    fp = f;
    partIdx = idx;
    partStart = start;
  }

  enum Whence { SET, CUR, END }

  /** Like fseeko(). If p is out of range then the file position is past the end. */
  void seek(long p, Whence whence) {
    if (!isOpen()) return;

    // Compute new offset
    long target;
    if (whence == Whence.SET) {
      target = p;
    } else if (whence == Whence.CUR) {
      target = off + p;
    } else {
      long total = 0;
      for (int i = 0; nparts < 0 || i < nparts; ++i) {
        long s = partSize(i);
        if (s < 0) {
          nparts = i;
          break;
        }
        total += s;
      }
      target = p + total;
    }
    off = target;
    if (off < 0) off = 0;

    // Go back to an earlier part
    if (off < partStart) {
      long start = 0;
      int i = 0;
      while (true) {
        long s = knownSize(i);
        if (s < 0 || start + s > off) break;
        start += s;
        ++i;
      }
      openPart(i, start);
    }

    // Move forward over parts that end before the target
    while (true) {
      if (nparts >= 0 && partIdx + 1 >= nparts) break;  // last part: position past its end
      final String next = Strs.subpart(fn, partIdx + 2);
      if (next.equals(part0)) break;
      long s = knownSize(partIdx);
      if (s < 0) {
        // Size unknown (stream): move within the part and see whether it is long enough
        fp.seekSet(off - partStart);
        fp.settle();
        if (fp.tell() >= off - partStart) return;
        s = fp.tell();
        setSize(partIdx, s);
      }
      if (partStart + s > off) break;
      ArchiveIo.In f = openNext(next, nparts >= 0);
      if (f == null) {
        nparts = partIdx + 1;
        break;
      }
      fp.close();
      fp = f;
      partStart += s;
      ++partIdx;
    }
    fp.seekSet(off - partStart);
  }

  long tell() {
    return off;
  }

  @Override
  public void close() {
    if (fp != null) fp.close();
    fp = null;
  }
}
