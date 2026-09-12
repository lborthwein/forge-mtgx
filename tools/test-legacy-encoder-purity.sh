#!/usr/bin/env bash
set -euo pipefail
# One JVM at a time; caller must use installed Studio admission test lane.
if [ "$#" -ne 3 ]; then echo 'Usage: test-legacy-encoder-purity.sh PINNED_JAR PINNED_CLASSES NEW_OUTPUT_DIR' >&2; exit 2; fi
legacy_jar="$1"; legacy_classes="$2"; legacy_out="$3"
legacy_root="$(cd "$(dirname "$0")/.." && pwd)"
test -f "$legacy_jar" && test -d "$legacy_classes" && test ! -e "$legacy_out"
test "$(shasum -a 256 "$legacy_jar" | awk '{print $1}')" = d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c
mkdir -p "$legacy_out/classes" "$legacy_out/home-baseline" "$legacy_out/home-fixed"
exec > >(tee "$legacy_out/fixture.log") 2>&1
echo 'DEVELOPMENT FIXTURE ONLY: legacy estimate compatibility and observation purity'
git -C "$legacy_root" rev-parse HEAD
legacy_pin="$(find "$legacy_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $legacy_pin"
shasum -a 256 "$legacy_jar" "$legacy_root/forge-ai/src/main/java/forge/bench/LegacyManaEstimate.java" \
  "$legacy_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$legacy_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$legacy_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$legacy_root/forge-gui-desktop/src/test/java/forge/bench/LegacyEncoderPurityEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$legacy_classes:$legacy_jar" -d "$legacy_out/classes" \
  "$legacy_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$legacy_root/forge-ai/src/main/java/forge/bench/LegacyManaEstimate.java" \
  "$legacy_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$legacy_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$legacy_root/forge-gui-desktop/src/test/java/forge/bench/LegacyEncoderPurityEngineSmoke.java"
# Dependency-first loads unpatched current-root encoder for actual mutation reproduction.
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$legacy_out/home-baseline" -cp "$legacy_classes:$legacy_out/classes:$legacy_jar" forge.bench.LegacyEncoderPurityEngineSmoke "$legacy_root" --baseline
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$legacy_out/home-fixed" -cp "$legacy_out/classes:$legacy_classes:$legacy_jar" forge.bench.LegacyEncoderPurityEngineSmoke "$legacy_root"
test "$legacy_pin" = "$(find "$legacy_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
