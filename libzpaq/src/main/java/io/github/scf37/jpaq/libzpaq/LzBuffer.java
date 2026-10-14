package io.github.scf37.jpaq.libzpaq;

/**
 * LZ77/BWT preprocessor for the built in compression methods (libzpaq LZBuffer). It reads the
 * input block and provides the transformed data as a {@link ZpaqReader}.
 *
 * <p>Arguments (as parsed from the method string "x" args):
 *
 * <ul>
 *   <li>args[0] log2 buffer size in MB
 *   <li>args[1] level (1=var. length, 2=byte aligned lz77, 3=bwt) + 4 if E8E9
 *   <li>args[2] the lz77 minimum match length and context order
 *   <li>args[3] the lz77 higher context order to search first, or else 0
 *   <li>args[4] the log2 hash bucket size (number of searches)
 *   <li>args[5] the log2 hash table size. If 21+args[0] then use a suffix array
 *   <li>args[6] the secondary context look ahead
 * </ul>
 *
 * <p>Level 1 uses variable length LZ77 codes: {@code 00,n,L[n]} = n literal bytes;
 * {@code mm,mmm,n,ll,r,q} (mm &gt; 00) = match 4*n+ll at offset (q&lt;&lt;rb)+r-1. Level 2 is byte
 * oriented LZ77 and level 3 is BWT with the end of string byte coded as 255 and the last 4 bytes
 * giving its position LSB first.
 */
final class LzBuffer implements ZpaqReader {
  private static final int BUFSIZE = 1 << 14;

  private final int[] ht;           // hash table, confirm in low bits
  private final byte[] in;          // input
  private final int checkbits;      // hash confirmation size or lg(ISA size)
  private final int level;          // 1=var length LZ77, 2=byte aligned LZ77, 3=BWT
  private final int htsize;         // size of hash table
  private final int n;              // input length
  private int i;                    // current location in in (0 <= i < n)
  private final int minMatch;       // minimum match length
  private final int minMatch2;      // second context order or 0 if not used
  private final int maxMatch;       // longest match length allowed
  private final int maxLiteral;     // longest literal length allowed
  private final int lookahead;      // second context look ahead
  private int h1;
  private int h2;                   // low, high order context hashes of in[i..]
  private final int bucket;         // number of matches to search per hash - 1
  private final int shift1;
  private final int shift2;         // how far to shift h1, h2 per hash
  private final int minMatchBoth;   // max(minMatch, minMatch2)
  private final int rb;             // number of level 1 r bits in match code
  private int bits;                 // pending output bits (level 1)
  private int nbits;                // number of bits in bits
  private int rpos;
  private int wpos;                 // read, write pointers
  private int idx;                  // BWT index
  private int[] sa;                 // suffix array for BWT or LZ77-SA
  private int[] isa;                // inverse suffix array for LZ77-SA
  private final byte[] buf = new byte[BUFSIZE];  // output buffer
  private final int[] cand;         // match candidates of one position
  private int touch;                // keeps the early reads of the candidates alive

  /**
   * @param inbuf input block; the E8E9 transform is applied to it in place if args[1] &gt; 4
   * @param args arguments as described above
   */
  LzBuffer(MemoryBuffer inbuf, int[] args) {
    n = inbuf.size();
    in = inbuf.data();
    level = args[1] & 3;
    minMatch = args[2];
    minMatch2 = args[3];
    maxMatch = BUFSIZE * 3;
    maxLiteral = BUFSIZE / 4;
    lookahead = args[6];
    bucket = (1 << args[4]) - 1;
    cand = new int[bucket + 1];
    final boolean sufArray = args[5] - args[0] >= 21;
    checkbits = !sufArray ? 12 - args[0] : 17 + args[0];
    long htlen;
    if ((args[1] & 3) == 3) htlen = 0;                    // BWT uses sa only
    else if (!sufArray) htlen = 1L << args[5];            // LZ77 hash table
    else htlen = 0;                                       // LZ77 SA and ISA
    if (htlen > MemoryBuffer.MAX_SIZE) throw new OutOfMemoryError("Out of memory");
    ht = ArrayPool.ints((int) htlen);
    htsize = ht.length;
    shift1 = minMatch > 0 ? (args[5] - 1) / minMatch + 1 : 1;
    shift2 = minMatch2 > 0 ? (args[5] - 1) / minMatch2 + 1 : 0;
    minMatchBoth = Math.max(minMatch, minMatch2 + lookahead) + 4;
    rb = args[0] > 4 ? args[0] - 4 : 0;
    if ((minMatch < 4 && level == 1) || (minMatch < 1 && level == 2))
      throw ZpaqException.error("match length $3 too small");

    // e8e9 transform
    if (args[1] > 4) E8E9.transform(in, n);

    // build suffix array
    if (sufArray || level == 3) {  // LZ77-SA or BWT
      sa = ArrayPool.ints(level == 3 ? n + 1 : n);
      if (n > 0) SuffixArray.build(in, n, sa);
      if (level < 3) {
        long isalen = 1L << 17 << args[0];
        if (isalen > MemoryBuffer.MAX_SIZE) throw new OutOfMemoryError("Out of memory");
        isa = ArrayPool.ints((int) isalen);
      }
    }
  }

  /** Returns the work arrays to the pool; the object must not be used afterwards. */
  void release() {
    ArrayPool.release(ht);
    ArrayPool.release(sa);
    ArrayPool.release(isa);
    sa = null;
    isa = null;
  }

  // write k bits of x
  private void putb(int x, int k) {
    x &= (1 << k) - 1;
    bits |= x << nbits;
    nbits += k;
    while (nbits > 7) {
      buf[wpos++] = (byte) bits;
      bits >>>= 8;
      nbits -= 8;
    }
  }

  // write last byte
  private void flush() {
    if (nbits > 0) buf[wpos++] = (byte) bits;
    bits = nbits = 0;
  }

  // write 1 byte
  private void put(int c) {
    buf[wpos++] = (byte) c;
  }

  @Override
  public int get() {
    int c = -1;
    if (rpos == wpos) fill();
    if (rpos < wpos) c = buf[rpos++] & 255;
    if (rpos == wpos) rpos = wpos = 0;
    return c;
  }

  @Override
  public int read(byte[] p, int off, int len) {
    if (rpos == wpos) fill();
    int nr = len;
    if (nr > wpos - rpos) nr = wpos - rpos;
    if (nr > 0) System.arraycopy(buf, rpos, p, off, nr);
    rpos += nr;
    if (rpos == wpos) rpos = wpos = 0;
    return nr;
  }

  // floor(log2(x)) + 1 = number of bits excluding leading zeros (0..32)
  static int lg(int x) {
    return 32 - Integer.numberOfLeadingZeros(x);
  }

  // in[idx] as unsigned, 0 outside the array
  private int at(int idx) {
    return idx >= 0 && idx < in.length ? in[idx] & 255 : 0;
  }

  // Encode from in to buf until end of input or buf is not empty
  private void fill() {

    // BWT
    if (level == 3) {
      for (; wpos < BUFSIZE && i < n + 5; ++i) {
        if (i == 0) put(n > 0 ? in[n - 1] : 255);
        else if (i > n) {
          put(idx & 255);
          idx >>>= 8;
        } else if (sa[i - 1] == 0) {
          idx = i;
          put(255);
        } else {
          put(in[sa[i - 1] - 1]);
        }
      }
      return;
    }

    // LZ77: scan the input
    int lit = 0;  // number of output literals pending
    final int mask = (1 << checkbits) - 1;
    while (i < n && wpos * 2 < BUFSIZE) {

      // Search for longest match, or pick closest in case of tie
      int blen = minMatch - 1;  // best match length
      int bp = 0;               // pointer to best match
      int blit = 0;             // literals before best match
      int bscore = 0;           // best cost

      // Look up contexts in suffix array
      if (isa != null) {
        if (sa[isa[i & mask]] != i) {  // rebuild ISA
          for (int j = 0; j < n; ++j)
            if ((sa[j] & ~mask) == (i & ~mask)) isa[sa[j] & mask] = j;
        }
        for (int h = 0; h <= lookahead; ++h) {
          int q = isa[(h + i) & mask];  // location of h+i in SA
          if (sa[q] != h + i) continue;
          for (int j = -1; j <= 1; j += 2) {  // search backward and forward
            for (int k = 1; k <= bucket; ++k) {
              long qq = (long) q + (long) j * k;
              int p;  // match to be tested
              if (qq >= 0 && qq < n && Integer.compareUnsigned(p = sa[(int) qq] - h, i) < 0) {
                int l;   // length of match
                int l1;  // leading literals
                for (l = h; i + l < n && l < maxMatch && in[p + l] == in[i + l]; ++l) { }
                for (l1 = h; l1 > 0 && in[p + l1 - 1] == in[i + l1 - 1]; --l1) { }
                int score = (l - l1) * 8 - lg(i - p) - 4 * (lit == 0 && l1 > 0 ? 1 : 0) - 11;
                for (int a = 0; a < h; ++a) score = score * 5 / 8;
                if (score > bscore) {
                  blen = l;
                  bp = p;
                  blit = l1;
                  bscore = score;
                }
                if (l < blen || l < minMatch || l > 255) break;
              }
            }
          }
          if (bscore <= 0 || blen < minMatch) break;
        }
      } else if (level == 1 || minMatch <= 64) {
        // Look up contexts in a hash table. Try the longest context orders first. If a match is
        // found, then skip the lower order as a speed optimization.
        if (minMatch2 > 0) {
          for (int k = 0; k <= bucket; ++k) {
            int p = ht[h2 ^ k];
            if (p != 0 && (p & mask) == (at(i + 3) & mask)) {
              p >>>= checkbits;
              if (Integer.compareUnsigned(p, i) < 0 && i + blen <= n
                  && at(p + blen - 1) == at(i + blen - 1)) {
                int l;  // match length from lookahead
                for (l = lookahead; i + l < n && l < maxMatch && in[p + l] == in[i + l]; ++l) { }
                if (l >= minMatch2 + lookahead) {
                  int l1;  // length back from lookahead
                  for (l1 = lookahead; l1 > 0 && in[p + l1 - 1] == in[i + l1 - 1]; --l1) { }
                  int score = (l - l1) * 8 - lg(i - p) - 8 * (lit == 0 && l1 > 0 ? 1 : 0) - 11;
                  if (score > bscore) {
                    blen = l;
                    bp = p;
                    blit = l1;
                    bscore = score;
                  }
                }
              }
            }
            if (blen >= 128) break;
          }
        }

        // Search the lower order context. First collect the candidates whose check bits agree and
        // touch their data, so that the cache misses of all candidates overlap.
        if (minMatch2 == 0 || blen < minMatch2) {
          int cnt = 0;
          int tch = 0;
          if (i + 3 < n) {
            final int chk = in[i + 3] & 255 & mask;
            for (int k = 0; k <= bucket; ++k) {
              final int p = ht[h1 ^ k];
              if (p != 0 && (p & mask) == chk) {
                final int q = p >>> checkbits;
                cand[cnt++] = q;
                tch += in[q < n ? q : 0];
              }
            }
          }
          touch += tch;
          for (int c = 0; c < cnt; ++c) {
            final int p = cand[c];
            if (Integer.compareUnsigned(p, i) < 0 && i + blen <= n
                && at(p + blen - 1) == at(i + blen - 1)) {
              int l;
              for (l = 0; i + l < n && l < maxMatch && in[p + l] == in[i + l]; ++l) { }
              int score = l * 8 - lg(i - p) - 2 * (lit > 0 ? 1 : 0) - 11;
              if (score > bscore) {
                blen = l;
                bp = p;
                blit = 0;
                bscore = score;
              }
            }
            if (blen >= 128) break;
          }
        }
      }

      // If match is long enough, then output any pending literals first, and then the match.
      // blen is the length of the match.
      final int off = i - bp;  // offset
      if (off > 0 && bscore > 0
          && blen - blit >= minMatch + (level == 2
              ? ((off >= (1 << 16) ? 1 : 0) + (off >= (1 << 24) ? 1 : 0)) : 0)) {
        lit += blit;
        lit = writeLiteral(i + blit, lit);
        writeMatch(blen - blit, off);
      } else {
        // Otherwise add to literal length
        blen = 1;
        ++lit;
      }

      // Update index, advance blen bytes
      if (isa != null) {
        i += blen;
      } else {
        while (blen-- > 0) {
          if (i + minMatchBoth < n) {
            int ih = ((i * 1234547) >>> 19) & bucket;
            final int p = (i << checkbits) | (in[i + 3] & 255 & mask);
            if (minMatch2 != 0) {
              ht[h2 ^ ih] = p;
              h2 = (((h2 * 9) << shift2)
                  + ((in[i + minMatch2 + lookahead] & 255) + 1) * 23456789) & (htsize - 1);
            }
            ht[h1 ^ ih] = p;
            h1 = (((h1 * 5) << shift1) + ((in[i + minMatch] & 255) + 1) * 123456791)
                & (htsize - 1);
          }
          ++i;
        }
      }

      // Write long literals to keep buf from filling up
      if (lit >= maxLiteral) lit = writeLiteral(i, lit);
    }

    // Write pending literals at end of input
    if (i == n) {
      lit = writeLiteral(n, lit);
      flush();
    }
  }

  // Write literal sequence in[i-lit..i-1], return 0 (new lit)
  private int writeLiteral(int i, int lit) {
    if (level == 1) {
      if (lit < 1) return lit;
      int ll = lg(lit);
      putb(0, 2);
      --ll;
      while (--ll >= 0) {
        putb(1, 1);
        putb((lit >>> ll) & 1, 1);
      }
      putb(0, 1);
      while (lit != 0) putb(in[i - lit--] & 255, 8);
    } else {
      while (lit > 0) {
        int lit1 = lit;
        if (lit1 > 64) lit1 = 64;
        put(lit1 - 1);
        for (int j = i - lit; j < i - lit + lit1; ++j) put(in[j]);
        lit -= lit1;
      }
    }
    return 0;
  }

  // Write match sequence of given length and offset
  private void writeMatch(int len, int off) {

    // mm,mmm,n,ll,r,q[mmmmm-8] = match n*4+ll, offset ((q-1)<<rb)+r+1
    if (level == 1) {
      int ll = lg(len) - 1;
      off += (1 << rb) - 1;
      int lo = lg(off) - 1 - rb;
      putb((lo + 8) >> 3, 2);  // mm
      putb(lo & 7, 3);         // mmm
      while (--ll >= 2) {      // n
        putb(1, 1);
        putb((len >>> ll) & 1, 1);
      }
      putb(0, 1);
      putb(len & 3, 2);        // ll
      putb(off, rb);           // r
      putb(off >>> rb, lo);    // q
    } else {
      // x[2]:len[6] off[x-1]
      --off;
      while (len > 0) {  // Split long matches to len1=minMatch..minMatch+63
        final int len1 = len > minMatch * 2 + 63 ? minMatch + 63
            : len > minMatch + 63 ? len - minMatch : len;
        if (off < (1 << 16)) {
          put(64 + len1 - minMatch);
          put(off >>> 8);
          put(off);
        } else if (off < (1 << 24)) {
          put(128 + len1 - minMatch);
          put(off >>> 16);
          put(off >>> 8);
          put(off);
        } else {
          put(192 + len1 - minMatch);
          put(off >>> 24);
          put(off >>> 16);
          put(off >>> 8);
          put(off);
        }
        len -= len1;
      }
    }
  }
}
