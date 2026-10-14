package io.github.scf37.jpaq.api;

/** Optional abilities of a {@link Storage}, beyond streaming read, write and delete. */
public enum Capability {
  /** {@link Storage#exists} and {@link Storage#length} work (needed to find archive parts). */
  PROBE,
  /** {@link Storage#read(String, long)} starts reading at an offset without transferring the skipped bytes. */
  RANGED_READ,
  /** {@link Storage#open} gives positional read, write, truncate (needed by the classic format). */
  RANDOM_WRITE
}
