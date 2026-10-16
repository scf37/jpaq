package io.github.scf37.jpaq.cli;

import static io.github.scf37.jpaq.cli.Text.f;

import io.github.scf37.jpaq.ArchiveContents;
import io.github.scf37.jpaq.ArchiveEntry;
import io.github.scf37.jpaq.ArchiveVersion;
import io.github.scf37.jpaq.LocalFile;
import io.github.scf37.jpaq.LocalFiles;
import io.github.scf37.jpaq.ReadOptions;
import io.github.scf37.jpaq.ZpaqArchive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/** The "list" command: lists the archive contents or compares them with external files. */
final class Lister {
  private final Args a;
  private final Console out;
  private final Path workDir;

  Lister(Args a, Console out, Path workDir) {
    this.a = a;
    this.out = out;
    this.workDir = workDir;
  }

  /** A line of the listing: an entry of the archive or an external file. Names are byte strings. */
  private static final class Row {
    final String name;
    long date;
    long size;
    final long attr;
    final int[] ptr;
    final ArchiveEntry entry;  // null for external files and version directories
    char data;                 // comparison result

    Row(String name, long date, long size, long attr, int[] ptr, ArchiveEntry entry) {
      this.name = name;
      this.date = date;
      this.size = size;
      this.attr = attr;
      this.ptr = ptr;
      this.entry = entry;
    }
  }

  /** Lexicographic comparison of the pointer lists as vectors of unsigned ints. */
  private static int comparePtr(int[] x, int[] y) {
    int n = Math.min(x.length, y.length);
    for (int i = 0; i < n; i++) {
      int c = Integer.compareUnsigned(x[i], y[i]);
      if (c != 0) return c;
    }
    return Integer.compare(x.length, y.length);
  }

  /** Sorts files by decreasing size, then fragment ID list. */
  private static final Comparator<Row> COMPARE_FRAGMENT_LIST = (p, q) -> {
    if (p.size != q.size) return p.size > q.size ? -1 : 1;
    int c = comparePtr(p.ptr, q.ptr);
    if (c != 0) return c;
    if (p.data != q.data) return Character.compare(p.data, q.data);
    return p.name.compareTo(q.name);
  };

  /** True if a local file or directory (without trailing /) exists. */
  private boolean exists(String filename) {
    int len = filename.length();
    if (len < 1) return false;
    if (filename.charAt(len - 1) == '/') filename = filename.substring(0, len - 1);
    if (filename.isEmpty()) return false;
    try {
      String s = Text.text(filename);
      if (Args.WINDOWS) s = s.replace('/', '\\');
      Path p = Path.of(s);
      return Files.exists(workDir != null ? workDir.resolve(p) : p, LinkOption.NOFOLLOW_LINKS);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** True if the archive entry p and the external file have the same content, or neither exists. */
  private boolean equal(ArchiveContents contents, Row p, String filename) {
    if (p.date == 0) return !exists(filename);
    if (!p.name.isEmpty() && p.name.charAt(p.name.length() - 1) == '/') return exists(filename);
    return p.entry != null && contents.sameContent(p.entry, Text.text(filename));
  }

  /** Lists the contents. */
  int list(ZpaqArchive archive) throws IOException {
    // Read archive into dt, which may be "" for empty.
    TreeMap<String, Row> dt = new TreeMap<>();
    List<ArchiveVersion> versions = List.of();
    ArchiveContents contents = null;
    long csize = 0;
    if (!a.archive.isEmpty()) {
      ReadOptions ro = ReadOptions.LATEST.withVersionDigits(a.all)
          .withSelect(n -> a.isselected(Text.bytes(n), true)).withNoAttributes(a.noattributes);
      if (a.version != Args.DEFAULT_VERSION) ro = CommandLine.until(ro, a.version);
      contents = archive.read(ro);
      csize = contents.end();
      versions = contents.versions();
      for (ArchiveEntry e : contents.entries()) {
        String n = Text.bytes(e.name());
        dt.put(n, new Row(n, e.date(), e.size(), e.attributes().raw(), e.fragments(), e));
      }
      if (a.all != 0) {  // version directories
        for (ArchiveVersion v : versions) {
          String fn = a.rename(Text.itos(v.number(), a.all) + "/");
          if (!a.isselected(fn, false)) continue;
          Row r = dt.get(fn);
          if (r != null) r.date = v.date();
          else dt.put(fn, new Row(fn, v.date(), 0, 0, new int[0], null));
        }
      }
    }

    // Read external files into edt
    TreeMap<String, Row> edt = new TreeMap<>();
    if (!a.files.isEmpty()) {
      List<String> roots = a.files.stream().map(Text::text).toList();
      for (LocalFile lf : LocalFiles.scan(workDir, roots, n -> a.excluded(Text.bytes(n)),
          n -> a.isselected(Text.bytes(n), false), a.noattributes, out)) {
        String n = Text.bytes(lf.name());
        edt.put(n, new Row(n, lf.date(), lf.size(), lf.attributes().raw(), new int[0], null));
      }
    }
    if (a.files.size() > 0) out.print(f("%d external files.\n", edt.size()));
    out.print("\n");

    // Compute directory sizes as the sum of their contents
    for (TreeMap<String, Row> m : List.of(dt, edt)) {
      for (Row p : m.values()) {
        final String pname = p.name;
        for (int k = 0; k < pname.length(); ++k) {
          if (pname.charAt(k) == '/') {
            Row q = m.get(pname.substring(0, k + 1));
            if (q != null) q.size += p.size;
          }
        }
      }
    }

    // Make list of files to list. List each external file preceded by the matching internal file,
    // if any. Then list any unmatched internal files at the end.
    List<Row> filelist = new ArrayList<>();
    for (Row p : edt.values()) {
      Row q = dt.get(a.rename(p.name));
      if (q != null && (a.all != 0 || q.date != 0)) {
        q.data = '-';
        filelist.add(q);
      }
      p.data = '+';
      filelist.add(p);
    }
    for (Row q : dt.values()) {
      if (q.data != '-' && (a.all != 0 || q.date != 0)) {
        q.data = '-';
        filelist.add(q);
      }
    }

    // Sort
    if (a.summary > 0) filelist.sort(COMPARE_FRAGMENT_LIST);

    // List
    long usize = 0;
    int matches = 0;
    int mismatches = 0;
    int internal = 0;
    int external = 0;
    int duplicates = 0;  // counts
    for (int fi = 0; fi < filelist.size() && (a.summary <= 0 || fi < a.summary); ++fi) {
      Row p = filelist.get(fi);
      String pname = p.name;

      // Compare external files
      if (a.summary <= 0 && p.data == '-' && fi + 1 < filelist.size()
          && filelist.get(fi + 1).data == '+') {
        Row p1 = filelist.get(fi + 1);
        if ((a.force && equal(contents, p, p1.name))
            || (!a.force && p.date == p1.date && p.size == p1.size
                && (p.attr == 0 || p1.attr == 0 || p.attr == p1.attr))) {
          p.data = '=';
          ++fi;
        } else {
          p.data = '#';
        }
      }

      // Compare with previous file in summary
      if (a.summary > 0 && fi > 0 && p.date != 0 && !pname.isEmpty()
          && pname.charAt(pname.length() - 1) != '/' && p.ptr.length > 0
          && Arrays.equals(filelist.get(fi - 1).ptr, p.ptr))
        p.data = '^';

      if (p.data == '=') ++matches;
      if (p.data == '#') ++mismatches;
      if (p.data == '-') ++internal;
      if (p.data == '+') ++external;
      if (p.data == '^') ++duplicates;

      // List selected comparison results
      if (a.nottype.indexOf(p.data) < 0 && p.data != 0) {
        if (!pname.isEmpty() && pname.charAt(pname.length() - 1) != '/') usize += p.size;
        StringBuilder t = new StringBuilder();
        t.append(f("%c %s %12.0f ", p.data, Text.dateToString(p.date), p.size + 0.0));
        if (!a.noattributes) t.append(Text.attrToString(p.attr)).append(' ');
        t.append(pname);
        if (a.summary < 0) {  // frag pointers
          boolean hyphen = false;
          final int n = p.ptr.length;
          for (int k = 0; k < n; ++k) {
            if (k == 0 || k == n - 1 || p.ptr[k] != p.ptr[k - 1] + 1
                || p.ptr[k] != p.ptr[k + 1] - 1) {
              if (!hyphen) t.append(' ');
              hyphen = false;
              t.append(p.ptr[k]);
            } else {
              if (!hyphen) t.append('-');
              hyphen = true;
            }
          }
        }
        int v;  // list version updates, deletes, compressed size
        if (a.all > 0 && pname.length() == a.all + 1 && (v = Text.atoi(pname)) > 0
            && v <= versions.size()) {  // version info
          ArchiveVersion vr = versions.get(v - 1);
          t.append(f(" +%d -%d -> %1.0f", vr.updates(), vr.deletes(),
              (v < versions.size() ? versions.get(v).offset() : csize) - vr.offset() + 0.0));
          if (a.summary < 0)  // print fragment range
            t.append(f(" %d-%d", vr.firstFragment(), v < versions.size()
                ? versions.get(v).firstFragment() - 1 : contents.fragmentCount() - 1));
        }
        t.append('\n');
        out.print(t.toString());
      }
    }  // end for i = each file version

    // Compute dedupe size
    final int htsize = contents != null ? contents.fragmentCount() : 1;
    long ddsize = 0;
    long allsize = 0;
    int nfiles = 0;
    int nfrags = 0;
    int unknownFrags = 0;
    int refs = 0;
    boolean[] ref = new boolean[htsize];
    for (Row p : dt.values()) {
      if (p.date != 0) {
        ++nfiles;
        for (int m : p.ptr) {
          if (m > 0 && m < htsize) {
            ++refs;
            final int us = contents.fragmentSize(m);
            if (us >= 0) allsize += us;
            if (!ref[m]) {
              ref[m] = true;
              ++nfrags;
              if (us >= 0) ddsize += us;
              else ++unknownFrags;
            }
          }
        }
      }
    }

    // Print archive statistics
    final long dhsize = contents != null ? contents.dataSize() : 0;
    final long dcsize = contents != null ? contents.storedDataSize() : 0;
    out.print(f("\n%1.6f MB of %1.6f MB (%d files) shown\n"
        + "  -> %1.6f MB (%d refs to %d of %d frags) after dedupe\n"
        + "  -> %1.6f MB compressed.\n",
        usize / 1000000.0, allsize / 1000000.0, nfiles, ddsize / 1000000.0, refs, nfrags,
        htsize - 1, (csize + dhsize - dcsize) / 1000000.0));
    if (unknownFrags != 0) out.print(f("%d fragments have unknown size\n", unknownFrags));
    if (a.files.size() > 0)
      out.print(f("%d =same, %d #different, %d +external, %d -internal\n", matches, mismatches,
          external, internal));
    if (a.summary > 0)
      out.print(f("%d of largest %d files are ^duplicates\n", duplicates, a.summary));
    if (dhsize != dcsize)  // index?
      out.print(f("Note: %1.0f of %1.0f compressed bytes are in archive\n", dcsize + 0.0,
          dhsize + 0.0));
    return 0;
  }
}
