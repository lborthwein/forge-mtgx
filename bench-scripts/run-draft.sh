#!/usr/bin/env bash
# Headless 8-seat cube draft + Forge limited deckbuild (forge.bench.DraftMain).
#
# Reads ONE JSON config line on stdin, writes one JSON line per seat on stdout.
#
#   echo '{"cards":[{"name":"Ponder","set":"LRW"},...],"seed":7,
#          "rankingsFile":"rankings_mtgx.txt","players":8,"packs":3,"cardsPerPack":15}' \
#     | ./run-draft.sh
#
# rankingsFile may be a bare name already under forge-gui/res/draft/, or an absolute
# path (DraftMain copies it into place).
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

RUN_DIR="$FORGE_REPO/forge-gui"

cat > "$RUN_DIR/forge.profile.properties" <<EOF
userDir=$BENCH_HOME/data/
cacheDir=$BENCH_HOME/cache/
decksDir=$BENCH_HOME/data/decks/
decksConstructedDir=$BENCH_HOME/decks/
EOF

cd "$RUN_DIR"
exec "$JAVA" \
  -Xmx4g \
  -Dapple.awt.UIElement=true \
  -Djava.util.Arrays.useLegacyMergeSort=true \
  -cp "$JAR" \
  forge.bench.DraftMain "$@"
