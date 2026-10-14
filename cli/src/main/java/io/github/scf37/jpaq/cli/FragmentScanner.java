package io.github.scf37.jpaq.cli;

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
}
