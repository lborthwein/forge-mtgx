#!/usr/bin/env bash
set -euo pipefail
# Run inside installed MTGX admission --lane test. No games.
if [ "$#" -ne 2 ]; then echo 'Usage: test-exile-known-observation.sh PINNED_JAR NEW_EVIDENCE_DIR' >&2; exit 2; fi
exile_jar="$1"
exile_dir="$2"
exile_root="$(cd "$(dirname "$0")/.." && pwd)"
exile_java="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17}/bin"
if [ ! -f "$exile_jar" ] || [ -e "$exile_dir" ]; then echo 'Missing jar or occupied evidence directory' >&2; exit 2; fi
mkdir -p "$exile_dir/classes" "$exile_dir/home"
exec > >(tee "$exile_dir/fixture.log") 2>&1
git -C "$exile_root" rev-parse HEAD
git -C "$exile_root" status --porcelain
shasum -a 256 "$exile_jar" "$exile_root/forge-game/src/main/java/forge/game/GameActionUtil.java" "$exile_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" "$exile_root/forge-gui/res/cardsfolder/d/decadent_dragon_expensive_taste.txt" "$exile_root/forge-gui-desktop/src/test/java/forge/bench/ExileKnownObservationEngineSmoke.java" "$exile_root/tools/test-exile-known-observation.sh"
"$exile_java/java" -version
"$exile_java/javac" -cp "$exile_jar" -d "$exile_dir/classes" \
  "$exile_root/forge-game/src/main/java/forge/game/GameActionUtil.java" \
  "$exile_root/forge-game/src/main/java/forge/game/card/Card.java" \
  "$exile_root/forge-game/src/main/java/forge/game/card/CardUtil.java" \
  "$exile_root/forge-game/src/main/java/forge/game/spellability/SpellAbility.java" \
  "$exile_root/forge-game/src/main/java/forge/game/staticability/StaticAbilityAlternativeCost.java" \
  "$exile_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$exile_root/forge-game/src/main/java/forge/game/mana/ManaCostBeingPaid.java" \
  "$exile_root/forge-game/src/main/java/forge/game/mana/ManaPool.java" \
  "$exile_root/forge-ai/src/main/java/forge/bench/BenchmarkAbilityEnumeration.java" \
  "$exile_root/forge-ai/src/main/java/forge/bench/BenchmarkOptionalCosts.java" \
  "$exile_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$exile_root/forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java" \
  "$exile_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$exile_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$exile_root/forge-ai/src/main/java/forge/bench/BenchMenuStateAudit.java" \
  "$exile_root/forge-gui-desktop/src/test/java/forge/bench/ExileKnownObservationEngineSmoke.java"
"$exile_java/java" -Xmx2g -Duser.home="$exile_dir/home" -cp "$exile_dir/classes:$exile_jar" forge.bench.ExileKnownObservationEngineSmoke "$exile_root"
