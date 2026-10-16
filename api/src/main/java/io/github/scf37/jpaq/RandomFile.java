package io.github.scf37.jpaq;

import java.io.Closeable;
import java.io.IOException;

/** A file with a position, like a C stdio stream opened "rb+" (see {@link Capability#RANDOM_WRITE}). */
public interface RandomFile extends Closeable {
  /** Reads up to {@code len} bytes at the current position; returns 0 at the end of the file. */
  int read(byte[] buf, int off, int len) throws IOException;

  void write(byte[] buf, int off, int len) throws IOException;

  /** Sets the position, which may be past the end of the file. */
  void seek(long pos) throws IOException;

  long length() throws IOException;

  void setLength(long len) throws IOException;
}
