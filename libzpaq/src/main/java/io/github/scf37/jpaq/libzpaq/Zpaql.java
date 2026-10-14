package io.github.scf37.jpaq.libzpaq;

/**
 * A ZPAQL virtual machine: either COMP+HCOMP (computes contexts for the predictor) or PCOMP
 * (post-processor). Corresponds to libzpaq::ZPAQL. Programs are interpreted.
 *
 * <p>Registers A, B, C, D, R0..R255 are 32 bit (stored in {@code int} and treated as unsigned
 * where it matters). M is an array of 2^m bytes and H an array of 2^h 32 bit values.
 */
public final class Zpaql {
  /** Destination of the OUT instruction, or null to discard. */
  ZpaqWriter output;
  /** Checksum computer fed with all OUT bytes, or null. */
  Sha1 sha1;

  /** ZPAQ1 block header: hsize[2] hh hm ph pm n COMP (guard) HCOMP (guard). */
  byte[] header = new byte[0];
  /** COMP is in header[7..cend-1]. */
  int cend;
  /** HCOMP/PCOMP is in header[hbegin..hend-1]. */
  int hbegin;
  int hend;

  private byte[] m = new byte[0];
  private int mmask;
  private int[] h = new int[0];
  private int hmask;
  private int[] r = new int[0];
  private final byte[] outbuf = new byte[1 << 14];
  private int bufptr;
  private int a;
  private int b;
  private int c;
  private int d;
  private boolean f;
  private int pc;

  public Zpaql() {
    clear();
  }

  /** Frees memory, erases the program and resets the machine state. */
  void clear() {
    cend = hbegin = hend = 0;
    a = b = c = d = pc = 0;
    f = false;
    header = new byte[0];
    h = new int[0];
    m = new byte[0];
    r = new int[0];
  }

  public void setOutput(ZpaqWriter out) {
    output = out;
  }

  public void setSha1(Sha1 s) {
    sha1 = s;
  }

  /** Returns element {@code i} of H (index taken modulo the array size). */
  int h(int i) {
    return h[i & hmask];
  }

  /** Initializes as HCOMP to run. */
  void inith() {
    init(header[2] & 255, header[3] & 255);
  }

  /** Initializes as PCOMP to run. */
  void initp() {
    init(header[4] & 255, header[5] & 255);
  }

  private static byte[] newBytes(int bits) {
    if (bits > 30) throw new OutOfMemoryError("Out of memory");
    return new byte[1 << bits];
  }

  private void init(int hbits, int mbits) {
    if (hbits > 32) throw ZpaqException.error("H too big");
    if (mbits > 32) throw ZpaqException.error("M too big");
    if (hbits > 30 || mbits > 30) throw new OutOfMemoryError("Out of memory");
    h = new int[1 << hbits];
    hmask = h.length - 1;
    m = newBytes(mbits);
    mmask = m.length - 1;
    r = new int[256];
    a = b = c = d = pc = 0;
    f = false;
  }

  /** Writes pending output to the output writer and checksum. */
  void flush() {
    if (output != null) output.write(outbuf, 0, bufptr);
    if (sha1 != null) sha1.write(outbuf, 0, bufptr);
    bufptr = 0;
  }

  /** Pass-through output of byte ch (0..255) or -1 at end of segment. */
  void write1(int ch) {
    outc(ch);
  }

  /** Outputs byte ch (0..255) or -1 at end of segment. */
  private void outc(int ch) {
    if (ch < 0) {
      flush();
      return;
    }
    outbuf[bufptr] = (byte) ch;
    if (++bufptr == outbuf.length) flush();
  }

  /** Reads the header (hsize, hh hm ph pm n, COMP, HCOMP) from in2. Returns its size. */
  int read(ZpaqReader in2) {
    int hsize = in2.get();
    hsize += in2.get() * 256;
    header = new byte[Math.max(hsize, 0) + 300];
    cend = hbegin = hend = 0;
    header[cend++] = (byte) (hsize & 255);
    header[cend++] = (byte) (hsize >> 8);
    while (cend < 7) header[cend++] = (byte) in2.get();

    // Read COMP
    int n = header[cend - 1] & 255;
    for (int i = 0; i < n; i++) {
      int type = in2.get();
      if (type < 0 || type > 255) throw ZpaqException.error("unexpected end of file");
      header[cend++] = (byte) type;
      int size = Components.size(type);
      if (size < 1) throw ZpaqException.error("Invalid component type");
      if (cend + size > hsize) throw ZpaqException.error("COMP overflows header");
      for (int j = 1; j < size; j++) header[cend++] = (byte) in2.get();
    }
    if ((header[cend++] = (byte) in2.get()) != 0) throw ZpaqException.error("missing COMP END");

    // Insert a guard gap and read HCOMP
    hbegin = hend = cend + 128;
    if (hend > hsize + 129) throw ZpaqException.error("missing HCOMP");
    while (hend < hsize + 129) {
      int op = in2.get();
      if (op == -1) throw ZpaqException.error("unexpected end of file");
      header[hend++] = (byte) op;
    }
    if ((header[hend++] = (byte) in2.get()) != 0) throw ZpaqException.error("missing HCOMP END");
    return cend + hend - hbegin;
  }

  /**
   * Writes the header to out2. If {@code pp} then writes only the PCOMP size and code, else the
   * COMP and HCOMP sections. Returns false if there is no HCOMP/PCOMP section.
   */
  boolean write(ZpaqWriter out2, boolean pp) {
    if (header.length <= 6) return false;
    if (!pp) {
      for (int i = 0; i < cend; i++) out2.put(header[i]);
    } else {
      out2.put((hend - hbegin) & 255);
      out2.put((hend - hbegin) >> 8);
    }
    for (int i = hbegin; i < hend; i++) out2.put(header[i]);
    return true;
  }

  private static double pow2(int x) {
    double r = 1;
    for (; x > 0; x--) r += r;
    return r;
  }

  /** Returns the memory requirement in bytes. */
  double memory() {
    double mem = pow2((header[2] & 255) + 2) + pow2(header[3] & 255)
        + pow2((header[4] & 255) + 2) + pow2(header[5] & 255) + header.length;
    int cp = 7;
    for (int i = 0; i < (header[6] & 255); i++) {
      int type = header[cp] & 255;
      double size = pow2(header[cp + 1] & 255);
      switch (type) {
        case Components.CM -> mem += 4 * size;
        case Components.ICM -> mem += 64 * size + 1024;
        case Components.MATCH -> mem += 4 * size + pow2(header[cp + 2] & 255);
        case Components.MIX2 -> mem += 2 * size;
        case Components.MIX -> mem += 4 * size * (header[cp + 3] & 255);
        case Components.ISSE -> mem += 64 * size + 2048;
        case Components.SSE -> mem += 128 * size;
        default -> { }
      }
      cp += Components.size(type);
    }
    return mem;
  }

  /** Executes the program with the given input in A (0..255, or -1 for end of segment). */
  void run(int input) {
    pc = hbegin;
    a = input;
    while (execute()) { }
  }

  private int opnd() {
    return header[pc++] & 255;
  }

  private void err() {
    throw ZpaqException.error("ZPAQL execution error");
  }

  // Execute one instruction, return false after HALT.
  private boolean execute() {
    final byte[] hd = header;
    final byte[] m = this.m;
    final int[] h = this.h;
    final int mm = mmask;
    final int hm = hmask;
    switch (hd[pc++] & 255) {
      case 0 -> err();
      case 1 -> ++a;
      case 2 -> --a;
      case 3 -> a = ~a;
      case 4 -> a = 0;
      case 7 -> a = r[opnd()];
      case 8 -> { int t = a; a = b; b = t; }
      case 9 -> ++b;
      case 10 -> --b;
      case 11 -> b = ~b;
      case 12 -> b = 0;
      case 15 -> b = r[opnd()];
      case 16 -> { int t = a; a = c; c = t; }
      case 17 -> ++c;
      case 18 -> --c;
      case 19 -> c = ~c;
      case 20 -> c = 0;
      case 23 -> c = r[opnd()];
      case 24 -> { int t = a; a = d; d = t; }
      case 25 -> ++d;
      case 26 -> --d;
      case 27 -> d = ~d;
      case 28 -> d = 0;
      case 31 -> d = r[opnd()];
      case 32 -> { int i = b & mm; int t = m[i] & 255; m[i] = (byte) a; a = (a & ~255) | t; }
      case 33 -> m[b & mm]++;
      case 34 -> m[b & mm]--;
      case 35 -> m[b & mm] = (byte) ~m[b & mm];
      case 36 -> m[b & mm] = 0;
      case 39 -> { if (f) pc += (((hd[pc] & 255) + 128) & 255) - 127; else ++pc; }
      case 40 -> { int i = c & mm; int t = m[i] & 255; m[i] = (byte) a; a = (a & ~255) | t; }
      case 41 -> m[c & mm]++;
      case 42 -> m[c & mm]--;
      case 43 -> m[c & mm] = (byte) ~m[c & mm];
      case 44 -> m[c & mm] = 0;
      case 47 -> { if (!f) pc += (((hd[pc] & 255) + 128) & 255) - 127; else ++pc; }
      case 48 -> { int i = d & hm; int t = h[i]; h[i] = a; a = t; }
      case 49 -> h[d & hm]++;
      case 50 -> h[d & hm]--;
      case 51 -> h[d & hm] = ~h[d & hm];
      case 52 -> h[d & hm] = 0;
      case 55 -> r[opnd()] = a;
      case 56 -> { return false; }
      case 57 -> outc(a & 255);
      case 59 -> a = (a + (m[b & mm] & 255) + 512) * 773;
      case 60 -> { int i = d & hm; h[i] = (h[i] + a + 512) * 773; }
      case 63 -> pc += (((hd[pc] & 255) + 128) & 255) - 127;
      case 64 -> { }
      case 65 -> a = b;
      case 66 -> a = c;
      case 67 -> a = d;
      case 68 -> a = m[b & mm] & 255;
      case 69 -> a = m[c & mm] & 255;
      case 70 -> a = h[d & hm];
      case 71 -> a = opnd();
      case 72 -> b = a;
      case 73 -> { }
      case 74 -> b = c;
      case 75 -> b = d;
      case 76 -> b = m[b & mm] & 255;
      case 77 -> b = m[c & mm] & 255;
      case 78 -> b = h[d & hm];
      case 79 -> b = opnd();
      case 80 -> c = a;
      case 81 -> c = b;
      case 82 -> { }
      case 83 -> c = d;
      case 84 -> c = m[b & mm] & 255;
      case 85 -> c = m[c & mm] & 255;
      case 86 -> c = h[d & hm];
      case 87 -> c = opnd();
      case 88 -> d = a;
      case 89 -> d = b;
      case 90 -> d = c;
      case 91 -> { }
      case 92 -> d = m[b & mm] & 255;
      case 93 -> d = m[c & mm] & 255;
      case 94 -> d = h[d & hm];
      case 95 -> d = opnd();
      case 96 -> m[b & mm] = (byte) a;
      case 97 -> m[b & mm] = (byte) b;
      case 98 -> m[b & mm] = (byte) c;
      case 99 -> m[b & mm] = (byte) d;
      case 100 -> { }
      case 101 -> m[b & mm] = m[c & mm];
      case 102 -> m[b & mm] = (byte) h[d & hm];
      case 103 -> m[b & mm] = (byte) opnd();
      case 104 -> m[c & mm] = (byte) a;
      case 105 -> m[c & mm] = (byte) b;
      case 106 -> m[c & mm] = (byte) c;
      case 107 -> m[c & mm] = (byte) d;
      case 108 -> m[c & mm] = m[b & mm];
      case 109 -> { }
      case 110 -> m[c & mm] = (byte) h[d & hm];
      case 111 -> m[c & mm] = (byte) opnd();
      case 112 -> h[d & hm] = a;
      case 113 -> h[d & hm] = b;
      case 114 -> h[d & hm] = c;
      case 115 -> h[d & hm] = d;
      case 116 -> h[d & hm] = m[b & mm] & 255;
      case 117 -> h[d & hm] = m[c & mm] & 255;
      case 118 -> { }
      case 119 -> h[d & hm] = opnd();
      case 128 -> a += a;
      case 129 -> a += b;
      case 130 -> a += c;
      case 131 -> a += d;
      case 132 -> a += m[b & mm] & 255;
      case 133 -> a += m[c & mm] & 255;
      case 134 -> a += h[d & hm];
      case 135 -> a += opnd();
      case 136 -> a -= a;
      case 137 -> a -= b;
      case 138 -> a -= c;
      case 139 -> a -= d;
      case 140 -> a -= m[b & mm] & 255;
      case 141 -> a -= m[c & mm] & 255;
      case 142 -> a -= h[d & hm];
      case 143 -> a -= opnd();
      case 144 -> a *= a;
      case 145 -> a *= b;
      case 146 -> a *= c;
      case 147 -> a *= d;
      case 148 -> a *= m[b & mm] & 255;
      case 149 -> a *= m[c & mm] & 255;
      case 150 -> a *= h[d & hm];
      case 151 -> a *= opnd();
      case 152 -> a = div(a, a);
      case 153 -> a = div(a, b);
      case 154 -> a = div(a, c);
      case 155 -> a = div(a, d);
      case 156 -> a = div(a, m[b & mm] & 255);
      case 157 -> a = div(a, m[c & mm] & 255);
      case 158 -> a = div(a, h[d & hm]);
      case 159 -> a = div(a, opnd());
      case 160 -> a = mod(a, a);
      case 161 -> a = mod(a, b);
      case 162 -> a = mod(a, c);
      case 163 -> a = mod(a, d);
      case 164 -> a = mod(a, m[b & mm] & 255);
      case 165 -> a = mod(a, m[c & mm] & 255);
      case 166 -> a = mod(a, h[d & hm]);
      case 167 -> a = mod(a, opnd());
      case 168 -> { }
      case 169 -> a &= b;
      case 170 -> a &= c;
      case 171 -> a &= d;
      case 172 -> a &= m[b & mm] & 255;
      case 173 -> a &= m[c & mm] & 255;
      case 174 -> a &= h[d & hm];
      case 175 -> a &= opnd();
      case 176 -> a = 0;
      case 177 -> a &= ~b;
      case 178 -> a &= ~c;
      case 179 -> a &= ~d;
      case 180 -> a &= ~(m[b & mm] & 255);
      case 181 -> a &= ~(m[c & mm] & 255);
      case 182 -> a &= ~h[d & hm];
      case 183 -> a &= ~opnd();
      case 184 -> { }
      case 185 -> a |= b;
      case 186 -> a |= c;
      case 187 -> a |= d;
      case 188 -> a |= m[b & mm] & 255;
      case 189 -> a |= m[c & mm] & 255;
      case 190 -> a |= h[d & hm];
      case 191 -> a |= opnd();
      case 192 -> a = 0;
      case 193 -> a ^= b;
      case 194 -> a ^= c;
      case 195 -> a ^= d;
      case 196 -> a ^= m[b & mm] & 255;
      case 197 -> a ^= m[c & mm] & 255;
      case 198 -> a ^= h[d & hm];
      case 199 -> a ^= opnd();
      case 200 -> a <<= (a & 31);
      case 201 -> a <<= (b & 31);
      case 202 -> a <<= (c & 31);
      case 203 -> a <<= (d & 31);
      case 204 -> a <<= (m[b & mm] & 31);
      case 205 -> a <<= (m[c & mm] & 31);
      case 206 -> a <<= (h[d & hm] & 31);
      case 207 -> a <<= (opnd() & 31);
      case 208 -> a >>>= (a & 31);
      case 209 -> a >>>= (b & 31);
      case 210 -> a >>>= (c & 31);
      case 211 -> a >>>= (d & 31);
      case 212 -> a >>>= (m[b & mm] & 31);
      case 213 -> a >>>= (m[c & mm] & 31);
      case 214 -> a >>>= (h[d & hm] & 31);
      case 215 -> a >>>= (opnd() & 31);
      case 216 -> f = true;
      case 217 -> f = a == b;
      case 218 -> f = a == c;
      case 219 -> f = a == d;
      case 220 -> f = a == (m[b & mm] & 255);
      case 221 -> f = a == (m[c & mm] & 255);
      case 222 -> f = a == h[d & hm];
      case 223 -> f = a == opnd();
      case 224 -> f = false;
      case 225 -> f = Integer.compareUnsigned(a, b) < 0;
      case 226 -> f = Integer.compareUnsigned(a, c) < 0;
      case 227 -> f = Integer.compareUnsigned(a, d) < 0;
      case 228 -> f = Integer.compareUnsigned(a, m[b & mm] & 255) < 0;
      case 229 -> f = Integer.compareUnsigned(a, m[c & mm] & 255) < 0;
      case 230 -> f = Integer.compareUnsigned(a, h[d & hm]) < 0;
      case 231 -> f = Integer.compareUnsigned(a, opnd()) < 0;
      case 232 -> f = false;
      case 233 -> f = Integer.compareUnsigned(a, b) > 0;
      case 234 -> f = Integer.compareUnsigned(a, c) > 0;
      case 235 -> f = Integer.compareUnsigned(a, d) > 0;
      case 236 -> f = Integer.compareUnsigned(a, m[b & mm] & 255) > 0;
      case 237 -> f = Integer.compareUnsigned(a, m[c & mm] & 255) > 0;
      case 238 -> f = Integer.compareUnsigned(a, h[d & hm]) > 0;
      case 239 -> f = Integer.compareUnsigned(a, opnd()) > 0;
      case 255 -> {
        pc = hbegin + (hd[pc] & 255) + 256 * (hd[pc + 1] & 255);
        if (pc >= hend) err();
      }
      default -> err();
    }
    return true;
  }

  private static int div(int x, int y) {
    return y != 0 ? Integer.divideUnsigned(x, y) : 0;
  }

  private static int mod(int x, int y) {
    return y != 0 ? Integer.remainderUnsigned(x, y) : 0;
  }
}
