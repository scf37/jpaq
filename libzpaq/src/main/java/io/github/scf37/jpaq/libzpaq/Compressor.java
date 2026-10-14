package io.github.scf37.jpaq.libzpaq;

import java.nio.charset.StandardCharsets;

/**
 * Compresses data into ZPAQ blocks and segments with fine control (libzpaq::Compressor).
 *
 * <pre>{@code
 * Compressor c = new Compressor();
 * c.setOutput(out);
 * c.writeTag();                     // optional locator tag
 * c.startBlock(2);                  // level 1, 2 or 3, or ZPAQL source/byte code
 * c.startSegment("filename", "comment");
 * c.setInput(in);
 * c.compress();
 * c.endSegment(sha1);               // optional 20 byte checksum
 * c.endBlock();
 * }</pre>
 */
public final class Compressor {
  private enum State { INIT, BLOCK1, SEG1, BLOCK2, SEG2 }

  // Models for levels 1, 2, 3: min.cfg, mid.cfg, max.cfg as header byte code (2 byte length
  // prefix each), terminated by a 0 length.
  private static final byte[] MODELS = toBytes(new int[] {
      26, 0, 1, 2, 0, 0, 2, 3, 16, 8, 19, 0, 0, 96, 4, 28,
      59, 10, 59, 112, 25, 10, 59, 10, 59, 112, 56, 0, 69, 0, 3, 3,
      0, 0, 8, 3, 5, 8, 13, 0, 8, 17, 1, 8, 18, 2, 8, 18,
      3, 8, 19, 4, 4, 22, 24, 7, 16, 0, 7, 24, 255, 0, 17, 104,
      74, 4, 95, 1, 59, 112, 10, 25, 59, 112, 10, 25, 59, 112, 10, 25,
      59, 112, 10, 25, 59, 112, 10, 25, 59, 10, 59, 112, 25, 69, 207, 8,
      112, 56, 0, 196, 0, 5, 9, 0, 0, 22, 1, 160, 3, 5, 8, 13,
      1, 8, 16, 2, 8, 18, 3, 8, 19, 4, 8, 19, 5, 8, 20, 6,
      4, 22, 24, 3, 17, 8, 19, 9, 3, 13, 3, 13, 3, 13, 3, 14,
      7, 16, 0, 15, 24, 255, 7, 8, 0, 16, 10, 255, 6, 0, 15, 16,
      24, 0, 9, 8, 17, 32, 255, 6, 8, 17, 18, 16, 255, 9, 16, 19,
      32, 255, 6, 0, 19, 20, 16, 0, 0, 17, 104, 74, 4, 95, 2, 59,
      112, 10, 25, 59, 112, 10, 25, 59, 112, 10, 25, 59, 112, 10, 25, 59,
      112, 10, 25, 59, 10, 59, 112, 10, 25, 59, 112, 10, 25, 69, 183, 32,
      239, 64, 47, 14, 231, 91, 47, 10, 25, 60, 26, 48, 134, 151, 20, 112,
      63, 9, 70, 223, 0, 39, 3, 25, 112, 26, 52, 25, 25, 74, 10, 4,
      59, 112, 25, 10, 4, 59, 112, 25, 10, 4, 59, 112, 25, 65, 143, 212,
      72, 4, 59, 112, 8, 143, 216, 8, 68, 175, 60, 60, 25, 69, 207, 9,
      112, 25, 25, 25, 25, 25, 112, 56, 0, 0, 0});

  private static byte[] toBytes(int[] v) {
    byte[] r = new byte[v.length];
    for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
    return r;
  }

  private final Zpaql z = new Zpaql();   // model
  private final Zpaql pz = new Zpaql();  // test postprocessor
  private final Encoder enc = new Encoder(z);
  private ZpaqReader in;
  private final Sha1 sha1 = new Sha1();  // to test pz output
  private byte[] sha1result;
  private State state = State.INIT;
  private boolean verify;

  /** Sets the destination of compressed output. */
  public void setOutput(ZpaqWriter out) {
    enc.out = out;
  }

  /** Writes the 13 byte locator tag which allows scanning for blocks. */
  public void writeTag() {
    final int[] tag = {0x37, 0x6b, 0x53, 0x74, 0xa0, 0x31, 0x83, 0xd3, 0x8c, 0xb2, 0x28, 0xb0, 0xd3};
    for (int b : tag) enc.out.put(b);
  }

  /** Starts a block using built in compression level 1, 2 or 3. */
  public void startBlock(int level) {
    if (level < 1) throw ZpaqException.error("compression level must be at least 1");
    int p = 0;
    for (int i = 1; i < level && toU16(MODELS, p) != 0; i++) p += toU16(MODELS, p) + 2;
    if (toU16(MODELS, p) < 1) throw ZpaqException.error("compression level too high");
    startBlock(MODELS, p);
  }

  private static int toU16(byte[] b, int p) {
    return (b[p] & 255) + 256 * (b[p + 1] & 255);
  }

  /** Starts a block using the COMP and HCOMP byte code (length prefixed) in {@code hcomp}. */
  public void startBlock(byte[] hcomp) {
    startBlock(hcomp, 0);
  }

  private void startBlock(byte[] hcomp, int off) {
    final int[] pos = {off};
    z.read(() -> hcomp[pos[0]++] & 255);
    pz.sha1 = sha1;
    startBlockHeader();
  }

  /**
   * Starts a block using ZPAQL source code.
   *
   * @param config ZPAQL source
   * @param args null or up to 9 arguments $1..$9
   * @param pcompCmd receives the preprocessor command text of "PCOMP cmd ;" or may be null
   */
  public void startBlock(String config, int[] args, ZpaqWriter pcompCmd) {
    new Compiler(config, args, z, pz, pcompCmd);
    pz.sha1 = sha1;
    startBlockHeader();
  }

  private void startBlockHeader() {
    enc.out.put('z');
    enc.out.put('P');
    enc.out.put('Q');
    enc.out.put(1 + (z.header[6] == 0 ? 1 : 0));  // level 1 or 2
    enc.out.put(1);
    z.write(enc.out, false);
    state = State.BLOCK1;
  }

  /** Enables testing the postprocessor on the data being compressed (slower). */
  public void setVerify(boolean v) {
    verify = v;
  }

  /** Writes the COMP and HCOMP sections of the current block header. */
  public void hcomp(ZpaqWriter out) {
    z.write(out, false);
  }

  /** Writes the PCOMP section (if any) of the current block. */
  public boolean pcomp(ZpaqWriter out) {
    return pz.write(out, true);
  }

  /** Writes a segment header. Null or empty filename continues the previous file. */
  public void startSegment(byte[] filename, byte[] comment) {
    enc.out.put(1);
    if (filename != null) for (byte b : filename) {
      if (b == 0) break;
      enc.out.put(b);
    }
    enc.out.put(0);
    if (comment != null) for (byte b : comment) {
      if (b == 0) break;
      enc.out.put(b);
    }
    enc.out.put(0);
    enc.out.put(0);
    if (state == State.BLOCK1) state = State.SEG1;
    if (state == State.BLOCK2) state = State.SEG2;
  }

  /** Same as {@link #startSegment(byte[], byte[])}, encoding the strings as UTF-8. */
  public void startSegment(String filename, String comment) {
    startSegment(filename == null ? null : filename.getBytes(StandardCharsets.UTF_8),
        comment == null ? null : comment.getBytes(StandardCharsets.UTF_8));
  }

  public void startSegment() {
    startSegment((byte[]) null, (byte[]) null);
  }

  public void setInput(ZpaqReader i) {
    in = i;
  }

  /**
   * Initializes encoding and writes the PCOMP code to the first segment. If {@code pcomp} is null
   * then it is taken from the compiled block. If {@code len} is 0 then the length is in
   * pcomp[0..1].
   */
  public void postProcess(byte[] pcomp, int len) {
    if (state == State.SEG2) return;
    enc.init();
    int off = 0;
    if (pcomp == null) {
      len = pz.hend - pz.hbegin;
      if (len > 0) {
        pcomp = pz.header;
        off = pz.hbegin;
      }
    } else if (len == 0) {
      len = toU16(pcomp, 0);
      off = 2;
    }
    if (len > 0) {
      enc.compress(1);
      enc.compress(len & 255);
      enc.compress((len >> 8) & 255);
      for (int i = 0; i < len; i++) enc.compress(pcomp[off + i] & 255);
      if (verify) pz.initp();
    } else {
      enc.compress(0);
    }
    state = State.SEG2;
  }

  private void postProcess() {
    postProcess(null, 0);
  }

  /**
   * Compresses {@code n} bytes of input, or to EOF if {@code n} &lt; 0. Returns true if there may be
   * more input.
   */
  public boolean compress(int n) {
    if (state == State.SEG1) postProcess();
    final int bufsize = 1 << 14;
    byte[] buf = new byte[bufsize];
    while (n != 0) {
      int nbuf = bufsize;
      if (n >= 0 && n < nbuf) nbuf = n;
      int nr = in.read(buf, 0, nbuf);
      if (nr < 0 || nr > bufsize || nr > nbuf) throw ZpaqException.error("invalid read size");
      if (nr <= 0) return false;
      if (n >= 0) n -= nr;
      if (!verify && enc.isStored()) {
        enc.compressStored(buf, 0, nr);
        continue;
      }
      for (int i = 0; i < nr; i++) {
        int ch = buf[i] & 255;
        enc.compress(ch);
        if (verify) {
          if (pz.hend != 0) pz.run(ch);
          else sha1.put(ch);
        }
      }
    }
    return true;
  }

  public boolean compress() {
    return compress(-1);
  }

  /** Ends the segment, writing a 20 byte checksum if {@code sha1string} is not null. */
  public void endSegment(byte[] sha1string) {
    if (state == State.SEG1) postProcess();
    enc.compress(-1);
    if (verify && pz.hend != 0) {
      pz.run(-1);
      pz.flush();
    }
    enc.out.put(0);
    enc.out.put(0);
    enc.out.put(0);
    enc.out.put(0);
    if (sha1string != null) {
      enc.out.put(253);
      for (int i = 0; i < 20; i++) enc.out.put(sha1string[i]);
    } else {
      enc.out.put(254);
    }
    state = State.BLOCK2;
  }

  public void endSegment() {
    endSegment(null);
  }

  /**
   * Ends the segment and writes the checksum computed by the verifier if verify is on and
   * {@code dosha1}. Returns the checksum or null if verify is off. {@code size[0]} receives the
   * size of the postprocessed output.
   */
  public byte[] endSegmentChecksum(long[] size, boolean dosha1) {
    if (state == State.SEG1) postProcess();
    enc.compress(-1);
    if (verify && pz.hend != 0) {
      pz.run(-1);
      pz.flush();
    }
    enc.out.put(0);
    enc.out.put(0);
    enc.out.put(0);
    enc.out.put(0);
    if (verify) {
      if (size != null) size[0] = sha1.usize();
      sha1result = sha1.result();
    }
    if (verify && dosha1) {
      enc.out.put(253);
      for (int i = 0; i < 20; i++) enc.out.put(sha1result[i]);
    } else {
      enc.out.put(254);
    }
    state = State.BLOCK2;
    return verify ? sha1result : null;
  }

  /** Size of the postprocessed output verified so far (with {@link #setVerify(boolean)}). */
  public long getSize() {
    return sha1.usize();
  }

  /** SHA-1 of the postprocessed output verified so far; resets the size to 0. */
  public byte[] getChecksum() {
    return sha1.result();
  }

  /** Ends the block. */
  public void endBlock() {
    enc.out.put(255);
    state = State.INIT;
  }
}
