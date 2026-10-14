#!/bin/bash
# diffrun.sh <label> <zpaq args...>
# Runs the C++ and the Java zpaq with the same arguments in $WORK/R and $WORK/J (create them with
# reset.sh) and compares standard output/error (order of the multi-threaded extract messages and
# timings are normalized), the exit code, the resulting files, and the modes and modification
# times of the files and directories (times within the last day are ignored).
. "$(dirname "$0")/common.sh"
label="$1"; shift
CMD="$1"
cd "$WORK" || exit 1
(cd R && "$REF_ZPAQ" "$@" > ../R.out 2> ../R.err; echo $? > ../R.rc)
(cd J && java_zpaq "$@" > ../J.out 2> ../J.err; echo $? > ../J.rc)
norm() {
  if [ "$1" = "R.err" -o "$1" = "J.err" -o "$CMD" != "x" ]; then SORT=cat; else SORT=sort; fi
  grep -v '^zpaq v7.15' "$1" | sed -E 's/[0-9]+\.[0-9]+ seconds/N seconds/; s/[0-9]+:[0-9]{2}:[0-9]{2} //g; s/\r[^\n]*//g; s/^[0-9]+\.[0-9]+% //; s/Job [0-9]+/Job N/' | $SORT
}
ok=1
if ! diff <(norm R.out) <(norm J.out) > diff.out 2>&1; then echo "STDOUT DIFF [$label]"; head -10 diff.out; ok=0; fi
if ! diff <(norm R.err) <(norm J.err) > diff2.out 2>&1; then echo "STDERR DIFF [$label]"; head -10 diff2.out; ok=0; fi
if [ "$(cat R.rc)" != "$(cat J.rc)" ]; then echo "RC DIFF [$label] ref=$(cat R.rc) java=$(cat J.rc)"; ok=0; fi
if ! diff -r -x fifo R J > filediff.out 2>&1; then echo "FILES DIFF [$label]"; head -10 filediff.out; ok=0; fi
statof() {
  (cd "$1" && find . -mindepth 1 -printf '%p\t%T@\t%m\t%y\n' | grep -v -E '^\./[^/]*\.zpaq|^\./[^/]*idx' \
    | awk -F'\t' -v cut=$(( $(date +%s) - 86400 )) '{t=$2; if (t>cut) t="T"; else sub(/\.[0-9]+/,"",t); print $1 "\t" t "\t" $3 "\t" $4}' | sort)
}
statof R > R.stat; statof J > J.stat
if ! diff R.stat J.stat > statdiff.out 2>&1; then echo "STAT DIFF [$label]"; head -10 statdiff.out; ok=0; fi
[ $ok = 1 ] && echo "OK [$label]"
[ $ok = 1 ]
