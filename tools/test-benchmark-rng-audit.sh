#!/usr/bin/env bash
set -euo pipefail
# Run THROUGH MTGX admitted-check.sh. No Maven/shared targets or gameplay.
if [ "$#" -ne 2 ]; then echo 'Usage: test-benchmark-rng-audit.sh PINNED_JAR ARTIFACT_DIR' >&2; exit 2; fi
fixture_jar="$1"
fixture_dir="$2"
fixture_root="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$fixture_dir/classes" "$fixture_dir/home"
exec > >(tee "$fixture_dir/fixture.log") 2>&1
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$fixture_jar" -d "$fixture_dir/classes" \
  "$fixture_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/BenchmarkOptionalCosts.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/BenchActionAudit.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/JsonRpcChannel.java" \
  "$fixture_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$fixture_root/forge-gui-desktop/src/main/java/forge/bench/BenchMain.java" \
  "$fixture_root/forge-gui-desktop/src/test/java/forge/bench/BenchRandomAuditSmoke.java" \
  "$fixture_root/forge-gui-desktop/src/test/java/forge/bench/BenchRandomAuditMenuSmoke.java" \
  "$fixture_root/forge-gui-desktop/src/test/java/forge/bench/BenchActionAuditSmoke.java"
/opt/homebrew/opt/openjdk@17/bin/java -cp "$fixture_dir/classes:$fixture_jar" forge.bench.BenchRandomAuditSmoke
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$fixture_dir/home" \
  -cp "$fixture_dir/classes:$fixture_jar" forge.bench.BenchRandomAuditMenuSmoke "$fixture_root"
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$fixture_dir/home" \
  -cp "$fixture_dir/classes:$fixture_jar" forge.bench.BenchActionAuditSmoke "$fixture_root"
