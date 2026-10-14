# Common settings for the differential test scripts (sourced).
# Everything is relative to the repository; override with the environment if needed.
DT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$DT/../.." && pwd)
BUILD=${BUILD:-$DT/build}                      # reference binaries (see build-ref.sh)
WORK=${WORK:-$BUILD/work}                      # scratch directory with the R/ and J/ trees
JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
CP=${CP:-$ROOT/libzpaq/target/classes:$ROOT/cli/target/classes:$ROOT/cli/target/test-classes:$BUILD/classes}
export LC_ALL=C.UTF-8
unset JAVA_TOOL_OPTIONS

# Reference (C++) and Java command lines. The "fixed" variants use a deterministic random generator
# for encryption salts and honour SOURCE_DATE_EPOCH, so archives can be compared byte for byte.
REF_ZPAQ=$BUILD/zpaq_fixed
java_zpaq() { "$JAVA" -cp "$CP" io.github.scf37.jpaq.cli.FixedRandomMain "$@"; }
