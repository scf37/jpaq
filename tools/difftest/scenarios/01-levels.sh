#!/bin/bash
. "$(dirname "$0")/../common.sh"
D=$DT/diffrun.sh
echo "== methods"
for m in 0 1 2 3 4 5; do $DT/reset.sh; $D "add -m$m" a t.zpaq tree -m$m -until 20250101000000; $D "extract -m$m" x t.zpaq -to out -until 20250101000000 ; done
