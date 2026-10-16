package io.github.scf37.jpaq.engine;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * An update of an archive driven by a program instead of a command line: files are added one at
 * a time, from streams or local files, entries are deleted by name, and the update is committed
 * or abandoned. Names are byte strings (see {@link Strs}).
 *
 * <p>Unlike the command line, entries of the archive that the update does not mention are kept;
 * only {@link #delete} and {@link #addTree} remove entries.
 */
public final class Updater implements AutoCloseable {
  private final Jidac j;
  private final Adder a;
  private boolean open;     // begun and neither committed nor abandoned
  private boolean failed;   // an addition failed midway: only abandoning is possible

  /** @param j options of the update (command 'a'); the archive is read by {@link #begin} */
  public Updater(Jidac j) {
    this.j = j;
    this.a = new Adder(j);
    a.rollbackOnAbort = true;
  }

  /**
   * Reads the archive (or index), opens the output and writes the transaction header. Throws if
   * the method is a streaming one, which this interface does not write.
   */
  public void begin() {
    try {
      j.command = 'a';
      j.prepare();
      a.prepare();
      if (a.streaming()) throw new IllegalArgumentException("streaming methods (s...) are not supported");
      for (DT p : j.dt.values()) p.data(1);  // keep everything not deleted explicitly
      a.start(0, false);
      open = true;
    } catch (RuntimeException | Error e) {
      a.abort();
      throw e;
    }
  }

  /** The date of the update, decimal YYYYMMDDHHMMSS (UT). */
  public long date() {
    return a.date();
  }

  /** The number of versions of the archive before this update. */
  public int versions() {
    return j.ver.size() - 1;
  }

  private void check() {
    if (failed) throw new IllegalStateException("the update failed; it can only be closed");
    if (!open) throw new IllegalStateException("the update is not open");
  }

  /** Runs an addition; a failure leaves the update unusable. */
  private void guarded(Runnable r) {
    check();
    try {
      r.run();
    } catch (RuntimeException | Error e) {
      failed = true;
      throw e;
    }
  }

  private static void checkName(String name) {
    if (name.isEmpty()) throw new IllegalArgumentException("empty name");
    if (name.length() > 65535) throw new IllegalArgumentException("name too long");
    if (name.indexOf(0) >= 0) throw new IllegalArgumentException("NUL in name");
  }

  /**
   * Adds a file whose content is read from {@code data} (to its end; the stream is not closed).
   * A file of the same name added before in this update is replaced.
   *
   * @param date decimal date YYYYMMDDHHMMSS (UT); 0 for the date of the update
   * @param attr attributes as stored by zpaq ('u' + mode &lt;&lt; 8, 'w' + attributes &lt;&lt; 8, or 0)
   */
  public void addStream(String name, InputStream data, long date, long attr) {
    checkName(name);
    if (name.endsWith("/")) throw new IllegalArgumentException("a file name cannot end with /");
    guarded(() -> {
      DT p = j.edt.put(name);
      p.date(date != 0 ? date : a.date());
      p.attr(j.noattributes ? 0 : attr);
      a.addStream(p, data);
    });
  }

  /** Adds a directory entry (the name gets a trailing '/'). */
  public void addDirectory(String name, long date, long attr) {
    checkName(name);
    final String n = name.endsWith("/") ? name : name + "/";
    guarded(() -> {
      DT p = j.edt.put(n);
      p.date(date != 0 ? date : a.date());
      p.attr(j.noattributes ? 0 : attr);
      p.data(0);
    });
  }

  /**
   * Adds a local file under the archive name {@code name} with its date, size and attributes.
   * Returns false if the file could not be read (reported as an error line, nothing added).
   */
  public boolean addFile(String name, String localFile) {
    checkName(name);
    final boolean[] ok = {false};
    guarded(() -> {
      Exception[] err = new Exception[1];
      Fs.Stat st = j.ctx.fs.lstat(localFile, err);
      if (st == null || !st.regular()) {
        if (st == null) j.ctx.fs.printerr(localFile, err[0]);
        else j.ctx.fs.printerr(localFile, "not a regular file");
        return;
      }
      DT p = j.edt.put(name);
      p.date(Dates.decimalTime(st.mtime()));
      p.size(st.size());
      p.attr(j.noattributes ? 0 : Jidac.attrOf(st));
      p.data(1);
      int before = a.errors();
      a.addExternal(p, localFile);
      ok[0] = a.errors() == before;
    });
    return ok[0];
  }

  /**
   * Makes the archive under {@code archiveRoot} like the local directory (or file) {@code
   * localRoot}, as the zpaq add command does: new files and files whose date or size changed (or
   * any file with force) are added, entries for files that are no longer there are deleted. Files
   * excluded by the options are neither added nor deleted. Files that can't be read are reported
   * as error lines and skipped.
   *
   * @param archiveRoot name in the archive; "" puts the contents at the top
   */
  public void addTree(String localRoot, String archiveRoot) {
    while (localRoot.length() > 1 && localRoot.endsWith("/"))
      localRoot = localRoot.substring(0, localRoot.length() - 1);
    while (archiveRoot.endsWith("/")) archiveRoot = archiveRoot.substring(0, archiveRoot.length() - 1);
    final String lr = localRoot;
    final String ar = archiveRoot;
    guarded(() -> {
      for (DT p : j.dt.values()) if (p.date() != 0 && under(ar, p.name())) p.data(0);
      List<DT> found = new ArrayList<>();
      scan(lr, ar, found);
      for (DT p : a.plan(found)) {
        String n = p.name();
        String local = n.equals(ar) ? lr : lr + "/" + (ar.isEmpty() ? n : n.substring(ar.length() + 1));
        a.addExternal(p, local);
      }
    });
  }

  /** Adds the local file or directory to edt as {@code name}, directories recursively. */
  private void scan(String local, String name, List<DT> found) {
    if (!name.isEmpty()) {
      for (String nf : j.notfiles) if (Jidac.ispath(nf, name)) return;
    }
    Exception[] err = new Exception[1];
    Fs.Stat sb = j.ctx.fs.lstat(local, err);
    if (sb == null) {
      j.ctx.fs.printerr(local, err[0]);
      return;
    }
    if (sb.regular()) found.add(entry(name, sb, sb.size()));
    if (sb.directory()) {
      if (!name.isEmpty()) found.add(entry(name + "/", sb, 0));
      try (var ds = java.nio.file.Files.newDirectoryStream(j.ctx.fs.path(local))) {
        for (java.nio.file.Path c : ds) {
          String child = Fs.bytes(c.getFileName().toString());
          scan(local.equals("/") ? "/" + child : local + "/" + child,
              name.isEmpty() ? child : name + "/" + child, found);
        }
      } catch (java.io.IOException | RuntimeException e) {
        j.ctx.fs.printerr(local, e);
      }
    }
  }

  private DT entry(String name, Fs.Stat sb, long size) {
    DT d = j.edt.put(name);
    d.date(Dates.decimalTime(sb.mtime()));
    d.size(size);
    d.attr(j.noattributes ? 0 : Jidac.attrOf(sb));
    return d;
  }

  /** True if name is root or inside it ("" contains everything). */
  private static boolean under(String root, String name) {
    if (root.isEmpty()) return true;
    return name.equals(root) || (name.startsWith(root) && name.charAt(root.length()) == '/');
  }

  /**
   * Deletes the entry {@code name} from the archive, and everything inside it if it is a
   * directory; also drops what this update added under that name. Returns the number of entries
   * deleted or dropped.
   */
  public int delete(String name) {
    checkName(name);
    while (name.length() > 1 && name.endsWith("/")) name = name.substring(0, name.length() - 1);
    final String n = name;
    final int[] count = {0};
    guarded(() -> {
      for (DT p : j.dt.values()) {
        if (p.date() != 0 && p.data() != 0 && under(n, p.name())) {
          p.data(0);
          ++count[0];
        }
      }
      for (DT p : j.edt.values()) {
        if (p.date() != 0 && under(n, p.name())) {
          p.date(0);
          ++count[0];
        }
      }
    });
    return count[0];
  }

  /**
   * Writes the file list and commits the update. An update that changes nothing writes nothing
   * (sequential format) or is truncated away (classic format). Returns 1 if a file could not be
   * added, else 0.
   */
  public int commit() {
    check();
    open = false;
    try {
      return a.finish();
    } catch (RuntimeException | Error e) {
      a.abort();
      throw e;
    }
  }

  /** Abandons the update unless it was committed: nothing becomes part of the archive. */
  @Override
  public void close() {
    if (open) {
      open = false;
      a.abort();
    }
  }
}
