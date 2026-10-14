package io.github.scf37.jpaq.cli;

import java.nio.charset.StandardCharsets;

/** String helpers. All strings are "byte strings": each char is one byte (0..255). */
final class Strs {
  private Strs() {}

  /** Converts a Java string to a byte string holding its UTF-8 encoding. */
  static String toBytes(String s) {
    return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
  }

  /** Converts a byte string holding UTF-8 to a Java string. */
  static String fromBytes(String b) {
    return new String(b.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
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
  static int atoi(String s, int p) {
    return (int) atol(s, p);
  }

  static int atoi(String s) {
    return atoi(s, 0);
  }

  static long atol(String s, int p) {
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

  static long atol(String s) {
    return atol(s, 0);
  }

  static boolean isdigit(int c) {
    return c >= '0' && c <= '9';
  }

  static boolean isalnum(int c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /** char at i or 0 past the end, like reading a NUL terminated C string. */
  static char at(String s, int i) {
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
