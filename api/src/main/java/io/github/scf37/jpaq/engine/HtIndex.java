package io.github.scf37.jpaq.engine;

/** Maps SHA-1 to a fragment ID in the fragment table, for fragments of known size. */
final class HtIndex {
  private final FragTable htr;  // reference to ht
  private int[] t;              // sha1 prefix -> index into ht
  private int htsize;           // number of IDs in t

  /** @param sz estimated number of fragments needed */
  HtIndex(FragTable r, long sz) {
    htr = r;
    htsize = 1;
    int b;
    for (b = 1; (sz * 3 >> b) != 0; ++b) { }
    t = new int[1 << (b - 1)];
    update();
  }

  private int hash(int h32) {
    return h32 & (t.length - 1);
  }

  /** Finds sha1[off..off+19] in ht. Returns its index or 0 if not found. */
  int find(byte[] sha1, int off) {
    int h = (sha1[off] & 255) | (sha1[off + 1] & 255) << 8 | (sha1[off + 2] & 255) << 16
        | (sha1[off + 3] & 255) << 24;
    h = hash(h);
    for (int i = 0; i < t.length; ++i) {
      int e = t[h ^ i];
      if (e == 0) return 0;
      if (htr.sha1Equals(e, sha1, off)) return e;
    }
    return 0;
  }

  /** Updates the index of ht. Does not index if the fragment size is unknown. */
  void update() {
    while (htsize < htr.size()) {
      if (htsize >= t.length / 4 * 3) {
        t = new int[t.length * 2];
        htsize = 1;
      }
      if (htr.usize(htsize) >= 0 && !htr.isZero(htsize)) {
        int h = hash(htr.hash32(htsize));
        for (int i = 0; i < t.length; ++i) {
          if (t[h ^ i] == 0) {
            t[h ^ i] = htsize;
            break;
          }
        }
      }
      ++htsize;
    }
  }
}
