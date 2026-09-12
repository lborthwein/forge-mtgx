#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then echo 'Usage: test-must-target-integrity.sh PINNED_JAR IMMUTABLE_CLASSES NEW_ARTIFACT_DIR [--characterize]' >&2; exit 2; fi
must_jar="$1"; must_classes="$2"; must_out="$3"; must_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$must_jar" ] || [ ! -d "$must_classes" ] || [ -e "$must_out" ]; then echo 'Missing inputs or occupied evidence directory' >&2; exit 2; fi
if [ "$(shasum -a 256 "$must_jar" | awk '{print $1}')" != d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c ]; then echo 'Wrong pinned jar' >&2; exit 2; fi
umask 077
mkdir -p "$must_out/classes" "$must_out/home"
exec > >(tee "$must_out/fixture.log") 2>&1
echo 'NOT CERTIFIED: bounded MustTarget actual casts, no matches'
git -C "$must_root" rev-parse HEAD
shasum -a 256 "$must_jar"
must_pin="$(find "$must_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $must_pin"
shasum -a 256 "$must_root/forge-ai/src/main/java/forge/bench/CallCounter.java" "$must_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" "$must_root/forge-gui-desktop/src/test/java/forge/bench/MustTargetExecutionEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$must_classes:$must_jar" -d "$must_out/classes" "$must_root/forge-ai/src/main/java/forge/bench/CallCounter.java" "$must_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" "$must_root/forge-gui-desktop/src/test/java/forge/bench/MustTargetExecutionEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$must_out/home" -cp "$must_out/classes:$must_classes:$must_jar" forge.bench.MustTargetExecutionEngineSmoke "$must_root" "${4:-}"
if [ "$must_pin" != "$(find "$must_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)" ]; then echo 'Dependency class tree changed' >&2; exit 1; fi
