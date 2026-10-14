package io.github.scf37.jpaq.libzpaq;

/** The E8E9 transform that improves compression of x86 code. */
final class E8E9 {
  private E8E9() {}

  /**
   * Transforms buf[0..n-1]: patterns (E8|E9 xx xx xx 00|FF) at offset i replace the 3 middle bytes
   * with x+i mod 2^24, LSB first, reading backward.
   */
  static void transform(byte[] buf, int n) {
    for (int i = n - 5; i >= 0; --i) {
      if (((buf[i] & 254) == 0xe8) && (((buf[i + 4] & 255) + 1) & 254) == 0) {
        int a = ((buf[i + 1] & 255) | (buf[i + 2] & 255) << 8 | (buf[i + 3] & 255) << 16) + i;
        buf[i + 1] = (byte) a;
        buf[i + 2] = (byte) (a >> 8);
        buf[i + 3] = (byte) (a >> 16);
      }
    }
  }
}
