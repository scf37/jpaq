package io.github.scf37.jpaq.engine;

import java.util.Arrays;

/** A growable list of ints. */
final class IntList {
  private static final int[] EMPTY = new int[0];

  private int[] a = EMPTY;
  private int n;

  int size() {
    return n;
  }

  int get(int i) {
    if (i >= n) throw new IndexOutOfBoundsException(i);
    return a[i];
  }

  void add(int v) {
    if (n == a.length) a = Arrays.copyOf(a, Math.max(4, n + (n >> 1)));
    a[n++] = v;
  }
}
