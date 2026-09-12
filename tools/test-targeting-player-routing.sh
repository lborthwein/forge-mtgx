#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -lt 4 ]; then echo 'Usage: runner PINNED_JAR CAST_CLASSES ROOT_CLASSES NEW_OUTPUT [--baseline]' >&2; exit 2; fi
route_jar="$1"; route_cast="$2"; route_deps="$3"; route_out="$4"; route_mode="${5:-}"
route_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ -e "$route_out" ] || [ ! -d "$route_cast" ] || [ ! -d "$route_deps" ]; then echo 'Bad immutable inputs/output' >&2; exit 2; fi
if [ "$(shasum -a 256 "$route_jar" | awk '{print $1}')" != d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c ]; then exit 2; fi
mkdir -p "$route_out/classes" "$route_out/home"
exec > >(tee "$route_out/fixture.log") 2>&1
git rev-parse HEAD
route_pin="$(find "$route_cast" "$route_deps" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)"
echo "DEPENDENCIES $route_pin"
route_sources=(forge-gui-desktop/src/test/java/forge/bench/TargetingPlayerEngineSmoke.java forge-ai/src/main/java/forge/bench/TargetingPlayerRouting.java)
if [ "$route_mode" != --baseline ]; then route_sources+=(forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java); fi
shasum -a 256 "$route_jar" "${route_sources[@]}"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$route_cast:$route_deps:$route_jar" -d "$route_out/classes" "${route_sources[@]}"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$route_out/home" -cp "$route_out/classes:$route_cast:$route_deps:$route_jar" forge.bench.TargetingPlayerEngineSmoke "$route_root" "$route_mode"
if [ "$route_pin" != "$(find "$route_cast" "$route_deps" -type f -name '*.class' -exec shasum -a 256 {} + | LC_ALL=C sort | shasum -a 256)" ]; then echo 'Dependency mutation' >&2; exit 1; fi
