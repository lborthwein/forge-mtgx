#!/usr/bin/env bash
set -euo pipefail
# Run through installed MTGX admission, --lane test. Never runs games.
if [ "$#" -ne 2 ]; then echo 'Usage: test-compact-payment-domain.sh PINNED_JAR NEW_ARTIFACT_DIR' >&2; exit 2; fi
domain_jar="$1"
domain_dir="$2"
domain_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$domain_jar" ] || [ -e "$domain_dir" ]; then echo 'Missing jar or occupied evidence directory' >&2; exit 2; fi
mkdir -p "$domain_dir/classes" "$domain_dir/home"
exec > >(tee "$domain_dir/fixture.log") 2>&1
echo 'COMPACT DOMAIN DEVELOPMENT FIXTURE ONLY: no benchmark games or deployment'
git -C "$domain_root" rev-parse HEAD
git -C "$domain_root" status --porcelain
shasum -a 256 "$domain_jar"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$domain_jar" -d "$domain_dir/classes" \
  "$domain_root/forge-game/src/main/java/forge/game/GameActionUtil.java" \
  "$domain_root/forge-game/src/main/java/forge/game/card/Card.java" \
  "$domain_root/forge-game/src/main/java/forge/game/card/CardUtil.java" \
  "$domain_root/forge-game/src/main/java/forge/game/spellability/SpellAbility.java" \
  "$domain_root/forge-game/src/main/java/forge/game/staticability/StaticAbilityAlternativeCost.java" \
  "$domain_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$domain_root/forge-game/src/main/java/forge/game/cost/CostPartMana.java" \
  "$domain_root/forge-game/src/main/java/forge/game/mana/ManaCostBeingPaid.java" \
  "$domain_root/forge-game/src/main/java/forge/game/mana/ManaPool.java" \
  "$domain_root/forge-ai/src/main/java/forge/ai/ComputerUtil.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/RulesPaymentChoices.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/RulesPaymentDomain.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$domain_root/forge-ai/src/main/java/forge/bench/BenchMenuStateAudit.java" \
  "$domain_root/forge-gui-desktop/src/test/java/forge/bench/RulesPaymentDomainEngineSmoke.java" \
  "$domain_root/forge-gui-desktop/src/test/java/forge/bench/SourceOutputTraitsEngineSmoke.java"
for domain_fixture in RulesPaymentDomainEngineSmoke SourceOutputTraitsEngineSmoke; do
  /opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$domain_dir/home" \
    -cp "$domain_dir/classes:$domain_jar" "forge.bench.$domain_fixture" "$domain_root"
done
