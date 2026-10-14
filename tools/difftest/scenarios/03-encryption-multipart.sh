#!/bin/bash
. "$(dirname "$0")/../common.sh"
D=$DT/diffrun.sh
mod() { (cd $WORK/R && eval "$@") ; (cd $WORK/J && eval "$@"); }
echo "== encryption"
$DT/reset.sh
$D "enc add" a e.zpaq tree -key pw1 -until 20250101000000
$D "enc list" l e.zpaq -key pw1
$D "enc extract" x e.zpaq -key pw1 -to out
mod "echo x >> tree/s.txt; touch -d '2022-02-02 02:02:02' tree/s.txt"
$D "enc update" a e.zpaq tree -key pw1 -until 20250102000000
$D "enc list all" l e.zpaq -key pw1 -all
$D "enc wrong pw" l e.zpaq -key bad
$D "enc no pw" l e.zpaq
$D "enc extract2" x e.zpaq -key pw1 -to out2
$D "repack -all decrypt" x e.zpaq -key pw1 -repack plain.zpaq -all
$D "repack -all encrypt" x plain.zpaq -repack enc2.zpaq pw2 -all
$D "repack files" x e.zpaq -key pw1 -repack part.zpaq tree/a.txt
$D "list repack" l part.zpaq
echo "== multipart"
$DT/reset.sh
$D "mp add1" a "mp???.zpaq" tree -until 20250101000000
mod "echo y >> tree/s.txt; touch -d '2022-02-02 02:02:02' tree/s.txt"
$D "mp add2" a "mp???.zpaq" tree -until 20250102000000
$D "mp list" l "mp???.zpaq"
$D "mp extract" x "mp???.zpaq" -to out
echo "== index"
$DT/reset.sh
$D "ix add1" a "ix???.zpaq" tree -index idx.zpaq -until 20250101000000
mod "echo y >> tree/s.txt; touch -d '2022-02-02 02:02:02' tree/s.txt"
$D "ix add2" a "ix???.zpaq" tree -index idx.zpaq -until 20250102000000
$D "ix list" l idx.zpaq
$D "ix list all" l "ix???.zpaq" -all
$D "ix extract" x "ix???.zpaq" -to out
$D "ix make" x "ix???.zpaq" -index idx2.zpaq
$D "ix list2" l idx2.zpaq
echo "== encrypted index"
$DT/reset.sh
$D "eix add1" a "ex???.zpaq" tree -index eidx.zpaq -key k -until 20250101000000
mod "echo y >> tree/s.txt; touch -d '2022-02-02 02:02:02' tree/s.txt"
$D "eix add2" a "ex???.zpaq" tree -index eidx.zpaq -key k -until 20250102000000
$D "eix extract" x "ex???.zpaq" -key k -to out
$D "eix make" x "ex???.zpaq" -key k -index eidx2.zpaq
