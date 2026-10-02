#!/usr/bin/env bash
# Routine upstream sync of the mtgx Forge fork (lborthwein/forge-mtgx) with Card-Forge/forge. First run: lane
# forge-upstream-1002 (merged forge-2.0.15). A sync changes Forge Default, the yardstick: run it only BETWEEN reads,
# and re-measure the reference reads on the new jar before any new read.
#
# Checklist (each step is a subcommand; stop at any red step):
#   1. drift   : fetch (upstream read-only), true merge-base, commit counts (GitHub compare: the clone is shallow-grafted,
#                so local rev-list counts are wrong), cube card drift. Pick TARGET = the newest release tag forge-2.0.N.
#   2. merge   : new worktree + branch sync/upstream-<MMDD> off the fork default branch; merge TARGET --no-ff --no-commit.
#                rerere is on: conflicts resolved in an earlier sync replay. Resolve the rest keeping every mtgx option off by
#                default (look-ahead, departZ, tutorRank, belief, targetVariants, aiFixes0928, policy pilot, interactive
#                bridge, sequentialAi); commit the merge with conflict resolutions only, API adaptations as separate commits.
#   3. build   : test-compile every default module; package forge-gui-desktop; copy the jar (+sha) to $RUN/jars.
#   4. tests   : the fork TestNG regression classes ($RUN/fork/full-classes.txt) + the upstream maven suite.
#   5. ref     : pure upstream worktree at TARGET + UpstreamParityBench only -> $RUN/jars/forge-upstream-ref-<sha>.jar.
#   6. gates (broker test leases, never 04:30-05:45 PT; lane scripts hosts/{lease,chain,jvm}.sh of forge-upstream-1002):
#        a  UpstreamParityBench, same 48 games, same order, ref jar vs sync jar, -Dlookahead.preloadTokens=false, two JVM
#           configs each: outcome, turns, turn/phase digests (cmp_rows.py) must agree 48/48;
#        b  LookaheadBench option-ON smokes (K1 shadow = null, K1 replay, live spec, TV, C1/C2, dedup, belief uniform,
#           tutor/prior/policy against stub services, aiFixes0928, sim seat);
#        c  private interactive host (mtgx tools/lib/live-stack-guard.mjs smokes), classic + look-ahead opponents.
#   7. baselines: re-measure the reference reads on the new jar (DEV-bank null + K8, archetype panel N/K1/K8, L2 N rows).
#   8. clean   : delete build output and the ref worktree.
#
# Env: RUN (lane run folder, required), FORGE (main clone, default ~/Documents/GitHub/forge), WT_ROOT (worktree root),
#      FORK (fork remote, default mtgx), UP (upstream remote, default origin), BASE_BRANCH (fork default branch),
#      CUBE (mtgx cube JSON, default the main mtgx clone's public/data/mush.json).
set -euo pipefail
cmd=${1:-help}; TARGET=${2:-}
FORGE=${FORGE:-$HOME/Documents/GitHub/forge}
WT_ROOT=${WT_ROOT:-$HOME/Documents/GitHub/forge-worktrees}
FORK=${FORK:-mtgx}; UP=${UP:-origin}
BASE_BRANCH=${BASE_BRANCH:-$(gh repo view lborthwein/forge-mtgx --json defaultBranchRef -q .defaultBranchRef.name 2>/dev/null || echo fix/modern-combat-allocation-0921)}
CUBE=${CUBE:-$HOME/Documents/GitHub/mtgx/public/data/mush.json}
HERE=$(cd "$(dirname "$0")" && pwd)
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}; export PATH="$JAVA_HOME/bin:$PATH"
need_run() { [ -n "${RUN:-}" ] || { echo "set RUN=<lane run folder>" >&2; exit 2; }; mkdir -p "$RUN"/{drift,jars,build,fork}; }
latest_tag() { git -C "$FORGE" tag -l 'forge-2.0.*' | sort -V | tail -1; }
tag_or_target() { [ -n "$TARGET" ] && echo "$TARGET" || latest_tag; }
SYNC_WT() { echo "$WT_ROOT/sync-upstream-$(date +%m%d)"; }

case $cmd in
drift)
  need_run
  git -C "$FORGE" fetch -q "$FORK"; git -C "$FORGE" fetch -q --no-tags "$UP" master 'refs/tags/forge-2.0.*:refs/tags/forge-2.0.*'  # not daily-snapshots (it moves)
  T=$(tag_or_target); TC=$(git -C "$FORGE" rev-parse "$T^{commit}"); B="$FORK/$BASE_BRANCH"
  MB=$(git -C "$FORGE" merge-base "$B" "$TC")
  echo "base $B = $(git -C "$FORGE" rev-parse --short=11 "$B")  target $T = ${TC:0:11}  merge-base ${MB:0:11} ($(git -C "$FORGE" log -1 --format=%cs "$MB"))"
  [ "$(git -C "$FORGE" rev-parse --is-shallow-repository)" = true ] && echo "note: shallow clone; local MB..target counts include pre-graft history"
  gh api "repos/Card-Forge/forge/compare/$MB...$TC" --jq '"upstream commits since merge-base: \(.ahead_by) (\(.status))"'
  echo "fork commits not upstream: $(git -C "$FORGE" rev-list --count "$B" "^$TC")"
  git -C "$FORGE" diff --name-only "$MB" "$TC" | awk -F/ '{ if ($1=="forge-gui" && $2=="res") print $1"/"$2"/"$3; else print $1 }' | sort | uniq -c | sort -rn | head -20
  echo "files changed on both sides:"; comm -12 <(git -C "$FORGE" diff --name-only "$MB" "$B" | sort) <(git -C "$FORGE" diff --name-only "$MB" "$TC" | sort)
  python3 "$HERE/cube_drift.py" "$FORGE" "$MB" "$TC" "$CUBE" "$RUN/drift/cube-drift-$T.json"
  ;;
merge)
  need_run; T=$(tag_or_target); W=$(SYNC_WT); BR="sync/upstream-$(date +%m%d)"
  git -C "$FORGE" config rerere.enabled true
  git -C "$FORGE" worktree add -b "$BR" "$W" "$FORK/$BASE_BRANCH"
  git -C "$W" merge --no-ff --no-commit "$(git -C "$FORGE" rev-parse "$T^{commit}")" || true
  echo "conflicts:"; git -C "$W" diff --name-only --diff-filter=U
  echo "resolve, then: git -C $W commit (message: target, merge-base, one line per conflict resolution)"
  ;;
build)
  need_run; W=${W:-$(SYNC_WT)}
  (cd "$W" && nice -n 10 mvn -B -fae -DskipTests test-compile) > "$RUN/build/all-modules-test-compile.log" 2>&1 \
    && echo "all modules: test-compile OK" || { echo "test-compile FAILED: $RUN/build/all-modules-test-compile.log"; exit 1; }
  (cd "$W" && nice -n 10 mvn -B -q -pl forge-gui-desktop -am -DskipTests package)
  H=$(git -C "$W" rev-parse --short=11 HEAD); J="$RUN/jars/forge-sync-$H.jar"
  cp "$W"/forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar "$J"; shasum -a 256 "$J"
  ;;
tests)
  need_run; W=${W:-$(SYNC_WT)}
  (cd "$W" && nice -n 10 mvn -B -q -pl forge-gui-desktop -am test-compile dependency:build-classpath -DskipTests \
     -Dmdep.outputFile=target/cp-test.txt -Dmdep.includeScope=test)
  CP="$W/forge-gui-desktop/target/test-classes:$W/forge-gui-desktop/target/classes:$(cat "$W/forge-gui-desktop/target/cp-test.txt")"
  CLASSES=$(tr '\n' ',' < "${CLASSES_FILE:-$HERE/full-classes.txt}" | sed 's/,$//')
  (cd "$W/forge-gui-desktop" && nice -n 10 java -ea --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED \
     --add-opens java.desktop/javax.imageio.spi=ALL-UNNAMED -cp "$CP" org.testng.TestNG -d target/testng-sync -testclass "$CLASSES") \
     > "$RUN/fork/tests-full.log" 2>&1 || true
  grep "Total tests run" "$RUN/fork/tests-full.log"
  (cd "$W" && nice -n 10 mvn -B -fae -pl forge-core,forge-game,forge-ai,forge-gui,forge-gui-desktop test) > "$RUN/build/upstream-suite.log" 2>&1 || true
  grep -E "Tests run: [0-9]+, Failures" "$RUN/build/upstream-suite.log" | grep -v " in " ; grep -E "BUILD (SUCCESS|FAILURE)" "$RUN/build/upstream-suite.log"
  ;;
ref)
  need_run; T=$(tag_or_target); TC=$(git -C "$FORGE" rev-parse "$T^{commit}"); R="$WT_ROOT/upstream-ref-${T#forge-}"
  [ -d "$R" ] || git -C "$FORGE" worktree add --detach "$R" "$TC"
  mkdir -p "$R/forge-gui-desktop/src/main/java/forge/bench"
  cp "${W:-$(SYNC_WT)}/forge-gui-desktop/src/main/java/forge/bench/UpstreamParityBench.java" "$R/forge-gui-desktop/src/main/java/forge/bench/"
  (cd "$R" && nice -n 10 mvn -B -q -pl forge-gui-desktop -am -DskipTests package)
  J="$RUN/jars/forge-upstream-ref-${TC:0:11}.jar"; cp "$R"/forge-gui-desktop/target/forge-gui-desktop-*-jar-with-dependencies.jar "$J"
  shasum -a 256 "$J" "$R/forge-gui-desktop/src/main/java/forge/bench/UpstreamParityBench.java"
  ;;
clean)
  for d in "${W:-$(SYNC_WT)}" "$WT_ROOT"/upstream-ref-*; do [ -d "$d" ] && find "$d" -maxdepth 2 -type d -name target -prune -exec rm -rf {} +; done
  for R in "$WT_ROOT"/upstream-ref-*; do [ -d "$R" ] && git -C "$FORGE" worktree remove --force "$R"; done
  echo "build output removed; ref worktrees removed"
  ;;
*) sed -n '2,32p' "$0" ;;
esac
