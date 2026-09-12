#!/usr/bin/env bash
set -euo pipefail
# Admitted test lane only. A single fixture JVM, no games or browser writes.
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then echo 'Usage: test-priority-ownership.sh PINNED_JAR IMMUTABLE_CLASSES NEW_ARTIFACT_DIR [--characterize-coercion]' >&2; exit 2; fi
priority_jar="$1"; priority_classes="$2"; priority_out="$3"
priority_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$priority_jar" ] || [ ! -d "$priority_classes" ] || [ -e "$priority_out" ]; then echo 'Missing inputs or occupied evidence directory' >&2; exit 2; fi
if [ "$(shasum -a 256 "$priority_jar" | awk '{print $1}')" != d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c ]; then echo 'Wrong pinned jar' >&2; exit 2; fi
umask 077
mkdir -p "$priority_out/classes" "$priority_out/home"
exec > >(tee "$priority_out/fixture.log") 2>&1
echo 'NOT CERTIFIED: bounded priority ownership fixture, no matches'
git -C "$priority_root" rev-parse HEAD
shasum -a 256 "$priority_jar"
priority_class_pin="$(find "$priority_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $priority_class_pin"
shasum -a 256 "$priority_root/forge-ai/src/main/java/forge/bench/CallCounter.java" "$priority_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" "$priority_root/forge-gui-desktop/src/test/java/forge/bench/PriorityOwnershipEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$priority_classes:$priority_jar" -d "$priority_out/classes" \
  "$priority_root/forge-ai/src/main/java/forge/bench/CallCounter.java" "$priority_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$priority_root/forge-gui-desktop/src/test/java/forge/bench/PriorityOwnershipEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$priority_out/home" -cp "$priority_out/classes:$priority_classes:$priority_jar" forge.bench.PriorityOwnershipEngineSmoke "$priority_root" "${4:-}"
if [ "$priority_class_pin" != "$(find "$priority_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)" ]; then echo 'Dependency classes changed during fixture' >&2; exit 1; fi
