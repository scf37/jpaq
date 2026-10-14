package io.github.scf37.jpaq.libzpaq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SuffixArrayTest {
  private static int[] naive(byte[] b, int n) {
    Integer[] ex = new Integer[n];
    for (int i = 0; i < n; i++) ex[i] = i;
    Arrays.sort(ex, (x, y) -> {
      int i = x;
      int j = y;
      while (i < n && j < n) {
        int c = Integer.compare(b[i] & 255, b[j] & 255);
        if (c != 0) return c;
        i++;
        j++;
      }
      return Integer.compare(n - x, n - y);
    });
    int[] r = new int[n];
    for (int i = 0; i < n; i++) r[i] = ex[i];
    return r;
  }

  @Test
  void matchesNaiveSort() {
    Random r = new Random(1);
    for (int iter = 0; iter < 3000; iter++) {
      int n = r.nextInt(iter < 2500 ? 40 : 2000) + 1;
      int alpha = 1 + r.nextInt(iter % 3 == 0 ? 2 : iter % 3 == 1 ? 4 : 256);
      byte[] b = new byte[n];
      boolean rep = r.nextInt(4) == 0;
      for (int i = 0; i < n; i++)
        b[i] = (byte) (rep && i > 5 ? b[i - 1 - r.nextInt(5)] : r.nextInt(alpha));
      int[] sa = new int[n + 3];
      SuffixArray.build(b, n, sa);
      assertArrayEquals(naive(b, n), Arrays.copyOf(sa, n), "iter " + iter);
    }
  }

  @Test
  void largeRepetitiveInput() {
    byte[] b = new byte[200000];
    for (int i = 0; i < b.length; i++) b[i] = (byte) ('a' + (i % 7 == 0 ? 1 : 0));
    int[] sa = new int[b.length];
    SuffixArray.build(b, b.length, sa);
    // verify sortedness
    for (int i = 1; i < sa.length; i++) {
      int x = sa[i - 1];
      int y = sa[i];
      int k = 0;
      while (x + k < b.length && y + k < b.length && b[x + k] == b[y + k]) k++;
      boolean less = x + k == b.length || (y + k < b.length && (b[x + k] & 255) < (b[y + k] & 255));
      if (!less) throw new AssertionError("not sorted at " + i);
    }
  }
}
