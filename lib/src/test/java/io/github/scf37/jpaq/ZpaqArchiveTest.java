package io.github.scf37.jpaq;

import static io.github.scf37.jpaq.TestTrees.assertSameTree;
import static io.github.scf37.jpaq.TestTrees.content;
import static io.github.scf37.jpaq.TestTrees.fill;
import static io.github.scf37.jpaq.TestTrees.readAll;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The library API on local archives: streams in and out, updates, versions, failures. */
class ZpaqArchiveTest {
  private static final Instant T1 = Instant.parse("2026-01-02T03:04:05Z");
  private static final Instant T2 = Instant.parse("2026-02-03T04:05:06Z");

  private static InputStream in(byte[] b) {
    return new ByteArrayInputStream(b);
  }

  /** Name -> content of some files of different kinds, with a duplicate and an empty one. */
  private static Map<String, byte[]> contents(long seed) {
    Random r = new Random(seed);
    Map<String, byte[]> m = new LinkedHashMap<>();
    m.put("db/dump.sql", content(r, 1, 3_000_000));
    m.put("db/blob.bin", content(r, 0, 700_000));
    m.put("copy/of/blob.bin", m.get("db/blob.bin"));
    m.put("empty.txt", new byte[0]);
    m.put("ünïcödé/名前.txt", "text".getBytes(StandardCharsets.UTF_8));
    for (int i = 0; i < 50; i++) m.put("small/s" + i, ("small file " + i).getBytes());
    return m;
  }

  @Test
  void streamsInAndOut(@TempDir Path dir) throws Exception {
    ZpaqArchive a = ZpaqArchive.of(dir.resolve("s.zpaq")).withThreads(3);
    Map<String, byte[]> files = contents(1);
    UpdateResult r;
    try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withMethod("2").withDate(T1))) {
      assertEquals(T1, u.date());
      for (var e : files.entrySet()) u.add(e.getKey(), in(e.getValue()));
      u.add("dated.txt", in("x".getBytes()), T2, Attributes.unix(0100640));
      u.addDirectory("emptydir", null, Attributes.NONE);
      r = u.commit();
    }
    assertTrue(r.ok() && r.changed(), r.toString());
    assertEquals(1, r.version());
    assertEquals(files.size() + 2, r.added());
    long total = files.values().stream().mapToLong(b -> b.length).sum() + 1;
    assertEquals(total, r.inputBytes());
    assertTrue(r.dedupedBytes() <= total - 700_000, "the copy is deduplicated: " + r);

    try (ArchiveReader rd = a.read()) {
      assertEquals(List.of(new ArchiveVersion(1, T1, files.size() + 2, 0)), rd.versions());
      for (var e : files.entrySet()) {
        ArchiveEntry en = rd.entry(e.getKey()).orElseThrow();
        assertEquals(e.getValue().length, en.size(), e.getKey());
        assertEquals(T1, en.lastModified());
        assertArrayEquals(e.getValue(), readAll(rd.open(en)), e.getKey());
      }
      ArchiveEntry d = rd.entry("dated.txt").orElseThrow();
      assertEquals(T2, d.lastModified());
      assertEquals(0100640, d.attributes().unixMode().orElseThrow());
      assertTrue(rd.entry("emptydir/").orElseThrow().isDirectory());
      assertThrows(NoSuchFileException.class, () -> rd.open("nope"));
      assertThrows(IOException.class, () -> rd.open("emptydir/"));

      // everything in storage order
      Map<String, byte[]> seen = new LinkedHashMap<>();
      rd.forEach((e, content) -> seen.put(e.name(), content.readAllBytes()));
      assertEquals(files.size() + 1, seen.size());
      for (var e : files.entrySet()) assertArrayEquals(e.getValue(), seen.get(e.getKey()));

      // byte by byte reads and a stream that is closed early
      try (InputStream s = rd.open("small/s7")) {
        assertEquals('s', s.read());
        assertEquals('m', s.read());
      }
    }

    // extraction gives the same files
    ExtractResult x = a.extract(dir.resolve("out"));
    assertTrue(x.ok(), x.toString());
    for (var e : files.entrySet())
      assertArrayEquals(e.getValue(), Files.readAllBytes(dir.resolve("out").resolve(e.getKey())));
    assertTrue(Files.isDirectory(dir.resolve("out/emptydir")));
    assertTrue(a.verify().ok());
  }

  @Test
  void versionsReplaceAndDelete(@TempDir Path dir) throws Exception {
    ZpaqArchive a = ZpaqArchive.of(dir.resolve("v.zpaq"));
    try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withDate(T1))) {
      u.add("a.txt", in("one".getBytes()));
      u.add("dir/b.txt", in("bee".getBytes()));
      u.add("dir/c.txt", in("sea".getBytes()));
      u.add("keep.txt", in("kept".getBytes()));
      u.commit();
    }
    UpdateResult r2;
    try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withDate(T2))) {
      u.add("a.txt", in("two".getBytes()));
      assertEquals(2, u.delete("dir"));
      u.add("new.txt", in("first".getBytes()));
      u.add("new.txt", in("second".getBytes()));  // the last one counts
      u.add("gone.txt", in("never".getBytes()));
      assertEquals(1, u.delete("gone.txt"));     // added in this update and dropped again
      r2 = u.commit();
    }
    assertEquals(2, r2.version());
    assertEquals(2, r2.removed(), r2.toString());

    try (ArchiveReader r = a.read()) {
      assertEquals(List.of("a.txt", "keep.txt", "new.txt"),
          r.entries().stream().map(ArchiveEntry::name).toList());
      assertEquals("two", new String(readAll(r.open("a.txt"))));
      assertEquals("second", new String(readAll(r.open("new.txt"))));
      assertEquals("kept", new String(readAll(r.open("keep.txt"))));
      assertEquals(2, r.versions().size());
    }
    for (ReadOptions old : List.of(ReadOptions.LATEST.withVersion(1), ReadOptions.LATEST.withVersion(-1),
        ReadOptions.LATEST.withDate(T1.plusSeconds(60)))) {
      try (ArchiveReader r = a.read(old)) {
        assertEquals(List.of("a.txt", "dir/b.txt", "dir/c.txt", "keep.txt"),
            r.entries().stream().map(ArchiveEntry::name).toList(), old.toString());
        assertEquals("one", new String(readAll(r.open("a.txt"))));
      }
    }
    // a later update that changes nothing
    try (ArchiveUpdate u = a.update()) {
      UpdateResult r3 = u.commit();
      assertFalse(r3.changed());
      assertEquals(2, r3.version());
    }
    try (ArchiveReader r = a.read()) {
      assertEquals(2, r.versions().size());
    }
    // a date before the latest version moves to just after it
    try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withDate(T1))) {
      assertEquals(T2.plusSeconds(1), u.date());
      u.add("late.txt", in("late".getBytes()));
      UpdateResult r4 = u.commit();
      assertTrue(r4.ok(), r4.toString());
      assertEquals(T2.plusSeconds(1), r4.date());
    }
  }

  @Test
  void addTreeKeepsTheArchiveLikeTheDirectory(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 5, 4);
    Files.writeString(src.resolve("skip.tmp"), "temporary");
    ZpaqArchive a = ZpaqArchive.of(dir.resolve("t.zpaq"));
    UpdateOptions o = UpdateOptions.DEFAULT.withExclude("*.tmp");
    try (ArchiveUpdate u = a.update(o.withDate(T1))) {
      u.addTree(src, "backup/src");
      u.add("outside.txt", in("not in the tree".getBytes()));
      u.commit();
    }
    try (ArchiveReader r = a.read()) {
      assertTrue(r.entry("backup/src/").isPresent());
      assertTrue(r.entry("backup/src/sub/deep/t.txt").isPresent());
      assertTrue(r.entry("backup/src/skip.tmp").isEmpty());
      assertEquals(Files.getLastModifiedTime(src.resolve("f0.bin")).toInstant().getEpochSecond(),
          r.entry("backup/src/f0.bin").orElseThrow().lastModified().getEpochSecond());
    }

    // nothing changed: nothing added
    List<Line> lines = new ArrayList<>();
    try (ArchiveUpdate u = a.withListener(lines::add).update(o.withDate(T2))) {
      u.addTree(src, "backup/src");
      assertFalse(u.commit().changed());
    }
    assertTrue(lines.stream().noneMatch(l -> l instanceof InfoLine.FileAdded), lines.toString());

    // a changed, a deleted and a new file
    Files.writeString(src.resolve("f1.bin"), "changed");
    Files.delete(src.resolve("f2.bin"));
    Files.writeString(src.resolve("sub/new.txt"), "new");
    UpdateResult r2;
    try (ArchiveUpdate u = a.update(o.withDate(T2))) {
      u.addTree(src, "backup/src");
      r2 = u.commit();
    }
    assertEquals(1, r2.removed(), r2.toString());
    assertTrue(r2.added() >= 2, r2.toString());  // f1.bin, sub/new.txt (and changed directories)
    try (ArchiveReader r = a.read()) {
      assertTrue(r.entry("backup/src/f2.bin").isEmpty());
      assertEquals("changed", new String(readAll(r.open("backup/src/f1.bin"))));
      assertTrue(r.entry("outside.txt").isPresent(), "entries outside the tree are kept");
    }
    a.extract(dir.resolve("out"), ExtractOptions.DEFAULT.withInclude("backup/src"));
    Files.delete(src.resolve("skip.tmp"));
    assertSameTree(src, dir.resolve("out/backup/src"));
    assertFalse(Files.exists(dir.resolve("out/outside.txt")));

    // a missing directory is an error, not a reason to delete everything
    try (ArchiveUpdate u = a.update()) {
      assertThrows(NoSuchFileException.class, () -> u.addTree(dir.resolve("nope"), "backup/src"));
      assertThrows(NoSuchFileException.class, () -> u.addFile("x", dir.resolve("nope")));
    }
  }

  @Test
  void abandonedUpdateLeavesTheArchiveAsItWas(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("a.zpaq");
    ZpaqArchive a = ZpaqArchive.of(f).withKey("k");
    try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withDate(T1))) {
      u.add("x", in(content(new Random(1), 0, 500_000)));
      u.commit();
    }
    byte[] before = Files.readAllBytes(f);
    try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withDate(T2))) {
      u.add("y", in(content(new Random(2), 0, 5_000_000)));
      u.delete("x");
      // no commit
    }
    assertArrayEquals(before, Files.readAllBytes(f));

    // a new archive that is abandoned does not exist
    try (ArchiveUpdate u = ZpaqArchive.of(dir.resolve("new.zpaq")).update()) {
      u.add("z", in(new byte[100_000]));
    }
    assertFalse(Files.exists(dir.resolve("new.zpaq")));

    // multi part archive with an index: neither the part nor the index change
    ZpaqArchive m = ZpaqArchive.of(dir.resolve("m???.zpaq"));
    UpdateOptions mi = UpdateOptions.DEFAULT.withIndex(dir.resolve("m.idx").toString());
    try (ArchiveUpdate u = m.update(mi)) {
      u.add("x", in("x".getBytes()));
      u.commit();
    }
    byte[] idx = Files.readAllBytes(dir.resolve("m.idx"));
    try (ArchiveUpdate u = m.update(mi)) {
      u.add("y", in("y".getBytes()));
    }
    assertArrayEquals(idx, Files.readAllBytes(dir.resolve("m.idx")));
    assertFalse(Files.exists(dir.resolve("m002.zpaq")));
  }

  @Test
  void aFailingStreamFailsTheUpdate(@TempDir Path dir) throws Exception {
    Path f = dir.resolve("a.zpaq");
    ZpaqArchive a = ZpaqArchive.of(f);
    try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withDate(T1))) {
      u.add("x", in("x".getBytes()));
      u.commit();
    }
    byte[] before = Files.readAllBytes(f);
    InputStream broken = new InputStream() {
      int n;

      @Override
      public int read() throws IOException {
        if (++n > 300_000) throw new IOException("disk on fire");
        return n & 255;
      }
    };
    try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withDate(T2))) {
      IOException e = assertThrows(IOException.class, () -> u.add("y", broken));
      assertEquals("disk on fire", e.getMessage());
      assertThrows(IllegalStateException.class, () -> u.add("z", in(new byte[1])));
      assertThrows(IllegalStateException.class, u::commit);
    }
    assertArrayEquals(before, Files.readAllBytes(f));
  }

  @Test
  void archivesAreIndependent(@TempDir Path dir) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<?>> jobs = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        final int n = i;
        jobs.add(pool.submit(() -> {
          Map<String, byte[]> files = contents(100 + n);
          ZpaqArchive a = ZpaqArchive.of(dir.resolve("p" + n + ".zpaq")).withThreads(2)
              .withKey(n % 2 == 0 ? "key" + n : null);
          try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withMethod("" + (n % 4)))) {
            for (var e : files.entrySet()) u.add(e.getKey(), in(e.getValue()));
            u.commit();
          }
          try (ArchiveReader r = a.read()) {
            for (var e : files.entrySet()) assertArrayEquals(e.getValue(), readAll(r.open(e.getKey())));
          }
          return null;
        }));
      }
      for (Future<?> j : jobs) j.get();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void namesThatLeaveTheOutputDirectoryAreNotExtracted(@TempDir Path dir) throws Exception {
    ZpaqArchive a = ZpaqArchive.of(dir.resolve("evil.zpaq"));
    try (ArchiveUpdate u = a.update()) {
      u.add("../escaped.txt", in("bad".getBytes()));
      u.add("a/../../escaped2.txt", in("bad".getBytes()));
      u.add("/abs/ok.txt", in("fine".getBytes()));
      u.add("good.txt", in("fine".getBytes()));
      u.commit();
    }
    Path out = dir.resolve("deep/out");
    ExtractResult x = a.extract(out);
    assertFalse(x.ok());
    assertEquals(2, x.problems().size(), x.toString());
    assertFalse(Files.exists(dir.resolve("deep/escaped.txt")));
    assertFalse(Files.exists(dir.resolve("escaped2.txt")));
    assertEquals("fine", Files.readString(out.resolve("good.txt")));
    assertEquals("fine", Files.readString(out.resolve("abs/ok.txt")));
  }

  @Test
  void failures(@TempDir Path dir) throws Exception {
    assertThrows(NoSuchFileException.class, () -> ZpaqArchive.of(dir.resolve("missing.zpaq")).read());
    Files.writeString(dir.resolve("text.zpaq"), "just some text, not an archive");
    assertThrows(ArchiveException.class, () -> ZpaqArchive.of(dir.resolve("text.zpaq")).read());

    ZpaqArchive a = ZpaqArchive.of(dir.resolve("a.zpaq")).withKey("right");
    try (ArchiveUpdate u = a.update()) {
      u.add("x", in(content(new Random(3), 1, 2_000_000)));
      assertThrows(IllegalArgumentException.class, () -> u.add("dir/", in(new byte[0])));
      assertThrows(IllegalArgumentException.class, () -> u.add("", in(new byte[0])));
      u.commit();
    }
    var e = assertThrows(ArchiveException.class, () -> a.withKey("wrong").read());
    assertTrue(e.getMessage().contains("password"), e.getMessage());

    // damaged data: the file list is fine, the content fails to read and to extract
    byte[] b = Files.readAllBytes(dir.resolve("a.zpaq"));
    b[b.length / 3] ^= 0x55;
    Files.write(dir.resolve("a.zpaq"), b);
    try (ArchiveReader r = a.read()) {
      assertTrue(r.entry("x").isPresent());
      IOException ex = assertThrows(IOException.class, () -> readAll(r.open("x")));
      assertTrue(ex.getMessage().startsWith("x: "), ex.getMessage());
    }
    ExtractResult x = a.extract(dir.resolve("out"));
    assertFalse(x.ok());
    assertEquals(1, x.failed(), x.toString());
    assertFalse(a.verify().ok());

    assertThrows(IllegalArgumentException.class,
        () -> UpdateOptions.DEFAULT.withMethod("s1"));
    assertThrows(IllegalArgumentException.class,
        () -> a.read(ReadOptions.LATEST.withVersion(1).withDate(T1)));
  }

  @Test
  void listenerSeesTheLinesAndItsFailureIsThrown(@TempDir Path dir) throws Exception {
    List<Line> lines = new ArrayList<>();
    ZpaqArchive a = ZpaqArchive.of(dir.resolve("l.zpaq")).withListener(lines::add);
    try (ArchiveUpdate u = a.update()) {
      u.add("ünï.txt", in("x".getBytes()));
      u.commit();
    }
    assertTrue(lines.stream().anyMatch(l -> l instanceof InfoLine.FileAdded f
        && f.name().equals("ünï.txt")), lines.toString());
    assertTrue(lines.stream().anyMatch(l -> l instanceof InfoLine.AddSummary), lines.toString());

    int[] n = {0};
    ZpaqArchive b = a.withListener(l -> {
      if (++n[0] == 2) throw new IllegalStateException("listener broke");
    });
    var ex = assertThrows(IllegalStateException.class, () -> b.extract(dir.resolve("out")));
    assertEquals("listener broke", ex.getMessage());
    assertEquals(2, n[0]);
    assertTrue(Files.exists(dir.resolve("out/ünï.txt")), "the operation completed");
  }
}
