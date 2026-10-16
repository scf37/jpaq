package io.github.scf37.jpaq.engine;

import io.github.scf37.jpaq.libzpaq.Dialect;
import io.github.scf37.jpaq.libzpaq.Libzpaq;
import io.github.scf37.jpaq.libzpaq.MemoryBuffer;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import io.github.scf37.jpaq.libzpaq.ZpaqWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A queue of blocks to compress and write to the archive. Each block cycles through states EMPTY,
 * FULL, COMPRESSING, COMPRESSED, WRITING. The main thread waits for EMPTY buffers and fills them.
 * A set of compress threads waits for FULL buffers and compresses them. A writer thread waits for
 * COMPRESSED buffers at the front of the queue and writes and removes them, so the output order is
 * the same as the input order regardless of the number of threads.
 */
final class CompressJob {
  private enum State { EMPTY, FULL, COMPRESSING, COMPRESSED, WRITING }

  /** Buffer queue element. */
  private static final class CJ {
    State state = State.EMPTY;
    MemoryBuffer in = new MemoryBuffer();    // uncompressed input
    MemoryBuffer out = new MemoryBuffer();   // compressed output
    String filename = "";                    // to write in filename field
    String comment = "";                     // if "" use default
    String method = "";                      // compression level or "" to mark end of data
    final Semaphore full = new Semaphore(0);        // 1 if in is FULL of data ready to compress
    final Semaphore compressed = new Semaphore(0);  // 1 if out contains COMPRESSED data
  }

  private final ReentrantLock mutex = new ReentrantLock();  // protects state changes
  private int job;                       // number of jobs
  private final CJ[] q;                  // buffer queue
  private final int qsize;               // number of elements in q
  private int front;                     // next to remove from queue
  private final ZpaqWriter out;          // archive
  private final Dialect dialect;         // block magic
  private final Semaphore empty;         // number of empty buffers ready to fill
  private final Semaphore compressors;   // number of compressors available to run
  private final List<Thread> threads = new ArrayList<>();

  private volatile boolean finished;

  /** Compressed block sizes. */
  final List<Integer> csize = Collections.synchronizedList(new ArrayList<>());

  private final Ctx ctx;

  CompressJob(Ctx ctx, int nthreads, int buffers, ZpaqWriter f, Dialect dialect) {
    this.ctx = ctx;
    this.dialect = dialect;
    qsize = buffers;
    out = f;
    q = new CJ[buffers];
    for (int i = 0; i < buffers; i++) q[i] = new CJ();
    empty = new Semaphore(buffers);
    compressors = new Semaphore(nthreads);
    for (int i = 0; i < buffers; i++) {
      Thread t = new Thread(this::compressThread, "compress-" + i);
      t.setDaemon(true);
      threads.add(t);
      t.start();
    }
    Thread w = new Thread(this::writeThread, "writer");
    w.setDaemon(true);
    threads.add(w);
    w.start();
  }

  /**
   * Writes s at the back of the queue (swapping its buffer with an empty one). Signal end of input
   * with method "".
   */
  void write(MemoryBuffer s, String fn, String method, String comment) {
    for (int k = method.isEmpty() ? qsize : 1; k > 0; --k) {
      while (true) {
        checkFailure();
        try {
          if (empty.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw ZpaqException.error("interrupted");
        }
      }
      mutex.lock();
      try {
        int i;
        for (i = 0; i < qsize; ++i) {
          CJ cj = q[(i + front) % qsize];
          if (cj.state == State.EMPTY) {
            cj.filename = fn != null ? fn : "";
            cj.comment = comment != null ? comment : "jDC\u0001";
            cj.method = method;
            cj.in.resize(0);
            cj.in.swap(s);
            cj.state = State.FULL;
            cj.full.release();
            break;
          }
        }
        if (i >= qsize) throw new IllegalStateException("queue should not be full");
      } finally {
        mutex.unlock();
      }
    }
  }

  // Waits for a permit; returns false if the thread was interrupted (job aborted).
  private static boolean acquire(Semaphore s) {
    try {
      s.acquire();
      return true;
    } catch (InterruptedException e) {
      return false;
    }
  }

  private volatile Throwable failure;

  /** A worker failed: report it, stop everything, and let the main thread throw. */
  private void fail(String message, Throwable e) {
    ctx.eprint(message);
    if (failure == null) failure = e;
    abort();
  }

  private void checkFailure() {
    Throwable f = failure;
    if (f != null) throw ZpaqException.error("compression failed: " + Jidac.what(f));
  }

  /** Stops all threads without finishing the queue (after an error in the main thread). */
  void abort() {
    if (finished) return;
    finished = true;
    for (Thread t : threads) t.interrupt();  // idle threads exit; busy ones stop when done
  }

  /** Waits for all threads to finish (after writing the end of input marker). */
  void join() {
    join0();
    finished = true;
    checkFailure();
  }

  private void join0() {
    for (Thread t : threads) {
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
  }

  // Compress data in the background, one per buffer
  private void compressThread() {
    int jobNumber = 0;
    try {
      // Get job number = assigned position in queue
      mutex.lock();
      CJ cj;
      try {
        jobNumber = job++;
        cj = q[jobNumber];
      } finally {
        mutex.unlock();
      }

      // Work until done
      while (true) {
        if (!acquire(cj.full)) return;
        mutex.lock();
        try {
          // Check for end of input
          if (cj.method.isEmpty()) {
            cj.compressed.release();
            return;
          }
          cj.state = State.COMPRESSING;
        } finally {
          mutex.unlock();
        }
        if (!acquire(compressors)) return;
        try {
          Libzpaq.compressBlock(cj.in, cj.out, cj.method, Strs.getBytes(cj.filename),
              cj.comment.isEmpty() ? null : Strs.getBytes(cj.comment), true, dialect);
        } finally {
          // released below
        }
        cj.in.resize(0);
        mutex.lock();
        try {
          cj.state = State.COMPRESSED;
          cj.compressed.release();
          compressors.release();
        } finally {
          mutex.unlock();
        }
      }
    } catch (RuntimeException | OutOfMemoryError e) {
      fail(String.format("job %d: %s\n", jobNumber + 1, Jidac.what(e)), e);
    }
  }

  // Write compressed data to the archive in the background
  private void writeThread() {
    try {
      // work until done
      while (true) {
        // wait for something to write
        CJ cj = q[front];  // no other threads move front
        if (!acquire(cj.compressed)) return;

        // Quit if end of input
        mutex.lock();
        try {
          if (cj.method.isEmpty()) return;
          cj.state = State.WRITING;
          csize.add(cj.out.size());
        } finally {
          mutex.unlock();
        }

        // Write to archive
        if (out != null && cj.out.size() > 0) out.write(cj.out.data(), 0, cj.out.size());
        cj.out.resize(0);
        mutex.lock();
        try {
          cj.state = State.EMPTY;
          front = (front + 1) % qsize;
          empty.release();
        } finally {
          mutex.unlock();
        }
      }
    } catch (RuntimeException | OutOfMemoryError e) {
      fail(String.format("zpaq exiting from writeThread: %s\n", Jidac.what(e)), e);
    }
  }
}
