/** The zpaq command line archiver, built on the public API of the archive library only. */
@SuppressWarnings("module")  // "scf37" ends with digits
module io.github.scf37.jpaq.cli {
  requires io.github.scf37.jpaq;

  exports io.github.scf37.jpaq.cli;
}
