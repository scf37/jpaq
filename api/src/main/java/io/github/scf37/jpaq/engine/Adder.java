package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.Event;
import io.github.scf37.jpaq.ZpaqArchive.Format;
import io.github.scf37.jpaq.libzpaq.Crypto;
import io.github.scf37.jpaq.libzpaq.Dialect;
import io.github.scf37.jpaq.libzpaq.Libzpaq;
import io.github.scf37.jpaq.libzpaq.MemoryBuffer;
import io.github.scf37.jpaq.libzpaq.Sha1;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** The "add" command: adds or deletes files from the archive. */
final class Adder {
  private final Jidac j;

  Adder(Jidac j) {
    this.j = j;
  }

  /** For writing to two archives at once. */
  private static final class WriterPair implements ZpaqWriter {
    OutputArchive a;
    ZpaqWriter b;

    @Override
    public void put(int c) {
      if (a != null) a.put(c);
      if (b != null) b.put(c);
    }

    @Override
    public void write(byte[] buf, int off, int n) {
      if (a != null) a.write(buf, off, n);
      if (b != null) b.write(buf, off, n);
    }
  }

  /**
   * Writes a ZPAQ compressed JIDAC block header. The output size does not depend on the input
   * data.
   */
  static void writeJidacHeader(ZpaqWriter out, long date, long cdata, int htsize) {
    writeJidacHeader(out, date, cdata, htsize, Dialect.STANDARD);
  }

  static void writeJidacHeader(ZpaqWriter out, long date, long cdata, int htsize, Dialect dialect) {
    if (out == null) return;
    MemoryBuffer is = new MemoryBuffer();
    Jidac.puti(is, cdata, 8);
    Libzpaq.compressBlock(is, out, "0",
        Strs.getBytes("jDC" + Strs.itos(date, 14) + "c" + Strs.itos(htsize & 0xFFFFFFFFL, 10)),
        Strs.getBytes("jDC\u0001"), true, dialect);
  }

  private static void compressJidacBlock(MemoryBuffer is, ZpaqWriter out, String method,
      String name, Dialect dialect) {
    Libzpaq.compressBlock(is, out, method, Strs.getBytes(name), Strs.getBytes("jDC\u0001"), true,
        dialect);
  }

  /** Sort by sortkey, then by full path. */
  private static final Comparator<DT> COMPARE_FILENAME = (a, b) -> {
    if (a.data() != b.data()) return Long.compare(a.data(), b.data());
    return a.compareName(b);
  };

  // State shared by the phases of an update
  private final int[] errors = {0};
  private boolean seq;            // sequential format
  private Dialect dialect;        // block magic written
  private boolean archiveExists;  // classic: first part existed at the start
  private long headerPos;         // where the transaction header is written
  private long offset;            // total size of existing parts
  private byte[] salt;            // encryption salt
  private String arcname;         // archive file (part, tome) written
  private String method;          // compression method with block size
  private long blocksize;
  private long maxFragment;
  private long minFragment;
  private long totalSize;         // size of all input
  private long totalDone;         // input deduped so far
  private long dedupesize;        // input size after dedupe
  private OutputArchive out;      // archive being written; null when committed
  private OutputArchive outi;     // classic format: index being written
  private boolean created;        // classic format: the archive file did not exist before
  private long indexStart = -1;   // classic format with index: its length before, -1 if new
  private CompressJob job;
  private MemoryBuffer sb;        // block to compress

  // Journaling format
  private long date;              // date of the update
  private HtIndex htinv;          // sha1 -> fragment ID
  private int htsize;             // fragments at start of update
  private long headerEnd;         // end of the transaction header
  private int frags;              // number of fragments in sb
  private long redundancy;        // estimated bytes that can be compressed out of sb
  private long text;              // number of fragments containing text
  private long exe;               // number of fragments containing x86 (exe, dll)
  private static final int ON = 4;  // number of order-1 tables to save
  private final byte[] o1prev = new byte[ON * 256];  // last ON order 1 predictions
  private FragmentScanner sc;
  private final List<Integer> blocklist = new ArrayList<>();  // list of starting fragments

  /**
   * Adds the files under the roots and deletes the selected entries that are no longer there.
   * Returns 1 if a file could not be added, else 0.
   */
  int add() {
    try {
      prepare();

      // Make list of files to add or delete
      for (String f : j.roots) j.scandir(f);
      List<DT> vf = plan(j.edt.values());

      // Test for reliable access to archive
      if (!seq && archiveExists != j.io.exists(Strs.subpart(j.archive, 1)))
        throw ZpaqException.error("archive access is intermittent");

      start(vf.size());
      for (DT p : vf) addExternal(p, p.name());
      int rc = finish();
      j.errors = errors[0];
      return rc;
    } catch (RuntimeException | Error e) {
      abort();
      throw e;
    }
  }

  /**
   * Reads the archive or index, decides where and how to write. Nothing is written yet; the
   * archive state (dt) is the one before the update.
   */
  void prepare() {
    seq = j.format == Format.SEQUENTIAL;
    dialect = seq ? Dialect.JPAQ : Dialect.STANDARD;
    j.io.checkWritable();
    if (seq && j.indexOut == null) throw ZpaqException.error("sequential format needs an index");

    // Read archive or index into ht, dt, ver.
    archiveExists = !seq && j.io.exists(Strs.subpart(j.archive, 1));
    String arcname = j.archive;  // input archive name
    if (j.index != null) arcname = j.index;
    headerPos = 0;
    // Sequential: the caller says whether there is an index; if so it must be readable
    if (seq ? j.index != null : j.io.exists(Strs.subpart(arcname, 1))) {
      headerPos = j.readArchive(arcname, errors);
      if (j.readDialect != null && j.readDialect != dialect)
        throw ZpaqException.error(seq ? "cannot add to a zpaq format archive in sequential format"
            : "cannot add to a jpaq sequential archive in zpaq format");
    }

    // Set arcname, offset, header_pos, and salt to open out archive
    arcname = j.archive;  // output file name
    offset = 0;
    salt = new byte[32];
    if (j.password != null) Crypto.random(salt, 32);

    if (j.index != null || seq) {  // Remote archive
      if (j.dcsize > 0) throw ZpaqException.error("index is a regular archive");
      if (j.version != Jidac.DEFAULT_VERSION) throw ZpaqException.error("cannot truncate with an index");
      offset = headerPos + j.dhsize;
      headerPos = 32 * (j.password != null && offset == 0 ? 1 : 0);
      arcname = Strs.subpart(j.archive, j.ver.size());
      if (!seq && j.io.exists(arcname)) {  // sequential: an unreferenced tome is overwritten
        j.ctx.fs.printerr(arcname, "archive exists");
        throw ZpaqException.error("archive exists");
      }
      if (j.password != null && j.index != null) {  // derive archive salt from index
        Exception[] serr = new Exception[1];
        ArchiveIo.In fp = seq || j.io.exists(j.index) ? j.io.openRead(j.index, serr) : null;
        if (fp == null && seq) throw j.ctx.ioerr(j.index, serr[0]);
        if (fp != null) {
          if (fp.read(salt, 0, 32) != 32) throw ZpaqException.error("cannot read salt from index");
          salt[0] ^= '7' ^ 'z';
          fp.close();
        }
      }
    } else {  // Local single or multi-part archive
      int parts = 0;  // number of existing parts in multipart
      String part0 = Strs.subpart(j.archive, 0);
      if (!part0.equals(j.archive)) {  // multi-part?
        for (int i = 1; ; ++i) {
          String partname = Strs.subpart(j.archive, i);
          if (partname.equals(part0)) throw ZpaqException.error("too many archive parts");
          if (!j.io.exists(partname)) break;
          ArchiveIo.In fp = j.io.openRead(partname, null);
          if (fp == null) break;
          ++parts;
          offset += fp.length();
          fp.close();
        }
        headerPos = 32 * (j.password != null && parts == 0 ? 1 : 0);
        arcname = Strs.subpart(j.archive, parts + 1);
        if (j.io.exists(arcname)) throw ZpaqException.error("part exists");
      }

      // Get salt from first part if it exists
      if (j.password != null) {
        ArchiveIo.In fp = j.io.exists(Strs.subpart(j.archive, 1))
            ? j.io.openRead(Strs.subpart(j.archive, 1), null) : null;
        if (fp == null) {
          if (headerPos > 32) throw ZpaqException.error("archive first part not found");
          headerPos = 32;
        } else {
          if (fp.read(salt, 0, 32) != 32) throw ZpaqException.error("cannot read salt");
          fp.close();
        }
      }
    }
    this.arcname = arcname;
    final boolean updating = !seq && j.io.exists(arcname);
    created = !updating;
    j.ctx.emit(Event.AddTarget.of(Ctx.t(arcname), !updating, headerPos, offset));

    // Set method
    String method = j.method;
    if (method.isEmpty()) method = "1";
    if (method.length() == 1) {  // set default blocksize
      if (method.charAt(0) >= '2' && method.charAt(0) <= '9') method += "6";
      else method += "4";
    }
    if ("0123456789xs".indexOf(method.charAt(0)) < 0)
      throw ZpaqException.error("-method must begin with 0..5, x, s");
    if (method.charAt(0) == 's' && (j.index != null || seq))
      throw ZpaqException.error("cannot index in streaming mode");
    this.method = method;

    // Set block and fragment sizes
    if (j.fragment < 0) j.fragment = 0;
    final int fragment = j.fragment;
    final int logBlocksize = 20 + Strs.atoi(method, 1);
    if (logBlocksize < 20 || logBlocksize > 31) throw ZpaqException.error("blocksize must be 0..11");
    blocksize = (1L << logBlocksize) - 4096;
    maxFragment = fragment > 19 || (8128L << fragment) > blocksize - 12
        ? blocksize - 12 : 8128L << fragment;
    minFragment = fragment > 25 || (64L << fragment) > maxFragment
        ? maxFragment : 64L << fragment;

    // Don't mix streaming and journaling
    for (Block b : j.block) {
      if (method.charAt(0) == 's') {
        if (b.usize >= 0) throw ZpaqException.error("cannot update journaling archive in streaming format");
      } else if (b.usize < 0) {
        throw ZpaqException.error("cannot update streaming archive in journaling format");
      }
    }
  }

  /** True if the method writes the streaming format (one block per file, no deduplication). */
  boolean streaming() {
    return method.charAt(0) == 's';
  }

  /**
   * Of the external files in edt, marks those that are in the archive (dt) as kept and returns
   * the ones to compress (new or changed), sorted by filename extension and decreasing size.
   * Adds their size to the total.
   */
  List<DT> plan(Iterable<DT> external) {
    List<DT> vf = new ArrayList<>();
    for (DT p : external) {
      final String pname = p.name();
      DT a = j.dt.get(j.toArchive.apply(pname));
      if (a != null) a.data(1);  // keep
      if (p.date() != 0 && !pname.isEmpty() && pname.charAt(pname.length() - 1) != '/'
          && (j.force || a == null || p.date() != a.date() || p.size() != a.size())) {
        totalSize += p.size();

        // Key by first 5 bytes of filename extension, case insensitive
        int sp = 0;  // sortkey byte position
        for (int qi = 0; qi < pname.length(); ++qi) {
          long c = pname.charAt(qi) & 255;
          if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
          if (c == '/') {
            sp = 0;
            p.data(0);
          } else if (c == '.') {
            sp = 8;
            p.data(0);
          } else if (sp > 3) {
            p.data(p.data() + (c << (--sp * 8)));
          }
        }

        // Key by descending size rounded to 16K
        long s = p.size() >> 14;
        if (s >= (1 << 24)) s = (1 << 24) - 1;
        p.data(p.data() + ((1 << 24) - s - 1));
        vf.add(p);
      }
    }
    vf.sort(COMPARE_FILENAME);
    return vf;
  }

  /**
   * Opens the output, starts the compression threads and writes the transaction header.
   *
   * @param nfiles number of files to compress, for the plan message
   */
  void start(int nfiles) {
    // Open output
    out = new OutputArchive(j.io, arcname, j.password, salt, offset);
    out.seek(headerPos, InputArchive.Whence.SET);

    // Start compress and write jobs
    final int ntid = j.threads * 2 - 1;
    job = new CompressJob(j.ctx, j.threads, ntid, out, dialect);
    j.ctx.emit(Event.AddPlan.of(totalSize, nfiles, method, j.threads, j.date));

    // Streaming mode: each file is a separate block. Large files are split into blocks of size
    // blocksize.
    if (streaming()) {
      sb = new MemoryBuffer((int) (blocksize + 4096 - 128));
      return;
    }

    // Adjust date to maintain sequential order
    if (j.ver.size() > 0 && j.ver.get(j.ver.size() - 1).lastdate >= j.date) {
      final long newdate = Dates.decimalTime(Dates.unixTime(j.ver.get(j.ver.size() - 1).lastdate) + 1);
      j.ctx.emit(Event.DateAdjusted.of(j.date, newdate));
      j.date = newdate;
    }
    date = j.date;

    // Build htinv for fast lookups of sha1 in ht
    htinv = new HtIndex(j.ht, (long) j.ht.size() + (totalSize >> (10 + j.fragment)) + nfiles);
    htsize = j.ht.size();

    // reserve space for the header block
    writeJidacHeader(out, date, seq ? 0 : -1, htsize, dialect);  // sequential: never patched
    headerEnd = out.tell();

    sb = new MemoryBuffer((int) (blocksize + 4096 - 128));
    sc = new FragmentScanner((int) maxFragment, (int) minFragment, j.fragment);
  }

  /** The date of the update (after {@link #start}). */
  long date() {
    return j.date;
  }

  /** Number of files that could not be read. */
  int errors() {
    return errors[0];
  }

  /**
   * Adds the external file p (an entry of edt) from the local file {@code source}. A file that
   * cannot be opened is reported and skipped.
   */
  void addExternal(DT p, String source) {
    if (streaming()) {
      addStreaming(p, source);
      return;
    }

    // Open input file
    sc.newFile();
    Exception[] err = new Exception[1];
    ArchiveFile in = ArchiveFile.openRead(j.ctx.fs, source, err);
    if (in == null) {  // skip if not found
      p.date(0);
      totalSize -= p.size();
      j.ctx.fs.printerr(p.name(), err[0]);
      ++errors[0];
      return;
    }
    p.data(1);  // add
    try {
      compress(p, in, p.size());
    } finally {
      in.close();
    }
  }

  /**
   * Splits a file into fragments, appends the new ones to the block being filled and records the
   * fragment pointers of p. p == null completes the last block.
   *
   * @param esize expected size of the file, used to decide whether it starts a new block
   */
  private void compress(DT p, ArchiveFile in, long esize) {
    final byte[] fragbuf = sc.fragbuf;

    // Read fragments
    long fsize = 0;  // file size after dedupe
    for (int fj = 0; true; ++fj) {
      int sz = 0;      // fragment size
      long hits = 0;   // correct prediction count
      int c = -1;      // current byte
      int htptr = 0;   // fragment index
      byte[] sha1result = new byte[20];  // fragment hash
      byte[] o1 = new byte[256];         // order 1 context -> predicted byte
      if (p != null) {
        Sha1 sha1 = new Sha1();
        c = sc.scan(in, sha1);
        sz = sc.sz;
        hits = sc.hits;
        o1 = sc.o1;
        totalDone += sz;

        // Look for matching fragment
        sha1result = sha1.result();
        htptr = htinv.find(sha1result, 0);
      }

      if (htptr == 0) {  // not matched or last block

        // Analyze fragment for redundancy, x86, text. Test for text: letters, digits, '.' and
        // ',' followed by spaces and no invalid UTF-8. Test for exe: 139 (mov reg, r/m) in lots
        // of contexts. 4 tests for redundancy, measured as hits/sz. Take the highest of:
        //   1. Successful prediction count in o1.
        //   2. Non-uniform distribution in o1 (counted in o2).
        //   3. Fraction of zeros in o1 (bytes never seen).
        //   4. Fraction of matches between o1 and previous o1 (o1prev).
        hits = sc.analyze(o1, sz, hits, o1prev, ON, DT_TABLE);
        final int text1 = sc.text1;
        final int exe1 = sc.exe1;

        // Start a new block if the current block is almost full, or at the start of a file
        // that won't fit or doesn't share mutual information with the current block, or last
        // file.
        boolean newblock = false;
        if (frags > 0 && fj == 0 && p != null) {
          final long newsize = sb.size() + esize + (esize >> 14) + 4096 + frags * 4L;
          if (newsize > blocksize / 4 && redundancy < sb.size() / 128) newblock = true;
          if (newblock) {  // test for mutual information
            int ct = 0;
            for (int i = 0; i < 256 * ON; ++i)
              if (o1prev[i] != 0 && o1prev[i] == o1[i & 255]) ++ct;
            if (ct > ON * 2) newblock = false;
          }
          if (newsize >= blocksize) newblock = true;  // won't fit?
        }
        if (sb.size() + (long) sz + 80 + frags * 4L >= blocksize) newblock = true;  // full?
        if (p == null) newblock = true;  // last file?
        if (frags < 1) newblock = false;  // block is empty?

        // Pad sb with fragment size list, then compress
        if (newblock) {
          for (int i = j.ht.size() - frags; i < j.ht.size(); ++i)
            Jidac.puti(sb, j.ht.usize(i), 4);  // list of frag sizes
          Jidac.puti(sb, 0, 4);  // omit first frag ID to make block movable
          Jidac.puti(sb, frags, 4);  // number of frags
          String m = method;
          if (Strs.isdigit(method.charAt(0)))
            m += "," + Strs.itos(redundancy / (sb.size() / 256 + 1)) + ","
                + Strs.itos((exe > frags ? 1 : 0) * 2 + (text > frags ? 1 : 0));
          String fn = "jDC" + Strs.itos(date, 14) + "d" + Strs.itos(j.ht.size() - frags, 10);
          j.ctx.printProgress(totalSize, totalDone);
          j.ctx.emit(Event.BlockCompressed.of(j.ht.size() - frags, j.ht.size() - 1, sb.size(), m));
          if (method.charAt(0) != 'i') {
            job.write(sb, fn, m, null);
          } else {  // index: don't compress data
            job.csize.add(sb.size());
            sb.resize(0);
          }
          blocklist.add(j.ht.size() - frags);  // mark block start
          frags = 0;
          redundancy = text = exe = 0;
          Arrays.fill(o1prev, (byte) 0);
        }

        // Append fragbuf to sb and update block statistics
        sb.write(fragbuf, 0, sz);
        ++frags;
        redundancy += hits;
        exe += exe1 * 4L;
        text += text1 * 2L;
        if (sz >= minFragment) {
          System.arraycopy(o1prev, 256, o1prev, 0, 256 * (ON - 1));
          System.arraycopy(o1, 0, o1prev, 256 * (ON - 1), 256);
        }
      }  // end if frag not matched or last block

      // Update HT and ptr list
      if (p != null) {
        if (htptr == 0) {
          htptr = j.ht.size();
          j.ht.add(sha1result, 0, sz);
          htinv.update();
          fsize += sz;
        }
        p.ptrPush(htptr);
      }
      if (c == -1) break;
    }  // end for each fragment fj
    if (p != null) {
      dedupesize += fsize;
      j.ctx.printProgress(totalSize, totalDone);
      String newname = j.toArchive.apply(p.name());
      DT a = j.dt.get(newname);
      final boolean upd = a != null && a.date() != 0;
      j.ctx.emit(Event.FileAdded.of(Ctx.t(p.name()), Ctx.t(newname), upd, p.size(), fsize));
    }
  }

  /** Streaming format: appends the file as blocks of its own. */
  private void addStreaming(DT p, String source) {
    j.ctx.printProgress(totalSize, totalDone);
    j.ctx.emit(Event.FileAdded.of(Ctx.t(p.name()), Ctx.t(p.name()), false, p.size(), -1));
    Exception[] err = new Exception[1];
    ArchiveFile in = ArchiveFile.openRead(j.ctx.fs, source, err);
    if (in == null) {
      j.ctx.fs.printerr(p.name(), err[0]);
      totalSize -= p.size();
      ++errors[0];
      return;
    }
    long i = 0;
    final int bufsize = 4096;
    byte[] buf = new byte[bufsize];
    while (true) {
      int r = in.read(buf, 0, bufsize);
      sb.write(buf, 0, r);
      i += r;
      if (r == 0 || sb.size() + bufsize > blocksize) {
        String filename = "";
        String comment = "";
        if (i == sb.size()) {  // first block?
          filename = j.toArchive.apply(p.name());
          comment = Strs.itos(p.date());
          if ((p.attr() & 255) > 0) {
            comment += " ";
            comment += (char) (p.attr() & 255);
            comment += Strs.itos(p.attr() >> 8);
          }
        }
        totalDone += sb.size();
        job.write(sb, filename, method, comment);
      }
      if (r == 0) break;
    }
    in.close();
  }

  /**
   * Completes the update: writes the fragment tables and the file list (additions of the files
   * in edt, deletions of the files in dt that are not kept) and commits. Returns 1 if a file
   * could not be added, else 0.
   */
  int finish() {
    if (streaming()) {
      // Wait for jobs to finish
      job.write(sb, null, "", null);  // signal end of input
      job.join();

      // Done
      final long outsize = out.tell();
      j.ctx.emit(Event.AddTotals.of(true, headerPos, totalSize, totalSize, outsize - headerPos,
          outsize));
      out.close();
      out = null;
      return errors[0] > 0 ? 1 : 0;
    }

    // Complete the last block
    compress(null, null, 0);

    // Wait for jobs to finish
    job.write(sb, null, "", null);  // signal end of input
    job.join();

    // Open index
    salt[0] ^= '7' ^ 'z';
    // Sequential format: the index segment is collected and written after the tome is complete
    MemoryBuffer indexSegment = seq ? new MemoryBuffer() : null;
    if (!seq && j.index != null && j.io.exists(j.index))
      indexStart = j.io.length(j.index);
    outi = seq ? null
        : new OutputArchive(j.io, j.index != null ? j.index : "", j.password, salt, 0);
    WriterPair wp = new WriterPair();
    wp.a = out;
    if (seq) wp.b = indexSegment;
    else if (j.index != null) wp.b = outi;
    writeJidacHeader(seq ? indexSegment : outi, date, 0, htsize, dialect);

    // Append compressed fragment tables to archive
    final long cdatasize = out.tell() - headerEnd;
    MemoryBuffer is = new MemoryBuffer();
    blocklist.add(j.ht.size());
    for (int i = 0; i < job.csize.size(); ++i) {
      if (blocklist.get(i) < blocklist.get(i + 1)) {
        Jidac.puti(is, job.csize.get(i), 4);  // compressed size of block
        byte[] hash = new byte[20];
        for (int k = blocklist.get(i); k < blocklist.get(i + 1); ++k) {
          j.ht.copySha1(k, hash, 0);
          is.write(hash, 0, 20);
          Jidac.puti(is, j.ht.usize(k), 4);
        }
        compressJidacBlock(is, wp, "0",
            "jDC" + Strs.itos(date, 14) + "h" + Strs.itos(blocklist.get(i), 10), dialect);
        is.resize(0);
      }
    }

    // Delete from archive
    int dtcount = 0;  // index block header name
    int removed = 0;  // count
    for (DT p : j.dt.values()) {
      if (p.date() != 0 && p.data() == 0) {
        Jidac.puti(is, 0, 8);
        byte[] nb = Strs.getBytes(p.name());
        is.write(nb, 0, nb.length);
        is.put(0);
        j.ctx.emit(Event.FileRemoved.of(Ctx.t(p.name())));
        ++removed;
        if (is.size() > 16000) {
          compressJidacBlock(is, wp, "1",
              "jDC" + Strs.itos(date) + "i" + Strs.itos(++dtcount, 10), dialect);
          is.resize(0);
        }
      }
    }

    // Append compressed index to archive
    int added = 0;  // count
    for (java.util.Iterator<DT> it = j.edt.values().iterator(); ; ) {
      DT p = it.hasNext() ? it.next() : null;
      if (p != null) {
        String filename = j.toArchive.apply(p.name());
        DT a = j.dt.get(filename);
        if (p.date() != 0 && (a == null  // new file
            || a.date() != p.date()  // date change
            || (a.attr() != 0 && a.attr() != p.attr())  // attr ch.
            || a.size() != p.size()  // size change
            || (p.data() != 0 && !a.samePtr(p)))) {  // content
          if (p.data() == 0) {  // not compressed?
            final boolean upd = a != null && a.date() != 0;
            j.ctx.emit(Event.FileAdded.of(Ctx.t(p.name()), Ctx.t(filename), upd, -1, -1));
          }
          ++added;
          Jidac.puti(is, p.date(), 8);
          byte[] nb = Strs.getBytes(filename);
          is.write(nb, 0, nb.length);
          is.put(0);
          if ((p.attr() & 255) == 'u') {  // unix attributes
            Jidac.puti(is, 3, 4);
            Jidac.puti(is, p.attr(), 3);
          } else if ((p.attr() & 255) == 'w') {  // windows attributes
            Jidac.puti(is, 5, 4);
            Jidac.puti(is, p.attr(), 5);
          } else {
            Jidac.puti(is, 0, 4);  // no attributes
          }
          if (a == null || p.data() != 0) a = p;  // use new frag pointers
          Jidac.puti(is, a.ptrSize(), 4);  // list of frag pointers
          for (int i = 0; i < a.ptrSize(); ++i) Jidac.puti(is, a.ptr(i), 4);
        }
      }
      if (is.size() > 16000 || (is.size() > 0 && p == null)) {
        compressJidacBlock(is, wp, "1", "jDC" + Strs.itos(date) + "i" + Strs.itos(++dtcount, 10),
            dialect);
        is.resize(0);
      }
      if (p == null) break;
    }
    j.ctx.emit(Event.AddSummary.of(added, removed));

    if (seq) {
      // Commit the tome first, then the index that refers to it. Nothing is patched.
      long archiveEnd = out.tell();
      final boolean noUpdate = added + removed == 0 && archiveEnd - headerPos == 104;
      if (noUpdate) {
        if (!out.discard()) out.close();
        archiveEnd = headerPos;
      } else {
        out.close();
        writeSequentialIndex(indexSegment, salt);
      }
      out = null;
      addResult(headerPos, totalSize, dedupesize, archiveEnd);
      return errors[0] > 0 ? 1 : 0;
    }

    // Back up and write the header
    outi.close();
    outi = null;
    long archiveEnd = out.tell();
    out.seek(headerPos, InputArchive.Whence.SET);
    writeJidacHeader(out, date, cdatasize, htsize);
    out.seek(0, InputArchive.Whence.END);
    final long archiveSize = out.tell();
    out.close();
    out = null;

    // Truncate empty update from archive (if not indexed)
    if (j.index == null) {
      if (added + removed == 0 && archiveEnd - headerPos == 104)  // no update
        archiveEnd = headerPos;
      if (archiveEnd < archiveSize) {
        if (archiveEnd > 0) {
          j.ctx.emit(Event.ArchiveTruncated.of(archiveSize, archiveEnd));
          if (!truncate(arcname, archiveEnd)) j.ctx.fs.printerr(j.archive, "truncate failed");
        } else if (archiveEnd == 0) {
          if (j.io.delete(arcname)) {
            j.ctx.emit(Event.ArchiveDeleted.of(Ctx.t(arcname)));
          }
        }
      }
    }
    addResult(headerPos, totalSize, dedupesize, archiveEnd);
    return errors[0] > 0 ? 1 : 0;
  }

  /**
   * Stops an update that failed: the compression threads end, a partial tome is not committed
   * (where the storage allows) and a classic archive and its index are truncated back to what
   * they were.
   */
  void abort() {
    if (job != null) job.abort();  // no-op if finished normally
    final boolean open = out != null;
    if (out != null) out.abort();
    out = null;
    if (outi != null) outi.abort();
    if (seq || !open) return;

    // Classic format: take back what was written, so the archive is as it was
    try {
      if (created) j.io.delete(arcname);
      else if (!truncate(arcname, headerPos)) j.ctx.fs.printerr(arcname, "truncate failed");
      if (outi != null && j.index != null) {
        if (indexStart < 0) j.io.delete(j.index);
        else if (!truncate(j.index, indexStart)) j.ctx.fs.printerr(j.index, "truncate failed");
      }
    } catch (RuntimeException e) {
      j.ctx.fs.printerr(arcname, e);
    } finally {
      outi = null;
    }
  }

  private void addResult(long headerPos, long totalSize, long dedupesize, long archiveEnd) {
    j.ctx.emit(Event.AddTotals.of(false, headerPos, totalSize, dedupesize, archiveEnd - headerPos,
        archiveEnd));
  }

  /** Truncates filename to length. Returns true on success. */
  private boolean truncate(String filename, long length) {
    ArchiveIo.Out f = j.io.openUpdate(filename, null);
    if (f == null) return false;
    try {
      f.truncate(length);
      return true;
    } catch (RuntimeException e) {
      return false;
    } finally {
      f.close();
    }
  }

  /**
   * Writes the updated index of a sequential archive: the old index (if any) copied as it is,
   * then the segment of this update, under the new index name. Everything is written once, front
   * to back.
   */
  private void writeSequentialIndex(MemoryBuffer segment, byte[] salt) {
    ArchiveIo.In old = null;
    if (j.index != null) {
      Exception[] err = new Exception[1];
      old = j.io.openRead(j.index, err);
      if (old == null) throw j.ctx.ioerr(j.index, err[0]);  // it was read before
    }
    OutputArchive idx = new OutputArchive(j.io, j.indexOut, j.password, salt, 0);
    try {
      if (old != null) {
        if (j.password != null) {  // the output starts with the same salt
          byte[] s = new byte[32];
          if (old.read(s, 0, 32) != 32) throw ZpaqException.error("cannot read salt from index");
          if (!Arrays.equals(s, salt)) throw ZpaqException.error("index salt mismatch");
        }
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = old.read(buf, 0, buf.length)) > 0) idx.writeRaw(buf, 0, n);
      }
      idx.write(segment.data(), 0, segment.size());
      idx.close();
    } catch (RuntimeException e) {
      idx.abort();
      throw e;
    } finally {
      if (old != null) old.close();
    }
  }

  /** 32768/((i+1)*204); only the first 160 entries are non-zero. */
  private static final int[] DT_TABLE = new int[256];

  static {
    final int[] v = {
        160, 80, 53, 40, 32, 26, 22, 20, 17, 16, 14, 13, 12, 11, 10, 10,
        9, 8, 8, 8, 7, 7, 6, 6, 6, 6, 5, 5, 5, 5, 5, 5,
        4, 4, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    System.arraycopy(v, 0, DT_TABLE, 0, v.length);
  }
}
