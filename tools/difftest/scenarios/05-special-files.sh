#!/bin/bash
. "$(dirname "$0")/../common.sh"
D=$DT/diffrun.sh
U=20250101000000
$DT/reset.sh
(cd $WORK/R && $DT/scenarios/mkspecial.sh); (cd $WORK/J && $DT/scenarios/mkspecial.sh)
$D "add special" a sp.zpaq tree -until $U
$D "list special" l sp.zpaq
$D "extract special" x sp.zpaq -to osp
$D "extract special in place" x sp.zpaq -to oq/
