package io.github.scf37.jpaq.cli;

import java.util.Arrays;

/**
 * The fragment hash table (vector&lt;HT&gt; in the C++): for each fragment ID a 20 byte SHA-1 and
 * the uncompressed size (-1 if unknown, -2 if not initialized). Element 0 is not used.
 */
final class FragTable {
  private byte[] sha1 = new byte[20 * 1024];
  private int[] usize = new int[1024];
  private int size;

  FragTable() {
    addEmpty();
  }

  int size() {
    return size;
  }

  private void grow() {
    if (size == usize.length) {
      int n = (int) Math.min((long) usize.length * 2, Integer.MAX_VALUE - 8);
      usize = Arrays.copyOf(usize, n);
      sha1 = Arrays.copyOf(sha1, 20 * n);
    }
  }

  /** Appends an uninitialized entry (zero hash, size -2). */
  void addEmpty() {
    grow();
    Arrays.fill(sha1, 20 * size, 20 * size + 20, (byte) 0);
    usize[size++] = -2;
  }

  /** Appends an entry with hash src[off..off+19] and size u. */
  void add(byte[] src, int off, int u) {
    grow();
    System.arraycopy(src, off, sha1, 20 * size, 20);
    usize[size++] = u;
  }

  /** Removes all entries from index n on. */
  void truncate(int n) {
    if (n < size) size = Math.max(n, 1);
  }

  int usize(int i) {
    return usize[i];
  }

  void setUsize(int i, int u) {
    usize[i] = u;
  }

  /** Copies the hash of entry i into dst[off..off+19]. */
  void copySha1(int i, byte[] dst, int off) {
    System.arraycopy(sha1, 20 * i, dst, off, 20);
  }

  /** Sets the hash of entry i from src[off..off+19]. */
  void setSha1(int i, byte[] src, int off) {
    System.arraycopy(src, off, sha1, 20 * i, 20);
  }

  boolean sha1Equals(int i, byte[] other, int off) {
    return Arrays.equals(sha1, 20 * i, 20 * i + 20, other, off, off + 20);
  }

  boolean isZero(int i) {
    for (int k = 0; k < 20; k++) if (sha1[20 * i + k] != 0) return false;
    return true;
  }

  /** First 4 bytes of the hash as a little-endian unsigned int. */
  int hash32(int i) {
    int o = 20 * i;
    return (sha1[o] & 255) | (sha1[o + 1] & 255) << 8 | (sha1[o + 2] & 255) << 16
        | (sha1[o + 3] & 255) << 24;
  }
}
