package io.github.scf37.jpaq.engine;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * The set of file names of an archive (or of the external files to add) with their date, size,
 * attributes and fragment pointer lists, in the order of the names (a {@code map<string, DT>} of
 * the C++). It is the second big part of the index of an archive with many files.
 *
 * <p>A {@code TreeMap<String, DT>} with one object per file costs about 150 bytes besides the
 * name (map entry, DT, String, two array headers). Here the fields of all files are kept in
 * paged primitive arrays, the names (byte strings, one char per byte) in a byte arena, and the
 * pointer lists in one int pool, which is about 50 bytes per file plus the name. Entries are
 * identified by their index; {@link DT} is a small handle.
 */
final class FileTable {
  private static final int PAGE_SHIFT = 12;
  private static final int PAGE = 1 << PAGE_SHIFT;
  private static final int PAGE_MASK = PAGE - 1;
  private static final int ARENA_SHIFT = 20;                   // names are stored in 1 MB chunks
  private static final int ARENA_CHUNK = 1 << ARENA_SHIFT;

  private int size;

  // Fields of the files, in pages of 4096 entries.
  private long[][] date = new long[4][];
  private long[][] fsize = new long[4][];
  private long[][] attr = new long[4][];
  private long[][] data;                // allocated when first set to non zero (extract / list)
  private long[][] nameRef = new long[4][];  // arena chunk << 20 | offset
  private int[][] ptrRef = new int[4][];     // index of the pointer list in pool, 0 if empty

  // Names: 2 byte length, then the bytes.
  private byte[][] arena = new byte[4][];
  private int arenaChunks;
  private int arenaPos = ARENA_CHUNK;       // write position in the last chunk

  // Pointer lists: pool[ref] = length, pool[ref + 1 ...] = pointers.
  private int[] pool = new int[16];
  private int poolEnd = 1;                  // pool[0] is not used so that ref 0 can mean empty
  private int garbage;                      // ints in pool that belong to no list

  // Open addressing hash of the names: entry + 1, 0 = empty.
  private int[] index = new int[16];
  private byte[] tag = new byte[16];        // 8 more hash bits per slot: avoids most name compares
  private int[] order;                      // entries sorted by name, null if out of date

  int size() {
    return size;
  }

  // ---------------------------------------------------------------------------------------
  // Lookup and insertion

  private static int spread(int h) {
    h *= 0x9E3779B1;
    return h ^ (h >>> 15);
  }

  private int hashOf(int e) {
    long r = nameRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
    byte[] a = arena[(int) (r >>> ARENA_SHIFT)];
    int o = (int) (r & (ARENA_CHUNK - 1));
    int n = (a[o] & 255) << 8 | (a[o + 1] & 255);
    int h = 0;
    for (int i = 0; i < n; i++) h = 31 * h + (a[o + 2 + i] & 255);
    return spread(h);
  }

  /** True if the name of e is the first len chars of s. */
  private boolean nameEquals(int e, String s, int len) {
    long r = nameRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
    byte[] a = arena[(int) (r >>> ARENA_SHIFT)];
    int o = (int) (r & (ARENA_CHUNK - 1));
    int n = (a[o] & 255) << 8 | (a[o + 1] & 255);
    if (n != len) return false;
    for (int i = 0; i < n; i++) if ((a[o + 2 + i] & 255) != s.charAt(i)) return false;
    return true;
  }

  /** Returns the entry named s or -1. */
  int find(String s) {
    return findPrefix(s, s.length(), s.hashCode());
  }

  /**
   * Returns the entry named by the first len chars of s or -1. raw is the String.hashCode() of
   * those chars, which a caller that looks up all prefixes of a string can compute on the way.
   */
  int findPrefix(String s, int len, int raw) {
    int mask = index.length - 1;
    int h = spread(raw);
    int i = h & mask;
    byte t = (byte) (h >>> 24);
    while (true) {
      int e = index[i];
      if (e == 0) return -1;
      if (tag[i] == t && nameEquals(e - 1, s, len)) return e - 1;
      i = (i + 1) & mask;
    }
  }

  /** Returns the entry named s, adding an empty one (all fields 0) if there is none. */
  int findOrAdd(String s) {
    int mask = index.length - 1;
    int h = spread(s.hashCode());
    int i = h & mask;
    byte t = (byte) (h >>> 24);
    while (true) {
      int e = index[i];
      if (e == 0) break;
      if (tag[i] == t && nameEquals(e - 1, s, s.length())) return e - 1;
      i = (i + 1) & mask;
    }
    int e = add(s);
    if ((long) size * 4 > (long) index.length * 3) {
      rehash();
    } else {
      index[i] = e + 1;
      tag[i] = t;
    }
    return e;
  }

  private void rehash() {
    int[] t = new int[index.length * 2];
    byte[] tg = new byte[t.length];
    int mask = t.length - 1;
    for (int e = 0; e < size; e++) {
      int h = hashOf(e);
      int i = h & mask;
      while (t[i] != 0) i = (i + 1) & mask;
      t[i] = e + 1;
      tg[i] = (byte) (h >>> 24);
    }
    index = t;
    tag = tg;
  }

  private static long[][] ensure(long[][] p, int page) {
    if (page >= p.length) p = Arrays.copyOf(p, Math.max(page + 1, p.length * 2));
    if (p[page] == null) p[page] = new long[PAGE];
    return p;
  }

  private int add(String s) {
    int n = s.length();
    if (n > 65535) throw new IllegalArgumentException("filename too long");
    int e = size;
    int page = e >>> PAGE_SHIFT;
    if (page >= ptrRef.length) {
      ptrRef = Arrays.copyOf(ptrRef, ptrRef.length * 2);
    }
    if (ptrRef[page] == null) ptrRef[page] = new int[PAGE];
    date = ensure(date, page);
    fsize = ensure(fsize, page);
    attr = ensure(attr, page);
    nameRef = ensure(nameRef, page);
    if (data != null) data = ensure(data, page);

    if (arenaPos + 2 + n > ARENA_CHUNK) {  // start a new chunk
      if (arenaChunks == arena.length) arena = Arrays.copyOf(arena, arenaChunks * 2);
      arena[arenaChunks++] = new byte[ARENA_CHUNK];
      arenaPos = 0;
    }
    byte[] a = arena[arenaChunks - 1];
    int o = arenaPos;
    a[o] = (byte) (n >>> 8);
    a[o + 1] = (byte) n;
    for (int i = 0; i < n; i++) {
      char c = s.charAt(i);
      if (c > 255) throw new IllegalArgumentException("not a byte string: " + s);
      a[o + 2 + i] = (byte) c;
    }
    arenaPos = o + 2 + n;
    nameRef[page][e & PAGE_MASK] = (long) (arenaChunks - 1) << ARENA_SHIFT | o;
    size = e + 1;
    order = null;
    return e;
  }

  /** The name of entry e as a byte string. */
  String name(int e) {
    long r = nameRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
    byte[] a = arena[(int) (r >>> ARENA_SHIFT)];
    int o = (int) (r & (ARENA_CHUNK - 1));
    int n = (a[o] & 255) << 8 | (a[o + 1] & 255);
    return latin1(a, o + 2, n);
  }

  @SuppressWarnings("deprecation")
  private static String latin1(byte[] a, int off, int n) {
    return new String(a, 0, off, n);  // high byte 0: copies the bytes (the fastest way)
  }

  int nameLength(int e) {
    long r = nameRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
    byte[] a = arena[(int) (r >>> ARENA_SHIFT)];
    int o = (int) (r & (ARENA_CHUNK - 1));
    return (a[o] & 255) << 8 | (a[o + 1] & 255);
  }

  /** The char (0..255) at position i of the name of entry e. */
  int nameAt(int e, int i) {
    long r = nameRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
    return arena[(int) (r >>> ARENA_SHIFT)][(int) (r & (ARENA_CHUNK - 1)) + 2 + i] & 255;
  }

  /** Compares the names of two entries like String.compareTo. */
  int compareNames(int x, int y) {
    long rx = nameRef[x >>> PAGE_SHIFT][x & PAGE_MASK];
    long ry = nameRef[y >>> PAGE_SHIFT][y & PAGE_MASK];
    byte[] ax = arena[(int) (rx >>> ARENA_SHIFT)];
    byte[] ay = arena[(int) (ry >>> ARENA_SHIFT)];
    int ox = (int) (rx & (ARENA_CHUNK - 1));
    int oy = (int) (ry & (ARENA_CHUNK - 1));
    int nx = (ax[ox] & 255) << 8 | (ax[ox + 1] & 255);
    int ny = (ay[oy] & 255) << 8 | (ay[oy + 1] & 255);
    int m = Math.min(nx, ny);
    int mm = Arrays.mismatch(ax, ox + 2, ox + 2 + m, ay, oy + 2, oy + 2 + m);
    if (mm >= 0) return (ax[ox + 2 + mm] & 255) - (ay[oy + 2 + mm] & 255);
    return nx - ny;
  }

  // ---------------------------------------------------------------------------------------
  // Fields

  long date(int e) {
    return date[e >>> PAGE_SHIFT][e & PAGE_MASK];
  }

  void setDate(int e, long v) {
    date[e >>> PAGE_SHIFT][e & PAGE_MASK] = v;
  }

  long size(int e) {
    return fsize[e >>> PAGE_SHIFT][e & PAGE_MASK];
  }

  void setSize(int e, long v) {
    fsize[e >>> PAGE_SHIFT][e & PAGE_MASK] = v;
  }

  long attr(int e) {
    return attr[e >>> PAGE_SHIFT][e & PAGE_MASK];
  }

  void setAttr(int e, long v) {
    attr[e >>> PAGE_SHIFT][e & PAGE_MASK] = v;
  }

  long data(int e) {
    if (data == null) return 0;
    long[] p = data[e >>> PAGE_SHIFT];
    return p == null ? 0 : p[e & PAGE_MASK];
  }

  void setData(int e, long v) {
    if (data == null) {
      if (v == 0) return;
      data = new long[date.length][];
    }
    int page = e >>> PAGE_SHIFT;
    if (page >= data.length || data[page] == null) {
      if (v == 0) return;
      data = ensure(data, page);
    }
    data[page][e & PAGE_MASK] = v;
  }

  /** Allocates the storage of the data field of all entries (needed if threads will set it). */
  void enableData() {
    if (data == null) data = new long[date.length][];
    for (int page = 0; page <= (size - 1) >>> PAGE_SHIFT && size > 0; page++) {
      data = ensure(data, page);
    }
  }

  /** Resets all fields of e to 0 and its pointer list to empty (a fresh DT). */
  void reset(int e) {
    setDate(e, 0);
    setSize(e, 0);
    setAttr(e, 0);
    setData(e, 0);
    ptrClear(e);
  }

  // ---------------------------------------------------------------------------------------
  // Pointer lists

  int ptrSize(int e) {
    int r = ptrRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
    return r == 0 ? 0 : pool[r];
  }

  int ptr(int e, int i) {
    int r = ptrRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
    if (i < 0 || r == 0 || i >= pool[r]) throw new ArrayIndexOutOfBoundsException(i);
    return pool[r + 1 + i];
  }

  void setPtr(int e, int i, int v) {
    int r = ptrRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
    if (i < 0 || r == 0 || i >= pool[r]) throw new ArrayIndexOutOfBoundsException(i);
    pool[r + 1 + i] = v;
  }

  void ptrClear(int e) {
    int[] p = ptrRef[e >>> PAGE_SHIFT];
    int r = p[e & PAGE_MASK];
    if (r != 0) {
      garbage += pool[r] + 1;
      p[e & PAGE_MASK] = 0;
    }
  }

  /** Sets the length of the list to n, new elements are 0. */
  void ptrResize(int e, int n) {
    int[] p = ptrRef[e >>> PAGE_SHIFT];
    int r = p[e & PAGE_MASK];
    int len = r == 0 ? 0 : pool[r];
    if (n == len) return;
    if (n < len) {
      pool[r] = n;
      garbage += len - n;
      return;
    }
    r = extend(p, e & PAGE_MASK, r, len, n);
    Arrays.fill(pool, r + 1 + len, r + 1 + n, 0);
    pool[r] = n;
  }

  void ptrPush(int e, int v) {
    int[] p = ptrRef[e >>> PAGE_SHIFT];
    int r = p[e & PAGE_MASK];
    int len = r == 0 ? 0 : pool[r];
    r = extend(p, e & PAGE_MASK, r, len, len + 1);
    pool[r + 1 + len] = v;
    pool[r] = len + 1;
  }

  /**
   * Makes room in the list at ref r (length len) for n elements; extends it in place if it is the
   * last list in the pool (the usual way lists grow), else moves it to the end. Returns the ref.
   * The length field is not updated.
   */
  private int extend(int[] refs, int slot, int r, int len, int n) {
    if (r != 0 && r + 1 + len == poolEnd) {
      reserve((long) r + 1 + n);
      poolEnd = r + 1 + n;
      return r;
    }
    int nr = poolEnd;
    reserve((long) nr + 1 + n);
    if (r != 0) {
      System.arraycopy(pool, r + 1, pool, nr + 1, len);
      garbage += len + 1;
    }
    poolEnd = nr + 1 + n;
    refs[slot] = nr;
    return nr;
  }

  private void reserve(long end) {
    if (end > pool.length) {
      if (end > Integer.MAX_VALUE - 8) throw new OutOfMemoryError("Out of memory");
      long n = Math.max(end, Math.min((long) pool.length + (pool.length >> 1), Integer.MAX_VALUE - 8));
      pool = Arrays.copyOf(pool, (int) n);
    }
  }

  /** Drops the unused space in the pointer pool (lists that were replaced, growth reserve). */
  void compact() {
    if (garbage * 4L > poolEnd) {
      long live = 1;
      for (int e = 0; e < size; e++) {
        int r = ptrRef[e >>> PAGE_SHIFT][e & PAGE_MASK];
        if (r != 0) live += pool[r] + 1L;
      }
      int[] np = new int[(int) live];
      int end = 1;
      for (int e = 0; e < size; e++) {
        int[] p = ptrRef[e >>> PAGE_SHIFT];
        int r = p[e & PAGE_MASK];
        if (r == 0) continue;
        int len = pool[r];
        System.arraycopy(pool, r, np, end, len + 1);
        p[e & PAGE_MASK] = end;
        end += len + 1;
      }
      pool = np;
      poolEnd = end;
      garbage = 0;
    } else if (pool.length - poolEnd > poolEnd / 16) {
      pool = Arrays.copyOf(pool, poolEnd);
    }
  }

  /** True if the pointer lists of a (in this table) and b (in o) are equal. */
  boolean samePtr(int a, FileTable o, int b) {
    int n = ptrSize(a);
    if (n != o.ptrSize(b)) return false;
    if (n == 0) return true;
    int ra = ptrRef[a >>> PAGE_SHIFT][a & PAGE_MASK];
    int rb = o.ptrRef[b >>> PAGE_SHIFT][b & PAGE_MASK];
    return Arrays.equals(pool, ra + 1, ra + 1 + n, o.pool, rb + 1, rb + 1 + n);
  }

  /** Lexicographic comparison of two pointer lists as vectors of unsigned ints. */
  int comparePtr(int a, FileTable o, int b) {
    int na = ptrSize(a);
    int nb = o.ptrSize(b);
    int n = Math.min(na, nb);
    if (n > 0) {
      int ra = ptrRef[a >>> PAGE_SHIFT][a & PAGE_MASK];
      int rb = o.ptrRef[b >>> PAGE_SHIFT][b & PAGE_MASK];
      for (int i = 1; i <= n; i++) {
        int c = Integer.compareUnsigned(pool[ra + i], o.pool[rb + i]);
        if (c != 0) return c;
      }
    }
    return Integer.compare(na, nb);
  }

  // ---------------------------------------------------------------------------------------
  // Ordered access

  /** Entries in the order of their names. Valid until an entry is added. */
  private int[] order() {
    int[] o = order;
    if (o == null) {
      o = new int[size];
      for (int i = 0; i < o.length; i++) o[i] = i;
      mergeSort(o, new int[o.length], 0, o.length);
      order = o;
    }
    return o;
  }

  private void mergeSort(int[] a, int[] tmp, int lo, int hi) {
    if (hi - lo < 2) return;
    if (hi - lo <= 12) {  // insertion sort
      for (int i = lo + 1; i < hi; i++) {
        int v = a[i];
        int k = i - 1;
        while (k >= lo && compareNames(a[k], v) > 0) {
          a[k + 1] = a[k];
          k--;
        }
        a[k + 1] = v;
      }
      return;
    }
    int mid = (lo + hi) >>> 1;
    mergeSort(a, tmp, lo, mid);
    mergeSort(a, tmp, mid, hi);
    if (compareNames(a[mid - 1], a[mid]) <= 0) return;  // already in order
    System.arraycopy(a, lo, tmp, lo, mid - lo);
    int i = lo;
    int j = mid;
    int k = lo;
    while (i < mid && j < hi) a[k++] = compareNames(a[j], tmp[i]) < 0 ? a[j++] : tmp[i++];
    while (i < mid) a[k++] = tmp[i++];
  }

  /** Iteration over entries by id, in the given order (null = ids ascending), or reversed. */
  private final class Entries implements Iterable<DT>, Iterator<DT> {
    private final int[] ids;
    private final int n;
    private final boolean reverse;
    private int i;

    Entries(int[] ids, int n, boolean reverse) {
      this.ids = ids;
      this.n = n;
      this.reverse = reverse;
      this.i = reverse ? n - 1 : 0;
    }

    @Override public Iterator<DT> iterator() {
      return new Entries(ids, n, reverse);
    }

    @Override public boolean hasNext() {
      return reverse ? i >= 0 : i < n;
    }

    @Override public DT next() {
      if (!hasNext()) throw new NoSuchElementException();
      int e = ids == null ? i : ids[i];
      i += reverse ? -1 : 1;
      return new DT(FileTable.this, e);
    }
  }

  /** The files in the order they were added (no sorting). */
  Iterable<DT> unordered() {
    return new Entries(null, size, false);
  }

  /** The files in the order of their names. */
  Iterable<DT> values() {
    return new Entries(order(), size, false);
  }

  /** The files in the reverse order of their names. */
  Iterable<DT> descendingValues() {
    return new Entries(order(), size, true);
  }

  // ---------------------------------------------------------------------------------------
  // DT access

  DT at(int e) {
    return new DT(this, e);
  }

  /** The file named s or null. */
  DT get(String s) {
    int e = find(s);
    return e < 0 ? null : new DT(this, e);
  }

  /** The file named by the first len chars of s (whose String.hashCode() is raw) or null. */
  DT getPrefix(String s, int len, int raw) {
    int e = findPrefix(s, len, raw);
    return e < 0 ? null : new DT(this, e);
  }

  /** dt[s] of the C++ map: the existing entry or a new default one. */
  DT getOrAdd(String s) {
    return new DT(this, findOrAdd(s));
  }

  /** Replaces the entry s by a new default one (dt[s] = DT()). */
  DT put(String s) {
    int e = findOrAdd(s);
    reset(e);
    return new DT(this, e);
  }
}
