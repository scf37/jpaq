# zpaq streaming investigation (notes for future work)

> Implemented in the Java port as the sequential (`.jpaq`) format with a pluggable `Storage`:
> see [docs/SEQUENTIAL.md](../docs/SEQUENTIAL.md). These notes describe the C++ sources.

Goal: modify zpaq (7.15, `zpaq.cpp` + `libzpaq.cpp`) so archives can be
written/read with **sequential-only I/O and bounded buffers**, while keeping
the on-disk archive format intact (old zpaq must still read new archives,
new zpaq must read old ones).

Terminology:
- **tome** = one part of a multi-part archive (`arc001.zpaq`, `arc002.zpaq`, ...),
  one per `add` invocation.
- **index** = the `-index idx.zpaq` file: a copy of all tomes minus the D blocks.

Line numbers below refer to the unmodified 7.15 sources in this directory.

## 1. Format facts that matter

### Layout
- **Tome**: `C` (transaction header) · `D`… (compressed data blocks) · `H`… (fragment
  tables, one per D block) · `I`… (file entries).
- **Index**: `C` (with data size = 0) · `H`… · `I`… repeated per version. No D blocks.
- Concatenation of all tomes == a normal single-file archive.
- The tome's H and I blocks are written to both outputs through `WriterPair`
  (`wp.a=&out, wp.b=&outi`, ~`zpaq.cpp:2580`).

### Block names / contents (all are normal ZPAQ blocks, comment ends in `jDC\x01`)
- Segment filename `jDC<YYYYMMDDHHMMSS>[c|d|h|i]<10-digit num>`.
- `c`: 8-byte LE int64 = total compressed size of the following D blocks.
  `-1` = incomplete transaction (readers stop there). Index writes 0.
  Compressed with method "0" (stored) -> **fixed 104 bytes** (unencrypted).
- `d<N>`: fragments N.. concatenated, then trailer: `usize[4]` per fragment,
  `0[4]`, `frags[4]` (`zpaq.cpp:~2496-2500`). **Self-describing**: from the block
  alone you can split it into fragments and know their IDs (N from the name).
- `h<N>`: `bsize[4]` (compressed size of the D block) then `(sha1[20] usize[4])`
  per fragment. Block offsets are reconstructed by summing bsize starting from
  the end of the C block — no absolute offsets are stored anywhere.
- `i<k>`: records `date[8] name\0 na[4] attr[na] ni[4] ptr[ni][4]`; `date=0`
  means deletion. Fragment IDs are **global** across all versions (dedup can
  point to any earlier tome).

### C block byte layout (verified with a real archive)
Unencrypted tome starts with the 104-byte C block. The bytes that differ
between the "reserve" (-1) write and the final write:
- `0x46..0x4D`: the 8-byte csize
- `0x53..0x66`: 20-byte SHA-1 of the 8-byte segment content
(`0x52` = `0xFD` hash marker, `0x67` = `0xFF` end of block.)
With encryption everything shifts by +32 (salt). `extract -index` relies on
this layout (`zpaq.cpp:~3160-3180`: `hsize-34`, `hsize-22`==253, etc.).

### Encryption
AES-256-CTR keyed by absolute offset in the (virtual, concatenated) archive
(`OutputArchive::flush`, `InputArchive::read`). Sequential-friendly; in-place
patching just re-encrypts at the same offset. The index uses the archive salt
with `salt[0]^='7'^'z'`. A new tome's keystream offset comes from the index
alone: `offset=header_pos+dhsize` (`zpaq.cpp:2126`). Nothing from earlier tomes
is needed to write a new tome.

### libzpaq is already stream-only
`libzpaq::Reader`/`Writer` (`libzpaq.h:864`) have only get/read/put/write —
no seek. `Decompresser::findBlock` scans for the locator tag, `readSegmentEnd`
skips data by scanning (`dec.skip()`). All seeking lives in `zpaq.cpp`
(`InputArchive`, `OutputArchive`, `fseeko` calls).

## 2. Verdict per case

| | write (create/update) | read (list/extract) |
|---|---|---|
| **index** | ✅ sequential (read old + append) | ✅ sequential |
| **tome**  | ❌ one 28-byte back-patch of C at tome start | ✅ only if metadata (index) is read first |

### 2a. Writing a tome — the one real obstacle
`add()`:
1. `writeJidacHeader(&out, date, -1, htsize)` reserves C with -1 (`zpaq.cpp:2347`).
2. D blocks appended by `writeThread` in queue order (deterministic order).
3. H, I appended.
4. `out.seek(header_pos)` + `writeJidacHeader(&out, date, cdatasize, htsize)`
   (`zpaq.cpp:2679-2682`) — **back-patch**.
5. (non-index only) truncate/delete empty update.

The csize is needed because readers jump over D with it
(`in.seek(data_offset+jmp)`, `zpaq.cpp:1486`). No format-legal escape:
- csize=0 + D blocks following: `read_archive` hits a `d` block and throws
  "Unexpected journaling block" (error path, counts as error). Not acceptable.
- C cannot go after D (readers need it first).

Options keeping the format:
1. **Patchable sink**: stream everything, then one small positional write of
   28 bytes at offset `header_pos+0x46` (local file, ranged-patch-capable
   object store, etc.). Simplest.
2. **Two-pass compress**: pass 1 compresses into a counting writer (the existing
   `archive ""` / `OutputArchive("")` path already counts without writing) to get
   cdatasize; pass 2 compresses again and writes C with the final value first.
   Compression is deterministic (method string derived from content analysis;
   writeThread writes in queue order). Cost: 2× CPU, 2× input reads; inputs must
   not change — pass 2 must verify fragment SHA-1s and block sizes match pass 1
   and abort otherwise.
3. Spool all D (temp file/RAM) until size known — violates "bounded buffer".
4. Multiple transactions per tome (buffer one block each) — format-valid but
   **breaks `-index` tome naming**: next tome is `subpart(archive, ver.size())`
   (`zpaq.cpp:2128`), so extra versions create gaps and `InputArchive` stops at
   the first missing part. Also produces bogus versions/dates. Rejected.

### 2b. Writing the index — fine
- `add -index` reads the old index fully (`read_archive`, sequential since csize=0),
  then `OutputArchive outi(index, ...)` (`zpaq.cpp:2580`) opens r+b, reads salt,
  seeks to END once, appends C(0) + H + I. No patch; C is final at write time.
- If the medium can't append: tee the old index into the new output while
  reading it, then append new blocks — one sequential read + one sequential write.
- Note: index update is **not transacted** (no -1 marker); interrupted write
  leaves a partial tail. Consider if this matters for a streaming design.
- `extract -index` (rebuild index from tomes, `zpaq.cpp:~3150-3195`): reads
  forward, skips D with `in.seek(ver[i].csize, SEEK_CUR)` (`zpaq.cpp:3184`,
  forward → can be read-and-discard), writes C with constant csize=0 and the
  constant SHA-1 of 8 zero bytes. Currently does a separate `read_archive` pass
  first; could be fused into one pass.

### 2c. Reading the index — fine
`read_archive()` (`zpaq.cpp:1356`) on an index never jumps (csize=0). Only seeks:
- `in.seek(-nr, SEEK_CUR)` after 4-byte password probe (`zpaq.cpp:1384`) → small pushback buffer.
- `in.seek(-d.buffered(), SEEK_CUR)` in exception recovery (`zpaq.cpp:1636`).
- `InputArchive` ctor stats every part's size (fseeko END) to map offsets
  (`zpaq.cpp:~706-725`, seek across parts `~670-700`) — replace with counting.

### 2d. Reading tomes (extract) — feasible with restructuring
Current code is random-access:
- `read_archive` pass jumps over D (forward only — OK).
- `decompressThread` (one per thread, `zpaq.cpp:2796`) each opens its own
  `InputArchive` and `in.seek(b.offset)` (`zpaq.cpp:2847`); threads grab READY
  blocks in parallel → out-of-order reads.
- Streaming-format blocks: `zpaq.cpp:3384` (same pattern, single thread).

Streaming design:
- Metadata first: read the **index** (sequential), or do a first sequential
  pass over the tomes skipping D. Block offsets are validated ascending
  (`zpaq.cpp:~3125-3130`).
- Then one sequential pass over the concatenated tomes: a single reader thread
  reads blocks in offset order, discards unneeded ones, hands needed compressed
  blocks to worker threads. Buffer ≈ threads × (compressed + decompressed block);
  block size 2^N MiB (default 16 MiB for -m0/-m1, 64 MiB otherwise, up to 2 GiB with blocksize 11).
- **Single pass without the index is impossible with bounded buffer**: a tome's
  H/I come after its D, and later versions may reference (dedup) or delete
  anything earlier, so you can't know at D-read time whether it is needed.
- **Output side**: files are assembled from fragments of arbitrary blocks in
  arbitrary order and written with `fseeko(job.outf, offset)` (`zpaq.cpp:3032`).
  Fine for a filesystem; if output must itself be a stream (e.g. tar to stdout),
  reordering may need unbounded buffering in the worst case.

## 3. Memory model (independent of I/O pattern)
`read_archive` decompresses one H/I block at a time into `os` and discards it,
but keeps decoded metadata for the whole history in RAM:
- `ht` `vector<HT>` (`zpaq.cpp:954`): **every fragment ever**, 24 B each.
- `dt` `map<string,DT>` (`zpaq.cpp:965`): per filename, latest state only
  (`dt[fn]=dtr` overwrites, `zpaq.cpp:1580`); deleted files stay with date=0;
  with `-all` one entry per version. ptr list 4 B/fragment.
- `block` (`zpaq.cpp:976`), `ver` (`zpaq.cpp:991`): small.
- `add` additionally builds `HTIndex` (`zpaq.cpp:2343`), ~4 B × 2–4 slots per fragment.
- Avg fragment ≈ 64 KiB by default (boundary prob 2^-16/byte; min 4 KiB,
  max ≈ 508 KiB) → 1 TB unique data ≈ 16 M fragments ≈ 400 MB `ht` + 130–260 MB
  `HTIndex`.
- `add` truly needs all hashes (dedup). Extract could be leaner (load final `dt`,
  then only referenced `ht` entries) but current code holds everything.

## 4. Suggested implementation plan
1. Abstract archive I/O: stream-capable `InputArchive`/`OutputArchive` that
   count offsets instead of `ftello`, support forward skip by read-and-discard,
   and a small pushback buffer (for the 4-byte probe / `d.buffered()`).
2. Index read/update: switch to stream reader; optional "tee old index into new
   output" mode for non-appendable sinks.
3. Tome write: keep the C reserve; make the final patch go through a
   "sink supports patch" path, else offer two-pass mode (pass 1 = count-only
   compression, pass 2 = real write with final C first, verify hashes/sizes).
4. Extract: index-first metadata load, then single sequential reader thread
   dispatching compressed blocks (in offset order) to decompress workers;
   drop per-thread `InputArchive` + `seek(b.offset)`.
5. `extract -index`: fuse into a single forward pass.
6. Test interop: archives written in streaming mode must be byte-identical
   (or at least readable) by stock zpaq 7.15, and vice versa.

## 5. Quick repro used for verification
```
mkdir src; head -c 300000 /dev/urandom > src/a; seq 1 100000 > src/b
zpaq add "arc???" src -index idx.zpaq
echo x >> src/b; zpaq add "arc???" src -index idx.zpaq
xxd arc001.zpaq | head -8   # C block with real csize + sha1
xxd idx.zpaq    | head -8   # C block with csize 0 + sha1('0'*8)
```
