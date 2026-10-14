package io.github.scf37.jpaq.libzpaq;

import java.util.Arrays;

/**
 * Suffix array construction using the SA-IS algorithm (Nong, Zhang and Chan), linear time.
 * The suffix array of a byte string is unique, so the result is identical to that of libdivsufsort
 * used by libzpaq. The end of the string sorts before every other character.
 */
final class SuffixArray {
  private SuffixArray() {}

  /** Computes the suffix array of {@code b[0..n-1]} into {@code sa[0..n-1]}. */
  static void build(byte[] b, int n, int[] sa) {
    if (n <= 0) return;
    if (n == 1) {
      sa[0] = 0;
      return;
    }
    sais(b, null, 0, n, sa, 256);
  }

  private static boolean isS(long[] st, int i) {
    return (st[i >> 6] >>> i & 1) != 0;
  }

  private static int ch(byte[] b, int[] s, int so, int i) {
    return b != null ? b[i] & 255 : s[so + i];
  }

  private static void getBuckets(int[] cnt, int[] bkt, int k, boolean end) {
    int sum = 0;
    for (int i = 0; i < k; i++) {
      sum += cnt[i];
      bkt[i] = end ? sum : sum - cnt[i];
    }
  }

  private static void induceL(byte[] b, int[] s, int so, int n, int[] sa, long[] st,
      int[] cnt, int[] bkt, int k) {
    getBuckets(cnt, bkt, k, false);
    // The virtual sentinel suffix sorts first and induces suffix n-1 (L type)
    sa[bkt[ch(b, s, so, n - 1)]++] = n - 1;
    for (int i = 0; i < n; i++) {
      int j = sa[i];
      if (j > 0 && !isS(st, j - 1)) sa[bkt[ch(b, s, so, j - 1)]++] = j - 1;
    }
  }

  private static void induceS(byte[] b, int[] s, int so, int n, int[] sa, long[] st,
      int[] cnt, int[] bkt, int k) {
    getBuckets(cnt, bkt, k, true);
    for (int i = n - 1; i >= 0; i--) {
      int j = sa[i];
      if (j > 0 && isS(st, j - 1)) sa[--bkt[ch(b, s, so, j - 1)]] = j - 1;
    }
  }

  // Suffix array of the string of length n over alphabet 0..k-1 given either as bytes b or as
  // ints s[so..so+n-1]; result in sa[0..n-1]. s may be the same array as sa (placed after the
  // output region).
  private static void sais(byte[] b, int[] s, int so, int n, int[] sa, int k) {
    if (n == 1) {
      sa[0] = 0;
      return;
    }
    // Classify suffixes: st[i] true if S type (smaller than the next suffix)
    long[] st = new long[(n >> 6) + 1];
    for (int i = n - 2; i >= 0; i--) {
      int c0 = ch(b, s, so, i);
      int c1 = ch(b, s, so, i + 1);
      if (c0 < c1 || (c0 == c1 && isS(st, i + 1))) st[i >> 6] |= 1L << i;
    }
    int[] cnt = new int[k];
    for (int i = 0; i < n; i++) cnt[ch(b, s, so, i)]++;
    int[] bkt = new int[k];

    // Stage 1: sort all LMS substrings
    Arrays.fill(sa, 0, n, -1);
    getBuckets(cnt, bkt, k, true);
    for (int i = 1; i < n; i++)
      if (isS(st, i) && !isS(st, i - 1)) sa[--bkt[ch(b, s, so, i)]] = i;
    induceL(b, s, so, n, sa, st, cnt, bkt, k);
    induceS(b, s, so, n, sa, st, cnt, bkt, k);

    // Compact the sorted LMS substrings into sa[0..n1-1]
    int n1 = 0;
    for (int i = 0; i < n; i++) {
      int j = sa[i];
      if (j > 0 && isS(st, j) && !isS(st, j - 1)) sa[n1++] = j;
    }
    Arrays.fill(sa, n1, n, -1);

    // Name the LMS substrings
    int name = 0;
    int prev = -1;
    for (int i = 0; i < n1; i++) {
      int pos = sa[i];
      boolean diff = false;
      if (prev < 0) {
        diff = true;
      } else {
        for (int d = 0; ; d++) {
          if (pos + d == n || prev + d == n) {
            diff = true;
            break;
          }
          if (ch(b, s, so, pos + d) != ch(b, s, so, prev + d)
              || isS(st, pos + d) != isS(st, prev + d)) {
            diff = true;
            break;
          }
          if (d > 0 && ((isS(st, pos + d) && !isS(st, pos + d - 1))
              || (isS(st, prev + d) && !isS(st, prev + d - 1))))
            break;
        }
      }
      if (diff) name++;
      prev = pos;
      sa[n1 + (pos >> 1)] = name - 1;
    }
    for (int i = n - 1, j = n - 1; i >= n1; i--)
      if (sa[i] >= 0) sa[j--] = sa[i];

    // Stage 2: solve the reduced problem; reduced string is sa[n-n1..n-1]
    if (name < n1) {
      sais(null, sa, n - n1, n1, sa, name);
    } else {
      for (int i = 0; i < n1; i++) sa[sa[n - n1 + i]] = i;
    }

    // Stage 3: induce the final suffix array from the sorted LMS suffixes
    for (int i = 1, j = 0; i < n; i++)
      if (isS(st, i) && !isS(st, i - 1)) sa[n - n1 + j++] = i;
    for (int i = 0; i < n1; i++) sa[i] = sa[n - n1 + sa[i]];
    Arrays.fill(sa, n1, n, -1);
    getBuckets(cnt, bkt, k, true);
    for (int i = n1 - 1; i >= 0; i--) {
      int j = sa[i];
      sa[i] = -1;
      sa[--bkt[ch(b, s, so, j)]] = j;
    }
    induceL(b, s, so, n, sa, st, cnt, bkt, k);
    induceS(b, s, so, n, sa, st, cnt, bkt, k);
  }
}
