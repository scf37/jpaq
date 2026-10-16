package io.github.scf37.jpaq.cli;

import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;

/**
 * The options of a zpaq command line, and the rules by which zpaq selects and renames files with
 * them. All text is byte strings (see {@link Text}).
 */
final class Args {
  static final boolean WINDOWS = !FileSystems.getDefault().supportedFileAttributeViews()
      .contains("unix") && System.getProperty("os.name", "").startsWith("Windows");

  /** Unless -until. */
  static final long DEFAULT_VERSION = 99999999999999L;

  char command;                      // command 'a', 'x', or 'l'
  String archive = "";               // archive name
  List<String> files = new ArrayList<>();  // filename args
  int all;                           // -all option
  boolean force;                     // -force option
  int fragment = 6;                  // -fragment option
  String index;                      // index option
  String key;                        // -key argument
  String method = "";                // default "1"
  boolean noattributes;              // -noattributes option
  List<String> notfiles = new ArrayList<>();   // list of prefixes to exclude
  String nottype = "";               // -not =...
  List<String> onlyfiles = new ArrayList<>();  // list of prefixes to include
  String repack;                     // -repack output file
  String newKey;                     // -repack key
  int summary;                       // summary option if > 0, detailed if -1
  boolean dotest;                    // -test option
  int threads;                       // default is number of cores
  List<String> tofiles = new ArrayList<>();    // -to option
  long date;                         // now as decimal YYYYMMDDHHMMSS (UT)
  long version = DEFAULT_VERSION;    // version number or 14 digit date

  /** In Windows converts upper case to lower case (identity on other systems). */
  private static int tolowerW(int c) {
    if (WINDOWS && c >= 'A' && c <= 'Z') return c - 'A' + 'a';
    return c;
  }

  /**
   * Returns true if strings a == b or a+"/" is a prefix of b or a ends in "/" and is a prefix of
   * b. Matches ? in a to any char in b. Matches * in a to any string in b. In Windows, not case
   * sensitive.
   */
  static boolean ispath(String a, String b) {
    return ispath(a, 0, b, 0);
  }

  private static boolean ispath(String a, int ai, String b, int bi) {
    for (; Text.at(a, ai) != 0; ++ai, ++bi) {
      final int ca = tolowerW(Text.at(a, ai));
      final int cb = tolowerW(Text.at(b, bi));
      if (ca == '*') {
        while (true) {
          if (ispath(a, ai + 1, b, bi)) return true;
          if (Text.at(b, bi) == 0) return false;
          ++bi;
        }
      } else if (ca == '?') {
        if (Text.at(b, bi) == 0) return false;
      } else if (ca == cb && ca == '/' && Text.at(a, ai + 1) == 0) {
        return true;
      } else if (ca != cb) {
        return false;
      }
    }
    return Text.at(b, bi) == 0 || Text.at(b, bi) == '/';
  }

  /** Returns a/b such that there is exactly one "/" in between. */
  static String appendPath(String a, String b) {
    int na = a.length();
    int nb = b.length();
    if (WINDOWS && nb > 1 && b.charAt(1) == ':') {  // remove : from drive letter
      if (nb > 2 && b.charAt(2) != '/') b = b.charAt(0) + "/" + b.substring(2);
      else b = b.charAt(0) + b.substring(2);
    }
    if (nb > 0 && b.charAt(0) == '/') b = b.substring(1);
    if (na > 0 && a.charAt(na - 1) == '/') a = a.substring(0, na - 1);
    return a + "/" + b;
  }

  /** Renames name using tofiles. */
  String rename(String name) {
    if (files.size() == 0 && tofiles.size() > 0) {  // append prefix tofiles[0]
      name = appendPath(tofiles.get(0), name);
    } else {  // replace prefix files[i] with tofiles[i]
      final int n = name.length();
      for (int i = 0; i < files.size() && i < tofiles.size(); ++i) {
        final int fn = files.get(i).length();
        if (fn <= n && files.get(i).equals(name.substring(0, fn)))
          return tofiles.get(i) + name.substring(fn);
      }
    }
    return name;
  }

  /** Tests whether filename is selected by files, -only, and -not. If rn then test renamed. */
  boolean isselected(String filename, boolean rn) {
    boolean matched = true;
    if (files.size() > 0) {
      matched = false;
      for (int i = 0; i < files.size() && !matched; ++i) {
        if (rn && i < tofiles.size()) {
          if (ispath(tofiles.get(i), filename)) matched = true;
        } else if (ispath(files.get(i), filename)) {
          matched = true;
        }
      }
    }
    if (!matched) return false;
    if (onlyfiles.size() > 0) {
      matched = false;
      for (int i = 0; i < onlyfiles.size() && !matched; ++i)
        if (ispath(onlyfiles.get(i), filename)) matched = true;
    }
    if (!matched) return false;
    for (String nf : notfiles) {
      if (ispath(nf, filename)) return false;
    }
    return true;
  }

  /** True if a -not pattern excludes the name (directories excluded are not scanned). */
  boolean excluded(String filename) {
    for (String nf : notfiles) if (ispath(nf, filename)) return true;
    return false;
  }
}
