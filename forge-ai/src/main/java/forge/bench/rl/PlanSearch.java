package forge.bench.rl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.simulation.ForcedWinSolver;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.BinaryChoiceType;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;

/**
 * Lane search-bones-1015: S-b, S-c over turn-plans, for one RL seat (spec key {@code plans} of the S-c search spec; absent
 * = S-c byte for byte).
 *
 * <p><b>(a) Turn-plans.</b> A plan is our seat's priority answers at an empty stack from this decision to the end of our
 * turn (each a menu entry by host card id, ability text and land flag, at a phase), after which the policy plays on;
 * or a forced-win solver line (its steps and forced answers, followed by the solver's follower). At a <i>plan point</i>
 * (our main phase, empty stack, the first searched decision of the phase or the first after a plan ended) the proposals
 * are: the solver's lines ({@link ForcedWinSolver#propose}: the width generator and the engine DFS, each verified by the
 * solver's own engine evaluation over its belief worlds with the adversary), and {@code samples} plans sampled from the
 * policy in copies of the decision's world 0 (the policy seat samples its empty-stack priority answers on our turn,
 * greedy elsewhere, until our turn ends; a greedy recording drops the samples equal to the policy's own plan). The plans
 * join S-c's candidates (the policy's choice and the next prior entries) and every one is played out in S-c's K worlds
 * (common random numbers): verified step by step in each world (a step that cannot be played there breaks the plan,
 * and the policy plays on), then valued by the policy play-outs and the value leaf.
 *
 * <p><b>(b) MCTS-Solver backups.</b> A solver line whose own verification won in every belief world it ran (at least
 * {@code minProvenWorlds}) and unopposed is a proven win: it is taken at once, before any play-out. In S-c's worlds a
 * candidate whose play-outs all end in our win with its plan unbroken is a proven win (taken without the departZ gate or
 * the loop guard); one that loses in every world is a proven loss (out of the choice).
 *
 * <p><b>Live following.</b> A chosen policy plan is followed through the search's answers (the seat's tape carries
 * every step); a chosen solver line is followed by this seat's plan hook (its answers are not on the tape). A step that
 * cannot be played ends the plan; the next searched decision is a plan point again.
 */
public final class PlanSearch implements PlayerControllerBridge.PlanHook {

    /** The spec ({@code plans} object of the S-c search spec). */
    public static final class Spec {
        /** Policy-sampled plans per plan point (0 = none). */
        public int samples = 4;
        /** Sampling temperature over the policy's probabilities (1 = the policy's own distribution). */
        public double temperature = 1.0;
        /** Priority answers recorded per plan at most. */
        public int maxSteps = 30;
        /** The solver's lines as proposals. */
        public boolean solver = true;
        /** Non-proven solver lines searched as candidates, at most. */
        public int maxLines = 2;
        /** A solver line is proven when it won in at least this many of its belief worlds (all it ran). */
        public int minProvenWorlds = 3;
        /** The recordings' share of the decision budget, ms. */
        public long recordMs = 1500;
        /** The solver's config (string values; {@link ForcedWinSolver.Config#fromProps}); act=false is forced. */
        public JsonObject solverCfg = new JsonObject();
        /**
         * (d), off by default: the opponent's lines priced in every play-out ({@link forge.ai.simulation.ThreatCheck}), and
         * our responses to their spells searched (a stack decision with their spell on top and a counter in our menu).
         */
        public boolean opp = false;
        /** (d): the opponent check's solver config (string values; exact, act=false, no defence are forced). */
        public JsonObject oppCfg = new JsonObject();
        /**
         * strong-opp-1020, off by default (and then nothing changes): the forced-win solver's DEFENSE in front of the live
         * seat. With their spell on top of the stack and a counterspell we can cast: counter it when resolving it gives
         * them a forced win by their next turns (and countering it does not), pass to keep the counter when a bigger
         * threat is likely still to come, else no opinion (the policy / search decide). Our discard effect on their
         * revealed hand: the card whose removal lowers their forced-win chance most. Their hidden cards come from the
         * search's belief re-draw when the spec has a belief (honest), else from their registered deck (the solver's
         * old convention: a leak, for comparison only).
         */
        public boolean defend = false;
        /** strong-opp-1020: the defender's solver config (string values; act=false is forced). */
        public JsonObject defendCfg = new JsonObject();
        /**
         * combo-assembly-1021 (B1), off by default (and then nothing changes): the tutor chooser
         * ({@link TutorChooser}) answers this seat's single-card library searches when no solver line is active: Forge
         * resolves every offered name in a copy, one value-head batch ranks them, the solver checks the top few.
         */
        public boolean choose = false;
        /** combo-assembly-1021: the chooser's config ({@link TutorChooser.Cfg} keys, string values). */
        public JsonObject chooseCfg = new JsonObject();

        static final Set<String> KEYS = new java.util.TreeSet<>(java.util.Arrays.asList("samples", "temperature",
                "maxSteps", "solver", "maxLines", "minProvenWorlds", "recordMs", "solverCfg", "opp", "oppCfg", "defend",
                "defendCfg", "choose", "chooseCfg"));

        public static Spec parse(final JsonObject o) {
            final Spec s = new Spec();
            if (o == null) {
                return s;
            }
            for (String k : o.keySet()) {
                if (!KEYS.contains(k)) {
                    throw new IllegalArgumentException("plans: unknown key " + k);
                }
            }
            if (o.has("samples")) s.samples = o.get("samples").getAsInt();
            if (o.has("temperature")) s.temperature = o.get("temperature").getAsDouble();
            if (o.has("maxSteps")) s.maxSteps = o.get("maxSteps").getAsInt();
            if (o.has("solver")) s.solver = o.get("solver").getAsBoolean();
            if (o.has("maxLines")) s.maxLines = o.get("maxLines").getAsInt();
            if (o.has("minProvenWorlds")) s.minProvenWorlds = o.get("minProvenWorlds").getAsInt();
            if (o.has("recordMs")) s.recordMs = o.get("recordMs").getAsLong();
            if (o.has("solverCfg")) s.solverCfg = o.getAsJsonObject("solverCfg");
            if (o.has("opp")) s.opp = o.get("opp").getAsBoolean();
            if (o.has("oppCfg")) s.oppCfg = o.getAsJsonObject("oppCfg");
            if (o.has("defend")) s.defend = o.get("defend").getAsBoolean();
            if (o.has("defendCfg")) s.defendCfg = o.getAsJsonObject("defendCfg");
            if (o.has("choose")) s.choose = o.get("choose").getAsBoolean();
            if (o.has("chooseCfg")) s.chooseCfg = o.getAsJsonObject("chooseCfg");
            TutorChooser.Cfg.parse(s.chooseCfg); // validated here, so a bad key fails at parse time
            if (s.samples < 0 || s.temperature <= 0 || s.maxSteps < 1 || s.maxLines < 0 || s.minProvenWorlds < 1) {
                throw new IllegalArgumentException("plans: samples >= 0, temperature > 0, maxSteps >= 1, maxLines >= 0, "
                        + "minProvenWorlds >= 1");
            }
            return s;
        }

        /** The solver's config: S-b's defaults (a 3 s proposal budget), the spec's keys, act=false. */
        ForcedWinSolver.Config solverConfig() {
            final JsonObject o = new JsonObject();
            o.addProperty("budgetMs", "3000");
            o.addProperty("engineMs", "1500");
            o.addProperty("evalReserveMs", "1000");
            o.addProperty("wallGraceMs", "1000");
            // step 1: no hand-written defence (counter / discard hooks); (d) prices the opponent's lines by search
            o.addProperty("defense", "false");
            for (Map.Entry<String, com.google.gson.JsonElement> e : solverCfg.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
            o.addProperty("act", "false");
            return SolverDriver.config(o);
        }

        /** strong-opp-1020: the defender's config: a 1.5 s width-search budget, 4 belief worlds; then the spec's keys. */
        ForcedWinSolver.Config defendConfig() {
            final JsonObject o = new JsonObject();
            o.addProperty("budgetMs", "3000");
            o.addProperty("planMs", "1500");
            o.addProperty("defenseWorlds", "4");
            o.addProperty("engine", "false");
            for (Map.Entry<String, com.google.gson.JsonElement> e : defendCfg.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
            o.addProperty("act", "false");
            o.addProperty("defense", "true");
            return SolverDriver.config(o);
        }

        /** (d): the opponent check's config: a cheap same-turn width search, verified exactly; then the spec's keys. */
        ForcedWinSolver.Config oppConfig() {
            final JsonObject o = new JsonObject();
            o.addProperty("budgetMs", "500");
            o.addProperty("wallGraceMs", "300");
            o.addProperty("nodes", "3000");
            o.addProperty("planMs", "200");
            o.addProperty("horizon", "1");
            o.addProperty("engine", "false");
            o.addProperty("top", "2");
            for (Map.Entry<String, com.google.gson.JsonElement> e : oppCfg.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
            return SolverDriver.config(o);
        }
    }

    // ------------------------------------------------------------------ plans

    /** One recorded priority answer: pass, or a menu entry (host id, ability text, land flag), at a phase. */
    public static final class PStep {
        final boolean pass;
        final int hostId;
        final String desc;
        final boolean land;
        final String phase;
        final String label;

        PStep(final SpellAbility sa, final String phase) {
            this.pass = sa == null;
            this.hostId = sa == null || sa.getHostCard() == null ? -1 : sa.getHostCard().getId();
            this.desc = sa == null ? "" : sa.getDescription();
            this.land = sa != null && sa.isLandAbility();
            this.phase = phase;
            this.label = sa == null ? "pass" : (sa.getHostCard() == null ? "?" : sa.getHostCard().getName());
        }

        String key() {
            return phase + ":" + (pass ? "pass" : hostId + "|" + (land ? "L" : "S") + "|" + desc);
        }

        boolean matches(final SpellAbility sa) {
            if (sa == null) {
                return pass;
            }
            return !pass && sa.getHostCard() != null && sa.getHostCard().getId() == hostId && sa.isLandAbility() == land
                    && desc.equals(sa.getDescription());
        }
    }

    /** A policy plan: our empty-stack priority answers in one of our turns. */
    public static final class PolicyPlan {
        final int turn;
        final List<PStep> steps;
        final String key;

        PolicyPlan(final int turn, final List<PStep> steps) {
            this.turn = turn;
            this.steps = steps;
            final StringBuilder sb = new StringBuilder();
            for (PStep s : steps) {
                sb.append(s.key()).append(';');
            }
            this.key = sb.toString();
        }

        String label() {
            final StringBuilder sb = new StringBuilder();
            for (PStep s : steps) {
                if (!s.pass) {
                    sb.append(sb.length() == 0 ? "" : ",").append(s.label);
                }
            }
            return "[" + sb + "]/" + steps.size();
        }
    }

    /** A forced-win solver line as a plan. */
    public static final class LinePlan {
        final Object line;
        final ForcedWinSolver.Proposal proposal;

        LinePlan(final ForcedWinSolver.Proposal p) {
            this.line = p.line;
            this.proposal = p;
        }
    }

    /** Follows a policy plan at the seat's priority asks (a play-out's seat, or the live seat through the search). */
    static final class Cursor implements RlSeat.PlanCursor {
        final PolicyPlan plan;
        final LookaheadSearch.FirstAction fa;
        int i;
        int state; // 0 running, 1 done, 2 broken
        String why = "";

        Cursor(final PolicyPlan plan, final LookaheadSearch.FirstAction fa, final int from) {
            this.plan = plan;
            this.fa = fa;
            this.i = from;
            if (i >= plan.steps.size()) {
                end(1, "");
            }
        }

        private void end(final int s, final String w) {
            state = s;
            why = w;
            if (fa != null) {
                fa.planState = s;
            }
        }

        boolean active() {
            return state == 0;
        }

        @Override
        public int choose(final Game g, final Player p, final RlCandidates.Menu m, final List<SpellAbility> objs) {
            if (state != 0 || m.trivial || !g.getStack().isEmpty()) {
                return -1;
            }
            final PhaseHandler ph = g.getPhaseHandler();
            if (ph.getTurn() != plan.turn || !ph.isPlayerTurn(p)) {
                // the turn ended first: done if every step left is a pass
                boolean passes = true;
                for (int k = i; k < plan.steps.size(); k++) {
                    passes &= plan.steps.get(k).pass;
                }
                end(passes ? 1 : 2, "turn");
                return -1;
            }
            final PStep st = plan.steps.get(i);
            if (!st.phase.equals(String.valueOf(ph.getPhase()))) {
                end(2, "phase " + ph.getPhase() + " != " + st.phase);
                return -1;
            }
            final int idx = match(m, objs, st);
            if (idx < 0) {
                end(2, "missing " + st.label);
                return -1;
            }
            i++;
            if (i >= plan.steps.size()) {
                end(1, "");
            }
            return idx;
        }
    }

    /** The menu candidate of a step (the first one: X variants of one entry play its first variant), or -1. */
    static int match(final RlCandidates.Menu m, final List<SpellAbility> objs, final PStep st) {
        for (int c = 0; c < m.C(); c++) {
            if (m.cands.get(c).kind <= 0) {
                continue;
            }
            final int ch = RlSearch.choiceOf(m, c);
            if (ch < 0 || ch > objs.size()) {
                continue;
            }
            if (st.matches(ch == 0 ? null : objs.get(ch - 1))) {
                return c;
            }
        }
        return -1;
    }

    /**
     * A recording play-out's seat: samples its empty-stack priority answers on our turn (greedy when temperature is
     * null) and records every priority answer at an empty stack in that turn.
     */
    public static final class Recorder implements RlSeat.PriorityTrace {
        final int turn;
        final Random rng;
        final double temperature;
        final boolean sampled;
        final int maxSteps;
        final List<PStep> steps = new ArrayList<>();
        Player seatPlayer;

        Recorder(final int turn, final long seed, final double temperature, final boolean sampled, final int maxSteps) {
            this.turn = turn;
            this.rng = new Random(seed);
            this.temperature = temperature;
            this.sampled = sampled;
            this.maxSteps = maxSteps;
        }

        boolean inPlan(final Game g, final Player p) {
            final PhaseHandler ph = g.getPhaseHandler();
            return ph.getTurn() == turn && ph.isPlayerTurn(p) && g.getStack().isEmpty() && steps.size() < maxSteps;
        }

        /** {@link RlSeat#sampleIf}: a PRIORITY frame of our plan's turn at an empty stack. */
        boolean sampleIf(final Game g, final RlWire.Decide f) {
            return sampled && f.family == RlSchema.F_PRIORITY && seatPlayer != null && inPlan(g, seatPlayer);
        }

        @Override
        public void answered(final Game g, final Player p, final RlCandidates.Menu m, final List<SpellAbility> objs,
                final int choice) {
            if (!inPlan(g, p) || choice < 0 || choice > objs.size()) {
                return;
            }
            steps.add(new PStep(choice == 0 ? null : objs.get(choice - 1), String.valueOf(g.getPhaseHandler().getPhase())));
        }

        /** A sampled answer from the policy's probabilities over the frame's candidates. */
        RlWire.Decision sample(final RlWire.Decide f, final RlSearchClient.Scores s) {
            final double[] w = new double[s.probs.length];
            double tot = 0;
            for (int i = 0; i < w.length; i++) {
                if (f.candKind != null && i < f.candKind.length && f.candKind[i] <= 0) {
                    continue; // not a candidate the seat can answer with
                }
                final double p = Math.max(0, s.probs[i]);
                w[i] = p <= 0 ? 0 : Math.pow(p, 1.0 / temperature);
                tot += w[i];
            }
            int pick = 0;
            if (tot > 0) {
                double u = rng.nextDouble() * tot;
                for (int i = 0; i < w.length; i++) {
                    u -= w[i];
                    if (u <= 0 && w[i] > 0) {
                        pick = i;
                        break;
                    }
                    if (w[i] > 0) {
                        pick = i;
                    }
                }
            }
            final RlWire.Decision d = new RlWire.Decision();
            d.gameUid = f.gameUid;
            d.decIdx = f.decIdx;
            d.status = RlWire.ST_OK;
            d.policyVersion = s.policyVersion;
            d.valueObs = s.valueObs;
            d.steps = new short[] {(short) pick};
            return d;
        }
    }

    // ------------------------------------------------------------------ per seat

    private final Spec spec;
    private final RlSearch rs;
    private final Player me;
    /** The S-b solver (act=false): proposals, the live line's follower and its choice answers. */
    final SolverDriver driver;
    /** The live policy plan being followed, or null. */
    private Cursor live;
    /** The search handed the current priority to the hook (read once by the bridge). */
    private boolean handoff;
    private int lastPointTurn = -1;
    private String lastPointPhase = "";
    private boolean pointAgain;
    final ForcedWinSolver.Config followCfg;
    /** (d): the opponent check (null = off). */
    final forge.ai.simulation.ThreatCheck threat;
    int respondPoints;
    /** strong-opp-1020: the defender (null = off) and its counters. */
    final ForcedWinSolver defender;
    int defendCalls, defendCounters, defendHolds, defendDiscards, defendErrors;
    /** combo-assembly-1021: the tutor chooser (null = off). */
    final TutorChooser chooser;
    final List<Double> defendMs = new ArrayList<>();

    // counters
    int planPoints, solverRuns, provenLinesTaken, lineCands, sampledPlans, recordings, recordFails, planDecisions,
            planDepartures, lineDepartures, policyPlanDepartures, provenPicks, liveSteps, liveBreaks, liveDone, lineHookActs;
    final List<Double> proposeMs = new ArrayList<>();
    /** The game turns of each live departure by kind (attribution of wins to solver lines vs sampled plans). */
    final List<Integer> provenTurns = new ArrayList<>(), lineTurns = new ArrayList<>(), planTurns = new ArrayList<>();
    final List<Double> pointMs = new ArrayList<>();
    final List<Double> solverMs = new ArrayList<>();

    PlanSearch(final Spec spec, final RlSearch rs, final Player me, final RlSeat seat, final SolverDriver unused) {
        this.spec = spec;
        this.rs = rs;
        this.me = me;
        final ForcedWinSolver.Config sc = spec.solverConfig();
        this.driver = new SolverDriver(me, sc);
        // honest-belief-1020: the solver's verification worlds and engine root draw the opponent's hidden cards from the
        // search's belief pool (null = belief off: as before)
        this.driver.solver().redraw = rs.ls().opponentRedraw();
        this.followCfg = sc;
        this.threat = spec.opp ? new forge.ai.simulation.ThreatCheck(spec.oppConfig()) : null;
        if (spec.defend) {
            this.defender = new ForcedWinSolver(me, spec.defendConfig());
            final ForcedWinSolver.Redraw prior = spec.defendCfg.has("prior") && !"none".equals(spec.defendCfg.get("prior").getAsString())
                    ? rs.ls().opponentRedrawPrior(loadSyn(spec.defendCfg.get("prior").getAsString()),
                            spec.defendCfg.has("priorGain") ? Double.parseDouble(spec.defendCfg.get("priorGain").getAsString()) : 20.0,
                            spec.defendCfg.has("priorFillBasics") && "true".equals(spec.defendCfg.get("priorFillBasics").getAsString()))
                    : null;
            // the belief's re-draw (uniform, or with the co-draft deck prior); null when the spec has no belief: the
            // solver's old registered-deck read (a leak, for comparison only)
            this.defender.redraw = prior != null ? prior : rs.ls().opponentRedraw();
        } else {
            this.defender = null;
        }
        this.chooser = spec.choose ? new TutorChooser(TutorChooser.Cfg.parse(spec.chooseCfg), rs, me, spec.solverCfg) : null;
    }

    /**
     * (d): a respond point: their spell on top of the stack and a counterspell in our menu. S-b searches it (S-c skips
     * every stack decision) with the opponent check in its play-outs.
     */
    boolean respondPoint(final Game g, final List<SpellAbility> objs) {
        if (threat == null || g.getStack().isEmpty()) {
            return false;
        }
        final SpellAbility top = g.getStack().peekAbility();
        if (top == null || top.getActivatingPlayer() == me || !top.isSpell()) {
            return false;
        }
        for (SpellAbility sa : objs) {
            if (isCounter(sa)) {
                respondPoints++;
                return true;
            }
        }
        return false;
    }

    static boolean isCounter(final SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.getApi() == forge.game.ability.ApiType.Counter) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the hook (live)

    @Override
    public List<SpellAbility> priority(final PlayerControllerBridge c) {
        if (defender != null && !me.getGame().getStack().isEmpty() && !driver.solver().lineActive()) {
            final long t0 = System.nanoTime();
            final int c0 = defender.counters, h0 = defender.holds, k0 = defender.threatChecks;
            ForcedWinSolver.Decision d;
            try {
                d = driver.probe(() -> defender.defend(me.getGame()));
            } catch (Throwable e) {
                // review S2-2: the defense never reaches the game thread; the seat decides as before
                d = null;
                defendErrors++;
            }
            if (d == null) {
                return PlayerControllerBridge.NO_OPINION;
            }
            if (defender.threatChecks != k0) {
                defendCalls++;
                defendMs.add((System.nanoTime() - t0) / 1e6);
            }
            if (d.owned) {
                if (defender.counters != c0) {
                    defendCounters++;
                }
                if (defender.holds != h0) {
                    defendHolds++;
                }
                if (d.action == null) {
                    return null;
                }
                final List<SpellAbility> out = new ArrayList<>();
                out.add(d.action);
                return out;
            }
        }
        if (driver.solver().lineDone()) {
            return PlayerControllerBridge.NO_OPINION;
        }
        final List<SpellAbility> r = driver.priority(c);
        if (r != PlayerControllerBridge.NO_OPINION) {
            lineHookActs++;
        }
        if (driver.solver().lineDone()) {
            pointAgain = true;
            if (driver.solver().lineBroken()) {
                liveBreaks++;
            } else {
                liveDone++;
            }
        }
        return r;
    }

    private static final Map<String, Map<String, Map<String, Double>>> SYN = new java.util.HashMap<>();

    /** strong-opp-1020: the jar's co-draft table (mtgx public/data/cobra-drafted-with.json, the mush cube). */
    public static final String DEFEND_PRIOR = "classpath:/forge/ai/simulation/defend-prior-cobra-mush.json";

    /**
     * strong-opp-1020: the co-draft table ({@code {"syn": {a: {b: score}}}}), loaded once per path ({@code classpath:} = a
     * jar resource).
     */
    static synchronized Map<String, Map<String, Double>> loadSyn(final String path) {
        return SYN.computeIfAbsent(path, k -> {
            try {
                final byte[] raw;
                if (k.startsWith("classpath:")) {
                    try (java.io.InputStream in = PlanSearch.class.getResourceAsStream(k.substring("classpath:".length()))) {
                        if (in == null) {
                            throw new java.io.IOException("no resource " + k);
                        }
                        raw = in.readAllBytes();
                    }
                } else {
                    raw = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(k));
                }
                final JsonObject o = com.google.gson.JsonParser.parseString(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("syn");
                final Map<String, Map<String, Double>> m = new java.util.HashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                    final Map<String, Double> row = new java.util.HashMap<>();
                    for (Map.Entry<String, com.google.gson.JsonElement> f : e.getValue().getAsJsonObject().entrySet()) {
                        row.put(f.getKey(), f.getValue().getAsDouble());
                    }
                    m.put(e.getKey(), row);
                }
                return m;
            } catch (java.io.IOException | RuntimeException e) {
                throw new IllegalArgumentException("plans.defendCfg.prior: " + k + ": " + e, e);
            }
        });
    }

    @Override
    public CardCollection discard(final Player p, final SpellAbility sa, final CardCollection valid, final int min,
            final int max) {
        return discard(p, sa, valid, min, max, null);
    }

    @Override
    public CardCollection discard(final Player p, final SpellAbility sa, final CardCollection valid, final int min,
            final int max, final CardCollectionView visibleToChooser) {
        if (defender == null) {
            return null;
        }
        final long t0 = System.nanoTime();
        CardCollection r;
        try {
            r = driver.probe(() -> defender.answerDiscardP(p, sa, valid, min, max, visibleToChooser));
        } catch (Throwable e) {
            r = null; // review S2-2: the policy chooses
            defendErrors++;
        }
        defendMs.add((System.nanoTime() - t0) / 1e6);
        if (r != null) {
            defendDiscards++;
        }
        return r;
    }

    @Override
    public boolean handoff() {
        final boolean h = handoff;
        handoff = false;
        return h;
    }

    @Override
    public boolean attack(final Player attacker, final Combat combat) {
        return driver.attack(attacker, combat);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> look(final CardCollection topN) {
        return driver.look(topN);
    }

    @Override
    public List<SpellAbility> triggers(final List<SpellAbility> abilities) {
        return driver.triggers(abilities);
    }

    @Override
    public Card pick(final ZoneType destination, final List<ZoneType> origin, final SpellAbility sa,
            final CardCollection choices, final Player decider) {
        final Card c = driver.pick(destination, origin, sa, choices, decider);
        if (c != null || chooser == null || !driver.solver().lineDone()) {
            return c;
        }
        // combo-assembly-1021 (B1): no solver line answered: the tutor chooser, else (null) the policy's ask
        return chooser.pick(destination, origin, sa, choices, decider);
    }

    @Override
    public CardCollectionView order(final CardCollectionView cards, final ZoneType destination, final SpellAbility source) {
        return driver.order(cards, destination, source);
    }

    @Override
    public Boolean confirm(final SpellAbility sa, final String message) {
        return driver.confirm(sa, message);
    }

    @Override
    public boolean confirmTrigger(final WrappedAbility w) {
        return driver.confirmTrigger(w);
    }

    @Override
    public Boolean binary(final BinaryChoiceType kind, final Boolean defaultVal) {
        return driver.binary(kind, defaultVal);
    }

    @Override
    public boolean forgeCosts() {
        return driver.forgeCosts();
    }

    // ------------------------------------------------------------------ the decision (from RlSearch.decide)

    /** Our library's known top (pile memory) for this seat's worlds. */
    int knownTop(final Game g) {
        return driver.probe(() -> driver.solver().knownTopCount(g));
    }

    /** The live policy plan's next step at this priority (its candidate index), or -1. */
    int follow(final Game g, final Player p, final RlCandidates.Menu m, final List<SpellAbility> objs) {
        if (live == null) {
            return -1;
        }
        final int idx = live.choose(g, p, m, objs);
        if (idx >= 0) {
            liveSteps++;
        }
        if (!live.active()) {
            if (live.state == 2) {
                liveBreaks++;
            } else {
                liveDone++;
            }
            live = null;
            pointAgain = true;
        }
        return idx;
    }

    /** A plan point: our main phase (or their end step, solver lines only), empty stack, once per phase or after a plan. */
    boolean planPoint(final Game g) {
        if (!g.getStack().isEmpty() || live != null || !driver.solver().lineDone()) {
            return false;
        }
        final PhaseHandler ph = g.getPhaseHandler();
        final boolean ourMain = ph.isPlayerTurn(me) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
        final boolean theirEnd = spec.solver && !ph.isPlayerTurn(me) && ph.is(PhaseType.END_OF_TURN);
        if (!ourMain && !theirEnd) {
            return false;
        }
        final String phase = String.valueOf(ph.getPhase());
        if (ph.getTurn() == lastPointTurn && phase.equals(lastPointPhase) && !pointAgain) {
            return false;
        }
        lastPointTurn = ph.getTurn();
        lastPointPhase = phase;
        pointAgain = false;
        return true;
    }

    /** What the plan stage decided before (or instead of) S-c's play-outs. */
    static final class Stage {
        /** The search hands the priority to the hook (a proven line was adopted). */
        boolean hook;
        /** The plan candidates for the play-outs (empty = none: S-c alone), their labels and kinds. */
        final List<Object> plans = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        int knownTop;
        double ms;
        String note = "";
    }

    /** The proposals at a plan point (solver lines, policy samples), in the decision's budget from {@code t0}. */
    Stage propose(final Game g, final Player p, final RlCandidates.Menu m, final List<SpellAbility> objs, final long t0) {
        final Stage st = new Stage();
        planPoints++;
        final ForcedWinSolver solver = driver.solver();
        st.knownTop = driver.probe(() -> solver.knownTopCount(g));
        if (spec.solver) {
            final long a = System.nanoTime();
            // combo-assembly-1021 (G1 attempt 2): the cards the seat knows in its library (B0's own placements and the
            // tracker's other honest knowledge) are the solver's known top; hideOwnLibrary hides only the rest
            solver.notePlaced(rs.knownOwnLibrary(me));
            final List<ForcedWinSolver.Proposal> props = driver.probe(() -> solver.propose(g));
            solverRuns++;
            solverMs.add((System.nanoTime() - a) / 1e6);
            ForcedWinSolver.Proposal proven = null;
            for (ForcedWinSolver.Proposal pr : props) {
                if (pr.proven(spec.minProvenWorlds) && (proven == null || pr.steps < proven.steps)) {
                    proven = pr;
                }
            }
            if (proven != null) {
                // (b) a proven node: take it, no play-outs
                solver.adopt(proven.line);
                handoff = true;
                provenLinesTaken++;
                provenTurns.add(g.getPhaseHandler().getTurn());
                st.hook = true;
                st.note = "proven-line " + proven.text;
                st.ms = (System.nanoTime() - t0) / 1e6;
                proposeMs.add(st.ms);
                return st;
            }
            int n = 0;
            for (ForcedWinSolver.Proposal pr : props) {
                if (n >= spec.maxLines) {
                    break;
                }
                st.plans.add(new LinePlan(pr));
                st.labels.add("line#" + n + " P=" + Math.round(pr.pBelief * 100) + "% steps=" + pr.steps);
                n++;
                lineCands++;
            }
        }
        final PhaseHandler ph = g.getPhaseHandler();
        if (spec.samples > 0 && ph.isPlayerTurn(p)) {
            final long a = System.nanoTime();
            // J0 (block-search-1019): both clocks go through WallClock, so a "Continue from here" rebuild unbinds them
            // (unchanged live)
            final long deadline = Math.min(a + forge.ai.WallClock.budgetMs(spec.recordMs) * 1_000_000L,
                    t0 + Math.max(1000L, forge.ai.WallClock.budgetMs(rs.budgetMs()) / 2) * 1_000_000L);
            final List<Object> recs = new ArrayList<>();
            final Recorder greedy = new Recorder(ph.getTurn(), 0L, 1.0, false, spec.maxSteps);
            recs.add(greedy);
            final long seed0 = rs.ls().nextWorld0Seed();
            for (int i = 0; i < spec.samples; i++) {
                recs.add(new Recorder(ph.getTurn(), RlSearch.splitmix(seed0 ^ (0x5a3L + i)), spec.temperature, true,
                        spec.maxSteps));
            }
            final boolean[] ok = rs.ls().recordPlans(g, p, recs, st.knownTop, deadline);
            recordings += recs.size();
            final Set<String> seen = new HashSet<>();
            String greedyKey = null;
            if (ok[0] && !greedy.steps.isEmpty()) {
                greedyKey = new PolicyPlan(ph.getTurn(), greedy.steps).key;
                seen.add(greedyKey);
            } else if (!ok[0]) {
                recordFails++;
            }
            for (int i = 1; i < recs.size(); i++) {
                final Recorder r = (Recorder) recs.get(i);
                if (!ok[i]) {
                    recordFails++;
                    continue;
                }
                if (r.steps.isEmpty()) {
                    continue;
                }
                final PolicyPlan pp = new PolicyPlan(ph.getTurn(), new ArrayList<>(r.steps));
                if (!seen.add(pp.key) || match(m, objs, pp.steps.get(0)) < 0) {
                    continue;
                }
                st.plans.add(pp);
                st.labels.add("sample#" + i + " " + pp.label());
                sampledPlans++;
            }
            proposeMs.add((System.nanoTime() - a) / 1e6);
        }
        st.ms = (System.nanoTime() - t0) / 1e6;
        return st;
    }

    /**
     * After the play-outs: the chosen candidate. A plan index (>= {@code givenSize}) adopts the plan: a solver line goes
     * to the hook ({@link RlSeat.PrioritySearch#HOOK}), a policy plan is followed from its first step (its candidate
     * index here). Otherwise -2 (not a plan: the caller's answer stands).
     */
    int apply(final Stage st, final LookaheadSearch.GivenResult r, final int givenSize, final RlCandidates.Menu m,
            final List<SpellAbility> objs, final Game g, final double ms) {
        planDecisions++;
        pointMs.add(ms);
        if (r.proven) {
            provenPicks++;
        }
        if (r.chosen < givenSize) {
            return -2;
        }
        final Object plan = st.plans.get(r.chosen - givenSize);
        planDepartures++;
        if (plan instanceof LinePlan) {
            driver.solver().adopt(((LinePlan) plan).line);
            handoff = true;
            lineDepartures++;
            lineTurns.add(g.getPhaseHandler().getTurn());
            return RlSeat.PrioritySearch.HOOK;
        }
        final PolicyPlan pp = (PolicyPlan) plan;
        final int idx = match(m, objs, pp.steps.get(0));
        if (idx < 0) {
            return -2;
        }
        policyPlanDepartures++;
        planTurns.add(g.getPhaseHandler().getTurn());
        live = new Cursor(pp, null, 1);
        if (!live.active()) {
            live = null;
            pointAgain = true;
        }
        return idx;
    }

    /** The plan's follower inside a play-out copy: a solver line on the copy seat's bridged controller. */
    static PlayerControllerBridge.PlanHook copyLineHook(final Player copyMe, final ForcedWinSolver.Config cfg,
            final LinePlan lp, final LookaheadSearch.FirstAction fa) {
        final SolverDriver d = new SolverDriver(copyMe, cfg, false);
        d.solver().adopt(lp.line);
        return new PlayerControllerBridge.PlanHook() {
            @Override
            public List<SpellAbility> priority(final PlayerControllerBridge c) {
                if (d.solver().lineDone()) {
                    return PlayerControllerBridge.NO_OPINION;
                }
                final List<SpellAbility> r = d.priority(c);
                if (d.solver().lineDone()) {
                    fa.planState = d.solver().lineBroken() ? 2 : 1;
                }
                return r;
            }

            @Override
            public boolean attack(final Player attacker, final Combat combat) {
                return d.attack(attacker, combat);
            }

            @Override
            public ImmutablePair<CardCollection, CardCollection> look(final CardCollection topN) {
                return d.look(topN);
            }

            @Override
            public List<SpellAbility> triggers(final List<SpellAbility> abilities) {
                return d.triggers(abilities);
            }

            @Override
            public Card pick(final ZoneType destination, final List<ZoneType> origin, final SpellAbility sa,
                    final CardCollection choices, final Player decider) {
                return d.pick(destination, origin, sa, choices, decider);
            }

            @Override
            public CardCollectionView order(final CardCollectionView cards, final ZoneType destination,
                    final SpellAbility source) {
                return d.order(cards, destination, source);
            }

            @Override
            public Boolean confirm(final SpellAbility sa, final String message) {
                return d.confirm(sa, message);
            }

            @Override
            public boolean confirmTrigger(final WrappedAbility w) {
                return d.confirmTrigger(w);
            }

            @Override
            public Boolean binary(final BinaryChoiceType kind, final Boolean defaultVal) {
                return d.binary(kind, defaultVal);
            }

            @Override
            public boolean forgeCosts() {
                return d.forgeCosts();
            }
        };
    }

    // ------------------------------------------------------------------ summary

    static JsonObject dist(final List<Double> xs) {
        final JsonObject o = new JsonObject();
        o.addProperty("n", xs.size());
        if (xs.isEmpty()) {
            return o;
        }
        final List<Double> s = new ArrayList<>(xs);
        java.util.Collections.sort(s);
        o.addProperty("p50", Math.round(s.get(s.size() / 2)));
        o.addProperty("p95", Math.round(s.get(Math.min(s.size() - 1, (int) Math.floor(s.size() * 0.95)))));
        o.addProperty("max", Math.round(s.get(s.size() - 1)));
        final JsonArray all = new JsonArray();
        for (double d : xs) {
            all.add(Math.round(d * 10) / 10.0);
        }
        o.add("each", all);
        return o;
    }

    static JsonArray ints(final List<Integer> xs) {
        final JsonArray a = new JsonArray();
        for (int x : xs) {
            a.add(x);
        }
        return a;
    }

    public JsonObject summary() {
        final JsonObject o = new JsonObject();
        if (defender != null) {
            o.addProperty("defendCalls", defendCalls);
            o.addProperty("defendCounters", defendCounters);
            o.addProperty("defendHolds", defendHolds);
            o.addProperty("defendDiscards", defendDiscards);
            o.addProperty("defendThreatChecks", defender.threatChecks);
            o.addProperty("defendCuts", defender.defendCuts);
            o.addProperty("defendErrors", defendErrors);
            double mx = 0, sum = 0;
            for (double x : defendMs) {
                mx = Math.max(mx, x);
                sum += x;
            }
            o.addProperty("defendMsMax", Math.round(mx));
            o.addProperty("defendMsSum", Math.round(sum));
        }
        o.addProperty("planPoints", planPoints);
        o.addProperty("solverRuns", solverRuns);
        o.addProperty("provenLinesTaken", provenLinesTaken);
        o.addProperty("lineCands", lineCands);
        o.addProperty("sampledPlans", sampledPlans);
        o.addProperty("recordings", recordings);
        o.addProperty("recordFails", recordFails);
        o.addProperty("planDecisions", planDecisions);
        o.addProperty("planDepartures", planDepartures);
        o.addProperty("lineDepartures", lineDepartures);
        o.addProperty("policyPlanDepartures", policyPlanDepartures);
        o.addProperty("provenPicks", provenPicks);
        o.addProperty("liveSteps", liveSteps);
        o.addProperty("liveBreaks", liveBreaks);
        o.addProperty("liveDone", liveDone);
        o.addProperty("lineHookActs", lineHookActs);
        o.add("provenTurns", ints(provenTurns));
        o.add("lineTurns", ints(lineTurns));
        o.add("planTurns", ints(planTurns));
        o.add("proposeMs", dist(proposeMs));
        o.add("pointMs", dist(pointMs));
        o.add("solverMs", dist(solverMs));
        o.add("driver", driver.summary());
        if (chooser != null) {
            o.add("choose", chooser.summary());
        }
        if (threat != null) {
            o.addProperty("respondPoints", respondPoints);
            o.addProperty("threat", threat.summary());
        }
        return o;
    }
}
