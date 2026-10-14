package io.github.scf37.jpaq.libzpaq;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;

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

  /** Adapts an {@link OutputStream}; I/O errors are rethrown as {@link UncheckedIOException}. */
  static ZpaqWriter of(OutputStream out) {
    return new ZpaqWriter() {
      @Override
      public void put(int c) {
        try {
          out.write(c);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      @Override
      public void write(byte[] buf, int off, int n) {
        try {
          out.write(buf, off, n);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    };
  }
}
