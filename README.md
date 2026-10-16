# jpaq – ZPAQ for Java

A Java 25 port of the [ZPAQ](http://mattmahoney.net/dc/zpaq.html) journaling archiver
(zpaq 7.15 / libzpaq 7.12–7.15 by Matt Mahoney), split into

| module | artifact | contents |
|--------|----------|----------|
| [`libzpaq/`](libzpaq) | `jpaq-libzpaq` | the compression library: ZPAQ level 2 compressor / decompressor, ZPAQL compiler and interpreter, LZ77 / BWT / E8E9 preprocessors, SHA-1, SHA-256, AES-CTR, scrypt (see [Compression library](#compression-library)) |
| [`lib/`](lib) | `jpaq-lib` | the archive library: create, update, read and extract zpaq and jpaq journaling archives (deduplication, versions, encryption, multi-part archives, indexes), with files added from streams and read as streams, and archive files in any storage (see [Archive library](#archive-library)) |
| [`cli/`](cli) | `jpaq-cli` | the `zpaq` command line archiver (`add`, `extract`, `list`) on top of the archive library |
| [`cpp/`](cpp) | – | the original C++ sources the port was made from (kept as the reference implementation) |
| [`tools/difftest/`](tools/difftest) | – | differential test tools that run this port and the C++ original side by side |

**Byte-identical output.** For every option supported by the C++ `zpaq` and `libzpaq`
(compression levels 0–5, every explicit `x…` method, streaming `s` and index `i` methods, block
and fragment sizes, encryption, multi-part archives, indexes, `-noattributes`, …) the Java version
writes exactly the same archive bytes as the C++ version, and the console output has the same text.
Archives are interchangeable in both directions. See [Compatibility and testing](#compatibility-and-testing).

> **Status:** byte-identical to the C++ version and within 1.0-1.6x of its speed. See
> [Performance](#performance).

## Building

Requirements: JDK 25 and Maven 3.9+.

```
mvn package          # builds everything and runs the tests
```

This produces `cli/target/jpaq-cli-<version>-all.jar`, an executable jar with the library included:

```
java -jar cli/target/jpaq-cli-7.15.0-SNAPSHOT-all.jar a backup.zpaq ~/documents
```

`cli/src/main/scripts/zpaq` is a small launcher (sets a UTF-8 locale and heap options) that finds
that jar and can be put on the `PATH` as `zpaq`.

## Command line

The command line is that of zpaq 7.15; the manual in [`cpp/zpaq.pod`](cpp/zpaq.pod) /
[`cpp/zpaq.1`](cpp/zpaq.1) applies unchanged. In short:

```
zpaq a|add     archive[.zpaq] files... [-options]   append files whose date changed
zpaq x|extract archive[.zpaq] [files...] [-options] extract (latest versions by default)
zpaq l|list    archive[.zpaq] [files...] [-options] list, or compare with external files
```

Options: `-all [N]`, `-force`/`-f`, `-fragment N`, `-index F`, `-key X`, `-mN`/`-method N`,
`-noattributes`, `-not files… =[+-#^?]`, `-only files…`, `-repack F [X]`, `-sN`/`-summary N`,
`-test`, `-tN`/`-threads N`, `-to files…`, `-until N|date`. Archive names with `*` or `?` select
multi-part archives (`arc???.zpaq`), `""` is the empty archive.

Differences from the C++ program:

* The environment variable `SOURCE_DATE_EPOCH` (seconds since 1970) overrides the clock that is
  used for the version date of an archive update, which makes archives reproducible.
  (`-until DATE` does the same on the command line, but cannot be combined with `-index`.)
* File names must be valid UTF-8, and the JVM has to run in a UTF-8 locale (`LC_ALL=C.UTF-8`;
  the launcher script takes care of that) for non-ASCII names – Java cannot open files whose names
  are not valid in the platform encoding. Names in archives are byte strings, so archives created
  on other systems are read faithfully.
* Windows: file attributes are stored as `w` attributes (read-only, hidden, system, archive,
  directory); NTFS alternate data streams, sparse files and wildcard expansion of the C++ version
  are not implemented.
* Block sizes larger than the largest Java array limit (about 2 GiB, and less for models that need
  more than 2^31 elements) are rejected with an out-of-memory error.
* The banner line says "Java port" instead of the compile date; the `-DDEBUG` help for advanced
  options of the C++ debug build is not included (the options themselves work).

### Running the command line in process

`io.github.scf37.jpaq.cli.Zpaq` runs a command line without spawning a JVM, with the output collected
as text. Nothing calls `System.exit`, and calls may run in parallel.

```java
Zpaq.Result r = Zpaq.run(workDir, "a", "backup.zpaq", "data", "-m2", "-key", "secret");
if (!r.ok()) throw new IOException(r.stderr());      // exitCode: 0 OK, 1 warnings, 2 errors
```

For anything else use the archive library.

## Archive library

```xml
<dependency>
  <groupId>io.github.scf37.jpaq</groupId>
  <artifactId>jpaq-lib</artifactId>
  <version>7.15.0</version>
</dependency>
```

`ZpaqArchive` is a handle to an archive: where it is (a `Storage` and a name), its password, the format
it is written in, the number of threads, and an optional listener for progress. Every operation reads or
writes the archive anew; nothing is global, so operations on different archives run in parallel.

```java
ZpaqArchive archive = ZpaqArchive.of(Path.of("backup.zpaq")).withKey("secret");

// a new version: files from streams, local files, directory trees; deletions
try (ArchiveUpdate u = archive.update(UpdateOptions.DEFAULT.withMethod("2"))) {
  u.addTree(Path.of("/home/me/docs"), "docs");                 // like `zpaq add`: new and changed files,
                                                              // entries of vanished files deleted
  try (InputStream dump = pgDump.getInputStream()) {
    u.add("db/dump.sql", dump);                               // any stream, read to its end
  }
  u.addFile("etc/app.conf", Path.of("/etc/app.conf"));
  u.delete("tmp");                                            // and everything under it
  UpdateResult r = u.commit();                                // without commit: nothing happened
  log.info("version {}: +{} -{}, {} bytes new", r.version(), r.added(), r.removed(), r.compressedBytes());
}

// read a version: entries, and contents as streams
try (ArchiveReader r = archive.read(ReadOptions.LATEST.withVersion(-1))) {   // or withDate(instant)
  for (ArchiveEntry e : r.entries()) System.out.println(e.name() + " " + e.size() + " " + e.lastModified());
  try (InputStream in = r.open("db/dump.sql")) { restore(in); }
  r.forEach((entry, content) -> tar.put(entry, content));   // everything, in storage order
}

// extract to a directory, or just check everything
ExtractResult x = archive.extract(Path.of("/restore"), ExtractOptions.DEFAULT.withInclude("docs").withOverwrite(true));
if (!x.ok()) x.problems().forEach(p -> log.warn(p.text()));
archive.verify();
```

Behaviour worth knowing:

* **Updates are transactions.** Files are compressed while they are added (in background threads); the
  version exists once `commit()` returns. Closing an update without committing, or a failure while
  adding (a stream that throws, a storage that fails), leaves the archive as it was. An update that
  changes nothing writes nothing.
* **Updates only touch what they name.** Unlike `zpaq add`, entries the update does not mention are kept.
  `addTree(dir, name)` makes the archive under `name` like `dir` (it deletes entries of files that are gone
  there) and fails if `dir` does not exist instead of deleting everything.
* **Contents are verified.** Every fragment read through `ArchiveReader` is checked against its SHA-1;
  a damaged one makes the stream throw. Extraction reports damaged files in `ExtractResult.problems()`
  and extracts the rest.
* **Extraction is confined.** Names are extracted under the target directory (a leading `/` or drive
  letter is dropped); names with `..` components are not extracted.
* **Errors** are `IOException`s: `NoSuchFileException` for a missing archive or file,
  `ArchiveException` for archives that are damaged, not archives, or have another password.
  `UnsupportedStorageException` (unchecked) when the storage cannot do what the format needs.
* **Byte for byte.** The library writes the same archives as the command line, which writes the same
  archives as zpaq 7.15.
* **File names** must be valid UTF-8 and, to name local files that are not ASCII, the JVM must run in a
  UTF-8 locale (see the command line notes). Streams in and out have no such limit.

The engine package (`io.github.scf37.jpaq.engine`) is exported only to the command line module; the
public API is the package `io.github.scf37.jpaq` (module `io.github.scf37.jpaq`).

### Storage and the sequential format

Archive files (parts/tomes and index files) are read and written through `Storage`: streaming
`read(name)`, `write(name)` and `delete(name)`, which is what a shell script can do, plus capabilities
a storage may declare: `PROBE` (`exists`, `length`), `RANGED_READ` (start reading at an offset) and
`RANDOM_WRITE` (positional read/write/truncate). A failed operation is just a failure: the archiver never
interprets the reason. `LocalFsStorage` (all capabilities) is what the command line uses;
`ShellStorage` runs three shell commands (`cat "$1"`, `aws s3 cp - "s3://bucket/$1"`, ...).

* `Format.CLASSIC`: the zpaq 7.15 format, byte identical to the C++ program. Writing needs `RANDOM_WRITE`
  and `PROBE` (the transaction header is patched at the end and empty updates are truncated).
* `Format.SEQUENTIAL` ("jpaq" archives, `.jpaq`): every tome and index file is written front to back, once,
  nothing is patched or truncated. Needs only streaming read/write/delete and an index. See
  [docs/SEQUENTIAL.md](docs/SEQUENTIAL.md). Stock zpaq cannot read these archives (and says so cleanly).

```java
Storage s3 = new ShellStorage("aws s3 cp \"s3://bkp/$1\" -", "aws s3 cp - \"s3://bkp/$1\"", "aws s3 rm \"s3://bkp/$1\"");
ZpaqArchive a = ZpaqArchive.of(s3, "home???.jpaq").withFormat(Format.SEQUENTIAL).withKey(key);
try (ArchiveUpdate u = a.update(UpdateOptions.DEFAULT.withIndex("index-41.jpaq").withIndexOut("index-42.jpaq"))) {
  u.addTree(home, "home");
  u.commit();                    // writes home042.jpaq, then index-42.jpaq; index-41.jpaq is not touched
}
try (ArchiveReader r = a.read(ReadOptions.LATEST.withIndex("index-42.jpaq"))) { ... }
```

The jpaq command line reads both formats and any file extension; it writes the classic format.

## Compression library

```java
import io.github.scf37.jpaq.libzpaq.*;

// whole buffers
byte[] packed = Libzpaq.compress(data, "3");           // method "0".."5" or "x..." (see libzpaq.h)
byte[] back   = Libzpaq.decompress(packed);

// streams
try (var in = Files.newInputStream(src); var out = Files.newOutputStream(dst)) {
  Libzpaq.compress(in, out, "14");                     // level 1, 16 MiB blocks
}

// full control: blocks, segments, file names, comments, checksums
Compressor c = new Compressor();
c.setOutput(ZpaqWriter.of(out));
c.writeTag();
c.startBlock(2);                                       // built in level 2, or ZPAQL source
c.startSegment("name", "comment");
c.setInput(ZpaqReader.of(in));
c.compress();
c.endSegment(sha1Bytes);
c.endBlock();

Decompresser d = new Decompresser();
d.setInput(ZpaqReader.of(archiveBytes));
while (d.findBlock()) {
  while (d.findFilename(nameSink)) {
    d.readComment(commentSink);
    d.setOutput(dataSink);
    d.decompress();
    d.readSegmentEnd(checksum21);
  }
}
```

`ZpaqReader` / `ZpaqWriter` are the byte source / sink abstractions (`of(InputStream)`,
`of(OutputStream)`, `of(byte[])` adapters are provided). `Crypto` has `stretchKey` (scrypt),
`random`; `AesCtr`, `Sha1`, `Sha256` and `MemoryBuffer` are the other public building
blocks. The classes follow the structure of `libzpaq.h`, whose documentation (in [`cpp/libzpaq.h`](cpp/libzpaq.h))
describes the method strings, the ZPAQL language and the file format.

## Compatibility and testing

The port was verified against the C++ implementation compiled from [`cpp/`](cpp):

* **Unit tests** (`mvn test`): `libzpaq` compares the output for 300+ combinations of inputs and
  methods with the SHA-256 of the C++ output (`libzpaq/src/test/resources/golden`); `cli` runs
  scenarios (all levels, updates, deletions, `-all`/`-until`, streaming, multi-part, index,
  encrypted, repack, listings, extraction) and compares archive hashes, listing output and the
  extracted trees with values recorded from the C++ program (`cli/src/test/resources`), and checks
  that the archive library writes the same bytes as the command line; `lib` tests the library API
  (streams, versions, abandoned and failed updates, damaged archives, the sequential format on
  stream-only storage, parallel use).
* **Differential tests** (`tools/difftest`): run both programs on the same inputs and compare
  the archives, the console output, exit codes and the files and attributes they extract. This
  includes a random-tree fuzzer over methods, options and update sequences.

Known harmless differences: timing and thread numbers in messages, and the banner line.

## Performance

Measured on a 4 core machine with JDK 25 (details in [docs/OPTIMIZATION.md](docs/OPTIMIZATION.md)):
`-m1` on 173 MB takes about as long as the C++ version (5.8 s vs 5.9 s), the context mixing levels
`-m3`..`-m5` are about 1.4-1.5x slower than the C++ version with its x86 JIT, `-m2` about 1.6x
(`-m4` on 12 MB of text: 17.6 s vs 12.4 s, `-m5`: 61 s vs 44 s). ZPAQL programs and the context
mixing predictor are compiled to JVM bytecode at run time (class-file API, one hidden class per
model; `-Djpaq.zpaqlJit=false` / `-Djpaq.predictorJit=false` select the plain interpreters). The
suffix array used by BWT and LZ77-SA modes is SA-IS rather than divsufsort.

## License

The ZPAQ sources are public domain (Matt Mahoney); see [COPYING](COPYING). The same terms apply
to this port. The MIT-licensed libdivsufsort code embedded in the C++ `libzpaq.cpp` is *not* part
of the port: the Java version builds suffix arrays with its own SA-IS implementation (the suffix
array of a string is unique, so the archives are identical).
