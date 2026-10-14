package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.libzpaq.AesCtr;
import io.github.scf37.jpaq.libzpaq.Crypto;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqReader;
import java.util.ArrayList;
import java.util.List;

/**
 * An InputArchive supports encrypted reading of single or multi-part archives. A name containing
 * '*' or '?' is a multi-part archive whose parts are read as one concatenation.
 */
final class InputArchive implements ZpaqReader, AutoCloseable {
  private AesCtr aes;                  // null if not encrypted
  private ArchiveFile fp;              // currently open file or null
  private final List<Long> sz = new ArrayList<>();  // part sizes
  private long off;                    // current offset
  private final String fn;             // filename, possibly multi-part with wildcards

  /** Opens filename. If password (32 byte key hash) is not null then decrypts input. */
  InputArchive(String filename, byte[] password) {
    this.fn = filename;

    // Get file sizes
    final String part0 = Strs.subpart(filename, 0);
    for (int i = 1; ; ++i) {
      final String parti = Strs.subpart(filename, i);
      if (i > 1 && parti.equals(part0)) break;
      fp = ArchiveFile.openRead(parti, null);
      if (fp == null) break;
      sz.add(fp.length());
      fp.close();
      fp = null;
    }

    // Open first part
    final String part1 = Strs.subpart(filename, 1);
    Exception[] err = new Exception[1];
    fp = ArchiveFile.openRead(part1, err);
    if (fp == null) throw ioerr(part1, err[0]);

    // Get encryption salt
    if (password != null) {
      byte[] salt = new byte[32];
      if (fp.read(salt, 0, 32) != 32) throw ZpaqException.error("cannot read salt");
      byte[] key = Crypto.stretchKey(password, salt);
      aes = new AesCtr(key, 32, salt);
      off = 32;
    }
  }

  static ZpaqException ioerr(String name, Exception e) {
    if (e != null) Fs.printerr(name, e);
    else Fs.printerr(name, "I/O error");
    return new ZpaqException(name);
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
    if (nr == 0) {
      seek(0, Whence.CUR);
      nr = fp.read(obuf, boff, len);
    }
    if (nr == 0) return 0;
    if (aes != null) aes.encrypt(obuf, boff, nr, off);
    off += nr;
    return nr;
  }

  enum Whence { SET, CUR, END }

  /** Like fseeko(). If p is out of range then the file position is past the end. */
  void seek(long p, Whence whence) {
    if (!isOpen()) return;

    // Compute new offset
    if (whence == Whence.SET) {
      off = p;
    } else if (whence == Whence.CUR) {
      off += p;
    } else {
      off = p;
      for (long s : sz) off += s;
    }

    // Optimization for single file to avoid close and reopen
    if (sz.size() == 1) {
      fp.seekSet(off);
      return;
    }

    // Seek across multiple files
    long sum = 0;
    int i;
    for (i = 0; ; ++i) {
      sum += sz.get(i);
      if (sum > off || i + 1 >= sz.size()) break;
    }
    final String next = Strs.subpart(fn, i + 1);
    fp.close();
    Exception[] err = new Exception[1];
    fp = ArchiveFile.openRead(next, err);
    if (fp == null) throw ioerr(next, err[0]);
    fp.seekEnd(off - sum);
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
