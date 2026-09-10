#!/usr/bin/env bash
set -euo pipefail
# Must be called through the shared admitted-check wrapper.
if [ "$#" -ne 2 ]; then echo 'Usage: test-benchmark-observation.sh PINNED_JAR NEW_ARTIFACT_DIR' >&2; exit 2; fi
observation_jar="$1"
observation_dir="$2"
observation_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ -e "$observation_dir" ]; then echo 'Refusing to overwrite existing test evidence' >&2; exit 2; fi
mkdir -p "$observation_dir/classes" "$observation_dir/home"
exec > >(tee "$observation_dir/fixture.log") 2>&1
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$observation_jar" -d "$observation_dir/classes" \
  "$observation_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$observation_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$observation_root/forge-gui-desktop/src/test/java/forge/bench/ObservationIntegrityEngineSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$observation_dir/home" \
  -cp "$observation_dir/classes:$observation_jar" forge.bench.ObservationIntegrityEngineSmoke "$observation_root"
