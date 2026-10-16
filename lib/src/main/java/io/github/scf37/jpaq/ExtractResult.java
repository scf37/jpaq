package io.github.scf37.jpaq;

import java.util.List;

/**
 * What an extraction (or verification) did.
 *
 * @param files files selected for extraction (without skipped ones)
 * @param skipped existing files that were left alone
 * @param failed files that could not be extracted completely
 * @param problems everything that went wrong: damaged blocks, files that could not be written,
 *     names that would leave the output directory
 */
public record ExtractResult(int files, int skipped, int failed, List<ErrorLine> problems) {
  public ExtractResult {
    problems = List.copyOf(problems);
  }

  /** True if everything was extracted (or verified) without problems. */
  public boolean ok() {
    return problems.isEmpty();
  }
}
