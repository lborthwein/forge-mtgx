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
import forge.ai.simulation.HonestLabel;
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
 * <p><b>What it runs.</b> An S-b search ({@link RlSearch} with {@link PlanSearch}: the forced-win solver's lines and the
 * policy's sampled turn-plans, plus the policy's own choice and the next prior entries, played out in K belief worlds by
 * the policy seat against Forge AI to the horizon) at the seat's priority, with the leaves scored by the search service's
 * value backend (the step-(k-1) value tower, P3's batched LEAVES). It is the S-B1 search's machinery but NOT the S-B1
 * seat (review #12): the search, its plan search and its solver are built fresh for every position, so there is no pile
 * memory (our library's known top is empty), no in-game engine node depletion and no no-line cache, and visibility is
 * read from the copy (which carries no may-look permissions). Each of those is less information, never more.
 *
 * <p><b>The value.</b> When the search keeps the policy's own choice, the mean over the K worlds of that candidate. When
 * it departs (to another candidate or a turn-plan), that candidate won a max over noisy K-world means, so its mean is
 * biased up; the value is then the same candidate played out again in K FRESH worlds, independently seeded (ruling 4).
 * A proven solver line taken at a plan point (all K worlds, unopposed) is 1. Values are on the P(win) scale.
 *
 * <p><b>Honesty, by construction, refused otherwise</b> ({@link Spec#parse}): belief {@code uniform} over the proof
 * check's own pool (every world's opponent hidden hand and library re-drawn from the observation-only pool, our library
 * shuffled below its known top), the plans' solver on {@code hideOwnLibrary} with its verification worlds and engine root
 * re-drawn from the same belief and its affordance probes started from a belief-redrawn root; the seat's frames are its
 * own observation (no privileged block). The search runs on a COPY of the replayed game under the bench isolation, and
 * the replay's digest check proves the live game did not move.
 *
 * <p><b>Host-independent, refused otherwise:</b> no wall budget ({@code budgetMs} 0, {@code cpuCapMs} 0), the plans'
 * solver on {@code budget=counter}, no wall-clock recording deadline, and the proof spec's pinned Forge AI settings.
 *
 * <p><b>Pins are fatal</b> (review #9): a service that serves another policy or value backend than the block pins, or no
 * service at all, throws {@link PinError}; the labeller stops the run (exit 2) instead of writing rows without targets.
 */
public final class StepSearch {

    /** A pinned identity the search service does not match, or no service: the run stops (never a row without target). */
    public static final class PinError extends IllegalStateException {
        PinError(final String why) {
            super(why);
        }
    }

    /** The block keys the identity leaves out (review #4): the transport, and the per-job value pin (rows carry it). */
    static final java.util.Set<String> TRANSPORT = new java.util.TreeSet<>(java.util.Arrays.asList("server",
            "connectTimeoutMs", "readTimeoutMs"));

    /**
     * The {@code search} block: {@code rlsearch} (RlSearch.Config keys), {@code valueSha} (the value backend the service
     * must serve; required unless {@code control} is true, the policy's own v(o)), {@code maxCopyCards} (review #11).
     */
    public static final class Spec {
        public final JsonObject json;
        public final int worlds;
        /** The value backend's sha the service must name (HELLO_ACK value_backend.value_sha), or null (control). */
        public final String valueSha;
        public final boolean control;
        /**
         * Review #11: the search prepares every candidate x world copy up front, so a position whose cards x worlds x
         * the most candidates exceeds this is not searched (no target, why "copies_capped").
         */
        public final int maxCopyCards;
        /** The band [lo, hi) and the proof spec's pinned Forge AI settings, part of the identity (reviews #14, #3). */
        public final int bandLo, bandHi;
        final JsonObject identity;
        final int maxCands;
        /**
         * bvw-step2-fork-1027 (S2T; null = a step-1 block): the teacher variant whose value is the row's target,
         * {@link #VARIANTS}. In the identity (key {@code target}).
         */
        public final String variant;
        /** S2T: the T re-play's worlds K' (T-sym's {@code k}; a smoke run's T with another variant: 8); 0 = none. */
        public final int trueK;
        /** S2T: the own-policy roll-out reference (key {@code opr}, in the identity): roll-outs, turn cap, step cap; k 0 = none. */
        public final int oprK, oprTurns, oprSteps;
        /**
         * S2T, key {@code smoke} (NOT in the identity): compute and log every variant (T-sym, H-sym, H-asym) and the
         * per-world leaf pairs; the spec variant's target is bit-identical with it on or off.
         */
        public final boolean smoke;

        private Spec(final JsonObject json, final int worlds, final String valueSha, final boolean control,
                final int maxCopyCards, final int bandLo, final int bandHi, final JsonObject identity, final int maxCands) {
            this(json, worlds, valueSha, control, maxCopyCards, bandLo, bandHi, identity, maxCands, null, 0, 0, 0, 0, false);
        }

        private Spec(final JsonObject json, final int worlds, final String valueSha, final boolean control,
                final int maxCopyCards, final int bandLo, final int bandHi, final JsonObject identity, final int maxCands,
                final String variant, final int trueK, final int oprK, final int oprTurns, final int oprSteps,
                final boolean smoke) {
            this.json = json;
            this.worlds = worlds;
            this.valueSha = valueSha;
            this.control = control;
            this.maxCopyCards = maxCopyCards;
            this.bandLo = bandLo;
            this.bandHi = bandHi;
            this.identity = identity;
            this.maxCands = maxCands;
            this.variant = variant;
            this.trueK = trueK;
            this.oprK = oprK;
            this.oprTurns = oprTurns;
            this.oprSteps = oprSteps;
            this.smoke = smoke;
        }

        /** bvw-step2-fork-1027: an S2T block (a {@code target} variant); its rows are schema mtgx-bvw-value/3. */
        public boolean s2t() {
            return variant != null;
        }

        /** S2T: the teacher variants (the row's target is the spec variant's value). */
        public static final java.util.List<String> VARIANTS = java.util.Collections.unmodifiableList(
                java.util.Arrays.asList("T-sym", "H-sym", "H-asym"));
        /** S2T: the T re-play's worlds in a smoke run whose variant is not T-sym. */
        public static final int SMOKE_TRUE_K = 8;

        static final java.util.Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("rlsearch", "valueSha",
                "control", "maxCopyCards", "target", "opr", "smoke"));

        /**
         * The spec, or IllegalArgumentException ("refused: ...") unless honest, host-independent and tied to the proof
         * check {@code label} (its K, its belief pool, its pinned Forge AI settings), for the band [bandLo, bandHi).
         */
        public static Spec parse(final JsonObject o, final HonestLabel.Spec label, final int bandLo, final int bandHi) {
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
            if (!label.counterBudgets()) {
                throw new IllegalArgumentException("refused: a step-1 search needs the proof check on budget=counter");
            }
            final int k = label.worlds;
            final JsonObject rs = o.getAsJsonObject("rlsearch");
            final RlSearch.Config c = RlSearch.Config.parse(rs.deepCopy());
            if (!("uniform".equals(c.belief) || "codraft".equals(c.belief)) || c.beliefCube == null) {
                throw new IllegalArgumentException("refused: the step-1 search needs belief \"uniform\" (or S2T's "
                        + "\"codraft\") with a beliefCube (every world's hidden cards from the observation-only pool), not "
                        + c.belief);
            }
            if ("codraft".equals(c.belief)) {
                // bvw-step2-fork-1027 (S2T): the co-draft table's pin is checked at the start (refused: exit 2)
                PlanSearch.loadSynPinned(c.beliefPrior, c.beliefPriorSha256);
            }
            // review #15: the search's belief pool is the proof check's
            if (!c.beliefCube.equals(label.beliefCube) || !java.util.Objects.equals(c.beliefCubeSha256, label.beliefCubeSha256)
                    || c.beliefBasics != label.beliefBasics || c.beliefSingleton != label.beliefSingleton) {
                throw new IllegalArgumentException("refused: the step-1 search's belief pool (beliefCube, beliefCubeSha256, "
                        + "beliefBasics, beliefSingleton) must be the proof check's");
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
            final boolean control = o.has("control") && o.get("control").getAsBoolean();
            final String vs = o.has("valueSha") && !o.get("valueSha").isJsonNull() ? o.get("valueSha").getAsString() : null;
            if (vs == null && !control) {
                throw new IllegalArgumentException("refused: the step-1 search pins its value backend (valueSha); only an "
                        + "explicit \"control\": true runs on the policy's own v(o)");
            }
            if (vs != null && control) {
                throw new IllegalArgumentException("refused: a control search block has no valueSha");
            }
            if (!o.has("maxCopyCards") || o.get("maxCopyCards").getAsInt() < 1) {
                throw new IllegalArgumentException("refused: the step-1 search needs its copy guard maxCopyCards >= 1");
            }
            final int maxCopyCards = o.get("maxCopyCards").getAsInt();
            if (bandLo < 0 || bandLo >= bandHi) {
                throw new IllegalArgumentException("label.search: band [lo, hi) needs 0 <= lo < hi");
            }
            // bvw-step2-fork-1027 (S2T): the target variant, the OPR reference and the smoke flag, refused unless honest
            final S2tKeys sk = S2tKeys.parse(o, c);
            // the identity: the block without its transport and per-job value pin, plus the band and the runtime
            final JsonObject id = new JsonObject();
            final JsonObject rsn = rs.deepCopy();
            for (String t : TRANSPORT) {
                rsn.remove(t);
            }
            id.add("rlsearch", rsn);
            if (control) {
                id.addProperty("control", true);
            }
            id.addProperty("maxCopyCards", maxCopyCards);
            final JsonArray band = new JsonArray();
            band.add(bandLo);
            band.add(bandHi);
            id.add("band", band);
            final JsonObject rt = new JsonObject();
            rt.addProperty("sequentialAi", label.sequentialAi);
            rt.addProperty("aiTimeoutSec", label.aiTimeoutSec);
            id.add("runtime", rt);
            if (sk.target != null) {
                // S2T: the variant and the OPR reference are in the identity; the smoke flag is not
                id.add("target", sk.target.deepCopy());
                if (sk.opr != null) {
                    id.add("opr", sk.opr.deepCopy());
                }
            }
            final int maxCands = Math.max(2, c.breadth) + ps.samples + ps.maxLines;
            return new Spec(o.deepCopy(), k, vs, control, maxCopyCards, bandLo, bandHi, id, maxCands, sk.variant, sk.trueK,
                    sk.oprK, sk.oprTurns, sk.oprSteps, sk.smoke);
        }

        RlSearch.Config config() {
            return RlSearch.Config.parse(json.getAsJsonObject("rlsearch").deepCopy());
        }

        /** The identity JSON the sha hashes (mtgx bvw.search_identity builds the same object). */
        public JsonObject identity() {
            return identity.deepCopy();
        }

        /** sha256 of the identity's canonical JSON (sorted keys): no transport, no value pin; band and runtime in. */
        public String sha() {
            try {
                final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                final byte[] h = md.digest(RlWire.canonicalString(identity).getBytes(StandardCharsets.UTF_8));
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

    /**
     * bvw-step2-fork-1027 (S2T): the block's {@code target} / {@code opr} / {@code smoke} keys, refused unless honest and
     * consistent. Any S2T play-out key (opponent "policy", leafSym, belief "codraft") or {@code opr} / {@code smoke}
     * needs an explicit {@code target}; every variant needs the opponent's play-out seat to be the policy (no Forge AI
     * seat, in particular none in a true world); T-sym and H-sym need the symmetric leaf, H-asym the plain one.
     */
    static final class S2tKeys {
        JsonObject target, opr;
        String variant;
        int trueK, oprK, oprTurns, oprSteps;
        boolean smoke;

        static S2tKeys parse(final JsonObject o, final RlSearch.Config c) {
            final S2tKeys s = new S2tKeys();
            final boolean playoutKeys = "policy".equals(c.opponent) || c.leafSym || "codraft".equals(c.belief);
            if (o.has("target") && !o.get("target").isJsonNull()) {
                if (!o.get("target").isJsonObject()) {
                    throw new IllegalArgumentException("label.search.target is {\"variant\": ..., \"k\": ...}");
                }
                s.target = o.getAsJsonObject("target");
                for (String key : s.target.keySet()) {
                    if (!"variant".equals(key) && !"k".equals(key)) {
                        throw new IllegalArgumentException("label.search.target: unknown key " + key);
                    }
                }
                s.variant = s.target.has("variant") ? s.target.get("variant").getAsString() : null;
                if (!Spec.VARIANTS.contains(s.variant)) {
                    throw new IllegalArgumentException("label.search.target.variant must be one of " + Spec.VARIANTS
                            + ", not " + s.variant);
                }
                if ("T-sym".equals(s.variant)) {
                    if (!s.target.has("k") || s.target.get("k").getAsInt() < 1 || s.target.get("k").getAsInt() > 64) {
                        throw new IllegalArgumentException("label.search.target: T-sym needs its true worlds k in 1..64");
                    }
                    s.trueK = s.target.get("k").getAsInt();
                } else if (s.target.has("k")) {
                    throw new IllegalArgumentException("label.search.target: k is T-sym's (its true worlds)");
                }
            }
            if (o.has("opr") && !o.get("opr").isJsonNull()) {
                if (!o.get("opr").isJsonObject()) {
                    throw new IllegalArgumentException("label.search.opr is {\"k\", \"maxTurns\", \"maxSteps\"}");
                }
                s.opr = o.getAsJsonObject("opr");
                for (String key : s.opr.keySet()) {
                    if (!"k".equals(key) && !"maxTurns".equals(key) && !"maxSteps".equals(key)) {
                        throw new IllegalArgumentException("label.search.opr: unknown key " + key);
                    }
                }
                if (!s.opr.has("k") || !s.opr.has("maxTurns") || !s.opr.has("maxSteps")) {
                    throw new IllegalArgumentException("label.search.opr needs k, maxTurns and maxSteps");
                }
                s.oprK = s.opr.get("k").getAsInt();
                s.oprTurns = s.opr.get("maxTurns").getAsInt();
                s.oprSteps = s.opr.get("maxSteps").getAsInt();
                if (s.oprK < 1 || s.oprK > 16 || s.oprTurns < 1 || s.oprTurns > 60 || s.oprSteps < 100
                        || s.oprSteps > 1_000_000) {
                    throw new IllegalArgumentException("label.search.opr: 1 <= k <= 16, 1 <= maxTurns <= 60, "
                            + "100 <= maxSteps <= 1000000");
                }
            }
            if (o.has("smoke") && !o.get("smoke").isJsonNull()) {
                s.smoke = o.get("smoke").getAsBoolean();
            }
            if (s.target == null) {
                if (playoutKeys || s.opr != null || o.has("smoke")) {
                    throw new IllegalArgumentException("refused: an S2T search block (opponent policy, leafSym, codraft, "
                            + "opr or smoke) names its target variant (label.search.target)");
                }
                return s;
            }
            if (c.beliefSingleton) {
                // step-3 hygiene (the coordinator's ruling after the review #123 recheck): S2T blocks draw from the
                // whole pool, as the proof check and step 1 do
                throw new IllegalArgumentException("refused: an S2T teacher needs rlsearch.beliefSingleton false");
            }
            if (!"policy".equals(c.opponent)) {
                // the honesty refusal: no Forge AI seat in an S2T play-out (above all in a true world: T, OPR)
                throw new IllegalArgumentException("refused: an S2T teacher needs rlsearch.opponent \"policy\" (the "
                        + "same policy seat on both sides; no Forge AI seat reads a true world)");
            }
            final boolean sym = !"H-asym".equals(s.variant);
            if (c.leafSym != sym) {
                throw new IllegalArgumentException("refused: variant " + s.variant + " needs rlsearch.leafSym " + sym);
            }
            if (s.smoke && s.trueK == 0) {
                s.trueK = Spec.SMOKE_TRUE_K;
            }
            return s;
        }
    }

    /** bvw-step2-fork-1027 (S2T): one row's variant values, re-play results and leaf pairs. */
    public static final class S2t {
        /** The variants' values (NaN = not computed or failed). */
        public double tSym = Double.NaN, hSym = Double.NaN, hAsym = Double.NaN;
        /** The spec variant's value (the row's search target before the proof precedence), NaN = none. */
        public double target = Double.NaN;
        /** The variants were computed (the search played the chosen candidate out). */
        public boolean computed;
        /** Why the T re-play gave no value (null = fine, or not run). */
        public String tWhy;
        /** The H target worlds' and the T worlds' [v_s, v_o, terminal] (smoke only; null otherwise). */
        public double[][] worlds, trueWorlds;
        /** OPR (null = not run): mean of the scored roll-outs, and the counts. */
        public double oprV = Double.NaN;
        public int oprScored, oprCapped, oprFailed;
        public boolean oprRan;
        /**
         * Review #123 S3-1: the true-world census of the T re-play and of OPR ({asks: Forge-answered asks by family,
         * calls: controller calls that never reached a policy seat by method, refused: refused policy answers}).
         */
        public JsonObject tForge, oprForge;

        /** The row's {@code s2t} object for {@code variant}. */
        public JsonObject toJson(final String variant) {
            final JsonObject o = new JsonObject();
            o.addProperty("variant", variant);
            if (computed) {
                final JsonObject t = new JsonObject();
                t.add("T-sym", Result.num(tSym));
                t.add("H-sym", Result.num(hSym));
                t.add("H-asym", Result.num(hAsym));
                o.add("targets", t);
            } else {
                o.add("targets", com.google.gson.JsonNull.INSTANCE);
            }
            if (oprRan) {
                final JsonObject r = new JsonObject();
                r.add("v", Result.num(oprV));
                r.addProperty("scored", oprScored);
                r.addProperty("capped", oprCapped);
                r.addProperty("failed", oprFailed);
                o.add("opr", r);
            } else {
                o.add("opr", com.google.gson.JsonNull.INSTANCE);
            }
            if (tWhy != null) {
                o.addProperty("t_why", tWhy);
            }
            if (tForge != null) {
                o.add("t_forge", tForge);
            }
            if (oprForge != null) {
                o.add("opr_forge", oprForge);
            }
            if (worlds != null) {
                o.add("worlds", pairsJson(worlds));
            }
            if (trueWorlds != null) {
                o.add("true_worlds", pairsJson(trueWorlds));
            }
            return o;
        }

        /** [v_s, v_o, terminal] per world (terminal null for a leaf). */
        static JsonArray pairsJson(final double[][] p) {
            final JsonArray a = new JsonArray();
            for (int w = 0; w < p[0].length; w++) {
                final JsonArray x = new JsonArray();
                x.add(Result.num(p[0][w]));
                x.add(Result.num(p[1][w]));
                x.add(Result.num(p[2][w]));
                a.add(x);
            }
            return a;
        }
    }

    /** One position's search value. */
    public static final class Result {
        /**
         * How the decision ended: searched, plan (a turn-plan was played), proven_line, or why nothing was searched
         * (stack, uncontested, trivial, copies_capped, score_failed, reeval_failed, unsupported-hidden-exile, error, ...).
         */
        public String why = "none";
        /** The look-ahead's outcome (kept, departed, loop-guard, capped, ...), or null. */
        public String outcome;
        /** The row's search value, P(win) in [0, 1] (NaN = none): the kept candidate's mean, or a departure's fresh one. */
        public double v = Double.NaN;
        /** The played candidate's mean in the decision's own worlds (the selected, biased value when it departed). */
        public double vSelected = Double.NaN;
        /** A departure's value in K fresh worlds (NaN = no departure). */
        public double vFresh = Double.NaN;
        /** The best searched candidate's value (NaN = none). */
        public double vMax = Double.NaN;
        public boolean departed;
        public int searched, given, worlds, failed, cards;
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
        /**
         * OPEN-6 ruling (2026-10-24): the face-down exiled cards the seat may not look at on the copy
         * ({@link HiddenExile#scan}); non-empty = no target for this row, proof included. Null = none.
         */
        public JsonArray hiddenExile;
        /** bvw-step2-fork-1027 (S2T blocks only; null otherwise): the variants, re-plays and pairs of this row. */
        public S2t s2t;

        /** A usable value target. */
        public boolean hasTarget() {
            if (s2t != null) {
                // S2T: the spec variant's value (a departure whose fresh belief worlds failed keeps its T-sym value)
                return !leafFallback && (provenLine || ("searched".equals(why) || "plan".equals(why)
                        || "reeval_failed".equals(why)) && !Double.isNaN(s2t.target));
            }
            return !leafFallback && (provenLine || ("searched".equals(why) || "plan".equals(why)) && !Double.isNaN(v));
        }

        /** The row's search target value: the spec variant's (S2T) or the search's value (step 1). */
        public double targetValue() {
            return s2t != null ? s2t.target : v;
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("why", why);
            if (outcome != null) o.addProperty("outcome", outcome);
            o.add("v", num(v));
            o.add("v_selected", num(vSelected));
            o.add("v_fresh", num(vFresh));
            o.add("v_max", num(vMax));
            o.addProperty("departed", departed);
            o.addProperty("searched", searched);
            o.addProperty("given", given);
            o.addProperty("worlds", worlds);
            o.addProperty("failed", failed);
            o.addProperty("cards", cards);
            o.addProperty("proven_line", provenLine);
            o.addProperty("proven_win", provenWin);
            o.addProperty("proven_loss", provenLoss);
            o.addProperty("leaf_fallback", leafFallback);
            if (ev != null) o.add("ev", ev);
            o.addProperty("ms", Math.round(ms * 10) / 10.0);
            if (error != null) o.addProperty("error", error);
            if (hiddenExile != null) o.add("hidden_exile", hiddenExile);
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

    /** The fresh worlds' salt of a departure's re-evaluation (never one of the decision's own world seeds). */
    static final long FRESH_SALT = 0x4652455348574CL;
    /** bvw-step2-fork-1027 (S2T): the T re-play's and OPR's world salts (fixed salts of the position's search seed). */
    static final long TRUE_SALT = 0x5452554557524CL, OPR_SALT = 0x4F50525245464CL;

    /**
     * The search value of {@code liveP}'s position in {@code live} (its priority, the replay about to ask its
     * controller). {@code liveKnow} is the replay's seat-knowledge tracker (read only: forked). Never throws, except
     * {@link PinError} (a pinned identity the service does not match, or no service), which must stop the run.
     */
    public Result value(final Game live, final Player liveP, final RlKnowledge liveKnow, final long uid, final long key) {
        return value(live, liveP, liveKnow, uid, key, false, false);
    }

    /**
     * As above; S2T ({@link Spec#s2t}): {@code oprWanted} = this row may be the seat's OPR row (the labeller's: the
     * seat has none yet in this game), {@code proofTarget} = the proof check already gives this row a target. OPR then
     * runs here when the row has a target and a searched candidate. Both are ignored by a step-1 block.
     */
    public Result value(final Game live, final Player liveP, final RlKnowledge liveKnow, final long uid, final long key,
            final boolean oprWanted, final boolean proofTarget) {
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
            res.cards = ForcedWinSolver.cardCount(c);
            // OPEN-6 ruling: a face-down exile the seat cannot see keeps its real identity in every world (the redraw
            // covers hand and library only): no search and no target here, read on the copy from the seat's view
            final JsonArray hidden = HiddenExile.scan(c, cp);
            if (spec.s2t()) {
                // step-3 hygiene (review #123 recheck): an opponent's face-down permanent we cannot see keeps its real
                // identity in every belief world, as a face-down exile does: OPEN-6's ruling applies
                hidden.addAll(HiddenExile.scanFaceDownPermanents(c, cp));
            }
            if (hidden.size() > 0) {
                res.why = HiddenExile.TARGET_SOURCE;
                res.hiddenExile = hidden;
                return res;
            }
            if ((long) res.cards * spec.worlds * spec.maxCands > spec.maxCopyCards) {
                // review #11: the look-ahead would hold cards x worlds x candidates in copies at once
                res.why = "copies_capped";
                return res;
            }
            final int seat = c.getRegisteredPlayers().indexOf(cp);
            final RlSearch.Config cfg = spec.config();
            search = new RlSearch(cfg, seedOf(uid, key), uid, index, liveKnow, jarSha, actorId, obs);
            // review #9: the pins first, fatal on a mismatch or no service
            final JsonObject ack;
            try {
                ack = search.connectAck();
            } catch (IOException | RuntimeException e) {
                throw new PinError("refused: the step-1 search service (" + cfg.server + ") is unusable: " + e);
            }
            checkBackend(ack);
            res.backend = ack == null || !ack.has("value_backend") ? null : ack.get("value_backend");
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
            final Object[] got = new Object[10];
            // bvw-step2-fork-1027 (S2T): the leaf pairs (always with the symmetric leaf; a smoke run logs them anyway)
            final boolean s2t = spec.s2t();
            final boolean pairs = s2t && (cfg.leafSym || spec.smoke);
            if (s2t && spec.smoke) {
                search.setLeafPairs(true);
            }
            final long seed0 = seedOf(uid, key);
            search.tap = (why, st, r, given, ans) -> {
                if (got[0] != null) {
                    return;
                }
                got[0] = why;
                got[1] = st;
                got[2] = r;
                got[3] = given;
                got[4] = ans;
                // ruling 4: a departure (another candidate or a plan) is re-played in K fresh worlds, here, before the
                // copy handles the answer (the copy is still the decision's position)
                if (r != null && r.givenIndex.length > 0 && r.chosen != 0) {
                    // review #123 S3-2: one match rule (the unique searched candidate of the chosen index, else none)
                    final int j = searchedIndex(r, r.chosen);
                    final int kt = st != null ? st.knownTop : ps.knownTop(c);
                    if (j >= 0 && pairs) {
                        // S2T: the same fresh worlds (same seeds, same leaves), with their leaf pairs
                        final LookaheadSearch.Replay fr = s0.replayCandidate(c, cp, j, RlSearch.splitmix(seedOf(uid, key)
                                ^ FRESH_SALT), kt, spec.worlds, LookaheadSearch.WORLD_BELIEF, 0, 0);
                        got[6] = fr;
                        got[5] = fr.why == null ? fr.values : null;
                    } else {
                        got[5] = j < 0 ? null : s0.reevaluate(c, cp, j, RlSearch.splitmix(seedOf(uid, key) ^ FRESH_SALT), kt);
                    }
                    if (got[5] == null) {
                        got[5] = "failed";
                    }
                }
                if (s2t && r != null && r.givenIndex.length > 0) {
                    // bvw-step2-fork-1027 (S2T): after the belief-world search and its fresh re-evaluation are fixed,
                    // the chosen candidate's T re-play (true worlds) and, for the seat's OPR row, its roll-outs
                    final int jj = searchedIndex(r, Math.max(0, r.chosen));
                    if (jj >= 0) {
                        got[9] = jj;
                        final int kt = st != null ? st.knownTop : ps.knownTop(c);
                        if (spec.trueK > 0) {
                            got[7] = s0.replayCandidate(c, cp, jj, RlSearch.splitmix(seed0 ^ TRUE_SALT), kt, spec.trueK,
                                    LookaheadSearch.WORLD_TRUE, 0, 0);
                        }
                        if (oprWanted && spec.oprK > 0 && !r.leafFallback
                                && (proofTarget || !Double.isNaN(variantValue(r, jj, got, cfg.leafSym)))) {
                            got[8] = s0.replayCandidate(c, cp, jj, RlSearch.splitmix(seed0 ^ OPR_SALT), kt, spec.oprK,
                                    LookaheadSearch.WORLD_OPR, spec.oprTurns, spec.oprSteps);
                        }
                    }
                }
            };
            LookaheadSearch.givePriorityTo(c.getPhaseHandler(), cp);
            try {
                bc.chooseSpellAbilityToPlay();
            } catch (PinError e) {
                throw e;
            } catch (RuntimeException | StackOverflowError e) {
                // the copy's own handling of the chosen answer (targets, costs) after the search: the search's result,
                // when the tap has it, stands; an error before it is an error row
                if (got[0] == null) {
                    throw e;
                }
                res.error = "after the search: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if ("score_failed".equals(got[0])) {
                // the pins held at connect: a SCORE failing now is the service failing mid-run (review #9: fatal)
                throw new PinError("refused: the step-1 search service failed a SCORE (" + cfg.server + ")");
            }
            fill(res, got);
            if (s2t) {
                fillS2t(res, got, cfg.leafSym);
            }
        } catch (PinError e) {
            throw e;
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

    /** The service must serve the pinned value backend (or none for a control block): {@link PinError} otherwise. */
    private void checkBackend(final JsonObject ack) {
        if (ack == null) {
            throw new PinError("refused: the step-1 search service sent no HELLO_ACK");
        }
        final JsonElement vb = ack.get("value_backend");
        final String got = vb != null && vb.isJsonObject() && vb.getAsJsonObject().has("value_sha")
                ? vb.getAsJsonObject().get("value_sha").getAsString() : null;
        if (spec.valueSha == null ? got != null : !spec.valueSha.equals(got)) {
            throw new PinError("refused: the search service's value backend is " + got + ", the spec pins "
                    + (spec.valueSha == null ? "none (control)" : spec.valueSha));
        }
    }

    /**
     * S2T, in the tap (before the result is filled): the spec variant's value of the chosen candidate {@code jj}, NaN =
     * none: T-sym = its T re-play's mean, H-* = its value in the decision's worlds (kept) or its fresh worlds (departed).
     */
    private double variantValue(final LookaheadSearch.GivenResult r, final int jj, final Object[] got, final boolean leafSym) {
        if ("T-sym".equals(spec.variant)) {
            final LookaheadSearch.Replay t = (LookaheadSearch.Replay) got[7];
            return t == null || t.why != null ? Double.NaN : leafSym ? mean(t.values) : meanSym(t.pairs);
        }
        if (r.chosen == 0) {
            return jj < r.ev.length ? r.ev[jj] : Double.NaN;
        }
        return got[5] instanceof double[] ? mean((double[]) got[5]) : Double.NaN;
    }

    /**
     * Review #123 S3-2: the searched candidate whose given index is {@code chosen}, or -1 when none or more than one is
     * (S-t's macros share their base's given index; a step-1 or S2T block has none, so the match is unique there).
     */
    static int searchedIndex(final LookaheadSearch.GivenResult r, final int chosen) {
        int j = -1;
        for (int x = 0; x < r.givenIndex.length; x++) {
            if (r.givenIndex[x] == chosen) {
                if (j >= 0) {
                    return -1;
                }
                j = x;
            }
        }
        return j;
    }

    static double mean(final double[] v) {
        if (v == null || v.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    /** The symmetric mean (v_s + 1 - v_o) / 2 over worlds of {v_s, v_o, terminal} pairs, NaN when any is missing. */
    static double meanSym(final double[][] p) {
        if (p == null || p[0].length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (int w = 0; w < p[0].length; w++) {
            s += (p[0][w] + 1.0 - p[1][w]) / 2.0;
        }
        return s / p[0].length;
    }

    static double clamp01(final double v) {
        return Double.isNaN(v) ? Double.NaN : Math.max(0.0, Math.min(1.0, v));
    }

    /** One candidate's {v_s, v_o, terminal} per world out of the search's pairs (null = none). */
    static double[][] column(final double[][][] pairs, final int c) {
        if (pairs == null || c >= pairs[0].length) {
            return null;
        }
        return new double[][] {pairs[0][c].clone(), pairs[1][c].clone(), pairs[2][c].clone()};
    }

    /**
     * bvw-step2-fork-1027 (S2T): the row's variants. The search's own value is the native H variant (H-sym with the
     * symmetric leaf, H-asym without); the other H variant comes from the same worlds' leaf pairs; T-sym from the T
     * re-play (its own leaves when symmetric, else its pairs). The spec variant's value is the row's search target.
     * OPR: the mean of the scored roll-outs (a roll-out at its cap is counted, not scored).
     */
    private void fillS2t(final Result res, final Object[] got, final boolean leafSym) {
        final S2t s = new S2t();
        res.s2t = s;
        final LookaheadSearch.GivenResult r = got[0] == null ? null : (LookaheadSearch.GivenResult) got[2];
        if (got[9] == null || r == null || res.leafFallback) {
            return;
        }
        final int jj = (Integer) got[9];
        s.computed = true;
        double[][] hp = null;
        if (!res.departed) {
            hp = column(r.pairs, jj);
        } else if (got[6] != null && got[5] instanceof double[]) {
            hp = ((LookaheadSearch.Replay) got[6]).pairs;
        }
        if (leafSym) {
            s.hSym = res.v;
            s.hAsym = hp == null ? Double.NaN : clamp01(mean(hp[0]));
        } else {
            s.hAsym = res.v;
            s.hSym = hp == null ? Double.NaN : clamp01(meanSym(hp));
        }
        final LookaheadSearch.Replay t = (LookaheadSearch.Replay) got[7];
        if (t == null && spec.trueK > 0) {
            s.tWhy = "not_run"; // review #123 S3-6: the tap stopped before the T re-play
        }
        if (t != null) {
            s.tForge = t.forge;
            if (t.why != null) {
                s.tWhy = t.why;
            } else {
                s.tSym = clamp01(leafSym ? mean(t.values) : meanSym(t.pairs));
                if (spec.smoke) {
                    s.trueWorlds = t.pairs;
                }
            }
        }
        if (spec.smoke) {
            s.worlds = hp;
        }
        s.target = "T-sym".equals(spec.variant) ? s.tSym : "H-sym".equals(spec.variant) ? s.hSym : s.hAsym;
        final LookaheadSearch.Replay o = (LookaheadSearch.Replay) got[8];
        if (o != null) {
            s.oprRan = true;
            s.oprForge = o.forge;
            s.oprFailed = o.failed;
            s.oprCapped = o.capped;
            double sum = 0;
            int n = 0;
            if (o.why == null && o.values != null) {
                for (double v : o.values) {
                    if (!Double.isNaN(v)) {
                        sum += v;
                        n++;
                    }
                }
            }
            s.oprScored = n;
            s.oprV = n == 0 ? Double.NaN : sum / n;
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
            res.vSelected = Math.max(0.0, Math.min(1.0, r.ev[played]));
            res.provenWin = played < r.provenWin.length && r.provenWin[played];
            res.provenLoss = played < r.provenLoss.length && r.provenLoss[played];
        }
        res.departed = r.chosen != 0;
        if (!res.departed) {
            res.v = res.vSelected;
        } else if (got[5] instanceof double[]) {
            final double[] fresh = (double[]) got[5];
            double s = 0;
            for (double x : fresh) {
                s += x;
            }
            res.vFresh = Math.max(0.0, Math.min(1.0, s / fresh.length));
            res.v = res.vFresh;
        } else {
            res.why = "reeval_failed"; // a departure whose fresh worlds failed: no target
            res.v = Double.NaN;
        }
        if (res.leafFallback) {
            res.v = Double.NaN;
        }
    }
}
