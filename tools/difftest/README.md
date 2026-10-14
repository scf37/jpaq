# Differential tests against the C++ original

These tools run the original C++ `zpaq` / `libzpaq` (built from [`../../cpp`](../../cpp)) and the Java
port side by side and compare the results. They need `g++`, `bash`, `python3` and a built Java tree
(`mvn package` in the repository root).

```
tools/difftest/build-ref.sh            # builds the reference binaries into tools/difftest/build
tools/difftest/scenarios/01-levels.sh  # one of the scenario scripts (see below)
python3 tools/difftest/fuzz.py 42 8    # random trees and command sequences, seed 42, 8 steps
tools/difftest/zpaql/run-random.sh 7   # random ZPAQL program (genz.py), seed 7
tools/difftest/cmp-libzpaq.sh x4.3ci1 file...   # libzpaq compress() output for one method
```

* `build-ref.sh` also builds `zpaq_fixed`: zpaq with a deterministic `random()` (for encryption
  salts) and support for the `SOURCE_DATE_EPOCH` environment variable, so that encrypted archives
  and archives written by `-index`/multi-part updates (which cannot use `-until`) are reproducible.
  The Java side uses `FixedRandomMain` (in `cli/src/test`) which installs the same generator.
* `diffrun.sh LABEL ARGS...` runs one command with both implementations in `build/work/R` and
  `build/work/J` and compares stdout/stderr (normalizing times and the order of messages printed
  by the multi-threaded extractor), the exit code, all files, and the modes and modification times.
* `scenarios/*.sh` are hand-written command sequences: all compression levels, explicit methods,
  streaming/index methods, update/delete/`-until`/`-all` flows, list and extract options,
  encryption, `-repack`, multi-part archives, indexes, damaged archives, unusual file names and
  file types.
* `fuzz.py` generates random directory trees (text, random, zero, x86-like, sparse files) and
  random command sequences with random methods and options.
* `zpaql/` compares the ZPAQL compiler and interpreter on random programs and on programs with
  loops and long jumps. The reference is the C++ *interpreter* (`-DNOJIT`): the x86 JIT of the C++
  version deviates from its own interpreter for a few odd random programs, which never occur in
  the built-in methods.

Everything that differs between runs by nature (wall-clock times, thread numbers) is normalized;
any other difference is a bug in the port.
