package io.github.scf37.jpaq.libzpaq;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 checksum computer (libzpaq::SHA256). {@link #result()} returns the hash and restarts. */
public final class Sha256 {
  private final MessageDigest md;
  private long len;

  public Sha256() {
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Hashes one byte. */
  public void put(int c) {
    md.update((byte) c);
    len++;
  }

  /** Hashes {@code buf[off..off+n-1]}. */
  public void write(byte[] buf, int off, int n) {
    md.update(buf, off, n);
    len += n;
  }

  /** Number of bytes hashed since the last {@link #result()}. */
  public long usize() {
    return len;
  }

  /** Returns the 32 byte hash of the data so far and starts a new hash. */
  public byte[] result() {
    len = 0;
    return md.digest();
  }
}
