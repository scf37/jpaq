package io.github.scf37.jpaq.libzpaq;

import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/** Key stretching (scrypt) and random number generation used by encrypted archives. */
public final class Crypto {
  private Crypto() {}

  /** Source of random bytes, replaceable for reproducible output in tests. */
  @FunctionalInterface
  public interface RandomSource {
    void nextBytes(byte[] buf);
  }

  private static final SecureRandom SECURE = new SecureRandom();
  private static volatile RandomSource source = SECURE::nextBytes;

  /** Replaces the random source (null restores the system secure random generator). */
  public static void setRandomSource(RandomSource s) {
    source = s != null ? s : SECURE::nextBytes;
  }

  /**
   * Fills {@code buf[0..n-1]} with cryptographic random bytes. The first byte is never '7' or
   * 'z' (start of a ZPAQ archive). For a pure random string discard the first byte.
   */
  public static void random(byte[] buf, int n) {
    byte[] t = new byte[n];
    source.nextBytes(t);
    System.arraycopy(t, 0, buf, 0, n);
    if (n >= 1 && (buf[0] == 'z' || buf[0] == '7')) buf[0] ^= (byte) 0x80;
  }

  // PBKDF2-HMAC-SHA256 with one iteration.
  private static void pbkdf2(byte[] pw, int pwLen, byte[] salt, int saltLen, byte[] buf,
      int bufOff, int dkLen) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pwLen == 0 ? new byte[1] : pw, 0, Math.max(pwLen, 1), "HmacSHA256"));
      for (int i = 1; i * 32 <= dkLen; i++) {
        mac.update(salt, 0, saltLen);
        mac.update(new byte[] {(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
        byte[] t = mac.doFinal();
        System.arraycopy(t, 0, buf, bufOff + i * 32 - 32, 32);
      }
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int rotl(int a, int b) {
    return (a << b) + (a >>> (32 - b));
  }

  // Hash b[off..off+15] using 8 rounds of salsa20
  private static void salsa8(int[] b, int off) {
    int[] x = new int[16];
    System.arraycopy(b, off, x, 0, 16);
    for (int i = 0; i < 4; i++) {
      x[4] ^= rotl(x[0] + x[12], 7);
      x[8] ^= rotl(x[4] + x[0], 9);
      x[12] ^= rotl(x[8] + x[4], 13);
      x[0] ^= rotl(x[12] + x[8], 18);
      x[9] ^= rotl(x[5] + x[1], 7);
      x[13] ^= rotl(x[9] + x[5], 9);
      x[1] ^= rotl(x[13] + x[9], 13);
      x[5] ^= rotl(x[1] + x[13], 18);
      x[14] ^= rotl(x[10] + x[6], 7);
      x[2] ^= rotl(x[14] + x[10], 9);
      x[6] ^= rotl(x[2] + x[14], 13);
      x[10] ^= rotl(x[6] + x[2], 18);
      x[3] ^= rotl(x[15] + x[11], 7);
      x[7] ^= rotl(x[3] + x[15], 9);
      x[11] ^= rotl(x[7] + x[3], 13);
      x[15] ^= rotl(x[11] + x[7], 18);
      x[1] ^= rotl(x[0] + x[3], 7);
      x[2] ^= rotl(x[1] + x[0], 9);
      x[3] ^= rotl(x[2] + x[1], 13);
      x[0] ^= rotl(x[3] + x[2], 18);
      x[6] ^= rotl(x[5] + x[4], 7);
      x[7] ^= rotl(x[6] + x[5], 9);
      x[4] ^= rotl(x[7] + x[6], 13);
      x[5] ^= rotl(x[4] + x[7], 18);
      x[11] ^= rotl(x[10] + x[9], 7);
      x[8] ^= rotl(x[11] + x[10], 9);
      x[9] ^= rotl(x[8] + x[11], 13);
      x[10] ^= rotl(x[9] + x[8], 18);
      x[12] ^= rotl(x[15] + x[14], 7);
      x[13] ^= rotl(x[12] + x[15], 9);
      x[14] ^= rotl(x[13] + x[12], 13);
      x[15] ^= rotl(x[14] + x[13], 18);
    }
    for (int i = 0; i < 16; i++) b[off + i] += x[i];
  }

  // BlockMix_{Salsa20/8, r} on b[off..off+32r-1]
  private static void blockmix(int[] b, int off, int r) {
    int[] x = new int[16];
    int[] y = new int[32 * r];
    System.arraycopy(b, off + 32 * r - 16, x, 0, 16);
    for (int i = 0; i < 2 * r; i++) {
      for (int j = 0; j < 16; j++) x[j] ^= b[off + i * 16 + j];
      salsa8(x, 0);
      System.arraycopy(x, 0, y, i * 16, 16);
    }
    for (int i = 0; i < r; i++) System.arraycopy(y, i * 32, b, off + i * 16, 16);
    for (int i = 0; i < r; i++) System.arraycopy(y, i * 32 + 16, b, off + (i + r) * 16, 16);
  }

  // Mix b[off..off+128r-1]. Uses 128*r*n bytes of memory and O(r*n) time
  private static void smix(byte[] b, int off, int r, int n) {
    int[] x = new int[32 * r];
    int[] v = new int[32 * r * n];
    for (int i = 0; i < r * 128; i++) x[i / 4] += (b[off + i] & 255) << (i % 4 * 8);
    for (int i = 0; i < n; i++) {
      System.arraycopy(x, 0, v, i * r * 32, r * 32);
      blockmix(x, 0, r);
    }
    for (int i = 0; i < n; i++) {
      int j = x[(2 * r - 1) * 16] & (n - 1);
      for (int k = 0; k < r * 32; k++) x[k] ^= v[j * r * 32 + k];
      blockmix(x, 0, r);
    }
    for (int i = 0; i < r * 128; i++) b[off + i] = (byte) (x[i / 4] >>> (i % 4 * 8));
  }

  /**
   * Strengthens password {@code pw[0..pwlen-1]} and salt {@code salt[0..saltlen-1]} into key
   * {@code buf[0..buflen-1]} using scrypt. Uses O(n*r*p) time and 128*r*n bytes of memory. n must
   * be a power of 2 and r &lt;= 8.
   */
  public static void scrypt(byte[] pw, int pwlen, byte[] salt, int saltlen, int n, int r, int p,
      byte[] buf, int buflen) {
    if (r > 8 || n <= 0 || (n & (n - 1)) != 0) throw ZpaqException.error("invalid scrypt parameters");
    byte[] b = new byte[p * r * 128];
    pbkdf2(pw, pwlen, salt, saltlen, b, 0, p * r * 128);
    for (int i = 0; i < p; i++) smix(b, i * r * 128, r, n);
    pbkdf2(pw, pwlen, b, p * r * 128, buf, 0, buflen);
  }

  /**
   * Generates a strong 32 byte key from {@code key[0..31]} (assumed to be the SHA-256 hash of the
   * password) and {@code salt[0..31]} using scrypt with N=16384, r=8, p=1.
   */
  public static byte[] stretchKey(byte[] key, byte[] salt) {
    byte[] out = new byte[32];
    scrypt(key, 32, salt, 32, 1 << 14, 8, 1, out, 32);
    return out;
  }
}
