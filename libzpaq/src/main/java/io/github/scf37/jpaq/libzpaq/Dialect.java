package io.github.scf37.jpaq.libzpaq;

/**
 * Block magic of a ZPAQ stream. {@link #STANDARD} blocks start with the locator tag and "zPQ" and
 * are what every ZPAQ program reads. {@link #JPAQ} blocks are identical except that the third
 * magic byte from the end of the block start is 'j' ("jPQ"), so that stock ZPAQ finds no blocks in
 * such a file at all, while this library reads both kinds.
 */
public enum Dialect {
  STANDARD('z'),
  JPAQ('j');

  private final int marker;

  Dialect(int marker) {
    this.marker = marker;
  }

  /** The first byte of the three byte block magic ("zPQ" / "jPQ"). */
  int marker() {
    return marker;
  }
}
