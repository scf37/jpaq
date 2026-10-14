package io.github.scf37.jpaq.libzpaq;

/**
 * Decompresses ZPAQ streams block by block and segment by segment (libzpaq::Decompresser).
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * Decompresser d = new Decompresser();
 * d.setInput(in);
 * while (d.findBlock()) {
 *   while (d.findFilename(filename)) {
 *     d.readComment(comment);
 *     d.setOutput(out);
 *     d.setSha1(sha1);
 *     while (d.decompress(1000)) { }
 *     byte[] end = new byte[21];
 *     d.readSegmentEnd(end);
 *   }
 * }
 * }</pre>
 */
public final class Decompresser {
  private enum State { BLOCK, FILENAME, COMMENT, DATA, SEGEND }

  private enum DecodeState { FIRSTSEG, SEG, SKIP }

  private final Zpaql z = new Zpaql();
  private final Decoder dec = new Decoder(z);
  private final PostProcessor pp = new PostProcessor();
  private State state = State.BLOCK;
  private DecodeState decodeState = DecodeState.FIRSTSEG;

  private static final int[] JPAQ_HASH = new int[4];

  static {
    final int[] mul = {12, 20, 28, 44};
    final int[] tag = {0x37, 0x6b, 0x53, 0x74, 0xa0, 0x31, 0x83, 0xd3, 0x8c, 0xb2, 0x28, 0xb0, 0xd3,
        Dialect.JPAQ.marker(), 'P', 'Q'};
    for (int k = 0; k < 4; k++) {
      int h = 0;
      for (int b : tag) h = h * mul[k] + b;
      JPAQ_HASH[k] = h;
    }
  }

  private Dialect dialect = Dialect.STANDARD;

  /** The block magic of the block found by the last {@link #findBlock}. */
  public Dialect dialect() {
    return dialect;
  }

  public void setInput(ZpaqReader in) {
    dec.in = in;
  }

  /**
   * Finds the start of the next block. Returns false if none is found.
   *
   * @param mem if not null, mem[0] is set to the approximate memory needed to decompress
   */
  public boolean findBlock(double[] mem) {
    // Find start of block: the 16 byte string tag + "zPQ" (STANDARD) or tag + "jPQ" (JPAQ). The
    // multipliers are multiples of 4, so each hash only depends on the last 16 bytes read. The
    // standard hashes start from values that stand for a preceding locator tag, so that a block
    // at the very start of a stream is also found; the JPAQ ones always need the whole string.
    int h1 = 0x3D49B113;
    int h2 = 0x29EB7F93;
    int h3 = 0x2614BE13;
    int h4 = 0x3828EB13;
    int g1 = 0;
    int g2 = 0;
    int g3 = 0;
    int g4 = 0;
    int c;
    Dialect found = null;
    while ((c = dec.get()) != -1) {
      h1 = h1 * 12 + c;
      h2 = h2 * 20 + c;
      h3 = h3 * 28 + c;
      h4 = h4 * 44 + c;
      g1 = g1 * 12 + c;
      g2 = g2 * 20 + c;
      g3 = g3 * 28 + c;
      g4 = g4 * 44 + c;
      if (h1 == 0xB16B88F1 && h2 == 0xFF5376F1 && h3 == 0x72AC5BF1 && h4 == 0x2F909AF1) {
        found = Dialect.STANDARD;  // hash of 16 byte string
        break;
      }
      if (g1 == JPAQ_HASH[0] && g2 == JPAQ_HASH[1] && g3 == JPAQ_HASH[2] && g4 == JPAQ_HASH[3]) {
        found = Dialect.JPAQ;
        break;
      }
    }
    if (c == -1) return false;
    dialect = found;

    // Read header
    if ((c = dec.get()) != 1 && c != 2) throw ZpaqException.error("unsupported ZPAQ level");
    if (dec.get() != 1) throw ZpaqException.error("unsupported ZPAQL type");
    z.read(dec);
    if (c == 1 && z.header.length > 6 && z.header[6] == 0)
      throw ZpaqException.error("ZPAQ level 1 requires at least 1 component");
    if (mem != null) mem[0] = z.memory();
    state = State.FILENAME;
    decodeState = DecodeState.FIRSTSEG;
    return true;
  }

  public boolean findBlock() {
    return findBlock(null);
  }

  /** Writes the COMP and HCOMP sections of the current block header to {@code out}. */
  public void hcomp(ZpaqWriter out) {
    z.write(out, false);
  }

  /**
   * Reads the start of a segment (returns true, writing the filename to {@code filename} which may
   * be null) or the end of block code (returns false).
   */
  public boolean findFilename(ZpaqWriter filename) {
    int c = dec.get();
    if (c == 1) {  // segment found
      while (true) {
        c = dec.get();
        if (c == -1) throw ZpaqException.error("unexpected EOF");
        if (c == 0) {
          state = State.COMMENT;
          return true;
        }
        if (filename != null) filename.put(c);
      }
    } else if (c == 255) {  // end of block found
      state = State.BLOCK;
      return false;
    } else {
      throw ZpaqException.error("missing segment or end of block");
    }
  }

  public boolean findFilename() {
    return findFilename(null);
  }

  /** Reads the comment from the segment header and writes it to {@code comment} (may be null). */
  public void readComment(ZpaqWriter comment) {
    state = State.DATA;
    while (true) {
      int c = dec.get();
      if (c == -1) throw ZpaqException.error("unexpected EOF");
      if (c == 0) break;
      if (comment != null) comment.put(c);
    }
    if (dec.get() != 0) throw ZpaqException.error("missing reserved byte");
  }

  public void readComment() {
    readComment(null);
  }

  /** Sets the destination of decompressed output, or null to discard it. */
  public void setOutput(ZpaqWriter out) {
    pp.setOutput(out);
  }

  /** Sets a checksum computer for the segment output (optional). */
  public void setSha1(Sha1 sha1) {
    pp.setSha1(sha1);
  }

  /**
   * Decompresses {@code n} bytes, or the whole segment if {@code n} &lt; 0. Returns true until
   * the end of the segment.
   */
  public boolean decompress(int n) {
    if (decodeState == DecodeState.SKIP)
      throw ZpaqException.error("decompression after skipped segment");

    // Initialize models to start decompressing block
    if (decodeState == DecodeState.FIRSTSEG) {
      dec.init();
      pp.init(z.header[4] & 255, z.header[5] & 255);
      decodeState = DecodeState.SEG;
    }

    // Decompress and load PCOMP into postprocessor
    while ((pp.getState() & 3) != 1) pp.write(dec.decompress());

    // Decompress n bytes, or all if n < 0
    while (n != 0) {
      int c = dec.decompress();
      pp.write(c);
      if (c == -1) {
        state = State.SEGEND;
        return false;
      }
      if (n > 0) --n;
    }
    return true;
  }

  public boolean decompress() {
    return decompress(-1);
  }

  /** Writes the PCOMP section of the current segment to {@code out}; false if there is none. */
  public boolean pcomp(ZpaqWriter out) {
    return pp.z.write(out, true);
  }

  /**
   * Skips any remaining data in the segment and reads its end marker. If a checksum is present
   * writes 1 and the 20 byte hash into {@code sha1string[0..20]}, else writes 0 in the first byte.
   * {@code sha1string} may be null.
   */
  public void readSegmentEnd(byte[] sha1string) {
    // Skip remaining data if any and get next byte
    int c = 0;
    if (state == State.DATA) {
      c = dec.skip();
      decodeState = DecodeState.SKIP;
    } else if (state == State.SEGEND) {
      c = dec.get();
    }
    state = State.FILENAME;

    // Read checksum
    if (c == 254) {
      if (sha1string != null) sha1string[0] = 0;
    } else if (c == 253) {
      if (sha1string != null) sha1string[0] = 1;
      for (int i = 1; i <= 20; i++) {
        c = dec.get();
        if (sha1string != null) sha1string[i] = (byte) c;
      }
    } else {
      throw ZpaqException.error("missing end of segment marker");
    }
  }

  public void readSegmentEnd() {
    readSegmentEnd(null);
  }

  /** Number of bytes read ahead of the logical input position. */
  public int buffered() {
    return dec.buffered();
  }
}
