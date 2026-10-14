package io.github.scf37.jpaq.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Stream;

/** Deterministic directory trees and their mutations for tests. */
final class TreeBuilder {
  private TreeBuilder() {}

  static final long T0 = 1_600_000_000L;

  private static final String[] WORDS = {"the", "archive", "journal", "block", "fragment", "hash",
      "compress", "model", "context", "mixing", "of", "and", "a", "to", "in", "is", "that", "for",
      "zpaq", "predict", "bit", "stream", "segment", "version", "index", "file", "directory"};

  static byte[] text(int size, long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    StringBuilder sb = new StringBuilder();
    while (sb.length() < size) {
      sb.append(WORDS[r.nextInt(WORDS.length)]);
      int c = r.nextInt(20);
      sb.append(c == 0 ? ".\n" : c == 1 ? ", " : " ");
    }
    return sb.substring(0, size).getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] random(int size, long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    byte[] b = new byte[size];
    for (int i = 0; i < size; i++) b[i] = (byte) r.nextInt(256);
    return b;
  }

  static byte[] exe(int size, long seed) {
    SplittableRandom r = new SplittableRandom(seed);
    byte[] b = new byte[size];
    for (int i = 0; i < size; i++) b[i] = (byte) (r.nextInt(4) == 0 ? r.nextInt(256) : 0x8b);
    for (int i = 0; i + 5 < size; i += 8 + r.nextInt(30)) {
      b[i] = (byte) (r.nextBoolean() ? 0xe8 : 0xe9);
      b[i + 4] = (byte) (r.nextBoolean() ? 0 : 255);
    }
    return b;
  }

  static byte[] sequence(int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= n; i++) sb.append(i).append('\n');
    return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
  }

  private static void file(Path root, String rel, byte[] data, long mtime, int mode)
      throws IOException {
    Path p = root.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.write(p, data);
    setMode(p, mode);
    Files.setLastModifiedTime(p, FileTime.from(Instant.ofEpochSecond(mtime)));
  }

  static void setMode(Path p, int mode) throws IOException {
    java.util.Set<PosixFilePermission> perms = new java.util.HashSet<>();
    PosixFilePermission[] all = {PosixFilePermission.OTHERS_EXECUTE,
        PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ,
        PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_READ, PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ};
    for (int i = 0; i < 9; i++) if ((mode & (1 << i)) != 0) perms.add(all[i]);
    Files.setPosixFilePermissions(p, perms);
  }

  private static void dir(Path root, String rel, long mtime, int mode) throws IOException {
    Path p = root.resolve(rel);
    Files.createDirectories(p);
    setMode(p, mode);
    Files.setLastModifiedTime(p, FileTime.from(Instant.ofEpochSecond(mtime)));
  }

  /** Creates the base tree {@code dir/tree}. */
  static void build(Path dir) throws IOException {
    Path t = dir.resolve("tree");
    byte[] a = text(30000, 1);
    file(t, "a.txt", a, T0 + 100, 0644);
    file(t, "dup.txt", a, T0 + 200, 0644);
    file(t, "s.txt", sequence(5000), T0 + 300, 0600);
    file(t, "small", text(3000, 2), T0 + 400, 0644);
    file(t, "zero_len", new byte[0], T0 + 500, 0644);
    file(t, "sub/prog.exe", exe(20000, 3), T0 + 600, 0755);
    file(t, "sub/deep/r.bin", random(8000, 4), T0 + 700, 0644);
    file(t, "sub/deep/t2.txt", text(12000, 5), T0 + 750, 0644);
    dir(t, "empty", T0 + 800, 0755);
    dir(t, "sub/deep", T0 + 900, 0755);
    dir(t, "sub", T0 + 1000, 0755);
    dir(t, "", T0 + 1100, 0755);
  }

  /** Applies mutation number n to {@code dir/tree}. */
  static void mutate(Path dir, int n) throws IOException {
    Path t = dir.resolve("tree");
    switch (n) {
      case 1 -> {
        byte[] s = Files.readAllBytes(t.resolve("s.txt"));
        byte[] s2 = java.util.Arrays.copyOf(s, s.length + 4000);
        System.arraycopy(text(4000, 6), 0, s2, s.length, 4000);
        file(t, "s.txt", s2, T0 + 5000, 0600);
        Files.delete(t.resolve("small"));
        file(t, "new.txt", text(9000, 7), T0 + 5100, 0644);
        file(t, "sub/deep/r.bin", random(8000, 8), T0 + 5200, 0644);
      }
      case 2 -> Files.setLastModifiedTime(t.resolve("a.txt"),
          FileTime.from(Instant.ofEpochSecond(T0 + 6000)));
      case 3 -> {
        delete(t.resolve("sub"));
        file(t, "moved/prog.exe", exe(20000, 3), T0 + 600, 0755);
      }
      default -> throw new IllegalArgumentException("mutation " + n);
    }
    fixDirectoryTimes(t);
  }

  /** Restores fixed directory modification times (creating or deleting files changes them). */
  private static void fixDirectoryTimes(Path t) throws IOException {
    String[] dirs = {"sub/deep", "sub", "empty", "moved", ""};
    long[] times = {T0 + 900, T0 + 1000, T0 + 800, T0 + 1200, T0 + 1100};
    for (int i = 0; i < dirs.length; i++) {
      Path p = t.resolve(dirs[i]);
      if (Files.isDirectory(p))
        Files.setLastModifiedTime(p, FileTime.from(Instant.ofEpochSecond(times[i])));
    }
  }

  static void delete(Path p) throws IOException {
    if (Files.isDirectory(p)) {
      try (Stream<Path> s = Files.list(p)) {
        for (Path c : (Iterable<Path>) s::iterator) delete(c);
      }
    }
    Files.delete(p);
  }

  /** Hash of the names, modes, modification times and contents of everything below {@code root}. */
  static String treeHash(Path root) throws IOException {
    List<Path> all = new ArrayList<>();
    try (Stream<Path> s = Files.walk(root)) {
      s.filter(p -> !p.equals(root)).forEach(all::add);
    }
    all.sort((x, y) -> root.relativize(x).toString().compareTo(root.relativize(y).toString()));
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (Path p : all) {
        boolean d = Files.isDirectory(p);
        Object mode = Files.getAttribute(p, "unix:mode");
        long mtime = Files.getLastModifiedTime(p).toInstant().getEpochSecond();
        // files and directories created without a stored date get the current time: not comparable
        if (mtime > T0 + 1_000_000) mtime = -1;
        md.update((root.relativize(p) + (d ? "/" : "") + "\t" + Integer.toOctalString((Integer) mode & 07777)
            + "\t" + mtime + "\t").getBytes(StandardCharsets.UTF_8));
        if (!d) md.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        md.update((byte) '\n');
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
