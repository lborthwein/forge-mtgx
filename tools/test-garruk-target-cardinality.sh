#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 2 ]; then echo 'Usage: test-garruk-target-cardinality.sh PINNED_JAR NEW_OUTPUT' >&2; exit 2; fi
garruk_jar="$1";garruk_out="$2";garruk_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$garruk_jar" ] || [ -e "$garruk_out" ]; then echo 'Missing jar or output exists' >&2; exit 2; fi
if [ "$(shasum -a 256 "$garruk_jar" | awk '{print $1}')" != d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c ]; then echo 'Wrong pinned jar' >&2; exit 2; fi
mkdir -p "$garruk_out/classes" "$garruk_out/home"
exec > >(tee "$garruk_out/fixture.log") 2>&1
echo 'PINNED JAR ONLY: no root/bridge class overlay, no full matches'
git -C "$garruk_root" rev-parse HEAD
shasum -a 256 "$garruk_jar" "$garruk_root/forge-gui/res/cardsfolder/g/garruk_wildspeaker.txt" "$garruk_root/forge-gui-desktop/src/test/java/forge/bench/GarrukTargetCardinalitySmoke.java" "$garruk_root/tools/test-garruk-target-cardinality.sh"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$garruk_jar" -d "$garruk_out/classes" "$garruk_root/forge-gui-desktop/src/test/java/forge/bench/GarrukTargetCardinalitySmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$garruk_out/home" -cp "$garruk_out/classes:$garruk_jar" forge.bench.GarrukTargetCardinalitySmoke "$garruk_root"
