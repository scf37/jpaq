#!/bin/bash
# cmp-libzpaq.sh METHOD FILE...
# Compresses each file with libzpaq::compress (C++) and Libzpaq.compress (Java) using METHOD and
# compares the bytes; also checks that Java decompresses the result.
. "$(dirname "$0")/common.sh"
m="$1"; shift
files=(); for f in "$@"; do files+=("$(realpath "$f")"); done
mkdir -p "$WORK/lib"; cd "$WORK/lib" || exit 1
for f in "${files[@]}"; do
  "$BUILD/refc" c "$m" "$f" ref.out > /dev/null 2> ref.err
  "$JAVA" -cp "$CP" JavaC c "$m" "$f" java.out 2> java.err
  if cmp -s ref.out java.out; then
    "$JAVA" -cp "$CP" JavaC d java.out java.dec 2> /dev/null
    if cmp -s java.dec "$f"; then echo "OK   $m $f ($(stat -c%s ref.out))"; else echo "DECFAIL $m $f"; fi
  else echo "DIFF $m $f ref=$(stat -c%s ref.out) java=$(stat -c%s java.out)"; fi
done
