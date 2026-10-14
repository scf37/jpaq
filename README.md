# jpaq – ZPAQ for Java

A Java 25 port of the [ZPAQ](http://mattmahoney.net/dc/zpaq.html) journaling archiver
(zpaq 7.15 / libzpaq 7.12–7.15 by Matt Mahoney), split into

| module | artifact | contents |
|--------|----------|----------|
| [`libzpaq/`](libzpaq) | `jpaq-libzpaq` | the compression library: ZPAQ level 2 compressor / decompressor, ZPAQL compiler and interpreter, LZ77 / BWT / E8E9 preprocessors, SHA-1, SHA-256, AES-CTR, scrypt |
| [`cli/`](cli) | `jpaq-cli` | the `zpaq` command line archiver (`add`, `extract`, `list`; journaling, deduplication, encryption, multi-part archives, indexes, streaming format) built on the library, and a Java API with pluggable archive storage and typed output (see [Calling the archiver from Java](#calling-the-archiver-from-java)) |
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

### Calling the archiver from Java

Applications (for example a backup program) run the archiver in-process through
`io.github.scf37.jpaq.cli.Zpaq`, without spawning a JVM. Nothing calls `System.exit`.

**With command line arguments** (output collected as text):

```java
Zpaq.Result r = Zpaq.run(workDir, "a", "backup.zpaq", "data", "-m2", "-key", "secret");
if (!r.ok()) throw new IOException(r.stderr());      // exitCode: 0 OK, 1 warnings, 2 errors
String listing = Zpaq.run(workDir, "l", "backup.zpaq").stdout();
```

**Typed**, with archive files in any storage and the output as objects:

```java
Storage storage = new ShellStorage("cat \"$1\"", "cat > \"$1\"", "rm -f \"$1\"", dir); // or your own
Options o = new Options().method("2").key("secret").threads(4);
int rc = Zpaq.run(storage, workDir,
    new Command.Add("data???.jpaq", List.of("home/docs"), o, Format.SEQUENTIAL, "index-41.jpaq", "index-42.jpaq"),
    line -> {
      switch (line) {
        case InfoLine.FileAdded f -> log("added " + f.name() + " " + f.size());
        case ErrorLine.FileError e -> warn(e.name() + ": " + e.reason());
        default -> { }
      }
    });
try (Stream<Line> lines = Zpaq.stream(storage, workDir, new Command.Extract(...))) { ... }
```

Commands are `Add`, `Extract`, `ListArchive` and `RebuildIndex`. Output is `InfoLine` (what the command
line prints to stdout, plus result statistics) and `ErrorLine` (problems) records such as `FileAdded`,
`FileExtracted`, `ListEntry`, `Progress`, `Finished`, `FileError`, `Fatal`; `line.text()` is exactly the
text the command line prints and `line.channel()` where. Calls are serialized (the program keeps
global state).

#### Storage and the sequential format

Archive files (parts/tomes and index files) are read and written through `api.Storage`: streaming
`read(name)`, `write(name)` and `delete(name)`, which is what a shell script can do, plus capabilities
a storage may declare: `PROBE` (`exists`, `length`), `RANGED_READ` (start reading at an offset) and
`RANDOM_WRITE` (positional read/write/truncate). A failed operation is just a failure: the archiver never
interprets the reason. `LocalFsStorage` (all capabilities) is what the command line uses.

* `Format.CLASSIC`: the zpaq 7.15 format, byte identical to the C++ program. Writing needs `RANDOM_WRITE`
  and `PROBE` (the transaction header is patched at the end and empty updates are truncated).
* `Format.SEQUENTIAL` ("jpaq" archives, `.jpaq`): every tome and index file is written front to back, once,
  nothing is patched or truncated. Needs only streaming read/write/delete and an index. See
  [docs/SEQUENTIAL.md](docs/SEQUENTIAL.md). Stock zpaq cannot read these archives (and says so cleanly).

The jpaq command line reads both formats and any file extension; it writes the classic format.

## Library

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
  extracted trees with values recorded from the C++ program (`cli/src/test/resources`).
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
