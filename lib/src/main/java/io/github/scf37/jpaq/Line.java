package io.github.scf37.jpaq;

/**
 * One line (or piece of a line) of output of a command: what the zpaq command line prints, as
 * objects. {@link InfoLine}s describe progress and results, {@link ErrorLine}s problems. {@link
 * #text()} is exactly the text that the command line prints for it, so concatenating the text of
 * all lines on each {@link Channel} gives the stdout and stderr of the command line.
 */
public sealed interface Line permits InfoLine, ErrorLine {
  /** The console text, including the line end if there is one. File names are real text. */
  String text();

  /** Where the command line prints this line. */
  Channel channel();

  enum Channel { STDOUT, STDERR }
}
