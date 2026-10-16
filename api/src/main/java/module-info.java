/**
 * Creating, updating, reading and extracting zpaq and jpaq journaling archives. Start with
 * {@link io.github.scf37.jpaq.ZpaqArchive}.
 */
@SuppressWarnings("module")  // "scf37" ends with digits
module io.github.scf37.jpaq {
  requires io.github.scf37.jpaq.libzpaq;

  exports io.github.scf37.jpaq;
}
