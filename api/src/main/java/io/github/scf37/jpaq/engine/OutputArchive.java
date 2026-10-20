package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.ZpaqArchive.Format;
import io.github.scf37.jpaq.libzpaq.AesCtr;
import io.github.scf37.jpaq.libzpaq.Crypto;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqWriter;
import java.util.Arrays;

/**
 * An output archive file supporting encryption. If the filename is empty then only the position is
 * tracked and nothing is written.
 */
final class OutputArchive implements ZpaqWriter, AutoCloseable {
  private static final int BUFSIZE = 1 << 16;

  private AesCtr aes;        // null if not encrypted
  private ArchiveIo.Out fp;  // currently open file or null
  private long off;          // preceding multi-part bytes
  private int ptr;           // write pointer in buf: 0 <= ptr <= BUFSIZE
  private final byte[] buf = new byte[BUFSIZE];

  /**
   * Creates or updates an existing archive or part. If filename is "" then keeps track of the
   * position but does not write to disk. Otherwise opens and encrypts with password if not null. If
   * the file exists then the salt is read from the first 32 bytes and off_ must be 0. Otherwise
   * encrypts assuming off_ previous bytes, of which the first 32 are salt_. If off_ is 0 then writes
   * salt_ to the first 32 bytes.
   */
  OutputArchive(ArchiveIo io, String filename, byte[] password, byte[] salt_, long off_) {
    this.off = off_;
    if (filename.isEmpty()) return;

    // Open existing file
    byte[] salt = new byte[32];
    // Only the classic format updates an existing file, and it can probe
    if (io.format == Format.CLASSIC && io.random && io.exists(filename)) {
      Exception[] uerr = new Exception[1];
      fp = io.openUpdate(filename, uerr);
      if (fp == null) throw io.ctx.ioerr(filename, uerr[0]);
    }
    if (fp != null) {
      if (off != 0) throw ZpaqException.error("file exists and off > 0");
      if (password != null) {
        if (fp.read(salt, 0, 32) != 32) throw ZpaqException.error("cannot read salt");
        if (salt_ != null && !Arrays.equals(salt, salt_)) throw ZpaqException.error("salt mismatch");
      }
      seek(0, InputArchive.Whence.END);
    } else {
      // Create new file
      Exception[] err = new Exception[1];
      fp = io.create(filename, err);
      if (fp == null) throw io.ctx.ioerr(filename, err[0]);
      if (password != null) {
        if (salt_ == null) throw ZpaqException.error("salt not specified");
        System.arraycopy(salt_, 0, salt, 0, 32);
        if (off == 0) fp.write(salt, 0, 32);
      }
    }

    // Set up encryption
    if (password != null) {
      byte[] key = io.format == Format.CLASSIC ? Crypto.stretchKey(password, salt)
          : Crypto.stretchKeySequential(password, salt);
      aes = new AesCtr(key, 32, salt);
    }
  }

  /** Writes pending output. */
  void flush() {
    if (aes != null) aes.encrypt(buf, 0, ptr, fp.tell() + off);
    fp.write(buf, 0, ptr);
    ptr = 0;
  }

  /** Positions the next write offset at p (relative to the start, current position or end). */
  void seek(long p, InputArchive.Whence whence) {
    if (fp != null) {
      flush();
      if (whence == InputArchive.Whence.SET) fp.seekSet(p);
      else if (whence == InputArchive.Whence.END) fp.seekEnd(p);
      else fp.seekSet(fp.tell() + p);
    } else if (whence == InputArchive.Whence.SET) {
      off = p;
    } else {
      off += p;  // assume at end
    }
  }

  /** Returns the current file offset. */
  long tell() {
    if (fp != null) return fp.tell() + ptr;
    return off;
  }

  @Override
  public void put(int c) {
    if (fp == null) {
      ++off;
    } else {
      if (ptr >= BUFSIZE) flush();
      buf[ptr++] = (byte) c;
    }
  }

  @Override
  public void write(byte[] ibuf, int boff, int len) {
    if (fp == null) {
      off += len;
      return;
    }
    while (len > 0) {
      if (ptr >= BUFSIZE) flush();
      int n = Math.min(len, BUFSIZE - ptr);
      System.arraycopy(ibuf, boff, buf, ptr, n);
      ptr += n;
      boff += n;
      len -= n;
    }
  }

  /**
   * Writes bytes that are already encrypted (a copy of the start of an archive with the same
   * salt) and counts them.
   */
  void writeRaw(byte[] ibuf, int boff, int len) {
    flush();
    fp.write(ibuf, boff, len);
  }

  /** Drops the output if nothing has reached the storage yet (an empty update). */
  boolean discard() {
    if (fp == null) return true;
    boolean d = fp.discard();
    if (d) {
      fp = null;
      ptr = 0;
    }
    return d;
  }

  /** Stops after an error without committing a partial file where the storage allows it. */
  void abort() {
    if (fp != null) fp.abort();
    fp = null;
  }

  /** Truncates the file to {@code len} bytes. */
  void truncate(long len) {
    flush();
    fp.truncate(len);
  }

  /** Flushes output and closes. */
  @Override
  public void close() {
    if (fp != null) {
      flush();
      fp.close();
    }
    fp = null;
  }

  boolean isOpen() {
    return fp != null;
  }
}
