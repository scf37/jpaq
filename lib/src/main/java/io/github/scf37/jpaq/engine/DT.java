package io.github.scf37.jpaq.engine;

/**
 * A filename entry: date, size, attributes and the list of fragment pointers. This is a handle
 * to an entry of a {@link FileTable}; it is cheap to create and compares equal to every handle
 * of the same entry.
 */
final class DT {
  final FileTable t;
  final int id;

  DT(FileTable t, int id) {
    this.t = t;
    this.id = id;
  }

  /** File name (key in the map). */
  String name() {
    return t.name(id);
  }

  /** Decimal YYYYMMDDHHMMSS (UT) or 0 if deleted. */
  long date() {
    return t.date(id);
  }

  void date(long v) {
    t.setDate(id, v);
  }

  /** Size or -1 if unknown. */
  long size() {
    return t.size(id);
  }

  void size(long v) {
    t.setSize(id, v);
  }

  /** First 8 attribute bytes. */
  long attr() {
    return t.attr(id);
  }

  void attr(long v) {
    t.setAttr(id, v);
  }

  /** Sort key or frags written. -1 = do not write. */
  long data() {
    return t.data(id);
  }

  void data(long v) {
    t.setData(id, v);
  }

  int ptrSize() {
    return t.ptrSize(id);
  }

  int ptr(int i) {
    return t.ptr(id, i);
  }

  void ptrResize(int n) {
    t.ptrResize(id, n);
  }

  void setPtr(int i, int v) {
    t.setPtr(id, i, v);
  }

  void ptrPush(int v) {
    t.ptrPush(id, v);
  }

  void ptrClear() {
    t.ptrClear(id);
  }

  /** True if the pointer lists are equal. */
  boolean samePtr(DT o) {
    return t.samePtr(id, o.t, o.id);
  }

  /** Lexicographic comparison of the pointer lists as vectors of unsigned ints. */
  int comparePtr(DT o) {
    return t.comparePtr(id, o.t, o.id);
  }

  /** Compares the names like String.compareTo, without creating the strings. */
  int compareName(DT o) {
    if (t == o.t) return t.compareNames(id, o.id);
    return name().compareTo(o.name());
  }

  /** True if the name is empty or ends with '/' (a directory). */
  boolean isDir() {
    int n = t.nameLength(id);
    return n > 0 && t.nameAt(id, n - 1) == '/';
  }

  @Override public boolean equals(Object o) {
    return o instanceof DT d && d.t == t && d.id == id;
  }

  @Override public int hashCode() {
    return id;
  }
}
