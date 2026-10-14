#!/bin/bash
# run-random.sh SEED [config]
# Generates a random ZPAQL program (genz.py SEED) or uses the given config, compresses a file with
# it using the C++ library (interpreter build, refz_nojit) and the Java library and compares the
# compressed output and the output of the post-processor when decompressing.
. "$(dirname "$0")/../common.sh"
Z=$DT/zpaql
mkdir -p "$WORK/z"; cd "$WORK/z" || exit 1
seed=$1
if [ -n "$2" ]; then cfg=$2; else python3 "$Z/genz.py" "$seed" > "c$seed.cfg"; cfg=c$seed.cfg; fi
in=${ZINPUT:-$ROOT/cpp/zpaq.pod}
head -c 5000 "$in" > input.bin
"$BUILD/refz_nojit" c "$cfg" input.bin "r$seed.out" > "r$seed.log" 2>&1; rr=$?
"$JAVA" -cp "$CP" JavaZ c "$cfg" input.bin "j$seed.out" 2> "j$seed.log"; jr=$?
if [ $rr != 0 -o $jr != 0 ]; then echo "seed $seed compile/compress rc ref=$rr java=$jr: $(head -c 120 r$seed.log) | $(head -c 160 j$seed.log)"; exit 0; fi
if ! cmp -s "r$seed.out" "j$seed.out"; then echo "seed $seed COMPRESS DIFF"; exit 1; fi
timeout 20 "$BUILD/refz_nojit" d "r$seed.out" "rd$seed.out" > "rd$seed.log" 2>&1; rr=$?
timeout 20 "$JAVA" -cp "$CP" JavaZ d "r$seed.out" "jd$seed.out" 2> "jd$seed.log"; jr=$?
if [ $rr != $(( jr != 0 ? 2 : 0 )) ]; then echo "seed $seed DECOMPRESS rc differ ref=$rr java=$jr"; exit 1; fi
if [ $rr = 0 ] && ! cmp -s "rd$seed.out" "jd$seed.out"; then echo "seed $seed DECOMPRESS DIFF"; exit 1; fi
echo "seed $seed ok ($(stat -c%s "r$seed.out") bytes, decompressed $(stat -c%s "rd$seed.out"))"
