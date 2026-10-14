#!/bin/bash
# reset.sh [tree]: recreates $WORK/R and $WORK/J as copies of the directory $WORK/tree
# (a default tree is generated from the repository files if it does not exist).
. "$(dirname "$0")/common.sh"
mkdir -p "$WORK"; cd "$WORK" || exit 1
if [ ! -d tree ]; then
  mkdir -p tree/sub/deep tree/empty
  cp "$ROOT/cpp/libzpaq.cpp" tree/a.txt; cp "$ROOT/cpp/zpaq.cpp" tree/s.txt
  cp "$BUILD/zpaq" tree/sub/prog.exe; head -c 200000 /dev/urandom > tree/sub/deep/r.bin
  head -c 3000 "$ROOT/cpp/zpaq.pod" > tree/small; : > tree/zero_len; cat tree/a.txt tree/a.txt > tree/dup.txt
  touch -d '2020-05-06 07:08:09' tree/a.txt tree/sub tree/sub/prog.exe
fi
rm -rf R J && mkdir R J && cp -a tree R/ && cp -a tree J/
