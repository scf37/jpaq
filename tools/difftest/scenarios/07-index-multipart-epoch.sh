#!/bin/bash
. "$(dirname "$0")/../common.sh"
D=$DT/diffrun.sh
export SOURCE_DATE_EPOCH=1735689600
echo "== index (real)"
$DT/reset.sh
$D "ix add1" a "ix???.zpaq" tree -index idx.zpaq
mod "echo y >> tree/s.txt; touch -d '2022-02-02 02:02:02' tree/s.txt; rm tree/small"
export SOURCE_DATE_EPOCH=1735776000
$D "ix add2" a "ix???.zpaq" tree -index idx.zpaq
$D "ix list" l idx.zpaq
$D "ix list all" l idx.zpaq -all
$D "ix list parts" l "ix???.zpaq" -all
$D "ix extract" x "ix???.zpaq" -to out
$D "ix extract via idx" x idx.zpaq -to out2
$D "ix make" x "ix???.zpaq" -index idx2.zpaq
$D "ix list2" l idx2.zpaq
echo "== encrypted index (real)"
export SOURCE_DATE_EPOCH=1735689600
$DT/reset.sh
$D "eix add1" a "ex???.zpaq" tree -index eidx.zpaq -key k
mod "echo y >> tree/s.txt; touch -d '2022-02-02 02:02:02' tree/s.txt"
export SOURCE_DATE_EPOCH=1735776000
$D "eix add2" a "ex???.zpaq" tree -index eidx.zpaq -key k
$D "eix list" l eidx.zpaq -key k -all
$D "eix extract" x "ex???.zpaq" -key k -to out
$D "eix make" x "ex???.zpaq" -key k -index eidx2.zpaq
echo "== multipart (real, no -until)"
export SOURCE_DATE_EPOCH=1735689600
$DT/reset.sh
$D "mp add1" a "mp???.zpaq" tree
mod "echo y >> tree/s.txt; touch -d '2022-02-02 02:02:02' tree/s.txt"
export SOURCE_DATE_EPOCH=1735776000
$D "mp add2" a "mp???.zpaq" tree
$D "mp list" l "mp???.zpaq" -all
$D "mp extract" x "mp???.zpaq" -to out
echo "== same-date adjust"
export SOURCE_DATE_EPOCH=1735776000
mod "echo z >> tree/s.txt; touch -d '2022-03-03 02:02:02' tree/s.txt"
$D "adjust date" a "mp???.zpaq" tree
