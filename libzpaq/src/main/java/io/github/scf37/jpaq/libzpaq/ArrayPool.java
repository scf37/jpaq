package io.github.scf37.jpaq.libzpaq;

import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Recycles the big work arrays (hash tables, suffix arrays, model tables) between blocks. Fresh
 * large arrays are expensive for the garbage collector (they are copied from the young
 * generation), so each compress thread reuses what the previous block released. Pooled arrays
 * are only softly reachable, so they are dropped when memory gets tight.
 */
final class ArrayPool {
  private ArrayPool() {}

  /** Arrays smaller than this are not worth pooling. */
  private static final int MIN_BYTES = 1 << 20;
  /** Retained arrays per size. */
  private static final int MAX_PER_SIZE = 8;

  private static final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<SoftReference<int[]>>>
      INTS = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<Integer, ConcurrentLinkedQueue<SoftReference<byte[]>>>
      BYTES = new ConcurrentHashMap<>();

  /** Returns a zero filled int array of the given length. */
  static int[] ints(int len) {
    if ((long) len * 4 >= MIN_BYTES) {
      ConcurrentLinkedQueue<SoftReference<int[]>> q = INTS.get(len);
      if (q != null) {
        SoftReference<int[]> r;
        while ((r = q.poll()) != null) {
          int[] a = r.get();
          if (a != null) {
            Arrays.fill(a, 0);
            return a;
          }
        }
      }
    }
    return new int[len];
  }

  /** Returns an int array to the pool; it must not be used afterwards. */
  static void release(int[] a) {
    if (a == null || (long) a.length * 4 < MIN_BYTES) return;
    ConcurrentLinkedQueue<SoftReference<int[]>> q =
        INTS.computeIfAbsent(a.length, k -> new ConcurrentLinkedQueue<>());
    if (q.size() < MAX_PER_SIZE) q.add(new SoftReference<>(a));
  }

  /** Returns a zero filled byte array of the given length. */
  static byte[] bytes(int len) {
    if (len >= MIN_BYTES) {
      ConcurrentLinkedQueue<SoftReference<byte[]>> q = BYTES.get(len);
      if (q != null) {
        SoftReference<byte[]> r;
        while ((r = q.poll()) != null) {
          byte[] a = r.get();
          if (a != null) {
            Arrays.fill(a, (byte) 0);
            return a;
          }
        }
      }
    }
    return new byte[len];
  }

  /** Returns a byte array to the pool; it must not be used afterwards. */
  static void release(byte[] a) {
    if (a == null || a.length < MIN_BYTES) return;
    ConcurrentLinkedQueue<SoftReference<byte[]>> q =
        BYTES.computeIfAbsent(a.length, k -> new ConcurrentLinkedQueue<>());
    if (q.size() < MAX_PER_SIZE) q.add(new SoftReference<>(a));
  }
}
