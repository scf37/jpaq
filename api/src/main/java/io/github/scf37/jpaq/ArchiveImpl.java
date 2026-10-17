package io.github.scf37.jpaq;

import io.github.scf37.jpaq.engine.Ctx;
import io.github.scf37.jpaq.engine.Jidac;
import io.github.scf37.jpaq.engine.Strs;
import io.github.scf37.jpaq.libzpaq.ZpaqException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** The implementation of {@link ZpaqArchive} and of the types that come with it. */
final class ArchiveImpl implements ZpaqArchive {
  private final Storage storage;
  private final String name;
  private final String key;
  private final Format format;
  private final int threads;
  private final Path directory;
  private final Clock clock;
  private final Consumer<? super Event> listener;

  private ArchiveImpl(Storage storage, String name, String key, Format format, int threads,
      Path directory, Clock clock, Consumer<? super Event> listener) {
    this.storage = storage;
    this.name = name;
    this.key = key;
    this.format = format;
    this.threads = threads;
    this.directory = directory;
    this.clock = clock;
    this.listener = listener;
  }

  static ZpaqArchive of(Storage storage, String name, Option... options) {
    return new ArchiveImpl(storage, name, null, Format.CLASSIC, 0, null, null, null)
        .withOptions(options);
  }

  // ---------------------------------------------------------------------------------------
  // Options

  /** The options other than formats. */
  sealed interface Opt extends Option {
    record Key(String password) implements Opt {}

    record Threads(int threads) implements Opt {}

    record Directory(Path directory) implements Opt {}

    record Time(Clock clock) implements Opt {}

    record Listener(Consumer<? super Event> listener) implements Opt {}
  }

  @Override
  public ZpaqArchive withOptions(Option... options) {
    String key = this.key;
    Format format = this.format;
    int threads = this.threads;
    Path directory = this.directory;
    Clock clock = this.clock;
    Consumer<? super Event> listener = this.listener;
    for (Option o : options) {
      switch (o) {
        case Format f -> format = f;
        case Opt.Key k -> key = k.password();
        case Opt.Threads t -> threads = t.threads();
        case Opt.Directory d -> directory = d.directory();
        case Opt.Time t -> clock = t.clock();
        case Opt.Listener l -> listener = l.listener();
      }
    }
    return new ArchiveImpl(storage, name, key, format, threads, directory, clock, listener);
  }

  @Override
  public Storage storage() {
    return storage;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Format format() {
    return format;
  }

  @Override
  public String toString() {
    return "ZpaqArchive[" + name + ", " + format + (key != null ? ", encrypted" : "") + "]";
  }

  // ---------------------------------------------------------------------------------------
  // Operations

  @Override
  public ArchiveContents read(Consumer<? super ReadBuilder> spec) throws IOException {
    ReadSpec s = new ReadSpec();
    spec.accept(s);
    Op op = op(null);
    Jidac j = op.j();
    s.applyTo(j);
    op.call(() -> {
      j.readContents();
      return null;
    });
    return new Contents(j);
  }

  @Override
  public AddResult add(Consumer<? super AddBuilder> spec) throws IOException {
    AddSpec s = new AddSpec();
    spec.accept(s);
    if (s.files.isEmpty()) throw new IllegalArgumentException("no files to add");
    if (format == Format.SEQUENTIAL) {
      if (s.indexOut == null)
        throw new IllegalArgumentException("the sequential format needs indexOut");
      if (name.indexOf('?') < 0 && name.indexOf('*') < 0)
        throw new IllegalArgumentException(
            "a sequential archive is a set of tomes: its name needs a part number, like arc???.jpaq");
    } else if (s.indexOut != null) {
      throw new IllegalArgumentException("indexOut is for the sequential format");
    }
    final Event.AddSummary[] summary = {null};
    final Event.AddTotals[] totals = {null};
    Op op = op(e -> {
      if (e instanceof Event.AddSummary a) summary[0] = a;
      if (e instanceof Event.AddTotals t) totals[0] = t;
    }, s.date);
    Jidac j = op.j();
    j.command = 'a';
    s.applyTo(j);
    op.call(j::run);
    int added = summary[0] != null ? summary[0].added() : 0;
    int removed = summary[0] != null ? summary[0].removed() : 0;
    Event.AddTotals t = totals[0];
    return t == null ? new Added(added, removed, 0, 0, 0, 0, j.errors)
        : new Added(added, removed, t.inputBytes(), t.dedupedBytes(), t.addedBytes(),
            t.archiveBytes(), j.errors);
  }

  @Override
  public ExtractResult extract(Consumer<? super ExtractBuilder> spec) throws IOException {
    ExtractSpec s = new ExtractSpec();
    spec.accept(s);
    final int[] counts = {0, 0};  // files planned, skipped
    Op op = op(e -> {
      if (e instanceof Event.ExtractPlan p) counts[0] = p.files();
      if (e instanceof Event.SkippedSummary k) counts[1] = k.count();
    });
    Jidac j = op.j();
    j.command = 'x';
    s.applyTo(j);
    if (s.output != null) j.toOutput = wrap(s.output);
    j.force = s.overwrite;
    j.dotest = s.test;
    op.call(j::run);
    return new Extracted(counts[0], counts[1], j.errors);
  }

  @Override
  public void repack(String target, Consumer<? super RepackBuilder> spec) throws IOException {
    RepackSpec s = new RepackSpec();
    spec.accept(s);
    Op op = op(null);
    Jidac j = op.j();
    j.command = 'x';
    s.applyTo(j);
    if (s.rename != null) j.toOutput = wrap(s.rename);
    j.force = s.overwrite;
    j.repack = Strs.toBytes(target);
    if (s.key != null) j.newPassword = Jidac.hashPassword(Strs.toBytes(s.key));
    op.call(j::run);
  }

  @Override
  public void copy(String target, Consumer<? super CopyBuilder> spec) throws IOException {
    CopySpec s = new CopySpec();
    spec.accept(s);
    Op op = op(null);
    Jidac j = op.j();
    j.command = 'x';
    j.all = 4;
    j.force = s.overwrite;
    j.repack = Strs.toBytes(target);
    if (s.key != null) j.newPassword = Jidac.hashPassword(Strs.toBytes(s.key));
    op.call(j::run);
  }

  @Override
  public void writeIndex(String index, Consumer<? super IndexBuilder> spec) throws IOException {
    IndexSpec s = new IndexSpec();
    spec.accept(s);
    Op op = op(null);
    Jidac j = op.j();
    j.command = 'x';
    s.applyTo(j);
    j.index = Strs.toBytes(index);
    j.force = s.overwrite;
    op.call(j::run);
  }

  @Override
  public List<LocalFile> scan(Consumer<? super ScanBuilder> spec) {
    ScanSpec s = new ScanSpec();
    spec.accept(s);
    Op op = op(null);
    Jidac j = op.j();
    s.applyTo(j);
    List<LocalFile> r = new ArrayList<>();
    j.scanLocal((n, date, size, attr, frags) ->
        r.add(new Local(n, date, size, Attributes.of(attr))));
    op.guard().rethrow();
    return List.copyOf(r);
  }

  // ---------------------------------------------------------------------------------------
  // Builders

  /** {@link ArchiveSelector}: what to read. */
  private abstract static class Selection<B> {
    private String index;
    private Long version;
    private Instant until;
    private int versionDigits;
    private Predicate<String> select;
    private boolean noAttributes;

    @SuppressWarnings("unchecked")
    private B self() {
      return (B) this;
    }

    public B index(String index) {
      this.index = index;
      return self();
    }

    public B version(long version) {
      this.version = version;
      this.until = null;
      return self();
    }

    public B until(Instant time) {
      this.until = time;
      this.version = null;
      return self();
    }

    public B allVersions(int digits) {
      if (digits < 0 || digits > 18) throw new IllegalArgumentException("digits: " + digits);
      this.versionDigits = digits;
      return self();
    }

    public B select(Predicate<String> names) {
      this.select = names;
      return self();
    }

    public B noAttributes(boolean noAttributes) {
      this.noAttributes = noAttributes;
      return self();
    }

    void applyTo(Jidac j) {
      if (index != null) j.readIndex = Strs.toBytes(index);
      if (version != null) j.version = version;
      if (until != null) j.version = decimal(until);
      j.all = versionDigits;
      if (select != null) j.select = wrap(select);
      j.noattributes = noAttributes;
    }
  }

  static final class ReadSpec extends Selection<ReadBuilder> implements ReadBuilder {}

  static final class ExtractSpec extends Selection<ExtractBuilder> implements ExtractBuilder {
    private UnaryOperator<String> output;
    private boolean overwrite;
    private boolean test;

    @Override
    public ExtractBuilder output(UnaryOperator<String> output) {
      this.output = output;
      return this;
    }

    @Override
    public ExtractBuilder under(String directory) {
      return output(underDirectory(directory));
    }

    @Override
    public ExtractBuilder overwrite(boolean overwrite) {
      this.overwrite = overwrite;
      return this;
    }

    @Override
    public ExtractBuilder test(boolean test) {
      this.test = test;
      return this;
    }
  }

  static final class RepackSpec extends Selection<RepackBuilder> implements RepackBuilder {
    private UnaryOperator<String> rename;
    private String key;
    private boolean overwrite;

    @Override
    public RepackBuilder rename(UnaryOperator<String> rename) {
      this.rename = rename;
      return this;
    }

    @Override
    public RepackBuilder key(String password) {
      this.key = password;
      return this;
    }

    @Override
    public RepackBuilder overwrite(boolean overwrite) {
      this.overwrite = overwrite;
      return this;
    }
  }

  static final class IndexSpec extends Selection<IndexBuilder> implements IndexBuilder {
    private boolean overwrite;

    @Override
    public IndexBuilder overwrite(boolean overwrite) {
      this.overwrite = overwrite;
      return this;
    }
  }

  static final class CopySpec implements CopyBuilder {
    private String key;
    private boolean overwrite;

    @Override
    public CopyBuilder key(String password) {
      this.key = password;
      return this;
    }

    @Override
    public CopyBuilder overwrite(boolean overwrite) {
      this.overwrite = overwrite;
      return this;
    }
  }

  /** {@link LocalSelector}: which local files. */
  private abstract static class Locals<B> {
    final List<String> files = new ArrayList<>();
    private Predicate<String> prune;
    private Predicate<String> accept;
    private boolean noAttributes;

    @SuppressWarnings("unchecked")
    private B self() {
      return (B) this;
    }

    public B files(String... files) {
      return files(Arrays.asList(files));
    }

    public B files(Collection<String> files) {
      for (String f : files) this.files.add(Objects.requireNonNull(f, "file"));
      return self();
    }

    public B prune(Predicate<String> prune) {
      this.prune = prune;
      return self();
    }

    public B accept(Predicate<String> accept) {
      this.accept = accept;
      return self();
    }

    public B noAttributes(boolean noAttributes) {
      this.noAttributes = noAttributes;
      return self();
    }

    void applyTo(Jidac j) {
      for (String f : files) j.roots.add(Strs.toBytes(f));
      if (prune != null) j.prune = wrap(prune);
      if (accept != null) j.accept = wrap(accept);
      j.noattributes = noAttributes;
    }
  }

  static final class ScanSpec extends Locals<ScanBuilder> implements ScanBuilder {}

  static final class AddSpec extends Locals<AddBuilder> implements AddBuilder {
    private UnaryOperator<String> archiveName;
    private Predicate<String> scope;
    private String method = "1";
    private int fragment = 6;
    private boolean force;
    private String index;
    String indexOut;
    Instant date;
    private Long rollbackVersion;
    private Instant rollbackTime;

    @Override
    public AddBuilder archiveName(UnaryOperator<String> archiveName) {
      this.archiveName = archiveName;
      return this;
    }

    @Override
    public AddBuilder scope(Predicate<String> scope) {
      this.scope = scope;
      return this;
    }

    @Override
    public AddBuilder method(String method) {
      this.method = method == null || method.isEmpty() ? "1" : method;
      return this;
    }

    @Override
    public AddBuilder fragment(int log2Kib) {
      this.fragment = log2Kib;
      return this;
    }

    @Override
    public AddBuilder force(boolean force) {
      this.force = force;
      return this;
    }

    @Override
    public AddBuilder index(String index) {
      this.index = index;
      return this;
    }

    @Override
    public AddBuilder indexOut(String indexOut) {
      this.indexOut = indexOut;
      return this;
    }

    @Override
    public AddBuilder date(Instant date) {
      this.date = date;
      return this;
    }

    @Override
    public AddBuilder rollback(long version) {
      this.rollbackVersion = version;
      this.rollbackTime = null;
      return this;
    }

    @Override
    public AddBuilder rollback(Instant time) {
      this.rollbackTime = time;
      this.rollbackVersion = null;
      return this;
    }

    @Override
    void applyTo(Jidac j) {
      super.applyTo(j);
      if (archiveName != null) j.toArchive = wrap(archiveName);
      j.select = scope != null ? wrap(scope) : defaultScope(j);
      j.method = method;
      j.fragment = fragment;
      j.force = force;
      if (index != null) j.index = Strs.toBytes(index);
      if (indexOut != null) j.indexOut = Strs.toBytes(indexOut);
      if (rollbackVersion != null) j.version = rollbackVersion;
      if (rollbackTime != null) j.version = decimal(rollbackTime);
    }

    /** The archive names of the roots and everything under them. */
    private static Predicate<String> defaultScope(Jidac j) {
      List<String> names = new ArrayList<>();
      for (String r : j.roots) {
        String n = j.toArchive.apply(r);
        while (n.length() > 1 && n.endsWith("/")) n = n.substring(0, n.length() - 1);
        names.add(n);
      }
      return n -> {
        for (String r : names) {
          if (n.startsWith(r) && (n.length() == r.length() || n.charAt(r.length()) == '/'
              || r.endsWith("/"))) return true;
        }
        return false;
      };
    }
  }

  // ---------------------------------------------------------------------------------------
  // Results and models

  record Added(int added, int removed, long inputBytes, long dedupedBytes, long addedBytes,
      long archiveBytes, int unreadable) implements AddResult {}

  record Extracted(int files, int skipped, int failed) implements ExtractResult {}

  record Local(String name, long date, long size, Attributes attributes) implements LocalFile {}

  record Attrs(long raw) implements Attributes {}

  record ContentsEntry(String name, long date, long size, Attributes attributes, int[] frags)
      implements ArchiveContents.Entry {
    @Override
    public int[] fragments() {
      return frags.clone();
    }

    @Override
    public String toString() {
      return "Entry[" + name + ", " + date + ", " + size + "]";
    }
  }

  record ContentsVersion(int number, long date, int updates, int deletes, long offset,
      int firstFragment) implements ArchiveContents.Version {}

  /** The file list read by a Jidac. */
  static final class Contents implements ArchiveContents {
    private final Jidac j;
    private final List<Entry> entries;
    private final List<Version> versions;

    Contents(Jidac j) {
      this.j = j;
      List<Entry> e = new ArrayList<>();
      j.entries((n, date, size, attr, frags) ->
          e.add(new ContentsEntry(n, date, size, Attributes.of(attr), frags)));
      List<Version> v = new ArrayList<>();
      j.versions((n, date, updates, deletes, offset, first) ->
          v.add(new ContentsVersion(n, date, updates, deletes, offset, first)));
      this.entries = List.copyOf(e);
      this.versions = List.copyOf(v);
    }

    @Override
    public List<Entry> entries() {
      return entries;
    }

    @Override
    public List<Version> versions() {
      return versions;
    }

    @Override
    public int fragmentCount() {
      return j.fragmentCount();
    }

    @Override
    public int fragmentSize(int id) {
      return j.fragmentSize(id);
    }

    @Override
    public long end() {
      return j.end();
    }

    @Override
    public long dataSize() {
      return j.indexedDataSize();
    }

    @Override
    public long storedDataSize() {
      return j.storedDataSize();
    }

    @Override
    public boolean sameContent(Entry entry, String localName) {
      return j.sameContent(Strs.toBytes(entry.name()), Strs.toBytes(localName));
    }
  }

  // ---------------------------------------------------------------------------------------
  // Running the engine

  /** One command on the archive: the engine with its options, and the guard of its events. */
  private record Op(Jidac j, ListenerGuard guard) {
    /** Runs the command; its failures become the exceptions of the API. */
    <T> T call(Supplier<T> action) throws IOException {
      T r;
      try {
        r = action.get();
      } catch (ZpaqException e) {
        guard.rethrow();
        throw new ArchiveException(Strs.fromBytes(Jidac.what(e)), e);
      } catch (UncheckedIOException e) {
        guard.rethrow();
        throw e.getCause();
      } catch (RuntimeException e) {
        guard.rethrow();
        throw e;
      }
      guard.rethrow();
      return r;
    }
  }

  private Op op(Consumer<Event> spy) {
    return op(spy, null);
  }

  /** A command on this archive; its events go to spy (if any) and then to the listener. */
  private Op op(Consumer<Event> spy, Instant now) {
    ListenerGuard guard = new ListenerGuard(spy, listener);
    if (now == null && clock != null) now = clock.instant();
    Jidac j = new Jidac(new Ctx(guard, directory, now), storage, format);
    j.archive = Strs.toBytes(name);
    if (key != null) j.password = Jidac.hashPassword(Strs.toBytes(key));
    j.threads = threads;
    j.date = j.ctx.now();
    return new Op(j, guard);
  }

  /** Delivers events; remembers the first exception of the listener and delivers no more. */
  private static final class ListenerGuard implements Consumer<Event> {
    private final Consumer<Event> spy;
    private final Consumer<? super Event> listener;
    private volatile RuntimeException failure;

    ListenerGuard(Consumer<Event> spy, Consumer<? super Event> listener) {
      this.spy = spy;
      this.listener = listener;
    }

    @Override
    public void accept(Event e) {
      if (spy != null) spy.accept(e);
      if (listener == null || failure != null) return;
      try {
        listener.accept(e);
      } catch (RuntimeException ex) {
        failure = ex;
      }
    }

    void rethrow() {
      if (failure != null) throw failure;
    }
  }

  // ---------------------------------------------------------------------------------------
  // Names and dates

  static String decodeName(byte[] name) {
    return Strs.fromBytes(new String(name, StandardCharsets.ISO_8859_1));
  }

  static byte[] encodeName(String name) {
    return Strs.toBytes(name).getBytes(StandardCharsets.ISO_8859_1);
  }

  /** A predicate on real names as one on byte strings. */
  private static Predicate<String> wrap(Predicate<String> p) {
    return n -> p.test(Strs.fromBytes(n));
  }

  /** A mapping of real names as one of byte strings. */
  private static UnaryOperator<String> wrap(UnaryOperator<String> f) {
    return n -> {
      String r = f.apply(Strs.fromBytes(n));
      return r == null ? null : Strs.toBytes(r);
    };
  }

  /**
   * The output mapping of {@link ExtractBuilder#under}: every entry under {@code dir}, without a
   * leading '/' or drive letter; names with a ".." component are skipped.
   */
  static UnaryOperator<String> underDirectory(String dir) {
    return name -> {
      for (String c : name.split("[/\\\\]")) if (c.equals("..")) return null;
      String n = name;
      if (n.length() > 1 && n.charAt(1) == ':') n = n.substring(2);
      while (n.startsWith("/")) n = n.substring(1);
      return dir.endsWith("/") ? dir + n : dir + "/" + n;
    };
  }

  /** The instant of a decimal date YYYYMMDDHHMMSS (UT). */
  static Instant instant(long date) {
    return Instant.ofEpochSecond(io.github.scf37.jpaq.engine.Dates.unixTime(date));
  }

  /** Decimal date YYYYMMDDHHMMSS (UT). */
  private static long decimal(Instant t) {
    ZonedDateTime z = t.atZone(ZoneOffset.UTC);
    return z.getYear() * 10000000000L + z.getMonthValue() * 100000000L
        + z.getDayOfMonth() * 1000000L + z.getHour() * 10000L + z.getMinute() * 100L
        + z.getSecond();
  }
}
