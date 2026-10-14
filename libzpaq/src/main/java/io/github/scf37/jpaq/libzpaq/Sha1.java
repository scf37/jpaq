package io.github.scf37.jpaq.libzpaq;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-1 checksum computer (libzpaq::SHA1). {@link #result()} returns the hash and restarts. */
public final class Sha1 {
  private final MessageDigest md;
  private final byte[] stage = new byte[256];
  private int staged;
  private long len;

  public Sha1() {
    try {
      md = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Hashes one byte. */
  public void put(int c) {
    if (staged == stage.length) flushStage();
    stage[staged++] = (byte) c;
    len++;
  }

  /** Hashes {@code buf[off..off+n-1]}. */
  public void write(byte[] buf, int off, int n) {
    if (n <= 0) return;
    flushStage();
    md.update(buf, off, n);
    len += n;
  }

  private void flushStage() {
    if (staged > 0) {
      md.update(stage, 0, staged);
      staged = 0;
    }
  }

  /** Number of bytes hashed since the last {@link #result()}. */
  public long usize() {
    return len;
  }

  /** Number of bytes hashed since the last {@link #result()}. */
  public double size() {
    return len;
  }

  /** Returns the 20 byte hash of the data so far and starts a new hash. */
  public byte[] result() {
    flushStage();
    len = 0;
    return md.digest();
  }
}
