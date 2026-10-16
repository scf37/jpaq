package io.github.scf37.jpaq.engine;

import java.nio.charset.StandardCharsets;

/** String helpers. All strings are "byte strings": each char is one byte (0..255). */
public final class Strs {
  private Strs() {}

  /**
   * Converts a Java string to a byte string holding its UTF-8 encoding. Chars U+DC80..U+DCFF
   * (lone surrogates, see {@link #fromBytes}) stand for the bytes 0x80..0xFF.
   */
  public static String toBytes(String s) {
    StringBuilder r = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); ) {
      int c = s.codePointAt(i);
      i += Character.charCount(c);
      if (c < 0x80) {
        r.append((char) c);
      } else if (c >= 0xDC80 && c <= 0xDCFF) {
        r.append((char) (c - 0xDC00));
      } else if (c < 0x800) {
        r.append((char) (0xC0 | c >> 6)).append((char) (0x80 | c & 63));
      } else if (c < 0x10000) {
        r.append((char) (0xE0 | c >> 12)).append((char) (0x80 | c >> 6 & 63))
            .append((char) (0x80 | c & 63));
      } else {
        r.append((char) (0xF0 | c >> 18)).append((char) (0x80 | c >> 12 & 63))
            .append((char) (0x80 | c >> 6 & 63)).append((char) (0x80 | c & 63));
      }
    }
    return r.toString();
  }

  /**
   * Converts a byte string holding UTF-8 to a Java string. Bytes that are not part of valid UTF-8
   * become the chars U+DC80..U+DCFF, so that {@link #toBytes} gives the same bytes back.
   */
  public static String fromBytes(String b) {
    StringBuilder r = new StringBuilder(b.length());
    final int n = b.length();
    for (int i = 0; i < n; ) {
      int c = b.charAt(i);
      int len = c < 0x80 ? 1 : c >= 0xC2 && c < 0xE0 ? 2 : c >= 0xE0 && c < 0xF0 ? 3
          : c >= 0xF0 && c < 0xF5 ? 4 : 0;
      int cp = len == 2 ? c & 31 : len == 3 ? c & 15 : c & 7;
      boolean ok = len > 0 && i + len <= n;
      for (int k = 1; ok && k < len; k++) {
        int d = b.charAt(i + k);
        if ((d & 0xC0) != 0x80) ok = false;
        cp = cp << 6 | (d & 63);
      }
      if (ok && len == 1) cp = c;
      if (ok && ((len == 3 && (cp < 0x800 || (cp >= 0xD800 && cp < 0xE000)))
          || (len == 4 && (cp < 0x10000 || cp > 0x10FFFF)))) ok = false;
      if (ok) {
        r.appendCodePoint(cp);
        i += len;
      } else {
        r.append((char) (0xDC00 + c));
        i++;
      }
    }
    return r.toString();
  }

  /** Converts non-negative decimal number x to string of at least n digits. */
  static String itos(long x, int n) {
    StringBuilder r = new StringBuilder();
    for (; x != 0 || n > 0; x /= 10, --n) r.append((char) ('0' + x % 10));
    return r.reverse().toString();
  }

  static String itos(long x) {
    return itos(x, 1);
  }

  /** Replace * and ? in fn with part or digits of part. */
  static String subpart(String fn, int part) {
    StringBuilder s = new StringBuilder(fn);
    for (int j = s.length() - 1; j >= 0; --j) {
      char c = s.charAt(j);
      if (c == '?') {
        s.setCharAt(j, (char) ('0' + part % 10));
        part /= 10;
      } else if (c == '*') {
        s.replace(j, j + 1, itos(part));
        part = 0;
      }
    }
    return s.toString();
  }

  /** C atoi(): optional white space, sign, digits. */
  public static int atoi(String s, int p) {
    return (int) atol(s, p);
  }

  public static int atoi(String s) {
    return atoi(s, 0);
  }

  public static long atol(String s, int p) {
    int n = s.length();
    while (p < n && (s.charAt(p) == ' ' || (s.charAt(p) >= 9 && s.charAt(p) <= 13))) ++p;
    boolean neg = false;
    if (p < n && s.charAt(p) == '-') {
      neg = true;
      ++p;
    } else if (p < n && s.charAt(p) == '+') {
      ++p;
    }
    long r = 0;
    while (p < n && s.charAt(p) >= '0' && s.charAt(p) <= '9') {
      r = r * 10 + (s.charAt(p++) - '0');
    }
    return neg ? -r : r;
  }

  public static long atol(String s) {
    return atol(s, 0);
  }

  public static boolean isdigit(int c) {
    return c >= '0' && c <= '9';
  }

  static boolean isalnum(int c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /** char at i or 0 past the end, like reading a NUL terminated C string. */
  public static char at(String s, int i) {
    return i >= 0 && i < s.length() ? s.charAt(i) : 0;
  }

  /** Index of the last occurrence of c, or -1. */
  static int strrchr(String s, char c) {
    return s.lastIndexOf(c);
  }

  /** Byte string to bytes. */
  static byte[] getBytes(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  /** Bytes to byte string. */
  static String ofBytes(byte[] b, int off, int len) {
    return new String(b, off, len, StandardCharsets.ISO_8859_1);
  }
}
