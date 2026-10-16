package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.Names;
import java.nio.charset.StandardCharsets;

/**
 * Text of the command line. Arguments, names and output are "byte strings" (a String whose chars
 * are the bytes 0..255), like the char* of the C++ program: patterns match bytes, output is the
 * bytes of the names. The API takes and gives real strings ({@link Names}).
 */
final class Text {
  private Text() {}

  /** Byte string of a real string. */
  static String bytes(String s) {
    return new String(Names.encode(s), StandardCharsets.ISO_8859_1);
  }

  /** Real string of a byte string. */
  static String text(String b) {
    return Names.decode(b.getBytes(StandardCharsets.ISO_8859_1));
  }

  /** char at i or 0 past the end, like reading a NUL terminated C string. */
  static char at(String s, int i) {
    return i >= 0 && i < s.length() ? s.charAt(i) : 0;
  }

  static boolean isdigit(int c) {
    return c >= '0' && c <= '9';
  }

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
    while (p < n && s.charAt(p) >= '0' && s.charAt(p) <= '9') r = r * 10 + (s.charAt(p++) - '0');
    return neg ? -r : r;
  }

  static long atol(String s) {
    return atol(s, 0);
  }

  /** Converts non-negative decimal number x to string of at least n digits. */
  static String itos(long x, int n) {
    StringBuilder r = new StringBuilder();
    for (; x != 0 || n > 0; x /= 10, --n) r.append((char) ('0' + x % 10));
    return r.reverse().toString();
  }

  /** Converts 64 bit decimal YYYYMMDDHHMMSS to "YYYY-MM-DD HH:MM:SS"; -1 unknown, 0 deleted. */
  static String dateToString(long date) {
    if (date <= 0) return "                   ";
    char[] s = "0000-00-00 00:00:00".toCharArray();
    final int[] t = {18, 17, 15, 14, 12, 11, 9, 8, 6, 5, 3, 2, 1, 0};
    for (int i = 0; i < 14; ++i) {
      s[t[i]] += (int) (date % 10);
      date /= 10;
    }
    return new String(s);
  }

  /** Converts attributes to a readable format. */
  static String attrToString(long attrib) {
    StringBuilder r = new StringBuilder("     ");
    if ((attrib & 255) == 'u') {
      r.setCharAt(0, "0pc3d5b7 9lBsDEF".charAt((int) ((attrib >> 20) & 15)));
      for (int i = 0; i < 4; ++i) r.setCharAt(4 - i, (char) ((attrib >> (8 + 3 * i)) % 8 + '0'));
    } else if ((attrib & 255) == 'w') {
      for (int i = 0, j = 0; i < 32; ++i) {
        if (((attrib >> (i + 8)) & 1) != 0) {
          char c = "RHS DAdFTprCoIEivs89012345678901".charAt(i);
          if (j < 5) r.setCharAt(j, c);
          else r.append(c);
          ++j;
        }
      }
    }
    return r.toString();
  }

  /** printf with the C locale. */
  static String f(String format, Object... args) {
    return String.format(java.util.Locale.ROOT, format, args);
  }
}
