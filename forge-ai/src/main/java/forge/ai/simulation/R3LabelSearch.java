package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import forge.ai.AiCache;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.CacHook;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.CubeComboPlayerController;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * r3-labelsearch-1030 (E1; DESIGN-R3 §N1-N5b and §3, PREDECLARATION-R3-step1 §3-4): the R3 step-1 label search of ONE
 * row. Off unless a label spec carries an {@code r3} block ({@link Spec#parse}); nothing here is constructed otherwise.
 *
 * <p><b>Rows.</b> A priority row ({@link #priorityRow}): candidates = the generator's recorded move (candidate 0),
 * pass, every costed-draw action (a spell or ability whose chain draws), each distinct land play and the policy's top
 * entries ({@link Env#policyTop}), deduplicated by action and capped. A choice row ({@link #choiceRow}, a
 * {@link ChooseACard.RowSearch}): Forge's answer (candidate 0) plus the {@code m} best of choose-a-card's ranked answers.
 *
 * <p><b>Selection and target</b> (N3, winner's curse): every candidate is played in {@code selectWorlds} belief worlds
 * with common seeds (their hidden cards re-drawn from the belief, our unknown library slots shuffled below the seat's
 * known positions); argmax of the resolved means, ties to the lower index, depart only by margin 0. The chosen
 * candidate's target is then its mean over independent TRUE worlds (FLAG-R3-3): hands kept, both libraries shuffled
 * below their known positions, our first unknown draw stratified over the unknown cards (N5b: W = min(U, targetWorlds),
 * world w gets the card at floor((w + 1/2) U / W) of the unknown cards sorted by (name, id)). A costed-draw pick's
 * target is the expectation over our next draws ({@link DrawExpectation#draws}: exact name sequences to drawDepth,
 * weight = probability). No target when more than {@code maxUnresolvedShare} of the target worlds are unresolved.
 *
 * <p><b>Lines.</b> From the candidate's first action (or a choice answer's settled copy) both seats are the
 * generator policy ({@code profile} + {@code solverSpec}: Forge AI plus the honest solver). Inside the line our seat's
 * hooked asks of the root turn are answered by choose-a-card ({@code inline}, mode on, valued by {@link Env#ranker}).
 * Leaf (FLAG-R3-2, step 1): the end of the game (1 / 0 / 0.5), or the proof check ({@link Env#proof}) at the first
 * empty-stack main-phase or end-step priority of each later turn (win 1, loss 0), else continue; a line past
 * {@code maxTurns} global turns or {@code maxSteps} main-loop steps is capped (unresolved, logged). Leaf {@code value}
 * (later steps and stub tests): v from {@link Env#ranker} at our first empty-stack priority {@code horizon} turns on.
 *
 * <p><b>oppMin</b> (labeller v2 (d) in label mode): at our spells and abilities of the root turn the opponent's
 * responses are labeller v2's response set ({@link ForcedWinSolver.AdversaryAi#responses}), each a branch played to
 * the leaf; a world's value is the MIN over its explored branches. A node cap, a truncated response set or a response
 * point past the depth cap makes a world whose observed min is above 0 unresolved (caps count as not won).
 *
 * <p><b>Budgets</b> (N4): counts only. No decision reads a clock; ms are logged. <b>Logs</b>: every candidate's world
 * values, ends and mean, the pick, the target worlds (first draw or draw sequence, weight, value, end), the oppMin
 * tree, the in-line choose-a-card decisions and the work counters ({@link Result#toJson}).
 *
 * <p><b>Composability</b> (lane E2: in-line payment, trigger order, sacrifice, cost cards): {@link LineHook}s are told
 * every line seat as it is installed, with the root turn, and may install their own hooks on it.
 */
public final class R3LabelSearch {

    // ------------------------------------------------------------------------------------------------ spec

    /** The {@code r3} block of a label spec. Every key is a count; any wall or CPU clock key is refused. */
    public static final class Spec {
        public int maxCands = 10;
        public int policyTop = 4;
        public int selectWorlds = 8;
        public int targetWorlds = 16;
        public boolean stratify = true;
        /** Choice rows: answers played out beyond Forge's (m). */
        public int topAnswers = 6;
        /** Choose-a-card keys for choice-row ranking and in-line answers ({@link ChooseACard.Config#fromJson}, no mode). */
        public JsonObject cac = defaultCac();
        public boolean inline = true;
        /** "rollout" (step 1) or "value". */
        public String leaf = "rollout";
        public int maxTurns = 8;
        public int maxSteps = 40000;
        /** value leaf: turns ahead of the root (0 = the line's first empty-stack priority). */
        public int horizon = 2;
        public boolean proofLeaf = true;
        /** The roll-out seats' AI profile and solver spec (FORCED_WIN_SPEC; null = the profile's own). */
        public String profile = AiProfileUtil.HONEST_FORCED_WIN_COUNTED_PROFILE;
        public String solverSpec = AiProfileUtil.HONEST_FORCED_WIN_COUNTED_SPEC + ",labellerV2=true";
        public int drawDepth = 2, seqCap = 64, samples = 32;
        public boolean oppMin = true;
        /** "target" (the T worlds only) or "both" (selection too). */
        public String oppMinIn = "target";
        public int oppResponses = 4, oppDepth = 2, oppNodes = 64;
        public double maxUnresolvedShare = 0.25;
        public long seed = 1030L;
        /** Tests only (never a JSON key): allow a stub ranker, no proof check and no belief re-draw. */
        public boolean testUnsafe = false;
        /** Tests only (never a JSON key): the opponent's own play at a response point is a pass (labeller v2's passive). */
        public boolean testPassiveOpp = false;
        /** The block as given (sorted), for the identity. */
        JsonObject json = new JsonObject();

        static JsonObject defaultCac() {
            final JsonObject o = new JsonObject();
            o.addProperty("cap", 48);
            o.addProperty("topM", 3);
            o.addProperty("settleSteps", 200);
            o.addProperty("beamWidth", 8);
            o.addProperty("beamBranch", 6);
            o.addProperty("shuffleWorlds", 4);
            return o;
        }

        static final Set<String> KEYS = new TreeSet<>(Arrays.asList("maxCands", "policyTop", "selectWorlds",
                "targetWorlds", "stratify", "topAnswers", "cac", "inline", "leaf", "maxTurns", "maxSteps", "horizon",
                "proofLeaf", "profile", "solverSpec", "drawDepth", "seqCap", "samples", "oppMin", "oppMinIn",
                "oppResponses", "oppDepth", "oppNodes", "maxUnresolvedShare", "seed"));

        public static Spec parse(final JsonObject o) {
            final Spec s = new Spec();
            for (String k : o.keySet()) {
                if (k.toLowerCase(java.util.Locale.ROOT).endsWith("ms") || k.toLowerCase(java.util.Locale.ROOT).contains("wall")
                        || k.toLowerCase(java.util.Locale.ROOT).contains("clock")) {
                    throw new IllegalArgumentException("refused: r3." + k + ": the R3 label search reads no clock (N4)");
                }
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException("r3: unknown key " + k);
                }
            }
            if (o.has("maxCands")) s.maxCands = o.get("maxCands").getAsInt();
            if (o.has("policyTop")) s.policyTop = o.get("policyTop").getAsInt();
            if (o.has("selectWorlds")) s.selectWorlds = o.get("selectWorlds").getAsInt();
            if (o.has("targetWorlds")) s.targetWorlds = o.get("targetWorlds").getAsInt();
            if (o.has("stratify")) s.stratify = o.get("stratify").getAsBoolean();
            if (o.has("topAnswers")) s.topAnswers = o.get("topAnswers").getAsInt();
            if (o.has("cac")) {
                final JsonObject c = o.getAsJsonObject("cac").deepCopy();
                if (c.has("mode")) {
                    throw new IllegalArgumentException("r3.cac: no mode (choice rows rank in shadow, lines answer on)");
                }
                for (String k : c.keySet()) {
                    if (k.toLowerCase(java.util.Locale.ROOT).endsWith("ms")) {
                        throw new IllegalArgumentException("refused: r3.cac." + k + " (N4)");
                    }
                }
                ChooseACard.Config.fromJson(c); // refuse a malformed block now
                s.cac = c;
            }
            if (o.has("inline")) s.inline = o.get("inline").getAsBoolean();
            if (o.has("leaf")) s.leaf = o.get("leaf").getAsString();
            if (o.has("maxTurns")) s.maxTurns = o.get("maxTurns").getAsInt();
            if (o.has("maxSteps")) s.maxSteps = o.get("maxSteps").getAsInt();
            if (o.has("horizon")) s.horizon = o.get("horizon").getAsInt();
            if (o.has("proofLeaf")) s.proofLeaf = o.get("proofLeaf").getAsBoolean();
            if (o.has("profile")) s.profile = o.get("profile").getAsString();
            if (o.has("solverSpec")) s.solverSpec = o.get("solverSpec").isJsonNull() ? null : o.get("solverSpec").getAsString();
            if (o.has("drawDepth")) s.drawDepth = o.get("drawDepth").getAsInt();
            if (o.has("seqCap")) s.seqCap = o.get("seqCap").getAsInt();
            if (o.has("samples")) s.samples = o.get("samples").getAsInt();
            if (o.has("oppMin")) s.oppMin = o.get("oppMin").getAsBoolean();
            if (o.has("oppMinIn")) s.oppMinIn = o.get("oppMinIn").getAsString();
            if (o.has("oppResponses")) s.oppResponses = o.get("oppResponses").getAsInt();
            if (o.has("oppDepth")) s.oppDepth = o.get("oppDepth").getAsInt();
            if (o.has("oppNodes")) s.oppNodes = o.get("oppNodes").getAsInt();
            if (o.has("maxUnresolvedShare")) s.maxUnresolvedShare = o.get("maxUnresolvedShare").getAsDouble();
            if (o.has("seed")) s.seed = o.get("seed").getAsLong();
            if (!"rollout".equals(s.leaf) && !"value".equals(s.leaf)) {
                throw new IllegalArgumentException("r3.leaf is \"rollout\" or \"value\", not " + s.leaf);
            }
            if (!"target".equals(s.oppMinIn) && !"both".equals(s.oppMinIn)) {
                throw new IllegalArgumentException("r3.oppMinIn is \"target\" or \"both\"");
            }
            if (s.maxCands < 2 || s.selectWorlds < 1 || s.targetWorlds < 1 || s.topAnswers < 1 || s.maxTurns < 1
                    || s.maxSteps < 1 || s.horizon < 0 || s.drawDepth < 1 || s.seqCap < 1 || s.samples < 1
                    || s.oppResponses < 0 || s.oppDepth < 1 || s.oppNodes < 1 || s.policyTop < 0
                    || !(s.maxUnresolvedShare >= 0 && s.maxUnresolvedShare <= 1)) {
                throw new IllegalArgumentException("r3: counts out of range");
            }
            s.json = sorted(o);
            return s;
        }

        /** The identity JSON: the block with every default made explicit (sorted keys). */
        public JsonObject identity() {
            final JsonObject o = new JsonObject();
            o.addProperty("schema", "mtgx-bvw-r3-labelsearch/1");
            o.addProperty("maxCands", maxCands);
            o.addProperty("policyTop", policyTop);
            o.addProperty("selectWorlds", selectWorlds);
            o.addProperty("targetWorlds", targetWorlds);
            o.addProperty("stratify", stratify);
            o.addProperty("topAnswers", topAnswers);
            o.add("cac", sorted(cac));
            o.addProperty("inline", inline);
            o.addProperty("leaf", leaf);
            o.addProperty("maxTurns", maxTurns);
            o.addProperty("maxSteps", maxSteps);
            o.addProperty("horizon", horizon);
            o.addProperty("proofLeaf", proofLeaf);
            o.addProperty("profile", profile);
            o.add("solverSpec", solverSpec == null ? JsonNull.INSTANCE : new JsonPrimitive(solverSpec));
            o.addProperty("drawDepth", drawDepth);
            o.addProperty("seqCap", seqCap);
            o.addProperty("samples", samples);
            o.addProperty("oppMin", oppMin);
            o.addProperty("oppMinIn", oppMinIn);
            o.addProperty("oppResponses", oppResponses);
            o.addProperty("oppDepth", oppDepth);
            o.addProperty("oppNodes", oppNodes);
            o.addProperty("maxUnresolvedShare", maxUnresolvedShare);
            o.addProperty("seed", seed);
            return sorted(o);
        }

        public String sha() {
            return sha256(identity().toString());
        }

        /** Choose-a-card's config for this spec in {@code mode} (shadow: choice-row ranking; on: in-line answers). */
        ChooseACard.Config cacConfig(final String mode) {
            final JsonObject c = cac.deepCopy();
            c.addProperty("mode", mode);
            final ChooseACard.Config cc = ChooseACard.Config.fromJson(c);
            cc.testUnsafe = testUnsafe;
            return cc;
        }
    }

    static JsonObject sorted(final JsonObject o) {
        final JsonObject out = new JsonObject();
        for (String k : new TreeSet<>(o.keySet())) {
            final JsonElement e = o.get(k);
            out.add(k, e.isJsonObject() ? sorted(e.getAsJsonObject()) : e.deepCopy());
        }
        return out;
    }

    static String sha256(final String s) {
        try {
            final byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------ host

    /** What the host supplies (the labeller: the value service, the proof spec, the belief, the seats' memories). */
    public interface Env {
        /** The ranker (the arm's start tower at step 1; v_{k-1} later): choice-row ranking, in-line answers, value leaves. */
        ChooseACard.Valuer ranker();

        /** The proof check (labeller v2), or null: no proof leaf and no solver promotion (tests only). */
        HonestLabel.Seat proof();

        /** The belief re-draw of the opponents' hidden cards, or null (tests only). */
        ForcedWinSolver.Redraw redraw();

        /** {@code p}'s own library card ids (in the live game) whose positions {@code p} knows. */
        Set<Integer> known(Player p);

        /** The policy's top {@code k} entries of {@code me}'s menu on the exact copy {@code copy} (none by default). */
        default List<SpellAbility> policyTop(final Game copy, final Player me, final int k) {
            return Collections.emptyList();
        }
    }

    /** E2's seam: told every line seat as it is installed (both seats, every line), with the line's root turn. */
    public interface LineHook {
        void onSeat(Game line, Player seat, PlayerControllerAi ctrl, boolean searching, int rootTurn);
    }

    private final Spec spec;
    private final Env env;
    private final List<LineHook> lineHooks = new ArrayList<>();

    /** The proof spec's belief re-draw (uniform over its pool): the selection worlds' and the in-line answers'. */
    public static ForcedWinSolver.Redraw beliefRedraw(final HonestLabel.Spec label) {
        final ForcedWinSolver.Redraw r = new LookaheadSearch(label.beliefConfig()).opponentRedraw();
        if (r == null) {
            throw new IllegalStateException("refused: the R3 label search has no belief re-draw (no belief cube)");
        }
        return r;
    }

    /**
     * REPORT ONLY (cost runs before the tower is pinned; never a training label): the static evaluator as the ranker,
     * behind a wrapper so the in-line answers run (choose-a-card refuses the bare static valuer in mode on).
     */
    public static ChooseACard.Valuer staticRanker() {
        return new ChooseACard.Valuer() {
            @Override
            public Object onCopy(final Game copy, final Player me) {
                return StaticValuer.INSTANCE.onCopy(copy, me);
            }

            @Override
            public Object capture(final Object ctx, final Game g, final Player me) {
                return StaticValuer.INSTANCE.capture(ctx, g, me);
            }

            @Override
            public double[] values(final List<Object> payloads) {
                return StaticValuer.INSTANCE.values(payloads);
            }
        };
    }

    public R3LabelSearch(final Spec spec, final Env env) {
        this.spec = spec;
        this.env = env;
        if (!spec.testUnsafe) {
            if (env.redraw() == null || env.proof() == null || env.ranker() == null) {
                throw new IllegalStateException("r3 refused: the label search needs the belief re-draw, the proof check "
                        + "and the ranker");
            }
        }
    }

    public Spec spec() {
        return spec;
    }

    public void addLineHook(final LineHook h) {
        lineHooks.add(h);
    }

    // ------------------------------------------------------------------------------------------------ results

    /** Work counters of one row (counts only). */
    static final class Work {
        long lines, steps, copies, proofs, proofWins, proofLosses, inlineAsks, inlineDeparted, oppNodes, oppCuts,
                leafValues;

        JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("lines", lines);
            o.addProperty("steps", steps);
            o.addProperty("copies", copies);
            o.addProperty("proofs", proofs);
            o.addProperty("proof_wins", proofWins);
            o.addProperty("proof_losses", proofLosses);
            o.addProperty("inline_asks", inlineAsks);
            o.addProperty("inline_departed", inlineDeparted);
            o.addProperty("opp_min_nodes", oppNodes);
            o.addProperty("opp_min_cuts", oppCuts);
            o.addProperty("leaf_values", leafValues);
            return o;
        }
    }

    /** One line's end. */
    static final class LineOut {
        double v = Double.NaN;
        /** terminal | proof:win | proof:loss | value | cap:turns | cap:steps | failed:<why>. */
        String end = "failed:none";
        int turns, steps;
        final List<Integer> sizes = new ArrayList<>();
        /** Response points met past the scripted depth (root turn). */
        int pointsMet;

        boolean resolved() {
            return !Double.isNaN(v);
        }

        boolean capped() {
            return end.startsWith("cap:");
        }
    }

    /** One world's value (the MIN over its oppMin branches when oppMin is on). */
    static final class WorldOut {
        double v = Double.NaN;
        String end;
        int branches = 1;
        String minWhy;
        String draw;
        double weight = Double.NaN;

        JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.add("v", num(v));
            o.addProperty("end", end);
            if (branches > 1) o.addProperty("branches", branches);
            if (minWhy != null) o.addProperty("min", minWhy);
            if (draw != null) o.addProperty("draw", draw);
            if (!Double.isNaN(weight)) o.add("w", num(weight));
            return o;
        }
    }

    /** One candidate's selection worlds. */
    static final class CandOut {
        final String desc, src;
        String key;
        double rank = Double.NaN;
        final List<WorldOut> worlds = new ArrayList<>();
        double mean = Double.NaN;
        int resolved, capped, failed;

        CandOut(final String desc, final String src) {
            this.desc = desc;
            this.src = src;
        }

        void close() {
            double s = 0;
            for (WorldOut w : worlds) {
                if (!Double.isNaN(w.v)) {
                    s += w.v;
                    resolved++;
                } else if (w.end != null && (w.end.startsWith("cap:") || w.end.startsWith("min-cap"))) {
                    capped++;
                } else {
                    failed++;
                }
            }
            mean = resolved == 0 ? Double.NaN : s / resolved;
        }
    }

    /** One row's search: candidates, pick, target and its worlds, work. */
    public static final class Result {
        public String row;
        public String why = "searched";
        public String kind;
        public double target = Double.NaN;
        public int pick = -1;
        public boolean departed;
        public String targetKind;
        public int targetResolved, targetCapped, targetFailed;
        final List<CandOut> cands = new ArrayList<>();
        final List<WorldOut> targetWorlds = new ArrayList<>();
        final Work work = new Work();
        JsonObject extra = new JsonObject();
        public double ms;
        public String error;

        public boolean hasTarget() {
            return !Double.isNaN(target);
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("row", row);
            o.addProperty("why", why);
            if (kind != null) o.addProperty("kind", kind);
            final JsonArray ca = new JsonArray();
            for (int i = 0; i < cands.size(); i++) {
                final CandOut c = cands.get(i);
                final JsonObject x = new JsonObject();
                x.addProperty("i", i);
                x.addProperty("desc", c.desc);
                x.addProperty("src", c.src);
                if (c.key != null) x.addProperty("key", c.key);
                if (!Double.isNaN(c.rank)) x.add("rank", num(c.rank));
                x.add("mean", num(c.mean));
                x.addProperty("resolved", c.resolved);
                x.addProperty("capped", c.capped);
                x.addProperty("failed", c.failed);
                final JsonArray wa = new JsonArray();
                for (WorldOut w : c.worlds) wa.add(w.toJson());
                x.add("worlds", wa);
                ca.add(x);
            }
            o.add("cands", ca);
            o.addProperty("pick", pick);
            o.addProperty("departed", departed);
            o.add("target", num(target));
            if (targetKind != null) o.addProperty("target_kind", targetKind);
            o.addProperty("target_resolved", targetResolved);
            o.addProperty("target_capped", targetCapped);
            o.addProperty("target_failed", targetFailed);
            final JsonArray ta = new JsonArray();
            for (WorldOut w : targetWorlds) ta.add(w.toJson());
            o.add("target_worlds", ta);
            o.add("work", work.toJson());
            for (Map.Entry<String, JsonElement> e : extra.entrySet()) o.add(e.getKey(), e.getValue());
            if (error != null) o.addProperty("error", error);
            o.addProperty("ms", Math.round(ms * 10) / 10.0);
            return o;
        }
    }

    static JsonElement num(final double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? JsonNull.INSTANCE : new JsonPrimitive(Math.round(v * 1e6) / 1e6);
    }

    // ------------------------------------------------------------------------------------------------ worlds

    /** A world's builder: one per (row, world, kind); it also keeps the copy's own-library memory of our seat. */
    final class World implements ChooseACard.WorldMaker {
        final boolean truth;
        final long seed;
        /** Stratification: world index and count (-1 = none); a draw sequence (null = none). */
        final int w, W;
        final List<String> seq;
        final DrawExpectation.Draws draws;
        final Set<Integer> knownMe, knownOpp;
        OwnLibraryMemory memory;
        String firstDraw;

        World(final boolean truth, final long seed, final int w, final int W, final List<String> seq,
                final DrawExpectation.Draws draws, final Set<Integer> knownMe, final Set<Integer> knownOpp) {
            this.truth = truth;
            this.seed = seed;
            this.w = w;
            this.W = W;
            this.seq = seq;
            this.draws = draws;
            this.knownMe = knownMe;
            this.knownOpp = knownOpp;
        }

        @Override
        public void make(final Game live, final Player liveMe, final Game g, final Player me) {
            if (truth) {
                // FLAG-R3-3: the real position, hands kept; every library shuffled below its known positions
                for (Player o : me.getOpponents()) {
                    OwnLibraryMemory.shuffleUnknown(o, knownOpp, new Random(LookaheadSearch.mix(seed, 0x4F50L)));
                }
                if (seq != null) {
                    DrawExpectation.arrange(me, seq, draws.unknownById, draws.unknownSlots,
                            new Random(LookaheadSearch.mix(seed, 0x5345L)));
                    firstDraw = String.valueOf(seq);
                } else {
                    OwnLibraryMemory.shuffleUnknown(me, knownMe, new Random(LookaheadSearch.mix(seed, 0x4D45L)));
                }
            } else {
                // belief: their hidden cards from our belief; our unknown slots shuffled below the known positions
                final ForcedWinSolver.Redraw rd = env.redraw();
                if (rd != null) {
                    rd.apply(live, liveMe, g, me, new Random(LookaheadSearch.mix(seed, 0x5244L)));
                }
                OwnLibraryMemory.shuffleUnknown(me, knownMe, new Random(LookaheadSearch.mix(seed, 0x4D45L)));
            }
            memory = new OwnLibraryMemory(me);
            memory.noteLooked(cardsOf(me, knownMe));
            memory.watch(g);
        }

        /** At the line's start (a priority copy: right after make; a choice copy: after the answer settled). */
        void atStart(final Player me) {
            if (truth && seq == null && spec.stratify && W > 0) {
                firstDraw = stratify(me, memory.known(me), w, W, new Random(LookaheadSearch.mix(seed, 0x5354L)));
            }
        }
    }

    static List<Card> cardsOf(final Player p, final Set<Integer> ids) {
        final List<Card> out = new ArrayList<>();
        if (ids == null) return out;
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            if (ids.contains(c.getId())) out.add(c);
        }
        return out;
    }

    /**
     * N5b: shuffle {@code me}'s unknown library slots ({@code known} stay), then put the stratified card of world
     * {@code w} of {@code W} in the first unknown slot: the unknown cards sorted by (name, id), index floor((w + 1/2) U / W).
     * Returns its name (null: no unknown card).
     */
    public static String stratify(final Player me, final Set<Integer> known, final int w, final int W, final Random rng) {
        OwnLibraryMemory.shuffleUnknown(me, known, rng);
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final List<Integer> slots = new ArrayList<>();
        final List<Card> unknown = new ArrayList<>();
        for (int i = 0; i < lib.size(); i++) {
            if (known == null || !known.contains(lib.get(i).getId())) {
                slots.add(i);
                unknown.add(lib.get(i));
            }
        }
        if (unknown.isEmpty()) {
            return null;
        }
        final List<Card> sortedU = new ArrayList<>(unknown);
        sortedU.sort((a, b) -> {
            final int c = a.getName().compareTo(b.getName());
            return c != 0 ? c : Integer.compare(a.getId(), b.getId());
        });
        final int U = sortedU.size();
        final Card pick = sortedU.get(Math.min(U - 1, (int) Math.floor((w + 0.5) * U / W)));
        final int first = slots.get(0);
        final int at = lib.indexOf(pick);
        final Card was = lib.get(first);
        lib.set(first, pick);
        lib.set(at, was);
        me.getZone(ZoneType.Library).setCards(lib);
        return pick.getName();
    }

    /** The target worlds' count: W = min(U, targetWorlds) with stratification (U = our unknown library cards), else all. */
    int targetCount(final Player liveMe, final Set<Integer> known) {
        if (!spec.stratify) {
            return spec.targetWorlds;
        }
        int u = 0;
        for (Card c : liveMe.getCardsIn(ZoneType.Library)) {
            if (known == null || !known.contains(c.getId())) u++;
        }
        return u == 0 ? spec.targetWorlds : Math.min(u, spec.targetWorlds);
    }

    // ------------------------------------------------------------------------------------------------ lines

    /** The shared state of one line (both seats' controllers read it). */
    final class LineState {
        final Game g;
        final Player me;
        final int rootTurn;
        final LineOut out;
        final Work work;
        final long seed;
        /** Priority candidates: the first action (host id, description, land; null host = pass), not yet played. */
        PCand first;
        boolean firstDone;
        final String rootPhase;
        final int rootStack;
        /** oppMin: the script per response point (null = no MIN) and the passive test opponent. */
        int[] script;
        int point = 0;
        final Set<Integer> seenPoints = new HashSet<>();
        int lastProofTurn;
        boolean stop;

        LineState(final Game g, final Player me, final int rootTurn, final LineOut out, final Work work, final long seed) {
            this.g = g;
            this.me = me;
            this.rootTurn = rootTurn;
            this.out = out;
            this.work = work;
            this.seed = seed;
            this.lastProofTurn = rootTurn;
            this.rootPhase = String.valueOf(g.getPhaseHandler().getPhase());
            this.rootStack = g.getStack().size();
        }

        /** Every seat's priority decision in the line: the leaf checks, our first action, the opponent's MIN script. */
        List<SpellAbility> choose(final PlayerControllerAi c, final java.util.function.Supplier<List<SpellAbility>> own) {
            if (stop) {
                return null;
            }
            final PhaseHandler ph = g.getPhaseHandler();
            final Player p = c.getPlayer();
            // the leaf: a proof check at the first empty-stack main / end priority of each later turn
            if ("rollout".equals(spec.leaf) && spec.proofLeaf && env.proof() != null && ph.getTurn() > lastProofTurn
                    && g.getStack().isEmpty() && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2)
                    || ph.is(PhaseType.END_OF_TURN))) {
                lastProofTurn = ph.getTurn();
                final HonestLabel.Result r = isolated(LookaheadSearch.mix(seed, 0x50524FL + ph.getTurn()),
                        () -> env.proof().label(g, me, LookaheadSearch.mix(seed, 0x50524F0000L + ph.getTurn())));
                work.proofs++;
                if ("win".equals(r.label) || "loss".equals(r.label)) {
                    out.v = "win".equals(r.label) ? 1.0 : 0.0;
                    out.end = "proof:" + r.label;
                    if ("win".equals(r.label)) work.proofWins++;
                    else work.proofLosses++;
                    stop = true;
                    return null;
                }
            }
            if ("value".equals(spec.leaf) && p == me && g.getStack().isEmpty() && ph.getTurn() >= rootTurn + spec.horizon
                    && (spec.horizon == 0 || ph.isPlayerTurn(me)) && (firstDone || first == null)) {
                out.v = valueLeaf(g, me, work);
                out.end = Double.isNaN(out.v) ? "failed:value" : "value";
                stop = true;
                return null;
            }
            if (p == me && first != null && !firstDone) {
                // a priority candidate's first action, at the root priority only
                if (ph.getTurn() == rootTurn && String.valueOf(ph.getPhase()).equals(rootPhase)
                        && g.getStack().size() == rootStack) {
                    firstDone = true;
                    if (first.pass) {
                        return null;
                    }
                    final SpellAbility sa = first.locate(g, me);
                    if (sa == null) {
                        out.end = "failed:first_not_in_copy";
                        stop = true;
                        return null;
                    }
                    return new ArrayList<>(Collections.singletonList(sa));
                }
                out.end = "failed:first_window";
                stop = true;
                return null;
            }
            if (script != null && p != me && ph.getTurn() == rootTurn && !g.getStack().isEmpty()) {
                final SpellAbilityStackInstance si = g.getStack().peek();
                final SpellAbility top = si.getSpellAbility();
                if (top != null && top.getActivatingPlayer() == me && !top.isManaAbility() && !top.isTrigger()
                        && (top.isSpell() || top.isActivatedAbility()) && seenPoints.add(si.getId())) {
                    final int pt = point++;
                    if (pt >= spec.oppDepth) {
                        out.pointsMet++;
                        return own.get(); // past the depth cap: the generator's own play (counted: the world is capped)
                    }
                    final List<SpellAbility> rs = responses(g, p, me, top);
                    out.sizes.add(rs.size());
                    final int choice = pt < script.length ? script[pt] : 0;
                    if (choice == 0) {
                        return spec.testPassiveOpp ? null : own.get();
                    }
                    if (choice == 1 || choice - 2 >= Math.min(rs.size(), spec.oppResponses)) {
                        return null;
                    }
                    return new ArrayList<>(Collections.singletonList(rs.get(choice - 2)));
                }
            }
            if (spec.testPassiveOpp && p != me && !g.getStack().isEmpty() && g.getStack().peekAbility() != null
                    && g.getStack().peekAbility().getActivatingPlayer() == me) {
                return null;
            }
            return own.get();
        }
    }

    /** The opponent's response set to our {@code top} (labeller v2 (d)'s, uncapped here: the caller caps and counts). */
    static List<SpellAbility> responses(final Game g, final Player opp, final Player me, final SpellAbility top) {
        final ForcedWinSolver.AdversaryAi a = new ForcedWinSolver.AdversaryAi(g, opp, opp.getController().getLobbyPlayer(),
                me, -1);
        a.maxResponses = Integer.MAX_VALUE;
        return a.responses(top);
    }

    /** A line seat on Forge AI alone (a profile without the cube combo plans). */
    static final class PlainSeat extends LookaheadSearch.RolloutAi {
        LineState st;

        PlainSeat(final Game g, final Player p, final forge.LobbyPlayer lp) {
            super(g, p, lp);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return st.choose(this, super::chooseSpellAbilityToPlay);
        }
    }

    /** A line seat on the generator policy (Forge AI plus the honest solver: the cube combo control). */
    static final class ComboSeat extends CubeComboPlayerController {
        LineState st;

        ComboSeat(final Game g, final Player p, final forge.LobbyPlayer lp) {
            super(g, p, lp);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return st.choose(this, super::chooseSpellAbilityToPlay);
        }
    }

    /** In-line choose-a-card, only during the root turn (DESIGN-R3 N1: our seat's asks that turn). */
    static final class TurnGate implements CacHook {
        final ChooseACard c;
        final Game g;
        final int turn;

        TurnGate(final ChooseACard c, final Game g, final int turn) {
            this.c = c;
            this.g = g;
            this.turn = turn;
        }

        boolean on() {
            return g.getPhaseHandler().getTurn() == turn;
        }

        @Override
        public void beforeAsk(final PlayerControllerAi pc) {
            if (on()) c.beforeAsk(pc);
        }

        @Override
        public Card pick(final PlayerControllerAi pc, final ZoneType d, final List<ZoneType> o, final SpellAbility sa,
                final CardCollection f, final boolean opt, final Player dec, final Card def) {
            return on() ? c.pick(pc, d, o, sa, f, opt, dec, def) : def;
        }

        @Override
        public CardCollectionView order(final PlayerControllerAi pc, final CardCollectionView cards, final ZoneType d,
                final SpellAbility sa, final CardCollectionView def) {
            return on() ? c.order(pc, cards, d, sa, def) : def;
        }

        @Override
        public boolean confirm(final PlayerControllerAi pc, final SpellAbility sa, final PlayerActionConfirmMode m,
                final String msg, final boolean def) {
            return on() ? c.confirm(pc, sa, m, msg, def) : def;
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> scry(final PlayerControllerAi pc,
                final CardCollection t, final org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> def) {
            return on() ? c.scry(pc, t, def) : def;
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> surveil(final PlayerControllerAi pc,
                final CardCollection t, final org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> def) {
            return on() ? c.surveil(pc, t, def) : def;
        }

        @Override
        public CardCollection discard(final PlayerControllerAi pc, final Player p, final SpellAbility sa,
                final CardCollection v, final int min, final int max, final CardCollection def) {
            return on() ? c.discard(pc, p, sa, v, min, max, def) : def;
        }

        @Override
        public List<SpellAbility> priority(final PlayerControllerAi pc, final List<SpellAbility> def) {
            return on() ? c.priority(pc, def) : def;
        }

        @Override
        public List<Card> dig(final PlayerControllerAi pc, final List<Card> o, final int min, final int max,
                final SpellAbility sa, final List<Card> def) {
            return on() ? c.dig(pc, o, min, max, sa, def) : def;
        }

        @Override
        public CardCollectionView returnCost(final PlayerControllerAi pc, final SpellAbility sa, final String t,
                final int n, final CardCollectionView def) {
            return on() ? c.returnCost(pc, sa, t, n, def) : def;
        }
    }

    private LobbyPlayerAi lineLobby() {
        final LobbyPlayerAi lp = new LobbyPlayerAi("r3-line", null);
        lp.setAiProfile(spec.profile);
        return lp;
    }

    /**
     * Play one line on {@code g} (a world copy, inside its own scopes) to its leaf. {@code first}: a priority
     * candidate's first action (null: a choice row's copy, already past its answer); {@code script}: the oppMin script.
     */
    LineOut line(final Game g, final Player me, final int rootTurn, final PCand first, final int[] script,
            final World world, final Work work, final long seed) {
        final LineOut out = new LineOut();
        final LineState st = new LineState(g, me, rootTurn, out, work, seed);
        st.first = first;
        st.script = script;
        work.lines++;
        try {
            if (world != null) {
                world.atStart(me);
            }
            final boolean combo = "true".equals(AiProfileUtil.getAIProp(lineLobby(), AiProps.CUBE_COMBO_PLANS));
            for (Player p : g.getPlayers()) {
                final LobbyPlayerAi lp = lineLobby();
                final PlayerControllerAi ctrl;
                if (combo) {
                    final ComboSeat s = new ComboSeat(g, p, lp);
                    s.st = st;
                    if (spec.solverSpec != null) {
                        s.cubeCombo().presetForcedWinSpec(spec.solverSpec);
                    }
                    ctrl = s;
                } else {
                    final PlainSeat s = new PlainSeat(g, p, lp);
                    s.st = st;
                    ctrl = s;
                }
                p.dangerouslySetController(ctrl);
                if (p == me && spec.inline) {
                    installInline(g, me, ctrl, rootTurn, world, work);
                }
                for (LineHook h : lineHooks) {
                    h.onSeat(g, p, ctrl, p == me, rootTurn);
                }
            }
            if (first != null) {
                LookaheadSearch.givePriorityTo(g.getPhaseHandler(), me);
            }
            if ("value".equals(spec.leaf) && spec.horizon == 0 && first == null) {
                out.v = valueLeaf(g, me, work);
                out.end = Double.isNaN(out.v) ? "failed:value" : "value";
                return out;
            }
            final PhaseHandler ph = g.getPhaseHandler();
            int steps = 0;
            while (!g.isGameOver() && !st.stop) {
                if (ph.getTurn() > rootTurn + spec.maxTurns) {
                    out.end = "cap:turns";
                    break;
                }
                if (steps >= spec.maxSteps) {
                    out.end = "cap:steps";
                    break;
                }
                ph.mainLoopStep();
                steps++;
            }
            out.steps = steps;
            out.turns = ph.getTurn() - rootTurn;
            work.steps += steps;
            if (g.isGameOver() || !me.isInGame()) {
                double t = me.hasWon() ? 1.0 : me.hasLost() || !me.isInGame() ? 0.0 : 0.5;
                for (Player o : me.getOpponents()) {
                    if (o.hasLost() && !me.hasLost()) t = 1.0;
                }
                out.v = t;
                out.end = "terminal";
            }
            return out;
        } catch (RuntimeException | StackOverflowError e) {
            out.v = Double.NaN;
            out.end = "failed:" + e.getClass().getSimpleName();
            if (work.lines <= 2) {
                System.err.println("[r3] line failed: " + e);
            }
            return out;
        }
    }

    private void installInline(final Game g, final Player me, final PlayerControllerAi ctrl, final int rootTurn,
            final World world, final Work work) {
        final ChooseACard c = new ChooseACard(spec.cacConfig("on"), env.ranker(),
                env.proof() == null ? null : ChooseACard.honest(env.proof()));
        c.setRedraw(env.redraw());
        c.install(ctrl);
        if (world != null && world.memory != null && c.memory() != null) {
            c.memory().noteLooked(cardsOf(me, world.memory.known(me)));
        }
        ctrl.setCacHook(new TurnGate(c, g, rootTurn));
        inlineEngines.add(c);
    }

    /** The in-line engines of the row in progress (their counts go to the row's work). */
    private final List<ChooseACard> inlineEngines = new ArrayList<>();

    private void drainInline(final Work work, final JsonObject extra) {
        final Map<String, Long> sum = new TreeMap<>();
        for (ChooseACard c : inlineEngines) {
            for (Map.Entry<String, Long> e : c.counts.entrySet()) sum.merge(e.getKey(), e.getValue(), Long::sum);
        }
        inlineEngines.clear();
        work.inlineAsks += sum.getOrDefault("kept", 0L) + sum.getOrDefault("departed", 0L) + sum.getOrDefault("single", 0L);
        work.inlineDeparted += sum.getOrDefault("departed", 0L);
        if (!sum.isEmpty()) {
            final JsonObject o = extra.has("inline") ? extra.getAsJsonObject("inline") : new JsonObject();
            for (Map.Entry<String, Long> e : sum.entrySet()) {
                o.addProperty(e.getKey(), (o.has(e.getKey()) ? o.get(e.getKey()).getAsLong() : 0L) + e.getValue());
            }
            extra.add("inline", o);
        }
    }

    private double valueLeaf(final Game g, final Player me, final Work work) {
        final ChooseACard.Valuer v = env.ranker();
        final Object ctx = v.onCopy(g, me);
        final double[] r = v.values(Collections.singletonList(v.capture(ctx, g, me)));
        work.leafValues++;
        return r == null || r.length != 1 ? Double.NaN : Math.max(0.0, Math.min(1.0, r[0]));
    }

    /** Run {@code f} under its own random stream, id scope and AI caches (the line's are put back after). */
    static <T> T isolated(final long seed, final java.util.function.Supplier<T> f) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(seed));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            return f.get();
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /**
     * One world of a candidate: the MIN over its oppMin branches when {@code min} (each branch the same world, the same
     * seeds), else the plain line. {@code play} runs one branch (its script) and returns its line.
     */
    WorldOut world(final boolean min, final Work work, final java.util.function.Function<int[], LineOut> play) {
        final WorldOut wo = new WorldOut();
        if (!min) {
            final LineOut l = play.apply(null);
            wo.v = l.v;
            wo.end = l.end;
            return wo;
        }
        // labeller v2 (d)'s breadth-first script tree: 0 = the generator's own play, 1 = pass, k >= 2 = response k-2
        final java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        queue.add(new int[0]);
        double lo = Double.POSITIVE_INFINITY;
        String loEnd = null, loWhy = null, cap = null;
        int nodes = 0;
        while (!queue.isEmpty()) {
            final int[] script = queue.poll();
            if (nodes >= spec.oppNodes) {
                work.oppCuts++;
                cap = "nodes";
                break;
            }
            nodes++;
            work.oppNodes++;
            final LineOut l = play.apply(script);
            if (!l.resolved()) {
                // an unresolved branch (capped or failed): the world's min is not known
                cap = cap == null ? "branch:" + l.end : cap;
                continue;
            }
            if (l.v < lo) {
                lo = l.v;
                loEnd = l.end;
                loWhy = Arrays.toString(script);
            }
            if (lo == 0.0) {
                break; // the floor: no branch can go lower (a cap no longer matters)
            }
            if (l.pointsMet > 0) {
                cap = cap == null ? "depth" : cap;
            }
            final int point = script.length;
            if (point < spec.oppDepth && l.sizes.size() > point) {
                final int size = l.sizes.get(point);
                if (size > spec.oppResponses) {
                    cap = cap == null ? "truncated" : cap;
                }
                final int choices = 1 + Math.min(spec.oppResponses, size);
                for (int c = 1; c <= choices; c++) {
                    final int[] child = Arrays.copyOf(script, point + 1);
                    child[point] = c;
                    queue.add(child);
                }
            }
        }
        wo.branches = nodes;
        if (lo == 0.0) {
            wo.v = 0.0;
            wo.end = loEnd;
            wo.minWhy = loWhy;
        } else if (cap != null || lo == Double.POSITIVE_INFINITY) {
            wo.v = Double.NaN; // caps count as not won: the world is unresolved
            wo.end = "min-cap:" + (cap == null ? "none" : cap);
            wo.minWhy = loWhy;
        } else {
            wo.v = lo;
            wo.end = loEnd;
            wo.minWhy = loWhy;
        }
        return wo;
    }

    // ------------------------------------------------------------------------------------------------ priority rows

    /** A priority candidate: an action by its host card id and description (null host: pass). */
    public static final class PCand {
        final boolean pass;
        final int hostId;
        final String desc;
        final boolean land;
        final String label, src;
        final boolean draws;

        PCand(final boolean pass, final int hostId, final String desc, final boolean land, final String label,
                final String src, final boolean draws) {
            this.pass = pass;
            this.hostId = hostId;
            this.desc = desc;
            this.land = land;
            this.label = label;
            this.src = src;
            this.draws = draws;
        }

        String key() {
            return pass ? "pass" : hostId + "|" + land + "|" + desc;
        }

        static PCand pass(final String src) {
            return new PCand(true, -1, "", false, "pass", src, false);
        }

        static PCand of(final SpellAbility sa, final String src) {
            final Card h = sa.getHostCard();
            return new PCand(false, h == null ? -1 : h.getId(), sa.getDescription(), sa.isLandAbility(),
                    (h == null ? "?" : h.getName()) + ": " + sa.getDescription(), src, drawsCards(sa));
        }

        /** This action in a copy (ids survive copies), or null. */
        SpellAbility locate(final Game g, final Player p) {
            final Card host = g.findById(hostId);
            if (host == null) return null;
            List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(new CardCollection(host), p);
            all = ComputerUtilAbility.getOriginalAndAltCostAbilities(all, p);
            for (SpellAbility sa : all) {
                if (sa.isLandAbility() == land && desc.equals(sa.getDescription())) {
                    sa.setActivatingPlayer(p);
                    return sa;
                }
            }
            return null;
        }
    }

    /** A costed-draw action: its ability chain draws cards (The One Ring, Necro, Night's Whisper, ...). */
    static boolean drawsCards(final SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.getApi() == ApiType.Draw) return true;
        }
        return false;
    }

    /**
     * The candidates of {@code liveMe}'s priority in {@code live} (read on an exact copy: nothing on the live game moves),
     * {@code recorded} the generator's move (null or empty = pass): recorded first, then pass, the costed draws, each
     * distinct land play, the policy's top; deduplicated by action, at most maxCands.
     */
    public List<PCand> candidates(final Game live, final Player liveMe, final List<SpellAbility> recorded) {
        final Map<String, PCand> out = new LinkedHashMap<>();
        final PCand rec = recorded == null || recorded.isEmpty() || recorded.get(0) == null ? PCand.pass("recorded")
                : PCand.of(recorded.get(0), "recorded");
        out.put(rec.key(), rec);
        out.putIfAbsent("pass", PCand.pass("pass"));
        isolated(LookaheadSearch.mix(spec.seed, 0x43414E44L), () -> {
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
            }
            final List<PCand> draws = new ArrayList<>(), lands = new ArrayList<>();
            final Set<String> landNames = new TreeSet<>();
            List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(ComputerUtilAbility.getAvailableCards(g, me), me);
            all = ComputerUtilAbility.getOriginalAndAltCostAbilities(all, me);
            for (SpellAbility sa : all) {
                if (sa.isManaAbility()) continue;
                sa.setActivatingPlayer(me);
                try {
                    if (sa.isLandAbility()) {
                        if (sa.getHostCard() != null && landNames.add(sa.getHostCard().getName()) && sa.canPlay()) {
                            lands.add(PCand.of(sa, "land"));
                        }
                        continue;
                    }
                    if (drawsCards(sa) && sa.canPlay() && ComputerUtilCost.canPayCost(sa, me, false)) {
                        draws.add(PCand.of(sa, "draw"));
                    }
                } catch (RuntimeException e) {
                    // an ability Forge cannot test here is not a candidate
                }
            }
            for (PCand c : draws) out.putIfAbsent(c.key(), c);
            for (PCand c : lands) out.putIfAbsent(c.key(), c);
            if (spec.policyTop > 0) {
                for (SpellAbility sa : env.policyTop(g, me, spec.policyTop)) {
                    if (sa != null) {
                        final PCand c = PCand.of(sa, "policy");
                        out.putIfAbsent(c.key(), c);
                    }
                }
            }
            return null;
        });
        final List<PCand> list = new ArrayList<>(out.values());
        return list.size() > spec.maxCands ? new ArrayList<>(list.subList(0, spec.maxCands)) : list;
    }

    /** Whether the menu has any action besides passing (a "contested" priority). */
    public static boolean contested(final List<PCand> cands) {
        for (PCand c : cands) {
            if (!c.pass) return true;
        }
        return false;
    }

    /** Whether a costed draw is among the candidates. */
    public static boolean hasDraw(final List<PCand> cands) {
        for (PCand c : cands) {
            if (c.draws) return true;
        }
        return false;
    }

    /**
     * The R3 search of {@code liveMe}'s priority row (the live game at that priority, before anything is played).
     * Never moves the live game. {@code key}: the row's key (seeds every world).
     */
    public Result priorityRow(final Game live, final Player liveMe, final List<PCand> cands, final long key) {
        final long t0 = System.nanoTime();
        final Result res = new Result();
        res.row = "priority";
        try {
            final int rootTurn = live.getPhaseHandler().getTurn();
            final Set<Integer> knownMe = env.known(liveMe);
            final Player liveOpp = liveMe.getOpponents().isEmpty() ? null : liveMe.getOpponents().get(0);
            final Set<Integer> knownOpp = liveOpp == null ? new HashSet<>() : env.known(liveOpp);
            final long seed0 = LookaheadSearch.mix(spec.seed, key);
            final boolean minSel = spec.oppMin && "both".equals(spec.oppMinIn);
            for (PCand c : cands) {
                final CandOut co = new CandOut(c.label, c.src);
                co.key = c.key();
                res.cands.add(co);
            }
            for (int w = 0; w < spec.selectWorlds; w++) {
                final long ws = LookaheadSearch.mix(seed0, 0x53454C0000L + w); // common across candidates
                for (int i = 0; i < cands.size(); i++) {
                    final PCand c = cands.get(i);
                    res.cands.get(i).worlds.add(world(minSel, res.work,
                            script -> priorityLine(live, liveMe, c, new World(false, ws, -1, 0, null, null, knownMe,
                                    knownOpp), ws, script, res.work)));
                }
            }
            for (CandOut co : res.cands) co.close();
            res.pick = argmax(res.cands);
            res.departed = res.pick > 0;
            if (res.pick < 0) {
                res.why = "no_resolved_candidate";
                return res;
            }
            final PCand pc = cands.get(res.pick);
            final long ts = LookaheadSearch.mix(seed0, 0x5452554557L);
            if (pc.draws) {
                // a costed draw: the expectation over our next draws (exact name sequences, weight = probability)
                final DrawExpectation.Draws dr = DrawExpectation.draws(liveMe, knownMe, spec.drawDepth, spec.seqCap,
                        spec.samples, ts);
                res.targetKind = dr.exact ? "draws:exact" : "draws:sampled";
                final JsonObject de = new JsonObject();
                de.addProperty("sequences", dr.seqs.size());
                de.addProperty("depth", dr.depth);
                de.addProperty("unknown", dr.unknown);
                de.addProperty("known", dr.known);
                de.addProperty("exact", dr.exact);
                res.extra.add("draws", de);
                for (int s = 0; s < dr.seqs.size(); s++) {
                    final long ws = LookaheadSearch.mix(ts, 0x5345510000L + s);
                    final World wd = new World(true, ws, -1, 0, dr.seqs.get(s), dr, knownMe, knownOpp);
                    final WorldOut wo = world(spec.oppMin, res.work, script -> priorityLine(live, liveMe, pc, wd, ws,
                            script, res.work));
                    wo.draw = String.valueOf(dr.seqs.get(s));
                    wo.weight = dr.weights.get(s);
                    res.targetWorlds.add(wo);
                }
            } else {
                final int W = targetCount(liveMe, knownMe);
                res.targetKind = spec.stratify ? "stratified" : "true";
                for (int w = 0; w < W; w++) {
                    final long ws = LookaheadSearch.mix(ts, 0x5457000000L + w);
                    final World wt = new World(true, ws, w, W, null, null, knownMe, knownOpp);
                    final WorldOut wo = world(spec.oppMin, res.work, script -> priorityLine(live, liveMe, pc, wt, ws,
                            script, res.work));
                    wo.draw = wt.firstDraw;
                    wo.weight = 1.0 / W;
                    res.targetWorlds.add(wo);
                }
            }
            closeTarget(res);
        } catch (RuntimeException | StackOverflowError e) {
            res.why = "error";
            res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            res.target = Double.NaN;
        } finally {
            drainInline(res.work, res.extra);
            res.ms = (System.nanoTime() - t0) / 1e6;
        }
        return res;
    }

    /** One priority line: an isolated copy of the live priority, its world, the candidate first, played to the leaf. */
    private LineOut priorityLine(final Game live, final Player liveMe, final PCand c, final World wm, final long ws,
            final int[] script, final Work work) {
        return isolated(LookaheadSearch.mix(ws, 1), () -> {
            final Game g;
            final Player me;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1);
                if (!live.getStack().isEmpty()) {
                    if (GameCopier.stackUnsupported(live) != null) {
                        final LineOut o = new LineOut();
                        o.end = "failed:stack_unsupported";
                        return o;
                    }
                    copier.setCopyStack(true);
                }
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                work.copies++;
                wm.make(live, liveMe, g, me);
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(ws, 3)));
            return line(g, me, live.getPhaseHandler().getTurn(), c, script, wm, work, ws);
        });
    }

    /** Argmax of the resolved means; ties to the lower index; a departure must be strictly better (margin 0). */
    static int argmax(final List<CandOut> cands) {
        int best = -1;
        for (int i = 0; i < cands.size(); i++) {
            final double m = cands.get(i).mean;
            if (Double.isNaN(m)) continue;
            if (best < 0 || m > cands.get(best).mean) best = i;
        }
        return best;
    }

    /** The target: the (weighted) mean of the resolved target worlds; none above the unresolved share. */
    void closeTarget(final Result res) {
        double s = 0, ws = 0;
        int n = 0;
        for (WorldOut w : res.targetWorlds) {
            if (!Double.isNaN(w.v)) {
                final double wt = Double.isNaN(w.weight) ? 1.0 : w.weight;
                s += wt * w.v;
                ws += wt;
                res.targetResolved++;
                n++;
            } else if (w.end != null && (w.end.startsWith("cap:") || w.end.startsWith("min-cap"))) {
                res.targetCapped++;
            } else {
                res.targetFailed++;
            }
        }
        final int all = res.targetWorlds.size();
        if (n == 0 || all == 0 || (double) (all - n) / all > spec.maxUnresolvedShare + 1e-12) {
            res.target = Double.NaN;
            res.why = n == 0 ? "target_unresolved" : "target_capped_share";
        } else {
            res.target = Math.round(s / ws * 1e6) / 1e6;
        }
    }

    // ------------------------------------------------------------------------------------------------ choice rows

    /**
     * The R3 search of a choice row: choose-a-card ranked the answers ({@code row}); Forge's answer and the m best are
     * played on belief worlds, and the pick's target on true worlds. The live game never moves (every play is a copy).
     */
    public Result choiceRow(final ChooseACard.Row row, final long key) {
        final long t0 = System.nanoTime();
        final Result res = new Result();
        res.row = "choice";
        res.kind = row.kind();
        try {
            final Game live = row.live();
            final Player liveMe = row.me();
            final int rootTurn = live.getPhaseHandler().getTurn();
            final Set<Integer> knownMe = new HashSet<>(row.known());
            knownMe.addAll(env.known(liveMe));
            final Player liveOpp = liveMe.getOpponents().isEmpty() ? null : liveMe.getOpponents().get(0);
            final Set<Integer> knownOpp = liveOpp == null ? new HashSet<>() : env.known(liveOpp);
            final long seed0 = LookaheadSearch.mix(spec.seed, key);
            // Forge's answer and the m best (ranking score, ties to the lower index), dropped answers excluded
            final List<Integer> idx = new ArrayList<>();
            for (int i = 1; i < row.size(); i++) {
                if (row.score(i) != Double.NEGATIVE_INFINITY) idx.add(i);
            }
            idx.sort((a, b) -> {
                final int c = Double.compare(row.score(b), row.score(a));
                return c != 0 ? c : Integer.compare(a, b);
            });
            final List<Integer> played = new ArrayList<>();
            played.add(0);
            for (int i = 0; i < idx.size() && played.size() < spec.topAnswers + 1; i++) played.add(idx.get(i));
            res.extra.addProperty("n_enum", row.nEnum());
            res.extra.addProperty("capped", row.capped());
            res.extra.addProperty("n_ranked", row.size());
            res.extra.addProperty("cac_decision", row.index());
            res.extra.addProperty("src_card", row.src());
            final JsonArray ranked = new JsonArray();
            for (int i = 0; i < row.size(); i++) {
                final JsonObject r = new JsonObject();
                r.addProperty("i", i);
                r.addProperty("desc", row.desc(i));
                r.add("score", num(row.score(i)));
                ranked.add(r);
            }
            res.extra.add("ranked", ranked);
            final boolean minSel = spec.oppMin && "both".equals(spec.oppMinIn);
            for (int i : played) {
                final CandOut co = new CandOut(row.desc(i), i == 0 ? "forge" : "answer");
                co.key = row.key(i);
                co.rank = row.score(i);
                res.cands.add(co);
            }
            for (int w = 0; w < spec.selectWorlds; w++) {
                final long ws = LookaheadSearch.mix(seed0, 0x53454C0000L + w);
                for (int j = 0; j < played.size(); j++) {
                    final int i = played.get(j);
                    res.cands.get(j).worlds.add(world(minSel, res.work, script -> choiceLine(row, i, new World(false, ws,
                            -1, 0, null, null, knownMe, knownOpp), ws, rootTurn, script, res.work)));
                }
            }
            for (CandOut co : res.cands) co.close();
            final int pj = argmax(res.cands);
            res.pick = pj;
            res.departed = pj > 0;
            if (pj < 0) {
                res.why = "no_resolved_candidate";
                return res;
            }
            final int pi = played.get(pj);
            res.extra.addProperty("pick_answer", pi);
            final long ts = LookaheadSearch.mix(seed0, 0x5452554557L);
            final int W = targetCount(liveMe, knownMe);
            res.targetKind = spec.stratify ? "stratified" : "true";
            for (int w = 0; w < W; w++) {
                final long ws = LookaheadSearch.mix(ts, 0x5457000000L + w);
                final World wt = new World(true, ws, w, W, null, null, knownMe, knownOpp);
                final WorldOut wo = world(spec.oppMin, res.work, script -> choiceLine(row, pi, wt, ws, rootTurn, script,
                        res.work));
                wo.draw = wt.firstDraw;
                wo.weight = 1.0 / W;
                res.targetWorlds.add(wo);
            }
            closeTarget(res);
        } catch (RuntimeException | StackOverflowError e) {
            res.why = "error";
            res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            res.target = Double.NaN;
        } finally {
            drainInline(res.work, res.extra);
            res.ms = (System.nanoTime() - t0) / 1e6;
        }
        return res;
    }

    private LineOut choiceLine(final ChooseACard.Row row, final int i, final World wm, final long ws, final int rootTurn,
            final int[] script, final Work work) {
        work.copies++;
        final LineOut l = row.play(i, wm, ws, (g, me) -> line(g, me, rootTurn, null, script, wm, work, ws));
        if (l == null) {
            final LineOut o = new LineOut();
            o.end = "failed:apply";
            return o;
        }
        return l;
    }

    /** A {@link ChooseACard.RowSearch} that runs {@link #choiceRow} and hands each result to {@code sink}. */
    public ChooseACard.RowSearch rowSearch(final java.util.function.ToLongFunction<ChooseACard.Row> keyOf,
            final java.util.function.BiConsumer<ChooseACard.Row, Result> sink) {
        return row -> sink.accept(row, choiceRow(row, keyOf.applyAsLong(row)));
    }

    /** A choice-row engine for {@code seat} of a live (forced-replay) game: shadow, bound, ranking by the ranker. */
    public ChooseACard choiceEngine(final Game live, final Player seat, final ChooseACard.RowSearch rs) {
        final ChooseACard c = new ChooseACard(spec.cacConfig("shadow"), env.ranker(),
                env.proof() == null ? null : ChooseACard.honest(env.proof()));
        c.setRedraw(env.redraw());
        c.bind(live, seat);
        c.setRowSearch(rs);
        return c;
    }
}
