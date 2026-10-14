package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.api.ErrorLine;
import io.github.scf37.jpaq.api.Format;
import io.github.scf37.jpaq.api.InfoLine;
import io.github.scf37.jpaq.libzpaq.Decompresser;
import io.github.scf37.jpaq.libzpaq.Dialect;
import io.github.scf37.jpaq.libzpaq.MemoryBuffer;
import io.github.scf37.jpaq.libzpaq.Sha1;
import io.github.scf37.jpaq.libzpaq.Sha256;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * A Jidac object represents an archive contents: a list of file fragments with hash, size, and
 * archive offset, and a list of files with date, attributes, and list of fragment pointers.
 * Methods add to, extract from, compare, and list the archive.
 */
final class Jidac {
  static final String ZPAQ_VERSION = "7.15";

  /** Unless -until. */
  static final long DEFAULT_VERSION = 99999999999999L;

  // Command line arguments
  char command;                      // command 'a', 'x', or 'l'
  String archive = "";               // archive name
  List<String> files = new ArrayList<>();  // filename args
  int all;                           // -all option
  boolean force;                     // -force option
  int fragment;                      // -fragment option
  String index;                      // index option
  byte[] password;                   // hash of -key argument or null
  String method = "";                // default "1"
  boolean noattributes;              // -noattributes option
  List<String> notfiles = new ArrayList<>();   // list of prefixes to exclude
  String nottype = "";               // -not =...
  List<String> onlyfiles = new ArrayList<>();  // list of prefixes to include
  String repack;                     // -repack output file
  byte[] newPassword;                // -repack hashed password
  int summary;                       // summary option if > 0, detailed if -1
  boolean dotest;                    // -test option
  int threads;                       // default is number of cores
  List<String> tofiles = new ArrayList<>();    // -to option
  long date;                         // now as decimal YYYYMMDDHHMMSS (UT)
  long version;                      // version number or 14 digit date

  // Archive state
  long dhsize;                       // total size of D blocks according to H blocks
  long dcsize;                       // total size of D blocks according to C blocks
  FragTable ht = new FragTable();    // list of fragments
  TreeMap<String, DT> dt = new TreeMap<>();    // set of files in archive
  TreeMap<String, DT> edt = new TreeMap<>();   // set of external files to add or compare
  List<Block> block = new ArrayList<>();       // list of data blocks to extract
  List<Ver> ver = new ArrayList<>();           // version info

  private static final InfoLine INCOMPLETE =
      new InfoLine.IncompleteTransaction("Incomplete transaction ignored\n");

  /** Time of program start in milliseconds, for progress reports. */
  static long globalStart;

  /** Where archive files are (tomes, parts, index). */
  final ArchiveIo io;
  Format format;                     // format written by add
  String indexOut;                   // sequential format: name of the updated index to write
  String readIndex;                  // extract/list: read the file list from this index instead
  java.util.function.Consumer<Jidac> configure;  // sets options the command line has no syntax for
  Dialect readDialect;               // block magic of the archive last read, null if none
  long virtualEnd;                   // end of the last archive read, counting D blocks of an index

  Jidac(ArchiveIo io) {
    this.io = io;
    this.format = io.format;
    ver.add(new Ver());  // version 0
  }

  // ---------------------------------------------------------------------------------------
  // Utilities

  /** Returns relative time in milliseconds. */
  static long mtime() {
    return System.currentTimeMillis();
  }

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

  /** In Windows converts upper case to lower case (identity on other systems). */
  private static int tolowerW(int c) {
    if (Fs.WINDOWS && c >= 'A' && c <= 'Z') return c - 'A' + 'a';
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
    for (; Strs.at(a, ai) != 0; ++ai, ++bi) {
      final int ca = tolowerW(Strs.at(a, ai));
      final int cb = tolowerW(Strs.at(b, bi));
      if (ca == '*') {
        while (true) {
          if (ispath(a, ai + 1, b, bi)) return true;
          if (Strs.at(b, bi) == 0) return false;
          ++bi;
        }
      } else if (ca == '?') {
        if (Strs.at(b, bi) == 0) return false;
      } else if (ca == cb && ca == '/' && Strs.at(a, ai + 1) == 0) {
        return true;
      } else if (ca != cb) {
        return false;
      }
    }
    return Strs.at(b, bi) == 0 || Strs.at(b, bi) == '/';
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

  /** Guesses the number of cores. */
  static int numberOfProcessors() {
    int rc = Runtime.getRuntime().availableProcessors();
    return Math.max(rc, 1);
  }

  /** Prints the help message and exits. */
  void usage() {
    Console.print(String.format(java.util.Locale.ROOT, """
        Usage: zpaq command archive[.zpaq] files... -options...
        Files... may be directory trees. Default is the whole archive.
        Use * or ???? in archive name for multi-part or "" for empty.
        Commands:
           a  add         Append files to archive if dates have changed.
           x  extract     Extract most recent versions of files.
           l  list        List or compare external files to archive by dates.
        Options:
          -all [N]        Extract/list versions in N [4] digit directories.
          -f -force       Add: append files if contents have changed.
                          Extract: overwrite existing output files.
                          List: compare file contents instead of dates.
          -index F        Extract: create index F for archive.
                          Add: create suffix for archive indexed by F, update F.
          -key X          Create or access encrypted archive with password X.
          -mN  -method N  Compress level N (0..5 = faster..better, default 1).
          -noattributes   Ignore/don't save file attributes or permissions.
          -not files...   Exclude. * and ? match any string or char.
               =[+-#^?]   List: exclude by comparison result.
          -only files...  Include only matches (default: *).
          -repack F [X]   Extract to new archive F with key X (default: none).
          -sN -summary N  List: show top N sorted by size. -1: show frag IDs.
                          Add/Extract: if N > 0 show brief progress.
          -test           Extract: verify but do not write files.
          -tN -threads N  Use N threads (default: 0 = %d cores).
          -to out...      Rename files... to out... or all to out/all.
          -until N        Roll back archive to N'th update or -N from end.
          -until %s  Set date, roll back (UT, default time: 235959).
        """, threads, Dates.dateToString(date)));
    throw new ExitException(1);
  }

  /** Overrides the current time (null = system clock / SOURCE_DATE_EPOCH). */
  static volatile Instant clockOverride;

  /**
   * The current time: the system clock, unless the environment variable SOURCE_DATE_EPOCH (seconds
   * since 1970) is set, which makes archives reproducible.
   */
  static Instant currentTime() {
    Instant o = clockOverride;
    if (o != null) return o;
    String e = System.getenv("SOURCE_DATE_EPOCH");
    if (e != null && !e.isBlank()) {
      try {
        return Instant.ofEpochSecond(Long.parseLong(e.trim()));
      } catch (NumberFormatException ex) {
        // fall through to the system clock
      }
    }
    return Instant.now();
  }

  /** Current time as a decimal date. */
  static long now() {
    ZonedDateTime t = currentTime().atZone(ZoneOffset.UTC);
    return t.getYear() * 10000000000L + t.getMonthValue() * 100000000L
        + t.getDayOfMonth() * 1000000L + t.getHour() * 10000L + t.getMinute() * 100L
        + t.getSecond();
  }

  /** Hash of a password argument as used for the -key option. */
  static byte[] hashPassword(String s) {
    Sha256 sha256 = new Sha256();
    for (int i = 0; i < s.length(); i++) sha256.put(s.charAt(i));
    return sha256.result();
  }

  // ---------------------------------------------------------------------------------------
  // Command line

  /** Parses the command line and executes the command. Returns 1 if error else 0. */
  int doCommand(String[] argv) {
    final int argc = argv.length;

    // Initialize options to default values
    command = 0;
    force = false;
    fragment = 6;
    all = 0;
    password = null;
    index = null;
    method = "";
    noattributes = false;
    repack = null;
    newPassword = null;
    summary = 0;
    dotest = false;
    threads = 0;
    version = DEFAULT_VERSION;
    date = 0;

    Console.emit(new InfoLine.Banner(ZPAQ_VERSION,
        "zpaq v" + ZPAQ_VERSION + " journaling archiver, Java port\n"));

    // Get date
    date = now();

    // Get optional options
    for (int i = 1; i < argc; ++i) {
      final String opt = argv[i];  // read command
      if ((opt.equals("add") || opt.equals("extract") || opt.equals("list") || opt.equals("convert")
          || opt.equals("a") || opt.equals("x") || opt.equals("l") || opt.equals("c"))
          && i < argc - 1 && Strs.at(argv[i + 1], 0) != '-' && command == 0) {
        command = opt.charAt(0);
        if (opt.equals("extract")) command = 'x';
        archive = argv[++i];  // append ".zpaq" to archive if no extension
        int slash = argv[i].lastIndexOf('/');
        int dot = argv[i].lastIndexOf('.');
        if (slash >= 0 && dot < slash) dot = -1;
        if (dot < 0 && !archive.isEmpty()) archive += ".zpaq";
        while (++i < argc && Strs.at(argv[i], 0) != '-')  // read filename args
          files.add(argv[i]);
        --i;
      } else if (opt.length() < 2 || opt.charAt(0) != '-') {
        usage();
      } else if (opt.equals("-all")) {
        all = 4;
        if (i < argc - 1 && Strs.isdigit(Strs.at(argv[i + 1], 0))) all = Strs.atoi(argv[++i]);
      } else if (opt.equals("-force") || opt.equals("-f")) {
        force = true;
      } else if (opt.equals("-fragment") && i < argc - 1) {
        fragment = Strs.atoi(argv[++i]);
      } else if (opt.equals("-index") && i < argc - 1) {
        index = argv[++i];
      } else if (opt.equals("-key") && i < argc - 1) {
        password = hashPassword(argv[++i]);
      } else if (opt.equals("-method") && i < argc - 1) {
        method = argv[++i];
      } else if (opt.charAt(1) == 'm') {
        method = argv[i].substring(2);
      } else if (opt.equals("-noattributes")) {
        noattributes = true;
      } else if (opt.equals("-not")) {  // read notfiles
        while (++i < argc && Strs.at(argv[i], 0) != '-') {
          if (Strs.at(argv[i], 0) == '=') nottype = argv[i];
          else notfiles.add(argv[i]);
        }
        --i;
      } else if (opt.equals("-only")) {  // read onlyfiles
        while (++i < argc && Strs.at(argv[i], 0) != '-') onlyfiles.add(argv[i]);
        --i;
      } else if (opt.equals("-repack") && i < argc - 1) {
        repack = argv[++i];
        if (i < argc - 1 && Strs.at(argv[i + 1], 0) != '-') newPassword = hashPassword(argv[++i]);
      } else if (opt.equals("-summary") && i < argc - 1) {
        summary = Strs.atoi(argv[++i]);
      } else if (opt.charAt(1) == 's') {
        summary = Strs.atoi(argv[i], 2);
      } else if (opt.equals("-test")) {
        dotest = true;
      } else if (opt.equals("-to")) {  // read tofiles
        while (++i < argc && Strs.at(argv[i], 0) != '-') tofiles.add(argv[i]);
        if (tofiles.size() == 0) tofiles.add("");
        --i;
      } else if (opt.equals("-threads") && i < argc - 1) {
        threads = Strs.atoi(argv[++i]);
      } else if (opt.charAt(1) == 't') {
        threads = Strs.atoi(argv[i], 2);
      } else if (opt.equals("-until") && i + 1 < argc) {  // read date

        // Read digits from multiple args and fill in leading zeros
        version = 0;
        int digits = 0;
        if (Strs.at(argv[i + 1], 0) == '-') {  // negative version
          version = Strs.atol(argv[i + 1]);
          if (version > -1) usage();
          ++i;
        } else {  // positive version or date
          while (++i < argc && Strs.at(argv[i], 0) != '-') {
            for (int j = 0; ; ++j) {
              if (Strs.isdigit(Strs.at(argv[i], j))) {
                version = version * 10 + Strs.at(argv[i], j) - '0';
                ++digits;
              } else {
                if (digits == 1) version = version / 10 * 100 + version % 10;
                digits = 0;
                if (Strs.at(argv[i], j) == 0) break;
              }
            }
          }
          --i;
        }

        // Append default time
        if (version >= 19000000L && version <= 29991231L) version = version * 100 + 23;
        if (version >= 1900000000L && version <= 2999123123L) version = version * 100 + 59;
        if (version >= 190000000000L && version <= 299912312359L) version = version * 100 + 59;
        if (version > 9999999) {
          if (version < 19000101000000L || version > 29991231235959L) {
            Console.eprintf("Version date %1.0f must be 19000101000000 to 29991231235959\n",
                (double) version);
            throw new ExitException(1);
          }
          date = version;
        }
      } else {
        Console.print("Unknown option ignored: " + argv[i] + "\n");
        usage();
      }
    }

    if (configure != null) configure.accept(this);

    // Set threads
    if (threads < 1) threads = numberOfProcessors();

    // Test date
    if (date < 19000000000000L || date > 30000000000000L)
      throw ZpaqException.error("date is incorrect, use -until YYYY-MM-DD HH:MM:SS to set");

    // Adjust negative version
    if (version < 0) {
      Jidac jidac = new Jidac(io);
      jidac.copyOptions(this);
      jidac.version = DEFAULT_VERSION;
      jidac.readArchive(archive, null);
      version += jidac.ver.size() - 1;
      Console.emit(new InfoLine.VersionSelected(version,
          String.format(java.util.Locale.ROOT, "Version %1.0f\n", version + .0)));
    }

    // Execute command
    if (command == 'a' && files.size() > 0) return new Adder(this).add();
    else if (command == 'x') return new Extractor(this).extract();
    else if (command == 'l') return new Lister(this).list();
    else usage();
    return 0;
  }

  /** Copies the options (not the archive state) from another Jidac. */
  private void copyOptions(Jidac o) {
    command = o.command;
    archive = o.archive;
    files = new ArrayList<>(o.files);
    all = o.all;
    force = o.force;
    fragment = o.fragment;
    index = o.index;
    indexOut = o.indexOut;
    readIndex = o.readIndex;
    password = o.password;
    method = o.method;
    noattributes = o.noattributes;
    notfiles = new ArrayList<>(o.notfiles);
    nottype = o.nottype;
    onlyfiles = new ArrayList<>(o.onlyfiles);
    repack = o.repack;
    newPassword = o.newPassword;
    summary = o.summary;
    dotest = o.dotest;
    threads = o.threads;
    tofiles = new ArrayList<>(o.tofiles);
    date = o.date;
    version = o.version;
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
        Console.emit(new ErrorLine.ArchiveNotFound(arc, arc + " not found.\n"));
        if (errors != null) ++errors[0];
      }
      return 0;
    }
    Console.emit(new InfoLine.ArchiveReading(arc, version == DEFAULT_VERSION ? -1 : version,
        arc + (version == DEFAULT_VERSION ? ": "
            : String.format(java.util.Locale.ROOT, " -until %1.0f: ", version + 0.0))));
    Console.flush();

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
    final boolean renamed = command == 'l' || command == 'a';

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
                  if (jmp < 0) Console.emit(INCOMPLETE);
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
                    if (all != 0) {
                      String fn = Strs.itos(ver.size() - 1, all) + "/";
                      if (renamed) fn = rename(fn);
                      if (isselected(fn, false)) dtGet(fn).date = fdate;
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
                    Console.eprintf("Unordered fragment tables: expected >= %d found %1.0f\n",
                        ht.size(), (double) num);
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
                  while (s + 9 <= end) {
                    DT dtr = new DT();
                    dtr.date = btol(osd, s);  // date
                    s += 8;
                    if (dtr.date != 0) ++lv.updates;
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
                    final boolean issel = isselected(fn, renamed);
                    s += len + 1;  // skip filename
                    if (s > end) throw ZpaqException.error("filename too long");
                    if (dtr.date != 0) {
                      ++nfiles;
                      if (s + 4 > end) throw ZpaqException.error("missing attr");
                      final long na = btoi(osd, s) & 0xFFFFFFFFL;  // attr bytes
                      s += 4;
                      if (s + na > end || na > 65535) throw ZpaqException.error("attr too long");
                      for (int k = 0; k < na; ++k, ++s)  // read attr
                        if (k < 8) dtr.attr += (long) (osd[s] & 255) << (k * 8);
                      if (noattributes) dtr.attr = 0;
                      if (s + 4 > end) throw ZpaqException.error("missing ptr");
                      final long ni = btoi(osd, s) & 0xFFFFFFFFL;  // ptr list size
                      s += 4;
                      if (ni > (end - s) / 4L) throw ZpaqException.error("ptr list too long");
                      if (issel) dtr.ptrResize((int) ni);
                      for (int k = 0; k < ni; ++k) {  // read ptr
                        final int j = btoi(osd, s);
                        s += 4;
                        if (issel) dtr.setPtr(k, j);
                      }
                    }
                    if (issel) {
                      dtr.name = fn;
                      dt.put(fn, dtr);
                    }
                  }  // end while more files
                } else if (btype == 'd' && adia == Dialect.JPAQ && ver.size() > 1) {
                  sawD = true;  // size is added to csize and dcsize below
                } else {
                  Console.print("Skipping " + fname + " " + comment.s + "\n");
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
                if (isselected(fn, renamed)) {
                  DT dtr = dtGet(fn);
                  if (filename.s.length() > 0 || first) {
                    ++nfiles;
                    dtr.date = date;
                    dtr.attr = 0;
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
        Console.emit(new ErrorLine.BlockSkipped(blockOffset, what(e),
            String.format(java.util.Locale.ROOT, "Skipping block at %1.0f: %s\n",
                (double) blockOffset, what(e))));
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
        Console.emit(INCOMPLETE);
        ver.remove(ver.size() - 1);
        ht.truncate(lv.firstFragment);
        while (block.size() > 0 && block.get(block.size() - 1).start >= lv.firstFragment)
          block.remove(block.size() - 1);
      }
    }
    readDialect = adia;
    virtualEnd = blockOffset + (adia == Dialect.JPAQ || asIndex ? dhsize - dcsize : 0);
    Console.emit(new InfoLine.ArchiveRead(ver.size() - 1, nfiles, ht.size() - 1,
        blockOffset / 1000000.0,
        String.format(java.util.Locale.ROOT, "%d versions, %d files, %d fragments, %1.6f MB\n",
            ver.size() - 1, nfiles, ht.size() - 1, blockOffset / 1000000.0)));

    // Calculate file sizes
    for (DT p : dt.values()) {
      for (int i = 0; i < p.ptrSize(); ++i) {
        final int j = p.ptr(i);
        if (j > 0 && j < ht.size() && p.size >= 0) {
          if (ht.usize(j) >= 0) p.size += ht.usize(j);
          else p.size = -1;  // unknown size
        }
      }
    }
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
  static String what(Throwable e) {
    String m = e.getMessage();
    if (e instanceof OutOfMemoryError) return "std::bad_alloc";
    if (e instanceof ZpaqException && m != null) return m;
    return m != null ? m : e.toString();
  }

  /** dt[fn] of the C++ map: the existing entry or a new default one. */
  DT dtGet(String fn) {
    DT d = dt.get(fn);
    if (d == null) {
      d = new DT(fn);
      dt.put(fn, d);
    }
    return d;
  }

  /** Same for the external file map. */
  DT edtGet(String fn) {
    DT d = edt.get(fn);
    if (d == null) {
      d = new DT(fn);
      edt.put(fn, d);
    }
    return d;
  }

  // ---------------------------------------------------------------------------------------
  // Scanning external files

  /**
   * Inserts the external filename (UTF-8 byte string with "/") into edt if selected by files,
   * onlyfiles, and notfiles. If filename is a directory then also inserts its contents.
   */
  void scandir(String filename) {

    // Don't scan directories excluded by -not
    for (String nf : notfiles)
      if (ispath(nf, filename)) return;

    // Add regular files and directories
    while (filename.length() > 1 && filename.charAt(filename.length() - 1) == '/')
      filename = filename.substring(0, filename.length() - 1);  // remove trailing /
    Exception[] err = new Exception[1];
    Fs.Stat sb = Fs.lstat(filename, err);
    if (sb != null) {
      if (sb.regular()) addfile(filename, Dates.decimalTime(sb.mtime()), sb.size(), attrOf(sb));

      // Traverse directory
      if (sb.directory()) {
        addfile(filename.equals("/") ? "/" : filename + "/", Dates.decimalTime(sb.mtime()), 0,
            attrOf(sb));
        try (var ds = java.nio.file.Files.newDirectoryStream(Fs.path(filename))) {
          for (java.nio.file.Path p : ds) {
            String s = filename;
            if (!s.equals("/")) s += "/";
            s += Fs.bytes(p.getFileName().toString());
            scandir(s);
          }
        } catch (java.io.IOException | RuntimeException e) {
          Fs.printerr(filename, e);
        }
      }
    } else {
      Fs.printerr(filename, err[0]);
    }
  }

  private static long attrOf(Fs.Stat sb) {
    if (Fs.UNIX) return 'u' + ((sb.mode() & 0xFFFFFFFFL) << 8);
    return 'w' + (sb.mode() << 8);
  }

  /** Adds an external file and its date, size, and attributes to edt. */
  void addfile(String filename, long edate, long esize, long eattr) {
    if (!isselected(filename, false)) return;
    DT d = edtGet(filename);
    d.date = edate;
    d.size = esize;
    d.attr = noattributes ? 0 : eattr;
    d.data = 0;
  }

  // ---------------------------------------------------------------------------------------
  // Progress

  /** Prints percent done (td/ts) and estimated time remaining. */
  static void printProgress(long ts, long td, int sum) {
    if (td > ts) td = ts;
    if (td >= 1000000) {
      double eta = 0.001 * (mtime() - globalStart) * (ts - td) / (td + 1.0);
      final double percent = td * 100.0 / (ts + 0.5);
      Console.emit(new InfoLine.Progress(percent, (int) eta,
          String.format(java.util.Locale.ROOT, "%5.2f%% %d:%02d:%02d %s", percent,
              (int) (eta / 3600), (int) (eta / 60) % 60, (int) eta % 60, sum > 0 ? "\r" : "")));
      if (sum > 0) Console.flush();
    }
  }
}
