package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AiCache;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * r2-choose-a-card-1029 (DESIGN-r2 §12.2, GOAL N5b): the value of an ACTION candidate (activate The One Ring, pay life to
 * Sylvan Library, Necro, Ad Nauseam, or pass) as the EXPECTATION OVER OUR OWN DRAWS, then the opponent's turn, then v.
 *
 * <ul>
 *   <li>The unknown part of our library: cards whose position the seat does not know ({@code known}: the
 *       {@link OwnLibraryMemory} set). Known-position cards stay where they are.</li>
 *   <li>EXACT for the next {@code drawDepth} unknown slots: every distinct name sequence, weighted by its probability
 *       (counts / remaining, without replacement). Over {@code seqCap} sequences: {@code samples} seeded samples, weight
 *       1 / samples.</li>
 *   <li>Per sequence and action: a copy (the opponent's hidden cards re-drawn by the caller's belief, when given), our
 *       library arranged (the sequence in the first unknown slots, the rest of the unknown cards in a seeded shuffle),
 *       the action played, then Forge AI on both seats through the opponent's turn to OUR next turn's first priority with
 *       an empty stack (at most {@code turnSteps} main-loop steps); then v. Every action sees the same arrangements
 *       (paired). A game that ended is terminal (1 / 0 / 0.5). All leaves of all actions: ONE values() call.</li>
 * </ul>
 * Every budget is a count. The live game is never moved (each copy runs in its own random stream, id scope and caches).
 */
public final class DrawExpectation {

    public static final class Config {
        public int drawDepth = 2;
        public int seqCap = 400;
        public int samples = 128;
        public int turnSteps = 4000;
        public long seed = 1029L;
        /** Tests only: allow no belief re-draw of the opponent (every real call must pass one: review S1-2). */
        public boolean testNoRedraw = false;
    }

    /** One action candidate, played on a copy. */
    public interface Action {
        String label();

        /** Play the action in the copy (it may go on the stack; the play-out resolves it). False = not playable. */
        boolean apply(Game copy, Player me);

        /**
         * The card whose abilities this seat does not use again in the play-out, up to the leaf (the decision is "use it
         * now or not": both arms leave it alone afterwards, so "pass" is not "use it at their end step"); -1 = none.
         */
        default int decidedSource() {
            return -1;
        }
    }

    public static Action pass() {
        return pass(-1);
    }

    /** Pass, declining {@code sourceId}'s abilities for the rest of this turn (the other arm of "use it now?"). */
    public static Action pass(final int sourceId) {
        return new Action() {
            @Override
            public String label() {
                return "pass";
            }

            @Override
            public boolean apply(final Game copy, final Player me) {
                return true;
            }

            @Override
            public int decidedSource() {
                return sourceId;
            }
        };
    }

    /** Activate the {@code index}-th spell ability of the card with id {@code cardId} (ids survive the copy). */
    public static Action activate(final int cardId, final int index, final String label) {
        return new Action() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public boolean apply(final Game copy, final Player me) {
                final Card c = copy.findById(cardId);
                if (c == null) return false;
                final List<SpellAbility> sas = new ArrayList<>(c.getSpellAbilities());
                if (index >= sas.size()) return false;
                final SpellAbility sa = sas.get(index);
                sa.setActivatingPlayer(me);
                if (!sa.canPlay()) return false;
                return ((PlayerControllerAi) me.getController()).playChosenSpellAbility(sa);
            }

            @Override
            public int decidedSource() {
                return cardId;
            }
        };
    }

    public static final class Result {
        public double[] ev;
        public final List<String> sequences = new ArrayList<>();
        public double[] weights;
        /** values[action][sequence]. */
        public double[][] values;
        /** The next drawDepth unknown cards were enumerated exactly AND no play-out drew more of our cards than that. */
        public boolean exact;
        /** The most of our library cards any play-out drew before its leaf (review S2-6: beyond drawDepth = not exact). */
        public int maxDraws;
        public int leaves, terminals, failures, valueCalls;
        /** A failed value batch (then every ev is -inf), or null. */
        public String error;
        public final JsonObject log = new JsonObject();
    }

    private final Config cfg;

    public DrawExpectation(final Config cfg) {
        this.cfg = cfg;
    }

    /**
     * Value each action. {@code known}: our library card ids whose positions the seat knows (null = none);
     * {@code redraw}: the belief re-draw of the opponent's hidden cards (null = none: only for tests and fully known
     * positions; logged as {@code opp_redraw: none}).
     */
    public Result evaluate(final Game live, final Player liveMe, final List<Action> actions, final Set<Integer> known,
            final ForcedWinSolver.Redraw redraw, final ChooseACard.Valuer valuer) {
        return evaluate(live, liveMe, actions, known, redraw, valuer, 0L);
    }

    /** As above; {@code key} (the decision's key) seeds the samples and the arrangements (review S3). */
    public Result evaluate(final Game live, final Player liveMe, final List<Action> actions, final Set<Integer> known,
            final ForcedWinSolver.Redraw redraw, final ChooseACard.Valuer valuer, final long key) {
        if (redraw == null && !cfg.testNoRedraw) {
            throw new IllegalStateException("draw expectation refused: no belief re-draw (the opponent's turn would be "
                    + "played from their real hidden cards)");
        }
        final long seed0 = LookaheadSearch.mix(cfg.seed, key);
        final Result res = new Result();
        // the unknown slots and cards of our library (top first)
        final List<Card> lib = new ArrayList<>(liveMe.getCardsIn(ZoneType.Library));
        final List<Integer> unknownSlots = new ArrayList<>();
        final List<Card> unknown = new ArrayList<>();
        for (int i = 0; i < lib.size(); i++) {
            if (known == null || !known.contains(lib.get(i).getId())) {
                unknownSlots.add(i);
                unknown.add(lib.get(i));
            }
        }
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<Card> sortedU = new ArrayList<>(unknown);
        sortedU.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        for (Card c : sortedU) counts.merge(c.getName(), 1, Integer::sum);
        final int d = Math.min(cfg.drawDepth, unknown.size());
        final List<List<String>> seqs = new ArrayList<>();
        final List<Double> w = new ArrayList<>();
        enumerate(new ArrayList<>(counts.keySet()), new ArrayList<>(counts.values()), unknown.size(), d, new ArrayList<>(),
                1.0, seqs, w, cfg.seqCap + 1);
        res.exact = seqs.size() <= cfg.seqCap;
        if (!res.exact) {
            seqs.clear();
            w.clear();
            for (int s = 0; s < cfg.samples; s++) {
                final List<Card> sh = new ArrayList<>(sortedU);
                Collections.shuffle(sh, new Random(LookaheadSearch.mix(seed0, 0x5A3D0000L + s)));
                final List<String> q = new ArrayList<>();
                for (int i = 0; i < d; i++) q.add(sh.get(i).getName());
                seqs.add(q);
                w.add(1.0 / cfg.samples);
            }
        }
        res.weights = new double[seqs.size()];
        for (int i = 0; i < seqs.size(); i++) {
            res.weights[i] = w.get(i);
            res.sequences.add(String.valueOf(seqs.get(i)));
        }
        final int A = actions.size(), S = seqs.size();
        final double[][] vals = new double[A][S];
        final List<Object> payloads = new ArrayList<>();
        final List<int[]> where = new ArrayList<>();
        final boolean[] ok = new boolean[A];
        java.util.Arrays.fill(ok, true);
        // review S3: a failed (action, sequence) play-out drops that sequence for that action (weights renormalised),
        // not the whole action; an action with no sequence left is dropped
        final boolean[][] seqOk = new boolean[A][S];
        for (int a = 0; a < A; a++) {
            for (int s = 0; s < S; s++) {
                final Object[] out = play(live, liveMe, actions.get(a), seqs.get(s), sortedU, unknownSlots, s, redraw, valuer,
                        seed0);
                if (out == null) {
                    res.failures++;
                    continue;
                }
                seqOk[a][s] = true;
                res.maxDraws = Math.max(res.maxDraws, (Integer) out[2]);
                if (out[0] != null) {
                    vals[a][s] = (Double) out[0];
                    res.terminals++;
                } else {
                    payloads.add(out[1]);
                    where.add(new int[] {a, s});
                }
            }
        }
        if (!payloads.isEmpty()) {
            res.valueCalls++;
            final double[] v = valuer.values(payloads);
            if (v == null || v.length != payloads.size()) {
                res.error = "value batch failed: " + valuer.error();
                res.ev = new double[A];
                java.util.Arrays.fill(res.ev, Double.NEGATIVE_INFINITY);
                res.log.addProperty("error", res.error);
                return res;
            }
            for (int k = 0; k < v.length; k++) vals[where.get(k)[0]][where.get(k)[1]] = v[k];
            res.leaves = payloads.size();
        }
        res.values = vals;
        res.ev = new double[A];
        final JsonArray aj = new JsonArray();
        res.exact = res.exact && res.maxDraws <= d;
        for (int a = 0; a < A; a++) {
            double e = 0, wsum = 0;
            for (int s = 0; s < S; s++) {
                if (seqOk[a][s]) {
                    e += res.weights[s] * vals[a][s];
                    wsum += res.weights[s];
                }
            }
            ok[a] = wsum > 0;
            if (ok[a]) e /= wsum;
            res.ev[a] = ok[a] ? e : Double.NEGATIVE_INFINITY;
            final JsonObject o = new JsonObject();
            o.addProperty("action", actions.get(a).label());
            if (ok[a]) o.addProperty("ev", Math.round(e * 1e6) / 1e6);
            else o.addProperty("dropped", "apply");
            aj.add(o);
        }
        res.log.add("actions", aj);
        res.log.addProperty("exact", res.exact);
        res.log.addProperty("max_draws", res.maxDraws);
        res.log.addProperty("failures", res.failures);
        res.log.addProperty("sequences", S);
        res.log.addProperty("draw_depth", d);
        res.log.addProperty("unknown", unknown.size());
        res.log.addProperty("known", lib.size() - unknown.size());
        res.log.addProperty("leaves", res.leaves);
        res.log.addProperty("terminals", res.terminals);
        res.log.addProperty("opp_redraw", redraw == null ? "none" : "belief");
        return res;
    }

    private static void enumerate(final List<String> names, final List<Integer> counts, final int total, final int d,
            final List<String> cur, final double p, final List<List<String>> out, final List<Double> w, final int stop) {
        if (out.size() >= stop) return;
        if (cur.size() == d) {
            out.add(new ArrayList<>(cur));
            w.add(p);
            return;
        }
        final int left = total - cur.size();
        for (int i = 0; i < names.size(); i++) {
            final int c = counts.get(i);
            if (c == 0) continue;
            counts.set(i, c - 1);
            cur.add(names.get(i));
            enumerate(names, counts, total, d, cur, p * c / left, out, w, stop);
            cur.remove(cur.size() - 1);
            counts.set(i, c);
        }
    }

    /** The seat's controller in a copy: Forge AI, stopping at our next turn's first priority with an empty stack. */
    static final class NextTurnLeaf extends LookaheadSearch.RolloutAi {
        final int startTurn;
        final int decided;
        boolean reached = false;

        NextTurnLeaf(final Game g, final Player p, final forge.LobbyPlayer lp, final int startTurn, final int decided) {
            super(g, p, lp);
            this.startTurn = startTurn;
            this.decided = decided;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            if (g.getPhaseHandler().getTurn() > startTurn && g.getPhaseHandler().getPlayerTurn() == getPlayer()
                    && g.getStack().isEmpty()) {
                reached = true;
                return null;
            }
            final List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
            if (decided >= 0 && chosen != null) {
                for (SpellAbility sa : chosen) {
                    if (sa.getHostCard() != null && sa.getHostCard().getId() == decided) {
                        return null; // the decided source is not used again before the leaf
                    }
                }
            }
            return chosen;
        }
    }

    /** One (action, sequence) copy: {terminal value or null, leaf payload}; null = failed. */
    private Object[] play(final Game live, final Player liveMe, final Action action, final List<String> seq,
            final List<Card> unknownById, final List<Integer> unknownSlots, final int s,
            final ForcedWinSolver.Redraw redraw, final ChooseACard.Valuer valuer, final long seed0) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        final long seed = LookaheadSearch.mix(seed0, 0xD2A0000L + s);
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player me;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1);
                if (!live.getStack().isEmpty()) {
                    if (GameCopier.stackUnsupported(live) != null) return null;
                    copier.setCopyStack(true);
                }
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                if (redraw != null) {
                    redraw.apply(live, liveMe, g, me, new Random(LookaheadSearch.mix(seed, 2)));
                }
            }
            arrange(me, seq, unknownById, unknownSlots, new Random(LookaheadSearch.mix(seed, 4)));
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 3)));
            final int turn = g.getPhaseHandler().getTurn();
            final NextTurnLeaf mine = new NextTurnLeaf(g, me, me.getController().getLobbyPlayer(), turn, action.decidedSource());
            me.dangerouslySetController(mine);
            for (Player o : g.getPlayers()) {
                if (o != me) o.dangerouslySetController(new LookaheadSearch.RolloutAi(g, o, o.getController().getLobbyPlayer()));
            }
            final Object ctx = valuer.onCopy(g, me);
            final int[] draws = {0};
            final int meId = me.getId();
            g.subscribeToEvents(new Object() {
                @com.google.common.eventbus.Subscribe
                public void on(final forge.game.event.GameEventCardChangeZone e) {
                    if (e.from() != null && e.from().zoneType() == ZoneType.Library && e.from().player() != null
                            && e.from().player().getId() == meId && e.to() != null && e.to().zoneType() == ZoneType.Hand) {
                        draws[0]++;
                    }
                }
            });
            if (!action.apply(g, me)) return null;
            int steps = 0;
            while (!g.isGameOver() && !mine.reached && steps < cfg.turnSteps) {
                g.getPhaseHandler().mainLoopStep();
                steps++;
            }
            if (g.isGameOver() || !me.isInGame()) {
                double t = me.hasWon() ? 1.0 : me.hasLost() || !me.isInGame() ? 0.0 : 0.5;
                for (Player o : me.getOpponents()) {
                    if (o.hasLost() && !me.hasLost()) t = 1.0;
                }
                return new Object[] {t, null, draws[0]};
            }
            if (!mine.reached) return null;
            return new Object[] {null, valuer.capture(ctx, g, me), draws[0]};
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("[draw-expectation] play failed: " + e);
            return null;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** Our library in the copy: known slots unchanged; the first unknown slots hold the sequence; the rest shuffled. */
    static void arrange(final Player me, final List<String> seq, final List<Card> unknownById,
            final List<Integer> unknownSlots, final Random rng) {
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final Map<Integer, Card> byId = new HashMap<>();
        for (Card c : lib) byId.put(c.getId(), c);
        final List<Card> pool = new ArrayList<>();
        for (Card c : unknownById) {
            final Card cc = byId.get(c.getId());
            if (cc == null) throw new IllegalStateException("draw expectation: library card " + c.getId() + " missing in the copy");
            pool.add(cc);
        }
        final List<Card> head = new ArrayList<>();
        for (String n : seq) {
            Card hit = null;
            for (Card c : pool) {
                if (c.getName().equals(n)) {
                    hit = c;
                    break;
                }
            }
            if (hit == null) throw new IllegalStateException("draw expectation: no " + n + " left");
            pool.remove(hit);
            head.add(hit);
        }
        Collections.shuffle(pool, rng);
        head.addAll(pool);
        final Card[] out = lib.toArray(new Card[0]);
        for (int i = 0; i < unknownSlots.size(); i++) {
            out[unknownSlots.get(i)] = head.get(i);
        }
        me.getZone(ZoneType.Library).setCards(java.util.Arrays.asList(out));
    }
}
