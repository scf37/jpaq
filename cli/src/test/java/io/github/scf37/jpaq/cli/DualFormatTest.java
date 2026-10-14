package io.github.scf37.jpaq.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scf37.jpaq.api.Format;
import io.github.scf37.jpaq.api.LocalFsStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line works with both archive formats and with both file extensions. */
class DualFormatTest {
  @Test
  void cliReadsSequentialArchivesAndNamesAreCosmetic(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    SeqArchiveTest.fill(src, 3, 4);
    var local = new LocalFsStorage(dir);

    // a sequential archive on disk (written through the API, as a backup tool would)
    var o = SeqArchiveTest.run(local, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx.jpaq",
        "a", "arc???.jpaq", "src", "-m1");
    assertEquals(0, o.rc(), o.stderr());
    assertTrue(Files.exists(dir.resolve("arc001.jpaq")));

    // the command line reads it without any option: list, extract, rebuild index
    Zpaq.Result l = Zpaq.run(dir, "l", "arc???.jpaq");
    assertTrue(l.ok(), l.stderr());
    assertTrue(l.stdout().contains("f0.bin"), l.stdout());
    Zpaq.Result x = Zpaq.run(dir, "x", "arc???.jpaq", "-to", "out");
    assertTrue(x.ok(), x.stderr() + x.stdout());
    SeqArchiveTest.assertSameTree(src, dir.resolve("out/src"));
    Zpaq.Result ri = Zpaq.run(dir, "x", "arc???.jpaq", "-index", "rebuilt.jpaq");
    assertTrue(ri.ok(), ri.stderr() + ri.stdout());
    assertArrayEquals(Files.readAllBytes(dir.resolve("idx.jpaq")),
        Files.readAllBytes(dir.resolve("rebuilt.jpaq")));

    // classic archives: .zpaq by default, any other extension is kept, and both read the same
    Jidac.clockOverride = java.time.Instant.parse("2026-01-02T03:04:05Z");  // same archive date
    try {
      assertTrue(Zpaq.run(dir, "a", "plain", "src", "-m1").ok());
      assertTrue(Zpaq.run(dir, "a", "named.jpaq", "src", "-m1").ok());
    } finally {
      Jidac.clockOverride = null;
    }
    assertTrue(Files.exists(dir.resolve("plain.zpaq")));
    assertTrue(Files.exists(dir.resolve("named.jpaq")));
    assertFalse(Files.exists(dir.resolve("named.jpaq.zpaq")));
    for (String n : new String[] {"plain", "plain.zpaq", "named.jpaq"}) {
      Zpaq.Result ll = Zpaq.run(dir, "l", n);
      assertTrue(ll.ok() && ll.stdout().contains("f0.bin"), n + ll.stderr());
    }
    byte[] a = Files.readAllBytes(dir.resolve("plain.zpaq"));
    byte[] b = Files.readAllBytes(dir.resolve("named.jpaq"));
    assertArrayEquals(a, b, "the extension does not change the classic format");

    // the formats are not mixed: classic add to a sequential archive, sequential add to classic
    Zpaq.Result bad = Zpaq.run(dir, "a", "arc???.jpaq", "src");
    assertNotEquals(0, bad.exitCode());
    assertTrue(bad.stderr().contains("sequential") || bad.stderr().contains("jpaq"), bad.stderr());
    var s = SeqArchiveTest.run(local, Format.SEQUENTIAL, dir, j -> j.indexOut = "ci.jpaq",
        "a", "arc???.jpaq", "src", "-index", "plain.zpaq");
    assertNotEquals(0, s.rc());
  }
}
