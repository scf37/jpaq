package io.github.scf37.jpaq.cli;

import java.util.Arrays;

/** A filename entry: date, size, attributes and the list of fragment pointers. */
final class DT {
  /** File name (key in the map). */
  String name;
  long date;   // decimal YYYYMMDDHHMMSS (UT) or 0 if deleted
  long size;   // size or -1 if unknown
  long attr;   // first 8 attribute bytes
  long data;   // sort key or frags written. -1 = do not write
  private int[] ptr = new int[0];
  private int nptr;

  DT() { }

  DT(String name) {
    this.name = name;
  }

  int ptrSize() {
    return nptr;
  }

  int ptr(int i) {
    return ptr[i];
  }

  void ptrResize(int n) {
    if (n > ptr.length) ptr = Arrays.copyOf(ptr, n);
    if (n > nptr) Arrays.fill(ptr, nptr, n, 0);
    nptr = n;
  }

  void setPtr(int i, int v) {
    ptr[i] = v;
  }

  void ptrPush(int v) {
    if (nptr == ptr.length) ptr = Arrays.copyOf(ptr, Math.max(4, nptr * 2));
    ptr[nptr++] = v;
  }

  void ptrClear() {
    nptr = 0;
  }

  /** True if the pointer lists are equal. */
  boolean samePtr(DT o) {
    return Arrays.equals(ptr, 0, nptr, o.ptr, 0, o.nptr);
  }

  /** Lexicographic comparison of the pointer lists as vectors of unsigned ints. */
  int comparePtr(DT o) {
    int n = Math.min(nptr, o.nptr);
    for (int i = 0; i < n; i++) {
      int c = Integer.compareUnsigned(ptr[i], o.ptr[i]);
      if (c != 0) return c;
    }
    return Integer.compare(nptr, o.nptr);
  }

  /** Copy of all fields (the C++ DT assignment). */
  DT copy() {
    DT d = new DT(name);
    d.date = date;
    d.size = size;
    d.attr = attr;
    d.data = data;
    d.ptr = Arrays.copyOf(ptr, nptr);
    d.nptr = nptr;
    return d;
  }
}
