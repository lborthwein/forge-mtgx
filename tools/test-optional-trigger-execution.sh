#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then echo 'Usage: test-optional-trigger-execution.sh PINNED_JAR PINNED_CLASSES NEW_OUTPUT_DIR' >&2; exit 2; fi
optional_jar="$1"; optional_classes="$2"; optional_out="$3"
optional_root="$(cd "$(dirname "$0")/.." && pwd)"
test -f "$optional_jar" && test -d "$optional_classes" && test ! -e "$optional_out"
test "$(shasum -a 256 "$optional_jar" | awk '{print $1}')" = d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c
mkdir -p "$optional_out/classes" "$optional_out/home"
exec > >(tee "$optional_out/fixture.log") 2>&1
echo 'DEVELOPMENT ONLY: optional literal-zero trigger ownership, no strength claim'
git -C "$optional_root" rev-parse HEAD
optional_pin="$(find "$optional_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $optional_pin"
optional_sources=(forge-game/src/main/java/forge/game/player/ScopedTriggerResolution.java forge-game/src/main/java/forge/game/ability/AbilityUtils.java forge-ai/src/main/java/forge/bench/OptionalZeroTriggerExecution.java forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java forge-gui-desktop/src/test/java/forge/bench/OptionalTriggerExecutionSmoke.java forge-gui-desktop/src/test/java/forge/bench/MandatoryTriggerExecutionSmoke.java)
optional_sources+=(forge-game/src/main/java/forge/game/player/ScopedReplacementExecution.java forge-game/src/main/java/forge/game/replacement/ReplacementHandler.java forge-ai/src/main/java/forge/bench/RulesReplacementExecution.java forge-ai/src/main/java/forge/bench/MandatoryZeroTriggerExecution.java)
optional_sources+=(forge-game/src/main/java/forge/game/cost/CostAdjustment.java forge-ai/src/main/java/forge/bench/OptionalManaTriggerExecution.java forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java)
optional_sources+=(forge-game/src/main/java/forge/game/cost/CostPartMana.java)
for optional_source in "${optional_sources[@]}"; do shasum -a 256 "$optional_root/$optional_source"; done
cd "$optional_root"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$optional_classes:$optional_jar" -d "$optional_out/classes" "${optional_sources[@]}"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$optional_out/home" -cp "$optional_out/classes:$optional_classes:$optional_jar" forge.bench.OptionalTriggerExecutionSmoke "$optional_root"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$optional_out/home" -cp "$optional_out/classes:$optional_classes:$optional_jar" forge.bench.MandatoryTriggerExecutionSmoke "$optional_root"
test "$optional_pin" = "$(find "$optional_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
