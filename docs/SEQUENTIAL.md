# Sequential archives (`.jpaq`)

A variant of the zpaq journaling format that is written strictly front to back, so archive files can
live on storage that is only a pipe: shell scripts, ssh, S3 uploads. No local temporary files, no seeking,
no truncating, no overwriting.

## Why a variant

The zpaq format has two spots where a writer has to go back:

* the transaction header (block `c`) at the start of every update holds the total size of the data blocks
  that follow. It is written as -1 and patched at the end (`engine/Adder.java`, `writeJidacHeader`);
* an update that turns out to be empty is truncated away afterwards.

Readers of the classic format also jump over data blocks using that size.

## Format

A sequential archive is a set of **tomes** (`arc001.jpaq`, `arc002.jpaq`, ... one per update) and an
**index** (a file with the same blocks as the tomes minus the data blocks, exactly like a zpaq `-index`).
Both consist of ordinary ZPAQ blocks with the segment names and comments of the journaling format (`jDC...`
with types `c`, `d`, `h`, `i`), with these differences:

* **Block magic.** A ZPAQ block starts with a 13 byte locator tag and `zPQ`. Here it is `jPQ`. Any
  other ZPAQ program searches for the 16 byte string, finds nothing, and reports "archive contains
  no data" (encrypted: "password incorrect"). Nothing is misread, nothing is written.
* **Header block.** The `c` block of a tome has data size 0 and the size is not used. The data blocks (`d`)
  follow it directly and readers pass over them in the stream. The block is still 104 bytes (plus salt).
* The `d`, `h`, `i` block bodies, the fragment IDs, the encryption (AES-CTR keyed by the offset in the virtual
  concatenation of all tomes) and the index are identical to the classic format. A test compares them.

Tome = `C` `D`... `H`... `I`...; index segment of an update = `C` `H`... `I`... (same `H`/`I` bytes).

## Commit order and failures

An update writes the tome, closes it (the commit of the upload), and only then writes the new index. The
caller keeps the previous index until the new one is stored:

* the index is never written under the name of an older one: an update with
  `AddOptions.withIndex("idx-41").withIndexOut("idx-42")` reads the old index, copies it and appends the new segment under the new name;
* a tome that was uploaded but is not in any index (failed upload, crash before the index) is an orphan:
  it is ignored and overwritten by the next update, which gets the same name (the tome number is the number
  of versions in the index + 1);
* an update that changes nothing writes nothing;
* an update that fails midway deletes the partial tome, if the storage allows, and writes no index.

The archiver never asks a storage whether a blob exists or why a read failed (a shell script does not know):
the caller says whether an index exists by passing its name, tome numbers come from the index.

## Reading

* **With the index** (`ReadOptions.withIndex`, for reading and extracting): file list and fragment table come from the index
  (one sequential read). The data blocks of tomes are read in archive order, in parallel decompression;
  tomes (and with `RANGED_READ` parts of tomes) that hold nothing wanted are not transferred. Offsets
  and sizes of the tomes come from the index: the position of a block in tome *k* is the header plus the sum
  of the compressed block sizes listed in the index, and tome *k* is as long as its index segment plus its data.
* **Without the index** (recovery): the tomes are scanned front to back for the file list (a second pass reads
  the data), and `ZpaqArchive.writeIndex` writes a new index. Finding the parts of `arc???.jpaq` needs `PROBE`.
* A tome without its file list at the end (an upload that did not finish) is ignored by the scan.

## Limits

* Sequential archives cannot be repacked (`-repack`) and cannot be updated in the classic format. Mixing the two
  formats in one archive is refused.
* The H and I blocks of an update are kept in memory until the tome is stored (24 bytes per new fragment).
* Calls are serialized per process (global state).
