package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.ErrorLine;
import io.github.scf37.jpaq.InfoLine;
import io.github.scf37.jpaq.libzpaq.Crypto;
import io.github.scf37.jpaq.libzpaq.Decompresser;
import io.github.scf37.jpaq.libzpaq.MemoryBuffer;
import io.github.scf37.jpaq.libzpaq.Sha1;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqReader;
import io.github.scf37.jpaq.libzpaq.ZpaqWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

/** The "extract" command. */
final class Extractor {
  private final Jidac j;

  Extractor(Jidac j) {
    this.j = j;
  }

  /**
   * An extract job is a set of blocks with at least one file pointing to them. Blocks are
   * extracted in separate threads, set READY -&gt; WORKING. A block is extracted to memory up to
   * the last fragment that has a file pointing to it. Then the checksums are verified. Then for
   * each file pointing to the block, each of the fragments that it points to within the block are
   * written in order.
   */
  private static final class ExtractJob {
    final ReentrantLock mutex = new ReentrantLock();       // protects state
    final ReentrantLock writeMutex = new ReentrantLock();  // protects writing to disk
    int job;                 // number of jobs started
    ArchiveFile outf;        // currently open output file
    DT lastdt;               // currently open output file name
    double maxMemory;        // largest memory used by any block (test mode)
    long totalSize;          // bytes to extract
    long totalDone;          // bytes extracted so far
    BlockingQueue<Fed> fed;  // compressed blocks read in order (storage without random access)
  }

  /** A compressed block read from the archive, ready to be decompressed. */
  private record Fed(Block block, byte[] data) {}

  private static final Fed END = new Fed(null, null);

  /** Streaming output destination. */
  private static final class OutputFile implements ZpaqWriter {
    private final ArchiveFile f;

    OutputFile(ArchiveFile f) {
      this.f = f;
    }

    @Override
    public void put(int c) {
      if (f != null) f.write(new byte[] {(byte) c}, 0, 1);
    }

    @Override
    public void write(byte[] buf, int off, int n) {
      if (f != null) f.write(buf, off, n);
    }
  }

  /** Copies at most n bytes from in to out. Returns how many were copied. */
  static long copy(InputArchive in, OutputArchive out, long n) {
    final int bufsize = 4096;
    long result = 0;
    byte[] buf = new byte[bufsize];
    while (n > 0) {
      int nc = n > bufsize ? bufsize : (int) n;
      int nr = in.read(buf, 0, nc);
      if (nr < 1) break;
      out.write(buf, 0, nr);
      result += nr;
      n -= nr;
    }
    return result;
  }

  static long copy(InputArchive in, OutputArchive out) {
    return copy(in, out, Long.MAX_VALUE);
  }

  /** Closes fp if open and sets the date and attributes unless 0. */
  void closeFile(String filename, long date, long attr, ArchiveFile fp) {
    if (fp != null) fp.close();
    j.ctx.fs.setDateAttr(filename, date, attr);
  }

  /**
   * Returns true if the internal file p and external file contents are equal or neither exists. If
   * filename is null then returns true if it is possible to compare.
   */
  boolean equal(DT p, String filename) {
    // test if all fragment sizes and hashes exist
    if (filename == null) {
      for (int i = 0; i < p.ptrSize(); ++i) {
        int k = p.ptr(i);
        if (k < 1 || k >= j.ht.size() || j.ht.usize(k) < 0 || j.ht.isZero(k)) return false;
      }
      return true;
    }

    // internal or neither file exists
    if (p.date() == 0) return !j.ctx.fs.exists(filename);

    // directories always match
    if (!p.name().isEmpty() && p.name().charAt(p.name().length() - 1) == '/') return j.ctx.fs.exists(filename);

    // compare sizes
    ArchiveFile in = ArchiveFile.openRead(j.ctx.fs, filename, null);
    if (in == null) return false;
    try {
      if (in.length() != p.size()) return false;

      // compare hashes
      final int bufsize = 4096;
      byte[] buf = new byte[bufsize];
      Sha1 sha1 = new Sha1();
      byte[] want = new byte[20];
      for (int i = 0; i < p.ptrSize(); ++i) {
        int f = p.ptr(i);
        if (f < 1 || f >= j.ht.size() || j.ht.usize(f) < 0) return false;
        for (int k = 0; k < j.ht.usize(f); ) {
          int n = j.ht.usize(f) - k;
          if (n > bufsize) n = bufsize;
          int r = in.read(buf, 0, n);
          if (r != n) return false;
          sha1.write(buf, 0, n);
          k += n;
        }
        j.ht.copySha1(f, want, 0);
        if (!Arrays.equals(sha1.result(), want)) return false;
      }
      return in.read(buf, 0, bufsize) == 0;
    } finally {
      in.close();
    }
  }

  /**
   * Extracts files from the archive. If force is true then overwrites existing files and sets the
   * dates and attributes of existing directories. Otherwise creates only new files and
   * directories. Returns 1 if error else 0.
   */
  int extract() {

    // Encrypt or decrypt whole archive
    if (j.repack != null && j.all != 0) {
      if (j.files.size() > 0 || j.tofiles.size() > 0 || j.onlyfiles.size() > 0 || j.noattributes
          || j.version != Jidac.DEFAULT_VERSION || !j.method.isEmpty())
        throw ZpaqException.error("-repack -all does not allow partial copy");
      InputArchive in = j.openArchive();
      j.io.checkWritable();
      if (j.force) j.io.delete(j.repack);
      if (j.io.exists(j.repack)) throw ZpaqException.error("output file exists");

      // Get key and salt
      byte[] salt = new byte[32];
      if (j.newPassword != null) Crypto.random(salt, 32);

      // Copy
      OutputArchive out = new OutputArchive(j.io, j.repack, j.newPassword, salt, 0);
      copy(in, out);
      j.ctx.print(j.archive + String.format(java.util.Locale.ROOT, " %1.0f ", in.tell() + .0)
          + j.repack + String.format(java.util.Locale.ROOT, " -> %1.0f\n", out.tell() + .0));
      out.close();
      in.close();
      return 0;
    }

    // Read archive
    final long sz = j.readArchive(j.fromIndex() ? j.readIndex : j.archive, null);
    if (sz < 1) throw ZpaqException.error("archive not found");

    // test blocks
    final List<Block> block = j.block;
    for (int i = 0; i < block.size(); ++i) {
      Block bi = block.get(i);
      if (bi.bsize < 0) throw ZpaqException.error("negative block size");
      if (bi.start < 1) throw ZpaqException.error("block starts at fragment 0");
      if (Integer.compareUnsigned(bi.start, j.ht.size()) >= 0)
        throw ZpaqException.error("block start too high");
      if (i > 0 && Integer.compareUnsigned(bi.start, block.get(i - 1).start) < 0)
        throw ZpaqException.error("unordered frags");
      if (i > 0 && bi.start == block.get(i - 1).start) throw ZpaqException.error("empty block");
      if (i > 0 && bi.offset < block.get(i - 1).offset + block.get(i - 1).bsize)
        throw ZpaqException.error("unordered blocks");
      if (i > 0 && block.get(i - 1).offset + block.get(i - 1).bsize > bi.offset)
        throw ZpaqException.error("overlapping blocks");
    }

    // Create index instead of extract files
    if (j.index != null) return createIndex(sz);

    // Label files to extract with data=0. Skip existing output files. If force then skip only if
    // equal and set date and attributes.
    j.dt.enableData();  // before the threads start: no allocation while they run
    ExtractJob job = new ExtractJob();
    int totalFiles = 0;
    int skipped = 0;
    for (DT p : j.dt.values()) {
      p.data(-1);  // skip
      if (p.date() != 0 && !p.name().isEmpty()) {
        final String fn = j.rename(p.name());
        final boolean isdir = p.name().charAt(p.name().length() - 1) == '/';
        if (j.repack == null && !j.dotest && j.force && !isdir && equal(p, fn)) {
          if (j.summary <= 0) {  // identical
            j.ctx.emit(new InfoLine.FileSkipped(fn, InfoLine.FileSkipped.Reason.IDENTICAL,
                "= " + fn + "\n"));
          }
          j.ctx.fs.setDateAttr(fn, p.date(), p.attr());
          ++skipped;
        } else if (j.repack == null && !j.dotest && !j.force && j.ctx.fs.exists(fn)) {  // exists, skip
          if (j.summary <= 0) {
            j.ctx.emit(new InfoLine.FileSkipped(fn, InfoLine.FileSkipped.Reason.EXISTS,
                "? " + fn + "\n"));
          }
          ++skipped;
        } else if (isdir) {  // update directories later
          p.data(0);
        } else if (block.size() > 0) {  // files to decompress
          p.data(0);
          int lo = 0;
          int hi = block.size() - 1;  // block indexes for binary search
          for (int i = 0; p.data() >= 0 && i < p.ptrSize(); ++i) {
            final int jj = p.ptr(i);  // fragment index
            if (jj == 0 || Integer.compareUnsigned(jj, j.ht.size()) >= 0 || j.ht.usize(jj) < -1) {
              j.ctx.emit(new ErrorLine.BadFragments(p.name(),
                  p.name() + ": bad frag IDs, skipping...\n"));
              p.data(-1);  // skip
              continue;
            }
            if (lo != hi || lo >= block.size() || Integer.compareUnsigned(jj, block.get(lo).start) < 0
                || (lo + 1 < block.size()
                    && Integer.compareUnsigned(jj, block.get(lo + 1).start) >= 0)) {
              lo = 0;  // find block with fragment j by binary search
              hi = block.size() - 1;
              while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (Integer.compareUnsigned(jj, block.get(mid).start) < 0) hi = mid - 1;
                else lo = mid;
              }
            }
            int c = jj - block.get(lo).start + 1;
            Block bl = block.get(lo);
            if (bl.size < c) bl.size = c;
            if (bl.files.size() == 0 || bl.files.get(bl.files.size() - 1) != p.id) bl.files.add(p.id);
          }
          ++totalFiles;
          job.totalSize += p.size();
        }
      }  // end if selected
    }  // end for
    if (!j.force && skipped > 0)
      j.ctx.emit(new InfoLine.SkippedSummary(skipped, false,
          skipped + " ?existing files skipped (-force overwrites).\n"));
    if (j.force && skipped > 0)
      j.ctx.emit(new InfoLine.SkippedSummary(skipped, true,
          skipped + " =identical files skipped.\n"));

    // Repack to new archive
    if (j.repack != null) return repack(sz);

    // Decompress archive in parallel
    j.ctx.emit(new InfoLine.ExtractPlan(job.totalSize / 1000000.0, totalFiles, j.threads,
        String.format(java.util.Locale.ROOT, "Extracting %1.6f MB in %d files -threads %d\n",
            job.totalSize / 1000000.0, totalFiles, j.threads)));
    List<Thread> tid = new ArrayList<>();
    Thread feeder = null;
    if (!j.io.random) {
      // The archive can only be read front to back: one thread reads the blocks to decompress in
      // order and hands them to the workers
      job.fed = new ArrayBlockingQueue<>(Math.max(j.threads, 1));
      feeder = new Thread(() -> feedBlocks(job), "read-blocks");
      feeder.setDaemon(true);
      feeder.start();
    }
    for (int i = 0; i < j.threads; i++) {
      Thread t = new Thread(() -> decompressThread(job), "decompress-" + i);
      t.setDaemon(true);
      tid.add(t);
      t.start();
    }

    // Extract streaming files
    int segments = 0;  // count
    InputArchive in = j.openArchive();
    if (in.isOpen()) {
      ArchiveFile outf = null;
      DT dtptr = null;
      for (int i = 0; i < block.size(); ++i) {
        if (block.get(i).usize < 0 && block.get(i).size > 0) {
          Block b = block.get(i);
          try {
            in.seek(b.offset, InputArchive.Whence.SET);
            Decompresser d = new Decompresser();
            d.setInput(in);
            if (!d.findBlock()) throw ZpaqException.error("block not found");
            StringBuilder filename = new StringBuilder();
            ZpaqWriter fnw = c -> filename.append((char) (c & 255));
            for (int jj = 0; jj < b.size; ++jj) {
              if (!d.findFilename(fnw)) throw ZpaqException.error("segment not found");
              d.readComment();

              // Start of new output file
              if (filename.length() > 0 || segments == 0) {
                int k;
                for (k = 0; k < b.files.size(); ++k) {  // find in dt
                  DT f = j.dt.at(b.files.get(k));
                  if (f.ptrSize() > 0 && f.ptr(0) == b.start + jj && f.date() > 0 && f.data() == 0)
                    break;
                }
                if (k < b.files.size()) {  // found new file
                  if (outf != null) outf.close();
                  outf = null;
                  dtptr = j.dt.at(b.files.get(k));
                  String outname = j.rename(dtptr.name());
                  job.mutex.lock();
                  try {
                    if (j.summary <= 0) {
                      j.ctx.emit(new InfoLine.FileExtracted(outname, "> " + outname + "\n"));
                    }
                    if (!j.dotest) {
                      j.ctx.fs.makepath(outname);
                      Exception[] err = new Exception[1];
                      outf = ArchiveFile.create(j.ctx.fs, outname, err);
                      if (outf == null) j.ctx.fs.printerr(outname, err[0]);
                    }
                  } finally {
                    job.mutex.unlock();
                  }
                } else {  // end of file
                  if (outf != null) outf.close();
                  outf = null;
                  dtptr = null;
                }
              }

              // Decompress segment
              Sha1 sha1 = new Sha1();
              d.setSha1(sha1);
              OutputFile o = new OutputFile(outf);
              d.setOutput(o);
              d.decompress();

              // Verify checksum
              byte[] sha1result = new byte[21];
              d.readSegmentEnd(sha1result);
              if (sha1result[0] == 1) {
                if (!Arrays.equals(sha1result, 1, 21, sha1.result(), 0, 20))
                  throw ZpaqException.error("checksum failed");
              } else if (sha1result[0] != 0) {
                throw ZpaqException.error("unknown checksum type");
              }
              ++b.extracted;
              if (dtptr != null) dtptr.data(dtptr.data() + 1);
              filename.setLength(0);
              ++segments;
            }
          } catch (RuntimeException | OutOfMemoryError e) {
            job.mutex.lock();
            try {
              j.ctx.print("Skipping block: " + Jidac.what(e) + "\n");
            } finally {
              job.mutex.unlock();
            }
          }
        }
      }
      if (outf != null) outf.close();
    }
    if (segments > 0) j.ctx.printf("%d streaming segments extracted\n", segments);

    // Wait for threads to finish
    for (Thread t : tid) joinThread(t);
    if (feeder != null) joinThread(feeder);

    // Create empty directories and set file dates and attributes
    if (!j.dotest) {
      for (DT p : j.dt.descendingValues()) {
        if (p.data() >= 0 && p.date() != 0 && !p.name().isEmpty()) {
          String s = j.rename(p.name());
          if (p.name().charAt(p.name().length() - 1) == '/') j.ctx.fs.makepath(s, p.date(), p.attr());
          else if ((p.attr() & 0x1ff) == 'w' + 256)  // read-only?
            j.ctx.fs.setDateAttr(s, 0, p.attr());
        }
      }
    }

    // Report failed extractions
    int extracted = 0;
    int errors = 0;
    for (DT p : j.dt.values()) {
      String fn = j.rename(p.name());
      if (p.data() >= 0 && p.date() != 0 && !fn.isEmpty() && fn.charAt(fn.length() - 1) != '/') {
        ++extracted;
        if (p.ptrSize() != (int) p.data()) {
          if (++errors == 1)
            j.ctx.eprint("\nFailed (extracted/total fragments, file):\n");
          j.ctx.emit(new ErrorLine.ExtractFailed(fn, (int) p.data(), p.ptrSize(),
              (int) p.data() + "/" + p.ptrSize() + " " + fn + "\n"));
        }
      }
    }
    if (errors > 0) {
      j.ctx.emit(new ErrorLine.ExtractFailures(extracted, errors,
          String.format(java.util.Locale.ROOT,
              "\nExtracted %d of %d files OK (%d errors) using %1.3f MB x %d threads\n",
              extracted - errors, extracted, errors, job.maxMemory / 1000000, tid.size())));
    }
    in.close();
    return errors > 0 ? 1 : 0;
  }

  private static void joinThread(Thread t) {
    boolean interrupted = false;
    while (true) {
      try {
        t.join();
        break;
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  // -------------------------------------------------------------------------------------------

  /** Creates an index of the archive (extract -index). */
  private int createIndex(long sz) {
    if (j.ver.size() < 2) throw ZpaqException.error("no journaling data");
    if (j.io.probe) {  // without PROBE an existing index is simply replaced
      if (j.force) j.io.delete(j.index);
      if (j.io.exists(j.index)) throw ZpaqException.error("index file exists");
    }

    // Get salt
    byte[] salt = new byte[32];
    if (j.ver.get(1).offset == 32) {  // encrypted?
      ArchiveIo.In fp = j.io.openRead(Strs.subpart(j.archive, 1), null);
      if (fp == null) throw ZpaqException.error("cannot read part 1");
      if (fp.read(salt, 0, 32) != 32) throw ZpaqException.error("cannot read salt");
      salt[0] ^= '7' ^ 'z';  // for index
      fp.close();
    }
    InputArchive in = j.openArchive();
    OutputArchive out = new OutputArchive(j.io, j.index, j.password, salt, 0);
    for (int i = 1; i < j.ver.size(); ++i) {
      Ver v = j.ver.get(i);
      if (in.tell() != v.offset) throw ZpaqException.error("I'm lost");

      // Read C block. Assume uncompressed and hash is present
      byte[] hdr = new byte[256];
      int hsize = (int) (v.dataOffset - v.offset);
      if (hsize < 70 || hsize > 255) throw ZpaqException.error("bad C block size");
      if (in.read(hdr, 0, hsize) != hsize) throw ZpaqException.error("EOF in header");
      if (hdr[hsize - 36] != 9  // size of uncompressed block low byte
          || (hdr[hsize - 22] & 255) != 253  // start of SHA1 marker
          || (hdr[hsize - 1] & 255) != 255) {  // end of block marker
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < hsize; ++k) sb.append(hdr[k] & 255).append(k % 10 == 9 ? '\n' : ' ');
        j.ctx.print(sb.toString());
        j.ctx.printf("at %1.0f\n", v.offset + .0);
        throw ZpaqException.error("C block in weird format");
      }
      final byte[] tail = {
          0, 0, 0, 0, 0, 0, 0, 0,  // csize = 0
          0, 0, 0, 0,  // compressed data terminator
          (byte) 0xfd,  // start of hash marker
          0x05, (byte) 0xfe, 0x40, 0x57, 0x53, 0x16, 0x6f, 0x12, 0x55, 0x59, (byte) 0xe7,
          (byte) 0xc9, (byte) 0xac, 0x55, (byte) 0x86, 0x54, (byte) 0xf1, 0x07, (byte) 0xc7,
          (byte) 0xe9,  // SHA-1('0'*8)
          (byte) 0xff};  // EOB
      System.arraycopy(tail, 0, hdr, hsize - 34, 34);
      out.write(hdr, 0, hsize);
      in.seek(v.csize, InputArchive.Whence.CUR);  // skip D blocks
      long end = sz;
      if (i + 1 < j.ver.size()) end = j.ver.get(i + 1).offset;
      long n = end - in.tell();
      if (copy(in, out, n) != n) throw ZpaqException.error("EOF");  // copy H and I blocks
    }
    j.ctx.emit(new InfoLine.IndexWritten(j.index, out.tell(),
        j.index + String.format(java.util.Locale.ROOT, " -> %1.0f\n", out.tell() + .0)));
    out.close();
    in.close();
    return 0;
  }

  /** Repacks the selected files to a new archive (extract -repack). */
  private int repack(long sz) {
    final List<Block> block = j.block;

    // Get total D block size
    if (j.ver.size() < 2) throw ZpaqException.error("cannot repack streaming archive");
    if (j.readDialect == io.github.scf37.jpaq.libzpaq.Dialect.JPAQ)
      throw ZpaqException.error("cannot repack a jpaq sequential archive");
    j.io.checkWritable();
    long csize = 0;  // total compressed size of D blocks
    for (Block b : block) {
      if (b.bsize < 1) throw ZpaqException.error("empty block");
      if (b.size > 0) csize += b.bsize;
    }

    // Open input
    InputArchive in = j.openArchive();

    // Open output
    if (!j.force && j.io.exists(j.repack)) throw ZpaqException.error("repack output exists");
    j.io.delete(j.repack);
    byte[] salt = new byte[32];
    if (j.newPassword != null) Crypto.random(salt, 32);
    OutputArchive out = new OutputArchive(j.io, j.repack, j.newPassword, salt, 0);
    long cstart = out.tell();

    // Write C block using first version date
    Adder.writeJidacHeader(out, j.ver.get(1).date, -1, 1);
    long dstart = out.tell();

    // Copy only referenced D blocks. If method then recompress.
    for (Block b : block) {
      if (b.size > 0) {
        in.seek(b.offset, InputArchive.Whence.SET);
        copy(in, out, b.bsize);
      }
    }
    j.ctx.printf("Data %1.0f -> ", csize + .0);
    csize = out.tell() - dstart;
    j.ctx.printf("%1.0f\n", csize + .0);

    // Re-create referenced H blocks using latest date
    final long lastDate = j.ver.get(j.ver.size() - 1).date;
    for (Block b : block) {
      if (b.size > 0) {
        MemoryBuffer is = new MemoryBuffer();
        Jidac.puti(is, b.bsize, 4);
        byte[] hash = new byte[20];
        for (int jj = 0; jj < b.frags; ++jj) {
          final int k = b.start + jj;
          if (k < 1 || k >= j.ht.size()) throw ZpaqException.error("frag out of range");
          j.ht.copySha1(k, hash, 0);
          is.write(hash, 0, 20);
          Jidac.puti(is, j.ht.usize(k), 4);
        }
        io.github.scf37.jpaq.libzpaq.Libzpaq.compressBlock(is, out, "0",
            Strs.getBytes("jDC" + Strs.itos(lastDate, 14) + "h" + Strs.itos(b.start, 10)),
            Strs.getBytes("jDC\u0001"), true);
      }
    }

    // Append I blocks of selected files
    int dtcount = 0;
    MemoryBuffer is = new MemoryBuffer();
    for (java.util.Iterator<DT> it = j.dt.values().iterator(); ; ) {
      DT p = it.hasNext() ? it.next() : null;
      if (p != null && p.date() > 0 && p.data() >= 0) {
        String filename = j.rename(p.name());
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
        Jidac.puti(is, p.ptrSize(), 4);  // list of frag pointers
        for (int i = 0; i < p.ptrSize(); ++i) Jidac.puti(is, p.ptr(i), 4);
      }
      if (is.size() > 16000 || (is.size() > 0 && p == null)) {
        io.github.scf37.jpaq.libzpaq.Libzpaq.compressBlock(is, out, "1",
            Strs.getBytes("jDC" + Strs.itos(lastDate) + "i" + Strs.itos(++dtcount, 10)),
            Strs.getBytes("jDC\u0001"), true);
        is.resize(0);
      }
      if (p == null) break;
    }

    // Summarize result
    j.ctx.print(j.archive + String.format(java.util.Locale.ROOT, " %1.0f -> ", sz + .0)
        + j.repack + String.format(java.util.Locale.ROOT, " %1.0f\n", out.tell() + .0));

    // Rewrite C block
    out.seek(cstart, InputArchive.Whence.SET);
    Adder.writeJidacHeader(out, j.ver.get(1).date, csize, 1);
    out.close();
    in.close();
    return 0;
  }

  // -------------------------------------------------------------------------------------------

  /** Decompresses blocks in a job until none are READY. */
  private void decompressThread(ExtractJob job) {
    try {
      decompressBlocks(job);
    } catch (RuntimeException | OutOfMemoryError e) {
      job.mutex.lock();
      try {
        j.ctx.eprint("decompress thread: " + Jidac.what(e) + "\n");
      } finally {
        job.mutex.unlock();
      }
    }
  }

  /** Reads the compressed blocks that are needed, in archive order, into job.fed. */
  private void feedBlocks(ExtractJob job) {
    try (InputArchive fin = j.openArchive()) {
      for (Block b : j.block) {
        if (!(b.size > 0 && b.usize >= 0)) continue;
        job.mutex.lock();
        try {
          if (b.state != Block.State.READY) continue;
          b.state = Block.State.WORKING;
        } finally {
          job.mutex.unlock();
        }
        try {
          if (b.bsize > Integer.MAX_VALUE - 16) throw ZpaqException.error("block too big");
          fin.seek(b.offset, InputArchive.Whence.SET);
          byte[] data = new byte[(int) b.bsize];
          int n = 0;
          while (n < data.length) {
            int r = fin.read(data, n, data.length - n);
            if (r <= 0) break;
            n += r;
          }
          if (n < data.length) data = java.util.Arrays.copyOf(data, n);
          putFed(job, new Fed(b, data));
        } catch (RuntimeException | OutOfMemoryError ex) {
          job.mutex.lock();
          try {
            j.ctx.eprintf("read block at %1.0f: %s\n", b.offset + 0.0, Jidac.what(ex));
          } finally {
            job.mutex.unlock();
          }
        }
      }
    } catch (RuntimeException ex) {
      job.mutex.lock();
      try {
        j.ctx.eprint("read blocks: " + Jidac.what(ex) + "\n");
      } finally {
        job.mutex.unlock();
      }
    } finally {
      for (int i = 0; i < Math.max(j.threads, 1); i++) putFed(job, END);
    }
  }

  private static void putFed(ExtractJob job, Fed f) {
    boolean interrupted = false;
    while (true) {
      try {
        job.fed.put(f);
        break;
      } catch (InterruptedException ex) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static Fed takeFed(ExtractJob job) {
    boolean interrupted = false;
    try {
      while (true) {
        try {
          return job.fed.take();
        } catch (InterruptedException ex) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private void decompressBlocks(ExtractJob job) {
    final List<Block> block = j.block;
    int jobNumber;

    // Get job number
    job.mutex.lock();
    try {
      jobNumber = ++job.job;
    } finally {
      job.mutex.unlock();
    }

    // Open archive for reading
    InputArchive in = job.fed != null ? null : j.openArchive();
    if (in != null && !in.isOpen()) return;
    MemoryBuffer out = new MemoryBuffer();

    // Look for next READY job.
    int next = 0;  // current job
    while (true) {
      Block b = null;
      ZpaqReader src = in;
      if (job.fed != null) {
        Fed f = takeFed(job);
        if (f == END) return;
        b = f.block();
        src = ZpaqReader.of(f.data());
      } else {
      job.mutex.lock();
      try {
        for (int i = 0; i <= block.size(); ++i) {
          int k = i + next;
          if (k >= block.size()) k -= block.size();
          if (i == block.size()) return;  // no more jobs?
          Block bb = block.get(k);
          if (bb.state == Block.State.READY && bb.size > 0 && bb.usize >= 0) {
            bb.state = Block.State.WORKING;
            next = k;
            b = bb;
            break;
          }
        }
      } finally {
        job.mutex.unlock();
      }
      }

      // Get uncompressed size of block
      long outputSize = 0;  // minimum size to decompress
      for (int jj = 0; jj < b.size; ++jj) outputSize += j.ht.usize(b.start + jj);

      // Decompress
      double[] mem = {0};  // how much memory used to decompress
      try {
        if (in != null) in.seek(b.offset, InputArchive.Whence.SET);
        Decompresser d = new Decompresser();
        d.setInput(src);
        out.resize(0);
        out.setLimit(b.usize);
        d.setOutput(out);
        if (!d.findBlock(mem)) throw ZpaqException.error("archive block not found");
        job.mutex.lock();
        try {
          if (mem[0] > job.maxMemory) job.maxMemory = mem[0];
        } finally {
          job.mutex.unlock();
        }
        while (d.findFilename()) {
          d.readComment();
          while (out.size() < outputSize && d.decompress(1 << 14)) { }
          job.mutex.lock();
          try {
            j.ctx.printProgress(job.totalSize, job.totalDone, j.summary);
            if (j.summary <= 0)
              j.ctx.emit(new InfoLine.BlockExtracted(b.start, b.start + b.size - 1, out.size(),
                  String.format(java.util.Locale.ROOT, "[%d..%d] -> %1.0f\n", b.start,
                      b.start + b.size - 1, out.size() + 0.0)));
          } finally {
            job.mutex.unlock();
          }
          if (out.size() >= outputSize) break;
          d.readSegmentEnd();
        }
        if (out.size() < outputSize) {
          job.mutex.lock();
          try {
            j.ctx.eprintf("output [%d..%d] %d of %d bytes\n", b.start, b.start + b.size - 1,
                out.size(), outputSize);
          } finally {
            job.mutex.unlock();
          }
          throw ZpaqException.error("unexpected end of compressed data");
        }

        // Verify fragment checksums if present
        long q = 0;  // fragment start
        Sha1 sha1 = new Sha1();
        byte[] want = new byte[20];
        for (int jj = b.start; jj < b.start + b.size; ++jj) {
          if (q + j.ht.usize(jj) > out.size()) throw ZpaqException.error("Incomplete decompression");
          sha1.write(out.data(), (int) q, j.ht.usize(jj));
          byte[] sha1result = sha1.result();
          q += j.ht.usize(jj);
          j.ht.copySha1(jj, want, 0);
          if (!Arrays.equals(sha1result, want)) {
            job.mutex.lock();
            try {
              j.ctx.eprintf("Job %d: fragment %d size %d checksum failed\n", jobNumber, jj,
                  j.ht.usize(jj));
            } finally {
              job.mutex.unlock();
            }
            throw ZpaqException.error("bad checksum");
          }
          ++b.extracted;
        }
      } catch (OutOfMemoryError e) {
        // If out of memory, let another thread try
        job.mutex.lock();
        try {
          j.ctx.eprintf("Job %d killed: %s\n", jobNumber, Jidac.what(e));
          b.state = Block.State.READY;
          b.extracted = 0;
          out.resize(0);
        } finally {
          job.mutex.unlock();
        }
        return;
      } catch (RuntimeException e) {
        // Other errors: assume bad input
        job.mutex.lock();
        try {
          j.ctx.eprintf("Job %d: skipping [%d..%d] at %1.0f: %s\n", jobNumber,
              b.start + b.extracted, b.start + b.size - 1, b.offset + 0.0, Jidac.what(e));
        } finally {
          job.mutex.unlock();
        }
        continue;
      }

      // Write the files in dt that point to this block
      job.writeMutex.lock();
      try {
        writeFiles(job, b, out);
      } finally {
        job.writeMutex.unlock();
      }
    } // end while true
  }

  private void writeFiles(ExtractJob job, Block b, MemoryBuffer out) {
    final byte[] od = out.data();
    for (int ip = 0; ip < b.files.size(); ++ip) {
      DT p = j.dt.at(b.files.get(ip));
      if (p.date() == 0 || p.data() < 0 || p.data() >= p.ptrSize()) continue;  // don't write

      // Look for pointers to this block
      long offset = 0;  // write offset
      for (int jj = 0; jj < p.ptrSize(); ++jj) {
        final int ptrj = p.ptr(jj);
        if (Integer.compareUnsigned(ptrj, b.start) < 0
            || Integer.compareUnsigned(ptrj, b.start + b.extracted) >= 0) {
          offset += j.ht.usize(ptrj);
          continue;
        }

        // Close last opened file if different
        if (p != job.lastdt) {
          if (job.outf != null) {
            job.outf.close();
            job.outf = null;
          }
          job.lastdt = null;
        }

        // Open file for output
        if (job.lastdt == null) {
          String filename = j.rename(p.name());
          if (p.data() == 0) {
            if (!j.dotest) j.ctx.fs.makepath(filename);
            if (j.summary <= 0) {
              job.mutex.lock();
              try {
                j.ctx.printProgress(job.totalSize, job.totalDone, j.summary);
                if (j.summary <= 0) {
                  j.ctx.emit(new InfoLine.FileExtracted(filename, "> " + filename + "\n"));
                }
              } finally {
                job.mutex.unlock();
              }
            }
            if (!j.dotest) {
              Exception[] err = new Exception[1];
              job.outf = ArchiveFile.create(j.ctx.fs, filename, err);
              if (job.outf == null) {
                job.mutex.lock();
                try {
                  j.ctx.fs.printerr(filename, err[0]);
                } finally {
                  job.mutex.unlock();
                }
              }
            }
          } else if (!j.dotest) {
            job.outf = ArchiveFile.openUpdate(j.ctx.fs, filename, null);  // update existing file
          }
          if (!j.dotest && job.outf == null) break;  // skip errors
          job.lastdt = p;
        }

        // Find block offset of fragment
        long q = 0;  // fragment offset from start of block
        for (int k = b.start; Integer.compareUnsigned(k, ptrj) < 0; ++k) {
          if (j.ht.usize(k) < 0) throw ZpaqException.error("streaming fragment in file");
          q += j.ht.usize(k);
        }

        // Combine consecutive fragments into a single write
        p.data(p.data() + 1);
        long usize = j.ht.usize(ptrj);
        while (jj + 1 < p.ptrSize() && p.ptr(jj + 1) == p.ptr(jj) + 1
            && Integer.compareUnsigned(p.ptr(jj + 1), b.start + b.size) < 0
            && j.ht.usize(p.ptr(jj + 1)) >= 0
            && usize + j.ht.usize(p.ptr(jj + 1)) <= 0x7fffffffL) {
          p.data(p.data() + 1);
          usize += j.ht.usize(p.ptr(++jj));
        }

        // Write the merged fragment unless they are all zeros and it does not include the last
        // fragment.
        long nz = q;  // first nonzero byte in fragments to be written
        while (nz < q + usize && od[(int) nz] == 0) ++nz;
        if (!j.dotest && (nz < q + usize || jj + 1 == p.ptrSize())) {
          job.outf.seekSet(offset);
          job.outf.write(od, (int) q, (int) usize);
        }
        offset += usize;
        job.mutex.lock();
        try {
          job.totalDone += usize;
        } finally {
          job.mutex.unlock();
        }

        // Close file. If this is the last fragment then set date and attr. Do not set read-only
        // attribute in Windows yet.
        if (p.data() == p.ptrSize()) {
          if (!j.dotest) {
            String fn = j.rename(p.name());
            long attr = p.attr();
            long date = p.date();
            if ((p.attr() & 0x1ff) == 'w' + 256) attr = 0;  // read-only?
            closeFile(fn, date, attr, job.outf);
            job.outf = null;
          }
          job.lastdt = null;
        }
      } // end for j
    } // end for ip
  }
}
