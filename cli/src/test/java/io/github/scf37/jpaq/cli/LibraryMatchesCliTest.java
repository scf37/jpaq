package io.github.scf37.jpaq.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scf37.jpaq.ArchiveUpdate;
import io.github.scf37.jpaq.LocalFsStorage;
import io.github.scf37.jpaq.UpdateOptions;
import io.github.scf37.jpaq.UpdateResult;
import io.github.scf37.jpaq.ZpaqArchive;
import io.github.scf37.jpaq.libzpaq.Crypto;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * An update made through the library (ArchiveUpdate.addTree) writes the same bytes as the
 * command line, which writes the same bytes as zpaq 7.15 (see CliGoldenTest).
 */
class LibraryMatchesCliTest {
  private static final Instant T1 = Instant.parse("2026-05-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-05-02T10:00:00Z");
  private static final Instant T3 = Instant.parse("2026-05-03T10:00:00Z");

  @ParameterizedTest
  @CsvSource({"0,", "1,", "2,", "3,", "5,", "1,secret", "x4.3ci1,", "14,secret"})
  void sameArchive(String method, String key, @TempDir Path dir) throws Exception {
    TreeBuilder.build(dir);
    ZpaqArchive lib = ZpaqArchive.of(dir.resolve("lib.zpaq")).withKey(key).withThreads(2);
    Instant[] dates = {T1, T2, T3};
    try {
      for (int v = 0; v < 3; v++) {
        if (v > 0) TreeBuilder.mutate(dir, v);
        List<String> args = new ArrayList<>(List.of("a", "cli.zpaq", "tree", "-m" + method, "-t2"));
        if (key != null) args.addAll(List.of("-key", key));
        ScenarioRunner.installFixedRandom();
        Zpaq.Result r = Zpaq.run(dir, dates[v], args.toArray(new String[0]));
        assertTrue(r.ok(), r.stderr());

        ScenarioRunner.installFixedRandom();
        UpdateResult u;
        try (ArchiveUpdate up = lib.update(UpdateOptions.DEFAULT.withMethod(method).withDate(dates[v]))) {
          up.addTree(dir.resolve("tree"), "tree");
          u = up.commit();
        }
        assertTrue(u.ok() && u.changed(), u.toString());
        assertEquals(v + 1, u.version());
        assertArrayEquals(Files.readAllBytes(dir.resolve("cli.zpaq")),
            Files.readAllBytes(dir.resolve("lib.zpaq")), "version " + (v + 1));
      }
    } finally {
      Crypto.setRandomSource(null);
    }
  }

  @ParameterizedTest
  @CsvSource({"1", "3"})
  void sameIndexedMultipartArchive(String method, @TempDir Path dir) throws Exception {
    TreeBuilder.build(dir);
    Path c = Files.createDirectories(dir.resolve("c"));
    Path l = Files.createDirectories(dir.resolve("l"));
    ZpaqArchive lib = ZpaqArchive.of(new LocalFsStorage(l), "part???.zpaq");
    Instant[] dates = {T1, T2};
    for (int v = 0; v < 2; v++) {
      if (v > 0) TreeBuilder.mutate(dir, 3);
      Zpaq.Result r = Zpaq.run(dir, dates[v], "a", "c/part???.zpaq", "tree", "-m" + method,
          "-index", "c/index.zpaq");
      assertTrue(r.ok(), r.stderr());
      try (ArchiveUpdate up = lib.update(UpdateOptions.DEFAULT.withMethod(method)
          .withIndex("index.zpaq").withDate(dates[v]))) {
        up.addTree(dir.resolve("tree"), "tree");
        up.commit();
      }
    }
    for (String f : new String[] {"part001.zpaq", "part002.zpaq", "index.zpaq"})
      assertArrayEquals(Files.readAllBytes(c.resolve(f)), Files.readAllBytes(l.resolve(f)), f);
  }
}
