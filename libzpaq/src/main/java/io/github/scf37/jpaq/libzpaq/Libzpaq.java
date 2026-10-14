package io.github.scf37.jpaq.libzpaq;

import java.nio.charset.StandardCharsets;

/**
 * Entry points of the library: whole-stream compression and decompression using the ZPAQ level 2
 * format, with the same output as the reference libzpaq 7.15.
 *
 * <p>The method string selects the compression: "0".."5" are the built in levels (faster to
 * better), optionally followed by a block size and hints as in "14,128,0"; "x..." describes an
 * exact method (see the original documentation: preprocessing, LZ77 settings and a context
 * model).
 */
public final class Libzpaq {
  private Libzpaq() {}

  private static boolean isdigit(int c) {
    return c >= '0' && c <= '9';
  }

  private static char ch(String s, int i) {
    return i < s.length() ? s.charAt(i) : 0;
  }

  /**
   * Compresses {@code in} to {@code out} in multiple blocks of one segment each. The default
   * method is "14,128,0".
   *
   * @param filename saved in the first block only, or null
   * @param comment appended to the input size in the first block's segment comment, or null
   * @param dosha1 whether to save the SHA-1 checksum of each block
   */
  public static void compress(ZpaqReader in, ZpaqWriter out, String method, byte[] filename,
      byte[] comment, boolean dosha1) {
    // Get block size
    int bs = 4;
    if (method != null && !method.isEmpty() && isdigit(ch(method, 1))) {
      bs = ch(method, 1) - '0';
      if (isdigit(ch(method, 2))) bs = bs * 10 + ch(method, 2) - '0';
      if (bs > 11) bs = 11;
    }
    bs = (0x100000 << bs) - 4096;

    // Compress in blocks
    MemoryBuffer sb = new MemoryBuffer(bs);
    sb.advance(bs);
    int n;
    while (in != null && (n = in.read(sb.data(), 0, bs)) > 0) {
      sb.resize(n);
      compressBlock(sb, out, method, filename, comment, dosha1);
      filename = null;
      comment = null;
      sb.resize(0);
    }
  }

  /** Same as above with the default of saving the checksum and UTF-8 text arguments. */
  public static void compress(ZpaqReader in, ZpaqWriter out, String method) {
    compress(in, out, method, (byte[]) null, (byte[]) null, true);
  }

  public static void compress(ZpaqReader in, ZpaqWriter out, String method, String filename,
      String comment, boolean dosha1) {
    compress(in, out, method, utf8(filename), utf8(comment), dosha1);
  }

  private static byte[] utf8(String s) {
    return s == null ? null : s.getBytes(StandardCharsets.UTF_8);
  }

  /** Same as {@link #compress} but the output is one block, ignoring the block size parameter. */
  public static void compressBlock(MemoryBuffer in, ZpaqWriter out, String method,
      String filename, String comment, boolean dosha1) {
    compressBlock(in, out, method, utf8(filename), utf8(comment), dosha1);
  }

  public static void compressBlock(MemoryBuffer in, ZpaqWriter out, String method) {
    compressBlock(in, out, method, (byte[]) null, null, true);
  }

  /**
   * Compresses {@code in} to {@code out} in one segment of one block using the algorithm
   * described in {@code method}. If the method begins with a digit then the actual method is
   * chosen depending on the type of the data. The filename and comment are saved in the segment
   * header; the comment is preceded by the input size as a decimal string.
   */
  public static void compressBlock(MemoryBuffer in, ZpaqWriter out, String method_,
      byte[] filename, byte[] comment, boolean dosha1) {
    String method = method_;
    final int n = in.size();  // input size
    final int arg0 = Math.max(LzBuffer.lg(n + 4095) - 20, 0);  // block size

    // Get type from method "LB,R,t" where L is level 0..5, B is block size 0..11, R is
    // redundancy 0..255, t = 0..3 = binary, text, exe, both.
    int type = 0;
    if (isdigit(ch(method, 0))) {
      int commas = 0;
      int[] arg = new int[4];
      for (int i = 1; i < method.length() && commas < 4; ++i) {
        char c = method.charAt(i);
        if (c == ',' || c == '.') ++commas;
        else if (isdigit(c)) arg[commas] = arg[commas] * 10 + c - '0';
      }
      if (commas == 0) type = 512;
      else type = arg[1] * 4 + arg[2];
    }

    // Get hash of input
    byte[] sha1ptr = null;
    if (dosha1) {
      Sha1 sha1 = new Sha1();
      sha1.write(in.data(), 0, n);
      sha1ptr = sha1.result();
    }

    // Expand default methods
    if (isdigit(ch(method, 0))) {
      final int level = method.charAt(0) - '0';

      // build models
      final int doe8 = (type & 2) * 2;
      method = "x" + ConfigBuilder.itos(arg0);
      final String htsz = "," + ConfigBuilder.itos(19 + arg0 + (arg0 <= 6 ? 1 : 0));
      final String sasz = "," + ConfigBuilder.itos(21 + arg0);
      final StringBuilder m = new StringBuilder(method);

      if (level == 0) {  // store uncompressed
        m.setLength(0);
        m.append("0").append(ConfigBuilder.itos(arg0)).append(",0");
      } else if (level == 1) {  // LZ77, no model. Store if hard to compress
        if (type < 40) {
          m.append(",0");
        } else {
          m.append(",").append(ConfigBuilder.itos(1 + doe8)).append(",");
          if (type < 80) m.append("4,0,1,15");
          else if (type < 128) m.append("4,0,2,16");
          else if (type < 256) m.append("4,0,2").append(htsz);
          else if (type < 960) m.append("5,0,3").append(htsz);
          else m.append("6,0,3").append(htsz);
        }
      } else if (level == 2) {  // LZ77 with longer search
        if (type < 32) {
          m.append(",0");
        } else {
          m.append(",").append(ConfigBuilder.itos(1 + doe8)).append(",");
          if (type < 64) m.append("4,0,3").append(htsz);
          else m.append("4,0,7").append(sasz).append(",1");
        }
      } else if (level == 3) {  // LZ77 with CM depending on redundancy
        if (type < 20) {  // store if not compressible
          m.append(",0");
        } else if (type < 48) {  // fast LZ77 if barely compressible
          m.append(",").append(ConfigBuilder.itos(1 + doe8)).append(",4,0,3").append(htsz);
        } else if (type >= 640 || (type & 1) != 0) {  // BWT if text or highly compressible
          m.append(",").append(ConfigBuilder.itos(3 + doe8)).append("ci1");
        } else {  // LZ77 with O0-1 compression of up to 12 literals
          m.append(",").append(ConfigBuilder.itos(2 + doe8)).append(",12,0,7").append(sasz)
              .append(",1c0,0,511i2");
        }
      } else if (level == 4) {  // LZ77+CM, fast CM, or BWT depending on type
        if (type < 12) {
          m.append(",0");
        } else if (type < 24) {
          m.append(",").append(ConfigBuilder.itos(1 + doe8)).append(",4,0,3").append(htsz);
        } else if (type < 48) {
          m.append(",").append(ConfigBuilder.itos(2 + doe8)).append(",5,0,7").append(sasz)
              .append("1c0,0,511");
        } else if (type < 900) {
          m.append(",").append(ConfigBuilder.itos(doe8)).append("ci1,1,1,1,2a");
          if ((type & 1) != 0) m.append("w");
          m.append("m");
        } else {
          m.append(",").append(ConfigBuilder.itos(3 + doe8)).append("ci1");
        }
      } else {  // 5..9: slow CM with lots of models
        // Model text files
        m.append(",").append(ConfigBuilder.itos(doe8));
        if ((type & 1) != 0) m.append("w2c0,1010,255i1");
        else m.append("w1i1");
        m.append("c256ci1,1,1,1,1,1,2a");

        // Analyze the data
        final int nr = 1 << 12;
        int[] pt = new int[256];  // position of last occurrence
        int[] r = new int[nr];    // count repetition gaps of length r
        final byte[] p = in.data();
        for (int i = 0; i < n; ++i) {
          final int k = i - pt[p[i] & 255];
          if (k > 0 && k < nr) ++r[k];
          pt[p[i] & 255] = i;
        }

        // Add periodic models
        int n1 = n - r[1] - r[2] - r[3];
        for (int i = 0; i < 2; ++i) {
          int period = 0;
          double score = 0;
          int t = 0;
          for (int j = 5; j < nr && t < n1; ++j) {
            final double s = r[j] / (256.0 + n1 - t);
            if (s > score) {
              score = s;
              period = j;
            }
            t += r[j];
          }
          if (period > 4 && score > 0.1) {
            m.append("c0,0,").append(ConfigBuilder.itos(999 + period)).append(",255i1");
            if (period <= 255) m.append("c0,").append(ConfigBuilder.itos(period)).append("i1");
            n1 -= r[period];
            r[period] = 0;
          } else {
            break;
          }
        }
        m.append("c0,2,0,255i1c0,3,0,0,255i1c0,4,0,0,0,255i1mm16ts19t0");
      }
      method = m.toString();
    }

    // Compress
    int[] args = new int[9];
    String config = ConfigBuilder.makeConfig(method, args);
    Compressor co = new Compressor();
    co.setOutput(out);
    MemoryBuffer pcompCmd = new MemoryBuffer();
    co.writeTag();
    co.startBlock(config, args, pcompCmd);
    StringBuilder cs = new StringBuilder(ConfigBuilder.itos(n));
    byte[] csBytes = cs.toString().getBytes(StandardCharsets.ISO_8859_1);
    if (comment != null) {
      byte[] c2 = new byte[csBytes.length + 1 + comment.length];
      System.arraycopy(csBytes, 0, c2, 0, csBytes.length);
      c2[csBytes.length] = ' ';
      System.arraycopy(comment, 0, c2, csBytes.length + 1, comment.length);
      csBytes = c2;
    }
    co.startSegment(filename, csBytes);
    if (args[1] >= 1 && args[1] <= 7 && args[1] != 4) {  // LZ77 or BWT
      LzBuffer lz = new LzBuffer(in, args);
      co.setInput(lz);
      co.compress();
    } else {  // compress with e8e9 or no preprocessing
      if (args[1] >= 4 && args[1] <= 7) E8E9.transform(in.data(), in.size());
      co.setInput(in);
      co.compress();
    }
    co.endSegment(sha1ptr);
    co.endBlock();
  }

  /** Decompresses any valid ZPAQ stream, ignoring filenames, comments and checksums. */
  public static void decompress(ZpaqReader in, ZpaqWriter out) {
    Decompresser d = new Decompresser();
    d.setInput(in);
    d.setOutput(out);
    while (d.findBlock()) {
      while (d.findFilename()) {
        d.readComment();
        d.decompress();
        d.readSegmentEnd();
      }
    }
  }
}
