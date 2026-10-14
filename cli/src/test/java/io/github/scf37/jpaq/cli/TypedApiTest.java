package io.github.scf37.jpaq.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.github.scf37.jpaq.api.Capability;
import io.github.scf37.jpaq.api.Command;
import io.github.scf37.jpaq.api.ErrorLine;
import io.github.scf37.jpaq.api.Format;
import io.github.scf37.jpaq.api.InfoLine;
import io.github.scf37.jpaq.api.Line;
import io.github.scf37.jpaq.api.Options;
import io.github.scf37.jpaq.api.ShellStorage;
import io.github.scf37.jpaq.api.UnsupportedStorageException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypedApiTest {
  private static <T extends Line> List<T> of(List<Line> lines, Class<T> c) {
    return lines.stream().filter(c::isInstance).map(c::cast).collect(Collectors.toList());
  }

  @Test
  void typedBackupAndRestoreOverShellStorage(@TempDir Path dir) throws Exception {
    // storage as three shell commands over a directory: the archiver only sees streams
    Path store = Files.createDirectory(dir.resolve("store"));
    var st = new ShellStorage("cat \"$1\"", "cat > \"$1\"", "rm -f \"$1\"", store);
    Path src = dir.resolve("src");
    SeqArchiveTest.fill(src, 11, 4);
    Options o = new Options().method("1").threads(2).key("pw");

    List<Line> l1 = new ArrayList<>();
    int rc = Zpaq.run(st, dir, new Command.Add("data???.jpaq", List.of("src"), o, Format.SEQUENTIAL,
        null, "index-1.jpaq"), l1::add);
    assertEquals(0, rc, l1.toString());
    assertEquals(List.of("data001.jpaq", "index-1.jpaq"), listing(store));
    assertEquals(1, of(l1, InfoLine.Banner.class).size());
    var added = of(l1, InfoLine.FileAdded.class);
    assertTrue(added.stream().anyMatch(a -> a.name().equals("src/f0.bin") && !a.updated()), l1.toString());
    assertEquals(8, of(l1, InfoLine.AddSummary.class).get(0).added(),
        "4 files, sub/deep/t.txt and 3 directories");
    var fin = of(l1, InfoLine.Finished.class);
    assertEquals(1, fin.size());
    assertTrue(fin.get(0).ok());
    // every line has its console text, stdout and stderr tell apart
    assertTrue(l1.stream().allMatch(l -> l.text() != null && !l.text().isEmpty()));
    assertTrue(of(l1, ErrorLine.class).isEmpty(), l1.toString());

    // second backup: one changed file; the first index is the base of the second
    Files.writeString(src.resolve("f1.bin"), "changed".repeat(5000));
    List<Line> l2 = new ArrayList<>();
    rc = Zpaq.run(st, dir, new Command.Add("data???.jpaq", List.of("src"), o, Format.SEQUENTIAL,
        "index-1.jpaq", "index-2.jpaq"), l2::add);
    assertEquals(0, rc, l2.toString());
    assertEquals(List.of("data001.jpaq", "data002.jpaq", "index-1.jpaq", "index-2.jpaq"), listing(store));
    var upd = of(l2, InfoLine.FileAdded.class);
    assertEquals(List.of("src/f1.bin"), upd.stream().map(InfoLine.FileAdded::name).distinct().toList());

    // list through the index
    List<Line> l3 = new ArrayList<>();
    rc = Zpaq.run(st, dir, new Command.ListArchive("data???.jpaq", List.of(), o, "index-2.jpaq"), l3::add);
    assertEquals(0, rc, l3.toString());
    var entries = of(l3, InfoLine.ListEntry.class);
    assertTrue(entries.stream().anyMatch(e -> e.name().equals("src/sub/deep/t.txt") && e.size() == 25000));
    assertEquals(1, of(l3, InfoLine.ArchiveRead.class).get(0).versions() - 1);

    // restore everything; the typed lines tell what was extracted
    List<Line> l4 = new ArrayList<>();
    rc = Zpaq.run(st, dir, new Command.Extract("data???.jpaq", List.of(), List.of("restored"), o,
        "index-2.jpaq", false), l4::add);
    assertEquals(0, rc, l4.toString());
    assertTrue(of(l4, InfoLine.FileExtracted.class).size() >= 5);
    SeqArchiveTest.assertSameTree(src, dir.resolve("restored/src"));

    // test everything
    List<Line> l5 = new ArrayList<>();
    assertEquals(0, Zpaq.run(st, dir, new Command.Extract("data???.jpaq", List.of(), List.of(), o,
        "index-2.jpaq", true), l5::add), l5.toString());

    // wrong password: typed error, exit code 2
    List<Line> l6 = new ArrayList<>();
    rc = Zpaq.run(st, dir, new Command.ListArchive("data???.jpaq", List.of(),
        new Options().key("wrong"), "index-2.jpaq"), l6::add);
    assertEquals(2, rc);
    assertFalse(of(l6, ErrorLine.class).isEmpty(), l6.toString());

    // a missing index is a typed failure, not a crash
    List<Line> l7 = new ArrayList<>();
    rc = Zpaq.run(st, dir, new Command.ListArchive("data???.jpaq", List.of(), o, "nope.jpaq"), l7::add);
    assertEquals(2, rc);
    assertFalse(of(l7, ErrorLine.Fatal.class).isEmpty() && of(l7, ErrorLine.FileError.class).isEmpty(),
        l7.toString());
  }

  private static List<String> listing(Path dir) throws Exception {
    try (Stream<Path> s = Files.list(dir)) {
      return s.map(p -> p.getFileName().toString()).sorted().toList();
    }
  }

  @Test
  void classicNeedsMoreFromTheStorage(@TempDir Path dir) {
    var st = new MemStorage();  // streaming only
    var e = assertThrows(UnsupportedStorageException.class,
        () -> Zpaq.run(st, dir, new Command.Add("x.zpaq", List.of("src"), new Options(), Format.CLASSIC,
            null, null), l -> { }));
    assertEquals(Capability.RANDOM_WRITE, e.missing());
    var st2 = new MemStorage(Capability.RANDOM_WRITE);
    var e2 = assertThrows(UnsupportedStorageException.class,
        () -> Zpaq.run(st2, dir, new Command.Add("x.zpaq", List.of("src"), new Options(),
            Format.CLASSIC, null, null), l -> { }));
    assertEquals(Capability.PROBE, e2.missing());
    // multi part names can't be read without PROBE unless an index tells the parts: a typed failure
    List<Line> lines = new ArrayList<>();
    int rc = Zpaq.run(st, dir, new Command.ListArchive("a???.jpaq", List.of(), new Options(), null),
        lines::add);
    assertEquals(2, rc);
    var fatal = of(lines, ErrorLine.Fatal.class);
    assertEquals(1, fatal.size(), lines.toString());
    assertTrue(fatal.get(0).cause() instanceof UnsupportedStorageException, lines.toString());
  }

  @Test
  void streamOfLines(@TempDir Path dir) throws Exception {
    var st = new MemStorage();
    SeqArchiveTest.fill(dir.resolve("src"), 5, 2);
    List<Line> lines;
    try (Stream<Line> s = Zpaq.stream(st, dir, new Command.Add("t???.jpaq", List.of("src"),
        new Options().method("1"), Format.SEQUENTIAL, null, "i1.jpaq"))) {
      lines = s.toList();
    }
    assertTrue(lines.get(0) instanceof InfoLine.Banner, lines.toString());
    assertTrue(lines.get(lines.size() - 1) instanceof InfoLine.Finished);
    assertTrue(st.files.containsKey("i1.jpaq"));
    // stopping early still finishes the command
    var st2 = new MemStorage();
    try (Stream<Line> s = Zpaq.stream(st2, dir, new Command.Add("t???.jpaq", List.of("src"),
        new Options().method("1"), Format.SEQUENTIAL, null, "i1.jpaq"))) {
      assertTrue(s.findFirst().isPresent());
    }
    assertTrue(st2.files.containsKey("i1.jpaq"));
  }

  @Test
  void receiverFailureEndsDeliveryAndIsReported(@TempDir Path dir) throws Exception {
    var st = new MemStorage();
    SeqArchiveTest.fill(dir.resolve("src"), 5, 2);
    int[] n = {0};
    var ex = assertThrows(IllegalStateException.class, () -> Zpaq.run(st, dir,
        new Command.Add("t???.jpaq", List.of("src"), new Options(), Format.SEQUENTIAL, null, "i1.jpaq"),
        l -> {
          if (++n[0] == 3) throw new IllegalStateException("receiver broke");
        }));
    assertEquals("receiver broke", ex.getMessage());
    assertEquals(3, n[0]);
  }
}
