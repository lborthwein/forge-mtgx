#!/usr/bin/env bash
set -euo pipefail
# Invoke this script THROUGH MTGX admitted-check.sh; no Maven or shared targets.
if [ "$#" -ne 2 ]; then echo 'Usage: test-benchmark-rules-cost.sh PINNED_JAR ARTIFACT_DIR' >&2; exit 2; fi
fixture_jar="$1"
fixture_dir="$2"
fixture_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$fixture_dir/classes" "$fixture_dir/home"
exec > >(tee "$fixture_dir/fixture.log") 2>&1
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$fixture_jar" -d "$fixture_dir/classes" \
  "$fixture_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$fixture_root/forge-game/src/main/java/forge/game/cost/CostPartMana.java" \
  "$fixture_root/forge-game/src/main/java/forge/game/mana/ManaCostBeingPaid.java" \
  "$fixture_root/forge-game/src/main/java/forge/game/mana/ManaPool.java" \
  "$fixture_root/forge-ai/src/main/java/forge/ai/ComputerUtil.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/RulesPaymentChoices.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/BenchmarkOptionalCosts.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/BenchActionAudit.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/JsonRpcChannel.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/CallCounter.java" \
  "$fixture_root/forge-gui-desktop/src/main/java/forge/bench/BenchMain.java" \
  "$fixture_root/forge-gui-desktop/src/test/java/forge/bench/RulesCostFeasibilityEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$fixture_dir/home" \
  -cp "$fixture_dir/classes:$fixture_jar" forge.bench.RulesCostFeasibilityEngineSmoke "$fixture_root"
