#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then echo 'Usage: test-target-reference.sh PINNED_JAR IMMUTABLE_CLASSES NEW_ARTIFACT_DIR [--characterize]' >&2; exit 2; fi
target_jar="$1"; target_classes="$2"; target_out="$3"
target_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$target_jar" ] || [ ! -d "$target_classes" ] || [ -e "$target_out" ]; then echo 'Missing inputs or occupied evidence directory' >&2; exit 2; fi
if [ "$(shasum -a 256 "$target_jar" | awk '{print $1}')" != d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c ]; then echo 'Wrong pinned jar' >&2; exit 2; fi
umask 077
mkdir -p "$target_out/classes" "$target_out/home"
exec > >(tee "$target_out/fixture.log") 2>&1
echo 'NOT CERTIFIED: bounded target reference callbacks, no matches'
git -C "$target_root" rev-parse HEAD
shasum -a 256 "$target_jar"
target_class_pin="$(find "$target_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $target_class_pin"
shasum -a 256 "$target_root/forge-ai/src/main/java/forge/bench/CallCounter.java" "$target_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" "$target_root/forge-gui-desktop/src/test/java/forge/bench/TargetReferenceEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$target_classes:$target_jar" -d "$target_out/classes" \
 "$target_root/forge-ai/src/main/java/forge/bench/CallCounter.java" "$target_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
 "$target_root/forge-gui-desktop/src/test/java/forge/bench/TargetReferenceEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$target_out/home" -cp "$target_out/classes:$target_classes:$target_jar" forge.bench.TargetReferenceEngineSmoke "$target_root" "${4:-}"
if [ "$target_class_pin" != "$(find "$target_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)" ]; then echo 'Dependency class tree changed' >&2; exit 1; fi
