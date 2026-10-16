package io.github.scf37.jpaq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/** Test trees and comparisons. */
public final class TestTrees {
  private TestTrees() {}

  /** Writes {@code files} files of random and of compressible content, and sub/deep/t.txt. */
  public static void fill(Path dir, long seed, int files) throws IOException {
    Random r = new Random(seed);
    Files.createDirectories(dir);
    for (int i = 0; i < files; i++) {
      Files.write(dir.resolve("f" + i + ".bin"), content(r, i, 20000 + r.nextInt(300000)));
    }
    Files.createDirectories(dir.resolve("sub/deep"));
    Files.writeString(dir.resolve("sub/deep/t.txt"), "text ".repeat(5000));
  }

  /** Random bytes for even i, text-like bytes for odd i. */
  public static byte[] content(Random r, int i, int size) {
    byte[] b = new byte[size];
    if (i % 2 == 0) {
      r.nextBytes(b);
    } else {
      for (int k = 0; k < b.length; k++) b[k] = (byte) ('a' + r.nextInt(6));
    }
    return b;
  }

  /** The regular files of a and b are the same, with the same contents. */
  public static void assertSameTree(Path a, Path b) throws IOException {
    List<Path> fa = files(a);
    assertEquals(fa, files(b));
    for (Path p : fa)
      assertArrayEquals(Files.readAllBytes(a.resolve(p)), Files.readAllBytes(b.resolve(p)), p.toString());
  }

  public static List<Path> files(Path dir) throws IOException {
    try (Stream<Path> s = Files.walk(dir)) {
      return s.filter(Files::isRegularFile).map(dir::relativize).sorted().toList();
    }
  }

  public static byte[] readAll(InputStream in) throws IOException {
    try (in) {
      return in.readAllBytes();
    }
  }

  /** Stock zpaq block finder: tag + "zPQ". */
  public static boolean stockFindsBlock(byte[] s) {
    int h1 = 0x3D49B113, h2 = 0x29EB7F93, h3 = 0x2614BE13, h4 = 0x3828EB13;
    for (byte b : s) {
      int c = b & 255;
      h1 = h1 * 12 + c;
      h2 = h2 * 20 + c;
      h3 = h3 * 28 + c;
      h4 = h4 * 44 + c;
      if (h1 == 0xB16B88F1 && h2 == 0xFF5376F1 && h3 == 0x72AC5BF1 && h4 == 0x2F909AF1) return true;
    }
    return false;
  }
}
