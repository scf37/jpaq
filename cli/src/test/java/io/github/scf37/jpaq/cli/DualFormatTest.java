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

  private static final byte[] TAG = {0x37, 0x6b, 0x53, 0x74, (byte) 0xa0, 0x31, (byte) 0x83,
      (byte) 0xd3, (byte) 0x8c, (byte) 0xb2, 0x28, (byte) 0xb0, (byte) 0xd3};

  /** Replaces the block magic jPQ by zPQ. Returns the number of blocks. */
  private static int normalize(byte[] b) {
    int n = 0;
    for (int i = 0; i + 16 <= b.length; i++) {
      boolean tag = true;
      for (int k = 0; k < 13 && tag; k++) tag = b[i + k] == TAG[k];
      if (tag && (b[i + 13] == 'z' || b[i + 13] == 'j') && b[i + 14] == 'P' && b[i + 15] == 'Q') {
        b[i + 13] = 'z';
        n++;
      }
    }
    return n;
  }

  @Test
  void sequentialTomeDiffersFromClassicOnlyInMagicAndHeader(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    SeqArchiveTest.fill(src, 21, 5);
    Jidac.clockOverride = java.time.Instant.parse("2026-03-04T05:06:07Z");
    try {
      // classic remote archive: tome + index (same layout as the sequential one)
      Path c = dir;
      assertTrue(Zpaq.run(c, "a", "arc???.zpaq", "src", "-m1", "-t1", "-index", "idx.zpaq").ok());
      var st = new MemStorage();
      var o = SeqArchiveTest.run(st, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx.jpaq",
          "a", "arc???.jpaq", "src", "-m1", "-t1");
      assertEquals(0, o.rc(), o.stderr());
      for (String[] pair : new String[][] {{"arc001.zpaq", "arc001.jpaq"}, {"idx.zpaq", "idx.jpaq"}}) {
        byte[] a = Files.readAllBytes(c.resolve(pair[0]));
        byte[] b = st.files.get(pair[1]).clone();
        assertEquals(a.length, b.length, pair[0]);
        assertEquals(0, normalize(a) - normalize(b));
        int diff = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) diff++;
        // only the header block (104 bytes) may differ: its data size and checksum
        for (int i = 104; i < a.length; i++) assertEquals(a[i], b[i], pair[0] + " at " + i);
        assertTrue(diff <= 30, "differences: " + diff);
      }
    } finally {
      Jidac.clockOverride = null;
    }
  }
}
