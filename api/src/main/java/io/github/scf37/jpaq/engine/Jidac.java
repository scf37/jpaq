package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.Event;
import io.github.scf37.jpaq.Storage;
import io.github.scf37.jpaq.ZpaqArchive.Format;
import io.github.scf37.jpaq.libzpaq.Decompresser;
import io.github.scf37.jpaq.libzpaq.Dialect;
import io.github.scf37.jpaq.libzpaq.MemoryBuffer;
import io.github.scf37.jpaq.libzpaq.Sha1;
import io.github.scf37.jpaq.libzpaq.Sha256;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A Jidac object represents an archive contents: a list of file fragments with hash, size, and
 * archive offset, and a list of files with date, attributes, and list of fragment pointers.
 * Methods add to, extract from, compare, and list the archive.
 */
public final class Jidac {
  public static final String ZPAQ_VERSION = "7.15";

  /** Unless -until. */
  public static final long DEFAULT_VERSION = 99999999999999L;

  // Options. Text is byte strings (see Strs).
  public char command;                      // 'a' add, 'x' extract, 'l' read the file list
  public String archive = "";               // archive name
  public int all;                           // read every version, in directories of n digits
  public boolean force;                     // add: also unchanged files; extract: overwrite
  public int fragment = 6;                  // average fragment size 2^n KiB
  public String index;                      // add: remote archive index; extract: index to write
  public byte[] password;                   // hash of the key or null
  public String method = "";                // default "1"
  public boolean noattributes;              // don't store or restore attributes
  public String repack;                     // extract: write the selected files to this archive
  public byte[] newPassword;                // its key hash
  public boolean dotest;                    // extract: verify, write no files
  public int threads;                       // default is number of cores
  public long date;                         // now as decimal YYYYMMDDHHMMSS (UT)
  public long version = DEFAULT_VERSION;    // version number or 14 digit date

  /** After add: files that could not be read; after extract: files not extracted completely. */
  public int errors;

  /** Archive names to read (with the version directory with -all); others are not loaded. */
  public Predicate<String> select = n -> true;
  /** Add: local files and directories to add. */
  public List<String> roots = new ArrayList<>();
  /** Add: local name -> archive name. */
  public UnaryOperator<String> toArchive = UnaryOperator.identity();
  /** Add: local names not looked at (directories not entered). */
  public Predicate<String> prune = n -> false;
  /** Add: local names to add. */
  public Predicate<String> accept = n -> true;
  /** Extract, repack: archive name -> output name (local file, or name in the new archive). */
  public UnaryOperator<String> toOutput = UnaryOperator.identity();

  // Archive state
  long dhsize;                       // total size of D blocks according to H blocks
  long dcsize;                       // total size of D blocks according to C blocks
  FragTable ht = new FragTable();    // list of fragments
  FileTable dt = new FileTable();              // set of files in archive
  FileTable edt = new FileTable();             // set of external files to add or compare
  List<Block> block = new ArrayList<>();       // list of data blocks to extract
  List<Ver> ver = new ArrayList<>();           // version info


  /** Where output goes, file names are resolved, and the clock of this run. */
  public final Ctx ctx;
  /** Where archive files are (tomes, parts, index). */
  final ArchiveIo io;
  Format format;                     // format written by add
  public String indexOut;            // sequential format: name of the updated index to write
  public String readIndex;           // extract/list: read the file list from this index instead
  Dialect readDialect;               // block magic of the archive last read, null if none
  long virtualEnd;                   // end of the last archive read, counting D blocks of an index

  /**
   * A command on the archive files in {@code storage}; {@code format} is the format written by
   * add (reading accepts both).
   */
  public Jidac(Ctx ctx, Storage storage, Format format) {
    this(ctx, new ArchiveIo(ctx, storage, format));
  }

  private Jidac(Ctx ctx, ArchiveIo io) {
    this.ctx = ctx;
    this.io = io;
    this.format = io.format;
    ver.add(new Ver());  // version 0
  }

  // ---------------------------------------------------------------------------------------
  // Utilities

  /** Reads 4 byte little-endian int from s[p..]. */
  static int btoi(byte[] s, int p) {
    return (s[p] & 255) | ((s[p + 1] & 255) << 8) | ((s[p + 2] & 255) << 16)
        | ((s[p + 3] & 255) << 24);
  }

  /** Reads 8 byte little-endian int from s[p..]. */
  static long btol(byte[] s, int p) {
    return (btoi(s, p) & 0xFFFFFFFFL) + ((long) btoi(s, p + 4) << 32);
  }

  /** Appends n bytes of x to sb in LSB order. */
  static void puti(MemoryBuffer sb, long x, int n) {
    for (; n > 0; --n) {
      sb.put((int) (x & 255));
      x >>>= 8;
    }
  }

  /** Returns a/b such that there is exactly one "/" in between. */
  static String appendPath(String a, String b) {
    int na = a.length();
    int nb = b.length();
    if (Fs.WINDOWS && nb > 1 && b.charAt(1) == ':') {  // remove : from drive letter
      if (nb > 2 && b.charAt(2) != '/') b = b.charAt(0) + "/" + b.substring(2);
      else b = b.charAt(0) + b.substring(2);
    }
    if (nb > 0 && b.charAt(0) == '/') b = b.substring(1);
    if (na > 0 && a.charAt(na - 1) == '/') a = a.substring(0, na - 1);
    return a + "/" + b;
  }

  /** Guesses the number of cores. */
  public static int numberOfProcessors() {
    int rc = Runtime.getRuntime().availableProcessors();
    return Math.max(rc, 1);
  }

  /** Hash of a password argument as used for the -key option. */
  public static byte[] hashPassword(String s) {
    Sha256 sha256 = new Sha256();
    for (int i = 0; i < s.length(); i++) sha256.put(s.charAt(i));
    return sha256.result();
  }

  // ---------------------------------------------------------------------------------------
  // Commands

  /**
   * Executes the command with the options set (the part of zpaq's command line handling after
   * the arguments are parsed). Returns 0 OK, 1 warnings. Errors that stop the command are thrown.
   */
  public int run() {
    prepare();
    return execute();
  }

  /** Completes the options: number of threads, date check, version counted from the end. */
  public void prepare() {
    // Set threads
    if (threads < 1) threads = numberOfProcessors();

    // Test date
    if (date < 19000000000000L || date > 30000000000000L)
      throw ZpaqException.error("date is incorrect, use -until YYYY-MM-DD HH:MM:SS to set");

    // Adjust negative version
    if (version < 0) {
      Jidac jidac = new Jidac(ctx, io);
      jidac.copyOptions(this);
      jidac.version = DEFAULT_VERSION;
      jidac.readArchive(archive, null);
      version += jidac.ver.size() - 1;
      ctx.emit(Event.VersionSelected.of(version));
    }
  }

  /** Runs the command after {@link #prepare}. */
  public int execute() {
    if (command == 'a') return new Adder(this).add();
    else if (command == 'x') return new Extractor(this).extract();
    throw new IllegalStateException("no command");
  }

  /** Copies the options (not the archive state) from another Jidac. */
  private void copyOptions(Jidac o) {
    command = o.command;
    archive = o.archive;
    all = o.all;
    force = o.force;
    fragment = o.fragment;
    index = o.index;
    indexOut = o.indexOut;
    readIndex = o.readIndex;
    password = o.password;
    method = o.method;
    noattributes = o.noattributes;
    repack = o.repack;
    newPassword = o.newPassword;
    dotest = o.dotest;
    threads = o.threads;
    date = o.date;
    version = o.version;
    select = o.select;
    roots = o.roots;
    toArchive = o.toArchive;
    prune = o.prune;
    accept = o.accept;
    toOutput = o.toOutput;
  }

  // ---------------------------------------------------------------------------------------
  // Contents, for the API

  /** Reads the file list of the archive (or of readIndex) up to version. */
  public void readContents() {
    command = 'l';
    prepare();
    readArchive(fromIndex() ? readIndex : archive, null);
  }

  /** Receives the entries of an archive or local files; names are real text. */
  @FunctionalInterface
  public interface EntrySink {
    void accept(String name, long date, long size, long attributes, int[] fragments);
  }

  /** Receives the versions of an archive. */
  @FunctionalInterface
  public interface VersionSink {
    void accept(int number, long date, int updates, int deletes, long offset, int firstFragment);
  }

  /** The entries read, in name order: present ones, or all with -all (deletions have date 0). */
  public void entries(EntrySink sink) {
    for (DT p : dt.values()) {
      if (p.date() == 0 && all == 0) continue;
      int[] f = new int[p.ptrSize()];
      for (int i = 0; i < f.length; i++) f[i] = p.ptr(i);
      sink.accept(Strs.fromBytes(p.name()), p.date(), p.size(), p.attr(), f);
    }
  }

  /** The versions read, oldest first. */
  public void versions(VersionSink sink) {
    for (int i = 1; i < ver.size(); i++) {
      Ver v = ver.get(i);
      sink.accept(i, v.date, v.updates, v.deletes, v.offset, v.firstFragment);
    }
  }

  /** Number of fragment IDs (fragment 0 is not used). */
  public int fragmentCount() {
    return ht.size();
  }

  /** Size of a fragment, -1 if unknown. */
  public int fragmentSize(int id) {
    return ht.usize(id);
  }

  /** End of the archive read, counting the data an index refers to. */
  public long end() {
    return virtualEnd;
  }

  /** Size of the data blocks according to the fragment tables. */
  public long indexedDataSize() {
    return dhsize;
  }

  /** Size of the data blocks in the files read (less than indexedDataSize for an index). */
  public long storedDataSize() {
    return dcsize;
  }

  /**
   * True if the entry (byte string name) has the content of the local file, or neither exists;
   * compares sizes and fragment hashes.
   */
  public boolean sameContent(String name, String localName) {
    DT p = dt.get(name);
    return p != null && new Extractor(this).equal(p, localName);
  }

  /** Scans the roots (pruned, accepted) and passes the local files and directories found. */
  public void scanLocal(EntrySink sink) {
    for (String f : roots) scandir(f);
    for (DT p : edt.values())
      sink.accept(Strs.fromBytes(p.name()), p.date(), p.size(), p.attr(), new int[0]);
  }

  // ---------------------------------------------------------------------------------------
  // read_archive

  /** For libzpaq output to a string less than 64K chars. */
  private static final class StringWriter implements ZpaqWriter {
    final StringBuilder s = new StringBuilder();

    @Override
    public void put(int c) {
      if (s.length() >= 65535) throw ZpaqException.error("string too long");
      s.append((char) (c & 255));
    }
  }

  /**
   * Reads arc up to -date into ht, dt, ver. Returns the place to append. If errors is not null
   * then errors[0] is set to the number of errors found.
   */
  long readArchive(String arc, int[] errors) {
    if (errors != null) errors[0] = 0;
    dcsize = dhsize = 0;
    int nfiles = 0;  // count

    // Open archive
    InputArchive in = new InputArchive(io, arc, password);
    if (!in.isOpen()) {
      if (command != 'a') {
        ctx.emit(Event.ArchiveNotFound.of(Ctx.t(arc)));
        if (errors != null) ++errors[0];
      }
      return 0;
    }
    ctx.emit(Event.ArchiveReading.of(Ctx.t(arc), version == DEFAULT_VERSION ? -1 : version));

    // Test password
    {
      byte[] s = new byte[4];
      final int nr = in.read(s, 0, 4);
      if (nr > 0 && !(s[0] == '7' && s[1] == 'k' && s[2] == 'S' && s[3] == 't')
          && (!(s[0] == 'z' && s[1] == 'P' && s[2] == 'Q') || s[3] < 1))
        throw ZpaqException.error("password incorrect");
      in.seek(-nr, InputArchive.Whence.CUR);
    }

    // Scan archive contents
    String lastfile = archive;  // last named file in streaming format
    if (lastfile.length() > 5 && lastfile.endsWith(".zpaq"))
      lastfile = lastfile.substring(0, lastfile.length() - 5);  // drop .zpaq
    final boolean asIndex = readIndex != null && arc.equals(readIndex);
    long blockOffset = 32 * (password != null ? 1 : 0);  // start of last block of any type
    long dataOffset = blockOffset;  // start of last block of d fragments
    boolean foundData = false;  // exit if nothing found
    boolean first = true;       // first segment in archive?
    MemoryBuffer os = new MemoryBuffer(32832);  // decompressed block

    // Detect archive format and read the filenames, fragment sizes, and hashes. In JIDAC format,
    // these are in the index blocks, allowing data to be skipped. Otherwise the whole archive is
    // scanned to get this information from the segment headers and trailers.
    boolean done = false;
    Dialect adia = null;  // block magic of the archive
    String fatal = null;  // error that ends the scan
    while (!done) {
      Decompresser d = new Decompresser();
      try {
        endblock:
        {
          d.setInput(in);
          double[] mem = {0};
          while (d.findBlock(mem)) {
            foundData = true;
            if (adia == null) {
              adia = d.dialect();
            } else if (adia != d.dialect()) {
              fatal = "cannot mix zpaq and jpaq format blocks in one archive";
              done = true;
              break endblock;
            }
            final long blockStart = blockOffset;
            boolean sawD = false;  // block of D data (sequential format)

            // Read the segments in the current block
            StringWriter filename = new StringWriter();
            StringWriter comment = new StringWriter();
            int segs = 0;  // segments in block
            boolean skip = false;  // skip decompression?
            while (d.findFilename(filename)) {
              if (filename.s.length() > 0) {
                for (int i = 0; i < filename.s.length(); ++i)
                  if (filename.s.charAt(i) == '\\') filename.s.setCharAt(i, '/');
                lastfile = filename.s.toString();
              }
              comment.s.setLength(0);
              d.readComment(comment);

              // Test for JIDAC format. Filename is jDC<fdate>[cdhi]<num> and comment ends with
              // " jDC\x01". Skip d (data) blocks.
              if (comment.s.length() >= 4
                  && comment.s.substring(comment.s.length() - 4).equals("jDC\u0001")) {
                final String fname = filename.s.toString();
                if (fname.length() != 28 || !fname.startsWith("jDC"))
                  throw ZpaqException.error("bad journaling block name");
                if (skip) throw ZpaqException.error("mixed journaling and streaming block");

                // Read uncompressed size from comment
                long usize = 0;
                int i;
                for (i = 0; i < comment.s.length() && Strs.isdigit(comment.s.charAt(i)); ++i) {
                  usize = usize * 10 + comment.s.charAt(i) - '0';
                  if (usize > 0xffffffffL) throw ZpaqException.error("journaling block too big");
                }

                // Read the date and number in the filename
                long fdate = 0;
                long num = 0;
                for (i = 3; i < 17 && Strs.isdigit(fname.charAt(i)); ++i)
                  fdate = fdate * 10 + fname.charAt(i) - '0';
                if (i != 17 || fdate < 19000000000000L || fdate >= 30000000000000L)
                  throw ZpaqException.error("bad date");
                for (i = 18; i < 28 && Strs.isdigit(fname.charAt(i)); ++i)
                  num = num * 10 + fname.charAt(i) - '0';
                if (i != 28 || num > 0xffffffffL) throw ZpaqException.error("bad fragment");

                // Decompress the block.
                os.resize(0);
                os.setLimit(usize);
                d.setOutput(os);
                Sha1 sha1 = new Sha1();
                d.setSha1(sha1);
                final char btype = fname.charAt(17);
                if (btype == 'c' || btype == 'h' || btype == 'i') {
                  if (mem[0] > 1.5e9) throw ZpaqException.error("index block requires too much memory");
                  d.decompress();
                  byte[] sha1result = new byte[21];
                  d.readSegmentEnd(sha1result);
                  if (os.size() != usize) throw ZpaqException.error("bad block size");
                  if (usize != sha1.usize()) throw ZpaqException.error("bad checksum size");
                  if (sha1result[0] != 0
                      && !Arrays.equals(sha1result, 1, 21, sha1.result(), 0, 20))
                    throw ZpaqException.error("bad checksum");
                } else {
                  d.readSegmentEnd();
                }
                final byte[] osd = os.data();

                if (btype == 'c') {
                  // Transaction header (type c). If in the future then stop here, else read 8
                  // byte data size from input and jump over it.
                  if (os.size() < 8) throw ZpaqException.error("c block too small");
                  final long dataOffsetReal = in.tell() + 1 - d.buffered();
                  // Sequential format: D blocks follow inline and csize is not used. An index has
                  // no D blocks; positions count them as if they were there.
                  final long skew = adia == Dialect.JPAQ || asIndex ? dhsize - dcsize : 0;
                  dataOffset = dataOffsetReal + skew;
                  final long jmp = adia == Dialect.JPAQ ? 0 : btol(osd, 0);
                  if (jmp < 0) ctx.emit(Event.IncompleteTransaction.of());
                  if (jmp < 0
                      || (version < 19000000000000L && (long) ver.size() > version)
                      || (version >= 19000000000000L && version < fdate)) {
                    done = true;  // roll back to here
                    break endblock;
                  } else {
                    dcsize += jmp;
                    if (jmp != 0) in.seek(dataOffsetReal + jmp, InputArchive.Whence.SET);
                    Ver v = new Ver();
                    ver.add(v);
                    v.firstFragment = ht.size();
                    v.offset = blockOffset + skew;
                    v.dataOffset = dataOffset;
                    v.date = v.lastdate = fdate;
                    v.csize = jmp;
                    if (all != 0 && command == 'x') {  // version directory
                      String fn = Strs.itos(ver.size() - 1, all) + "/";
                      if (select.test(fn)) dtGet(fn).date(fdate);
                    }
                    if (jmp != 0) break endblock;
                  }
                } else if (btype == 'h') {
                  // Fragment table (type h). Contents is bsize[4] (sha1[20] usize[4])... for
                  // fragment N... where bsize is the compressed block size. Store in
                  // ht[].{sha1,usize}. Set ht[].csize to block offset assuming N in ascending
                  // order.
                  Ver lv = ver.get(ver.size() - 1);
                  if (fdate > lv.lastdate) lv.lastdate = fdate;
                  if (os.size() % 24 != 4) throw ZpaqException.error("bad h block size");
                  final int n = (os.size() - 4) / 24;
                  if (num < 1 || num + n > 0xffffffffL) throw ZpaqException.error("bad h fragment");
                  int s = 0;
                  final long bsize = btoi(osd, s) & 0xFFFFFFFFL;
                  s += 4;
                  dhsize += bsize;
                  if ((long) ht.size() > num) {
                    ctx.diagnostic(String.format(java.util.Locale.ROOT,
                        "Unordered fragment tables: expected >= %d found %1.0f\n", ht.size(),
                        (double) num));
                  }
                  for (int k = 0; k < n; ++k) {
                    if (k == 0) {
                      Block b = new Block((int) num, dataOffset);
                      b.usize = 8;
                      b.bsize = bsize;
                      b.frags = os.size() / 24;
                      block.add(b);
                    }
                    while ((long) ht.size() <= num + k) ht.addEmpty();
                    final int idx = (int) (num + k);
                    ht.setSha1(idx, osd, s);
                    s += 20;
                    final int f = btoi(osd, s);
                    s += 4;
                    if (f < 0) throw ZpaqException.error("fragment too big");
                    ht.setUsize(idx, f);
                    block.get(block.size() - 1).usize += (long) f + 4L;
                  }
                  dataOffset += bsize;
                } else if (btype == 'i') {
                  // Index (type i). Contents is: 0[8] filename 0 (deletion) or: date[8] filename 0
                  // na[4] attr[na] ni[4] ptr[ni][4]. Read into DT
                  Ver lv = ver.get(ver.size() - 1);
                  if (fdate > lv.lastdate) lv.lastdate = fdate;
                  int s = 0;
                  final int end = os.size();
                  int[] ptrs = new int[16];  // pointer list of the file being read
                  while (s + 9 <= end) {
                    final long fdt = btol(osd, s);  // date
                    long fattr = 0;
                    int ni = 0;
                    s += 8;
                    if (fdt != 0) ++lv.updates;
                    else ++lv.deletes;
                    int len = 0;
                    while (s + len < end && osd[s + len] != 0) len++;
                    if (s + len >= end) {
                      // no terminating NUL: strlen would run past the block
                      throw ZpaqException.error("filename too long");
                    }
                    if (len > 65535) throw ZpaqException.error("filename too long");
                    String fn = Strs.ofBytes(osd, s, len);  // filename renamed
                    if (all != 0) fn = appendPath(Strs.itos(ver.size() - 1, all), fn);
                    final boolean issel = select.test(fn);
                    s += len + 1;  // skip filename
                    if (s > end) throw ZpaqException.error("filename too long");
                    if (fdt != 0) {
                      ++nfiles;
                      if (s + 4 > end) throw ZpaqException.error("missing attr");
                      final long na = btoi(osd, s) & 0xFFFFFFFFL;  // attr bytes
                      s += 4;
                      if (s + na > end || na > 65535) throw ZpaqException.error("attr too long");
                      for (int k = 0; k < na; ++k, ++s)  // read attr
                        if (k < 8) fattr += (long) (osd[s] & 255) << (k * 8);
                      if (noattributes) fattr = 0;
                      if (s + 4 > end) throw ZpaqException.error("missing ptr");
                      final long nl = btoi(osd, s) & 0xFFFFFFFFL;  // ptr list size
                      s += 4;
                      if (nl > (end - s) / 4L) throw ZpaqException.error("ptr list too long");
                      ni = (int) nl;
                      if (issel && ni > ptrs.length) ptrs = new int[ni];
                      for (int k = 0; k < ni; ++k) {  // read ptr
                        final int j = btoi(osd, s);
                        s += 4;
                        if (issel) ptrs[k] = j;
                      }
                    }
                    if (issel) {
                      DT dtr = dt.put(fn);
                      dtr.date(fdt);
                      dtr.attr(fattr);
                      dtr.ptrResize(ni);
                      for (int k = 0; k < ni; ++k) dtr.setPtr(k, ptrs[k]);
                    }
                  }  // end while more files
                } else if (btype == 'd' && adia == Dialect.JPAQ && ver.size() > 1) {
                  sawD = true;  // size is added to csize and dcsize below
                } else {
                  ctx.note("Skipping " + fname + " " + comment.s + "\n");
                  throw ZpaqException.error("Unexpected journaling block");
                }
              } else {
                // Streaming format

                // If previous version does not exist, start a new one
                if (ver.size() == 1) {
                  if (version < 1) {
                    done = true;
                    break endblock;
                  }
                  Ver v = new Ver();
                  ver.add(v);
                  v.firstFragment = ht.size();
                  v.offset = blockOffset;
                  v.csize = -1;
                }

                byte[] sha1result = new byte[21];
                d.readSegmentEnd(sha1result);
                skip = true;
                String fn = lastfile;
                if (all != 0) fn = appendPath(Strs.itos(ver.size() - 1, all), fn);
                if (select.test(fn)) {
                  DT dtr = dtGet(fn);
                  if (filename.s.length() > 0 || first) {
                    ++nfiles;
                    dtr.date(date);
                    dtr.attr(0);
                    dtr.ptrClear();
                    ++ver.get(ver.size() - 1).updates;
                  }
                  dtr.ptrPush(ht.size());
                }
                if (segs == 0 || block.size() == 0) block.add(new Block(ht.size(), blockOffset));
                ht.add(sha1result, 1, -1);
              }  // end else streaming
              ++segs;
              filename.s.setLength(0);
              first = false;
            }  // end while findFilename
            if (!done) blockOffset = in.tell() - d.buffered();
            if (sawD) {
              dcsize += blockOffset - blockStart;
              ver.get(ver.size() - 1).csize += blockOffset - blockStart;
            }
          }  // end while findBlock
          done = true;
        }  // endblock
      } catch (RuntimeException | OutOfMemoryError e) {
        in.seek(-d.buffered(), InputArchive.Whence.CUR);
        ctx.emit(Event.BlockSkipped.of(blockOffset, Ctx.t(what(e))));
        if (errors != null) ++errors[0];
      }
    }  // end while !done
    if (fatal != null) throw ZpaqException.error(fatal);
    if (in.tell() > 32 * (password != null ? 1 : 0) && !foundData)
      throw ZpaqException.error("archive contains no data");
    if (adia == Dialect.JPAQ && ver.size() > 1) {
      // A transaction that never got its file list was not committed
      Ver lv = ver.get(ver.size() - 1);
      if (lv.updates + lv.deletes == 0) {
        ctx.emit(Event.IncompleteTransaction.of());
        ver.remove(ver.size() - 1);
        ht.truncate(lv.firstFragment);
        while (block.size() > 0 && block.get(block.size() - 1).start >= lv.firstFragment)
          block.remove(block.size() - 1);
      }
    }
    readDialect = adia;
    virtualEnd = blockOffset + (adia == Dialect.JPAQ || asIndex ? dhsize - dcsize : 0);
    ctx.emit(Event.ArchiveRead.of(ver.size() - 1, nfiles, ht.size() - 1, blockOffset));

    // Calculate file sizes
    for (DT p : dt.unordered()) {
      for (int i = 0; i < p.ptrSize(); ++i) {
        final int j = p.ptr(i);
        if (j > 0 && j < ht.size() && p.size() >= 0) {
          if (ht.usize(j) >= 0) p.size(p.size() + ht.usize(j));
          else p.size(-1);  // unknown size
        }
      }
    }
    dt.compact();
    in.close();
    return blockOffset;
  }

  /** The file list is read from {@link #readIndex}; the archive parts only hold the data. */
  boolean fromIndex() {
    return readIndex != null;
  }

  /** Archive file to read data from: the parts, whose sizes an index knows. */
  InputArchive openArchive() {
    return new InputArchive(io, archive, password, partSizes());
  }

  /** Sizes of the parts of the archive, as told by the index that was read; else null. */
  List<Long> partSizes() {
    if (!fromIndex() || ver.size() < 2) return null;
    List<Long> r = new ArrayList<>();
    long start = 0;
    for (int i = 1; i < ver.size(); ++i) {
      long end = i + 1 < ver.size() ? ver.get(i + 1).offset : virtualEnd;
      r.add(end - start);
      start = end;
    }
    return r;
  }

  /** Message of an exception as reported by e.what(). */
  public static String what(Throwable e) {
    String m = e.getMessage();
    if (e instanceof OutOfMemoryError) return "std::bad_alloc";
    if (e instanceof ZpaqException && m != null) return m;
    return m != null ? m : e.toString();
  }

  /** dt[fn] of the C++ map: the existing entry or a new default one. */
  DT dtGet(String fn) {
    return dt.getOrAdd(fn);
  }

  /** Same for the external file map. */
  DT edtGet(String fn) {
    return edt.getOrAdd(fn);
  }

  // ---------------------------------------------------------------------------------------
  // Scanning external files

  /**
   * Inserts the external filename (UTF-8 byte string with "/") into edt unless pruned and if
   * accepted. If filename is a directory (and not pruned) then also inserts its contents.
   */
  void scandir(String filename) {

    // Don't scan what is excluded (directories excluded are not entered)
    if (prune.test(filename)) return;

    // Add regular files and directories
    while (filename.length() > 1 && filename.charAt(filename.length() - 1) == '/')
      filename = filename.substring(0, filename.length() - 1);  // remove trailing /
    Exception[] err = new Exception[1];
    Fs.Stat sb = ctx.fs.lstat(filename, err);
    if (sb != null) {
      if (sb.regular()) addfile(filename, Dates.decimalTime(sb.mtime()), sb.size(), attrOf(sb));

      // Traverse directory
      if (sb.directory()) {
        addfile(filename.equals("/") ? "/" : filename + "/", Dates.decimalTime(sb.mtime()), 0,
            attrOf(sb));
        try (var ds = java.nio.file.Files.newDirectoryStream(ctx.fs.path(filename))) {
          for (java.nio.file.Path p : ds) {
            String s = filename;
            if (!s.equals("/")) s += "/";
            s += Fs.bytes(p.getFileName().toString());
            scandir(s);
          }
        } catch (java.io.IOException | RuntimeException e) {
          ctx.fs.printerr(filename, e);
        }
      }
    } else {
      ctx.fs.printerr(filename, err[0]);
    }
  }

  static long attrOf(Fs.Stat sb) {
    if (Fs.UNIX) return 'u' + ((sb.mode() & 0xFFFFFFFFL) << 8);
    return 'w' + (sb.mode() << 8);
  }

  /** Adds an external file and its date, size, and attributes to edt. */
  void addfile(String filename, long edate, long esize, long eattr) {
    if (!accept.test(filename)) return;
    DT d = edtGet(filename);
    d.date(edate);
    d.size(esize);
    d.attr(noattributes ? 0 : eattr);
    d.data(0);
  }
}
