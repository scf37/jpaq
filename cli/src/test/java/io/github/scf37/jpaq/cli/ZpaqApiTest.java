package io.github.scf37.jpaq.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZpaqApiTest {
  @Test
  void roundTripInProcess(@TempDir Path dir) throws Exception {
    Path src = Files.createDirectory(dir.resolve("src"));
    Files.writeString(src.resolve("a.txt"), "hello hello hello");
    Zpaq.Result r = Zpaq.run(dir, "a", "backup.zpaq", "src", "-m1");
    assertTrue(r.ok(), r.stderr());
    assertTrue(Files.exists(dir.resolve("backup.zpaq")));
    Zpaq.Result l = Zpaq.run(dir, "l", "backup.zpaq");
    assertEquals(0, l.exitCode(), l.stderr());
    assertTrue(l.stdout().contains("a.txt"), l.stdout());
    Path out = Files.createDirectory(dir.resolve("out"));
    assertTrue(Zpaq.run(dir, "x", "backup.zpaq", "-to", "out").ok());
    assertEquals("hello hello hello", Files.readString(out.resolve("src/a.txt")));
    assertEquals(2, Zpaq.run(dir, "x", "missing.zpaq", "-to", "out").exitCode());
  }
}
