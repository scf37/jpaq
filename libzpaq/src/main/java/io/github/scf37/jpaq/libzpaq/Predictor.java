package io.github.scf37.jpaq.libzpaq;

import java.util.Arrays;

/**
 * Predicts the next bit using the context mixing model described by the COMP section of a
 * ZPAQ block header (libzpaq::Predictor).
 */
final class Predictor {
  /** One model component with its state. */
  static final class Component {
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

  /** Generated predict / update code specialized for one model. */
  interface Code {
    int predict(Predictor pr);

    void update(Predictor pr, int y);
  }

  int c8 = 1;     // last 0...7 bits with a leading 1
  int hmap4 = 1;  // c8 split into nibbles
  final int[] p = new int[256];  // predictions
  final int[] h = new int[256];  // unrolled copy of z.h
  private final Zpaql z;
  final Component[] comp = new Component[256];
  private int n;
  private Code code;

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
    c8 = 1;
    hmap4 = 1;
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
    code = PredictorJit.get(z.header, n);
  }

  /** Returns the probability that the next bit is a 1 (0..32767). */
  int predict() {
    if (code != null) return code.predict(this);
    final int n = this.n;
    final int c8 = this.c8;
    final int hmap4 = this.hmap4;
    final int[] p = this.p;
    final int[] h = this.h;
    for (int i = 0; i < n; i++) {
      final Component cr = comp[i];
      switch (cr.type) {
        case Components.CONS -> { }
        case Components.CM -> PredOps.predictCm(cr, p, h, i, hmap4);
        case Components.ICM -> PredOps.predictIcm(cr, p, h, i, c8, hmap4);
        case Components.MATCH -> PredOps.predictMatch(cr, p, i);
        case Components.AVG -> PredOps.predictAvg(cr, p, i);
        case Components.MIX2 -> PredOps.predictMix2(cr, p, h, i, c8);
        case Components.MIX -> PredOps.predictMix(cr, p, h, i, c8);
        case Components.ISSE -> PredOps.predictIsse(cr, p, h, i, c8, hmap4);
        case Components.SSE -> PredOps.predictSse(cr, p, h, i, c8);
        default -> throw ZpaqException.error("component predict not implemented");
      }
    }
    return PredOps.squash(p[n - 1]);
  }

  /** Updates the model with the coded bit y (0 or 1). */
  void update(int y) {
    if (code != null) {
      code.update(this, y);
      return;
    }
    final int n = this.n;
    final int hmap4 = this.hmap4;
    final int[] p = this.p;
    final int[] h = this.h;
    for (int i = 0; i < n; i++) {
      final Component cr = comp[i];
      switch (cr.type) {
        case Components.CONS, Components.AVG -> { }
        case Components.CM -> PredOps.updateCm(cr, y);
        case Components.ICM -> PredOps.updateIcm(cr, y, hmap4);
        case Components.MATCH -> PredOps.updateMatch(cr, h, i, y);
        case Components.MIX2 -> PredOps.updateMix2(cr, p, i, y);
        case Components.MIX -> PredOps.updateMix(cr, p, i, y);
        case Components.ISSE -> PredOps.updateIsse(cr, p, i, y, hmap4);
        case Components.SSE -> PredOps.updateSse(cr, y);
        default -> throw new AssertionError();
      }
    }
    endUpdate(y);
  }

  /** Saves bit y in c8, hmap4 and runs the HCOMP program at the end of a byte. */
  void endUpdate(int y) {
    int c8 = this.c8;
    final int hmap4 = this.hmap4;
    c8 += c8 + y;
    if (c8 >= 256) {
      z.run(c8 - 256);
      this.hmap4 = 1;
      c8 = 1;
      final int[] h = this.h;
      for (int i = 0; i < n; i++) h[i] = z.h(i);
    } else if (c8 >= 16 && c8 < 32) {
      this.hmap4 = (hmap4 & 0xf) << 5 | y << 4 | 1;
    } else {
      this.hmap4 = (hmap4 & 0x1f0) | (((hmap4 & 0xf) * 2 + y) & 0xf);
    }
    this.c8 = c8;
  }
}
