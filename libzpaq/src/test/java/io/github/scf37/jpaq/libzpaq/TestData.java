package io.github.scf37.jpaq.libzpaq;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/** Deterministic test inputs. */
final class TestData {
  private TestData() {}

  private static final String[] WORDS = {"the", "archive", "journal", "block", "fragment", "hash",
      "compress", "model", "context", "mixing", "of", "and", "a", "to", "in", "is", "that", "for",
      "it", "as", "with", "was", "on", "are", "this", "be", "or", "by", "at", "from", "zpaq",
      "predict", "bit", "stream", "segment", "version", "index", "file", "directory", "update"};

  static byte[] text(int size, long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    StringBuilder sb = new StringBuilder();
    while (sb.length() < size) {
      sb.append(WORDS[r.nextInt(WORDS.length)]);
      int c = r.nextInt(20);
      sb.append(c == 0 ? ".\n" : c == 1 ? ", " : " ");
    }
    return sb.substring(0, size).getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] code(int size, long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    StringBuilder sb = new StringBuilder();
    int i = 0;
    while (sb.length() < size) {
      sb.append("int f_").append(i++).append("(int x) { return x * ").append(r.nextInt(100))
          .append(" + ").append(r.nextInt(1000)).append("; }\n");
    }
    return sb.substring(0, size).getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] random(int size, long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    byte[] b = new byte[size];
    for (int i = 0; i < size; i++) b[i] = (byte) r.nextInt(256);
    return b;
  }

  static byte[] exe(int size, long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    byte[] b = new byte[size];
    for (int i = 0; i < size; i++) b[i] = (byte) (r.nextInt(4) == 0 ? r.nextInt(256) : 0x8b);
    for (int i = 0; i + 5 < size; i += 8 + r.nextInt(30)) {
      b[i] = (byte) (r.nextBoolean() ? 0xe8 : 0xe9);
      b[i + 4] = (byte) (r.nextBoolean() ? 0 : 255);
    }
    return b;
  }

  static byte[] zeros(int size) {
    return new byte[size];
  }

  static byte[] sequence(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= n; i++) sb.append(i).append('\n');
    return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  /** A repetitive input bigger than the smallest block size (2^20 - 4096). */
  static byte[] big() {
    byte[] a = text(300000, 7);
    byte[] b = exe(150000, 8);
    byte[] c = random(100000, 9);
    byte[] out = new byte[1_150_000];
    int p = 0;
    for (int k = 0; p < out.length; k++) {
      byte[] src = k % 3 == 0 ? a : k % 3 == 1 ? b : c;
      int n = Math.min(src.length, out.length - p);
      System.arraycopy(src, 0, out, p, n);
      p += n;
    }
    return out;
  }

  /** All named inputs. */
  static Map<String, byte[]> inputs() {
    Map<String, byte[]> m = new LinkedHashMap<>();
    m.put("empty", new byte[0]);
    m.put("one", new byte[] {'a'});
    m.put("text", text(24000, 1));
    m.put("code", code(16000, 2));
    m.put("exe", exe(20000, 3));
    m.put("rand", random(8000, 4));
    m.put("zeros", zeros(50000));
    m.put("seq", sequence(5000));
    return m;
  }
}
