package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.libzpaq.Sha1;
import java.util.Arrays;

/**
 * Splits a file into fragments with a rolling hash over an order 1 prediction (the content
 * defined chunking of zpaq). Kept apart from {@link Adder#add} so that the hot loop is a small
 * method for the JIT.
 */
final class FragmentScanner {
  private static final int BUFSIZE = 1 << 16;  // input buffer

  final byte[] fragbuf;     // current fragment
  final byte[] o1 = new byte[256];  // order 1 context -> predicted byte
  int sz;                   // fragment size
  long hits;                // correct prediction count
  int text1;                // 1 if the last analyzed fragment looks like text
  int exe1;                 // 1 if it looks like x86 code

  private final byte[] buf = new byte[BUFSIZE];
  private int bufptr;
  private int buflen;
  private final long maxFragment;
  private final long minFragment;
  private final int hthresh;

  FragmentScanner(int maxFragment, int minFragment, int fragment) {
    this.maxFragment = maxFragment;
    this.minFragment = minFragment;
    this.fragbuf = new byte[maxFragment];
    this.hthresh = fragment <= 22 ? 1 << (22 - fragment) : 0;
  }

  /** Discards buffered input before reading from a new file. */
  void newFile() {
    bufptr = buflen = 0;
  }

  /**
   * Reads the next fragment of {@code in} into {@link #fragbuf} and hashes it into {@code sha1}.
   * Returns -1 if the end of the file was reached, else 0.
   */
  int scan(ArchiveFile in, Sha1 sha1) {
    Arrays.fill(o1, (byte) 0);
    sz = 0;
    hits = 0;
    int c1 = 0;  // previous byte
    int h = 0;   // rolling hash for finding fragment boundaries
    int c = 0;
    while (true) {
      if (bufptr >= buflen) {
        bufptr = 0;
        buflen = in.read(buf, 0, BUFSIZE);
      }
      if (bufptr >= buflen) {
        c = -1;
        break;
      }
      // scan up to the end of the buffer or the maximum fragment size
      final int start = bufptr;
      final int end = (int) Math.min(buflen, start + (maxFragment - sz));
      int j = start;
      int hh = h;
      int cc1 = c1;
      long hit = 0;
      // sz + (j - start) is the fragment size after consuming byte j
      final int minj = (int) Math.max(start, start + minFragment - sz - 1);
      boolean cut = false;
      for (; j < end; ) {
        final int cx = buf[j++] & 255;
        if (cx == (o1[cc1] & 255)) {
          hh = (hh + cx + 1) * 314159265;
          ++hit;
        } else {
          hh = (hh + cx + 1) * 271828182;
        }
        o1[cc1] = (byte) cx;
        cc1 = cx;
        if (j > minj && Integer.compareUnsigned(hh, hthresh) < 0) {
          cut = true;
          break;
        }
      }
      System.arraycopy(buf, start, fragbuf, sz, j - start);
      sz += j - start;
      bufptr = j;
      h = hh;
      c1 = cc1;
      hits += hit;
      if (cut || sz >= maxFragment) break;
    }
    sha1.write(fragbuf, 0, sz);
    return c;
  }

  /**
   * Analyzes a fragment (order 1 table {@code o1}, size {@code sz}, {@code hits} correct
   * predictions) for redundancy, x86 and text. Returns the redundancy estimate and sets
   * {@link #text1} and {@link #exe1}. Test for text: letters, digits, '.' and ',' followed by
   * spaces and no invalid UTF-8. Test for exe: 139 (mov reg, r/m) in lots of contexts. 4 tests for
   * redundancy, measured as hits/sz. Take the highest of:
   *   1. Successful prediction count in o1.
   *   2. Non-uniform distribution in o1 (counted in o2).
   *   3. Fraction of zeros in o1 (bytes never seen).
   *   4. Fraction of matches between o1 and previous o1 (o1prev).
   */
  long analyze(byte[] o1, int sz, long hits, byte[] o1prev, int on, int[] dtTable) {
    int text = 0;
    int exe = 0;
    long h1 = sz;
    int[] o1ct = new int[256];  // counts of bytes in o1
    for (int i = 0; i < 256; ++i) {
      final int o1i = o1[i] & 255;
      if (o1ct[o1i] < 255) h1 -= ((long) sz * dtTable[o1ct[o1i]++]) >> 15;
      if (o1i == ' ' && (Strs.isalnum(i) || i == '.' || i == ',')) ++text;
      if (o1i != 0 && (i < 9 || i == 11 || i == 12 || (i >= 14 && i <= 31) || i >= 240)) --text;
      if (i >= 192 && i < 240 && o1i != 0 && (o1i < 128 || o1i >= 192)) --text;
      if (o1i == 139) ++exe;
    }
    text1 = text >= 3 ? 1 : 0;
    exe1 = exe >= 5 ? 1 : 0;
    if (sz > 0) h1 = h1 * h1 / sz;  // Test 2: near 0 if random.
    long h2 = h1 & 0xFFFFFFFFL;
    if (h2 > hits) hits = h2;
    h2 = (long) o1ct[0] * sz / 256;  // Test 3: bytes never seen or that predict 0.
    if (h2 > hits) hits = h2;
    h2 = 0;
    for (int i = 0; i < 256 * on; ++i)  // Test 4: compare to previous o1.
      h2 += o1prev[i] == o1[i & 255] ? 1 : 0;
    h2 = h2 * sz / (256 * on);
    if (h2 > hits) hits = h2;
    if (hits > sz) hits = sz;
    return hits;
  }
}
