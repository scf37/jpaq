package io.github.scf37.jpaq.cli;

import static io.github.scf37.jpaq.cli.Text.bytes;
import static io.github.scf37.jpaq.cli.Text.text;

import io.github.scf37.jpaq.Storage;
import io.github.scf37.jpaq.ZpaqArchive;
import io.github.scf37.jpaq.ZpaqArchive.ArchiveSelector;
import io.github.scf37.jpaq.ZpaqArchive.Option;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Consumer;

/** The zpaq 7.15 command line on top of the archive library. */
final class CommandLine {
  private CommandLine() {}

  static final String ZPAQ_VERSION = "7.15";

  /** A failure of the command line itself (the C++ error() before any archive is touched). */
  static final class Fatal extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Fatal(String message) {
      super(message);
    }
  }

  /**
   * Parses the command line and executes the command. Arguments are byte strings, argv[0] is the
   * program name. Returns 0 OK, 1 warnings; throws {@link ExitException} after printing the usage.
   */
  static int run(Console out, Path workDir, Instant clock, String[] argv) throws IOException {
    out.print("zpaq v" + ZPAQ_VERSION + " journaling archiver, Java port\n");
    Args a = new Args();
    a.date = decimal(clock != null ? clock : Instant.now());
    parse(out, a, argv);
    out.summary = a.summary;

    // Set threads
    if (a.threads < 1) a.threads = Math.max(Runtime.getRuntime().availableProcessors(), 1);

    // Test date
    if (a.date < 19000000000000L || a.date > 30000000000000L)
      throw new Fatal("date is incorrect, use -until YYYY-MM-DD HH:MM:SS to set");

    if (!((a.command == 'a' && a.files.size() > 0) || a.command == 'x' || a.command == 'l'))
      usage(out, a);

    ZpaqArchive archive = ZpaqArchive.of(Storage.ofDirectory(workDir), text(a.archive),
        Option.key(a.key != null ? text(a.key) : null), Option.threads(a.threads),
        Option.directory(workDir), Option.clock(Clock.fixed(instant(a.date), ZoneOffset.UTC)),
        Option.listener(out));
    return switch (a.command) {
      case 'a' -> add(a, archive);
      case 'x' -> extract(a, archive);
      default -> new Lister(a, out, workDir).list(archive);
    };
  }

  /**
   * The archive versions and entries of -all, -until and -noattributes; the names of the files
   * and -only, -not ({@code addition}: the selection of add, else of extract).
   */
  static Consumer<ArchiveSelector<?>> selection(Args a, boolean addition) {
    return s -> {
      s.allVersions(a.all).select(n -> a.isselected(bytes(n), addition))
          .noAttributes(a.noattributes);
      if (a.version > 9999999 && a.version != Args.DEFAULT_VERSION) s.until(instant(a.version));
      else if (a.version != Args.DEFAULT_VERSION) s.version(a.version);
    };
  }

  private static int add(Args a, ZpaqArchive archive) throws IOException {
    var r = archive.add(b -> {
      b.files(a.files.stream().map(Text::text).toList())
          .archiveName(n -> text(a.rename(bytes(n))))   // -to
          .prune(n -> a.excluded(bytes(n)))              // -not: not even scanned
          .accept(n -> a.isselected(bytes(n), false))    // files, -only, -not
          .scope(n -> a.isselected(bytes(n), true))      // what the update is about in the archive
          .method(a.method).fragment(a.fragment).force(a.force).noAttributes(a.noattributes)
          .index(a.index != null ? text(a.index) : null).date(instant(a.date));
      if (a.version > 9999999 && a.version != Args.DEFAULT_VERSION) b.rollback(instant(a.version));
      else if (a.version != Args.DEFAULT_VERSION) b.rollback(a.version);
    });
    return r.unreadable() > 0 ? 1 : 0;
  }

  private static int extract(Args a, ZpaqArchive archive) throws IOException {
    // Copy the whole archive, encrypted or decrypted
    if (a.repack != null && a.all != 0) {
      if (a.files.size() > 0 || a.tofiles.size() > 0 || a.onlyfiles.size() > 0 || a.noattributes
          || a.version != Args.DEFAULT_VERSION || !a.method.isEmpty())
        throw new Fatal("-repack -all does not allow partial copy");
      archive.copy(text(a.repack), c -> c.key(a.newKey != null ? text(a.newKey) : null)
          .overwrite(a.force));
      return 0;
    }
    Consumer<ArchiveSelector<?>> select = selection(a, false);
    if (a.index != null) {
      archive.writeIndex(text(a.index), i -> select.accept(i.overwrite(a.force)));
      return 0;
    }
    if (a.repack != null) {
      archive.repack(text(a.repack), r -> select.accept(r.rename(n -> text(a.rename(bytes(n))))
          .key(a.newKey != null ? text(a.newKey) : null).overwrite(a.force)));
      return 0;
    }
    var r = archive.extract(x -> select.accept(x.output(n -> text(a.rename(bytes(n))))
        .overwrite(a.force).test(a.dotest)));
    return r.failed() > 0 ? 1 : 0;
  }

  /** Decimal date YYYYMMDDHHMMSS (UT) of an instant. */
  static long decimal(Instant i) {
    ZonedDateTime t = i.atZone(ZoneOffset.UTC);
    return t.getYear() * 10000000000L + t.getMonthValue() * 100000000L
        + t.getDayOfMonth() * 1000000L + t.getHour() * 10000L + t.getMinute() * 100L
        + t.getSecond();
  }

  /** The instant of a decimal date (fields out of range carry over). */
  static Instant instant(long d) {
    return LocalDateTime.of((int) (d / 10000000000L), 1, 1, 0, 0)
        .plusMonths(d / 100000000 % 100 - 1).plusDays(d / 1000000 % 100 - 1)
        .plusHours(d / 10000 % 100).plusMinutes(d / 100 % 100).plusSeconds(d % 100)
        .toInstant(ZoneOffset.UTC);
  }

  /** Prints the help message and exits. */
  private static void usage(Console out, Args a) {
    out.print(String.format(java.util.Locale.ROOT, """
        Usage: zpaq command archive[.zpaq] files... -options...
        Files... may be directory trees. Default is the whole archive.
        Use * or ???? in archive name for multi-part or "" for empty.
        Commands:
           a  add         Append files to archive if dates have changed.
           x  extract     Extract most recent versions of files.
           l  list        List or compare external files to archive by dates.
        Options:
          -all [N]        Extract/list versions in N [4] digit directories.
          -f -force       Add: append files if contents have changed.
                          Extract: overwrite existing output files.
                          List: compare file contents instead of dates.
          -index F        Extract: create index F for archive.
                          Add: create suffix for archive indexed by F, update F.
          -key X          Create or access encrypted archive with password X.
          -mN  -method N  Compress level N (0..5 = faster..better, default 1).
          -noattributes   Ignore/don't save file attributes or permissions.
          -not files...   Exclude. * and ? match any string or char.
               =[+-#^?]   List: exclude by comparison result.
          -only files...  Include only matches (default: *).
          -repack F [X]   Extract to new archive F with key X (default: none).
          -sN -summary N  List: show top N sorted by size. -1: show frag IDs.
                          Add/Extract: if N > 0 show brief progress.
          -test           Extract: verify but do not write files.
          -tN -threads N  Use N threads (default: 0 = %d cores).
          -to out...      Rename files... to out... or all to out/all.
          -until N        Roll back archive to N'th update or -N from end.
          -until %s  Set date, roll back (UT, default time: 235959).
        """, a.threads, Text.dateToString(a.date)));
    throw new ExitException(1);
  }

  private static void parse(Console out, Args a, String[] argv) {
    final int argc = argv.length;
    for (int i = 1; i < argc; ++i) {
      final String opt = argv[i];  // read command
      if ((opt.equals("add") || opt.equals("extract") || opt.equals("list") || opt.equals("convert")
          || opt.equals("a") || opt.equals("x") || opt.equals("l") || opt.equals("c"))
          && i < argc - 1 && Text.at(argv[i + 1], 0) != '-' && a.command == 0) {
        a.command = opt.charAt(0);
        if (opt.equals("extract")) a.command = 'x';
        a.archive = argv[++i];  // append ".zpaq" to archive if no extension
        int slash = argv[i].lastIndexOf('/');
        int dot = argv[i].lastIndexOf('.');
        if (slash >= 0 && dot < slash) dot = -1;
        if (dot < 0 && !a.archive.isEmpty()) a.archive += ".zpaq";
        while (++i < argc && Text.at(argv[i], 0) != '-')  // read filename args
          a.files.add(argv[i]);
        --i;
      } else if (opt.length() < 2 || opt.charAt(0) != '-') {
        usage(out, a);
      } else if (opt.equals("-all")) {
        a.all = 4;
        if (i < argc - 1 && Text.isdigit(Text.at(argv[i + 1], 0))) a.all = Text.atoi(argv[++i]);
      } else if (opt.equals("-force") || opt.equals("-f")) {
        a.force = true;
      } else if (opt.equals("-fragment") && i < argc - 1) {
        a.fragment = Text.atoi(argv[++i]);
      } else if (opt.equals("-index") && i < argc - 1) {
        a.index = argv[++i];
      } else if (opt.equals("-key") && i < argc - 1) {
        a.key = argv[++i];
      } else if (opt.equals("-method") && i < argc - 1) {
        a.method = argv[++i];
      } else if (opt.charAt(1) == 'm') {
        a.method = argv[i].substring(2);
      } else if (opt.equals("-noattributes")) {
        a.noattributes = true;
      } else if (opt.equals("-not")) {  // read notfiles
        while (++i < argc && Text.at(argv[i], 0) != '-') {
          if (Text.at(argv[i], 0) == '=') a.nottype = argv[i];
          else a.notfiles.add(argv[i]);
        }
        --i;
      } else if (opt.equals("-only")) {  // read onlyfiles
        while (++i < argc && Text.at(argv[i], 0) != '-') a.onlyfiles.add(argv[i]);
        --i;
      } else if (opt.equals("-repack") && i < argc - 1) {
        a.repack = argv[++i];
        if (i < argc - 1 && Text.at(argv[i + 1], 0) != '-') a.newKey = argv[++i];
      } else if (opt.equals("-summary") && i < argc - 1) {
        a.summary = Text.atoi(argv[++i]);
      } else if (opt.charAt(1) == 's') {
        a.summary = Text.atoi(argv[i], 2);
      } else if (opt.equals("-test")) {
        a.dotest = true;
      } else if (opt.equals("-to")) {  // read tofiles
        while (++i < argc && Text.at(argv[i], 0) != '-') a.tofiles.add(argv[i]);
        if (a.tofiles.size() == 0) a.tofiles.add("");
        --i;
      } else if (opt.equals("-threads") && i < argc - 1) {
        a.threads = Text.atoi(argv[++i]);
      } else if (opt.charAt(1) == 't') {
        a.threads = Text.atoi(argv[i], 2);
      } else if (opt.equals("-until") && i + 1 < argc) {  // read date

        // Read digits from multiple args and fill in leading zeros
        long version = 0;
        int digits = 0;
        if (Text.at(argv[i + 1], 0) == '-') {  // negative version
          version = Text.atol(argv[i + 1]);
          if (version > -1) usage(out, a);
          ++i;
        } else {  // positive version or date
          while (++i < argc && Text.at(argv[i], 0) != '-') {
            for (int k = 0; ; ++k) {
              if (Text.isdigit(Text.at(argv[i], k))) {
                version = version * 10 + Text.at(argv[i], k) - '0';
                ++digits;
              } else {
                if (digits == 1) version = version / 10 * 100 + version % 10;
                digits = 0;
                if (Text.at(argv[i], k) == 0) break;
              }
            }
          }
          --i;
        }

        // Append default time
        if (version >= 19000000L && version <= 29991231L) version = version * 100 + 23;
        if (version >= 1900000000L && version <= 2999123123L) version = version * 100 + 59;
        if (version >= 190000000000L && version <= 299912312359L) version = version * 100 + 59;
        if (version > 9999999) {
          if (version < 19000101000000L || version > 29991231235959L) {
            out.eprint(String.format(java.util.Locale.ROOT,
                "Version date %1.0f must be 19000101000000 to 29991231235959\n", (double) version));
            throw new ExitException(1);
          }
          a.date = version;
        }
        a.version = version;
      } else {
        out.print("Unknown option ignored: " + argv[i] + "\n");
        usage(out, a);
      }
    }
  }
}
