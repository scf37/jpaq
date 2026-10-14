package io.github.scf37.jpaq.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scf37.jpaq.api.Capability;
import io.github.scf37.jpaq.api.Format;
import io.github.scf37.jpaq.api.Storage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SeqArchiveTest {
  record Out(int rc, String stdout, String stderr) {}

  static synchronized Out run(Storage st, Format fmt, Path work, Consumer<Jidac> cfg, String... args) {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    ByteArrayOutputStream e = new ByteArrayOutputStream();
    PrintStream oldOut = Console.out;
    PrintStream oldErr = Console.err;
    PrintStream po = new PrintStream(o, false, StandardCharsets.ISO_8859_1);
    PrintStream pe = new PrintStream(e, false, StandardCharsets.ISO_8859_1);
    Console.setStreams(po, pe);
    int rc;
    try {
      rc = Main.run(work, new ArchiveIo(st, fmt), cfg, args);
    } finally {
      po.flush();
      pe.flush();
      Console.setStreams(oldOut, oldErr);
    }
    return new Out(rc, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
  }

  static void fill(Path dir, long seed, int files) throws Exception {
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

  static void assertSameTree(Path a, Path b) throws Exception {
    List<Path> fa;
    try (Stream<Path> s = Files.walk(a)) {
      fa = s.filter(Files::isRegularFile).map(a::relativize).sorted().toList();
    }
    List<Path> fb;
    try (Stream<Path> s = Files.walk(b)) {
      fb = s.filter(Files::isRegularFile).map(b::relativize).sorted().toList();
    }
    assertEquals(fa, fb);
    for (Path p : fa) assertArrayEquals(Files.readAllBytes(a.resolve(p)), Files.readAllBytes(b.resolve(p)), p.toString());
  }

  /** Stock zpaq block finder: tag + "zPQ". */
  static boolean stockFindsBlock(byte[] s) {
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

  @Test
  void roundTripOnStreamOnlyStorage(@TempDir Path dir) throws Exception {
    MemStorage st = new MemStorage();  // no capabilities: read, write, delete only
    Path src = dir.resolve("src");
    fill(src, 1, 6);

    Out r1 = run(st, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx1.jpaq",
        "a", "arc???.jpaq", "src", "-m1", "-t2");
    assertEquals(0, r1.rc, r1.stderr);
    assertEquals(List.of("arc001.jpaq", "idx1.jpaq"), List.copyOf(st.files.keySet()));
    for (byte[] b : st.files.values()) assertFalse(stockFindsBlock(b), "zpaq must not see any block");

    // update: change one file, add one
    Files.write(src.resolve("f1.bin"), "changed".repeat(30000).getBytes());
    Files.writeString(src.resolve("new.txt"), "new file".repeat(1000));
    Out r2 = run(st, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx2.jpaq",
        "a", "arc???.jpaq", "src", "-m1", "-t2", "-index", "idx1.jpaq");
    assertEquals(0, r2.rc, r2.stderr);
    assertEquals(List.of("arc001.jpaq", "arc002.jpaq", "idx1.jpaq", "idx2.jpaq"),
        List.copyOf(st.files.keySet()));
    assertTrue(st.files.get("idx2.jpaq").length > st.files.get("idx1.jpaq").length);
    assertArrayEquals(st.files.get("idx1.jpaq"),
        Arrays.copyOf(st.files.get("idx2.jpaq"), st.files.get("idx1.jpaq").length),
        "the new index starts with the old one");

    // an unchanged tree adds nothing
    int before = st.files.size();
    Out r3 = run(st, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx3.jpaq",
        "a", "arc???.jpaq", "src", "-m1", "-index", "idx2.jpaq");
    assertEquals(0, r3.rc, r3.stderr);
    assertEquals(before, st.files.size(), "nothing written for an empty update");

    // only streaming operations were used
    assertTrue(st.log.stream().noneMatch(s -> s.startsWith("exists")), st.log.toString());

    // list and extract from the index; tomes are read through streams
    Out l = run(st, Format.SEQUENTIAL, dir, j -> j.readIndex = "idx2.jpaq", "l", "arc???.jpaq");
    assertEquals(0, l.rc, l.stderr);
    assertTrue(l.stdout.contains("new.txt"), l.stdout);
    Path out = dir.resolve("out");
    Files.createDirectories(out);
    Out x = run(st, Format.SEQUENTIAL, dir, j -> j.readIndex = "idx2.jpaq",
        "x", "arc???.jpaq", "-to", "out", "-t3");
    assertEquals(0, x.rc, x.stderr + x.stdout);
    assertSameTree(src, out.resolve("src"));
  }

  /** Two versions with an optional password; returns the storage. */
  private MemStorage twoVersions(Path dir, String... extra) throws Exception {
    MemStorage st = new MemStorage(Capability.PROBE);
    Path src = dir.resolve("src");
    fill(src, 7, 5);
    String[] a1 = Stream.concat(Stream.of("a", "arc???.jpaq", "src", "-m1", "-t2"), Stream.of(extra))
        .toArray(String[]::new);
    Out r1 = run(st, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx1.jpaq", a1);
    assertEquals(0, r1.rc, r1.stderr);
    Files.write(src.resolve("f2.bin"), "second version".repeat(20000).getBytes());
    Files.delete(src.resolve("f0.bin"));
    String[] a2 = Stream.concat(Stream.of("a", "arc???.jpaq", "src", "-m1", "-t2", "-index", "idx1.jpaq"),
        Stream.of(extra)).toArray(String[]::new);
    Out r2 = run(st, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx2.jpaq", a2);
    assertEquals(0, r2.rc, r2.stderr);
    return st;
  }

  @Test
  void readWithoutIndexAndRebuildIndex(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir);
    Path src = dir.resolve("src");

    // the tomes alone are enough (needs PROBE to find the parts)
    Path out = dir.resolve("out");
    Out x = run(st, Format.SEQUENTIAL, dir, null, "x", "arc???.jpaq", "-to", "out", "-t2");
    assertEquals(0, x.rc, x.stderr + x.stdout);
    assertSameTree(src, out.resolve("src"));

    // an index rebuilt from the tomes is the index that was written
    Out ri = run(st, Format.SEQUENTIAL, dir, j -> j.index = "rebuilt.jpaq", "x", "arc???.jpaq");
    assertEquals(0, ri.rc, ri.stderr + ri.stdout);
    assertArrayEquals(st.files.get("idx2.jpaq"), st.files.get("rebuilt.jpaq"));

    // older version through the index
    Path old = dir.resolve("old");
    Out xv = run(st, Format.SEQUENTIAL, dir, j -> j.readIndex = "idx2.jpaq", "x", "arc???.jpaq", "-until", "1",
        "-to", "old");
    assertEquals(0, xv.rc, xv.stderr + xv.stdout);
    assertTrue(Files.exists(old.resolve("src/f0.bin")));
  }

  @Test
  void encrypted(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir, "-key", "secret");
    Path src = dir.resolve("src");
    Out x = run(st, Format.SEQUENTIAL, dir, j -> j.readIndex = "idx2.jpaq", "x", "arc???.jpaq", "-to", "out",
        "-key", "secret", "-t2");
    assertEquals(0, x.rc, x.stderr + x.stdout);
    assertSameTree(src, dir.resolve("out/src"));
    Out y = run(st, Format.SEQUENTIAL, dir, null, "x", "arc???.jpaq", "-to", "out2", "-key", "secret");
    assertEquals(0, y.rc, y.stderr + y.stdout);
    assertSameTree(src, dir.resolve("out2/src"));
    Out ri = run(st, Format.SEQUENTIAL, dir, j -> j.index = "rebuilt.jpaq", "x", "arc???.jpaq", "-key",
        "secret");
    assertEquals(0, ri.rc, ri.stderr + ri.stdout);
    assertArrayEquals(st.files.get("idx2.jpaq"), st.files.get("rebuilt.jpaq"));
    // not readable with a wrong key
    Out bad = run(st, Format.SEQUENTIAL, dir, j -> j.readIndex = "idx2.jpaq", "l", "arc???.jpaq", "-key", "nope");
    assertNotEquals(0, bad.rc);
  }

  @Test
  void incompleteLastTomeIsIgnored(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir);
    byte[] t2 = st.files.get("arc002.jpaq");
    st.files.put("arc002.jpaq", Arrays.copyOf(t2, t2.length / 2));  // upload died midway
    Out l = run(st, Format.SEQUENTIAL, dir, null, "l", "arc???.jpaq");
    assertEquals(0, l.rc, l.stderr);
    assertTrue(l.stdout.contains("1 versions") || l.stdout.contains("versions"), l.stdout);
    assertTrue(l.stdout.contains("f0.bin"), l.stdout);
  }

  @Test
  void orphanTomeIsOverwritten(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir);
    st.files.put("arc003.jpaq", new byte[] {1, 2, 3});  // upload whose index never arrived
    st.allowOverwrite = true;
    Files.writeString(dir.resolve("src/extra.txt"), "x".repeat(5000));
    Out r = run(st, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx3.jpaq", "a", "arc???.jpaq", "src",
        "-m1", "-index", "idx2.jpaq");
    assertEquals(0, r.rc, r.stderr);
    assertTrue(st.files.get("arc003.jpaq").length > 100);
    Out x = run(st, Format.SEQUENTIAL, dir, j -> j.readIndex = "idx3.jpaq", "x", "arc???.jpaq", "-to", "out");
    assertEquals(0, x.rc, x.stderr);
    assertSameTree(dir.resolve("src"), dir.resolve("out/src"));
  }

  @Test
  void formatsAreNotMixed(@TempDir Path dir) throws Exception {
    MemStorage st = twoVersions(dir);
    // adding in the classic format to a jpaq archive fails
    Out r = run(new io.github.scf37.jpaq.cli.MemStorage(), Format.CLASSIC, dir, null, "a", "x.zpaq", "src");
    assertNotEquals(0, r.rc);  // classic needs RANDOM_WRITE and PROBE
    assertTrue(r.stderr.contains("RANDOM_WRITE"), r.stderr);
    // a sequential add to a classic archive fails
    Path src = dir.resolve("src");
    LocalDirTest.classicArchive(dir, "classic.zpaq");
    io.github.scf37.jpaq.api.LocalFsStorage local = new io.github.scf37.jpaq.api.LocalFsStorage(dir);
    Out s = run(local, Format.SEQUENTIAL, dir, j -> j.indexOut = "ci.jpaq", "a", "classic??.zpaq", "src", "-index",
        "classic.zpaq");
    assertNotEquals(0, s.rc);
  }

  @Test
  void rangedReadsSkipTransfer(@TempDir Path dir) throws Exception {
    MemStorage plain = twoVersions(dir);
    MemStorage ranged = new MemStorage(Capability.PROBE, Capability.RANGED_READ);
    ranged.files.putAll(plain.files);
    // extract only a small file of the second version: the first tome is not needed
    Out x = run(ranged, Format.SEQUENTIAL, dir, j -> j.readIndex = "idx2.jpaq", "x", "arc???.jpaq", "src/f2.bin",
        "-to", "out", "-t1");
    assertEquals(0, x.rc, x.stderr + x.stdout);
    assertArrayEquals(Files.readAllBytes(dir.resolve("src/f2.bin")), Files.readAllBytes(dir.resolve("out")));
    assertTrue(ranged.log.stream().anyMatch(l -> l.startsWith("read arc002.jpaq @")), ranged.log.toString());
    assertTrue(ranged.log.stream().noneMatch(l -> l.equals("read arc001.jpaq")), ranged.log.toString());
  }

  /** Stock C++ zpaq (set ZPAQ_CPP to its path) must not find anything in jpaq archives. */
  @Test
  void stockZpaqSeesNothing(@TempDir Path dir) throws Exception {
    String bin = System.getenv("ZPAQ_CPP");
    org.junit.jupiter.api.Assumptions.assumeTrue(bin != null && Files.isExecutable(Path.of(bin)));
    for (String[] extra : new String[][] {{}, {"-key", "secret"}}) {
      Path d = Files.createDirectories(dir.resolve("k" + extra.length));
      MemStorage st = twoVersions(d, extra);
      Path dump = Files.createDirectories(d.resolve("dump"));
      for (var e : st.files.entrySet()) Files.write(dump.resolve(e.getKey()), e.getValue());
      for (String f : new String[] {"arc001.jpaq", "arc002.jpaq", "idx1.jpaq", "idx2.jpaq"}) {
        for (String cmd : new String[] {"l", "x"}) {
          String[] args = extra.length == 0 ? new String[] {bin, cmd, f, "-to", "o"}
              : new String[] {bin, cmd, f, "-to", "o", "-key", "secret"};
          Process p = new ProcessBuilder(args).directory(dump.toFile()).redirectErrorStream(true).start();
          String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
          int rc = p.waitFor();
          assertEquals(2, rc, f + " " + cmd + ": " + out);
          assertTrue(out.contains("archive contains no data") || out.contains("password incorrect"), out);
          assertFalse(out.contains("versions,") && !out.contains("0 versions"), out);
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
      public java.io.OutputStream write(String name) throws java.io.IOException {
        java.io.OutputStream o = super.write(name);
        if (!name.startsWith("arc")) return o;
        return new java.io.OutputStream() {
          long n;

          @Override
          public void write(int b) throws java.io.IOException {
            write(new byte[] {(byte) b}, 0, 1);
          }

          @Override
          public void write(byte[] b, int off, int len) throws java.io.IOException {
            if ((n += len) > 200000) throw new java.io.IOException("connection lost");
            o.write(b, off, len);
          }

          @Override
          public void close() throws java.io.IOException {
            o.close();
          }
        };
      }
    };
    fill(dir.resolve("src"), 9, 6);
    Out r = run(st, Format.SEQUENTIAL, dir, j -> j.indexOut = "idx1.jpaq", "a", "arc???.jpaq", "src", "-m1", "-t2");
    assertEquals(2, r.rc, r.stderr);
    assertTrue(st.files.isEmpty(), "nothing committed: " + st.files.keySet());
  }
}
