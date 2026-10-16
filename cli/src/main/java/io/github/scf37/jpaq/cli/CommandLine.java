package io.github.scf37.jpaq.cli;

import io.github.scf37.jpaq.InfoLine;
import io.github.scf37.jpaq.engine.Ctx;
import io.github.scf37.jpaq.engine.Dates;
import io.github.scf37.jpaq.engine.Jidac;
import io.github.scf37.jpaq.engine.Strs;

/** The zpaq 7.15 command line: parses the arguments into the options of a {@link Jidac}. */
final class CommandLine {
  private CommandLine() {}

  /**
   * Parses the command line and executes the command. Arguments are byte strings, argv[0] is the
   * program name. Returns 0 OK, 1 warnings; throws {@link ExitException} after printing the usage.
   */
  static int run(Ctx ctx, Jidac j, String[] argv) {
    ctx.emit(new InfoLine.Banner(Jidac.ZPAQ_VERSION,
        "zpaq v" + Jidac.ZPAQ_VERSION + " journaling archiver, Java port\n"));
    j.date = ctx.now();
    parse(ctx, j, argv);
    j.prepare();
    if (!j.hasCommand()) usage(ctx, j);
    return j.execute();
  }

  /** Prints the help message and exits. */
  private static void usage(Ctx ctx, Jidac j) {
    ctx.print(String.format(java.util.Locale.ROOT, """
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
        """, j.threads, Dates.dateToString(j.date)));
    throw new ExitException(1);
  }

  private static void parse(Ctx ctx, Jidac j, String[] argv) {
    final int argc = argv.length;
    for (int i = 1; i < argc; ++i) {
      final String opt = argv[i];  // read command
      if ((opt.equals("add") || opt.equals("extract") || opt.equals("list") || opt.equals("convert")
          || opt.equals("a") || opt.equals("x") || opt.equals("l") || opt.equals("c"))
          && i < argc - 1 && Strs.at(argv[i + 1], 0) != '-' && j.command == 0) {
        j.command = opt.charAt(0);
        if (opt.equals("extract")) j.command = 'x';
        j.archive = argv[++i];  // append ".zpaq" to archive if no extension
        int slash = argv[i].lastIndexOf('/');
        int dot = argv[i].lastIndexOf('.');
        if (slash >= 0 && dot < slash) dot = -1;
        if (dot < 0 && !j.archive.isEmpty()) j.archive += ".zpaq";
        while (++i < argc && Strs.at(argv[i], 0) != '-')  // read filename args
          j.files.add(argv[i]);
        --i;
      } else if (opt.length() < 2 || opt.charAt(0) != '-') {
        usage(ctx, j);
      } else if (opt.equals("-all")) {
        j.all = 4;
        if (i < argc - 1 && Strs.isdigit(Strs.at(argv[i + 1], 0))) j.all = Strs.atoi(argv[++i]);
      } else if (opt.equals("-force") || opt.equals("-f")) {
        j.force = true;
      } else if (opt.equals("-fragment") && i < argc - 1) {
        j.fragment = Strs.atoi(argv[++i]);
      } else if (opt.equals("-index") && i < argc - 1) {
        j.index = argv[++i];
      } else if (opt.equals("-key") && i < argc - 1) {
        j.password = Jidac.hashPassword(argv[++i]);
      } else if (opt.equals("-method") && i < argc - 1) {
        j.method = argv[++i];
      } else if (opt.charAt(1) == 'm') {
        j.method = argv[i].substring(2);
      } else if (opt.equals("-noattributes")) {
        j.noattributes = true;
      } else if (opt.equals("-not")) {  // read notfiles
        while (++i < argc && Strs.at(argv[i], 0) != '-') {
          if (Strs.at(argv[i], 0) == '=') j.nottype = argv[i];
          else j.notfiles.add(argv[i]);
        }
        --i;
      } else if (opt.equals("-only")) {  // read onlyfiles
        while (++i < argc && Strs.at(argv[i], 0) != '-') j.onlyfiles.add(argv[i]);
        --i;
      } else if (opt.equals("-repack") && i < argc - 1) {
        j.repack = argv[++i];
        if (i < argc - 1 && Strs.at(argv[i + 1], 0) != '-')
          j.newPassword = Jidac.hashPassword(argv[++i]);
      } else if (opt.equals("-summary") && i < argc - 1) {
        j.summary = Strs.atoi(argv[++i]);
      } else if (opt.charAt(1) == 's') {
        j.summary = Strs.atoi(argv[i], 2);
      } else if (opt.equals("-test")) {
        j.dotest = true;
      } else if (opt.equals("-to")) {  // read tofiles
        while (++i < argc && Strs.at(argv[i], 0) != '-') j.tofiles.add(argv[i]);
        if (j.tofiles.size() == 0) j.tofiles.add("");
        --i;
      } else if (opt.equals("-threads") && i < argc - 1) {
        j.threads = Strs.atoi(argv[++i]);
      } else if (opt.charAt(1) == 't') {
        j.threads = Strs.atoi(argv[i], 2);
      } else if (opt.equals("-until") && i + 1 < argc) {  // read date

        // Read digits from multiple args and fill in leading zeros
        long version = 0;
        int digits = 0;
        if (Strs.at(argv[i + 1], 0) == '-') {  // negative version
          version = Strs.atol(argv[i + 1]);
          if (version > -1) usage(ctx, j);
          ++i;
        } else {  // positive version or date
          while (++i < argc && Strs.at(argv[i], 0) != '-') {
            for (int k = 0; ; ++k) {
              if (Strs.isdigit(Strs.at(argv[i], k))) {
                version = version * 10 + Strs.at(argv[i], k) - '0';
                ++digits;
              } else {
                if (digits == 1) version = version / 10 * 100 + version % 10;
                digits = 0;
                if (Strs.at(argv[i], k) == 0) break;
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
            ctx.eprint(String.format(java.util.Locale.ROOT,
                "Version date %1.0f must be 19000101000000 to 29991231235959\n", (double) version));
            throw new ExitException(1);
          }
          j.date = version;
        }
        j.version = version;
      } else {
        ctx.print("Unknown option ignored: " + argv[i] + "\n");
        usage(ctx, j);
      }
    }
  }
}
