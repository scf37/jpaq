package io.github.scf37.jpaq.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/** Random test trees and their comparison. */
final class Trees {
  private Trees() {}

  /** Writes {@code files} files of random and of compressible content, and sub/deep/t.txt. */
  static void fill(Path dir, long seed, int files) throws IOException {
    Random r = new Random(seed);
    Files.createDirectories(dir);
    for (int i = 0; i < files; i++) {
      byte[] b = new byte[20000 + r.nextInt(300000)];
      if (i % 2 == 0) {
        r.nextBytes(b);
      } else {
        for (int k = 0; k < b.length; k++) b[k] = (byte) ('a' + r.nextInt(6));
      }
      Files.write(dir.resolve("f" + i + ".bin"), b);
    }
    Files.createDirectories(dir.resolve("sub/deep"));
    Files.writeString(dir.resolve("sub/deep/t.txt"), "text ".repeat(5000));
  }

  /** The regular files of a and b are the same, with the same contents. */
  static void assertSameTree(Path a, Path b) throws IOException {
    List<Path> fa = files(a);
    assertEquals(fa, files(b));
    for (Path p : fa)
      assertArrayEquals(Files.readAllBytes(a.resolve(p)), Files.readAllBytes(b.resolve(p)), p.toString());
  }

  private static List<Path> files(Path dir) throws IOException {
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).map(dir::relativize).sorted().toList();
    }
  }
}
