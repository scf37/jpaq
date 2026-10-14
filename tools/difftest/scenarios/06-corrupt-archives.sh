#!/bin/bash
. "$(dirname "$0")/../common.sh"
D=$DT/diffrun.sh
U=20250101000000
$DT/reset.sh
$D "add base" a t.zpaq tree -until $U
mod "mv t.zpaq base.keep"   # keep pristine copy
SZ=$(stat -c%s $WORK/R/base.keep)
echo "size $SZ"
corrupt() { # name, shell command applied to c.zpaq
  mod "cp base.keep c.zpaq; $2"
  $D "$1 list" l c.zpaq
  $D "$1 test" x c.zpaq -test
  $D "$1 extract" x c.zpaq -to out_$1
  mod "rm -rf out_$1"
}
corrupt trunc_half "truncate -s $((SZ/2)) c.zpaq"
corrupt trunc_tail "truncate -s $((SZ-30)) c.zpaq"
corrupt trunc_100 "truncate -s 100 c.zpaq"
corrupt flip_data "printf '\\xff' | dd of=c.zpaq bs=1 seek=5000 conv=notrunc 2>/dev/null"
corrupt flip_data2 "printf '\\x00\\x01' | dd of=c.zpaq bs=1 seek=200000 conv=notrunc 2>/dev/null"
corrupt flip_h "printf '\\xaa' | dd of=c.zpaq bs=1 seek=$((SZ-300)) conv=notrunc 2>/dev/null"
corrupt flip_i "printf '\\x55\\x55' | dd of=c.zpaq bs=1 seek=$((SZ-60)) conv=notrunc 2>/dev/null"
corrupt flip_c "printf '\\x01' | dd of=c.zpaq bs=1 seek=70 conv=notrunc 2>/dev/null"
corrupt garbage_tail "head -c 1000 /dev/zero >> c.zpaq"
corrupt garbage_pre "printf 'GARBAGE' | cat - base.keep > c.zpaq"
corrupt notzpaq "head -c 5000 tree/sub/deep/r.bin > c.zpaq"
corrupt emptyfile ": > c.zpaq"
corrupt textfile "echo hello world > c.zpaq"
echo "== update on damaged"
mod "cp base.keep c.zpaq; truncate -s $((SZ-30)) c.zpaq; echo q >> tree/s.txt; touch -d '2022-02-02 02:02:02' tree/s.txt"
$D "update damaged" a c.zpaq tree -until 20250102000000
$D "list after" l c.zpaq
mod "cp base.keep d.zpaq; head -c 5000 tree/sub/deep/r.bin >> d.zpaq"
$D "update garbage" a d.zpaq tree -until 20250103000000
