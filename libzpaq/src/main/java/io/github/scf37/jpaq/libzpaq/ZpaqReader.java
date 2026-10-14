package io.github.scf37.jpaq.libzpaq;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

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

  /** Adapts an {@link InputStream}; I/O errors are rethrown as {@link UncheckedIOException}. */
  static ZpaqReader of(InputStream in) {
    return new ZpaqReader() {
      private final byte[] one = new byte[1];

      @Override
      public int get() {
        return read(one, 0, 1) == 1 ? one[0] & 255 : -1;
      }

      @Override
      public int read(byte[] buf, int off, int n) {
        try {
          int total = 0;
          while (total < n) {
            int r = in.read(buf, off + total, n - total);
            if (r <= 0) break;
            total += r;
          }
          return total;
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    };
  }

  /** Reads from a byte array. */
  static ZpaqReader of(byte[] data) {
    return of(data, 0, data.length);
  }

  /** Reads from {@code data[off..off+len-1]}. */
  static ZpaqReader of(byte[] data, int off, int len) {
    return new ZpaqReader() {
      private int pos = off;
      private final int end = off + len;

      @Override
      public int get() {
        return pos < end ? data[pos++] & 255 : -1;
      }

      @Override
      public int read(byte[] buf, int o, int n) {
        n = Math.min(n, end - pos);
        if (n <= 0) return 0;
        System.arraycopy(data, pos, buf, o, n);
        pos += n;
        return n;
      }
    };
  }
}
