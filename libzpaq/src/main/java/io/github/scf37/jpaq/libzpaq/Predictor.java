package io.github.scf37.jpaq.libzpaq;

import java.util.Arrays;

/**
 * Predicts the next bit using the context mixing model described by the COMP section of a
 * ZPAQ block header (libzpaq::Predictor).
 */
final class Predictor {
  /** One model component with its state. */
  private static final class Component {
    int type;
    int c1;
    int c2;
    int c3;
    int c4;
    int c5;
    int limit;
    int cxt;
    int a;
    int b;
    int c;
    int[] cm;
    byte[] ht;
    char[] a16;
  }

  private int c8 = 1;     // last 0...7 bits with a leading 1
  private int hmap4 = 1;  // c8 split into nibbles
  private final int[] p = new int[256];  // predictions
  private final int[] h = new int[256];  // unrolled copy of z.h
  private final Zpaql z;
  private final Component[] comp = new Component[256];
  private int n;

  Predictor(Zpaql z) {
    this.z = z;
  }

  boolean isModeled() {
    return z.header[6] != 0;
  }

  private static int[] ints(int bits, int mul) {
    long len = (long) mul << bits;
    if (bits > 40) throw ZpaqException.error("Array too big");
    if (bits > 31 || len > MemoryBuffer.MAX_SIZE) throw new OutOfMemoryError("Out of memory");
    return new int[(int) len];
  }

  private static byte[] bytes(int bits, int mul) {
    long len = (long) mul << bits;
    if (bits > 40) throw ZpaqException.error("Array too big");
    if (bits > 31 || len > MemoryBuffer.MAX_SIZE) throw new OutOfMemoryError("Out of memory");
    return new byte[(int) len];
  }

  private static int cminit(int state) {
    return ((Tables.SNS[state * 4 + 3] * 2 + 1) << 22)
        / (Tables.SNS[state * 4 + 2] + Tables.SNS[state * 4 + 3] + 1);
  }

  private static int squash(int x) {
    return Tables.SQUASH[x + 2048];
  }

  private static int stretch(int x) {
    return Tables.STRETCH[x];
  }

  private static int clamp2k(int x) {
    return x < -2048 ? -2048 : Math.min(x, 2047);
  }

  private static int clamp512k(int x) {
    return x < -(1 << 19) ? -(1 << 19) : Math.min(x, (1 << 19) - 1);
  }

  /** Initializes the predictor with the model in z. */
  void init() {
    z.inith();
    Arrays.fill(h, 0);
    Arrays.fill(p, 0);
    Arrays.fill(comp, null);
    n = z.header[6] & 255;
    final byte[] hd = z.header;
    int cp = 7;
    for (int i = 0; i < n; i++) {
      Component cr = comp[i] = new Component();
      int type = hd[cp] & 255;
      cr.type = type;
      cr.c1 = hd[cp + 1] & 255;
      cr.c2 = hd[cp + 2] & 255;
      cr.c3 = hd[cp + 3] & 255;
      cr.c4 = hd[cp + 4] & 255;
      cr.c5 = hd[cp + 5] & 255;
      switch (type) {
        case Components.CONS -> p[i] = (cr.c1 - 128) * 4;
        case Components.CM -> {
          if (cr.c1 > 32) throw ZpaqException.error("max size for CM is 32");
          cr.cm = ints(cr.c1, 1);
          cr.limit = cr.c2 * 4;
          Arrays.fill(cr.cm, 0x80000000);
        }
        case Components.ICM -> {
          if (cr.c1 > 26) throw ZpaqException.error("max size for ICM is 26");
          cr.limit = 1023;
          cr.cm = new int[256];
          cr.ht = bytes(cr.c1, 64);
          for (int j = 0; j < 256; j++) cr.cm[j] = cminit(j);
        }
        case Components.MATCH -> {
          if (cr.c1 > 32 || cr.c2 > 32) throw ZpaqException.error("max size for MATCH is 32 32");
          cr.cm = ints(cr.c1, 1);
          cr.ht = bytes(cr.c2, 1);
          cr.ht[0] = 1;
        }
        case Components.AVG -> {
          if (cr.c1 >= i) throw ZpaqException.error("AVG j >= i");
          if (cr.c2 >= i) throw ZpaqException.error("AVG k >= i");
        }
        case Components.MIX2 -> {
          if (cr.c1 > 32) throw ZpaqException.error("max size for MIX2 is 32");
          if (cr.c3 >= i) throw ZpaqException.error("MIX2 k >= i");
          if (cr.c2 >= i) throw ZpaqException.error("MIX2 j >= i");
          cr.c = 1 << cr.c1;
          char[] w;
          if (cr.c1 > 30) throw new OutOfMemoryError("Out of memory");
          w = new char[1 << cr.c1];
          Arrays.fill(w, (char) 32768);
          cr.a16 = w;
        }
        case Components.MIX -> {
          if (cr.c1 > 32) throw ZpaqException.error("max size for MIX is 32");
          if (cr.c2 >= i) throw ZpaqException.error("MIX j >= i");
          if (cr.c3 < 1 || cr.c3 > i - cr.c2) throw ZpaqException.error("MIX m not in 1..i-j");
          int m = cr.c3;
          cr.c = 1 << cr.c1;
          cr.cm = ints(cr.c1, m);
          Arrays.fill(cr.cm, 65536 / m);
        }
        case Components.ISSE -> {
          if (cr.c1 > 32) throw ZpaqException.error("max size for ISSE is 32");
          if (cr.c2 >= i) throw ZpaqException.error("ISSE j >= i");
          cr.ht = bytes(cr.c1, 64);
          cr.cm = new int[512];
          for (int j = 0; j < 256; j++) {
            cr.cm[j * 2] = 1 << 15;
            cr.cm[j * 2 + 1] = clamp512k(stretch(cminit(j) >> 8) * 1024);
          }
        }
        case Components.SSE -> {
          if (cr.c1 > 32) throw ZpaqException.error("max size for SSE is 32");
          if (cr.c2 >= i) throw ZpaqException.error("SSE j >= i");
          if (cr.c3 > cr.c4 * 4) throw ZpaqException.error("SSE start > limit*4");
          cr.cm = ints(cr.c1, 32);
          cr.limit = cr.c4 * 4;
          for (int j = 0; j < cr.cm.length; j++)
            cr.cm[j] = squash((j & 31) * 64 - 992) << 17 | cr.c3;
        }
        default -> throw ZpaqException.error("unknown component type");
      }
      cp += Components.size(type);
    }
  }

  /** Returns the probability that the next bit is a 1 (0..32767). */
  int predict() {
    final int n = this.n;
    final int c8 = this.c8;
    final int hmap4 = this.hmap4;
    final int[] p = this.p;
    final int[] h = this.h;
    for (int i = 0; i < n; i++) {
      final Component cr = comp[i];
      switch (cr.type) {
        case Components.CONS -> { }
        case Components.CM -> {
          cr.cxt = h[i] ^ hmap4;
          p[i] = stretch(cr.cm[cr.cxt & (cr.cm.length - 1)] >>> 17);
        }
        case Components.ICM -> {
          if (c8 == 1 || (c8 & 0xf0) == 16) cr.c = find(cr.ht, cr.c1 + 2, h[i] + 16 * c8);
          cr.cxt = cr.ht[cr.c + (hmap4 & 15)] & 255;
          p[i] = stretch(cr.cm[cr.cxt] >>> 8);
        }
        case Components.MATCH -> {
          if (cr.a == 0) {
            p[i] = 0;
          } else {
            byte[] ht = cr.ht;
            cr.c = ((ht[(cr.limit - cr.b) & (ht.length - 1)] & 255) >> (7 - cr.cxt)) & 1;
            p[i] = stretch(Tables.SDT2K[cr.a] * (cr.c * -2 + 1) & 32767);
          }
        }
        case Components.AVG ->
            p[i] = (p[cr.c1] * cr.c3 + p[cr.c2] * (256 - cr.c3)) >> 8;
        case Components.MIX2 -> {
          cr.cxt = (h[i] + (c8 & cr.c5)) & (cr.c - 1);
          int w = cr.a16[cr.cxt];
          p[i] = (w * p[cr.c2] + (65536 - w) * p[cr.c3]) >> 16;
        }
        case Components.MIX -> {
          int m = cr.c3;
          cr.cxt = h[i] + (c8 & cr.c5);
          cr.cxt = (cr.cxt & (cr.c - 1)) * m;
          int[] wt = cr.cm;
          int base = cr.cxt;
          int s = 0;
          int pj = cr.c2;
          for (int j = 0; j < m; j++) s += (wt[base + j] >> 8) * p[pj + j];
          p[i] = clamp2k(s >> 8);
        }
        case Components.ISSE -> {
          if (c8 == 1 || (c8 & 0xf0) == 16) cr.c = find(cr.ht, cr.c1 + 2, h[i] + 16 * c8);
          cr.cxt = cr.ht[cr.c + (hmap4 & 15)] & 255;
          int[] wt = cr.cm;
          p[i] = clamp2k((wt[cr.cxt * 2] * p[cr.c2] + wt[cr.cxt * 2 + 1] * 64) >> 16);
        }
        case Components.SSE -> {
          final int mask = cr.cm.length - 1;
          cr.cxt = (h[i] + c8) * 32;
          int pq = p[cr.c2] + 992;
          if (pq < 0) pq = 0;
          if (pq > 1983) pq = 1983;
          int wt = pq & 63;
          pq >>= 6;
          cr.cxt += pq;
          p[i] = stretch((((cr.cm[cr.cxt & mask] >>> 10) * (64 - wt))
              + ((cr.cm[(cr.cxt + 1) & mask] >>> 10) * wt)) >> 13);
          cr.cxt += wt >> 5;
        }
        default -> throw ZpaqException.error("component predict not implemented");
      }
    }
    return squash(p[n - 1]);
  }

  // Reduce prediction error in cr.cm[cr.cxt]
  private static void train(Component cr, int y) {
    int idx = cr.cxt & (cr.cm.length - 1);
    int pn = cr.cm[idx];
    int count = pn & 0x3ff;
    int error = y * 32767 - (pn >>> 17);
    cr.cm[idx] = pn + ((error * Tables.SDT[count] & -1024) + (count < cr.limit ? 1 : 0));
  }

  /** Updates the model with the coded bit y (0 or 1). */
  void update(int y) {
    final int n = this.n;
    final int hmap4 = this.hmap4;
    final int[] p = this.p;
    final int[] h = this.h;
    for (int i = 0; i < n; i++) {
      final Component cr = comp[i];
      switch (cr.type) {
        case Components.CONS -> { }
        case Components.CM -> train(cr, y);
        case Components.ICM -> {
          int hi = cr.c + (hmap4 & 15);
          cr.ht[hi] = (byte) Tables.SNS[(cr.ht[hi] & 255) * 4 + y];
          int pn = cr.cm[cr.cxt];
          cr.cm[cr.cxt] = pn + ((y * 32767 - (pn >>> 8)) >> 2);
        }
        case Components.MATCH -> {
          byte[] ht = cr.ht;
          final int hmask = ht.length - 1;
          if (cr.c != y) cr.a = 0;
          ht[cr.limit] = (byte) ((ht[cr.limit] & 255) * 2 + y);
          if (++cr.cxt == 8) {
            cr.cxt = 0;
            ++cr.limit;
            cr.limit &= (1 << cr.c2) - 1;
            int[] cm = cr.cm;
            int ci = h[i] & (cm.length - 1);
            if (cr.a == 0) {
              cr.b = cr.limit - cm[ci];
              if ((cr.b & hmask) != 0) {
                while (cr.a < 255
                    && ht[(cr.limit - cr.a - 1) & hmask] == ht[(cr.limit - cr.a - cr.b - 1) & hmask])
                  ++cr.a;
              }
            } else if (cr.a < 255) {
              cr.a++;
            }
            cm[ci] = cr.limit;
          }
        }
        case Components.AVG -> { }
        case Components.MIX2 -> {
          int err = ((y * 32767 - squash(p[i])) * cr.c4) >> 5;
          int w = cr.a16[cr.cxt];
          w += (err * (p[cr.c2] - p[cr.c3]) + (1 << 12)) >> 13;
          if (w < 0) w = 0;
          if (w > 65535) w = 65535;
          cr.a16[cr.cxt] = (char) w;
        }
        case Components.MIX -> {
          int m = cr.c3;
          int err = ((y * 32767 - squash(p[i])) * cr.c4) >> 4;
          int[] wt = cr.cm;
          int base = cr.cxt;
          int pj = cr.c2;
          for (int j = 0; j < m; j++)
            wt[base + j] = clamp512k(wt[base + j] + ((err * p[pj + j] + (1 << 12)) >> 13));
        }
        case Components.ISSE -> {
          int err = y * 32767 - squash(p[i]);
          int[] wt = cr.cm;
          int w0 = cr.cxt * 2;
          wt[w0] = clamp512k(wt[w0] + ((err * p[cr.c2] + (1 << 12)) >> 13));
          wt[w0 + 1] = clamp512k(wt[w0 + 1] + ((err + 16) >> 5));
          cr.ht[cr.c + (hmap4 & 15)] = (byte) Tables.SNS[cr.cxt * 4 + y];
        }
        case Components.SSE -> train(cr, y);
        default -> throw new AssertionError();
      }
    }

    // Save bit y in c8, hmap4
    c8 += c8 + y;
    if (c8 >= 256) {
      z.run(c8 - 256);
      this.hmap4 = 1;
      c8 = 1;
      for (int i = 0; i < n; i++) h[i] = z.h(i);
    } else if (c8 >= 16 && c8 < 32) {
      this.hmap4 = (hmap4 & 0xf) << 5 | y << 4 | 1;
    } else {
      this.hmap4 = (hmap4 & 0x1f0) | (((hmap4 & 0xf) * 2 + y) & 0xf);
    }
    this.c8 = c8;
  }

  // Find cxt row in hash table ht. ht has rows of 16 indexed by the low sizebits of cxt with
  // element 0 having the next higher 8 bits for collision detection. If not found after 3
  // adjacent tries, replace the row with lowest element 1 as priority. Return index of row.
  private static int find(byte[] ht, int sizebits, int cxt) {
    int chk = (cxt >>> sizebits) & 255;
    int h0 = (cxt << 4) & (ht.length - 16);
    if ((ht[h0] & 255) == chk) return h0;
    int h1 = h0 ^ 16;
    if ((ht[h1] & 255) == chk) return h1;
    int h2 = h0 ^ 32;
    if ((ht[h2] & 255) == chk) return h2;
    int p0 = ht[h0 + 1] & 255;
    int p1 = ht[h1 + 1] & 255;
    int p2 = ht[h2 + 1] & 255;
    int r;
    if (p0 <= p1 && p0 <= p2) r = h0;
    else if (p1 < p2) r = h1;
    else r = h2;
    Arrays.fill(ht, r, r + 16, (byte) 0);
    ht[r] = (byte) chk;
    return r;
  }
}
