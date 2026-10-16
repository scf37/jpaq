package io.github.scf37.jpaq;

import static io.github.scf37.jpaq.TestTrees.assertSameTree;
import static io.github.scf37.jpaq.TestTrees.fill;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The library API on local archives. */
class ZpaqArchiveTest {
  private static final Instant T1 = Instant.parse("2026-01-02T03:04:05Z");
  private static final Instant T2 = Instant.parse("2026-02-03T04:05:06Z");

  private static ZpaqArchive archive(Path dir, String name) {
    return ZpaqArchive.of(dir.resolve(name)).withDirectory(dir).withThreads(2)
        .withClock(Clock.fixed(T1, ZoneOffset.UTC));
  }

  private static List<String> names(ArchiveContents c) {
    return c.entries().stream().map(ArchiveEntry::name).toList();
  }

  private static ExtractResult extractTo(ZpaqArchive a, ReadOptions ro, String out)
      throws IOException {
    return a.extract(new ExtractOptions(ro, ExtractOptions.under(out), false, false));
  }

  @Test
  void versions(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 1, 4);
    ZpaqArchive a = archive(dir, "a.zpaq");
    AddResult r1 = a.add(AddOptions.of("src"));
    assertEquals(8, r1.added(), r1.toString());  // 4 files, t.txt, src/ sub/ sub/deep/
    assertTrue(r1.inputBytes() > 0 && r1.addedBytes() > 0 && r1.archiveBytes() == r1.addedBytes());

    Files.writeString(src.resolve("f1.bin"), "changed");
    Files.delete(src.resolve("f2.bin"));
    Files.writeString(src.resolve("new.txt"), "new");
    AddResult r2 = a.add(AddOptions.of("src").withDate(T2));
    assertEquals(1, r2.removed(), r2.toString());
    assertFalse(a.add(AddOptions.of("src").withDate(T2.plusSeconds(10))).changed());

    ArchiveContents c = a.read(ReadOptions.LATEST);
    assertEquals(2, c.versions().size());
    assertEquals(T1, c.versions().get(0).instant());
    assertEquals(T2, c.versions().get(1).instant());
    assertTrue(names(c).contains("src/new.txt"));
    assertFalse(names(c).contains("src/f2.bin"));
    assertTrue(names(c).contains("src/sub/"));
    for (ReadOptions old : List.of(ReadOptions.LATEST.withVersion(1L),
        ReadOptions.LATEST.withVersion(-1L), ReadOptions.LATEST.withDate(T1.plusSeconds(60)))) {
      ArchiveContents o = a.read(old);
      assertEquals(1, o.versions().size(), old.toString());
      assertTrue(names(o).contains("src/f2.bin"), old.toString());
    }
    assertTrue(a.read(ReadOptions.LATEST.withVersion(0L)).entries().isEmpty());

    // every version of every file
    ArchiveContents all = a.read(ReadOptions.LATEST.withVersionDigits(4));
    assertTrue(names(all).contains("0001/src/f2.bin"));
    assertTrue(all.entries().stream().anyMatch(e -> e.name().equals("0002/src/f2.bin") && e.date() == 0));
    assertEquals(0, extractTo(a, ReadOptions.LATEST.withVersionDigits(4), "all").failed());
    assertTrue(Files.exists(dir.resolve("all/0001/src/f2.bin")));
    assertEquals("changed", Files.readString(dir.resolve("all/0002/src/f1.bin")));
    assertTrue(Files.isDirectory(dir.resolve("all/0002")));

    // latest
    ExtractResult x = extractTo(a, ReadOptions.LATEST, "out");
    assertTrue(x.ok() && x.files() == 5, x.toString());
    assertSameTree(src, dir.resolve("out/src"));
    // again: everything exists and is skipped, or is identical with overwrite
    assertEquals(8, extractTo(a, ReadOptions.LATEST, "out").skipped());  // with 3 directories
    assertEquals(5, a.extract(new ExtractOptions(ReadOptions.LATEST, ExtractOptions.under("out"),
        true, false)).skipped());
  }

  @Test
  void namesFiltersAndScope(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 2, 3);
    Files.writeString(src.resolve("skip.tmp"), "tmp");
    Files.createDirectories(src.resolve("cache"));
    Files.writeString(src.resolve("cache/big.bin"), "cache");
    ZpaqArchive a = archive(dir, "n.zpaq");
    AddOptions o = AddOptions.of("src")
        .withArchiveName(n -> "backup/" + n)
        .withPrune(n -> n.equals("src/cache"))
        .withAccept(n -> !n.endsWith(".tmp"))
        .withScope(n -> n.startsWith("backup/src"));
    a.add(o);
    List<String> n1 = names(a.read(ReadOptions.LATEST));
    assertTrue(n1.contains("backup/src/f0.bin"));
    assertFalse(n1.stream().anyMatch(n -> n.contains("tmp") || n.contains("cache")), n1.toString());

    // entries out of scope are not deleted
    try (var unused = Files.newOutputStream(dir.resolve("other.txt"))) { }
    a.add(AddOptions.of("other.txt").withDate(T2));
    Files.delete(src.resolve("f0.bin"));
    AddResult r = a.add(o.withDate(T2.plusSeconds(1)));
    assertEquals(1, r.removed());
    List<String> n2 = names(a.read(ReadOptions.LATEST.withSelect(n -> !n.startsWith("backup/src/sub"))));
    assertTrue(n2.contains("other.txt"));
    assertFalse(n2.contains("backup/src/f0.bin"));
    assertFalse(n2.stream().anyMatch(n -> n.startsWith("backup/src/sub")), "not selected");

    // extract renamed and only some
    a.extract(new ExtractOptions(ReadOptions.LATEST.withSelect(n -> n.endsWith("f1.bin")),
        n -> "restored/" + n.substring(n.lastIndexOf('/') + 1), false, false));
    assertArrayEquals(Files.readAllBytes(src.resolve("f1.bin")),
        Files.readAllBytes(dir.resolve("restored/f1.bin")));
    assertEquals(1, TestTrees.files(dir.resolve("restored")).size());

    // a local scan sees what an update would see
    List<LocalFile> local = LocalFiles.scan(dir, List.of("src"), n -> n.equals("src/cache"), null,
        false, null);
    assertTrue(local.stream().anyMatch(f -> f.name().equals("src/sub/deep/t.txt") && f.size() == 25000));
    assertTrue(local.stream().noneMatch(f -> f.name().startsWith("src/cache")));
    assertTrue(local.stream().anyMatch(f -> f.name().equals("src/") && f.isDirectory()));
  }

  @Test
  void rollbackRepackCopyIndex(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 3, 3);
    ZpaqArchive a = archive(dir, "r.zpaq").withKey("k1");
    a.add(AddOptions.of("src"));
    Files.writeString(src.resolve("f0.bin"), "v2");
    a.add(AddOptions.of("src").withDate(T2));
    Files.writeString(src.resolve("f0.bin"), "v3");
    // replace version 2 by a new one
    a.add(AddOptions.of("src").withDate(T2.plusSeconds(5)).withRollbackVersion(1L));
    ArchiveContents c = a.read(ReadOptions.LATEST);
    assertEquals(2, c.versions().size());
    assertTrue(c.sameContent(c.entries().stream().filter(e -> e.name().equals("src/f0.bin"))
        .findFirst().orElseThrow(), "src/f0.bin"));

    // repack some files under another key, copy everything under another key
    a.repack("part.zpaq", "k2", new ExtractOptions(ReadOptions.LATEST.withSelect(
        n -> n.startsWith("src/sub")), null, false, false));
    ZpaqArchive part = archive(dir, "part.zpaq").withKey("k2");
    assertTrue(names(part.read(ReadOptions.LATEST)).stream().allMatch(n -> n.startsWith("src/sub")));
    assertThrows(ArchiveException.class, () -> a.repack("part.zpaq", null, ExtractOptions.DEFAULT));
    a.copy("copy.zpaq", null, false);
    assertEquals(names(c), names(archive(dir, "copy.zpaq").read(ReadOptions.LATEST)));

    // an index, then updates of a multi part archive through it
    ZpaqArchive m = archive(dir, "m???.zpaq");
    m.add(AddOptions.of("src").withIndex("m.idx"));
    Files.writeString(src.resolve("f1.bin"), "changed");
    m.add(AddOptions.of("src").withIndex("m.idx").withDate(T2));
    assertTrue(Files.exists(dir.resolve("m002.zpaq")));
    ArchiveContents fromIndex = m.read(ReadOptions.LATEST.withIndex("m.idx"));
    assertEquals(names(m.read(ReadOptions.LATEST)), names(fromIndex));
    assertTrue(fromIndex.dataSize() > fromIndex.storedDataSize());
    m.writeIndex("rebuilt.idx", false, ReadOptions.LATEST);
    assertArrayEquals(Files.readAllBytes(dir.resolve("m.idx")), Files.readAllBytes(dir.resolve("rebuilt.idx")));
  }

  @Test
  void damageIsReported(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 4, 4);
    ZpaqArchive a = archive(dir, "d.zpaq");
    a.add(AddOptions.of("src"));
    assertTrue(a.extract(ExtractOptions.DEFAULT.withTest(true)).ok());
    byte[] b = Files.readAllBytes(dir.resolve("d.zpaq"));
    b[b.length / 3] ^= 0x55;
    Files.write(dir.resolve("d.zpaq"), b);
    List<Event> events = Collections.synchronizedList(new ArrayList<>());
    ExtractResult t = a.withListener(events::add).extract(ExtractOptions.DEFAULT.withTest(true));
    assertFalse(t.ok());
    assertTrue(events.stream().anyMatch(e -> e instanceof Event.ExtractFailed), events.toString());
    assertTrue(events.stream().anyMatch(e -> e instanceof Event.Problem));
  }

  @Test
  void failures(@TempDir Path dir) throws Exception {
    List<Event> events = new ArrayList<>();
    var e = assertThrows(ArchiveException.class,
        () -> archive(dir, "missing.zpaq").withListener(events::add).read(ReadOptions.LATEST));
    assertEquals("missing.zpaq", e.getMessage());
    assertTrue(events.stream().anyMatch(x -> x instanceof Event.FileError f
        && f.cause() instanceof NoSuchFileException), events.toString());
    Files.writeString(dir.resolve("text.zpaq"), "just some text, not an archive");
    assertThrows(ArchiveException.class, () -> archive(dir, "text.zpaq").read(ReadOptions.LATEST));

    fill(dir.resolve("src"), 5, 2);
    ZpaqArchive a = archive(dir, "k.zpaq").withKey("right");
    a.add(AddOptions.of("src"));
    var w = assertThrows(ArchiveException.class, () -> a.withKey("wrong").read(ReadOptions.LATEST));
    assertEquals("password incorrect", w.getMessage());

    // a local file that can't be read is reported and counted
    List<Event> ev = new ArrayList<>();
    AddResult r = archive(dir, "u.zpaq").withListener(ev::add).add(AddOptions.of("src", "nope"));
    assertEquals(0, r.unreadable());  // missing roots are reported by the scan
    assertTrue(ev.stream().anyMatch(x -> x instanceof Event.FileError f && f.name().equals("nope")));
  }

  @Test
  void aFailedUpdateLeavesTheArchiveAsItWas(@TempDir Path dir) throws Exception {
    fill(dir.resolve("src"), 6, 4);
    LocalFsStorage local = new LocalFsStorage(dir);
    ZpaqArchive.of(local, "f.zpaq").withDirectory(dir).add(AddOptions.of("src/f0.bin"));
    byte[] before = Files.readAllBytes(dir.resolve("f.zpaq"));
    // a storage whose writes fail after a while
    Storage failing = new Storage() {
      @Override
      public java.io.InputStream read(String name) throws IOException {
        return local.read(name);
      }

      @Override
      public java.io.OutputStream write(String name) throws IOException {
        return local.write(name);
      }

      @Override
      public void delete(String name) throws IOException {
        local.delete(name);
      }

      @Override
      public Set<Capability> capabilities() {
        return local.capabilities();
      }

      @Override
      public boolean exists(String name) throws IOException {
        return local.exists(name);
      }

      @Override
      public long length(String name) throws IOException {
        return local.length(name);
      }

      @Override
      public RandomFile open(String name, boolean write, boolean create) throws IOException {
        RandomFile f = local.open(name, write, create);
        return new RandomFile() {
          long written;

          @Override
          public int read(byte[] buf, int off, int len) throws IOException {
            return f.read(buf, off, len);
          }

          @Override
          public void write(byte[] buf, int off, int len) throws IOException {
            if ((written += len) > 200_000) throw new IOException("disk full");
            f.write(buf, off, len);
          }

          @Override
          public void seek(long pos) throws IOException {
            f.seek(pos);
          }

          @Override
          public long length() throws IOException {
            return f.length();
          }

          @Override
          public void setLength(long len) throws IOException {
            f.setLength(len);
          }

          @Override
          public void close() throws IOException {
            f.close();
          }
        };
      }
    };
    var e = assertThrows(IOException.class,
        () -> ZpaqArchive.of(failing, "f.zpaq").withDirectory(dir).add(AddOptions.of("src")));
    assertTrue(e.getMessage().contains("disk full") || String.valueOf(e.getCause()).contains("disk full"),
        e.toString());
    assertArrayEquals(before, Files.readAllBytes(dir.resolve("f.zpaq")));
  }

  @Test
  void listenerFailureFailsTheOperation(@TempDir Path dir) throws Exception {
    fill(dir.resolve("src"), 7, 2);
    int[] n = {0};
    ZpaqArchive a = archive(dir, "l.zpaq").withListener(ev -> {
      if (++n[0] == 3) throw new IllegalStateException("listener broke");
    });
    var ex = assertThrows(IllegalStateException.class, () -> a.add(AddOptions.of("src")));
    assertEquals("listener broke", ex.getMessage());
    assertEquals(3, n[0], "no events after the failure");
  }

  @Test
  void archivesAreIndependent(@TempDir Path dir) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        final int k = i;
        jobs.add(pool.submit(() -> {
          Path d = Files.createDirectories(dir.resolve("p" + k));
          fill(d.resolve("src"), 100 + k, 3);
          ZpaqArchive a = archive(d, "p.zpaq").withKey(k % 2 == 0 ? "key" + k : null);
          a.add(AddOptions.of("src").withMethod("" + (k % 4)));
          assertEquals(0, extractTo(a, ReadOptions.LATEST, "out").failed());
          assertSameTree(d.resolve("src"), d.resolve("out/src"));
          return null;
        }));
      }
      for (Future<?> j : jobs) j.get();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void namesThatAreNotUtf8(@TempDir Path dir) {
    byte[] latin1 = {'c', 'a', 'f', (byte) 0xE9};
    String s = Names.decode(latin1);
    assertArrayEquals(latin1, Names.encode(s));
    assertEquals("ünï", Names.decode(Names.encode("ünï")));
    assertEquals("x/y", ExtractOptions.under("out").apply("/x/y").substring(4));
    assertNull(ExtractOptions.under("out").apply("a/../../etc"));
  }
}
