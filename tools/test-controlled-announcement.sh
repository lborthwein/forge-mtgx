#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ];then echo 'Usage: test-controlled-announcement.sh PINNED_JAR ROOT_CLASSES NEW_OUTPUT' >&2;exit 2;fi
cast_jar="$1";cast_deps="$2";cast_out="$3";cast_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$cast_jar" ] || [ ! -d "$cast_deps" ] || [ -e "$cast_out" ];then echo 'Input missing or output exists' >&2;exit 2;fi
if [ "$(shasum -a 256 "$cast_jar" | awk '{print $1}')" != d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c ];then echo 'Wrong pinned jar' >&2;exit 2;fi
mkdir -p "$cast_out/classes" "$cast_out/home"
exec > >(tee "$cast_out/fixture.log") 2>&1
git -C "$cast_root" rev-parse HEAD
cast_dep_pin="$(find "$cast_deps" -name '*.class' -type f -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $cast_dep_pin"
cast_sources=(forge-ai/src/main/java/forge/ai/ComputerUtil.java forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java forge-ai/src/main/java/forge/bench/RulesCastingAuthorization.java forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java forge-ai/src/main/java/forge/bench/RulesPaymentDomain.java forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java forge-gui-desktop/src/test/java/forge/bench/ProductionPaymentDomainEngineSmoke.java forge-gui-desktop/src/test/java/forge/bench/ControlledAnnouncementSmoke.java)
cast_sources+=(forge-ai/src/main/java/forge/bench/MandatoryZeroTriggerExecution.java forge-gui-desktop/src/test/java/forge/bench/ControlledModalEngineSmoke.java)
cast_sources+=(forge-game/src/main/java/forge/game/player/ScopedReplacementExecution.java forge-game/src/main/java/forge/game/replacement/ReplacementHandler.java forge-ai/src/main/java/forge/bench/RulesReplacementExecution.java)
cast_sources+=(forge-gui-desktop/src/test/java/forge/bench/CastingAuthorizationEngineSmoke.java)
cast_sources+=(forge-game/src/main/java/forge/game/cost/CostPartMana.java forge-game/src/main/java/forge/game/cost/CostAdjustment.java forge-ai/src/main/java/forge/bench/OptionalZeroTriggerExecution.java forge-ai/src/main/java/forge/bench/OptionalManaTriggerExecution.java forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java)
shasum -a 256 "$cast_jar" "${cast_sources[@]}"
node -e 'const fs=require("node:fs"),crypto=require("node:crypto");const source=fs.readFileSync(process.argv[1],"utf8");const prior=source.replace(/    \/\*\* Trusted controlled-cast continuation[\s\S]*?(?=    public static boolean handlePlayingSpellAbility\(final Player ai, SpellAbility sa, Consumer<SpellAbility> chooseTargets\) \{)/,"");if(prior===source||crypto.createHash("sha256").update(prior).digest("hex")!=="dcb5d42630c54cc8dc812d6243eb36bc148add32a2ac0dc67e4220d9969a8c42")throw Error("Default source changed outside controlled-only addition");console.log("PASS exact entire baseline ComputerUtil source after removing controlled-only addition");' forge-ai/src/main/java/forge/ai/ComputerUtil.java
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$cast_deps:$cast_jar" -d "$cast_out/classes" "${cast_sources[@]}"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$cast_out/home" -cp "$cast_out/classes:$cast_deps:$cast_jar" forge.bench.ControlledAnnouncementSmoke "$cast_root"
if [ "$cast_dep_pin" != "$(find "$cast_deps" -name '*.class' -type f -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)" ];then echo 'Dependency mutation' >&2;exit 1;fi
