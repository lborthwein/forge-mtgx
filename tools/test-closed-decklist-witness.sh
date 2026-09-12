#!/usr/bin/env bash
set -euo pipefail
umask 077
# Run THROUGH Studio admission. This reproduces a defect in stock Forge;
# successful execution is negative evidence, NOT a fair-play certification.
if [ "$#" -ne 2 ]; then
  echo 'usage: test-closed-decklist-witness.sh PINNED_JAR NEW_ARTIFACT_DIR' >&2
  exit 2
fi
witness_jar="$1"
witness_out="$2"
witness_java_home="${JAVA_HOME:?Set JAVA_HOME to a JDK 17 installation}"
witness_root="$(cd "$(dirname "$0")/.." && pwd)"
test -f "$witness_jar"
test ! -e "$witness_out"
witness_hash="$(shasum -a256 "$witness_jar")"
test "${witness_hash%% *}" = d43b3b910e089269fcfd33e5589752a04b766749de76dbe3a8e5bde07726069c
mkdir -p "$witness_out/classes" "$witness_out/source" "$witness_out/home" "$witness_out/runtime"
exec > >(tee "$witness_out/fixture.log") 2>&1
echo 'NEGATIVE CONTROL: pinned stock Forge closed-decklist violation; no benchmark-impact claim'
cd "$witness_root"
git rev-parse HEAD
tar -czf "$witness_out/source.tar.gz" forge-gui-desktop/src/test/java/forge/bench/ClosedDecklistExilePreferenceWitness.java tools/test-closed-decklist-witness.sh
shasum -a256 "$witness_out/source.tar.gz" "$witness_jar" forge-gui/res/cardsfolder/c/cemetery_gatekeeper.txt forge-gui/res/cardsfolder/p/phyrexian_revoker.txt > "$witness_out/input-sha256.txt"
tar -xzf "$witness_out/source.tar.gz" -C "$witness_out/source"
"$witness_java_home/bin/javac" -cp "$witness_jar" -d "$witness_out/classes" "$witness_out/source/forge-gui-desktop/src/test/java/forge/bench/ClosedDecklistExilePreferenceWitness.java"
cd "$witness_out/runtime"
ln -s "$witness_root/forge-gui/res" res
"$witness_java_home/bin/java" -Xmx2g -Duser.home="$witness_out/home" -cp "$witness_out/classes:$witness_jar" forge.bench.ClosedDecklistExilePreferenceWitness "$witness_root" "$witness_jar" > "$witness_out/witness.log" 2>&1
tail -10 "$witness_out/witness.log"
