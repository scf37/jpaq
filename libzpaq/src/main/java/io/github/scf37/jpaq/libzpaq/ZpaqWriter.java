package io.github.scf37.jpaq.libzpaq;

/**
 * Byte sink. {@link #put(int)} must be implemented; {@link #write(byte[], int, int)} may be
 * overridden for efficiency.
 */
public interface ZpaqWriter {
  /** Writes the low 8 bits of {@code c}. */
  void put(int c);

  /** Writes {@code buf[off..off+n-1]}. */
  default void write(byte[] buf, int off, int n) {
    for (int i = 0; i < n; i++) put(buf[off + i]);
  }
}
