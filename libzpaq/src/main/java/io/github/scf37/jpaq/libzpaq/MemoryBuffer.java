package io.github.scf37.jpaq.libzpaq;

import java.util.Arrays;

/**
 * A growable byte buffer that is both a {@link ZpaqReader} and a {@link ZpaqWriter}
 * (libzpaq::StringBuffer). Writing appends bytes which can later be read back. The
 * contents are also accessible directly through {@link #data()}.
 */
public final class MemoryBuffer implements ZpaqReader, ZpaqWriter {
  /** Largest array size this buffer will allocate. */
  static final int MAX_SIZE = Integer.MAX_VALUE - 8;

  private byte[] p = EMPTY;
  private int wpos;
  private int rpos;
  private long limit = Long.MAX_VALUE;
  private final int init;

  private static final byte[] EMPTY = new byte[0];

  public MemoryBuffer() {
    this(0);
  }

  /** @param n initial allocation after first use (at least 128 is used) */
  public MemoryBuffer(int n) {
    this.init = Math.max(n, 128);
  }

  /** Sets the maximum size; writing past it throws {@link ZpaqException}. */
  public void setLimit(long n) {
    limit = n;
  }

  /** Number of bytes written. */
  public int size() {
    return wpos;
  }

  /** Number of bytes left to read before EOF. */
  public int remaining() {
    return wpos - rpos;
  }

  /** Discards the contents and frees the memory. */
  public void reset() {
    p = EMPTY;
    rpos = wpos = 0;
  }

  /**
   * Direct access to the backing array. Valid indexes are {@code 0..size()-1}; the array may
   * be longer. Writes may invalidate (replace) the array.
   */
  public byte[] data() {
    return p;
  }

  private void reserve(long a) {
    if (a <= p.length) return;
    if (a > MAX_SIZE) throw new OutOfMemoryError("Out of memory");
    p = Arrays.copyOf(p, (int) a);
  }

  private void lengthen(int n) {
    if ((long) wpos + n > limit || (long) wpos + n > MAX_SIZE) {
      if ((long) wpos + n > limit) throw ZpaqException.error("StringBuffer overflow");
      throw new OutOfMemoryError("Out of memory");
    }
    if (wpos + n <= p.length) return;
    long a = p.length;
    while (wpos + (long) n >= a) a = a * 2 + init;
    reserve(Math.min(a, MAX_SIZE));
  }

  /** Makes sure at least {@code n} bytes can be written without reallocation. */
  public void ensureCapacity(int n) {
    reserve(n);
  }

  /** Writes one byte. */
  @Override
  public void put(int c) {
    lengthen(1);
    p[wpos++] = (byte) c;
  }

  /** Writes {@code buf[off..off+n-1]}. */
  @Override
  public void write(byte[] buf, int off, int n) {
    if (n < 1) return;
    lengthen(n);
    System.arraycopy(buf, off, p, wpos, n);
    wpos += n;
  }

  /** Convenience for {@code write(buf, 0, buf.length)}. */
  public void write(byte[] buf) {
    write(buf, 0, buf.length);
  }

  /** Advances the write pointer by {@code n} bytes without storing data (contents undefined). */
  public void advance(int n) {
    if (n < 1) return;
    lengthen(n);
    wpos += n;
  }

  /** Appends the low {@code n} bytes of {@code x}, least significant byte first. */
  public void putLE(long x, int n) {
    for (; n > 0; n--) {
      put((int) (x & 255));
      x >>>= 8;
    }
  }

  /** Reads one byte, or -1 at the end. */
  @Override
  public int get() {
    return rpos < wpos ? p[rpos++] & 255 : -1;
  }

  /** Reads up to {@code n} bytes; returns 0 at EOF. {@code buf} may be null to skip. */
  @Override
  public int read(byte[] buf, int off, int n) {
    if (rpos + (long) n > wpos) n = wpos - rpos;
    if (n > 0 && buf != null) System.arraycopy(p, rpos, buf, off, n);
    if (n > 0) rpos += n;
    return Math.max(n, 0);
  }

  /** Truncates (or extends the write pointer) to size {@code i}. */
  public void resize(int i) {
    if (i > p.length) reserve(i);
    wpos = i;
    if (rpos > wpos) rpos = wpos;
  }

  /** Copy of the written contents. */
  public byte[] toByteArray() {
    return Arrays.copyOf(p, wpos);
  }

  /** Exchanges contents with {@code s} (the initial allocation size is not swapped). */
  public void swap(MemoryBuffer s) {
    byte[] tp = p;
    p = s.p;
    s.p = tp;
    int t = wpos;
    wpos = s.wpos;
    s.wpos = t;
    t = rpos;
    rpos = s.rpos;
    s.rpos = t;
    long tl = limit;
    limit = s.limit;
    s.limit = tl;
  }
}
