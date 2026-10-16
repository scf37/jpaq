package io.github.scf37.jpaq.engine;

import java.util.Arrays;

/**
 * The fragment hash table (vector&lt;HT&gt; in the C++): for each fragment ID a 20 byte SHA-1 and
 * the uncompressed size (-1 if unknown, -2 if not initialized). Element 0 is not used.
 *
 * <p>This is the biggest part of the index of a large archive (24 bytes per fragment), so it is
 * stored in fixed size chunks instead of one array that doubles: no growth slack beyond the last
 * chunk, no copy of the whole table (and 2-3x peak) when it grows, and no 2^31 byte limit.
 */
final class FragTable {
  private static final int SHIFT = 12;                 // 4096 fragments per chunk
  private static final int CHUNK = 1 << SHIFT;
  private static final int MASK = CHUNK - 1;

  private byte[][] sha1 = new byte[16][];              // chunk -> 20 bytes per fragment
  private int[][] usize = new int[16][];               // chunk -> size per fragment
  private int size;

  FragTable() {
    addEmpty();
  }

  int size() {
    return size;
  }

  /** Makes room for one more entry and returns its index. */
  private int grow() {
    int i = size;
    int c = i >>> SHIFT;
    if (c == usize.length) {
      usize = Arrays.copyOf(usize, c * 2);
      sha1 = Arrays.copyOf(sha1, c * 2);
    }
    if (usize[c] == null) {
      usize[c] = new int[CHUNK];
      sha1[c] = new byte[20 * CHUNK];
    }
    size = i + 1;
    return i;
  }

  /** Appends an uninitialized entry (zero hash, size -2). */
  void addEmpty() {
    int i = grow();
    int c = i >>> SHIFT;
    int k = i & MASK;
    Arrays.fill(sha1[c], 20 * k, 20 * k + 20, (byte) 0);  // slot may be reused after truncate
    usize[c][k] = -2;
  }

  /** Appends an entry with hash src[off..off+19] and size u. */
  void add(byte[] src, int off, int u) {
    int i = grow();
    int c = i >>> SHIFT;
    int k = i & MASK;
    System.arraycopy(src, off, sha1[c], 20 * k, 20);
    usize[c][k] = u;
  }

  /** Removes all entries from index n on. */
  void truncate(int n) {
    if (n < size) size = Math.max(n, 1);
  }

  int usize(int i) {
    return usize[i >>> SHIFT][i & MASK];
  }

  void setUsize(int i, int u) {
    usize[i >>> SHIFT][i & MASK] = u;
  }

  /** Copies the hash of entry i into dst[off..off+19]. */
  void copySha1(int i, byte[] dst, int off) {
    System.arraycopy(sha1[i >>> SHIFT], 20 * (i & MASK), dst, off, 20);
  }

  /** Sets the hash of entry i from src[off..off+19]. */
  void setSha1(int i, byte[] src, int off) {
    System.arraycopy(src, off, sha1[i >>> SHIFT], 20 * (i & MASK), 20);
  }

  boolean sha1Equals(int i, byte[] other, int off) {
    int o = 20 * (i & MASK);
    return Arrays.equals(sha1[i >>> SHIFT], o, o + 20, other, off, off + 20);
  }

  boolean isZero(int i) {
    byte[] a = sha1[i >>> SHIFT];
    int o = 20 * (i & MASK);
    for (int k = 0; k < 20; k++) if (a[o + k] != 0) return false;
    return true;
  }

  /** First 4 bytes of the hash as a little-endian unsigned int. */
  int hash32(int i) {
    byte[] a = sha1[i >>> SHIFT];
    int o = 20 * (i & MASK);
    return (a[o] & 255) | (a[o + 1] & 255) << 8 | (a[o + 2] & 255) << 16 | (a[o + 3] & 255) << 24;
  }
}
