package io.github.scf37.jpaq.libzpaq;

/** Arithmetic encoder (libzpaq::Encoder). */
final class Encoder {
  /** Destination of compressed bytes. */
  ZpaqWriter out;
  private int low = 1;
  private int high = 0xFFFFFFFF;
  private final Predictor pr;
  private byte[] buf = new byte[0];  // unmodeled input

  Encoder(Zpaql z) {
    pr = new Predictor(z);
  }

  /** Initializes for the start of a block. */
  void init() {
    low = 1;
    high = 0xFFFFFFFF;
    pr.init();
    if (!pr.isModeled()) {
      low = 0;
      buf = new byte[1 << 16];
    }
  }

  // compress bit y having probability p/64K
  private void encode(int y, int p) {
    int mid = low + (int) ((((high - low) & 0xFFFFFFFFL) * p) >>> 16);
    if (y != 0) high = mid;
    else low = mid + 1;
    while (((high ^ low) >>> 24) == 0) {
      out.put(high >>> 24);
      high = high << 8 | 255;
      low = low << 8;
      if (low == 0) low = 1;
    }
  }

  /** Compresses byte c (0..255), or -1 for end of segment. */
  void compress(int c) {
    if (pr.isModeled()) {
      if (c == -1) {
        encode(1, 0);
      } else {
        encode(0, 0);
        for (int i = 7; i >= 0; i--) {
          int p = pr.predict() * 2 + 1;
          int y = c >> i & 1;
          encode(y, p);
          pr.update(y);
        }
      }
    } else {
      if (low != 0 && (c < 0 || low == buf.length)) {
        out.put((low >>> 24) & 255);
        out.put((low >>> 16) & 255);
        out.put((low >>> 8) & 255);
        out.put(low & 255);
        out.write(buf, 0, low);
        low = 0;
      }
      if (c >= 0) buf[low++] = (byte) c;
    }
  }

  /** Whether the block has no model, so that {@link #compressStored} can be used. */
  boolean isStored() {
    return !pr.isModeled();
  }

  /** Same as {@link #compress(int)} for each of {@code b[off..off+len-1]}, for unmodeled blocks. */
  void compressStored(byte[] b, int off, int len) {
    while (len > 0) {
      if (low == buf.length) {
        compress(b[off++] & 255);  // writes the full buffer
        --len;
        continue;
      }
      final int k = Math.min(len, buf.length - low);
      System.arraycopy(b, off, buf, low, k);
      low += k;
      off += k;
      len -= k;
    }
  }
}
