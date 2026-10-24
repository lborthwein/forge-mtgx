package forge.bench.rl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinSolver;
import forge.ai.simulation.GameCopier;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.BenchSession;
import forge.bench.CallCounter;
import forge.bench.JsonRpcChannel;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane bvw-step1-build-1024 (GOAL rev 8, BVW step 1): the honest search value of one position for one seat, the search
 * target of a step-1 value label. Nothing builds one without a replay label spec's {@code search} block.
 *
 * <p><b>What it runs.</b> The S-b search ({@link RlSearch} with {@link PlanSearch}: the forced-win solver's lines and the
 * policy's sampled turn-plans, plus the policy's own choice and the next prior entries, played out in K belief worlds by
 * the policy seat against Forge AI to the horizon) at the seat's priority, exactly as the S-B1 seat searches a live
 * decision, with the leaves scored by the search service's value backend (the step-(k-1) value tower, P3's batched
 * LEAVES). The value is the mean over the K worlds of the candidate the search plays (its own departure rule), on the
 * P(win) scale; a proven solver line taken at a plan point is 1.
 *
 * <p><b>Honesty, by construction, refused otherwise</b> ({@link Spec#parse}): belief {@code uniform} (every world's
 * opponent hidden hand and library re-drawn from the observation-only pool, our library shuffled below its known top),
 * the plans' solver on {@code hideOwnLibrary} with its verification worlds and engine root re-drawn from the same
 * belief, and its affordance probes started from a belief-redrawn root ({@link ForcedWinSolver.Config#honestProbes});
 * the seat's frames are its own observation (no privileged block). The search runs on a COPY of the replayed game; the
 * caller runs it under the bench isolation, and the replay's digest check proves the live game did not move.
 *
 * <p><b>Host-independent, refused otherwise:</b> no wall budget ({@code budgetMs} 0, {@code cpuCapMs} 0), the plans'
 * solver on {@code budget=counter}, and no wall-clock recording deadline (counter mode). Every world, line and play-out
 * is bounded by counters; the values are a function of the position, the seeds and the service's answers.
 */
public final class StepSearch {

    /** The {@code search} block: {@code rlsearch} (RlSearch.Config keys), {@code valueSha} (null = the policy's v(o)). */
    public static final class Spec {
        public final JsonObject json;
        public final int worlds;
        /** The value backend's sha the service must name (HELLO_ACK value_backend.value_sha), or null = none (control). */
        public final String valueSha;

        private Spec(final JsonObject json, final int worlds, final String valueSha) {
            this.json = json;
            this.worlds = worlds;
            this.valueSha = valueSha;
        }

        static final java.util.Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("rlsearch", "valueSha"));

        /**
         * The spec, or IllegalArgumentException ("refused: ...") unless honest and host-independent. {@code k} is the
         * label's K (its proof check's belief worlds): the search's worlds and its solver's must equal it.
         */
        public static Spec parse(final JsonObject o, final int k) {
            if (o == null) {
                throw new IllegalArgumentException("refused: no search block");
            }
            for (String key : o.keySet()) {
                if (!KEYS.contains(key)) {
                    throw new IllegalArgumentException("label.search: unknown key " + key);
                }
            }
            if (!o.has("rlsearch") || !o.get("rlsearch").isJsonObject()) {
                throw new IllegalArgumentException("refused: label.search needs an rlsearch object");
            }
            final JsonObject rs = o.getAsJsonObject("rlsearch");
            final RlSearch.Config c = RlSearch.Config.parse(rs.deepCopy());
            if (!"uniform".equals(c.belief) || c.beliefCube == null) {
                throw new IllegalArgumentException("refused: the step-1 search needs belief \"uniform\" with a beliefCube "
                        + "(every world's hidden cards from the observation-only pool), not " + c.belief);
            }
            if (c.plans == null) {
                throw new IllegalArgumentException("refused: the step-1 search is S-b: it needs plans");
            }
            final PlanSearch.Spec ps = PlanSearch.Spec.parse(c.plans);
            final ForcedWinSolver.Config sc = ps.solverConfig();
            if (!sc.hideOwnLibrary) {
                throw new IllegalArgumentException("refused: the step-1 search's solver needs hideOwnLibrary = \"true\"");
            }
            if (!sc.counter) {
                throw new IllegalArgumentException("refused: the step-1 search's solver needs budget = \"counter\" "
                        + "(host-independent labels)");
            }
            if (ps.defend || ps.opp) {
                throw new IllegalArgumentException("refused: the step-1 search runs without defend and opp (their "
                        + "solvers keep wall clocks)");
            }
            if (c.budgetMs != 0 || c.cpuCapMs != 0) {
                throw new IllegalArgumentException("refused: the step-1 search reads no wall or CPU clock: budgetMs 0 and "
                        + "cpuCapMs 0");
            }
            if (!"value".equals(c.leaf) || !"policy".equals(c.playout)) {
                throw new IllegalArgumentException("refused: the step-1 search needs leaf \"value\" and playout \"policy\"");
            }
            if (c.labelSink != null || c.decisionLog != null || c.forcedWin != null || c.choices != 0 || c.stack) {
                throw new IllegalArgumentException("refused: the step-1 search takes no labelSink, decisionLog, forcedWin, "
                        + "choices or stack");
            }
            if (c.worlds != k || sc.worlds != k || ps.minProvenWorlds != k) {
                throw new IllegalArgumentException("refused: the step-1 search's worlds, its solver's worlds and "
                        + "minProvenWorlds must all be the label's K = " + k + " (got " + c.worlds + ", " + sc.worlds + ", "
                        + ps.minProvenWorlds + ")");
            }
            if (c.policySha == null) {
                throw new IllegalArgumentException("refused: the step-1 search pins the served policy (policySha)");
            }
            final String vs = o.has("valueSha") && !o.get("valueSha").isJsonNull() ? o.get("valueSha").getAsString() : null;
            return new Spec(o.deepCopy(), k, vs);
        }

        RlSearch.Config config() {
            return RlSearch.Config.parse(json.getAsJsonObject("rlsearch").deepCopy());
        }

        /** sha256 of the block's canonical JSON (sorted keys). */
        public String sha() {
            try {
                final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                final byte[] h = md.digest(RlWire.canonicalString(json).getBytes(StandardCharsets.UTF_8));
                final StringBuilder sb = new StringBuilder();
                for (byte b : h) {
                    sb.append(String.format("%02x", b & 0xff));
                }
                return sb.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** One position's search value. */
    public static final class Result {
        /**
         * How the decision ended: searched, plan (a turn-plan was played), proven_line, or why nothing was searched
         * (stack, uncontested, trivial, score_failed, error, ...).
         */
        public String why = "none";
        /** The look-ahead's outcome (kept, departed, loop-guard, capped, ...), or null. */
        public String outcome;
        /** The value of the candidate the search plays, P(win) in [0, 1] (NaN = none). */
        public double v = Double.NaN;
        /** The best searched candidate's value (NaN = none). */
        public double vMax = Double.NaN;
        public int searched, given, worlds, failed;
        /** S-b took a proven solver line (all K worlds, unopposed) at a plan point: v = 1, no play-outs. */
        public boolean provenLine;
        /** The played candidate won (lost) in every world, its plan unbroken (S-b's MCTS-Solver flags). */
        public boolean provenWin, provenLoss;
        /** A leaf batch fell back to the static evaluator (never a value target). */
        public boolean leafFallback;
        public double ms;
        public JsonArray ev;
        public String error;
        /** The service's value backend identity (HELLO_ACK value_backend), or null. */
        public JsonElement backend;

        /** A usable value target. */
        public boolean hasTarget() {
            return !leafFallback && (provenLine || ("searched".equals(why) || "plan".equals(why)) && !Double.isNaN(v));
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("why", why);
            if (outcome != null) o.addProperty("outcome", outcome);
            o.add("v", num(v));
            o.add("v_max", num(vMax));
            o.addProperty("searched", searched);
            o.addProperty("given", given);
            o.addProperty("worlds", worlds);
            o.addProperty("failed", failed);
            o.addProperty("proven_line", provenLine);
            o.addProperty("proven_win", provenWin);
            o.addProperty("proven_loss", provenLoss);
            o.addProperty("leaf_fallback", leafFallback);
            if (ev != null) o.add("ev", ev);
            o.addProperty("ms", Math.round(ms * 10) / 10.0);
            if (error != null) o.addProperty("error", error);
            return o;
        }

        static JsonElement num(final double v) {
            return Double.isNaN(v) || Double.isInfinite(v) ? com.google.gson.JsonNull.INSTANCE
                    : new com.google.gson.JsonPrimitive(Math.round(v * 1e6) / 1e6);
        }
    }

    private final Spec spec;
    private final CardIndex index;
    private final int obs;
    private final String jarSha;
    private final String actorId;

    public StepSearch(final Spec spec, final CardIndex index, final int obs, final String jarSha, final String actorId) {
        this.spec = spec;
        this.index = index;
        this.obs = obs;
        this.jarSha = jarSha;
        this.actorId = actorId;
    }

    /** The search seed of a position (its game uid and label key). */
    static long seedOf(final long uid, final long key) {
        return RlSearch.splitmix(uid ^ RlSearch.splitmix(key ^ 0x5354455031L));
    }

    /**
     * The search value of {@code liveP}'s position in {@code live} (its priority, the replay about to ask its
     * controller). {@code liveKnow} is the replay's seat-knowledge tracker (read only: forked). Never throws.
     */
    public Result value(final Game live, final Player liveP, final RlKnowledge liveKnow, final long uid, final long key) {
        final long t0 = System.nanoTime();
        final Result res = new Result();
        RlSearch search = null;
        try {
            final Game c;
            final Player cp;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1); // per-turn records and history: the copy's menu is the live one
                c = copier.makeCopy();
                cp = (Player) copier.find(liveP);
            }
            final int seat = c.getRegisteredPlayers().indexOf(cp);
            final RlSearch.Config cfg = spec.config();
            search = new RlSearch(cfg, seedOf(uid, key), uid, index, liveKnow, jarSha, actorId, obs);
            final RlKnowledge know = liveKnow.forkFor(c);
            know.attach();
            final RlFeaturizer feat = new RlFeaturizer(index);
            feat.setVersion(obs);
            feat.setKnowledge(know);
            final BenchSession session = new BenchSession(new JsonRpcChannel(InputStream.nullInputStream(),
                    OutputStream.nullOutputStream()));
            session.setEncodeState(false);
            session.setLiveGame(c);
            session.setKnowledgeObserver(know);
            final String[] ctl = new String[c.getRegisteredPlayers().size()];
            for (int s = 0; s < ctl.length; s++) {
                ctl[s] = s == seat ? "rl:step1-label" : "forge";
            }
            final RlSeat.Config sc = new RlSeat.Config();
            sc.mode = "eval";
            final RlSearch s0 = search;
            final RlSeat.Endpoint ep = new RlSeat.Endpoint() {
                @Override
                public RlWire.Decision decide(final byte[] p) throws IOException {
                    return s0.policyDecide(p);
                }

                @Override
                public void record(final byte[] p) throws IOException {
                    throw new IOException("the step-1 label seat never records");
                }
            };
            final RlSeat rs = new RlSeat(feat, ep, sc, uid, ctl, false);
            rs.setGame(c);
            rs.seenNames = know::opponentSeen;
            rs.search = search;
            rs.searchSeat = seat;
            session.setLocalAnswerer(rs);
            final PlayerControllerBridge bc = new PlayerControllerBridge(c, cp, cp.getController().getLobbyPlayer(), session,
                    BenchSession.Mode.BRIDGE, seat, new CallCounter());
            cp.dangerouslySetController(bc);
            final boolean[] rl = new boolean[ctl.length];
            rl[seat] = true;
            search.attach(c, rl, rs);
            final PlanSearch ps = search.planSearchOf(cp);
            if (ps == null) {
                throw new IllegalStateException("refused: no plan search on the label seat");
            }
            // the plans' affordance probes start from a belief-redrawn, own-library-shuffled root (as the generator's)
            ps.driver.solver().config().honestProbes = true;
            if (ps.driver.solver().redraw == null || !ps.driver.solver().config().hideOwnLibrary
                    || !ps.driver.solver().config().counter) {
                throw new IllegalStateException("refused: a dishonest or wall-clock step-1 solver");
            }
            final Object[] got = new Object[5];
            search.tap = (why, st, r, given, ans) -> {
                if (got[0] == null) {
                    got[0] = why;
                    got[1] = st;
                    got[2] = r;
                    got[3] = given;
                    got[4] = ans;
                }
            };
            LookaheadSearch.givePriorityTo(c.getPhaseHandler(), cp);
            bc.chooseSpellAbilityToPlay();
            final JsonObject ack = search.servedAck();
            res.backend = ack == null || !ack.has("value_backend") ? null : ack.get("value_backend");
            checkBackend(ack);
            fill(res, got);
        } catch (RuntimeException | StackOverflowError e) {
            res.why = "error";
            res.v = Double.NaN;
            res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (search != null) {
                search.close();
            }
            res.ms = (System.nanoTime() - t0) / 1e6;
        }
        return res;
    }

    /** The service must serve the pinned value backend (or none for a control spec); refused otherwise. */
    private void checkBackend(final JsonObject ack) {
        if (ack == null) {
            return; // no connection was opened (a trivial menu): nothing was scored
        }
        final JsonElement vb = ack.get("value_backend");
        final String got = vb != null && vb.isJsonObject() && vb.getAsJsonObject().has("value_sha")
                ? vb.getAsJsonObject().get("value_sha").getAsString() : null;
        if (spec.valueSha == null ? got != null : !spec.valueSha.equals(got)) {
            throw new IllegalStateException("refused: the search service's value backend is " + got + ", the spec pins "
                    + spec.valueSha);
        }
    }

    private static void fill(final Result res, final Object[] got) {
        if (got[0] == null) {
            res.why = "trivial"; // the seat's menu was answered without a DECISION (pass only, one legal answer, capped)
            return;
        }
        res.why = (String) got[0];
        final LookaheadSearch.GivenResult r = (LookaheadSearch.GivenResult) got[2];
        res.given = (Integer) got[3];
        if ("proven_line".equals(res.why)) {
            res.provenLine = true;
            res.v = 1.0;
            res.worlds = 0;
            return;
        }
        if (r == null) {
            return;
        }
        res.outcome = r.outcome;
        res.failed = r.failed;
        res.leafFallback = r.leafFallback;
        res.worlds = r.worlds;
        res.searched = r.givenIndex.length;
        if (r.givenIndex.length == 0) {
            res.why = r.outcome == null ? "unsearched" : r.outcome;
            return;
        }
        final JsonArray ev = new JsonArray();
        int played = -1;
        for (int j = 0; j < r.givenIndex.length; j++) {
            ev.add(Result.num(r.ev[j]));
            if (r.givenIndex[j] == r.chosen) {
                played = j;
            }
            if (!Double.isNaN(r.ev[j]) && (Double.isNaN(res.vMax) || r.ev[j] > res.vMax)) {
                res.vMax = r.ev[j];
            }
        }
        res.ev = ev;
        if (played >= 0 && !Double.isNaN(r.ev[played])) {
            res.v = Math.max(0.0, Math.min(1.0, r.ev[played]));
            res.provenWin = played < r.provenWin.length && r.provenWin[played];
            res.provenLoss = played < r.provenLoss.length && r.provenLoss[played];
        }
        if (res.leafFallback) {
            res.v = Double.NaN;
        }
    }
}
