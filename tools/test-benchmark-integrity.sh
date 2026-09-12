#!/usr/bin/env bash
set -euo pipefail
umask 077
# Invoke THROUGH MTGX admitted-check.sh. Isolated classes/home; no shared target,
# runtime deployment, Maven, or strength games. This is not a benchmark certificate.
if [ "$#" -ne 2 ]; then echo 'Usage: test-benchmark-integrity.sh PINNED_JAR NEW_ARTIFACT_DIR' >&2; exit 2; fi
integrity_jar="$1"
integrity_dir="$2"
integrity_root="$(cd "$(dirname "$0")/.." && pwd)"
if [ ! -f "$integrity_jar" ] || [ -e "$integrity_dir" ]; then
  echo 'Missing jar or occupied evidence directory; refusing to overwrite evidence' >&2
  exit 2
fi
mkdir -p "$integrity_dir/classes" "$integrity_dir/home"
exec > >(tee "$integrity_dir/fixture.log") 2>&1
echo 'NOT CERTIFIED: integrated compilation and development fixtures only'
git -C "$integrity_root" rev-parse HEAD
git -C "$integrity_root" status --porcelain
shasum -a 256 "$integrity_jar"
# Preserve the exact uncommitted source overlay at compilation time, not only
# its base commit. Later integration must not rewrite the evidence for this run.
(cd "$integrity_root" && git ls-files -z --modified --others --exclude-standard | tar --null -czf "$integrity_dir/source-overlay.tar.gz" -T -)
shasum -a 256 "$integrity_dir/source-overlay.tar.gz"
# Compile and inspect the SAME frozen source, even if work continues locally.
# The former runner archived bytes but source-audit fixtures read the live tree.
mkdir -p "$integrity_dir/source/forge-gui"
git -C "$integrity_root" archive HEAD forge-ai/src forge-game/src forge-gui-desktop/src forge-core/src | tar -xf - -C "$integrity_dir/source"
tar -xzf "$integrity_dir/source-overlay.tar.gz" -C "$integrity_dir/source"
ln -s "$integrity_root/forge-gui/res" "$integrity_dir/source/forge-gui/res"
integrity_root="$integrity_dir/source"
/opt/homebrew/opt/openjdk@17/bin/javac -cp "$integrity_jar" -d "$integrity_dir/classes" \
  "$integrity_root/forge-game/src/main/java/forge/game/GameRules.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/combat/Combat.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/combat/CombatDamageAssignment.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/player/ScopedCombatDamageAssignment.java" \
  "$integrity_root/forge-ai/src/main/java/forge/ai/AiKnownCardObservations.java" \
  "$integrity_root/forge-ai/src/main/java/forge/ai/SpecialCardAi.java" \
  "$integrity_root/forge-ai/src/main/java/forge/ai/ability/ChangeZoneAi.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/GameActionUtil.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/ability/AbilityUtils.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/player/ScopedTriggerResolution.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/player/ScopedReplacementExecution.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/replacement/ReplacementHandler.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/card/Card.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/spellability/SpellAbility.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/staticability/StaticAbilityAlternativeCost.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/cost/CostAdjustment.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/cost/CostPartMana.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/mana/ManaCostBeingPaid.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/mana/ManaPool.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/spellability/LandAbility.java" \
  "$integrity_root/forge-game/src/main/java/forge/game/card/CardUtil.java" \
  "$integrity_root/forge-ai/src/main/java/forge/ai/ComputerUtil.java" \
  "$integrity_root/forge-ai/src/main/java/forge/ai/PlayerControllerAi.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/StateEncoder.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/KnownHandObservation.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RevealHistoryObservation.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/PriorityStackTargetDomain.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/PriorityBoardTargetDomain.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/PriorityModalTargetDomain.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/PriorityActivationIdentity.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/CallCounter.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/CombatDeclarationChoices.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesCostFeasibility.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/ReflectedManaProduction.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesCastingAuthorization.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/TargetingPlayerRouting.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesCostDecisionMaker.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesPaymentExecutor.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesPaymentChoices.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesPaymentDomain.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchmarkOptionalCosts.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchmarkAbilityEnumeration.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchMenuStateAudit.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchSession.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/LobbyPlayerBridge.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchRandomAudit.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/LegacyManaEstimate.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/MandatoryZeroTriggerExecution.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/TriggerOrderChoices.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/RulesReplacementExecution.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/OptionalZeroTriggerExecution.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/OptionalManaTriggerExecution.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/BenchActionAudit.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/JsonRpcChannel.java" \
  "$integrity_root/forge-ai/src/main/java/forge/bench/PlayerControllerBridge.java" \
  "$integrity_root/forge-gui-desktop/src/main/java/forge/bench/BenchMain.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/RulesCostFeasibilityEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/MonolithLoopEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ReflectedManaPaymentEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/PaidOptionalTriggerExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/RepeatedManaTriggerExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/SelfSacrificeExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ModalActivationDomainSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ObservationIntegrityEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/FixedLifePaymentEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/SourceLifePaymentEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/MixedManaPaymentEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/RulesPaymentDomainEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/SourceOutputTraitsEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/PaymentPoolReceiptEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ProductionPaymentDomainEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ControlledAnnouncementSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ControlledModalEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/CastingAuthorizationEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/PermissionEnumerationEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/PermissionTaxProjectionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ExileKnownObservationEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/PaymentShardEquivalenceEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchRandomAuditSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchRandomAuditMenuSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchMenuPurityVariantsSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchNullProbeWiringSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/BenchActionAuditSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ControllerCoverageSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ForcedControllerChoiceSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/XTaxFeasibilityEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ControllerSurfaceSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ControllerOwnershipEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/PriorityOwnershipEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/DividedTargetExecutionEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/LegacyEncoderPurityEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/HostTriggerPreparationSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/TargetReferenceEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/MandatoryTriggerExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/TriggerOrderExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/CombatDeclarationExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ShuffleIdentityExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/CombatDamageExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/HostLifecycleSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/RevealHistorySmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/MillOrderExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/HostChoiceIntegritySmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/DiscardChoiceExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/HandKnowledgeEffectSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ClosedInformationRepairSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ProspectiveCdaScopeSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/OptionalTriggerExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/TargetingPlayerEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/PriorityStackTargetDomainSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/PriorityBoardTargetWitness.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/XBoardTargetDomainSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/SpellFaceIdentitySmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/SpellFaceReceiptMutationSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/ReplacementExecutionScopeSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/SpellFaceExecutionSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/SwallowedFailureEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/StackIdentityEngineSmoke.java" \
  "$integrity_root/forge-gui-desktop/src/test/java/forge/bench/MustTargetExecutionEngineSmoke.java"
for integrity_fixture in SwallowedFailureEngineSmoke PriorityStackTargetDomainSmoke TargetingPlayerEngineSmoke OptionalTriggerExecutionSmoke ControlledAnnouncementSmoke StackIdentityEngineSmoke MustTargetExecutionEngineSmoke MandatoryTriggerExecutionSmoke TargetReferenceEngineSmoke HostTriggerPreparationSmoke LegacyEncoderPurityEngineSmoke DividedTargetExecutionEngineSmoke PriorityOwnershipEngineSmoke ControllerSurfaceSmoke ControllerOwnershipEngineSmoke XTaxFeasibilityEngineSmoke ControllerCoverageSmoke ExileKnownObservationEngineSmoke PaymentShardEquivalenceEngineSmoke PermissionEnumerationEngineSmoke ProductionPaymentDomainEngineSmoke RulesPaymentDomainEngineSmoke SourceOutputTraitsEngineSmoke BenchRandomAuditSmoke MixedManaPaymentEngineSmoke SourceLifePaymentEngineSmoke FixedLifePaymentEngineSmoke ObservationIntegrityEngineSmoke RulesCostFeasibilityEngineSmoke BenchRandomAuditMenuSmoke BenchMenuPurityVariantsSmoke BenchActionAuditSmoke BenchNullProbeWiringSmoke; do
  echo "RUN $integrity_fixture"
  /opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
    -cp "$integrity_dir/classes:$integrity_jar" "forge.bench.$integrity_fixture" "$integrity_root"
done
echo 'RUN PriorityBoardTargetWitness'
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
  -cp "$integrity_dir/classes:$integrity_jar" forge.bench.PriorityBoardTargetWitness "$integrity_root"
echo 'RUN XBoardTargetDomainSmoke'
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
  -cp "$integrity_dir/classes:$integrity_jar" forge.bench.XBoardTargetDomainSmoke "$integrity_root"
echo 'RUN SpellFaceIdentitySmoke'
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
  -cp "$integrity_dir/classes:$integrity_jar" forge.bench.SpellFaceIdentitySmoke "$integrity_root"
for integrity_fixture in SpellFaceReceiptMutationSmoke ReplacementExecutionScopeSmoke SpellFaceExecutionSmoke MonolithLoopEngineSmoke ReflectedManaPaymentEngineSmoke PaidOptionalTriggerExecutionSmoke RepeatedManaTriggerExecutionSmoke SelfSacrificeExecutionSmoke ModalActivationDomainSmoke PaymentPoolReceiptEngineSmoke TriggerOrderExecutionSmoke CombatDeclarationExecutionSmoke MillOrderExecutionSmoke HostChoiceIntegritySmoke DiscardChoiceExecutionSmoke HandKnowledgeEffectSmoke ForcedControllerChoiceSmoke ClosedInformationRepairSmoke ProspectiveCdaScopeSmoke PermissionTaxProjectionSmoke; do
  echo "RUN $integrity_fixture"
  /opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
    -cp "$integrity_dir/classes:$integrity_jar" "forge.bench.$integrity_fixture" "$integrity_root"
done
echo 'RUN ShuffleIdentityExecutionSmoke'
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
  -cp "$integrity_dir/classes:$integrity_jar" forge.bench.ShuffleIdentityExecutionSmoke "$integrity_root"
echo 'RUN CombatDamageExecutionSmoke'
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
  -cp "$integrity_dir/classes:$integrity_jar" forge.bench.CombatDamageExecutionSmoke "$integrity_root"
echo 'RUN HostLifecycleSmoke'
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
  -cp "$integrity_dir/classes:$integrity_jar" forge.bench.HostLifecycleSmoke "$integrity_root"
echo 'RUN RevealHistorySmoke'
/opt/homebrew/opt/openjdk@17/bin/java -Xmx2g -Duser.home="$integrity_dir/home" \
  -cp "$integrity_dir/classes:$integrity_jar" forge.bench.RevealHistorySmoke "$integrity_root" "$integrity_dir/reveal-states.json"
echo 'PASS integrated development fixtures; NOT CERTIFIED for strength comparisons'
