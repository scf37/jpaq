package io.github.scf37.jpaq.libzpaq;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES in CTR mode with random access (libzpaq::AES_CTR). The i'th 16 byte block of the stream
 * is XORed with AES(iv0, iv1, i>>32, i&0xffffffff), all words MSB first. Encryption and
 * decryption are the same operation. Instances are not thread safe.
 */
public final class AesCtr {
  private final Cipher cipher;
  private final int iv0;
  private final int iv1;
  private final byte[] counters = new byte[16 * 256];
  private final byte[] keystream = new byte[16 * 256];

  /**
   * @param key 16, 24 or 32 byte key (the first {@code keylen} bytes of {@code key} are used)
   * @param keylen length of key
   * @param iv 8 byte initialization vector or null (all zero)
   */
  public AesCtr(byte[] key, int keylen, byte[] iv) {
    if (keylen != 16 && keylen != 24 && keylen != 32)
      throw ZpaqException.error("invalid AES key length");
    int a = 0;
    int b = 0;
    if (iv != null) {
      a = be32(iv, 0);
      b = be32(iv, 4);
    }
    iv0 = a;
    iv1 = b;
    try {
      cipher = Cipher.getInstance("AES/ECB/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, 0, keylen, "AES"));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int be32(byte[] b, int o) {
    return (b[o] & 255) << 24 | (b[o + 1] & 255) << 16 | (b[o + 2] & 255) << 8 | (b[o + 3] & 255);
  }

  private static void putBe32(byte[] b, int o, int v) {
    b[o] = (byte) (v >>> 24);
    b[o + 1] = (byte) (v >>> 16);
    b[o + 2] = (byte) (v >>> 8);
    b[o + 3] = (byte) v;
  }

  /**
   * Encrypts one block: the plaintext {@code s0 s1 s2 s3} (4 32-bit words, first byte of plaintext
   * in the high 8 bits of s0) to {@code ct[0..15]}.
   */
  public void encrypt(int s0, int s1, int s2, int s3, byte[] ct) {
    byte[] block = new byte[16];
    putBe32(block, 0, s0);
    putBe32(block, 4, s1);
    putBe32(block, 8, s2);
    putBe32(block, 12, s3);
    try {
      cipher.update(block, 0, 16, ct, 0);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Encrypts or decrypts {@code buf[off..off+n-1]} which sits at stream position {@code offset}. */
  public void encrypt(byte[] buf, int off, int n, long offset) {
    if (n <= 0) return;
    long block = offset >>> 4;
    int skip = (int) (offset & 15);
    int done = 0;
    try {
      while (done < n) {
        int blocks = Math.min(256, (skip + (n - done) + 15) >> 4);
        for (int k = 0; k < blocks; k++) {
          long i = block + k;
          int o = k * 16;
          putBe32(counters, o, iv0);
          putBe32(counters, o + 4, iv1);
          putBe32(counters, o + 8, (int) (i >>> 32));
          putBe32(counters, o + 12, (int) i);
        }
        cipher.update(counters, 0, blocks * 16, keystream, 0);
        int m = Math.min(n - done, blocks * 16 - skip);
        for (int j = 0; j < m; j++) buf[off + done + j] ^= keystream[skip + j];
        done += m;
        block += blocks;
        skip = 0;
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
