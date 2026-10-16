# Common settings for the differential test scripts (sourced).
# Everything is relative to the repository; override with the environment if needed.
DT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$DT/../.." && pwd)
BUILD=${BUILD:-$DT/build}                      # reference binaries (see build-ref.sh)
WORK=${WORK:-$BUILD/work}                      # scratch directory with the R/ and J/ trees
JAVA=${JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}
CP=${CP:-$ROOT/libzpaq/target/classes:$ROOT/api/target/classes:$ROOT/cli/target/classes:$ROOT/cli/target/test-classes:$BUILD/classes}
export LC_ALL=C.UTF-8
unset JAVA_TOOL_OPTIONS

# Reference (C++) and Java command lines. The "fixed" variants use a deterministic random generator
# for encryption salts and honour SOURCE_DATE_EPOCH, so archives can be compared byte for byte.
REF_ZPAQ=$BUILD/zpaq_fixed
java_zpaq() { "$JAVA" -cp "$CP" io.github.scf37.jpaq.cli.FixedRandomMain "$@"; }

# Directory modification times are stored in archives; directories modified in R and J a few
# milliseconds apart could straddle a second boundary, so they are reset to a fixed time.
fixdirs() { for d in R J; do find "$WORK/$d/tree" -type d -exec touch -d @1600000000 {} + 2>/dev/null; done; }
# mod CMD: run a shell command in both trees (e.g. to modify files), then normalize directory times.
mod() { (cd "$WORK/R" && eval "$@"); (cd "$WORK/J" && eval "$@"); fixdirs; }
