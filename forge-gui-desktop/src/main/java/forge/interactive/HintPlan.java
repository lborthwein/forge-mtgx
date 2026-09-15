package forge.interactive;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/**
 * One AI evaluation, cached for the whole of one human-seat decision.
 *
 * <p>Forge's AI decides a whole line at once — cast X, targeting Y, paying with
 * Z, with X=3 — while the bridge asks the browser a sequence of granular
 * questions. Recomputing the AI at each sub-request would be both wasteful and
 * incoherent: at the target step the AI might prefer a different spell than the
 * one already committed at the priority step, and (under the live-swap path)
 * every recomputation re-runs target selection over in-flight SpellAbilities.
 *
 * <p>So the plan is computed exactly once, at the request that <em>opens</em> a
 * decision ({@code priority}, {@code combat} or {@code mulligan}), and is then
 * projected onto each follow-up {@code mana} / {@code target} / {@code choice} /
 * {@code confirm} sub-request of the same line. The moment the seat submits an
 * input that diverges from the plan, the plan is marked {@link #diverged} and
 * every later sub-request of that line carries no hint.
 */
final class HintPlan {
    /** Monotonic id of the decision this plan opened. */
    final long decisionId;
    /** The kind of the request that opened the decision. */
    final String openedKind;

    /** Priority: the SpellAbility Forge's AI chose, or null meaning "pass". */
    final SpellAbility chosenSa;
    /** Priority: the host card of {@link #chosenSa}, captured eagerly. */
    final Card chosenHost;
    /** Priority: true when the chosen line is a land drop, not an activation. */
    final boolean chosenIsLand;

    /** Combat/attack: attackerId -> "player"|"card". */
    final Map<Integer, String> attackDefenderKind;
    /** Combat/attack: attackerId -> defenderId. */
    final Map<Integer, Integer> attackDefenderId;
    /** Combat/block: blockerId -> attackerId. */
    final Map<Integer, Integer> blockAssignments;

    /** Mulligan: true to keep the hand. */
    final Boolean mulliganKeep;

    /** Mana: the mana source cards Forge's AI would tap, in order. */
    final List<Integer> manaSourceIds;

    /** London mulligan: the card ids Forge's AI would put back, in order. */
    final List<Integer> londonTuckIds;

    /** Wall-clock milliseconds the evaluation cost. */
    final long ms;
    /** Non-null when the evaluation timed out, threw, or produced nothing. */
    final String degraded;

    /** Set once the seat submits something the plan did not predict. */
    private boolean diverged;
    /** Sub-requests already served from this plan, for diagnostics. */
    private int projections;

    private HintPlan(final long decisionId, final String openedKind,
                     final SpellAbility chosenSa, final Card chosenHost, final boolean chosenIsLand,
                     final Map<Integer, String> attackDefenderKind,
                     final Map<Integer, Integer> attackDefenderId,
                     final Map<Integer, Integer> blockAssignments,
                     final Boolean mulliganKeep,
                     final List<Integer> manaSourceIds,
                     final List<Integer> londonTuckIds,
                     final long ms, final String degraded) {
        this.decisionId = decisionId;
        this.openedKind = openedKind;
        this.chosenSa = chosenSa;
        this.chosenHost = chosenHost;
        this.chosenIsLand = chosenIsLand;
        this.attackDefenderKind = attackDefenderKind;
        this.attackDefenderId = attackDefenderId;
        this.blockAssignments = blockAssignments;
        this.mulliganKeep = mulliganKeep;
        this.manaSourceIds = manaSourceIds;
        this.londonTuckIds = londonTuckIds;
        this.ms = ms;
        this.degraded = degraded;
    }

    static HintPlan priority(final long decisionId, final SpellAbility sa, final long ms) {
        final Card host = sa == null ? null : sa.getHostCard();
        final boolean land = sa != null && sa.isLandAbility();
        return new HintPlan(decisionId, "priority", sa, host, land,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                null, Collections.emptyList(), Collections.emptyList(), ms, null);
    }

    static HintPlan attacks(final long decisionId, final Map<Integer, String> kinds,
                            final Map<Integer, Integer> defenders, final long ms) {
        return new HintPlan(decisionId, "combat", null, null, false,
                new LinkedHashMap<>(kinds), new LinkedHashMap<>(defenders), Collections.emptyMap(),
                null, Collections.emptyList(), Collections.emptyList(), ms, null);
    }

    static HintPlan blocks(final long decisionId, final Map<Integer, Integer> blocks, final long ms) {
        return new HintPlan(decisionId, "combat", null, null, false,
                Collections.emptyMap(), Collections.emptyMap(), new LinkedHashMap<>(blocks),
                null, Collections.emptyList(), Collections.emptyList(), ms, null);
    }

    static HintPlan mulligan(final long decisionId, final boolean keep, final long ms) {
        return new HintPlan(decisionId, "mulligan", null, null, false,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                keep, Collections.emptyList(), Collections.emptyList(), ms, null);
    }

    static HintPlan mana(final long decisionId, final List<Integer> sourceIds, final long ms) {
        return new HintPlan(decisionId, "mana", null, null, false,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                null, List.copyOf(sourceIds), Collections.emptyList(), ms, null);
    }

    static HintPlan london(final long decisionId, final List<Integer> tuckIds, final long ms) {
        return new HintPlan(decisionId, "mulligan", null, null, false,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                null, Collections.emptyList(), List.copyOf(tuckIds), ms, null);
    }

    static HintPlan degraded(final long decisionId, final String openedKind,
                             final String reason, final long ms) {
        return new HintPlan(decisionId, openedKind, null, null, false,
                Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
                null, Collections.emptyList(), Collections.emptyList(), ms, reason);
    }

    boolean isDegraded() {
        return degraded != null;
    }

    boolean hasDiverged() {
        return diverged;
    }

    void markDiverged() {
        diverged = true;
    }

    int noteProjection() {
        return ++projections;
    }

    /**
     * A priority plan whose chosen SpellAbility is still the one the seat is
     * acting on. Used to project target/mana/ability sub-requests.
     */
    boolean stillPlanning(final SpellAbility sa) {
        return !diverged && chosenSa != null && sa != null && chosenSa == sa;
    }
}
