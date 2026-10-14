package io.github.scf37.jpaq.api;

/** Archive format written by a command (reading accepts both). */
public enum Format {
  /**
   * The zpaq 7.15 format, byte identical to the C++ program, files ".zpaq". Writing needs
   * {@link Capability#RANDOM_WRITE} (the transaction header is patched at the end) and, for multi
   * part archives without an index, {@link Capability#PROBE}.
   */
  CLASSIC,
  /**
   * Append-only variant ("sequential tome", files ".jpaq"): blocks carry a different magic so zpaq
   * cannot read them, every file is written front to back exactly once and nothing is patched.
   * Needs only streaming read, write and delete, and an index.
   */
  SEQUENTIAL
}
