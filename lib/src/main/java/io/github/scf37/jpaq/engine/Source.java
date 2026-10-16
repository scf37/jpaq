package io.github.scf37.jpaq.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The content of a file to add: a local file or a stream. */
interface Source {
  /** Reads up to len bytes; fewer only at the end of the input. Returns 0 at the end. */
  int read(byte[] buf, int off, int len);

  /** A stream as a source; read errors are thrown as {@link UncheckedIOException}. */
  static Source of(InputStream in) {
    return (buf, off, len) -> {
      try {
        return in.readNBytes(buf, off, len);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }
}
