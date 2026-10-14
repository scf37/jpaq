#!/bin/bash
. "$(dirname "$0")/../common.sh"
D=$DT/diffrun.sh
mod() { (cd $WORK/R && eval "$@") ; (cd $WORK/J && eval "$@"); }
U=20250101000000
$DT/reset.sh
echo "== usage/errors"
$D "no args"
$D "help" a
$D "bad option" a x.zpaq tree -bogus
$D "nonexistent file" a t.zpaq nonexistent -until $U
$D "list nonexistent archive" l nope.zpaq
$D "extract nonexistent archive" x nope.zpaq
$D "bad method" a t.zpaq tree -method z -until $U
$D "bad blocksize" a t.zpaq tree -method 1,99 -until $U
$D "bad until" l t.zpaq -until 1800
$D "add none" a t.zpaq tree -until $U
echo "== options"
$D "fragment 3" a f3.zpaq tree -fragment 3 -until $U
$D "fragment 10" a f10.zpaq tree -fragment 10 -until $U
$D "fragment 0" a f0.zpaq tree -fragment 0 -until $U
$D "fragment 30" a f30.zpaq tree -fragment 30 -until $U
$D "threads 1" a th1.zpaq tree -t1 -until $U
$D "threads 7" a th7.zpaq tree -threads 7 -until $U
$D "noattr" a na.zpaq tree -noattributes -until $U
$D "summary add" a sm.zpaq tree -s1 -until $U
$D "m2 blk" a mb.zpaq tree -m 20 -until $U
$D "m x1" a mx.zpaq tree -method x1.3ci1 -until $U
$D "-method 3,200,1" a mh.zpaq tree -method 3,200,1 -until $U
$D "list noattr" l t.zpaq -noattributes
$D "ext noattr" x f3.zpaq -noattributes -to oo
$D "list -not =" l f3.zpaq -not =+- 
$D "list sum" l f3.zpaq -summary 3
$D "list -s-1" l f3.zpaq -s -1
$D "list sel" l f3.zpaq tree/sub
$D "list only" l f3.zpaq -only '*.txt'
$D "list files cmp" l f3.zpaq tree/a.txt tree/small
$D "until date forms" l f3.zpaq -until 2025-01-01
$D "until date forms 2" l f3.zpaq -until 2025 1 1 0 0 0
$D "until date forms 3" l f3.zpaq -until 2024-12-31 23:59:58
echo "== special files"
mod "mkdir -p tree/uni && cp tree/small 'tree/uni/h\xc3\xa9llo w\xc3\xb6rld.txt' 2>/dev/null; echo 'zz' > 'tree/uni/h\xc3\xa9llo.txt'; ln -s small tree/link; chmod 4755 tree/sub/prog.exe; chmod 600 tree/s.txt; touch -d '2019-03-04 05:06:07' tree/uni tree/uni/* tree/link -h; mkfifo tree/fifo"
$D "add special" a sp.zpaq tree -until $U
$D "list special" l sp.zpaq
$D "extract special" x sp.zpaq -to osp
