package forge.interactive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityView;

/**
 * Turns Forge objects into the bridge's {@code controlId} vocabulary.
 *
 * <p>The single invariant of this class: <b>it may only ever name a control the
 * request actually advertised.</b> {@code POST /input} already enforces that on
 * the way in; keeping it symmetric on the way out is what stops advice from
 * becoming a licence for the client to invent an action. Whenever the AI's
 * answer does not correspond to an advertised control the mapper returns an
 * empty list and the caller records {@code degraded: "unmapped"} — it never
 * guesses a nearby control.
 */
final class ExpertHintMapper {
    private ExpertHintMapper() {
    }

    /** Result of a mapping attempt: the ids, plus why nothing mapped. */
    record Mapped(List<String> controlIds, String degraded, String diagnostic) {
        static Mapped of(final List<String> ids) {
            return new Mapped(ids, null, null);
        }

        static Mapped unmapped(final String diagnostic) {
            return new Mapped(Collections.emptyList(), "unmapped", diagnostic);
        }

        static Mapped none(final String reason) {
            return new Mapped(Collections.emptyList(), reason, null);
        }

        boolean isEmpty() {
            return controlIds.isEmpty();
        }
    }

    /** {@code priority}: the AI's chosen line, or Forge's own pass control. */
    static Mapped priority(final HintPlan plan, final Set<String> advertised) {
        if (plan.chosenSa == null) {
            return advertised.contains("priority:pass")
                    ? Mapped.of(List.of("priority:pass"))
                    : Mapped.unmapped("AI passed but no passPriority control was advertised");
        }
        final Card host = plan.chosenHost;
        if (host == null) {
            return Mapped.unmapped("chosen ability had no host card");
        }
        final String id = "card:" + host.getId();
        if (!advertised.contains(id)) {
            return Mapped.unmapped("chosen host card " + host.getId() + " was not an advertised control");
        }
        return Mapped.of(List.of(id));
    }

    /**
     * {@code combat} declare-attackers: one control per assigned pair, then the
     * confirm. Attack pairs whose control was not advertised are dropped rather
     * than guessed; if none survive the hint degrades to the plain confirm.
     */
    static Mapped attacks(final HintPlan plan, final Set<String> advertised) {
        final List<String> ids = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : plan.attackDefenderId.entrySet()) {
            final int attackerId = entry.getKey();
            final String kind = plan.attackDefenderKind.get(attackerId);
            if (kind == null) {
                continue;
            }
            final String id = "combat:attack:" + attackerId + ":" + kind + ":" + entry.getValue();
            if (advertised.contains(id)) {
                ids.add(id);
            }
        }
        return finishCombat(ids, advertised, plan.attackDefenderId.size());
    }

    /** {@code combat} declare-blockers: one control per assignment, then confirm. */
    static Mapped blocks(final HintPlan plan, final Set<String> advertised) {
        final List<String> ids = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : plan.blockAssignments.entrySet()) {
            final String id = "combat:block:" + entry.getKey() + ":" + entry.getValue();
            if (advertised.contains(id)) {
                ids.add(id);
            }
        }
        return finishCombat(ids, advertised, plan.blockAssignments.size());
    }

    private static Mapped finishCombat(final List<String> ids, final Set<String> advertised,
                                       final int wanted) {
        // Forge's combat inputs are confirmed with the ordinary OK button. A
        // combat hint that named pairs but cannot name the confirm is useless
        // for the blend: the seat would assign and then stall.
        if (!advertised.contains("button:ok")) {
            return ids.isEmpty()
                    ? Mapped.none("no assignments and no confirm control")
                    : Mapped.unmapped("combat assignments had no advertised confirm control");
        }
        if (ids.isEmpty() && wanted > 0) {
            // The AI wanted assignments but none were advertised. Confirming an
            // empty combat is a real, different decision — do not silently
            // substitute it for the AI's answer.
            return Mapped.unmapped("no advertised control matched " + wanted + " AI combat assignment(s)");
        }
        ids.add("button:ok");
        return Mapped.of(List.copyOf(ids));
    }

    /** {@code mulligan}: London keep/return. */
    static Mapped mulligan(final HintPlan plan, final Set<String> advertised) {
        if (plan.mulliganKeep == null) {
            return Mapped.none("no mulligan decision in plan");
        }
        // The bridge advertises OK to keep. The cancel/mulligan control is
        // absent for InputLondonMulligan, where OK means "take this hand".
        if (plan.mulliganKeep) {
            if (advertised.contains("button:ok")) {
                return Mapped.of(List.of("button:ok"));
            }
            return Mapped.unmapped("keep decision had no advertised OK control");
        }
        if (advertised.contains("button:cancel")) {
            return Mapped.of(List.of("button:cancel"));
        }
        if (advertised.contains("confirm:no")) {
            return Mapped.of(List.of("confirm:no"));
        }
        return Mapped.unmapped("mulligan decision had no advertised mulligan control");
    }

    /**
     * {@code mulligan} / London tuck: the cards Forge's AI would put back, then
     * the confirm. A tuck that cannot name every card it wants is not a partial
     * answer — Forge requires exactly {@code cardsToReturn} — so it degrades.
     */
    static Mapped london(final HintPlan plan, final int cardsToReturn, final Set<String> advertised) {
        final List<String> ids = new ArrayList<>();
        for (Integer cardId : plan.londonTuckIds) {
            final String id = "card:" + cardId;
            if (advertised.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.size() != cardsToReturn) {
            return Mapped.unmapped("AI tuck named " + ids.size() + " advertised card(s) of "
                    + cardsToReturn + " required");
        }
        if (!advertised.contains("button:ok")) {
            return Mapped.unmapped("London tuck had no advertised confirm control");
        }
        ids.add("button:ok");
        return Mapped.of(List.copyOf(ids));
    }

    /**
     * {@code target}: the ids the AI chose for the live in-flight ability.
     *
     * One control per request: Forge re-asks after each selection and closes the
     * input itself once the required count is met, so naming the whole set at
     * once would push controls the next request has already withdrawn.
     */
    static Mapped targets(final HintPlan plan, final Set<String> advertised) {
        final List<String> ids = new ArrayList<>();
        for (String id : plan.targetControlIds) {
            if (advertised.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Mapped.unmapped("AI targets matched no advertised control");
        }
        return Mapped.of(List.copyOf(ids));
    }

    /** The {@code ability:<i>} whose emitted abilityId is the planned mana ability. */
    static Mapped manaAbility(final HintPlan plan, final Map<String, SpellAbilityView> byControlId) {
        if (plan.manaAbilityViewId == null) {
            return Mapped.none("no mana ability in plan");
        }
        for (Map.Entry<String, SpellAbilityView> entry : byControlId.entrySet()) {
            if (entry.getValue() != null && entry.getValue().getId() == plan.manaAbilityViewId) {
                return Mapped.of(List.of(entry.getKey()));
            }
        }
        return Mapped.unmapped("planned mana ability " + plan.manaAbilityViewId + " was not offered");
    }

    /** Legacy projection from a planned ability's own written targets. */
    static Mapped projectedTargets(final SpellAbility sa, final Map<Integer, Integer> seatByPlayerId,
                          final Set<String> advertised) {
        if (sa == null || sa.getTargets() == null) {
            return Mapped.none("no planned ability to read targets from");
        }
        final List<String> ids = new ArrayList<>();
        for (GameObject target : sa.getTargets()) {
            final String id;
            if (target instanceof Card card) {
                id = "card:" + card.getId();
            } else if (target instanceof Player player) {
                final Integer seat = seatByPlayerId.get(player.getId());
                if (seat == null) {
                    continue;
                }
                id = "player:" + seat;
            } else {
                continue;
            }
            if (advertised.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Mapped.unmapped("planned targets matched no advertised control");
        }
        // Forge closes a target selection with OK once the minimum is met.
        if (advertised.contains("button:ok")) {
            ids.add("button:ok");
        }
        return Mapped.of(List.copyOf(ids));
    }

    /**
     * {@code mana}: prefer a whole-cost pool payment Forge itself enumerated,
     * then the AI's own mana sources, then floating mana. Every branch names an
     * advertised control; none of them constructs a payment of its own.
     */
    static Mapped mana(final HintPlan plan, final List<String> paymentControlIds,
                       final Set<String> advertised) {
        // An exact whole-cost payment out of the existing pool is unambiguous
        // and finishes the request in one submit. Forge enumerated it, so it is
        // legal by construction.
        for (String payment : paymentControlIds) {
            if (advertised.contains(payment)) {
                return Mapped.of(List.of(payment));
            }
        }
        for (Integer sourceId : plan.manaSourceIds) {
            final String id = "card:" + sourceId;
            if (advertised.contains(id)) {
                // One source per request: tapping it produces a new mana
                // request, and the plan is recomputed against the new cost.
                return Mapped.of(List.of(id));
            }
        }
        if (plan.isDegraded()) {
            return Mapped.none(plan.degraded);
        }
        return Mapped.unmapped("no advertised payment matched "
                + plan.manaSourceIds.size() + " AI mana source(s)");
    }

    /** {@code confirm}: yes/no. */
    static Mapped confirm(final boolean yes, final Set<String> advertised) {
        final String wanted = yes ? "confirm:yes" : "confirm:no";
        if (advertised.contains(wanted)) {
            return Mapped.of(List.of(wanted));
        }
        final String fallback = yes ? "button:ok" : "button:cancel";
        if (advertised.contains(fallback)) {
            return Mapped.of(List.of(fallback));
        }
        return Mapped.unmapped("confirm decision had no advertised " + wanted + " control");
    }

    /**
     * {@code modal:getAbilityToPlay}: the {@code ability:<i>} control whose
     * emitted {@code abilityId} equals the planned ability's view id.
     */
    static Mapped abilityChoice(final SpellAbility planned,
                                final Map<String, SpellAbilityView> byControlId) {
        if (planned == null) {
            return Mapped.none("no planned ability");
        }
        final SpellAbilityView plannedView = planned.getView();
        if (plannedView == null) {
            return Mapped.unmapped("planned ability had no view");
        }
        for (Map.Entry<String, SpellAbilityView> entry : byControlId.entrySet()) {
            if (entry.getValue() != null && entry.getValue().getId() == plannedView.getId()) {
                return Mapped.of(List.of(entry.getKey()));
            }
        }
        return Mapped.unmapped("planned ability id " + plannedView.getId()
                + " was not among the offered abilities");
    }
}
