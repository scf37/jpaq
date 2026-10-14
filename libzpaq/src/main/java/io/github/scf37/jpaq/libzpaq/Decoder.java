package io.github.scf37.jpaq.libzpaq;

/** Arithmetic decoder (libzpaq::Decoder). It is also the buffered reader of the raw input. */
final class Decoder implements ZpaqReader {
  private static final int BUFSIZE = 1 << 16;

  /** Source of compressed bytes. */
  ZpaqReader in;
  private int low = 1;
  private int high = 0xFFFFFFFF;
  private int curr;   // last 4 bytes of archive or remaining bytes in subblock
  private int rpos;
  private int wpos;
  private final Predictor pr;
  private final byte[] buf = new byte[BUFSIZE];

  Decoder(Zpaql z) {
    pr = new Predictor(z);
  }

  /** Initializes at the start of a block. */
  void init() {
    pr.init();
    if (pr.isModeled()) {
      low = 1;
      high = 0xFFFFFFFF;
      curr = 0;
    } else {
      low = high = curr = 0;
    }
  }

  /** Returns one byte of buffered input or -1 at EOF. */
  @Override
  public int get() {
    if (rpos == wpos) {
      rpos = 0;
      wpos = in != null ? in.read(buf, 0, BUFSIZE) : 0;
    }
    return rpos < wpos ? buf[rpos++] & 255 : -1;
  }

  /** How far the decoder has read ahead of the logical input position. */
  int buffered() {
    return wpos - rpos;
  }

  // Return next bit of decoded input, which has 16 bit probability p of being 1
  private int decode(int p) {
    if (Integer.compareUnsigned(curr, low) < 0 || Integer.compareUnsigned(curr, high) > 0)
      throw ZpaqException.error("archive corrupted");
    int mid = low + (int) ((((high - low) & 0xFFFFFFFFL) * p) >>> 16);
    int y;
    if (Integer.compareUnsigned(curr, mid) <= 0) {
      y = 1;
      high = mid;
    } else {
      y = 0;
      low = mid + 1;
    }
    while (((high ^ low) >>> 24) == 0) {
      high = high << 8 | 255;
      low = low << 8;
      if (low == 0) low = 1;
      int c = get();
      if (c < 0) throw ZpaqException.error("unexpected end of file");
      curr = curr << 8 | c;
    }
    return y;
  }

  /** Decompresses one byte, or returns -1 at the end of the segment. */
  int decompress() {
    if (pr.isModeled()) {
      if (curr == 0) {
        for (int i = 0; i < 4; i++) curr = curr << 8 | get();
      }
      if (decode(0) != 0) {
        if (curr != 0) throw ZpaqException.error("decoding end of stream");
        return -1;
      } else {
        int c = 1;
        while (c < 256) {
          int p = pr.predict() * 2 + 1;
          c += c + decode(p);
          pr.update(c & 1);
        }
        return c - 256;
      }
    } else {
      if (curr == 0) {
        for (int i = 0; i < 4; i++) curr = curr << 8 | get();
        if (curr == 0) return -1;
      }
      --curr;
      return get();
    }
  }

  /** Skips to the end of the segment and returns the next byte. */
  int skip() {
    int c = -1;
    if (pr.isModeled()) {
      while (curr == 0) curr = get();
      while (curr != 0 && (c = get()) >= 0) curr = curr << 8 | c;
      while ((c = get()) == 0) { }
      return c;
    } else {
      if (curr == 0) {
        for (int i = 0; i < 4 && (c = get()) >= 0; i++) curr = curr << 8 | c;
      }
      while (curr != 0) {
        // curr is unsigned
        while (curr != 0) {
          --curr;
          if (get() < 0) throw ZpaqException.error("skipped to EOF");
        }
        for (int i = 0; i < 4 && (c = get()) >= 0; i++) curr = curr << 8 | c;
      }
      if (c >= 0) c = get();
      return c;
    }
  }
}
