#!/usr/bin/env bash
# Headless mtgx<->Forge play-benchmark bridge (forge.bench.BenchMain, wire protocol v1).
#
# Reads ONE JSON config line on stdin, writes JSON-lines protocol on stdout, logs on stderr.
#
#   echo '{"decks":["/abs/a.dck","/abs/b.dck"],"games":3,"seed":123,
#          "seats":{"0":"null","1":"null"},"aiProfile":"Default","timeoutSec":120}' \
#     | ./run-bench.sh
#
# Env:
#   FORGE_REPO   Forge checkout (default ~/Documents/GitHub/forge)
#   BENCH_HOME   writable scratch dir for Forge's prefs/cache (default: this script's dir)
#   SEQ_AI       1 (default) to force AiAttackController's forced-attacker futures sequential
set -euo pipefail

FORGE_REPO="${FORGE_REPO:-/Users/lbo/Documents/GitHub/forge}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH_HOME="${BENCH_HOME:-$HERE/forge-home}"
mkdir -p "$BENCH_HOME/data" "$BENCH_HOME/cache" "$BENCH_HOME/decks"

JAR=$(ls "$FORGE_REPO"/forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar 2>/dev/null | head -1)
if [[ -z "$JAR" ]]; then
  echo "Fat jar not found. Build with:" >&2
  echo "  JAVA_HOME=$JAVA_HOME /opt/homebrew/bin/mvn -f $FORGE_REPO/pom.xml -pl forge-gui-desktop -am -DskipTests package" >&2
  exit 1
fi

# Forge resolves res/ relative to the CWD for a versioned jar, so the CWD MUST be forge-gui/.
RUN_DIR="$FORGE_REPO/forge-gui"

# Keep Forge's writes out of ~/Library/... (gitignored by Forge).
cat > "$RUN_DIR/forge.profile.properties" <<EOF
userDir=$BENCH_HOME/data/
cacheDir=$BENCH_HOME/cache/
decksDir=$BENCH_HOME/data/decks/
decksConstructedDir=$BENCH_HOME/decks/
EOF

COMMIT=$(cd "$FORGE_REPO" && git rev-parse --short HEAD)
SEQ="${SEQ_AI:-1}"
SEQFLAG=false
[[ "$SEQ" == "1" ]] && SEQFLAG=true

cd "$RUN_DIR"
# NOTE: do NOT pass -Djava.awt.headless=true -- forge-gui-desktop's error handler needs a
# live AWT toolkit and the JVM dies silently (exit 1, no output).
exec "$JAVA" \
  -Xmx4g \
  -Dapple.awt.UIElement=true \
  -Djava.util.Arrays.useLegacyMergeSort=true \
  -Dforge.bench.sequentialAi=$SEQFLAG \
  -Dforge.bench.commit="$COMMIT" \
  -cp "$JAR" \
  forge.bench.BenchMain "$@"
