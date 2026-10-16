package io.github.scf37.jpaq.engine;

/** Version info. */
final class Ver {
  long date;          // Date of C block, 0 if streaming
  long lastdate;      // Latest date of any block
  long offset;        // start of transaction C block
  long dataOffset;    // start of first D block
  long csize;         // size of compressed data, -1 = no index
  int updates;        // file updates
  int deletes;        // file deletions
  int firstFragment;  // first fragment ID
}
