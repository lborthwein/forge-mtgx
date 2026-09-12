#!/usr/bin/env bash
set -euo pipefail
# All JVMs must run inside installed Studio admission, --lane test. No games.
if [ "$#" -ne 2 ]; then echo 'Usage: test-x-tax-feasibility.sh PINNED_JAR NEW_EVIDENCE_DIR' >&2; exit 2; fi
xtax_jar="$1"
xtax_dir="$2"
xtax_root="$(cd "$(dirname "$0")/.." && pwd)"
xtax_java="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17}/bin"
if [ ! -f "$xtax_jar" ] || [ -e "$xtax_dir" ]; then echo 'Missing jar or occupied evidence directory' >&2; exit 2; fi
mkdir -p "$xtax_dir/classes" "$xtax_dir/home"
exec > >(tee "$xtax_dir/fixture.log") 2>&1
git -C "$xtax_root" rev-parse HEAD
git -C "$xtax_root" status --porcelain
shasum -a 256 "$xtax_jar" "$xtax_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" "$xtax_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" "$xtax_root/forge-gui-desktop/src/test/java/forge/bench/XTaxFeasibilityEngineSmoke.java" "$xtax_root/tools/test-x-tax-feasibility.sh"
shasum -a 256 "$xtax_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" "$xtax_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" "$xtax_root/forge-ai/src/main/java/forge/bench/RulesPaymentDomain.java" "$xtax_root/forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java" "$xtax_root/forge-gui/res/cardsfolder/m/mind_twist.txt" "$xtax_root/forge-gui/res/cardsfolder/f/forth_eorlingas.txt" "$xtax_root/forge-gui/res/cardsfolder/t/thalia_guardian_of_thraben.txt" "$xtax_root/forge-gui/res/cardsfolder/w/walking_ballista.txt" "$xtax_root/forge-gui/res/cardsfolder/l/lightning_bolt.txt"
"$xtax_java/java" -version
"$xtax_java/javac" -cp "$xtax_jar" -d "$xtax_dir/classes" \
  "$xtax_root/forge-game/src/main/java/forge/game/GameActionUtil.java" \
  "$xtax_root/forge-game/src/main/java/forge/game/card/Card.java" \
  "$xtax_root/forge-game/src/main/java/forge/game/card/CardUtil.java" \
  "$xtax_root/forge-game/src/main/java/forge/game/spellability/SpellAbility.java" \
  "$xtax_root/forge-game/src/main/java/forge/game/staticability/StaticAbilityAlternativeCost.java" \
  "$xtax_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$xtax_root/forge-game/src/main/java/forge/game/cost/CostPartMana.java" \
  "$xtax_root/forge-game/src/main/java/forge/game/mana/ManaCostBeingPaid.java" \
  "$xtax_root/forge-game/src/main/java/forge/game/mana/ManaPool.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/BenchmarkAbilityEnumeration.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/BenchmarkOptionalCosts.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java" \
  "$xtax_root/forge-ai/src/main/java/forge/ai/ComputerUtil.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/RulesPaymentDomain.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/RulesPaymentChoices.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/BenchSession.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/LobbyPlayerBridge.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/BenchActionAudit.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/JsonRpcChannel.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/CallCounter.java" \
  "$xtax_root/forge-gui-desktop/src/main/java/forge/bench/BenchMain.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$xtax_root/forge-ai/src/main/java/forge/bench/BenchMenuStateAudit.java" \
  "$xtax_root/forge-gui-desktop/src/test/java/forge/bench/XTaxFeasibilityEngineSmoke.java"
"$xtax_java/java" -Xmx2g -Duser.home="$xtax_dir/home" -cp "$xtax_dir/classes:$xtax_jar" forge.bench.XTaxFeasibilityEngineSmoke "$xtax_root"
