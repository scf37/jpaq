/**
 * Creating, updating, reading and extracting zpaq and jpaq journaling archives. Start with
 * {@link io.github.scf37.jpaq.ZpaqArchive}.
 */
@SuppressWarnings("module")  // "scf37" ends with digits; the cli module is built later
module io.github.scf37.jpaq {
  requires io.github.scf37.jpaq.libzpaq;

  exports io.github.scf37.jpaq;
  // The archiver engine, for the zpaq command line only.
  exports io.github.scf37.jpaq.engine to io.github.scf37.jpaq.cli;
}
