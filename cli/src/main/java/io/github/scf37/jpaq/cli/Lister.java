package io.github.scf37.jpaq.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The "list" command: lists the archive contents or compares them with external files. */
final class Lister {
  private final Jidac j;

  Lister(Jidac j) {
    this.j = j;
  }

  /** Sorts files by decreasing size, then fragment ID list. */
  private static final Comparator<DT> COMPARE_FRAGMENT_LIST = (p, q) -> {
    if (p.size != q.size) return p.size > q.size ? -1 : 1;
    int c = p.comparePtr(q);
    if (c != 0) return c;
    if (p.data != q.data) return Long.compare(p.data, q.data);
    return p.name.compareTo(q.name);
  };

  /** Lists the contents. */
  int list() {

    // Read archive into dt, which may be "" for empty.
    long csize = 0;
    if (!j.archive.isEmpty()) csize = j.readArchive(j.archive, null);

    // Read external files into edt
    for (String f : j.files) j.scandir(f);
    if (j.files.size() > 0) Console.printf("%d external files.\n", j.edt.size());
    Console.print("\n");

    // Compute directory sizes as the sum of their contents
    @SuppressWarnings("unchecked")
    java.util.TreeMap<String, DT>[] dp = new java.util.TreeMap[] {j.dt, j.edt};
    for (int i = 0; i < 2; ++i) {
      for (DT p : dp[i].values()) {
        int len = p.name.length();
        // The C++ tests p->first[len] != '/', i.e. the terminating NUL, which is always true.
        if (len > 0) {
          for (int k = 0; k < len; ++k) {
            if (p.name.charAt(k) == '/') {
              DT q = dp[i].get(p.name.substring(0, k + 1));
              if (q != null) q.size += p.size;
            }
          }
        }
      }
    }

    // Make list of files to list. List each external file preceded by the matching internal file,
    // if any. Then list any unmatched internal files at the end.
    List<DT> filelist = new ArrayList<>();
    for (DT p : j.edt.values()) {
      DT a = j.dt.get(j.rename(p.name));
      if (a != null && (j.all != 0 || a.date != 0)) {
        a.data = '-';
        filelist.add(a);
      }
      p.data = '+';
      filelist.add(p);
    }
    for (DT a : j.dt.values()) {
      if (a.data != '-' && (j.all != 0 || a.date != 0)) {
        a.data = '-';
        filelist.add(a);
      }
    }

    // Sort
    if (j.summary > 0) filelist.sort(COMPARE_FRAGMENT_LIST);

    // List
    long usize = 0;
    int matches = 0;
    int mismatches = 0;
    int internal = 0;
    int external = 0;
    int duplicates = 0;  // counts
    Extractor eq = new Extractor(j);
    for (int fi = 0; fi < filelist.size() && (j.summary <= 0 || fi < j.summary); ++fi) {
      DT p = filelist.get(fi);

      // Compare external files
      if (j.summary <= 0 && p.data == '-' && fi + 1 < filelist.size()
          && filelist.get(fi + 1).data == '+') {
        DT p1 = filelist.get(fi + 1);
        if ((j.force && eq.equal(p, p1.name))
            || (!j.force && p.date == p1.date && p.size == p1.size
                && (p.attr == 0 || p1.attr == 0 || p.attr == p1.attr))) {
          p.data = '=';
          ++fi;
        } else {
          p.data = '#';
        }
      }

      // Compare with previous file in summary
      if (j.summary > 0 && fi > 0 && p.date != 0 && !p.name.isEmpty()
          && p.name.charAt(p.name.length() - 1) != '/' && p.ptrSize() > 0
          && filelist.get(fi - 1).samePtr(p))
        p.data = '^';

      if (p.data == '=') ++matches;
      if (p.data == '#') ++mismatches;
      if (p.data == '-') ++internal;
      if (p.data == '+') ++external;
      if (p.data == '^') ++duplicates;

      // List selected comparison results
      if (j.nottype.indexOf((char) p.data) < 0 && p.data != 0) {
        if (!p.name.isEmpty() && p.name.charAt(p.name.length() - 1) != '/') usize += p.size;
        Console.printf("%c %s %12.0f ", (char) p.data, Dates.dateToString(p.date), p.size + 0.0);
        if (!j.noattributes) Console.printf("%s ", Dates.attrToString(p.attr));
        Console.print(p.name);
        if (j.summary < 0) {  // frag pointers
          boolean hyphen = false;
          final int n = p.ptrSize();
          for (int k = 0; k < n; ++k) {
            if (k == 0 || k == n - 1 || p.ptr(k) != p.ptr(k - 1) + 1
                || p.ptr(k) != p.ptr(k + 1) - 1) {
              if (!hyphen) Console.print(" ");
              hyphen = false;
              Console.printf("%d", p.ptr(k));
            } else {
              if (!hyphen) Console.print("-");
              hyphen = true;
            }
          }
        }
        int v;  // list version updates, deletes, compressed size
        if (j.all > 0 && p.name.length() == j.all + 1 && (v = Strs.atoi(p.name)) > 0
            && v < j.ver.size()) {  // version info
          Ver vr = j.ver.get(v);
          Console.printf(" +%d -%d -> %1.0f", vr.updates, vr.deletes,
              (v + 1 < j.ver.size() ? j.ver.get(v + 1).offset : csize) - vr.offset + 0.0);
          if (j.summary < 0)  // print fragment range
            Console.printf(" %d-%d", vr.firstFragment,
                v + 1 < j.ver.size() ? j.ver.get(v + 1).firstFragment - 1 : j.ht.size() - 1);
        }
        Console.print("\n");
      }
    }  // end for i = each file version

    // Compute dedupe size
    long ddsize = 0;
    long allsize = 0;
    int nfiles = 0;
    int nfrags = 0;
    int unknownFrags = 0;
    int refs = 0;
    boolean[] ref = new boolean[j.ht.size()];
    for (DT p : j.dt.values()) {
      if (p.date != 0) {
        ++nfiles;
        for (int k = 0; k < p.ptrSize(); ++k) {
          int m = p.ptr(k);
          if (m > 0 && m < j.ht.size()) {
            ++refs;
            if (j.ht.usize(m) >= 0) allsize += j.ht.usize(m);
            if (!ref[m]) {
              ref[m] = true;
              ++nfrags;
              if (j.ht.usize(m) >= 0) ddsize += j.ht.usize(m);
              else ++unknownFrags;
            }
          }
        }
      }
    }

    // Print archive statistics
    Console.printf("\n%1.6f MB of %1.6f MB (%d files) shown\n"
        + "  -> %1.6f MB (%d refs to %d of %d frags) after dedupe\n"
        + "  -> %1.6f MB compressed.\n",
        usize / 1000000.0, allsize / 1000000.0, nfiles, ddsize / 1000000.0, refs, nfrags,
        j.ht.size() - 1, (csize + j.dhsize - j.dcsize) / 1000000.0);
    if (unknownFrags != 0) Console.printf("%d fragments have unknown size\n", unknownFrags);
    if (j.files.size() > 0)
      Console.printf("%d =same, %d #different, %d +external, %d -internal\n", matches, mismatches,
          external, internal);
    if (j.summary > 0)
      Console.printf("%d of largest %d files are ^duplicates\n", duplicates, j.summary);
    if (j.dhsize != j.dcsize)  // index?
      Console.printf("Note: %1.0f of %1.0f compressed bytes are in archive\n", j.dcsize + 0.0,
          j.dhsize + 0.0);
    return 0;
  }
}
