package io.github.scf37.jpaq.cli;

import java.nio.file.Path;

/** Helpers for tests that need a classic archive on disk. */
final class LocalDirTest {
  private LocalDirTest() {}

  static void classicArchive(Path dir, String name) {
    int rc = Zpaq.run(dir, "a", name, "src", "-m1").exitCode();
    if (rc != 0) throw new IllegalStateException("classic archive failed " + rc);
  }
}
