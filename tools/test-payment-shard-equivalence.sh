#!/usr/bin/env bash
set -euo pipefail
# Invoke inside installed MTGX admission --lane test; no benchmark games.
if [ "$#" -ne 2 ]; then echo 'Usage: test-payment-shard-equivalence.sh PINNED_JAR NEW_EVIDENCE_DIR' >&2; exit 2; fi
shard_jar="$1"
shard_dir="$2"
shard_root="$(cd "$(dirname "$0")/.." && pwd)"
shard_java="${JAVA17_HOME:-/opt/homebrew/opt/openjdk@17}/bin"
if [ ! -f "$shard_jar" ] || [ -e "$shard_dir" ]; then echo 'Missing jar or occupied evidence directory' >&2; exit 2; fi
mkdir -p "$shard_dir/classes" "$shard_dir/home"
exec > >(tee "$shard_dir/fixture.log") 2>&1
echo 'PAYMENT SHARD DEVELOPMENT FIXTURE: no games, no strength claim'
git -C "$shard_root" rev-parse HEAD
git -C "$shard_root" status --porcelain
shasum -a 256 "$shard_jar" "$shard_root/forge-gui-desktop/src/test/java/forge/bench/PaymentShardEquivalenceEngineSmoke.java" "$shard_root/tools/test-payment-shard-equivalence.sh"
"$shard_java/java" -version
"$shard_java/javac" -cp "$shard_jar" -d "$shard_dir/classes" \
  "$shard_root/forge-game/src/main/java/forge/game/GameActionUtil.java" \
  "$shard_root/forge-game/src/main/java/forge/game/card/Card.java" \
  "$shard_root/forge-game/src/main/java/forge/game/card/CardUtil.java" \
  "$shard_root/forge-game/src/main/java/forge/game/spellability/SpellAbility.java" \
  "$shard_root/forge-game/src/main/java/forge/game/staticability/StaticAbilityAlternativeCost.java" \
  "$shard_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$shard_root/forge-game/src/main/java/forge/game/cost/CostPartMana.java" \
  "$shard_root/forge-game/src/main/java/forge/game/mana/ManaCostBeingPaid.java" \
  "$shard_root/forge-game/src/main/java/forge/game/mana/ManaPool.java" \
  "$shard_root/forge-ai/src/main/java/forge/ai/ComputerUtil.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/RulesPaymentChoices.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/RulesPaymentDomain.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$shard_root/forge-ai/src/main/java/forge/bench/BenchMenuStateAudit.java" \
  "$shard_root/forge-gui-desktop/src/test/java/forge/bench/PaymentShardEquivalenceEngineSmoke.java"
"$shard_java/java" -Xmx2g -Duser.home="$shard_dir/home" -cp "$shard_dir/classes:$shard_jar" forge.bench.PaymentShardEquivalenceEngineSmoke "$shard_root"
