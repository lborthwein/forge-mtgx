package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * Lane bvw-plr-1023 (GOAL rev 8, BVW prerequisite P-LR, "label during replay"): the HONEST forced-win solver's label of
 * one position, from the deciding seat's information only. Off unless a replay's label spec builds one; nothing in play
 * reads it.
 *
 * <p><b>Honesty, by construction.</b> {@link Spec#parse} refuses a spec without {@code "redraw": "belief"} or without
 * {@code hideOwnLibrary = "true"} in both solver configs. Every game the solver sees is a COPY of the live game whose
 * opponent hidden cards (hand and library) were re-drawn from the seat's belief pool ({@link LookaheadSearch}'s
 * {@code belief=uniform}: the cube's names, plus the basics) and whose own library was shuffled below the top the seat
 * can see; the solver's own verification worlds and engine root re-draw again from that pool ({@link
 * ForcedWinSolver#redraw}). Visibility (which opponent cards the seat can see) is read from the LIVE game, never from a
 * copy. The opponent's real hidden cards are never in any game the solver reads.
 *
 * <p><b>The checks.</b> The solver's gate is its own ({@link ForcedWinSolver#propose}): a priority with an empty stack in
 * the main phase of the player whose turn it is, or the other player's end step. So each eligible position gets one
 * check, from the seat that may act at sorcery speed:
 * <ul>
 *   <li><b>win</b> (our main phase, or their end step): the solver for our seat on an honest root copy, {@link
 *       Spec#worlds} belief worlds with the counter adversary. A line that won unopposed and in all K worlds is a
 *       PROVEN WIN. p_win = the best line's share of the K worlds (one line for every world: no strategy fusion over
 *       their hidden hand).</li>
 *   <li><b>loss</b> (their main phase, or our end step): K worlds drawn from OUR belief (their hidden cards re-drawn, our
 *       library shuffled; our hand is ours, so it stays). In each, the opponent's solver in exact mode looks for a line
 *       and verifies it against our real hand, the adversary countering each of its spells. A world with such a line is
 *       a lost world; all K lost = a PROVEN LOSS. The opponent may pick its line per world: it knows its own hand.</li>
 * </ul>
 * Anything else, or no proof, is UNRESOLVED (with p_win / p_loss when a check ran). How a proven game ends is the
 * losing seat's {@code GameLossReason} in the line's unopposed play-out; turns-to-end is that play-out's end turn minus
 * the position's (global turn numbers; 0 = this turn).
 *
 * <p><b>Not moving the game.</b> This class reads the live game and copies it under {@code synchronized (live)}; the
 * caller runs it under the bench isolation (scratch random stream, fresh id scope, AI caches and card memory put back),
 * and the replay's digest check proves the game was not moved.
 */
public final class HonestLabel {

    /** The label spec (the replay config's {@code label} block). */
    public static final class Spec {
        /** K: belief worlds per check. */
        public int worlds = 8;
        /** The win check's solver config (string values, {@link ForcedWinSolver.Config#fromProps}). */
        public JsonObject solverCfg = new JsonObject();
        /** The loss check's solver config (string values; exact=true and act=false are forced). */
        public JsonObject lossCfg = new JsonObject();
        /** The loss check on (default true). */
        public boolean loss = true;
        /** The re-draw: must be "belief". */
        public String redraw;
        public String beliefCube = BeliefSampler.LIVE_CUBE;
        public String beliefCubeSha256 = BeliefSampler.LIVE_CUBE_SHA256;
        public int beliefBasics = 8;
        /** Singleton pool (our deck and their seen cards left out): only for decks drafted together from one cube. */
        public boolean beliefSingleton = false;
        /** The position's wall budget for the loss worlds, ms (0 = none): worlds not started by then are not run. */
        public long lossWallMs = 0;
        public long seed = 1023L;

        static final Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("worlds", "solverCfg", "lossCfg",
                "loss", "redraw", "beliefCube", "beliefCubeSha256", "beliefBasics", "beliefSingleton", "lossWallMs",
                "seed"));

        /** The spec, or IllegalArgumentException ("refused: ...") when it is not honest or not well formed. */
        public static Spec parse(final JsonObject o) {
            if (o == null) {
                throw new IllegalArgumentException("refused: no label spec");
            }
            for (String k : o.keySet()) {
                if (!KEYS.contains(k) && !"turns".equals(k) && !"out".equals(k)) {
                    throw new IllegalArgumentException("label: unknown key " + k);
                }
            }
            final Spec s = new Spec();
            if (o.has("worlds")) s.worlds = o.get("worlds").getAsInt();
            if (o.has("solverCfg")) s.solverCfg = o.getAsJsonObject("solverCfg");
            if (o.has("lossCfg")) s.lossCfg = o.getAsJsonObject("lossCfg");
            if (o.has("loss")) s.loss = o.get("loss").getAsBoolean();
            if (o.has("redraw") && !o.get("redraw").isJsonNull()) s.redraw = o.get("redraw").getAsString();
            if (o.has("beliefCube")) s.beliefCube = o.get("beliefCube").getAsString();
            if (o.has("beliefCubeSha256")) s.beliefCubeSha256 = o.get("beliefCubeSha256").getAsString();
            if (o.has("beliefBasics")) s.beliefBasics = o.get("beliefBasics").getAsInt();
            if (o.has("beliefSingleton")) s.beliefSingleton = o.get("beliefSingleton").getAsBoolean();
            if (o.has("lossWallMs")) s.lossWallMs = o.get("lossWallMs").getAsLong();
            if (o.has("seed")) s.seed = o.get("seed").getAsLong();
            s.checkHonest();
            return s;
        }

        /** The honest-mode guard: belief re-draw and hidden own library, in every solver this spec builds. */
        public void checkHonest() {
            if (!"belief".equals(redraw)) {
                throw new IllegalArgumentException("refused: the label mode needs \"redraw\": \"belief\" (the opponent's "
                        + "hidden cards from the belief pool), not " + (redraw == null ? "none" : "\"" + redraw + "\""));
            }
            for (Map.Entry<String, JsonObject> e : java.util.Map.of("solverCfg", solverCfg, "lossCfg", lossCfg).entrySet()) {
                final JsonElement h = e.getValue() == null ? null : e.getValue().get("hideOwnLibrary");
                if (h == null || !"true".equals(h.getAsString())) {
                    throw new IllegalArgumentException("refused: the label mode needs " + e.getKey()
                            + ".hideOwnLibrary = \"true\" (our own library order hidden), not " + h);
                }
            }
            if (beliefCube == null || beliefCube.isBlank()) {
                throw new IllegalArgumentException("refused: the label mode needs a belief cube");
            }
            if (worlds < 1 || worlds > 64 || beliefBasics < 1) {
                throw new IllegalArgumentException("label: worlds in [1, 64], beliefBasics >= 1");
            }
        }

        /** The win check's solver config: S-b's budgets, the spec's keys, K worlds, act=false, no hand-written defence. */
        ForcedWinSolver.Config winConfig() {
            final JsonObject o = defaults();
            for (Map.Entry<String, JsonElement> e : solverCfg.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
            o.addProperty("worlds", String.valueOf(worlds));
            o.addProperty("act", "false");
            o.addProperty("defense", "false");
            o.addProperty("exact", "false");
            return config(o);
        }

        /** The loss check's (the opponent's) solver config: exact (one world, the position as it stands), act=false. */
        ForcedWinSolver.Config lossConfig() {
            final JsonObject o = defaults();
            for (Map.Entry<String, JsonElement> e : lossCfg.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
            o.addProperty("act", "false");
            o.addProperty("defense", "false");
            o.addProperty("exact", "true");
            return config(o);
        }

        private static JsonObject defaults() {
            final JsonObject o = new JsonObject();
            o.addProperty("budgetMs", "3000");
            o.addProperty("engineMs", "1500");
            o.addProperty("evalReserveMs", "1000");
            o.addProperty("wallGraceMs", "1000");
            return o;
        }

        private static ForcedWinSolver.Config config(final JsonObject o) {
            final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(k -> o.has(k) ? o.get(k).getAsString() : null);
            if (!c.hideOwnLibrary) {
                throw new IllegalStateException("refused: a label solver without hideOwnLibrary");
            }
            return c;
        }

        /** The seat's belief (uniform over the pool), as the live search's belief=uniform builds it. */
        LookaheadSearch.Config beliefConfig() {
            final LookaheadSearch.Config c = new LookaheadSearch.Config();
            c.belief = "uniform";
            c.beliefCube = beliefCube;
            c.beliefCubeSha256 = beliefCubeSha256;
            c.beliefBasics = beliefBasics;
            c.beliefSingleton = beliefSingleton;
            c.threads = 1;
            c.worlds = worlds;
            return c;
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("worlds", worlds);
            o.add("solverCfg", solverCfg.deepCopy());
            o.add("lossCfg", lossCfg.deepCopy());
            o.addProperty("loss", loss);
            o.addProperty("redraw", redraw);
            o.addProperty("beliefCube", beliefCube);
            o.addProperty("beliefCubeSha256", beliefCubeSha256);
            o.addProperty("beliefBasics", beliefBasics);
            o.addProperty("beliefSingleton", beliefSingleton);
            o.addProperty("lossWallMs", lossWallMs);
            o.addProperty("seed", seed);
            return o;
        }

        /** sha256 of the spec's canonical JSON (sorted keys). */
        public String sha() {
            try {
                final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                final byte[] h = md.digest(canonical(toJson()).getBytes(StandardCharsets.UTF_8));
                final StringBuilder sb = new StringBuilder();
                for (byte b : h) {
                    sb.append(String.format("%02x", b & 0xff));
                }
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        static String canonical(final JsonElement e) {
            if (e == null || e.isJsonNull()) {
                return "null";
            }
            if (e.isJsonObject()) {
                final TreeMap<String, JsonElement> m = new TreeMap<>();
                for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                    m.put(x.getKey(), x.getValue());
                }
                final StringBuilder sb = new StringBuilder("{");
                for (Map.Entry<String, JsonElement> x : m.entrySet()) {
                    if (sb.length() > 1) {
                        sb.append(',');
                    }
                    sb.append(new com.google.gson.JsonPrimitive(x.getKey())).append(':').append(canonical(x.getValue()));
                }
                return sb.append('}').toString();
            }
            if (e.isJsonArray()) {
                final StringBuilder sb = new StringBuilder("[");
                for (JsonElement x : e.getAsJsonArray()) {
                    if (sb.length() > 1) {
                        sb.append(',');
                    }
                    sb.append(canonical(x));
                }
                return sb.append(']').toString();
            }
            return e.toString();
        }
    }

    /** One position's label. */
    public static final class Result {
        /** win | loss | unresolved. */
        public String label = "unresolved";
        /** win | loss | none: which check ran. */
        public String check = "none";
        /** Why no check ran ("ok" when one did): stack, phase:X, players, error. */
        public String gate = "ok";
        public double pWin = Double.NaN, pLoss = Double.NaN;
        /** Win check: the worlds the best line ran (K when complete). Loss check: the worlds run, and lost. */
        public int worldsWin, worldsLoss, lostWorlds;
        /** Win check: proposals that won unopposed. */
        public int proposals;
        /** The proven line's end: the loser's GameLossReason name, its class, the line's kind and steps. */
        public String endReason, how = "unresolved", lineHow;
        public int lineSteps = -1;
        /** Turns from the position to the proven end (global turn numbers; -1 = not proven). */
        public int turnsToEnd = -1;
        public double ms;
        public String error;

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("label", label);
            o.addProperty("check", check);
            o.addProperty("gate", gate);
            add(o, "p_win", pWin);
            add(o, "p_loss", pLoss);
            o.addProperty("worlds_win", worldsWin);
            o.addProperty("worlds_loss", worldsLoss);
            o.addProperty("lost_worlds", lostWorlds);
            o.addProperty("proposals", proposals);
            o.addProperty("how", how);
            if (endReason != null) o.addProperty("end_reason", endReason);
            if (lineHow != null) o.addProperty("line_how", lineHow);
            o.addProperty("line_steps", lineSteps);
            if (turnsToEnd >= 0) {
                o.addProperty("turns_to_end", turnsToEnd);
            } else {
                o.add("turns_to_end", com.google.gson.JsonNull.INSTANCE);
            }
            o.addProperty("ms", Math.round(ms * 10) / 10.0);
            if (error != null) o.addProperty("error", error);
            return o;
        }

        private static void add(final JsonObject o, final String k, final double v) {
            if (Double.isNaN(v)) {
                o.add(k, com.google.gson.JsonNull.INSTANCE);
            } else {
                o.addProperty(k, Math.round(v * 1e6) / 1e6);
            }
        }
    }

    /** The class of a GameLossReason name: life, library, poison, alt-win, or other. */
    public static String howOf(final String reason) {
        if (reason == null) {
            return "unresolved";
        }
        switch (reason) {
            case "LifeReachedZero": case "CommanderDamage": return "life";
            case "Milled": return "library";
            case "Poisoned": return "poison";
            case "OpponentWon": case "SpellEffect": return "alt-win";
            default: return "other:" + reason;
        }
    }

    /**
     * One seat's labeller in one game: the seat's own belief (its pool leaves out what IT can see; a {@link
     * LookaheadSearch} per seat and game, as live). Build it on the game thread, under the caller's isolation.
     */
    public static final class Seat {
        private final Spec spec;
        private final LookaheadSearch belief;
        private final ForcedWinSolver.Redraw redrawBase;

        public Seat(final Spec spec) {
            spec.checkHonest();
            this.spec = spec;
            this.belief = new LookaheadSearch(spec.beliefConfig());
            this.redrawBase = belief.opponentRedraw();
            if (redrawBase == null) {
                throw new IllegalStateException("refused: the belief re-draw is off (no belief cube loaded)");
            }
        }

        /**
         * The label of {@code liveMe}'s position in {@code live} (a priority the caller saw). {@code key} seeds the
         * position's worlds. Never throws; an error is an unresolved label with {@link Result#error}.
         */
        public Result label(final Game live, final Player liveMe, final long key) {
            final long t0 = System.nanoTime();
            final Result r = new Result();
            try {
                label0(live, liveMe, key, r);
            } catch (RuntimeException | StackOverflowError e) {
                r.label = "unresolved";
                r.gate = "error";
                r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            r.ms = (System.nanoTime() - t0) / 1e6;
            return r;
        }

        private void label0(final Game live, final Player liveMe, final long key, final Result r) {
            final PhaseHandler ph = live.getPhaseHandler();
            if (!live.getStack().isEmpty()) {
                r.gate = "stack";
                return;
            }
            final List<Player> opps = new ArrayList<>(liveMe.getOpponents());
            if (opps.size() != 1) {
                r.gate = "players";
                return;
            }
            final Player liveOpp = opps.get(0);
            final boolean mine = ph.isPlayerTurn(liveMe);
            final boolean main = ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2);
            final boolean end = ph.is(PhaseType.END_OF_TURN);
            if (mine && main || !mine && end) {
                r.check = "win";
                win(live, liveMe, liveOpp, key, r);
            } else if (spec.loss && (!mine && main || mine && end)) {
                r.check = "loss";
                loss(live, liveMe, liveOpp, key, r);
            } else {
                r.gate = "phase:" + ph.getPhase();
            }
        }

        /** An honest world: a copy of the live game, their hidden cards from our belief, our library shuffled. */
        private Object[] world(final Game live, final Player liveMe, final Player liveOpp, final Random rng) {
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1); // storm count, per-turn records (as the solver's own play-outs)
                final Game g = copier.makeCopy();
                final Player me = (Player) copier.find(liveMe);
                final Player opp = (Player) copier.find(liveOpp);
                // their hand + library re-drawn from the pool (visibility read in the LIVE game), our library below its
                // visible top shuffled
                belief.resampleBelief(live, liveMe, g, me, rng, null);
                return new Object[] {g, me, opp};
            }
        }

        private void win(final Game live, final Player liveMe, final Player liveOpp, final long key, final Result r) {
            final Object[] w = world(live, liveMe, liveOpp, new Random(LookaheadSearch.mix(spec.seed, key)));
            final Game root = (Game) w[0];
            final Player me = (Player) w[1];
            final ForcedWinSolver s = new ForcedWinSolver(me, spec.winConfig());
            // every world the solver copies from the root (verification worlds, the engine's root) re-draws their hidden
            // cards again from OUR pool, with visibility from the live game
            s.redraw = (lv, lvMe, g, cm, rng) -> redrawBase.apply(live, liveMe, g, cm, rng);
            if (s.redraw == null || !s.config().hideOwnLibrary) {
                throw new IllegalStateException("refused: dishonest win solver");
            }
            final List<ForcedWinSolver.Proposal> props = s.propose(root);
            r.proposals = props.size();
            ForcedWinSolver.Proposal best = null, proven = null;
            for (ForcedWinSolver.Proposal p : props) {
                if (best == null || p.pBelief > best.pBelief + 1e-9) {
                    best = p;
                }
                if (p.proven(spec.worlds) && (proven == null || p.endTurn >= 0 && p.endTurn < proven.endTurn)) {
                    proven = p;
                }
            }
            if (best != null) {
                r.pWin = best.pBelief;
                r.worldsWin = best.worldsRun;
            } else {
                r.pWin = Double.NaN; // no line won unopposed: no forced line found (not P = 0)
            }
            if (proven != null) {
                r.label = "win";
                r.pWin = proven.pBelief;
                r.worldsWin = proven.worldsRun;
                r.endReason = proven.endReason;
                r.how = proven.endReason == null ? "unknown" : howOf(proven.endReason);
                r.lineHow = proven.how;
                r.lineSteps = proven.steps;
                r.turnsToEnd = proven.endTurn < 0 ? -1 : Math.max(0, proven.endTurn - live.getPhaseHandler().getTurn());
            }
        }

        private void loss(final Game live, final Player liveMe, final Player liveOpp, final long key, final Result r) {
            final long wall = spec.lossWallMs <= 0 ? Long.MAX_VALUE : System.nanoTime() + spec.lossWallMs * 1_000_000L;
            final ForcedWinSolver.Config cfg = spec.lossConfig();
            int ran = 0, lost = 0, maxTurns = -1;
            final Map<String, Integer> reasons = new TreeMap<>();
            final Map<String, Integer> kinds = new TreeMap<>();
            int steps = -1;
            for (int j = 0; j < spec.worlds; j++) {
                if (System.nanoTime() > wall) {
                    break;
                }
                final Object[] w = world(live, liveMe, liveOpp,
                        new Random(LookaheadSearch.mix(spec.seed, key * 131L + 7919L * (j + 1))));
                final Game g = (Game) w[0];
                final Player opp = (Player) w[2];
                // the opponent's solver in this world (their sampled hand is theirs to know); exact: the line is checked
                // in this world as it stands, our real hand countering; no re-draw (the world already holds no real
                // hidden card of theirs), their own library hidden from their engine root (hideOwnLibrary)
                final ForcedWinSolver s = new ForcedWinSolver(opp, cfg);
                if (s.redraw != null || !s.config().hideOwnLibrary || !s.config().exact) {
                    throw new IllegalStateException("refused: dishonest loss solver");
                }
                final List<ForcedWinSolver.Proposal> props = s.propose(g);
                ran++;
                ForcedWinSolver.Proposal proven = null;
                for (ForcedWinSolver.Proposal p : props) {
                    if (p.proven(1) && (proven == null || p.endTurn >= 0 && p.endTurn < proven.endTurn)) {
                        proven = p;
                    }
                }
                if (proven != null) {
                    lost++;
                    reasons.merge(String.valueOf(proven.endReason), 1, Integer::sum);
                    kinds.merge(String.valueOf(proven.how), 1, Integer::sum);
                    steps = Math.max(steps, proven.steps);
                    if (proven.endTurn >= 0) {
                        maxTurns = Math.max(maxTurns, Math.max(0, proven.endTurn - live.getPhaseHandler().getTurn()));
                    }
                }
            }
            r.worldsLoss = ran;
            r.lostWorlds = lost;
            r.pLoss = ran == 0 ? Double.NaN : (double) lost / ran;
            if (ran == spec.worlds && lost == ran && ran > 0) {
                r.label = "loss";
                r.endReason = mode(reasons);
                r.how = r.endReason == null ? "unknown" : howOf(r.endReason);
                r.lineHow = mode(kinds);
                r.lineSteps = steps;
                r.turnsToEnd = maxTurns;
            }
        }

        private static String mode(final Map<String, Integer> m) {
            String best = null;
            int n = -1;
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (e.getValue() > n) {
                    best = e.getKey();
                    n = e.getValue();
                }
            }
            return "null".equals(best) ? null : best;
        }
    }

    private HonestLabel() {
    }
}
