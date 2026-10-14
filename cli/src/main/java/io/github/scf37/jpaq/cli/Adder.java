package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.libzpaq.Crypto;
import io.github.scf37.jpaq.libzpaq.Libzpaq;
import io.github.scf37.jpaq.libzpaq.MemoryBuffer;
import io.github.scf37.jpaq.libzpaq.Sha1;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The "add" command: adds or deletes files from the archive. */
final class Adder {
  private final Jidac j;

  Adder(Jidac j) {
    this.j = j;
  }

  /** For writing to two archives at once. */
  private static final class WriterPair implements ZpaqWriter {
    OutputArchive a;
    OutputArchive b;

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
    if (out == null) return;
    MemoryBuffer is = new MemoryBuffer();
    Jidac.puti(is, cdata, 8);
    Libzpaq.compressBlock(is, out, "0",
        Strs.getBytes("jDC" + Strs.itos(date, 14) + "c" + Strs.itos(htsize & 0xFFFFFFFFL, 10)),
        Strs.getBytes("jDC\u0001"), true);
  }

  private static void compressJidacBlock(MemoryBuffer is, ZpaqWriter out, String method,
      String name) {
    Libzpaq.compressBlock(is, out, method, Strs.getBytes(name), Strs.getBytes("jDC\u0001"), true);
  }

  /** Sort by sortkey, then by full path. */
  private static final Comparator<DT> COMPARE_FILENAME = (a, b) -> {
    if (a.data != b.data) return Long.compare(a.data, b.data);
    return a.name.compareTo(b.name);
  };

  /** Adds or deletes files from the archive. Returns 1 if error else 0. */
  int add() {
    // Read archive or index into ht, dt, ver.
    int[] errors = {0};
    final boolean archiveExists = Fs.exists(Strs.subpart(j.archive, 1));
    String arcname = j.archive;  // input archive name
    if (j.index != null) arcname = j.index;
    long headerPos = 0;
    if (Fs.exists(Strs.subpart(arcname, 1))) headerPos = j.readArchive(arcname, errors);

    // Set arcname, offset, header_pos, and salt to open out archive
    arcname = j.archive;  // output file name
    long offset = 0;      // total size of existing parts
    byte[] salt = new byte[32];  // encryption salt
    if (j.password != null) Crypto.random(salt, 32);

    if (j.index != null) {  // Remote archive
      if (j.dcsize > 0) throw ZpaqException.error("index is a regular archive");
      if (j.version != Jidac.DEFAULT_VERSION) throw ZpaqException.error("cannot truncate with an index");
      offset = headerPos + j.dhsize;
      headerPos = 32 * (j.password != null && offset == 0 ? 1 : 0);
      arcname = Strs.subpart(j.archive, j.ver.size());
      if (Fs.exists(arcname)) {
        Console.eprint(arcname + ": archive exists\n");
        throw ZpaqException.error("archive exists");
      }
      if (j.password != null) {  // derive archive salt from index
        ArchiveFile fp = ArchiveFile.openRead(j.index, null);
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
          ArchiveFile fp = ArchiveFile.openRead(partname, null);
          if (fp == null) break;
          ++parts;
          offset += fp.length();
          fp.close();
        }
        headerPos = 32 * (j.password != null && parts == 0 ? 1 : 0);
        arcname = Strs.subpart(j.archive, parts + 1);
        if (Fs.exists(arcname)) throw ZpaqException.error("part exists");
      }

      // Get salt from first part if it exists
      if (j.password != null) {
        ArchiveFile fp = ArchiveFile.openRead(Strs.subpart(j.archive, 1), null);
        if (fp == null) {
          if (headerPos > 32) throw ZpaqException.error("archive first part not found");
          headerPos = 32;
        } else {
          if (fp.read(salt, 0, 32) != 32) throw ZpaqException.error("cannot read salt");
          fp.close();
        }
      }
    }
    if (Fs.exists(arcname)) Console.print("Updating ");
    else Console.print("Creating ");
    Console.print(arcname);
    Console.printf(" at offset %1.0f + %1.0f\n", (double) headerPos, (double) offset);

    // Set method
    String method = j.method;
    if (method.isEmpty()) method = "1";
    if (method.length() == 1) {  // set default blocksize
      if (method.charAt(0) >= '2' && method.charAt(0) <= '9') method += "6";
      else method += "4";
    }
    if ("0123456789xs".indexOf(method.charAt(0)) < 0)
      throw ZpaqException.error("-method must begin with 0..5, x, s");
    if (method.charAt(0) == 's' && j.index != null)
      throw ZpaqException.error("cannot index in streaming mode");

    // Set block and fragment sizes
    if (j.fragment < 0) j.fragment = 0;
    final int fragment = j.fragment;
    final int logBlocksize = 20 + Strs.atoi(method, 1);
    if (logBlocksize < 20 || logBlocksize > 31) throw ZpaqException.error("blocksize must be 0..11");
    final long blocksize = (1L << logBlocksize) - 4096;
    final long maxFragment = fragment > 19 || (8128L << fragment) > blocksize - 12
        ? blocksize - 12 : 8128L << fragment;
    final long minFragment = fragment > 25 || (64L << fragment) > maxFragment
        ? maxFragment : 64L << fragment;

    // Don't mix streaming and journaling
    for (Block b : j.block) {
      if (method.charAt(0) == 's') {
        if (b.usize >= 0) throw ZpaqException.error("cannot update journaling archive in streaming format");
      } else if (b.usize < 0) {
        throw ZpaqException.error("cannot update streaming archive in journaling format");
      }
    }

    // Make list of files to add or delete
    for (String f : j.files) j.scandir(f);

    // Sort the files to be added by filename extension and decreasing size
    List<DT> vf = new ArrayList<>();
    long totalSize = 0;  // size of all input
    long totalDone = 0;  // input deduped so far
    for (DT p : j.edt.values()) {
      DT a = j.dt.get(j.rename(p.name));
      if (a != null) a.data = 1;  // keep
      if (p.date != 0 && !p.name.isEmpty() && p.name.charAt(p.name.length() - 1) != '/'
          && (j.force || a == null || p.date != a.date || p.size != a.size)) {
        totalSize += p.size;

        // Key by first 5 bytes of filename extension, case insensitive
        int sp = 0;  // sortkey byte position
        for (int qi = 0; qi < p.name.length(); ++qi) {
          long c = p.name.charAt(qi) & 255;
          if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
          if (c == '/') {
            sp = 0;
            p.data = 0;
          } else if (c == '.') {
            sp = 8;
            p.data = 0;
          } else if (sp > 3) {
            p.data += c << (--sp * 8);
          }
        }

        // Key by descending size rounded to 16K
        long s = p.size >> 14;
        if (s >= (1 << 24)) s = (1 << 24) - 1;
        p.data += (1 << 24) - s - 1;
        vf.add(p);
      }
    }
    vf.sort(COMPARE_FILENAME);

    // Test for reliable access to archive
    if (archiveExists != Fs.exists(Strs.subpart(j.archive, 1)))
      throw ZpaqException.error("archive access is intermittent");

    // Open output
    OutputArchive out = new OutputArchive(arcname, j.password, salt, offset);
    out.seek(headerPos, InputArchive.Whence.SET);

    // Start compress and write jobs
    final int ntid = j.threads * 2 - 1;
    CompressJob job = new CompressJob(j.threads, ntid, out);
    Console.printf("Adding %1.6f MB in %d files -method %s -threads %d at %s.\n",
        totalSize / 1000000.0, vf.size(), method, j.threads, Dates.dateToString(j.date));

    // Append in streaming mode. Each file is a separate block. Large files are split into blocks
    // of size blocksize.
    long dedupesize = 0;  // input size after dedupe
    if (method.charAt(0) == 's') {
      MemoryBuffer sb = new MemoryBuffer((int) (blocksize + 4096 - 128));
      for (DT p : vf) {
        Jidac.printProgress(totalSize, totalDone, j.summary);
        if (j.summary <= 0) {
          Console.print("+ ");
          Console.print(p.name);
          Console.printf(" %1.0f\n", p.size + 0.0);
        }
        Exception[] err = new Exception[1];
        ArchiveFile in = ArchiveFile.openRead(p.name, err);
        if (in == null) {
          Fs.printerr(p.name, err[0]);
          totalSize -= p.size;
          ++errors[0];
          continue;
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
              filename = j.rename(p.name);
              comment = Strs.itos(p.date);
              if ((p.attr & 255) > 0) {
                comment += " ";
                comment += (char) (p.attr & 255);
                comment += Strs.itos(p.attr >> 8);
              }
            }
            totalDone += sb.size();
            job.write(sb, filename, method, comment);
          }
          if (r == 0) break;
        }
        in.close();
      }

      // Wait for jobs to finish
      job.write(sb, null, "", null);  // signal end of input
      job.join();

      // Done
      final long outsize = out.tell();
      Console.printf("%1.0f + (%1.0f -> %1.0f) = %1.0f\n", (double) headerPos, (double) totalSize,
          (double) (outsize - headerPos), (double) outsize);
      out.close();
      return errors[0] > 0 ? 1 : 0;
    }  // end if streaming

    // Adjust date to maintain sequential order
    if (j.ver.size() > 0 && j.ver.get(j.ver.size() - 1).lastdate >= j.date) {
      final long newdate = Dates.decimalTime(Dates.unixTime(j.ver.get(j.ver.size() - 1).lastdate) + 1);
      Console.eprintf("Warning: adjusting date from %s to %s\n", Dates.dateToString(j.date),
          Dates.dateToString(newdate));
      j.date = newdate;
    }
    final long date = j.date;

    // Build htinv for fast lookups of sha1 in ht
    HtIndex htinv = new HtIndex(j.ht, (long) j.ht.size() + (totalSize >> (10 + fragment)) + vf.size());
    final int htsize = j.ht.size();  // fragments at start of update

    // reserve space for the header block
    writeJidacHeader(out, date, -1, htsize);
    final long headerEnd = out.tell();

    // Compress until end of last file
    MemoryBuffer sb = new MemoryBuffer((int) (blocksize + 4096 - 128));  // block to compress
    int frags = 0;       // number of fragments in sb
    long redundancy = 0; // estimated bytes that can be compressed out of sb
    long text = 0;       // number of fragments containing text
    long exe = 0;        // number of fragments containing x86 (exe, dll)
    final int ON = 4;    // number of order-1 tables to save
    byte[] o1prev = new byte[ON * 256];  // last ON order 1 predictions
    byte[] fragbuf = new byte[(int) maxFragment];
    List<Integer> blocklist = new ArrayList<>();  // list of starting fragments

    // For each file to be added
    for (int fi = 0; fi <= vf.size(); ++fi) {
      ArchiveFile in = null;
      final int BUFSIZE = 1 << 16;  // input buffer
      byte[] buf = new byte[BUFSIZE];
      int bufptr = 0;
      int buflen = 0;  // read pointer and limit
      DT p = null;
      if (fi < vf.size()) {
        p = vf.get(fi);

        // Open input file
        bufptr = buflen = 0;
        Exception[] err = new Exception[1];
        in = ArchiveFile.openRead(p.name, err);
        if (in == null) {  // skip if not found
          p.date = 0;
          totalSize -= p.size;
          Fs.printerr(p.name, err[0]);
          ++errors[0];
          continue;
        }
        p.data = 1;  // add
      }

      // Read fragments
      long fsize = 0;  // file size after dedupe
      for (int fj = 0; true; ++fj) {
        int sz = 0;      // fragment size
        long hits = 0;   // correct prediction count
        int c = -1;      // current byte
        int htptr = 0;   // fragment index
        byte[] sha1result = new byte[20];  // fragment hash
        byte[] o1 = new byte[256];         // order 1 context -> predicted byte
        if (fi < vf.size()) {
          int c1 = 0;  // previous byte
          int h = 0;   // rolling hash for finding fragment boundaries
          Sha1 sha1 = new Sha1();
          while (true) {
            if (bufptr >= buflen) {
              bufptr = 0;
              buflen = in.read(buf, 0, BUFSIZE);
            }
            if (bufptr >= buflen) c = -1;
            else c = buf[bufptr++] & 255;
            if (c != -1) {
              if (c == (o1[c1] & 255)) {
                h = (h + c + 1) * 314159265;
                ++hits;
              } else {
                h = (h + c + 1) * 271828182;
              }
              o1[c1] = (byte) c;
              c1 = c;
              sha1.put(c);
              fragbuf[sz++] = (byte) c;
            }
            if (c == -1 || sz >= maxFragment
                || (fragment <= 22 && Integer.compareUnsigned(h, 1 << (22 - fragment)) < 0
                    && sz >= minFragment))
              break;
          }
          totalDone += sz;

          // Look for matching fragment
          sha1result = sha1.result();
          htptr = htinv.find(sha1result, 0);
        }  // end if fi<vf.size()

        if (htptr == 0) {  // not matched or last block

          // Analyze fragment for redundancy, x86, text. Test for text: letters, digits, '.' and
          // ',' followed by spaces and no invalid UTF-8. Test for exe: 139 (mov reg, r/m) in lots
          // of contexts. 4 tests for redundancy, measured as hits/sz. Take the highest of:
          //   1. Successful prediction count in o1.
          //   2. Non-uniform distribution in o1 (counted in o2).
          //   3. Fraction of zeros in o1 (bytes never seen).
          //   4. Fraction of matches between o1 and previous o1 (o1prev).
          int text1 = 0;
          int exe1 = 0;
          long h1 = sz;
          int[] o1ct = new int[256];  // counts of bytes in o1
          for (int i = 0; i < 256; ++i) {
            final int o1i = o1[i] & 255;
            if (o1ct[o1i] < 255) h1 -= ((long) sz * DT_TABLE[o1ct[o1i]++]) >> 15;
            if (o1i == ' ' && (Strs.isalnum(i) || i == '.' || i == ',')) ++text1;
            if (o1i != 0 && (i < 9 || i == 11 || i == 12 || (i >= 14 && i <= 31) || i >= 240))
              --text1;
            if (i >= 192 && i < 240 && o1i != 0 && (o1i < 128 || o1i >= 192)) --text1;
            if (o1i == 139) ++exe1;
          }
          text1 = text1 >= 3 ? 1 : 0;
          exe1 = exe1 >= 5 ? 1 : 0;
          if (sz > 0) h1 = h1 * h1 / sz;  // Test 2: near 0 if random.
          long h2 = h1 & 0xFFFFFFFFL;
          if (h2 > hits) hits = h2;
          h2 = (long) o1ct[0] * sz / 256;  // Test 3: bytes never seen or that predict 0.
          if (h2 > hits) hits = h2;
          h2 = 0;
          for (int i = 0; i < 256 * ON; ++i)  // Test 4: compare to previous o1.
            h2 += o1prev[i] == o1[i & 255] ? 1 : 0;
          h2 = h2 * sz / (256 * ON);
          if (h2 > hits) hits = h2;
          if (hits > sz) hits = sz;

          // Start a new block if the current block is almost full, or at the start of a file
          // that won't fit or doesn't share mutual information with the current block, or last
          // file.
          boolean newblock = false;
          if (frags > 0 && fj == 0 && fi < vf.size()) {
            final long esize = vf.get(fi).size;
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
          if (fi == vf.size()) newblock = true;  // last file?
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
            Jidac.printProgress(totalSize, totalDone, j.summary);
            if (j.summary <= 0)
              Console.printf("[%d..%d] %d -method %s\n", j.ht.size() - frags, j.ht.size() - 1,
                  sb.size(), m);
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
        if (fi < vf.size()) {
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
      if (fi < vf.size()) {
        dedupesize += fsize;
        Jidac.printProgress(totalSize, totalDone, j.summary);
        if (j.summary <= 0) {
          String newname = j.rename(p.name);
          DT a = j.dt.get(newname);
          if (a == null || a.date == 0) Console.print("+ ");
          else Console.print("# ");
          Console.print(p.name);
          if (!newname.equals(p.name)) {
            Console.print(" -> ");
            Console.print(newname);
          }
          Console.printf(" %1.0f", p.size + 0.0);
          if (fsize != p.size) Console.printf(" -> %1.0f", fsize + 0.0);
          Console.print("\n");
        }
        in.close();
      }
    }  // end for each file fi

    // Wait for jobs to finish
    job.write(sb, null, "", null);  // signal end of input
    job.join();

    // Open index
    salt[0] ^= '7' ^ 'z';
    OutputArchive outi = new OutputArchive(j.index != null ? j.index : "", j.password, salt, 0);
    WriterPair wp = new WriterPair();
    wp.a = out;
    if (j.index != null) wp.b = outi;
    writeJidacHeader(outi, date, 0, htsize);

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
            "jDC" + Strs.itos(date, 14) + "h" + Strs.itos(blocklist.get(i), 10));
        is.resize(0);
      }
    }

    // Delete from archive
    int dtcount = 0;  // index block header name
    int removed = 0;  // count
    for (DT p : j.dt.values()) {
      if (p.date != 0 && p.data == 0) {
        Jidac.puti(is, 0, 8);
        byte[] nb = Strs.getBytes(p.name);
        is.write(nb, 0, nb.length);
        is.put(0);
        if (j.summary <= 0) {
          Console.print("- ");
          Console.print(p.name);
          Console.print("\n");
        }
        ++removed;
        if (is.size() > 16000) {
          compressJidacBlock(is, wp, "1",
              "jDC" + Strs.itos(date) + "i" + Strs.itos(++dtcount, 10));
          is.resize(0);
        }
      }
    }

    // Append compressed index to archive
    int added = 0;  // count
    for (java.util.Iterator<DT> it = j.edt.values().iterator(); ; ) {
      DT p = it.hasNext() ? it.next() : null;
      if (p != null) {
        String filename = j.rename(p.name);
        DT a = j.dt.get(filename);
        if (p.date != 0 && (a == null  // new file
            || a.date != p.date  // date change
            || (a.attr != 0 && a.attr != p.attr)  // attr ch.
            || a.size != p.size  // size change
            || (p.data != 0 && !a.samePtr(p)))) {  // content
          if (j.summary <= 0 && p.data == 0) {  // not compressed?
            if (a == null || a.date == 0) Console.print("+ ");
            else Console.print("# ");
            Console.print(p.name);
            if (!filename.equals(p.name)) {
              Console.print(" -> ");
              Console.print(filename);
            }
            Console.print("\n");
          }
          ++added;
          Jidac.puti(is, p.date, 8);
          byte[] nb = Strs.getBytes(filename);
          is.write(nb, 0, nb.length);
          is.put(0);
          if ((p.attr & 255) == 'u') {  // unix attributes
            Jidac.puti(is, 3, 4);
            Jidac.puti(is, p.attr, 3);
          } else if ((p.attr & 255) == 'w') {  // windows attributes
            Jidac.puti(is, 5, 4);
            Jidac.puti(is, p.attr, 5);
          } else {
            Jidac.puti(is, 0, 4);  // no attributes
          }
          if (a == null || p.data != 0) a = p;  // use new frag pointers
          Jidac.puti(is, a.ptrSize(), 4);  // list of frag pointers
          for (int i = 0; i < a.ptrSize(); ++i) Jidac.puti(is, a.ptr(i), 4);
        }
      }
      if (is.size() > 16000 || (is.size() > 0 && p == null)) {
        compressJidacBlock(is, wp, "1", "jDC" + Strs.itos(date) + "i" + Strs.itos(++dtcount, 10));
        is.resize(0);
      }
      if (p == null) break;
    }
    Console.printf("%d +added, %d -removed.\n", added, removed);

    // Back up and write the header
    outi.close();
    long archiveEnd = out.tell();
    out.seek(headerPos, InputArchive.Whence.SET);
    writeJidacHeader(out, date, cdatasize, htsize);
    out.seek(0, InputArchive.Whence.END);
    final long archiveSize = out.tell();
    out.close();

    // Truncate empty update from archive (if not indexed)
    if (j.index == null) {
      if (added + removed == 0 && archiveEnd - headerPos == 104)  // no update
        archiveEnd = headerPos;
      if (archiveEnd < archiveSize) {
        if (archiveEnd > 0) {
          Console.printf("truncating archive from %1.0f to %1.0f\n", (double) archiveSize,
              (double) archiveEnd);
          if (!truncate(arcname, archiveEnd)) Fs.printerr(j.archive, "truncate failed");
        } else if (archiveEnd == 0) {
          if (Fs.deleteFile(arcname)) {
            Console.print("deleted ");
            Console.print(arcname);
            Console.print("\n");
          }
        }
      }
    }
    Console.flush();
    Console.eprintf("\n%1.6f + (%1.6f -> %1.6f -> %1.6f) = %1.6f MB\n", headerPos / 1000000.0,
        totalSize / 1000000.0, dedupesize / 1000000.0, (archiveEnd - headerPos) / 1000000.0,
        archiveEnd / 1000000.0);
    return errors[0] > 0 ? 1 : 0;
  }

  /** Truncates filename to length. Returns true on success. */
  private static boolean truncate(String filename, long length) {
    ArchiveFile f = ArchiveFile.openUpdate(filename, null);
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
