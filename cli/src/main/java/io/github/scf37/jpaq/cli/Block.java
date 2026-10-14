package io.github.scf37.jpaq.cli;

import java.util.ArrayList;
import java.util.List;

/** A data block to extract. */
final class Block {
  enum State { READY, WORKING, GOOD, BAD }

  long offset;       // location in archive
  long usize = -1;   // uncompressed size, -1 if unknown (streaming)
  long bsize;        // compressed size
  final List<DT> files = new ArrayList<>();  // list of files pointing here
  int start;         // index in ht of first fragment
  int size;          // number of fragments to decompress
  int frags;         // number of fragments in block
  int extracted;     // number of fragments decompressed OK
  State state = State.READY;

  Block(int s, long o) {
    offset = o;
    start = s;
  }
}
