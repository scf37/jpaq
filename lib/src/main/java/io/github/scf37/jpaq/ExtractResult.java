package io.github.scf37.jpaq;

/**
 * What an extraction (or test) did.
 *
 * @param files files selected (without those skipped)
 * @param skipped existing files left as they are
 * @param failed files not extracted completely (reported as {@link Event.ExtractFailed})
 */
public record ExtractResult(int files, int skipped, int failed) {
  public boolean ok() {
    return failed == 0;
  }
}
