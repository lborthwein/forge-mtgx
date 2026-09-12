#!/usr/bin/env bash
set -euo pipefail
# Caller uses Studio admission; sequential JVMs, no matches or live resources.
if [ "$#" -ne 3 ]; then echo 'Usage: test-mandatory-trigger-execution.sh PINNED_JAR PINNED_CLASSES NEW_OUTPUT_DIR' >&2; exit 2; fi
trigger_jar="$1"; trigger_classes="$2"; trigger_out="$3"
trigger_root="$(cd "$(dirname "$0")/.." && pwd)"
test -f "$trigger_jar" && test -d "$trigger_classes" && test ! -e "$trigger_out"
test "$(shasum -a 256 "$trigger_jar" | awk '{print $1}')" = d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c
mkdir -p "$trigger_out/classes" "$trigger_out/home"
exec > >(tee "$trigger_out/fixture.log") 2>&1
echo 'DEVELOPMENT ONLY: mandatory zero trigger execution, not complete policy ownership'
git -C "$trigger_root" rev-parse HEAD
trigger_pin="$(find "$trigger_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $trigger_pin"
shasum -a 256 "$trigger_jar" "$trigger_root/forge-ai/src/main/java/forge/bench/MandatoryZeroTriggerExecution.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$trigger_root/forge-gui-desktop/src/test/java/forge/bench/MandatoryTriggerExecutionSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$trigger_classes:$trigger_jar" -d "$trigger_out/classes" \
  "$trigger_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/OptionalZeroTriggerExecution.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/OptionalManaTriggerExecution.java" \
  "$trigger_root/forge-game/src/main/java/forge/game/cost/CostPartMana.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java" \
  "$trigger_root/forge-game/src/main/java/forge/game/player/ScopedReplacementExecution.java" \
  "$trigger_root/forge-game/src/main/java/forge/game/replacement/ReplacementHandler.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/RulesReplacementExecution.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/MandatoryZeroTriggerExecution.java" \
  "$trigger_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$trigger_root/forge-gui-desktop/src/test/java/forge/bench/MandatoryTriggerExecutionSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$trigger_out/home" -cp "$trigger_out/classes:$trigger_classes:$trigger_jar" forge.bench.MandatoryTriggerExecutionSmoke "$trigger_root"
test "$trigger_pin" = "$(find "$trigger_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
