package io.github.scf37.jpaq;

import static io.github.scf37.jpaq.TestTrees.assertSameTree;
import static io.github.scf37.jpaq.TestTrees.fill;
import static org.junit.jupiter.api.Assertions.*;

import io.github.scf37.jpaq.ZpaqArchive.ArchiveSelector;
import io.github.scf37.jpaq.ZpaqArchive.AddResult;
import io.github.scf37.jpaq.ZpaqArchive.AddBuilder;
import io.github.scf37.jpaq.ZpaqArchive.ExtractResult;
import io.github.scf37.jpaq.ZpaqArchive.LocalFile;
import io.github.scf37.jpaq.ZpaqArchive.Option;
import io.github.scf37.jpaq.Storage.Capability;
import io.github.scf37.jpaq.Storage.RandomFile;
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
import java.util.function.Consumer;
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
    return ZpaqArchive.of(dir.resolve(name), Option.directory(dir), Option.threads(2),
        Option.clock(Clock.fixed(T1, ZoneOffset.UTC)));
  }

  private static List<String> names(ArchiveContents c) {
    return c.entries().stream().map(ArchiveContents.Entry::name).toList();
  }

  private static ExtractResult extractTo(ZpaqArchive a, Consumer<ArchiveSelector<?>> select,
      String out) throws IOException {
    return a.extract(x -> select.accept(x.under(out)));
  }

  private static final Consumer<ArchiveSelector<?>> LATEST = s -> { };

  @Test
  void versions(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 1, 4);
    ZpaqArchive a = archive(dir, "a.zpaq");
    AddResult r1 = a.add(b -> b.files("src"));
    assertEquals(8, r1.added(), r1.toString());  // 4 files, t.txt, src/ sub/ sub/deep/
    assertTrue(r1.inputBytes() > 0 && r1.addedBytes() > 0 && r1.archiveBytes() == r1.addedBytes());

    Files.writeString(src.resolve("f1.bin"), "changed");
    Files.delete(src.resolve("f2.bin"));
    Files.writeString(src.resolve("new.txt"), "new");
    AddResult r2 = a.add(b -> b.files("src").date(T2));
    assertEquals(1, r2.removed(), r2.toString());
    assertFalse(a.add(b -> b.files("src").date(T2.plusSeconds(10))).changed());

    ArchiveContents c = a.read();
    assertEquals(2, c.versions().size());
    assertEquals(T1, c.versions().get(0).time());
    assertEquals(T2, c.versions().get(1).time());
    assertTrue(names(c).contains("src/new.txt"));
    assertFalse(names(c).contains("src/f2.bin"));
    assertTrue(names(c).contains("src/sub/"));
    List<Consumer<ArchiveSelector<?>>> olds = List.of(s -> s.version(1), s -> s.version(-1),
        s -> s.until(T1.plusSeconds(60)), s -> s.until(T2).version(1));
    for (int i = 0; i < olds.size(); i++) {
      ArchiveContents o = a.read(olds.get(i));
      assertEquals(1, o.versions().size(), "selection " + i);
      assertTrue(names(o).contains("src/f2.bin"), "selection " + i);
    }
    assertTrue(a.read(r -> r.version(0)).entries().isEmpty());

    // every version of every file
    ArchiveContents all = a.read(r -> r.allVersions(4));
    assertTrue(names(all).contains("0001/src/f2.bin"));
    assertTrue(all.entries().stream().anyMatch(e -> e.name().equals("0002/src/f2.bin") && e.isDeleted()));
    assertEquals(0, extractTo(a, s -> s.allVersions(4), "all").failed());
    assertTrue(Files.exists(dir.resolve("all/0001/src/f2.bin")));
    assertEquals("changed", Files.readString(dir.resolve("all/0002/src/f1.bin")));
    assertTrue(Files.isDirectory(dir.resolve("all/0002")));

    // latest
    ExtractResult x = extractTo(a, LATEST, "out");
    assertTrue(x.ok() && x.files() == 5, x.toString());
    assertSameTree(src, dir.resolve("out/src"));
    // again: everything exists and is skipped, or is identical with overwrite
    assertEquals(8, extractTo(a, LATEST, "out").skipped());  // with 3 directories
    assertEquals(5, a.extract(x2 -> x2.under("out").overwrite(true)).skipped());
  }

  @Test
  void namesFiltersAndScope(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 2, 3);
    Files.writeString(src.resolve("skip.tmp"), "tmp");
    Files.createDirectories(src.resolve("cache"));
    Files.writeString(src.resolve("cache/big.bin"), "cache");
    ZpaqArchive a = archive(dir, "n.zpaq");
    Consumer<AddBuilder> o = b -> b.files("src")
        .archiveName(n -> "backup/" + n)
        .prune(n -> n.equals("src/cache"))
        .accept(n -> !n.endsWith(".tmp"))
        .scope(n -> n.startsWith("backup/src"));
    a.add(o);
    List<String> n1 = names(a.read());
    assertTrue(n1.contains("backup/src/f0.bin"));
    assertFalse(n1.stream().anyMatch(n -> n.contains("tmp") || n.contains("cache")), n1.toString());

    // entries out of scope are not deleted
    try (var unused = Files.newOutputStream(dir.resolve("other.txt"))) { }
    a.add(b -> b.files("other.txt").date(T2));
    Files.delete(src.resolve("f0.bin"));
    AddResult r = a.add(o.andThen(b -> b.date(T2.plusSeconds(1))));
    assertEquals(1, r.removed());
    List<String> n2 = names(a.read(s -> s.select(n -> !n.startsWith("backup/src/sub"))));
    assertTrue(n2.contains("other.txt"));
    assertFalse(n2.contains("backup/src/f0.bin"));
    assertFalse(n2.stream().anyMatch(n -> n.startsWith("backup/src/sub")), "not selected");

    // extract renamed and only some
    a.extract(x -> x.select(n -> n.endsWith("f1.bin"))
        .output(n -> "restored/" + n.substring(n.lastIndexOf('/') + 1)));
    assertArrayEquals(Files.readAllBytes(src.resolve("f1.bin")),
        Files.readAllBytes(dir.resolve("restored/f1.bin")));
    assertEquals(1, TestTrees.files(dir.resolve("restored")).size());

    // a local scan sees what an update would see
    List<LocalFile> local = a.scan(s -> s.files("src").prune(n -> n.equals("src/cache")));
    assertTrue(local.stream().anyMatch(f -> f.name().equals("src/sub/deep/t.txt") && f.size() == 25000));
    assertTrue(local.stream().noneMatch(f -> f.name().startsWith("src/cache")));
    assertTrue(local.stream().anyMatch(f -> f.name().equals("src/") && f.isDirectory()));
  }

  @Test
  void rollbackRepackCopyIndex(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 3, 3);
    ZpaqArchive a = archive(dir, "r.zpaq").withOptions(Option.key("k1"));
    a.add(b -> b.files("src"));
    Files.writeString(src.resolve("f0.bin"), "v2");
    a.add(b -> b.files("src").date(T2));
    Files.writeString(src.resolve("f0.bin"), "v3");
    // replace version 2 by a new one
    a.add(b -> b.files("src").date(T2.plusSeconds(5)).rollback(1));
    ArchiveContents c = a.read();
    assertEquals(2, c.versions().size());
    assertTrue(c.sameContent(c.entries().stream().filter(e -> e.name().equals("src/f0.bin"))
        .findFirst().orElseThrow(), "src/f0.bin"));

    // repack some files under another key, copy everything under another key
    a.repack("part.zpaq", p -> p.select(n -> n.startsWith("src/sub")).key("k2"));
    ZpaqArchive part = archive(dir, "part.zpaq").withOptions(Option.key("k2"));
    assertTrue(names(part.read()).stream().allMatch(n -> n.startsWith("src/sub")));
    assertThrows(ArchiveException.class, () -> a.repack("part.zpaq", p -> { }));
    a.copy("copy.zpaq", cp -> { });
    assertEquals(names(c), names(archive(dir, "copy.zpaq").read()));

    // an index, then updates of a multi part archive through it
    ZpaqArchive m = archive(dir, "m???.zpaq");
    m.add(b -> b.files("src").index("m.idx"));
    Files.writeString(src.resolve("f1.bin"), "changed");
    m.add(b -> b.files("src").index("m.idx").date(T2));
    assertTrue(Files.exists(dir.resolve("m002.zpaq")));
    ArchiveContents fromIndex = m.read(r -> r.index("m.idx"));
    assertEquals(names(m.read()), names(fromIndex));
    assertTrue(fromIndex.dataSize() > fromIndex.storedDataSize());
    m.writeIndex("rebuilt.idx", i -> { });
    assertArrayEquals(Files.readAllBytes(dir.resolve("m.idx")), Files.readAllBytes(dir.resolve("rebuilt.idx")));
  }

  @Test
  void damageIsReported(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("src");
    fill(src, 4, 4);
    ZpaqArchive a = archive(dir, "d.zpaq");
    a.add(b -> b.files("src"));
    assertTrue(a.extract(x -> x.test(true)).ok());
    byte[] b = Files.readAllBytes(dir.resolve("d.zpaq"));
    b[b.length / 3] ^= 0x55;
    Files.write(dir.resolve("d.zpaq"), b);
    List<Event> events = Collections.synchronizedList(new ArrayList<>());
    ExtractResult t = a.withOptions(Option.listener(events::add)).extract(x -> x.test(true));
    assertFalse(t.ok());
    assertTrue(events.stream().anyMatch(e -> e instanceof Event.ExtractFailed), events.toString());
    assertTrue(events.stream().anyMatch(e -> e instanceof Event.Problem));
  }

  @Test
  void failures(@TempDir Path dir) throws Exception {
    List<Event> events = new ArrayList<>();
    var e = assertThrows(ArchiveException.class,
        () -> archive(dir, "missing.zpaq").withOptions(Option.listener(events::add)).read());
    assertEquals("missing.zpaq", e.getMessage());
    assertTrue(events.stream().anyMatch(x -> x instanceof Event.FileError f
        && f.cause() instanceof NoSuchFileException), events.toString());
    Files.writeString(dir.resolve("text.zpaq"), "just some text, not an archive");
    assertThrows(ArchiveException.class, () -> archive(dir, "text.zpaq").read());

    fill(dir.resolve("src"), 5, 2);
    ZpaqArchive a = archive(dir, "k.zpaq").withOptions(Option.key("right"));
    a.add(b -> b.files("src"));
    var w = assertThrows(ArchiveException.class, () -> a.withOptions(Option.key("wrong")).read());
    assertEquals("password incorrect", w.getMessage());

    // a local file that can't be read is reported and counted
    List<Event> ev = new ArrayList<>();
    AddResult r = archive(dir, "u.zpaq").withOptions(Option.listener(ev::add))
        .add(b -> b.files("src", "nope"));
    assertEquals(0, r.unreadable());  // missing roots are reported by the scan
    assertTrue(ev.stream().anyMatch(x -> x instanceof Event.FileError f && f.name().equals("nope")));
  }

  @Test
  void aFailedUpdateLeavesTheArchiveAsItWas(@TempDir Path dir) throws Exception {
    fill(dir.resolve("src"), 6, 4);
    Storage local = Storage.ofDirectory(dir);
    ZpaqArchive.of(local, "f.zpaq", Option.directory(dir)).add(b -> b.files("src/f0.bin"));
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
        () -> ZpaqArchive.of(failing, "f.zpaq", Option.directory(dir)).add(b -> b.files("src")));
    assertTrue(e.getMessage().contains("disk full") || String.valueOf(e.getCause()).contains("disk full"),
        e.toString());
    assertArrayEquals(before, Files.readAllBytes(dir.resolve("f.zpaq")));
  }

  @Test
  void listenerFailureFailsTheOperation(@TempDir Path dir) throws Exception {
    fill(dir.resolve("src"), 7, 2);
    int[] n = {0};
    ZpaqArchive a = archive(dir, "l.zpaq").withOptions(Option.listener(ev -> {
      if (++n[0] == 3) throw new IllegalStateException("listener broke");
    }));
    var ex = assertThrows(IllegalStateException.class, () -> a.add(b -> b.files("src")));
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
          ZpaqArchive a = archive(d, "p.zpaq").withOptions(Option.key(k % 2 == 0 ? "key" + k : null));
          a.add(b -> b.files("src").method("" + (k % 4)));
          assertEquals(0, extractTo(a, LATEST, "out").failed());
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
    String s = ZpaqArchive.decodeName(latin1);
    assertArrayEquals(latin1, ZpaqArchive.encodeName(s));
    assertEquals("ünï", ZpaqArchive.decodeName(ZpaqArchive.encodeName("ünï")));
    assertEquals("out/x/y", ArchiveImpl.underDirectory("out").apply("/x/y"));
    assertNull(ArchiveImpl.underDirectory("out").apply("a/../../etc"));
  }
}
