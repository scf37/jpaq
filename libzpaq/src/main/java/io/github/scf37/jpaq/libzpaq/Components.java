package io.github.scf37.jpaq.libzpaq;

/** ZPAQ component type codes and their encoded sizes in a block header. */
final class Components {
  private Components() {}

  static final int NONE = 0;
  static final int CONS = 1;
  static final int CM = 2;
  static final int ICM = 3;
  static final int MATCH = 4;
  static final int AVG = 5;
  static final int MIX2 = 6;
  static final int MIX = 7;
  static final int ISSE = 8;
  static final int SSE = 9;

  /** Number of bytes to encode a component of each type (0 = invalid). */
  private static final int[] SIZE = {0, 2, 3, 2, 3, 4, 6, 6, 3, 5};

  /** Component names as used in ZPAQL source. */
  static final String[] NAMES = {"", "const", "cm", "icm", "match", "avg", "mix2", "mix",
      "isse", "sse"};

  /** {@link #NAMES} followed by a null terminator, for token matching. */
  static final String[] NAMES_NULL_TERMINATED = {"", "const", "cm", "icm", "match", "avg", "mix2",
      "mix", "isse", "sse", null};

  static int size(int type) {
    return type >= 0 && type < SIZE.length ? SIZE[type] : 0;
  }
}
