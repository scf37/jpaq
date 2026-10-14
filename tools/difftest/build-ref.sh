#!/bin/bash
# Builds the reference tools from ../../cpp into ./build:
#   zpaq        stock zpaq 7.15
#   zpaq_fixed  zpaq 7.15 with a deterministic random() and SOURCE_DATE_EPOCH support
#   refc        libzpaq::compress/decompress driver (JIT)       refz / refz_nojit  ZPAQL config driver
# and compiles the Java side drivers (JavaC, JavaZ) against libzpaq/target/classes.
set -e
. "$(dirname "$0")/common.sh"
mkdir -p "$BUILD/cppfixed" "$BUILD/classes"
CPP=$ROOT/cpp
g++ -O2 -Dunix -o "$BUILD/zpaq" "$CPP/zpaq.cpp" "$CPP/libzpaq.cpp" -pthread
python3 - "$CPP" "$BUILD/cppfixed" <<'PY'
import sys
cpp, out = sys.argv[1], sys.argv[2]
lib = open(cpp + '/libzpaq.cpp').read()
i = lib.index('void random(char* buf, int n) {')
j = lib.index('//////////////////////////// Component ///////////////////////')
new = '''void random(char* buf, int n) {
  static unsigned counter=0;
  SHA256 sha;
  const char* seed="seed";
  for (const char* p=seed; *p; ++p) sha.put(*p);
  sha.put(counter>>24); sha.put(counter>>16); sha.put(counter>>8); sha.put(counter);
  ++counter;
  const char* r=sha.result();
  for (int i=0;i<n;++i) buf[i]=r[i%32];
  if (n>=1 && (buf[0]=='z' || buf[0]=='7')) buf[0]^=0x80;
}

'''
open(out + '/libzpaq.cpp', 'w').write(lib[:i] + new + lib[j:])
z = open(cpp + '/zpaq.cpp').read()
old = 'time_t now=time(NULL);'
assert old in z
open(out + '/zpaq.cpp', 'w').write(z.replace(old, 'time_t now=getenv("SOURCE_DATE_EPOCH")?atol(getenv("SOURCE_DATE_EPOCH")):time(NULL);'))
PY
cp "$CPP/libzpaq.h" "$BUILD/cppfixed/"
g++ -O2 -Dunix -o "$BUILD/zpaq_fixed" "$BUILD/cppfixed/zpaq.cpp" "$BUILD/cppfixed/libzpaq.cpp" -pthread
g++ -O2 -Dunix -I"$CPP" -o "$BUILD/refc" "$DT/refc.cpp" "$CPP/libzpaq.cpp"
g++ -O2 -Dunix -I"$CPP" -o "$BUILD/refz" "$DT/refz.cpp" "$CPP/libzpaq.cpp"
g++ -O2 -Dunix -DNOJIT -I"$CPP" -o "$BUILD/refz_nojit" "$DT/refz.cpp" "$CPP/libzpaq.cpp"
javac -cp "$ROOT/libzpaq/target/classes" -d "$BUILD/classes" "$DT/JavaC.java" "$DT/JavaZ.java"
echo "reference tools built in $BUILD"
