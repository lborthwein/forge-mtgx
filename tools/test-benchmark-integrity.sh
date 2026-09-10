#!/usr/bin/env bash
set -euo pipefail
# Invoke THROUGH MTGX admitted-check.sh. Isolated classes/home; no shared target,
# runtime deployment, Maven, or strength games. This is not a benchmark certificate.
if [ "$#" -ne 2 ]; then echo 'Usage: test-benchmark-integrity.sh PINNED_JAR NEW_ARTIFACT_DIR' >&2; exit 2; fi
integrity_jar="$1"
integrity_dir="$2"
integrity_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$integrity_jar" ] || [ -e "$integrity_dir" ]; then
  echo 'Missing jar or occupied evidence directory; refusing to overwrite evidence' >&2
  exit 2
fi
mkdir -p "$integrity_dir/classes" "$integrity_dir/home"
exec > >(tee "$integrity_dir/fixture.log") 2>&1
echo 'NOT CERTIFIED: integrated compilation and development fixtures only'
git -C "$integrity_root" rev-parse HEAD
git -C "$integrity_root" status --porcelain
shasum -a 256 "$integrity_jar"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$integrity_jar" -d "$integrity_dir/classes" \
  "$integrity_root/forge-game/src/main/java/forge/game/GameActionUtil.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/card/Card.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/spellability/SpellAbility.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/staticability/StaticAbilityAlternativeCost.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/mana/ManaCostBeingPaid.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/mana/ManaPool.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/spellability/LandAbility.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/card/CardUtil.java" \
  "$integrity_root/forge-ai/src/main/java/forge/ai/ComputerUtil.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesPaymentChoices.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchmarkOptionalCosts.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchmarkAbilityEnumeration.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchMenuStateAudit.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchSession.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/LobbyPlayerBridge.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchActionAudit.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/JsonRpcChannel.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$integrity_root/forge-gui-desktop/src/main/java/forge/bench/BenchMain.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/RulesCostFeasibilityEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ObservationIntegrityEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/FixedLifePaymentEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchRandomAuditSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchRandomAuditMenuSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchMenuPurityVariantsSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchNullProbeWiringSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchActionAuditSmoke.java"
for integrity_fixture in BenchRandomAuditSmoke FixedLifePaymentEngineSmoke ObservationIntegrityEngineSmoke RulesCostFeasibilityEngineSmoke BenchRandomAuditMenuSmoke BenchMenuPurityVariantsSmoke BenchActionAuditSmoke BenchNullProbeWiringSmoke; do
  echo "RUN $integrity_fixture"
  /opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
    -cp "$integrity_dir/classes:$integrity_jar" "forge.bench.$integrity_fixture" "$integrity_root"
done
echo 'PASS integrated development fixtures; NOT CERTIFIED for strength comparisons'
