package forge.bench.rl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinSolver;
import forge.bench.PlayerControllerBridge;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.player.PlayerController.BinaryChoiceType;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * Lane search-bones-1015, the baseline "S-c + ForcedWin": the forced-win solver in front of an RL seat (and its S-c
 * search), as the built-in profile {@code CubeComboSolver} puts it in front of CubeCombo. Installed as the seat's
 * {@link PlayerControllerBridge.PlanHook} only when the search spec sets {@code forcedWin}. At every priority the solver
 * decides first ({@link ForcedWinSolver#decide}: follow its active line, counter back, defend against the opponent's
 * forced win, or look for its own); when it has no opinion the seat asks the policy (and S-c) as before. While a line is
 * active the solver answers the line's choice asks (picks, piles, orders, scry, triggers, the alpha strike, no
 * shuffle), and it chooses our discard effect's card from the opponent's hand. Everything else is the seat's.
 *
 * <p>Every solver call runs in this seat's own id scope (as {@code CubeComboAi.probeIds}): ability copies and
 * enumerations it makes do not advance the live game's id counters, so a priority at which the solver has no opinion
 * leaves the live game as the seat alone would.
 */
public final class SolverDriver implements PlayerControllerBridge.PlanHook {

    /** First id of the probe range: below CubeComboAi's (1.1e9), above a scoped live game's ids (from 1e9). */
    static final int PROBE_ID_BASE = 1_060_000_000;
    static final int PROBE_ID_STRIDE = 20_000_000;

    final ForcedWinSolver solver;
    private final Player me;
    private final Object probeIds;

    /** Priority calls, those the solver owned, the actions it played; its time per priority call. */
    int calls, owned, actions;
    final List<Double> msEach = new ArrayList<>();
    /** The time of the solver's last priority call (ms), read by the search's decision row. */
    double lastMs;
    /** Choice answers the solver gave, by kind. */
    final Map<String, Integer> answers = new java.util.TreeMap<>();

    public SolverDriver(final Player me, final ForcedWinSolver.Config cfg) {
        this(me, cfg, true);
    }

    /** {@code scoped} false: no id scope of its own (inside a search's game copy, which has its own). */
    public SolverDriver(final Player me, final ForcedWinSolver.Config cfg, final boolean scoped) {
        this.me = me;
        this.scoped = scoped;
        this.solver = new ForcedWinSolver(me, cfg);
        final AtomicInteger[] counters = new AtomicInteger[IdScope.Kind.values().length];
        final int base = PROBE_ID_BASE + PROBE_ID_STRIDE * Math.floorMod(me.getId(), 2);
        for (int i = 0; i < counters.length; i++) {
            counters[i] = new AtomicInteger(base);
        }
        this.probeIds = counters;
    }

    /** The solver config from a spec object of string values (the keys of {@link ForcedWinSolver.Config#fromProps}). */
    public static ForcedWinSolver.Config config(final JsonObject o) {
        final Map<String, String> kv = new java.util.HashMap<>();
        if (o != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                kv.put(e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString());
            }
        }
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    private final boolean scoped;

    <T> T probe(final Supplier<T> body) {
        if (!scoped) {
            return body.get();
        }
        final Object prev = IdScope.capture();
        if (prev == probeIds) {
            return body.get();
        }
        IdScope.install(probeIds);
        try {
            return body.get();
        } finally {
            IdScope.install(prev);
        }
    }

    private void count(final String k) {
        answers.merge(k, 1, Integer::sum);
    }

    public ForcedWinSolver solver() {
        return solver;
    }

    @Override
    public List<SpellAbility> priority(final PlayerControllerBridge c) {
        final long t0 = System.nanoTime();
        calls++;
        final ForcedWinSolver.Decision d = probe(() -> solver.decide(me.getGame()));
        lastMs = (System.nanoTime() - t0) / 1e6;
        msEach.add(lastMs);
        if (!d.owned) {
            return PlayerControllerBridge.NO_OPINION;
        }
        owned++;
        if (d.action == null) {
            return null;
        }
        actions++;
        final forge.game.phase.PhaseHandler ph = me.getGame().getPhaseHandler();
        actTurn = ph.getTurn();
        actPhase = ph.getPhase();
        final List<SpellAbility> out = new ArrayList<>();
        out.add(d.action);
        return out;
    }

    /** The turn and phase of the solver's last action (its costs are paid right after the hook returns it). */
    private int actTurn = -1;
    private forge.game.phase.PhaseType actPhase;

    /**
     * True in a phase where the solver played an action: the card choices of costs (Underworld Breach's escape exile,
     * a sacrifice, a discard) are Forge AI's, as in the engine's copies the line was found and verified on.
     */
    @Override
    public boolean forgeCosts() {
        final forge.game.phase.PhaseHandler ph = me.getGame().getPhaseHandler();
        return ph.getTurn() == actTurn && ph.getPhase() == actPhase;
    }

    @Override
    public boolean attack(final Player attacker, final Combat combat) {
        final boolean a = probe(() -> solver.answerAttack(attacker, combat));
        if (a) {
            count("attack");
        }
        return a;
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> look(final CardCollection topN) {
        final ImmutablePair<CardCollection, CardCollection> r = probe(() -> solver.answerLook(topN));
        if (r != null) {
            count("look");
        }
        return r;
    }

    @Override
    public List<SpellAbility> triggers(final List<SpellAbility> abilities) {
        if (!solver.lineActive()) {
            return null;
        }
        final List<SpellAbility> rest = probe(() -> solver.answerTriggers(abilities));
        if (rest != null && rest.size() != abilities.size()) {
            count("trigger");
        }
        return rest;
    }

    @Override
    public Card pick(final ZoneType destination, final List<ZoneType> origin, final SpellAbility sa,
            final CardCollection choices, final Player decider) {
        final Card c = probe(() -> solver.answerPick(destination, origin, sa, choices, decider));
        if (c != null) {
            count("pick");
        }
        return c;
    }

    @Override
    public CardCollectionView order(final CardCollectionView cards, final ZoneType destination, final SpellAbility source) {
        final CardCollectionView o = probe(() -> solver.answerOrder(cards, destination, source));
        if (o != null) {
            count("order");
        }
        return o;
    }

    @Override
    public CardCollection discard(final Player p, final SpellAbility sa, final CardCollection valid, final int min,
            final int max) {
        final CardCollection d = probe(() -> solver.answerDiscard(p, sa, valid, min, max));
        if (d != null) {
            count("discard");
        }
        return d;
    }

    @Override
    public Boolean confirm(final SpellAbility sa, final String message) {
        final Boolean b = probe(() -> solver.answerConfirm(sa, message));
        if (b != null) {
            count("confirm");
        }
        return b;
    }

    @Override
    public boolean confirmTrigger(final WrappedAbility w) {
        final boolean b = probe(() -> solver.answerConfirmTrigger(w));
        if (b) {
            count("confirmTrigger");
        }
        return b;
    }

    @Override
    public Boolean binary(final BinaryChoiceType kind, final Boolean defaultVal) {
        final Boolean b = probe(() -> solver.answerBinary(kind, defaultVal));
        if (b != null) {
            count("binary");
        }
        return b;
    }

    /** Per-game summary: the solver's counters, the driver's, and the time per priority call. */
    public JsonObject summary() {
        final JsonObject o = new JsonObject();
        o.addProperty("solver", solver.summary());
        o.addProperty("calls", calls);
        o.addProperty("owned", owned);
        o.addProperty("actions", actions);
        o.addProperty("taken", solver.taken);
        o.addProperty("defense_counters", solver.counters);
        o.addProperty("defense_holds", solver.holds);
        o.addProperty("discard_choices", solver.discardChoices);
        final JsonObject a = new JsonObject();
        answers.forEach(a::addProperty);
        o.add("answers", a);
        final JsonArray ms = new JsonArray();
        for (double d : msEach) {
            if (d >= 5.0) {
                ms.add(Math.round(d * 10) / 10.0); // the calls that did any search (gated calls cost < 5 ms)
            }
        }
        o.add("msOver5", ms);
        int over8 = 0;
        double max = 0;
        for (double d : msEach) {
            max = Math.max(max, d);
            if (d > 8000) {
                over8++;
            }
        }
        o.addProperty("over8s", over8);
        o.addProperty("maxMs", Math.round(max));
        return o;
    }
}
