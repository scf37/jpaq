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
