#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 3 ]; then echo 'Usage: test-host-trigger-preparation.sh PINNED_JAR PINNED_CLASSES NEW_OUTPUT' >&2; exit 2; fi
trigger_jar="$1"; trigger_classes="$2"; trigger_out="$3"
trigger_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$trigger_jar" ] || [ ! -d "$trigger_classes" ] || [ -e "$trigger_out" ]; then echo 'Missing input or output already exists' >&2; exit 2; fi
if [ "$(shasum -a 256 "$trigger_jar" | awk '{print $1}')" != d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c ]; then echo 'Wrong pinned jar' >&2; exit 2; fi
mkdir -p "$trigger_out/classes" "$trigger_out/home"
exec > >(tee "$trigger_out/fixture.log") 2>&1
echo 'BOUNDED HOST TARGET PREPARATION; no full match or certification'
git -C "$trigger_root" rev-parse HEAD
trigger_pin="$(find "$trigger_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCY_CLASS_TREE $trigger_pin"
shasum -a 256 "$trigger_jar" "$trigger_root/forge-ai/src/main/java/forge/ai/PlayerControllerAi.java" "$trigger_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" "$trigger_root/forge-gui-desktop/src/test/java/forge/bench/HostTriggerPreparationSmoke.java" "$trigger_root/forge-gui-desktop/src/test/java/forge/bench/ControllerSurfaceSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$trigger_classes:$trigger_jar" -d "$trigger_out/classes" "$trigger_root/forge-ai/src/main/java/forge/ai/PlayerControllerAi.java" "$trigger_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" "$trigger_root/forge-gui-desktop/src/test/java/forge/bench/HostTriggerPreparationSmoke.java" "$trigger_root/forge-gui-desktop/src/test/java/forge/bench/ControllerSurfaceSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$trigger_out/home" -cp "$trigger_out/classes:$trigger_classes:$trigger_jar" forge.bench.ControllerSurfaceSmoke "$trigger_root"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$trigger_out/home" -cp "$trigger_out/classes:$trigger_classes:$trigger_jar" forge.bench.HostTriggerPreparationSmoke "$trigger_root"
if [ "$trigger_pin" != "$(find "$trigger_classes" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)" ]; then echo 'Dependency classes changed during fixture' >&2; exit 1; fi
