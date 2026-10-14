package io.github.scf37.jpaq.libzpaq;

import java.util.Arrays;

/**
 * Per component predict / update steps of {@link Predictor}. Small static methods so that the
 * JIT inlines them into the generated (per model) predictor code, where the component indices,
 * sizes and rates are constants.
 */
final class PredOps {
  private PredOps() {}

  static int squash(int x) {
    return Tables.SQUASH[x + 2048];
  }

  static int stretch(int x) {
    return Tables.STRETCH[x];
  }

  static int clamp2k(int x) {
    return x < -2048 ? -2048 : Math.min(x, 2047);
  }

  static int clamp512k(int x) {
    return x < -(1 << 19) ? -(1 << 19) : Math.min(x, (1 << 19) - 1);
  }

  // ---- CM
  static void predictCm(Predictor.Component cr, int[] p, int[] h, int i, int hmap4, int mask) {
    final int[] cm = cr.cm;
    int cxt = (h[i] ^ hmap4) & mask;
    cr.cxt = cxt;
    p[i] = stretch(cm[cxt] >>> 17);
  }

  static void updateCm(Predictor.Component cr, int y, int mask, int limit) {
    train(cr, y, mask, limit);
  }

  // Reduce prediction error in cr.cm[cr.cxt & mask]
  static void train(Predictor.Component cr, int y, int mask, int limit) {
    final int[] cm = cr.cm;
    int idx = cr.cxt & mask;
    int pn = cm[idx];
    int count = pn & 0x3ff;
    int error = y * 32767 - (pn >>> 17);
    cm[idx] = pn + ((error * Tables.SDT[count] & -1024) + (count < limit ? 1 : 0));
  }

  // ---- ICM
  static void predictIcm(Predictor.Component cr, int[] p, int[] h, int i, int c8, int hmap4,
      int sizebits) {
    if (c8 == 1 || (c8 & 0xf0) == 16) cr.c = find(cr.ht, sizebits, h[i] + 16 * c8);
    int cxt = cr.ht[cr.c + (hmap4 & 15)] & 255;
    cr.cxt = cxt;
    p[i] = stretch(cr.cm[cxt] >>> 8);
  }

  static void updateIcm(Predictor.Component cr, int y, int hmap4) {
    int hi = cr.c + (hmap4 & 15);
    final byte[] ht = cr.ht;
    ht[hi] = (byte) Tables.SNS[(ht[hi] & 255) * 4 + y];
    int pn = cr.cm[cr.cxt];
    cr.cm[cr.cxt] = pn + ((y * 32767 - (pn >>> 8)) >> 2);
  }

  // ---- MATCH
  static void predictMatch(Predictor.Component cr, int[] p, int i, int hmask) {
    if (cr.a == 0) {
      p[i] = 0;
    } else {
      byte[] ht = cr.ht;
      cr.c = ((ht[(cr.limit - cr.b) & hmask] & 255) >> (7 - cr.cxt)) & 1;
      p[i] = stretch(Tables.SDT2K[cr.a] * (cr.c * -2 + 1) & 32767);
    }
  }

  static void updateMatch(Predictor.Component cr, int[] h, int i, int y, int hmask, int cmask) {
    byte[] ht = cr.ht;
    if (cr.c != y) cr.a = 0;
    ht[cr.limit] = (byte) ((ht[cr.limit] & 255) * 2 + y);
    if (++cr.cxt == 8) {
      cr.cxt = 0;
      ++cr.limit;
      cr.limit &= hmask;
      int[] cm = cr.cm;
      int ci = h[i] & cmask;
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

  // ---- AVG
  static void predictAvg(int[] p, int i, int j, int k, int w) {
    p[i] = (p[j] * w + p[k] * (256 - w)) >> 8;
  }

  // ---- MIX2
  static void predictMix2(Predictor.Component cr, int[] p, int[] h, int i, int c8, int j, int k,
      int c5, int mask) {
    int cxt = (h[i] + (c8 & c5)) & mask;
    cr.cxt = cxt;
    int w = cr.a16[cxt];
    p[i] = (w * p[j] + (65536 - w) * p[k]) >> 16;
  }

  static void updateMix2(Predictor.Component cr, int[] p, int i, int y, int j, int k, int c4) {
    int err = ((y * 32767 - squash(p[i])) * c4) >> 5;
    int w = cr.a16[cr.cxt];
    w += (err * (p[j] - p[k]) + (1 << 12)) >> 13;
    if (w < 0) w = 0;
    if (w > 65535) w = 65535;
    cr.a16[cr.cxt] = (char) w;
  }

  // ---- MIX
  static void predictMix(Predictor.Component cr, int[] p, int[] h, int i, int c8, int m, int pj,
      int c5, int mask) {
    int cxt = h[i] + (c8 & c5);
    cxt = (cxt & mask) * m;
    cr.cxt = cxt;
    int[] wt = cr.cm;
    int s = 0;
    for (int j = 0; j < m; j++) s += (wt[cxt + j] >> 8) * p[pj + j];
    p[i] = clamp2k(s >> 8);
  }

  static void updateMix(Predictor.Component cr, int[] p, int i, int y, int m, int pj, int c4) {
    int err = ((y * 32767 - squash(p[i])) * c4) >> 4;
    int[] wt = cr.cm;
    int base = cr.cxt;
    for (int j = 0; j < m; j++)
      wt[base + j] = clamp512k(wt[base + j] + ((err * p[pj + j] + (1 << 12)) >> 13));
  }

  // ---- ISSE
  static void predictIsse(Predictor.Component cr, int[] p, int[] h, int i, int c8, int hmap4,
      int j, int sizebits) {
    if (c8 == 1 || (c8 & 0xf0) == 16) cr.c = find(cr.ht, sizebits, h[i] + 16 * c8);
    int cxt = cr.ht[cr.c + (hmap4 & 15)] & 255;
    cr.cxt = cxt;
    int[] wt = cr.cm;
    p[i] = clamp2k((wt[cxt * 2] * p[j] + wt[cxt * 2 + 1] * 64) >> 16);
  }

  static void updateIsse(Predictor.Component cr, int[] p, int i, int y, int hmap4, int j) {
    int err = y * 32767 - squash(p[i]);
    int[] wt = cr.cm;
    int w0 = cr.cxt * 2;
    wt[w0] = clamp512k(wt[w0] + ((err * p[j] + (1 << 12)) >> 13));
    wt[w0 + 1] = clamp512k(wt[w0 + 1] + ((err + 16) >> 5));
    cr.ht[cr.c + (hmap4 & 15)] = (byte) Tables.SNS[cr.cxt * 4 + y];
  }

  // ---- SSE
  static void predictSse(Predictor.Component cr, int[] p, int[] h, int i, int c8, int j, int mask) {
    final int[] cm = cr.cm;
    int cxt = (h[i] + c8) * 32;
    int pq = p[j] + 992;
    if (pq < 0) pq = 0;
    if (pq > 1983) pq = 1983;
    int wt = pq & 63;
    pq >>= 6;
    cxt += pq;
    p[i] = stretch((((cm[cxt & mask] >>> 10) * (64 - wt))
        + ((cm[(cxt + 1) & mask] >>> 10) * wt)) >> 13);
    cr.cxt = cxt + (wt >> 5);
  }

  static void updateSse(Predictor.Component cr, int y, int mask, int limit) {
    train(cr, y, mask, limit);
  }

  // Find cxt row in hash table ht. ht has rows of 16 indexed by the low sizebits of cxt with
  // element 0 having the next higher 8 bits for collision detection. If not found after 3
  // adjacent tries, replace the row with lowest element 1 as priority. Return index of row.
  static int find(byte[] ht, int sizebits, int cxt) {
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
