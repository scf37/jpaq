/** The zpaq command line archiver. */
@SuppressWarnings("module")  // "scf37" ends with digits
module io.github.scf37.jpaq.cli {
  requires io.github.scf37.jpaq;
  requires io.github.scf37.jpaq.libzpaq;

  exports io.github.scf37.jpaq.cli;
}
