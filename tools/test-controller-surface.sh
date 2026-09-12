#!/usr/bin/env bash
set -euo pipefail
# Run via installed admitted-check test lane. One JVM; no games or live writes.
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then echo 'Usage: test-controller-surface.sh PINNED_JAR PINNED_CLASSES NEW_ARTIFACT_DIR [--inventory]' >&2; exit 2; fi
surface_jar="$1"; surface_classes="$2"; surface_out="$3"
surface_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$surface_jar" ] || [ ! -d "$surface_classes" ] || [ -e "$surface_out" ]; then echo 'Missing inputs or occupied evidence directory' >&2; exit 2; fi
if [ "$(shasum -a 256 "$surface_jar" | awk '{print $1}')" != d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c ]; then echo 'Wrong pinned jar' >&2; exit 2; fi
mkdir -p "$surface_out/classes" "$surface_out/home"
exec > >(tee "$surface_out/fixture.log") 2>&1
echo 'NOT CERTIFIED: controller surface and bounded callback development fixtures'
git -C "$surface_root" rev-parse HEAD
shasum -a 256 "$surface_jar" "$surface_classes/forge/bench/CallCounter.class" "$surface_classes/forge/bench/RulesPaymentExecutor.class"
surface_class_pin="$(find "$surface_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $surface_class_pin"
shasum -a 256 "$surface_root/forge-ai/src/main/java/forge/bench/CallCounter.java" \
  "$surface_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$surface_root/forge-gui-desktop/src/test/java/forge/bench/ControllerSurfaceSmoke.java" \
  "$surface_root/forge-gui-desktop/src/test/java/forge/bench/ControllerOwnershipEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$surface_classes:$surface_jar" -d "$surface_out/classes" \
  "$surface_root/forge-ai/src/main/java/forge/bench/CallCounter.java" \
  "$surface_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$surface_root/forge-gui-desktop/src/test/java/forge/bench/ControllerSurfaceSmoke.java" \
  "$surface_root/forge-gui-desktop/src/test/java/forge/bench/ControllerOwnershipEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$surface_out/home" -cp "$surface_out/classes:$surface_classes:$surface_jar" forge.bench.ControllerSurfaceSmoke "$surface_root" "${4:-}"
if [ "${4:-}" != --inventory ]; then
  /opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$surface_out/home" -cp "$surface_out/classes:$surface_classes:$surface_jar" forge.bench.ControllerOwnershipEngineSmoke "$surface_root"
fi
if [ "$surface_class_pin" != "$(find "$surface_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)" ]; then echo 'Dependency class tree changed during fixture' >&2; exit 1; fi
