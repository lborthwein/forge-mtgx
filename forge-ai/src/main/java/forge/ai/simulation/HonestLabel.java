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
        /**
         * bvw-step1-build-1024 (false = the default, and then nothing changes, the spec's sha included): each row carries
         * its check's solver work ({@link ForcedWinSolver.Meter}: counter units and the stops each budget caused).
         */
        public boolean meter = false;
        /**
         * bvw-step1-build-1024 (review #3; null = unset, the default, and then nothing changes, the sha included): the
         * Forge AI settings a counter-budget spec runs under, part of its identity. A counter spec must set both
         * ({@code sequentialAi} true, {@code aiTimeoutSec} >= 600) and the runner refuses a JVM or game that differs.
         */
        public Boolean sequentialAi = null;
        public Integer aiTimeoutSec = null;
        /**
         * e1-q8-1101 (DESIGN-R3 ruling 2026-11-01 06:40:01, q^8; false = off, the default, and then nothing changes, the
         * spec's sha included): PROVEN only for lines that read no hidden card of the seat's own library. A proven line
         * that read one or two is labelled by its exact-draw fraction (tag exact-draw: "win" only at 1); a deeper one, or
         * one the enumeration cannot cover, by its belief worlds k/W (tag sampled), never "win". A loss whose proving
         * line read the opponent's hidden library is likewise sampled, never "loss". Which line is played is unchanged.
         */
        public boolean libLabels = false;
        /**
         * known-lib-1102 (GOAL N5b / N10; false = off, the default, and then nothing changes, the spec's sha included):
         * the replay labeller tracks, per seat, which own-library cards the seat knows the position of
         * ({@link KnownOwnLibrary}: a Doomsday pile, a put-back, a Ponder keep, a searched card put on top; a shuffle
         * forgets) and passes them to {@link Seat#label(Game, Player, long, Set)}: every belief world keeps them in place,
         * and the win solver knows its top and bottom (it plans lines that draw and cast them; drawing them is no q^8 read).
         */
        public boolean knownLib = false;
        /**
         * coverage-fix-1102 (false = off, the default, and then nothing changes, the sha included): with {@link #meter},
         * each row's meter also carries why its OppMin worlds were cut (by reason, with up to three answer cards each)
         * and which engine counter stopped its engine searches (ForcedWinSolver.Meter#reasons). Diagnostics only.
         */
        public boolean meterReasons = false;

        static final Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("worlds", "solverCfg", "lossCfg",
                "loss", "redraw", "beliefCube", "beliefCubeSha256", "beliefBasics", "beliefSingleton", "lossWallMs",
                "seed", "meter", "sequentialAi", "aiTimeoutSec", "libLabels", "knownLib", "meterReasons"));

        /** bvw-step1-build-1024: keys the replay config reads beside the spec's own (the label window and outputs). */
        static final Set<String> HOST_KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("turns", "out", "band",
                "search", "reference", "referenceTurns", "r3", "r3Ranker"));

        /** The spec, or IllegalArgumentException ("refused: ...") when it is not honest or not well formed. */
        public static Spec parse(final JsonObject o) {
            if (o == null) {
                throw new IllegalArgumentException("refused: no label spec");
            }
            for (String k : o.keySet()) {
                if (!KEYS.contains(k) && !HOST_KEYS.contains(k)) {
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
            if (o.has("meter")) s.meter = o.get("meter").getAsBoolean();
            if (o.has("sequentialAi") && !o.get("sequentialAi").isJsonNull()) s.sequentialAi = o.get("sequentialAi").getAsBoolean();
            if (o.has("aiTimeoutSec") && !o.get("aiTimeoutSec").isJsonNull()) s.aiTimeoutSec = o.get("aiTimeoutSec").getAsInt();
            if (o.has("libLabels")) s.libLabels = o.get("libLabels").getAsBoolean();
            if (o.has("knownLib")) s.knownLib = o.get("knownLib").getAsBoolean();
            if (o.has("meterReasons")) s.meterReasons = o.get("meterReasons").getAsBoolean();
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
            // bvw-step1-build-1024: budget=counter in both checks or in neither; a counter spec reads no wall clock
            final boolean wc = counter(solverCfg), lc = counter(lossCfg);
            if (wc != lc) {
                throw new IllegalArgumentException("refused: solverCfg and lossCfg must both be budget=counter or both not");
            }
            if (wc && lossWallMs > 0) {
                throw new IllegalArgumentException("refused: a budget=counter label spec needs lossWallMs = 0 (no wall)");
            }
            if (wc && (!Boolean.TRUE.equals(sequentialAi) || aiTimeoutSec == null
                    || aiTimeoutSec < ForcedWinSolver.COUNTER_MIN_AI_TIMEOUT_SEC)) {
                throw new IllegalArgumentException("refused: a budget=counter label spec pins its Forge AI settings: "
                        + "sequentialAi true and aiTimeoutSec >= " + ForcedWinSolver.COUNTER_MIN_AI_TIMEOUT_SEC);
            }
        }

        /**
         * bvw-step1-build-1024 (review #3): null when this JVM and a game with {@code gameAiTimeoutSec} run this spec as
         * pinned; else the refusal (only a counter spec pins).
         */
        public String runtimeRefusal(final int gameAiTimeoutSec) {
            if (!counterBudgets()) {
                return null;
            }
            final String no = ForcedWinSolver.counterRuntimeRefusal(gameAiTimeoutSec);
            if (no != null) {
                return no;
            }
            if (aiTimeoutSec != gameAiTimeoutSec) {
                return "refused: the label spec pins aiTimeoutSec " + aiTimeoutSec + ", the run has " + gameAiTimeoutSec;
            }
            return null;
        }

        /** bvw-step1-build-1024: this solver config object asks for counter budgets. */
        static boolean counter(final JsonObject cfg) {
            final JsonElement b = cfg == null ? null : cfg.get("budget");
            return b != null && "counter".equals(b.getAsString());
        }

        /** bvw-step1-build-1024: both checks run on counter budgets (host-independent labels). */
        public boolean counterBudgets() {
            return counter(solverCfg);
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
            final ForcedWinSolver.Config c = config(o);
            c.libWatch = libLabels; // e1-q8-1101
            return c;
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
            final ForcedWinSolver.Config c = config(o);
            c.libWatch = libLabels; // e1-q8-1101
            return c;
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
            if (meter) {
                o.addProperty("meter", true); // bvw-step1-build-1024: only when on, so every other spec's sha is unchanged
            }
            if (minOn(solverCfg) || minOn(lossCfg)) {
                // review #148 S2-1: the shared budgeted MIN (OppMin) changed what oppMin proves; only specs with oppMin
                // on carry the version, so v1 specs keep their sha and v2 rows from before #148 cannot share one
                o.addProperty("oppMinVersion", OppMin.VERSION);
            }
            if (sequentialAi != null) {
                o.addProperty("sequentialAi", sequentialAi); // review #3: only when set (counter specs set both)
            }
            if (aiTimeoutSec != null) {
                o.addProperty("aiTimeoutSec", aiTimeoutSec);
            }
            if (libLabels) {
                o.addProperty("libLabels", true); // e1-q8-1101: only when on, so every other spec's sha is unchanged
            }
            if (knownLib) {
                o.addProperty("knownLib", true); // known-lib-1102: only when on, so every other spec's sha is unchanged
            }
            if (meterReasons) {
                o.addProperty("meterReasons", true); // coverage-fix-1102: only when on
            }
            return o;
        }

        static boolean minOn(final JsonObject cfg) {
            if (cfg == null) return false;
            for (String k : new String[] {"labellerV2", "oppMin"}) {
                if (cfg.has(k) && "true".equals(cfg.get(k).getAsString())) return true;
            }
            return false;
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
        /**
         * gamecopier-fidelity-1030: what the solver's copies of this position cannot carry (pending delayed triggers or
         * commands; null = nothing left out). A proof that could depend on it is not accepted: the label stays unresolved.
         */
        public String copyGap;
        /** gamecopier-fidelity-1030: proofs refused because of {@link #copyGap}. */
        public int gapRefused;
        /** gamecopier-fidelity-1030: "carried" when the root had pending triggers or commands and the copies carry them all. */
        public String copyPending;
        /** bvw-step1-build-1024: the check's solver work (null unless the spec meters). */
        public JsonObject meter;
        /**
         * rollout-budget-1101: the cap a solver of this check hit ({@link forge.ai.AiWork#CAP}: its Forge AI work), or
         * null; with the solvers (the win check's one, the loss check's per-world ones) that hit it. A capped solver
         * proposes no line, so a capped check proves nothing it did not prove before the cap.
         */
        public String cap;
        public int capHits;
        /**
         * rollout-budget-1101 (review #160 S2-1): loss-check worlds whose solver hit the cap. They are left out of
         * {@link #worldsLoss} and the p_loss denominator (a capped world is unknown, not "safe"), so a check with one
         * is never a "loss" label (that needs every world run and lost).
         */
        public int worldsCapped;
        /**
         * e1-q8-1101 (libLabels only; null otherwise): the proving line read hidden own-library cards: "exact-draw" (the
         * label is its exact fraction over {@link #libSeqs} name sequences) or "sampled" (k/W over {@link #libW} worlds,
         * never proven); {@link #libTakes} = the most hidden cards one play-out read; {@link #libWhy} = why an exact-draw
         * was not possible.
         */
        public String libTag, libWhy;
        public int libW, libTakes, libSeqs;
        /**
         * known-lib-1102 (knownLib only, and only when the seat knows some; -1 otherwise): the root's known own-library
         * cards passed to the label: the known top (a contiguous prefix) and the other known cards (pinned at fixed
         * offsets from the top or the bottom).
         */
        public int knownTop = -1, knownPinned = -1;

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("label", label);
            o.addProperty("check", check);
            o.addProperty("gate", gate);
            add(o, "p_win", pWin);
            add(o, "p_loss", pLoss);
            if ("sampled".equals(libTag) && worldsWin < 0) {
                o.add("worlds_win", com.google.gson.JsonNull.INSTANCE); // follow-up #161: a withheld re-estimate
            } else {
                o.addProperty("worlds_win", worldsWin);
            }
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
            if (meter != null) o.add("meter", meter);
            if (cap != null) {
                o.addProperty("cap", cap);
                o.addProperty("cap_hits", capHits);
                if ("loss".equals(check)) {
                    o.addProperty("worlds_capped", worldsCapped);
                }
            }
            if (copyPending != null) {
                o.addProperty("copy_pending", copyPending);
            }
            if (copyGap != null) {
                o.addProperty("copy_gap", copyGap);
                o.addProperty("gap_refused", gapRefused);
            }
            if (knownTop >= 0) {
                o.addProperty("known_top", knownTop);
                o.addProperty("known_pinned", knownPinned);
            }
            if (libTag != null) {
                o.addProperty("lib_tag", libTag);
                o.addProperty("lib_takes", libTakes >= ForcedWinSolver.HiddenWatch.FAILED ? -1 : libTakes);
                if ("exact-draw".equals(libTag)) {
                    o.addProperty("lib_seqs", libSeqs);
                } else if (libW >= 0) {
                    o.addProperty("lib_w", libW);
                }
                if (libWhy != null) {
                    o.addProperty("lib_why", libWhy);
                }
            }
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
            return label(live, liveMe, key, null);
        }

        /**
         * r2-choose-a-card-1029 (N5b, opt-in): as {@link #label(Game, Player, long)}, keeping {@code knownOwn} (our
         * library card ids whose positions the seat knows: a put-back, a pile, a scry) at their positions in every root
         * world, and telling the win solver they are placed (its pile memory). Null or empty = exactly the plain call.
         * The set is passed down as an argument (no field), so concurrent or nested calls do not share it.
         */
        public Result label(final Game live, final Player liveMe, final long key, final Set<Integer> knownOwn) {
            final Set<Integer> known = knownOwn == null || knownOwn.isEmpty() ? null : knownOwn;
            final long t0 = System.nanoTime();
            final Result r = new Result();
            if (known != null && spec.knownLib) {
                final int[] tb = topBottom(liveMe, known);
                r.knownTop = tb[0];
                r.knownPinned = tb[1];
            }
            try {
                label0(live, liveMe, key, r, known);
            } catch (RuntimeException | StackOverflowError e) {
                r.label = "unresolved";
                r.gate = "error";
                r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            r.ms = (System.nanoTime() - t0) / 1e6;
            return r;
        }

        private void label0(final Game live, final Player liveMe, final long key, final Result r, final Set<Integer> known) {
            final PhaseHandler ph = live.getPhaseHandler();
            if (!live.getStack().isEmpty()) {
                // solver-cap12 round 3 (R3-5, engineStack only): our priority with the opponent's item on top is a win
                // check (a response line); otherwise, as before, no check
                if (spec.winConfig().engineStack && live.getStack().peek().getActivatingPlayer() != liveMe
                        && GameCopier.stackUnsupported(live) == null) {
                    final List<Player> os = new ArrayList<>(liveMe.getOpponents());
                    if (os.size() == 1) {
                        r.check = "win";
                        win(live, liveMe, os.get(0), key, r, known);
                        return;
                    }
                }
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
            if ((mine && main || !mine && end || spec.loss && (!mine && main || mine && end)) && GameCopier.SOLVER_COPY_PENDING) {
                // gamecopier-fidelity-1030: what the copies cannot carry; a gap that can act this turn leaves the
                // position unresolved (no check runs), one that acts only after this turn refuses proofs past it
                final List<String> gaps = GameCopier.unsupportedPending(live);
                if (gaps == null && GameCopier.hasPending(live)) {
                    r.copyPending = "carried";
                    ForcedWinSolver.receipt("FORCED_WIN copy-pending label turn=" + ph.getTurn() + " phase=" + ph.getPhase() + " carried");
                }
                if (gaps != null) {
                    r.copyGap = String.join("; ", gaps);
                    ForcedWinSolver.receipt("FORCED_WIN copy-gap turn=" + ph.getTurn() + " phase=" + ph.getPhase() + " " + r.copyGap);
                    if (!GameCopier.gapsMatterOnlyAfterThisTurn(gaps)) {
                        r.gate = "copy-gap";
                        return;
                    }
                }
            }
            if (mine && main || !mine && end) {
                r.check = "win";
                win(live, liveMe, liveOpp, key, r, known);
            } else if (spec.loss && (!mine && main || mine && end)) {
                r.check = "loss";
                loss(live, liveMe, liveOpp, key, r, known);
            } else {
                r.gate = "phase:" + ph.getPhase();
            }
        }

        /** known-lib-1102: the counts of {@code known} that are the library's prefix (top), and the other known cards. */
        static int[] topBottom(final Player me, final Set<Integer> known) {
            final List<forge.game.card.Card> lib = new ArrayList<>(me.getCardsIn(forge.game.zone.ZoneType.Library));
            int t = 0;
            while (t < lib.size() && known.contains(lib.get(t).getId())) {
                t++;
            }
            int b = 0;
            for (int i = t; i < lib.size(); i++) {
                b += known.contains(lib.get(i).getId()) ? 1 : 0;
            }
            return new int[] {t, b};
        }

        /** An honest world: a copy of the live game, their hidden cards from our belief, our library shuffled. */
        private Object[] world(final Game live, final Player liveMe, final Player liveOpp, final Random rng) {
            return world(live, liveMe, liveOpp, rng, null);
        }

        /** As above, keeping {@code knownOwn} (null = none) at their library positions. */
        private Object[] world(final Game live, final Player liveMe, final Player liveOpp, final Random rng,
                final Set<Integer> knownOwn) {
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING); // gamecopier-fidelity-1030
                copier.setCopyFidelity(1); // storm count, per-turn records (as the solver's own play-outs)
                ForcedWinEngine.copyStackIf(copier, live, spec.winConfig().engineStack); // R3-5
                final Game g = copier.makeCopy();
                final Player me = (Player) copier.find(liveMe);
                final Player opp = (Player) copier.find(liveOpp);
                // their hand + library re-drawn from the pool (visibility read in the LIVE game), our library below its
                // visible top shuffled
                final List<Integer> before = knownOwn == null ? null : OwnLibraryMemory.order(me);
                belief.resampleBelief(live, liveMe, g, me, rng, null);
                if (knownOwn != null) {
                    OwnLibraryMemory.keepKnown(me, before, knownOwn);
                }
                return new Object[] {g, me, opp};
            }
        }

        private void win(final Game live, final Player liveMe, final Player liveOpp, final long key, final Result r,
                final Set<Integer> knownOwn) {
            final Object[] w = world(live, liveMe, liveOpp, new Random(LookaheadSearch.mix(spec.seed, key)), knownOwn);
            final Game root = (Game) w[0];
            final Player me = (Player) w[1];
            final ForcedWinSolver s = new ForcedWinSolver(me, spec.winConfig());
            s.meter.reasons = spec.meterReasons; // coverage-fix-1102 (meter only)
            if (knownOwn != null) {
                final List<forge.game.card.Card> placed = new ArrayList<>();
                for (forge.game.card.Card c : me.getCardsIn(forge.game.zone.ZoneType.Library)) {
                    if (knownOwn.contains(c.getId())) {
                        placed.add(c);
                    }
                }
                s.notePlaced(placed);
                if (spec.knownLib) {
                    // known-lib-1102: every known card beyond the known top stays at its index in the solver's worlds too
                    s.notePinned(placed);
                }
            }
            // every world the solver copies from the root (verification worlds, the engine's root) re-draws their hidden
            // cards again from OUR pool, with visibility from the live game
            s.redraw = (lv, lvMe, g, cm, rng) -> redrawBase.apply(live, liveMe, g, cm, rng);
            if (s.redraw == null || !s.config().hideOwnLibrary) {
                throw new IllegalStateException("refused: dishonest win solver");
            }
            final List<ForcedWinSolver.Proposal> props = s.propose(root);
            if (s.lastCap() != null) {
                r.cap = s.lastCap(); // rollout-budget-1101
                r.capHits++;
            }
            r.proposals = props.size();
            if (spec.meter) {
                r.meter = s.meter.toJson();
            }
            ForcedWinSolver.Proposal best = null, proven = null;
            for (ForcedWinSolver.Proposal p : props) {
                if (best == null || p.pBelief > best.pBelief + 1e-9) {
                    best = p;
                }
                if (p.proven(spec.worlds) && r.copyGap != null && p.endTurn != live.getPhaseHandler().getTurn()) {
                    r.gapRefused++; // gamecopier-fidelity-1030: past this turn the copies lack a pending command
                    continue;
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
            if (proven != null && spec.libLabels && proven.libDependent) {
                // e1-q8-1101 (ruling 2026-11-01 06:40:01): a proven line that read hidden cards of our library
                proven = libLabel(s, root, proven, props, live, r, key);
                if (spec.meter) {
                    r.meter = s.meter.toJson(); // the exact-draw work included
                }
            }
            if (proven == null && spec.knownLib && spec.libLabels && r.libTag == null && r.copyGap == null) {
                // known-lib-1102 (N5b): no line proves it, and our unknown library is a small known set (a Doomsday
                // pile after a fetch): plan per draw, then label the best line by its exact-draw fraction
                proven = drawPlans(s, root, me, r, key);
                if (spec.meter) {
                    r.meter = s.meter.toJson();
                }
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

        /**
         * known-lib-1102 (knownLib with libLabels; N5b's "enumerate the distinct names, weight by count, solve each"):
         * when no line is proven and the unknown part of our library has at most {@link #PLAN_SEQ_CAP} distinct name
         * sequences for its next two draws (a Doomsday pile after a fetch, a tiny library), plan in a copy of the root
         * with each sequence put on the known top (the planner can then cast what it draws), and collect the proven lines.
         * Planning on a sequence is clairvoyant, so a line is never labelled by those plans: each collected line is
         * re-verified in the real root (our library unknown below the known top) and labelled by its exact-draw fraction
         * ({@link ForcedWinSolver#libExact}, the q^8 path: one or two reads). The best fraction is the row's p_win with tag
         * exact-draw (lib_why "per-draw"); "win" only at 1. Returns the proposal still proven, or null.
         */
        private ForcedWinSolver.Proposal drawPlans(final ForcedWinSolver s, final Game root, final Player me, final Result r,
                final long key) {
            final List<forge.game.card.Card> lib = new ArrayList<>(me.getCardsIn(forge.game.zone.ZoneType.Library));
            final int kt = Math.min(s.knownTopCount(root), lib.size());
            final Set<Integer> pins = s.pinnedIn(me);
            final Set<Integer> known = new java.util.HashSet<>(pins);
            for (int i = 0; i < kt; i++) {
                known.add(lib.get(i).getId());
            }
            final DrawExpectation.Draws d = DrawExpectation.draws(me, known, 2, PLAN_SEQ_CAP, 0, LookaheadSearch.mix(spec.seed, key));
            if (!d.exact || d.seqs.isEmpty() || d.depth < 1) {
                return null;
            }
            final List<ForcedWinPlanner.Line> lines = new ArrayList<>();
            final Set<String> seen = new java.util.HashSet<>();
            for (int q = 0; q < d.seqs.size(); q++) {
                final Game g2;
                final Player me2;
                synchronized (root) {
                    final GameCopier copier = new GameCopier(root, true);
                    copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING);
                    copier.setCopyFidelity(1);
                    ForcedWinEngine.copyStackIf(copier, root, spec.winConfig().engineStack);
                    g2 = copier.makeCopy();
                    me2 = (Player) copier.find(me);
                }
                DrawExpectation.arrange(me2, d.seqs.get(q), d.unknownById, d.unknownSlots,
                        new Random(LookaheadSearch.mix(key, 0x504C414EL + q)));
                final ForcedWinSolver s2 = new ForcedWinSolver(me2, spec.winConfig());
                s2.redraw = s.redraw;
                final List<forge.game.card.Card> lib2 = new ArrayList<>(me2.getCardsIn(forge.game.zone.ZoneType.Library));
                // the known cards and the arranged draws are placed (the known top then runs through them); pins stay pinned
                final List<forge.game.card.Card> placed2 = new ArrayList<>();
                for (int i = 0; i < lib2.size(); i++) {
                    final int slot = d.unknownSlots.indexOf(i);
                    if (known.contains(lib2.get(i).getId()) || slot >= 0 && slot < d.depth) {
                        placed2.add(lib2.get(i));
                    }
                }
                s2.notePlaced(placed2);
                s2.notePinned(placed2);
                final List<ForcedWinSolver.Proposal> props2 = s2.propose(g2);
                ForcedWinSolver.receipt("FORCED_WIN per-draw seq=" + d.seqs.get(q) + " knownTop=" + s2.knownTopCount(g2)
                        + " proposals=" + props2.size() + " proven=" + props2.stream().filter(p -> p.proven(spec.worlds)).count());
                for (ForcedWinSolver.Proposal p : props2) {
                    if (p.proven(spec.worlds) && p.line instanceof ForcedWinPlanner.Line && lines.size() < PLAN_LINE_CAP
                            && seen.add(String.valueOf(((ForcedWinPlanner.Line) p.line).steps))) {
                        lines.add((ForcedWinPlanner.Line) p.line);
                    }
                }
                if (s2.lastCap() != null) {
                    r.cap = s2.lastCap();
                    r.capHits++;
                }
            }
            ForcedWinSolver.Proposal best = null;
            ForcedWinSolver.LibExact bestX = null;
            for (ForcedWinPlanner.Line line : lines) {
                line.knownTop = kt; // the seat does not know the draws
                final ForcedWinSolver.Proposal p = new ForcedWinSolver.Proposal(
                        s.evaluateBudgeted(root, line, LookaheadSearch.mix(spec.seed, key ^ 0x44524157L)));
                if (p.proven(spec.worlds) && !p.libDependent) {
                    return p; // a real proof after all
                }
                if (!p.libDependent || p.hiddenTakes < 1 || p.hiddenTakes > 2) {
                    ForcedWinSolver.receipt("FORCED_WIN per-draw skip dep=" + p.libDependent + " takes=" + p.hiddenTakes + " " + p.text);
                    continue;
                }
                final ForcedWinSolver.LibExact x = s.libExact(root, p, LIB_SEQ_CAP, key);
                ForcedWinSolver.receipt("FORCED_WIN per-draw verify takes=" + p.hiddenTakes + " exact=" + x.exact + " fraction="
                        + x.fraction + " why=" + x.why + " " + p.text);
                if (x.exact && (bestX == null || x.fraction > bestX.fraction + 1e-12)) {
                    best = p;
                    bestX = x;
                }
            }
            if (best == null) {
                return null;
            }
            r.libTag = "exact-draw";
            r.libWhy = "per-draw";
            r.libSeqs = bestX.seqs;
            r.libTakes = best.hiddenTakes;
            r.pWin = bestX.fraction;
            r.worldsWin = best.worldsRun;
            return bestX.fraction >= 1.0 - 1e-9 ? best : null;
        }

        /**
         * e1-q8-1101: the label of a proven, library-dependent proposal. A proven proposal that read no hidden card wins
         * as before (the shortest such); else one or two hidden reads get the exact-draw fraction (a win only at 1), and
         * deeper reads or an uncoverable enumeration the sampled k/W. Returns the proposal still proven, or null.
         */
        private ForcedWinSolver.Proposal libLabel(final ForcedWinSolver s, final Game root, final ForcedWinSolver.Proposal dep,
                final List<ForcedWinSolver.Proposal> props, final Game live, final Result r, final long key) {
            ForcedWinSolver.Proposal clean = null;
            for (ForcedWinSolver.Proposal p : props) {
                if (!p.proven(spec.worlds) || p.libDependent || r.copyGap != null && p.endTurn != live.getPhaseHandler().getTurn()) {
                    continue;
                }
                if (clean == null || p.endTurn >= 0 && p.endTurn < clean.endTurn) {
                    clean = p;
                }
            }
            if (clean != null) {
                return clean;
            }
            r.libTakes = dep.hiddenTakes;
            r.worldsWin = dep.worldsRun;
            if (dep.hiddenTakes <= 2) {
                final ForcedWinSolver.LibExact x = s.libExact(root, dep, LIB_SEQ_CAP, key);
                if (x.exact) {
                    r.libTag = "exact-draw";
                    r.libSeqs = x.seqs;
                    r.pWin = x.fraction;
                    return x.fraction >= 1.0 - 1e-9 ? dep : null;
                }
                r.libWhy = x.why;
            }
            // review #161 S2-1: k/W re-estimated on fresh worlds keyed by the decision (the proof's own worlds picked the
            // line, so their k = W says nothing); withheld (null) when the re-estimate was cut
            final ForcedWinSolver.LibExact w = s.libResample(root, dep, LookaheadSearch.mix(key, 0x5A3D1EL));
            r.libTag = "sampled";
            r.libW = Double.isNaN(w.fraction) ? -1 : w.worlds; // re-check S3: withheld = absent, not 0
            r.pWin = w.fraction;
            r.worldsWin = r.libW;
            if (w.why != null) {
                r.libWhy = r.libWhy == null ? w.why : r.libWhy + "+" + w.why;
            }
            return null;
        }

        private void loss(final Game live, final Player liveMe, final Player liveOpp, final long key, final Result r,
                final Set<Integer> knownOwn) {
            final long wall = spec.lossWallMs <= 0 ? Long.MAX_VALUE : System.nanoTime() + spec.lossWallMs * 1_000_000L;
            final ForcedWinSolver.Config cfg = spec.lossConfig();
            int ran = 0, lost = 0, maxTurns = -1;
            final Map<String, Integer> reasons = new TreeMap<>();
            final Map<String, Integer> kinds = new TreeMap<>();
            int steps = -1;
            int libLost = 0, libTakes = 0;
            final ForcedWinSolver.Meter sum = spec.meter ? new ForcedWinSolver.Meter() : null;
            if (sum != null) {
                sum.reasons = spec.meterReasons; // coverage-fix-1102 (meter only)
            }
            boolean wallStop = false;
            for (int j = 0; j < spec.worlds; j++) {
                if (System.nanoTime() > wall) {
                    wallStop = true;
                    break;
                }
                final Object[] w = world(live, liveMe, liveOpp,
                        new Random(LookaheadSearch.mix(spec.seed, key * 131L + 7919L * (j + 1))), knownOwn);
                final Game g = (Game) w[0];
                final Player opp = (Player) w[2];
                // the opponent's solver in this world (their sampled hand is theirs to know); exact: the line is checked
                // in this world as it stands, our real hand countering; no re-draw (the world already holds no real
                // hidden card of theirs), their own library hidden from their engine root (hideOwnLibrary)
                final ForcedWinSolver s = new ForcedWinSolver(opp, TEST_CAP_LOSS_WORLD != null
                        && TEST_CAP_LOSS_WORLD.test(j) ? capOne(spec.lossConfig()) : cfg);
                if (s.redraw != null || !s.config().hideOwnLibrary || !s.config().exact) {
                    throw new IllegalStateException("refused: dishonest loss solver");
                }
                final List<ForcedWinSolver.Proposal> props = s.propose(g);
                if (sum != null) {
                    sum.plus(s.meter);
                }
                if (s.lastCap() != null) {
                    // rollout-budget-1101 (review #160 S2-1): a capped world is not run: out of ran and worlds_loss
                    r.cap = s.lastCap();
                    r.capHits++;
                    r.worldsCapped++;
                    continue;
                }
                ran++;
                ForcedWinSolver.Proposal proven = null;
                for (ForcedWinSolver.Proposal p : props) {
                    if (p.proven(1) && r.copyGap != null && p.endTurn != live.getPhaseHandler().getTurn()) {
                        r.gapRefused++; // gamecopier-fidelity-1030 (as the win check)
                        continue;
                    }
                    if (p.proven(1) && (proven == null || p.endTurn >= 0 && p.endTurn < proven.endTurn)) {
                        proven = p;
                    }
                }
                if (proven != null && spec.libLabels && proven.libDependent) {
                    // review #161 S3-3: a proven line of theirs that reads nothing hidden, when there is one
                    ForcedWinSolver.Proposal clean = null;
                    for (ForcedWinSolver.Proposal p : props) {
                        if (p.proven(1) && !p.libDependent
                                && !(r.copyGap != null && p.endTurn != live.getPhaseHandler().getTurn())
                                && (clean == null || p.endTurn >= 0 && p.endTurn < clean.endTurn)) {
                            clean = p; // re-check S3: the shortest clean line, as the win check
                        }
                    }
                    if (clean != null) {
                        proven = clean;
                    }
                }
                if (proven != null && spec.libLabels && proven.libDependent) {
                    libLost++; // e1-q8-1101: their proving line read their own hidden library
                    libTakes = Math.max(libTakes, proven.hiddenTakes);
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
            if (sum != null) {
                r.meter = sum.toJson();
                r.meter.addProperty("loss_wall_stop", wallStop); // lossWallMs stopped the worlds (ms specs only)
            }
            r.pLoss = ran == 0 ? Double.NaN : (double) lost / ran;
            if (ran == spec.worlds && lost == ran && ran > 0 && libLost > 0) {
                // e1-q8-1101: every world lost, but some by lines that read their hidden library: sampled, never "loss"
                r.libTag = "sampled";
                r.libW = ran;
                r.libTakes = libTakes;
                // review #161 S2-1: each world's line was found and verified in that same world, so lost/ran is not an
                // estimate of the dependent lines: p_loss is withheld (null) for a sampled loss
                r.pLoss = Double.NaN;
            } else if (ran == spec.worlds && lost == ran && ran > 0) {
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

    /**
     * rollout-budget-1101 (tests only; null = off, the default): loss-check world indices whose solver gets
     * {@code aiWork=1} (a cap hit), to test the capped-world accounting with K worlds and one capped.
     */
    static java.util.function.IntPredicate TEST_CAP_LOSS_WORLD;

    /** e1-q8-1101: the most name sequences an exact-draw label enumerates (more: sampled). */
    static final int LIB_SEQ_CAP = 64;
    /** known-lib-1102: the most draw sequences {@link Seat#drawPlans} plans on, and the most lines it re-verifies. */
    static final int PLAN_SEQ_CAP = 16, PLAN_LINE_CAP = 8;

    private static ForcedWinSolver.Config capOne(final ForcedWinSolver.Config c) {
        c.aiWork = 1;
        return c;
    }

    private HonestLabel() {
    }
}
