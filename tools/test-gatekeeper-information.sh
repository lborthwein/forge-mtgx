#!/usr/bin/env bash
set -euo pipefail
# Run only through MTGX admitted-check.sh, --lane test. No games or deployment.
if [ "$#" -ne 2 ]; then echo 'Usage: test-gatekeeper-information.sh PINNED_JAR NEW_ARTIFACT_DIR' >&2; exit 2; fi
gatekeeper_jar="$1"
gatekeeper_dir="$2"
gatekeeper_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$gatekeeper_jar" ] || [ -e "$gatekeeper_dir" ]; then
  echo 'Missing jar or occupied evidence directory; refusing to overwrite evidence' >&2; exit 2
fi
mkdir -p "$gatekeeper_dir/classes" "$gatekeeper_dir/home"
exec > >(tee "$gatekeeper_dir/fixture.log") 2>&1
echo 'DEVELOPMENT INFORMATION WITNESS ONLY: no strength games or certification'
git -C "$gatekeeper_root" rev-parse HEAD
git -C "$gatekeeper_root" status --porcelain
shasum -a 256 "$gatekeeper_jar" "$gatekeeper_root/forge-gui/res/cardsfolder/c/cemetery_gatekeeper.txt"
# Stock PlayerControllerAi / ChangeZoneAi stay in the pinned jar. Only current
# seat-visible encoding dependencies and the new test are overlaid.
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$gatekeeper_jar" -d "$gatekeeper_dir/classes" \
  "$gatekeeper_root/forge-game/src/main/java/forge/game/GameActionUtil.java" \
  "$gatekeeper_root/forge-game/src/main/java/forge/game/card/Card.java" \
  "$gatekeeper_root/forge-game/src/main/java/forge/game/staticability/StaticAbilityAlternativeCost.java" \
  "$gatekeeper_root/forge-game/src/main/java/forge/game/spellability/SpellAbility.java" \
  "$gatekeeper_root/forge-game/src/main/java/forge/game/card/CardUtil.java" \
  "$gatekeeper_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$gatekeeper_root/forge-gui-desktop/src/test/java/forge/bench/GatekeeperInformationEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$gatekeeper_dir/home" \
  -cp "$gatekeeper_dir/classes:$gatekeeper_jar" forge.bench.GatekeeperInformationEngineSmoke "$gatekeeper_root"
