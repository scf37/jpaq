package io.github.scf37.jpaq.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scf37.jpaq.ArchiveException;
import io.github.scf37.jpaq.Storage;
import io.github.scf37.jpaq.ZpaqArchive;
import io.github.scf37.jpaq.ZpaqArchive.Format;
import io.github.scf37.jpaq.ZpaqArchive.Option;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The command line works with both archive formats and with both file extensions. */
class DualFormatTest {
  /** A sequential archive of {@code src} written through the library, as a backup tool would. */
  private static void sequential(Path dir, String name, String indexOut, Path src, Instant date)
      throws IOException {
    ZpaqArchive a = ZpaqArchive.of(Storage.ofDirectory(dir), name, Format.SEQUENTIAL,
        Option.threads(1), Option.directory(src.getParent()));
    a.add(b -> b.files("src").indexOut(indexOut).date(date));
  }

  @Test
  void cliReadsSequentialArchivesAndNamesAreCosmetic(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    Trees.fill(src, 3, 4);
    sequential(dir, "arc???.jpaq", "idx.jpaq", src, null);
    assertTrue(Files.exists(dir.resolve("arc001.jpaq")));

    // the command line reads it without any option: list, extract, rebuild index
    Zpaq.Result l = Zpaq.run(dir, "l", "arc???.jpaq");
    assertTrue(l.ok(), l.stderr());
    assertTrue(l.stdout().contains("f0.bin"), l.stdout());
    Zpaq.Result x = Zpaq.run(dir, "x", "arc???.jpaq", "-to", "out");
    assertTrue(x.ok(), x.stderr() + x.stdout());
    Trees.assertSameTree(src, dir.resolve("out/src"));
    Zpaq.Result ri = Zpaq.run(dir, "x", "arc???.jpaq", "-index", "rebuilt.jpaq");
    assertTrue(ri.ok(), ri.stderr() + ri.stdout());
    assertArrayEquals(Files.readAllBytes(dir.resolve("idx.jpaq")),
        Files.readAllBytes(dir.resolve("rebuilt.jpaq")));

    // classic archives: .zpaq by default, any other extension is kept, and both read the same
    Instant t = Instant.parse("2026-01-02T03:04:05Z");  // same archive date
    assertTrue(Zpaq.run(dir, t, "a", "plain", "src", "-m1").ok());
    assertTrue(Zpaq.run(dir, t, "a", "named.jpaq", "src", "-m1").ok());
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

    // the command line does not modify jpaq archives: no add, no repack
    byte[] tome = Files.readAllBytes(dir.resolve("arc001.jpaq"));
    Zpaq.Result bad = Zpaq.run(dir, "a", "arc???.jpaq", "src");
    assertEquals(2, bad.exitCode());
    assertTrue(bad.stderr().contains("cannot add to a jpaq sequential archive"), bad.stderr());
    Zpaq.Result rp = Zpaq.run(dir, "x", "arc???.jpaq", "-repack", "re.zpaq");
    assertEquals(2, rp.exitCode());
    assertTrue(rp.stderr().contains("cannot repack a jpaq sequential archive"), rp.stderr());
    assertArrayEquals(tome, Files.readAllBytes(dir.resolve("arc001.jpaq")));
    assertFalse(Files.exists(dir.resolve("arc002.jpaq")));
    Zpaq.Result test = Zpaq.run(dir, "x", "arc???.jpaq", "-test");
    assertEquals(0, test.exitCode(), test.stderr());

    // the library does not mix the formats either
    assertThrows(ArchiveException.class, () -> ZpaqArchive.of(Storage.ofDirectory(dir),
        "plain??.zpaq", Format.SEQUENTIAL, Option.directory(dir))
        .add(add -> add.files("src").index("plain.zpaq").indexOut("ci.jpaq")));
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
    Trees.fill(src, 21, 5);
    Instant t = Instant.parse("2026-03-04T05:06:07Z");
    // classic remote archive: tome + index (same layout as the sequential one)
    assertTrue(Zpaq.run(dir, t, "a", "arc???.zpaq", "src", "-m1", "-t1", "-index", "idx.zpaq").ok());
    Path s = Files.createDirectories(dir.resolve("seq"));
    sequential(s, "arc???.jpaq", "idx.jpaq", src, t);
    for (String[] pair : new String[][] {{"arc001.zpaq", "arc001.jpaq"}, {"idx.zpaq", "idx.jpaq"}}) {
      byte[] a = Files.readAllBytes(dir.resolve(pair[0]));
      byte[] b = Files.readAllBytes(s.resolve(pair[1]));
      assertEquals(a.length, b.length, pair[0]);
      assertEquals(0, normalize(a) - normalize(b));
      int diff = 0;
      for (int i = 0; i < a.length; i++) if (a[i] != b[i]) diff++;
      // only the header block (104 bytes) may differ: its data size and checksum
      for (int i = 104; i < a.length; i++) assertEquals(a[i], b[i], pair[0] + " at " + i);
      assertTrue(diff <= 30, "differences: " + diff);
    }
  }
}
