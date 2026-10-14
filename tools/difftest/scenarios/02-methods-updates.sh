#!/bin/bash
. "$(dirname "$0")/../common.sh"
D=$DT/diffrun.sh
U=20250101000000
echo "== x methods / streaming"
for m in x4.1.5.0.3.22 x4.3ci1 x4.0ci1.1.1.1.2amm x6.2.12.0.7.25.1c0.0.511i2 x4.7ci1 s s0 s4 i; do
  $DT/reset.sh; $D "add -m$m" a t.zpaq tree -m$m -until $U; $D "extract -m$m" x t.zpaq -to out -until $U; $D "list -m$m" l t.zpaq
done
echo "== update flow"
$DT/reset.sh
$D "add1" a t.zpaq tree -until 20250101000000
mod "echo more >> tree/s.txt; rm tree/small; cp tree/a.txt tree/newcopy.txt; touch -d '2021-01-02 03:04:05' tree/a.txt"
$D "add2" a t.zpaq tree -until 20250102000000
$D "add3 noop" a t.zpaq tree -until 20250103000000
mod "rm -rf tree/sub"
$D "add4 delete dir" a t.zpaq tree -until 20250104000000
$D "list" l t.zpaq
$D "list -all" l t.zpaq -all
$D "list -all -s-1" l t.zpaq -all -s -1
$D "list -s5" l t.zpaq -s5
$D "list compare" l t.zpaq tree
$D "list compare -f" l t.zpaq tree -f
$D "list -until 1" l t.zpaq -until 1
$D "list -until -1" l t.zpaq -until -1
$D "extract -until 1" x t.zpaq -to o1 -until 1
$D "extract -until 2" x t.zpaq -to o2 -until 2
$D "extract all" x t.zpaq -to o3 -all
$D "extract force" x t.zpaq -f -to o3 -until 2
$D "extract again skip" x t.zpaq -to o3 -until 2
$D "extract -test" x t.zpaq -test
$D "extract only" x t.zpaq -to o4 -only 'tree/*.txt'
$D "extract not" x t.zpaq -to o5 -not 'tree/s*'
$D "extract files" x t.zpaq tree/a.txt tree/dup.txt -to o6 
$D "extract files to" x t.zpaq tree/a.txt tree/dup.txt -to o7/x o7/y
