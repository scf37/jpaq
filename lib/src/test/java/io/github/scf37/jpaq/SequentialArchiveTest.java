package io.github.scf37.jpaq;

import static io.github.scf37.jpaq.TestTrees.assertSameTree;
import static io.github.scf37.jpaq.TestTrees.fill;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sequential (jpaq) format on storages that can only stream. */
class SequentialArchiveTest {
  /** The archive arc???.jpaq in st; local names relative to dir. */
  static ZpaqArchive seq(Storage st, Path dir) {
    return ZpaqArchive.of(st, "arc???.jpaq").withFormat(Format.SEQUENTIAL).withThreads(2)
        .withDirectory(dir);
  }

  /** Adds the local tree "src" with the given index and new index. */
  static AddResult backup(ZpaqArchive a, String index, String indexOut) throws IOException {
    return a.add(AddOptions.of("src").withIndex(index).withIndexOut(indexOut));
  }

  /** Extracts everything under the local directory out. */
  static ExtractResult restore(ZpaqArchive a, ReadOptions ro, String out) throws IOException {
    return a.extract(new ExtractOptions(ro, ExtractOptions.under(out), false, false));
  }

  static ArchiveEntry entry(ArchiveContents c, String name) {
    return c.entries().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void roundTripOnStreamOnlyStorage(@TempDir Path dir) throws Exception {
    MemStorage st = new MemStorage();  // no capabilities: read, write, delete only
    Path src = dir.resolve("src");
    fill(src, 1, 6);
    ZpaqArchive a = seq(st, dir);

    AddResult r1 = backup(a, null, "idx1.jpaq");
    assertTrue(r1.changed() && r1.unreadable() == 0, r1.toString());
    assertEquals(List.of("arc001.jpaq", "idx1.jpaq"), List.copyOf(st.files.keySet()));
    for (byte[] b : st.files.values()) assertFalse(TestTrees.stockFindsBlock(b), "zpaq must not see any block");

    // update: change one file, add one (the directory keeps its date)
    var srcTime = Files.getLastModifiedTime(src);
    Files.write(src.resolve("f1.bin"), "changed".repeat(30000).getBytes());
    Files.writeString(src.resolve("new.txt"), "new file".repeat(1000));
    Files.setLastModifiedTime(src, srcTime);
    AddResult r2 = backup(a, "idx1.jpaq", "idx2.jpaq");
    assertEquals(2, r2.added(), r2.toString());
    assertEquals(List.of("arc001.jpaq", "arc002.jpaq", "idx1.jpaq", "idx2.jpaq"),
        List.copyOf(st.files.keySet()));
    assertArrayEquals(st.files.get("idx1.jpaq"),
        Arrays.copyOf(st.files.get("idx2.jpaq"), st.files.get("idx1.jpaq").length),
        "the new index starts with the old one");

    // an unchanged tree adds nothing
    int before = st.files.size();
    assertFalse(backup(a, "idx2.jpaq", "idx3.jpaq").changed());
    assertEquals(before, st.files.size(), "nothing written for an empty update");

    // only streaming operations were used
    assertTrue(st.log.stream().noneMatch(s -> s.startsWith("exists")), st.log.toString());

    // list and extract from the index; tomes are read through streams
    ReadOptions ro = ReadOptions.LATEST.withIndex("idx2.jpaq");
    ArchiveContents c = a.read(ro);
    assertEquals(2, c.versions().size());
    assertEquals(8000, entry(c, "src/new.txt").size());
    assertTrue(c.sameContent(entry(c, "src/f1.bin"), "src/f1.bin"));
    assertEquals(0, restore(a, ro, "out").failed());
    assertSameTree(src, dir.resolve("out/src"));
  }

  /** Two versions with an optional password; returns the storage. */
  static MemStorage twoVersions(Path dir, String key) throws Exception {
    MemStorage st = new MemStorage(Capability.PROBE);
    Path src = dir.resolve("src");
    fill(src, 7, 5);
    ZpaqArchive a = seq(st, dir).withKey(key);
    assertEquals(0, backup(a, null, "idx1.jpaq").unreadable());
    Files.write(src.resolve("f2.bin"), "second version".repeat(20000).getBytes());
    Files.delete(src.resolve("f0.bin"));
    assertEquals(1, backup(a, "idx1.jpaq", "idx2.jpaq").removed());
    return st;
  }

  @Test
  void readWithoutIndexAndRebuildIndex(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir, null);
    Path src = dir.resolve("src");
    ZpaqArchive a = seq(st, dir);

    // the tomes alone are enough (needs PROBE to find the parts)
    assertEquals(0, restore(a, ReadOptions.LATEST, "out").failed());
    assertSameTree(src, dir.resolve("out/src"));

    // an index rebuilt from the tomes is the index that was written
    a.writeIndex("rebuilt.jpaq", false, ReadOptions.LATEST);
    assertArrayEquals(st.files.get("idx2.jpaq"), st.files.get("rebuilt.jpaq"));

    // older version through the index
    assertEquals(0, restore(a, ReadOptions.LATEST.withIndex("idx2.jpaq").withVersion(1L), "old")
        .failed());
    assertTrue(Files.exists(dir.resolve("old/src/f0.bin")));
  }

  @Test
  void encrypted(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir, "secret");
    Path src = dir.resolve("src");
    ZpaqArchive a = seq(st, dir).withKey("secret");
    assertEquals(0, restore(a, ReadOptions.LATEST.withIndex("idx2.jpaq"), "out").failed());
    assertSameTree(src, dir.resolve("out/src"));
    assertEquals(0, restore(a, ReadOptions.LATEST, "out2").failed());
    assertSameTree(src, dir.resolve("out2/src"));
    a.writeIndex("rebuilt.jpaq", false, ReadOptions.LATEST);
    assertArrayEquals(st.files.get("idx2.jpaq"), st.files.get("rebuilt.jpaq"));
    // not readable with a wrong key or without one
    var e = assertThrows(ArchiveException.class,
        () -> a.withKey("nope").read(ReadOptions.LATEST.withIndex("idx2.jpaq")));
    assertEquals("password incorrect", e.getMessage());
    assertThrows(ArchiveException.class, () -> a.withKey(null).read(ReadOptions.LATEST));
  }

  @Test
  void incompleteLastTomeIsIgnored(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir, null);
    byte[] t2 = st.files.get("arc002.jpaq");
    st.files.put("arc002.jpaq", Arrays.copyOf(t2, t2.length / 2));  // upload died midway
    ArchiveContents c = seq(st, dir).read(ReadOptions.LATEST);
    assertEquals(1, c.versions().size());
    assertNotNull(entry(c, "src/f0.bin"));
  }

  @Test
  void orphanTomeIsOverwritten(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir, null);
    st.files.put("arc003.jpaq", new byte[] {1, 2, 3});  // upload whose index never arrived
    st.allowOverwrite = true;
    Files.writeString(dir.resolve("src/extra.txt"), "x".repeat(5000));
    ZpaqArchive a = seq(st, dir);
    assertTrue(backup(a, "idx2.jpaq", "idx3.jpaq").changed());
    assertTrue(st.files.get("arc003.jpaq").length > 100);
    assertEquals(0, restore(a, ReadOptions.LATEST.withIndex("idx3.jpaq"), "out").failed());
    assertSameTree(dir.resolve("src"), dir.resolve("out/src"));
  }

  @Test
  void formatsAreNotMixed(@TempDir Path dir) throws Exception {
    fill(dir.resolve("src"), 3, 2);
    // the classic format needs more than streams
    var e = assertThrows(UnsupportedStorageException.class,
        () -> ZpaqArchive.of(new MemStorage(), "x.zpaq").withDirectory(dir).add(AddOptions.of("src")));
    assertEquals(Capability.RANDOM_WRITE, e.missing());
    var e2 = assertThrows(UnsupportedStorageException.class,
        () -> ZpaqArchive.of(new MemStorage(Capability.RANDOM_WRITE), "x.zpaq").withDirectory(dir)
            .add(AddOptions.of("src")));
    assertEquals(Capability.PROBE, e2.missing());
    // multi part names can't be read without PROBE unless an index tells the parts
    assertThrows(UnsupportedStorageException.class,
        () -> ZpaqArchive.of(new MemStorage(), "a???.jpaq").read(ReadOptions.LATEST));

    // a sequential update of a classic archive fails, and so does the opposite
    LocalFsStorage local = new LocalFsStorage(dir);
    ZpaqArchive.of(local, "classic.zpaq").withDirectory(dir).add(AddOptions.of("src"));
    var bad = assertThrows(ArchiveException.class, () -> ZpaqArchive.of(local, "classic??.zpaq")
        .withFormat(Format.SEQUENTIAL).withDirectory(dir)
        .add(AddOptions.of("src").withIndex("classic.zpaq").withIndexOut("ci.jpaq")));
    assertTrue(bad.getMessage().contains("zpaq format"), bad.getMessage());
    LocalFsStorage seqDir = new LocalFsStorage(Files.createDirectories(dir.resolve("seq")));
    backup(seq(seqDir, dir), null, "idx1.jpaq");
    var bad2 = assertThrows(ArchiveException.class, () -> ZpaqArchive.of(seqDir, "arc???.jpaq")
        .withDirectory(dir).add(AddOptions.of("src")));
    assertTrue(bad2.getMessage().contains("jpaq sequential"), bad2.getMessage());

    // the tomes of a sequential archive need a part number in the name
    assertThrows(IllegalArgumentException.class, () -> ZpaqArchive.of(seqDir, "one.jpaq")
        .withFormat(Format.SEQUENTIAL).add(AddOptions.of("src").withIndexOut("i.jpaq")));
  }

  @Test
  void rangedReadsSkipTransfer(@TempDir Path dir) throws Exception {
    MemStorage plain = twoVersions(dir, null);
    MemStorage ranged = new MemStorage(Capability.PROBE, Capability.RANGED_READ);
    ranged.files.putAll(plain.files);
    // extract only a file of the second version: the first tome is not needed
    ReadOptions ro = ReadOptions.LATEST.withIndex("idx2.jpaq").withSelect(n -> n.equals("src/f2.bin"));
    ExtractResult x = restore(seq(ranged, dir).withThreads(1), ro, "out");
    assertEquals(1, x.files(), x.toString());
    assertArrayEquals(Files.readAllBytes(dir.resolve("src/f2.bin")),
        Files.readAllBytes(dir.resolve("out/src/f2.bin")));
    assertTrue(ranged.log.stream().anyMatch(l -> l.startsWith("read arc002.jpaq @")), ranged.log.toString());
    assertTrue(ranged.log.stream().noneMatch(l -> l.startsWith("read arc001.jpaq")), ranged.log.toString());
  }

  @Test
  void backupAndRestoreOverShellCommands(@TempDir Path dir) throws Exception {
    // storage as three shell commands over a directory: the archiver only sees streams
    Path store = Files.createDirectory(dir.resolve("store"));
    var st = new ShellStorage("cat \"$1\"", "cat > \"$1\"", "rm -f \"$1\"", store);
    Path src = dir.resolve("src");
    fill(src, 11, 4);
    ZpaqArchive a = ZpaqArchive.of(st, "data???.jpaq").withFormat(Format.SEQUENTIAL)
        .withKey("pw").withThreads(2).withDirectory(dir);
    AddResult r1 = backup(a, null, "index-1.jpaq");
    assertEquals(8, r1.added(), "4 files, sub/deep/t.txt and 3 directories");
    Files.writeString(src.resolve("f1.bin"), "changed".repeat(5000));
    assertEquals(1, backup(a, "index-1.jpaq", "index-2.jpaq").added());
    try (Stream<Path> s = Files.list(store)) {
      assertEquals(List.of("data001.jpaq", "data002.jpaq", "index-1.jpaq", "index-2.jpaq"),
          s.map(p -> p.getFileName().toString()).sorted().toList());
    }
    ReadOptions ro = ReadOptions.LATEST.withIndex("index-2.jpaq");
    assertEquals(25000, entry(a.read(ro), "src/sub/deep/t.txt").size());
    assertEquals(0, restore(a, ro, "restored").failed());
    assertSameTree(src, dir.resolve("restored/src"));
    assertTrue(a.extract(ExtractOptions.DEFAULT.withRead(ro).withTest(true)).ok());
    assertThrows(ArchiveException.class, () -> a.withKey("wrong").read(ro));
    assertThrows(ArchiveException.class, () -> a.read(ReadOptions.LATEST.withIndex("nope.jpaq")));
  }

  /** Stock C++ zpaq (set ZPAQ_CPP to its path) must not find anything in jpaq archives. */
  @Test
  void stockZpaqSeesNothing(@TempDir Path dir) throws Exception {
    String bin = System.getenv("ZPAQ_CPP");
    org.junit.jupiter.api.Assumptions.assumeTrue(bin != null && Files.isExecutable(Path.of(bin)));
    for (String key : new String[] {null, "secret"}) {
      Path d = Files.createDirectories(dir.resolve(key == null ? "plain" : "key"));
      MemStorage st = twoVersions(d, key);
      Path dump = Files.createDirectories(d.resolve("dump"));
      for (var e : st.files.entrySet()) Files.write(dump.resolve(e.getKey()), e.getValue());
      for (String f : new String[] {"arc001.jpaq", "arc002.jpaq", "idx1.jpaq", "idx2.jpaq"}) {
        for (String cmd : new String[] {"l", "x"}) {
          String[] args = key == null ? new String[] {bin, cmd, f, "-to", "o"}
              : new String[] {bin, cmd, f, "-to", "o", "-key", key};
          Process p = new ProcessBuilder(args).directory(dump.toFile()).redirectErrorStream(true).start();
          String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
          int rc = p.waitFor();
          assertEquals(2, rc, f + " " + cmd + ": " + out);
          assertTrue(out.contains("archive contains no data") || out.contains("password incorrect"), out);
        }
      }
      try (Stream<Path> s = Files.walk(dump)) {
        assertEquals(4, s.filter(Files::isRegularFile).count(), "stock zpaq wrote nothing");
      }
    }
  }

  @Test
  void failedUploadLeavesNoTomeAndNoIndex(@TempDir Path dir) throws Exception {
    MemStorage st = new MemStorage() {
      @Override
      public OutputStream write(String name) throws IOException {
        OutputStream o = super.write(name);
        if (!name.startsWith("arc")) return o;
        return new OutputStream() {
          long n;

          @Override
          public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            if ((n += len) > 200000) throw new IOException("connection lost");
            o.write(b, off, len);
          }

          @Override
          public void close() throws IOException {
            o.close();
          }
        };
      }
    };
    fill(dir.resolve("src"), 9, 6);
    var e = assertThrows(IOException.class, () -> backup(seq(st, dir), null, "idx1.jpaq"));
    assertTrue(e.getMessage().contains("connection lost"), e.toString());
    assertTrue(st.files.isEmpty(), "nothing committed: " + st.files.keySet());
  }
}
