package io.github.scf37.jpaq.libzpaq;

/**
 * Byte source. {@link #get()} must be implemented; {@link #read(byte[], int, int)} may be
 * overridden for efficiency.
 */
public interface ZpaqReader {
  /** Returns the next byte (0..255), or -1 at end of input. */
  int get();

  /**
   * Reads up to {@code n} bytes into {@code buf[off..off+n-1]}.
   *
   * @return the number of bytes read, 0 at end of input
   */
  default int read(byte[] buf, int off, int n) {
    int i = 0;
    int c;
    while (i < n && (c = get()) >= 0) buf[off + i++] = (byte) c;
    return i;
  }
}
