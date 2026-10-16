package io.github.scf37.jpaq.engine;

/** Conversions between decimal dates (YYYYMMDDHHMMSS), unix time and printable forms. */
public final class Dates {
  private Dates() {}

  /** Converts 64 bit decimal YYYYMMDDHHMMSS to "YYYY-MM-DD HH:MM:SS"; -1 unknown, 0 deleted. */
  public static String dateToString(long date) {
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

  /** Converts seconds since 1/1/1970 to decimal YYYYMMDDHHMMSS. Valid from 1970 to 2099. */
  public static long decimalTime(long tt) {
    if (tt == -1) tt = 0;
    long t = tt;
    final int second = (int) (t % 60);
    final int minute = (int) (t / 60 % 60);
    final int hour = (int) (t / 3600 % 24);
    t /= 86400;  // days since Jan 1 1970
    final int term = (int) (t / 1461);  // 4 year terms since 1970
    t %= 1461;
    t += (t >= 59) ? 1 : 0;  // insert Feb 29 on non leap years
    t += (t >= 425) ? 1 : 0;
    t += (t >= 1157) ? 1 : 0;
    final int year = (int) (term * 4 + t / 366 + 1970);  // actual year
    t %= 366;
    t += (t >= 60) ? 2 : 0;  // make Feb. 31 days
    t += (t >= 123) ? 1 : 0;  // insert Apr 31
    t += (t >= 185) ? 1 : 0;  // insert June 31
    t += (t >= 278) ? 1 : 0;  // insert Sept 31
    t += (t >= 340) ? 1 : 0;  // insert Nov 31
    final int month = (int) (t / 31 + 1);
    final int day = (int) (t % 31 + 1);
    return year * 10000000000L + month * 100000000L + day * 1000000L + hour * 10000L
        + minute * 100L + second;
  }

  /** Converts decimal date to unix time - inverse of decimalTime(). -1 if date &lt;= 0. */
  public static long unixTime(long date) {
    if (date <= 0) return -1;
    final int[] days = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
    final int year = (int) (date / 10000000000L % 10000);
    final int month = (int) ((date / 100000000 % 100 - 1) % 12);
    final int day = (int) (date / 1000000 % 100);
    final int hour = (int) (date / 10000 % 100);
    final int min = (int) (date / 100 % 100);
    final int sec = (int) (date % 100);
    // int arithmetic as in the C++ (time_t is 64 bit but the sum is computed in int)
    long d = (day - 1 + days[month] + ((year % 4 == 0 && month > 1) ? 1 : 0)
        + ((year - 1970) * 1461 + 1) / 4);
    return d * 86400L + hour * 3600 + min * 60 + sec;
  }
}
