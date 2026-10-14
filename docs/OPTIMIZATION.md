# Optimization notes

The current code is the *working* version of the port: it reproduces the C++ output bit for bit
(see the test suites) and is structured like the original so that it can be compared line by
line. No effort has been spent on speed yet. This file records the baseline and the places where
the time goes, so the optimization step can start from facts. The invariant for all of it: the
archives must stay byte-identical (`mvn verify` and `tools/difftest`).

## Baseline (4 cores, JDK 25, default JVM flags + `-XX:+UseParallelGC`)

Input: 217 MB of mixed files (JDK `modules` image, `libjvm.so`, `src.zip`, sources) for `-m1`/`-m2`,
19 MB subset for `-m3`..`-m5`; `zpaq a archive dir -mN -until 20250101000000`, wall clock.

| level | C++ (g++ -O2, JIT) | Java | ratio |
|-------|--------------------|------|-------|
| `-m1` (217 MB) | 7.3 s | 9.0 s | 1.2x |
| `-m2` (217 MB) | 33 s, 1.0 GB RSS | 53 s, 1.7 GB RSS | 1.6x |
| `-m2` (19 MB) | 5.9 s | 11.2 s | 1.9x |
| `-m3` (19 MB) | 11.4 s | 22.6 s | 2.0x |
| `-m4` (19 MB) | 26.1 s | 55.8 s | 2.1x |
| `-m5` (19 MB) | 98 s | 198 s | 2.0x |

Extraction speed behaves the same (decompression of `-m5`: 103 s C++, 197 s Java).

## Where the time goes

1. **Predictor** (`Predictor.predict/update`): context mixing level 3..5 spend most of their time
   here. Each component is an object with several arrays and the switch over component types
   is evaluated per bit. Ideas: specialize the update/predict loop per component type and per
   model (generate a specialized predictor class per model with the class-file API, `java.lang.classfile`
   in Java 25, the way the C++ JIT emits x86 code), keep all tables in a few flat arrays,
   use `Math.multiplyHigh`/shifts instead of table lookups where cheaper, avoid the bounds checks of
   the `& (length-1)` indexing with masks known to the JIT.
2. **ZPAQL interpreter** (`Zpaql.execute`): HCOMP runs once per byte, PCOMP (the post-processor for
   LZ77/BWT/E8E9 on decompression) once per output byte. A big `switch` interpreter; the C++ version
   compiles ZPAQL to x86. Ideas: translate the header program to JVM bytecode (class-file API) and
   load it as a hidden class, or at least pre-decode into an `int[]` of operations with resolved
   operands; the built-in models only use a small subset of the instructions.
3. **Suffix array** (`SuffixArray`, SA-IS with `boolean[]` type array, int[] buckets): used by BWT and
   `LZ77-SA` modes; libdivsufsort is faster and uses less memory. Candidates: a tuned SA-IS with
   bit-packed types and a byte[]-specialized first level, or a port of divsufsort.
4. **Fragmenting / hashing in `add`** (`Adder`): the rolling hash loop reads byte by byte and
   `Sha1.put`s every byte; bulk-hash the fragment after finding its boundary and scan with a
   tighter loop (SHA-1 is intrinsified in the JDK).
5. **LZ77 match finder** (`LzBuffer`): straightforward port; `at()` bounds helper in the hot path.
6. **I/O**: `RandomAccessFile` with small reads for the archive input (64 KB per `read` call), per-byte
   `OutputArchive.put`; use `FileChannel` with direct buffers or memory mapping.
7. **Memory**: every compress worker keeps its own tables (`int[]`/`byte[]` per component, zero filled
   per block). Reusing allocations across blocks and using `MemorySegment` for the multi-GB tables
   would avoid the 2^31 element array limit (see README, limitations) and cut GC pressure.

## Measuring

`tools/difftest/cmp-libzpaq.sh METHOD files...` prints sizes and verifies bytes; for timing use
the commands above. A JFR recording of a `-m4` run
(`java -XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=m4.jfr ...`) shows
`Predictor.predict`/`update` and `Zpaql.execute` as the top frames.

## Optimization log

Each step was measured against the previous one and kept only if the gain was clearly above the
(considerable, about +-10 %) run to run noise of the shared test machine; the archives stayed
byte-identical (`mvn verify`, `tools/difftest` scenarios, 300 random ZPAQL programs against the C++
interpreter). Measured with JDK 25, 4 cores, `-XX:+UseParallelGC`, wall clock, "before" is the
baseline commit.

Benchmark data: 14 MB (8 MB of the JDK `modules` image, 6 MB of `libjvm.so`, 0.5 MB text; "corpus"),
12 MB of text (`/usr/include`, docs; "txt", one thread), 173 MB (`modules` + `libjvm.so`; "big").

| step | what | measured effect |
|------|------|-----------------|
| 1 | ZPAQL programs (HCOMP / PCOMP) compiled to bytecode with the class-file API (`ZpaqlJit`), interpreter kept as fallback and for programs with unusual jumps | extraction `-m1`/`-m2` of corpus 3.0 s -> 0.6 s (PCOMP runs per output byte); `-m4` compression -8 % |
| 2 | Per model generated `predict`/`update` (`PredictorJit`): unrolled calls to the component steps in `PredOps` with component numbers, sizes and rates as constants, no per component switch, mixer loops with constant length | corpus `-m4` 32.2 s -> 27.5 s, txt `-m4` 28.5 s -> 17.6 s, txt `-m5` 96 s -> 61 s (C++: 12.4 s / 43.8 s) |
| 3 | `ArrayPool`: LZ77 hash table / suffix array / inverse suffix array are recycled between blocks (64 MB per block at `-m1` produced 1.5 s of young GC pauses per 173 MB) | big `-m1` 8.6 s -> 7.5 s |
| 4 | Fragment scanning of `add` moved out of the 5.6 KB `Adder.add` into `FragmentScanner` (tight loop, bulk SHA-1). The huge method was OSR compiled and deoptimized again and again: 3.9 s of C2 time for a 6 s run | big `-m1` 7.5 s -> 5.8 s (C++ 5.9 s); C2 time 3.9 s -> 1.7 s |
| 5 | SA-IS: suffix types in a bitset (1/8 of the cache footprint) | corpus `-m2` 7.4 s -> 6.7 s |
| 6 | Generated classes are only created after ~4 KB of input; a model that was compiled before in the same process is used immediately | no cost for tiny inputs |
| 7 | `LzBuffer.putb` collects bits in a `long` and stores 4 bytes at a time (the 1-then-bit pairs of the length codes are one call); unmodeled blocks (`-m1`, stored) are copied into the encoder's buffer with `System.arraycopy` instead of one `Encoder.compress` call per byte | big `-m1` 5.5 s -> 4.9 s wall, 18.0 s -> 15.5 s CPU (4 interleaved pairs); 64 KB hash table (in cache) 2.0 s -> 1.8 s |

Current gap to the C++ version (same machine): `-m1` ~1.0x, `-m2` ~1.6x, `-m3` ~1.5x, `-m4`/`-m5`
~1.4x, extraction like compression. What remains is mostly cache misses (hash table rows of ICM /
ISSE, LZ77 match search, mixer weight sets) plus the JIT warm-up, which is a fixed ~0.5 s per
process and block.

### `-m1` (LZ77 match finder), warm JVM

`-m1` on 217 MB: 5.8-6.3 s, 19 s CPU on 4 cores (all busy), same as C++. JFR with
`-XX:+DebugNonSafepoints`: ~70 % in `LzBuffer.fill`; of that the hash table row load (`ht[h1^k]`)
31 %, the verification read `in[p+blen-1]` of the candidates 21 %, the compare loop 13 %, the table
store 9 %. Counters for a 64 MB input: 12.6 M searches, 100 M probes, 36 M verified candidates,
179 M compared bytes, 64 M table inserts. It is memory bound (a 64 KB table makes it 25 % faster
but changes the output), so the lever is overlapping misses, not instructions.

* Kept: collect the candidates whose check bits match first and read their first byte, so the
  random reads of all candidates (up to 8) miss at the same time (~5-7 %, 5.2-5.5 s on 217 MB).
* Dropped: touching the table row of the position `AHEAD` (8/16/32) bytes in front: 10-15 % slower;
  touching the row of the next position only: no change.

Measured with a single thread library benchmark over 6 blocks of 16 MB (sum of the per block
minimum over 5 iterations, interleaved runs; whole runs vary by +-20 % on the shared machine):

* Dropped: hash table off-heap (`Arena.allocate(bytes, 64)`), so that the 32 byte rows of
  `bucket` 8 never straddle two cache lines (with an 8 byte aligned `int[]` half of the rows do):
  5.28-5.31 s vs 5.21-5.32 s. The adjacent line prefetcher apparently hides the second line.
* Dropped: Vector API (`jdk.incubator.vector`) for the bucket scan: one 256 bit load of the row,
  `rearrange` by `h1 & 7` into probe order, compare the check bits, iterate the set mask bits:
  5.06-5.70 s vs 4.64-5.47 s. The scan is a few instructions next to a cache miss, and the
  incubator module would need `--add-modules` at compile and run time. The other vector
  candidates of `-m1` do not pay either: verified matches are ~5 bytes long (`Arrays.mismatch`,
  which is vectorized, did not help), and the rolling hash of the fragment scan and the E8E9
  transform are sequential.
* Not used: transparent huge pages. `-XX:+UseTransparentHugePages` makes the hash table lookups
  5-10 % faster in the library benchmark, but the whole `zpaq a -m1` run 25 % slower
  (6.3-6.6 s vs 4.8-5.2 s) on a kernel with `defrag=madvise`, where the huge page faults of the
  heap stall on compaction.

### ZPAQL: generated bytecode vs interpreter vs hand-written Java (warm JVM)

Library benchmark, 7.1 MB mixed input, one thread, 4 warm iterations, `-Djpaq.zpaqlJit=true|false`:

| | generated | interpreter |
|---|---|---|
| compress `-m3` | 4.6-4.8 s | 6.2-6.5 s |
| compress `-m4` | 11.6-13.7 s | 13.7-15.5 s |
| compress `-m1` (no model, no HCOMP) | 0.63 s | 0.59 s |
| decompress `-m1` / `-m2` (PCOMP per output byte) | 0.09-0.14 s | 1.7-1.9 s |

JFR with hidden frames shown (`-XX:+UnlockDiagnosticVMOptions -XX:+ShowHiddenFrames`) puts all ZPAQL
code (HCOMP) at 0.8-0.9 % of the samples for `-m4`/`-m5` compression. Replacing the known programs by
hand-written Java could therefore gain at most that ~1 %, so it was not done; the generator is already
as good as hand-written code for this purpose. The time is in `PredOps.find/predictIcm/predictIsse`,
`Predictor.endUpdate` (cache misses on the hash tables) and, for `-m2`/`-m3`, `LzBuffer`/`SuffixArray`.

Tried and dropped (no gain beyond noise, or slower):

* `Arrays.mismatch` for the LZ77 match length, `System.arraycopy` for literals, fields of
  `LzBuffer.fill` copied to locals.
* Touching the hash table rows of all ICM/ISSE components in advance ("prefetch").
* 16 byte stores instead of `Arrays.fill` in `PredOps.find`.
* `PredOps.find` loading all three probe check bytes up front: m3 4.4-4.7 s vs 4.8-5.0 s, m4 unchanged
  (12.1-12.5 s either way), i.e. noise; not kept.
* `MemorySegment` (64 byte aligned, off-heap) instead of `byte[]` for the ICM/ISSE tables: about
  15 % slower with the current JDK. The array alignment does matter though: shifting all rows by
  0/16/32/48 bytes changes the `-m4` time by +-8 %, depending on where the JVM happens to put the
  array.
* Transparent huge pages (`-XX:+UseTransparentHugePages`): no effect here.

## Ideas for later

* **Vector API** (incubator, not used yet; tried for `-m1` without gain, see above): the mixers (`PredOps.predictMix` / `updateMix`: dot
  product of `m` weights with the stretched inputs and the weight update, `m` is typically 5..8
  and constant in the generated code, so one 256 bit vector suffices), `AVG`/`MIX2` of several
  components, the E8E9 transform, `Sha1`-free parts of the fragment scan, and the match length
  comparison in `LzBuffer`. `ISSE` weight pairs are 2 lanes only and not worth it.
* Parallel decompression of independent blocks inside one file is already done by the extractor;
  a faster `Decoder` loop (the arithmetic decoder + predictor call chain) has not been looked at.
* divsufsort port for the suffix array (SA-IS is still ~1.5x slower on 8 MB blocks).
* Aligned tables (see above) would need either `Unsafe` or a faster `MemorySegment` access path.
