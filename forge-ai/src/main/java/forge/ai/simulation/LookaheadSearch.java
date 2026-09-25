package forge.ai.simulation;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.AiCache;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterType;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-step look-ahead over belief-sampled worlds, run INSIDE Forge (the mtgx PIMC recipe).
 *
 * <p>At a contested priority decision of the searching seat, take Forge AI's own answer plus
 * "pass" plus the next legal candidates in Forge's own ordering (breadth B in all). For each
 * of K worlds -- the live position copied with {@link GameCopier}, the opponent's unseen
 * cards (hand + library) and our own library order re-drawn from a seeded belief -- apply each
 * candidate in its own copy and let Forge's AI play BOTH seats until the start of turn
 * {@code now + horizonTurns}. Score with Forge's static {@link GameStateEvaluator} (a result
 * is +/-{@link #TERMINAL}), average over worlds (expected value), play the argmax. Ties go to
 * Forge's own answer.
 *
 * <p>Every world uses common random numbers across candidates (the same sampled world and the
 * same play-out stream), so the comparison between candidates is paired. Every random draw a
 * copy makes comes from a per-thread stream ({@link MyRandom#setThreadRandom}) and every AI
 * cache it touches is per-thread ({@link AiCache#openScope}); the live game's RNG and caches are
 * never advanced by the search. The decision is a pure function of (seed, decision index,
 * position), independent of thread scheduling.
 *
 * <p>Known fidelity limits of the copy (inherited from GameCopier; see the lane's plan.md):
 * the stack is not copied (the search is skipped when the stack is not empty), end-of-turn
 * "until" commands and delayed triggers are not copied, per-turn AI memory is fresh in copies.
 */
public final class LookaheadSearch {

    public static final double TERMINAL = 100000.0;
    private static final AtomicInteger FAILURE_TRACES = new AtomicInteger();

    public static final class Config {
        public int worlds = 1;
        public int breadth = 4;
        public int horizonTurns = 2;
        public int maxSteps = 5000;
        public long seed = 0L;
        /** Search and record, but always play Forge's own answer. */
        public boolean shadow = false;
        public boolean resample = true;
        public int threads = 1;
        /** A departure must beat Forge's answer's EV by more than this. */
        public double margin = 0.0;
        public int maxDeparturesPerTurn = 12;
        /** Probe instrumentation (copy timing, copy fidelity, determinism, sim-AI cost). */
        public boolean probe = false;
        /** Probe instrumentation on at most this many searched decisions per game. */
        public int probeMax = 6;
        /** Probe: once per game, put the live game on a known stream and compare it with a truth rollout. */
        public boolean fidelity = true;
        /** C5: base URL of the foundation-model leaf service (null = Forge's static evaluator as the leaf). */
        public String modelUrl = null;
        /** C5: per-request timeout; a failed request falls back to the static evaluator for that decision. */
        public int modelTimeoutMs = 2000;

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("worlds", worlds);
            o.addProperty("breadth", breadth);
            o.addProperty("horizonTurns", horizonTurns);
            o.addProperty("maxSteps", maxSteps);
            o.addProperty("seed", seed);
            o.addProperty("shadow", shadow);
            o.addProperty("resample", resample);
            o.addProperty("threads", threads);
            o.addProperty("margin", margin);
            o.addProperty("maxDeparturesPerTurn", maxDeparturesPerTurn);
            o.addProperty("probe", probe);
            o.addProperty("probeMax", probeMax);
            o.addProperty("fidelity", fidelity);
            if (modelUrl != null) {
                o.addProperty("modelUrl", modelUrl);
                o.addProperty("modelTimeoutMs", modelTimeoutMs);
            }
            return o;
        }
    }

    /** Per-seat, per-game counters. */
    public static final class Stats {
        public long decisions, stackSkipped, uncontested, searched, departed, departFallback, loopGuard;
        public long rollouts, rolloutFailures, rolloutCapped, candidatesDropped, steps;
        public long searchNanos, maxSearchNanos;
        /** C5 model leaf: requests, leaves scored, decisions that fell back to the static evaluator. */
        public long modelCalls, modelLeaves, modelFallbacks, modelNanos, modelUnknownCards;
        public String modelDigest, modelCheckpoint, modelLastError;
        public final JsonArray probes = new JsonArray();

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("decisions", decisions);
            o.addProperty("stackSkipped", stackSkipped);
            o.addProperty("uncontested", uncontested);
            o.addProperty("searched", searched);
            o.addProperty("departed", departed);
            o.addProperty("departFallback", departFallback);
            o.addProperty("loopGuard", loopGuard);
            o.addProperty("rollouts", rollouts);
            o.addProperty("rolloutFailures", rolloutFailures);
            o.addProperty("rolloutCapped", rolloutCapped);
            o.addProperty("candidatesDropped", candidatesDropped);
            o.addProperty("steps", steps);
            o.addProperty("searchMs", searchNanos / 1e6);
            o.addProperty("maxSearchMs", maxSearchNanos / 1e6);
            o.addProperty("msPerSearch", searched == 0 ? 0 : searchNanos / 1e6 / searched);
            if (modelCalls > 0 || modelFallbacks > 0) {
                o.addProperty("modelCalls", modelCalls);
                o.addProperty("modelLeaves", modelLeaves);
                o.addProperty("modelFallbacks", modelFallbacks);
                o.addProperty("modelMs", modelNanos / 1e6);
                o.addProperty("modelUnknownCards", modelUnknownCards);
                o.addProperty("modelDigest", modelDigest);
                o.addProperty("modelCheckpoint", modelCheckpoint);
                if (modelLastError != null) {
                    o.addProperty("modelLastError", modelLastError);
                }
            }
            if (probes.size() > 0) {
                o.add("probes", probes);
            }
            return o;
        }
    }

    /** A candidate, addressable in any copy by the host card's id (ids survive the copy). */
    static final class Cand {
        final boolean pass;
        final int hostId;
        final String desc;
        final boolean land;
        final boolean isDefault;
        final String label;

        Cand(SpellAbility sa, boolean isDefault) {
            this.pass = sa == null;
            this.hostId = sa == null ? -1 : sa.getHostCard().getId();
            this.desc = sa == null ? "" : sa.getDescription();
            this.land = sa != null && sa.isLandAbility();
            this.isDefault = isDefault;
            this.label = sa == null ? "pass" : (sa.getHostCard().getName() + " :: " + trim(sa.toString()));
        }

        String key() {
            return pass ? "pass" : hostId + "|" + (land ? "L" : "S") + "|" + desc;
        }

        private static String trim(String s) {
            return s.length() > 60 ? s.substring(0, 60) : s;
        }
    }

    private final Config cfg;
    private final Stats stats = new Stats();
    private final ExecutorService pool;
    private final ModelClient model;
    private JsonObject modelDeck = null;
    private int decisionIndex = 0;
    private int departuresTurn = -1;
    private int departuresThisTurn = 0;
    private final Map<String, Integer> departureCounts = new TreeMap<>();
    private boolean fidelityArmed = true;

    /** Fidelity probe: the live game's fingerprint at the watched turn, compared with a truth rollout. */
    private FidelityWatch liveWatch = null;

    public LookaheadSearch(Config cfg) {
        this.cfg = cfg;
        if (cfg.threads > 1) {
            final AtomicInteger n = new AtomicInteger();
            pool = Executors.newFixedThreadPool(cfg.threads, r -> {
                // "Game" prefix: ThreadUtil.isGameThread() must hold so GameAction.invoke runs inline.
                Thread t = new Thread(r, "Game-lookahead-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            });
        } else {
            pool = null;
        }
        model = cfg.modelUrl == null ? null : new ModelClient(cfg.modelUrl, cfg.modelTimeoutMs);
    }

    public Config getConfig() {
        return cfg;
    }

    public Stats getStats() {
        return stats;
    }

    public void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ decision

    /**
     * @param ctrl  the live seat's controller (Forge AI already asked)
     * @param def   Forge AI's own answer (null = pass)
     * @return what to play (null = pass)
     */
    public List<SpellAbility> decide(PlayerControllerAi ctrl, List<SpellAbility> def) {
        final Game live = ctrl.getGame();
        final Player me = ctrl.getPlayer();
        stats.decisions++;
        final int index = decisionIndex++;

        if (!live.getStack().isEmpty()) {
            stats.stackSkipped++;
            return def;
        }
        final PhaseHandler ph = live.getPhaseHandler();
        final int turn = ph.getTurn();
        if (turn != departuresTurn) {
            departuresTurn = turn;
            departuresThisTurn = 0;
            departureCounts.clear();
        }

        final long t0 = System.nanoTime();
        final long decisionSeed = mix(cfg.seed, 0x5eedL + index);
        final SpellAbility defSa = def == null || def.isEmpty() ? null : def.get(0);

        final boolean check = Boolean.getBoolean("lookahead.checkLive");
        final String before = check ? liveProbe(live, defSa) : null;
        // Candidates, enumerated in a copy so the live game is never touched by enumeration.
        final List<Cand> cands = enumerate(live, me, defSa, decisionSeed);
        if (check) {
            checkLive("enumerate", before, live, defSa, index);
        }
        if (cands.size() < 2) {
            stats.uncontested++;
            return def;
        }
        stats.searched++;

        final int k = Math.max(1, cfg.worlds);
        final double[][] values = new double[cands.size()][k];
        final Rollout[][] outs = new Rollout[cands.size()][k];
        final boolean[] ok = new boolean[cands.size()];
        Arrays.fill(ok, true);

        final List<Runnable> tasks = new ArrayList<>();
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < cands.size(); c++) {
                final int ww = w, cc = c;
                tasks.add(() -> {
                    Rollout r = rollout(live, me, cands.get(cc), defSa, mix(decisionSeed, 1000 + ww), cfg.resample, null);
                    synchronized (values) {
                        values[cc][ww] = r.value;
                        outs[cc][ww] = r;
                        if (!r.ok) {
                            ok[cc] = false;
                        }
                        stats.rollouts++;
                        stats.steps += r.steps;
                        if (!r.ok) {
                            stats.rolloutFailures++;
                        }
                        if (r.capped) {
                            stats.rolloutCapped++;
                        }
                    }
                });
            }
        }
        runAll(tasks);
        if (check) {
            checkLive("rollouts " + cands.size() + "x" + k + " def=" + cands.get(0).label, before, live, defSa, index);
        }
        if (model != null) {
            modelLeaves(live, me, values, outs, ok, k);
        }

        int best = 0; // Forge's own answer is candidate 0
        double[] ev = new double[cands.size()];
        for (int c = 0; c < cands.size(); c++) {
            double s = 0;
            for (int w = 0; w < k; w++) {
                s += values[c][w];
            }
            ev[c] = ok[c] ? s / k : Double.NEGATIVE_INFINITY;
            if (!ok[c]) {
                stats.candidatesDropped++;
            }
        }
        if (!ok[0]) {
            // Forge's own answer could not be played out: never depart on a comparison without it.
            best = 0;
        } else {
            for (int c = 1; c < cands.size(); c++) {
                if (ev[c] > ev[best] + (best == 0 ? cfg.margin : 0)) {
                    best = c;
                }
            }
        }

        List<SpellAbility> answer = def;
        String outcome = "kept";
        if (best != 0 && !cfg.shadow) {
            final Cand chosen = cands.get(best);
            final String key = chosen.key() + "@" + ph.getPhase() + "#" + live.getStack().size();
            final int seen = departureCounts.getOrDefault(key, 0);
            if (departuresThisTurn >= cfg.maxDeparturesPerTurn || seen >= 2) {
                stats.loopGuard++;
                outcome = "loop-guard";
            } else {
                List<SpellAbility> mapped = mapToLive(ctrl, live, me, chosen);
                if (mapped == null && !chosen.pass) {
                    stats.departFallback++;
                    outcome = "map-failed";
                } else {
                    answer = mapped;
                    stats.departed++;
                    departuresThisTurn++;
                    departureCounts.put(key, seen + 1);
                    outcome = "departed";
                }
            }
        } else if (best != 0) {
            outcome = "shadow-would-depart";
        }

        if (Boolean.getBoolean("lookahead.debug") && best != 0) {
            System.err.println("[lookahead] decision " + index + " T" + turn + " " + ph.getPhase() + " stack=" + live.getStack().size()
                    + " def=" + cands.get(0).label + " best=" + cands.get(best).label + " outcome=" + outcome
                    + " answer=" + (answer == null ? "pass" : answer.isEmpty() ? "[]" : answer.get(0) + " targets=" + answer.get(0).getAllTargetChoices()));
        }
        final long dt = System.nanoTime() - t0;
        stats.searchNanos += dt;
        stats.maxSearchNanos = Math.max(stats.maxSearchNanos, dt);

        if (cfg.probe && turn >= 3 && stats.probes.size() < cfg.probeMax) {
            JsonObject p = new JsonObject();
            p.addProperty("decision", index);
            p.addProperty("turn", turn);
            p.addProperty("phase", String.valueOf(ph.getPhase()));
            p.addProperty("activePlayer", ph.getPlayerTurn() == me);
            p.addProperty("cardsInGame", live.getCardsInGame().size());
            p.addProperty("searchMs", dt / 1e6);
            p.addProperty("outcome", outcome);
            JsonArray ca = new JsonArray();
            for (int c = 0; c < cands.size(); c++) {
                JsonObject co = new JsonObject();
                co.addProperty("label", cands.get(c).label);
                co.addProperty("ok", ok[c]);
                co.addProperty("ev", ok[c] ? ev[c] : null);
                JsonArray vs = new JsonArray();
                for (int w = 0; w < k; w++) {
                    vs.add(values[c][w]);
                }
                co.add("values", vs);
                ca.add(co);
            }
            p.add("candidates", ca);
            probeExtras(p, live, me, cands, defSa, decisionSeed, turn);
            stats.probes.add(p);
        }
        return answer;
    }

    private void runAll(List<Runnable> tasks) {
        if (pool == null) {
            for (Runnable r : tasks) {
                r.run();
            }
            return;
        }
        List<Future<?>> fs = new ArrayList<>();
        for (Runnable r : tasks) {
            fs.add(pool.submit(r));
        }
        for (Future<?> f : fs) {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException("look-ahead worker failed", e);
            }
        }
    }

    // ------------------------------------------------------------------ candidates

    private List<Cand> enumerate(Game live, Player liveMe, SpellAbility defSa, long decisionSeed) {
        final List<Cand> out = new ArrayList<>();
        out.add(new Cand(defSa, true));
        if (defSa != null) {
            out.add(new Cand(null, false));
        }
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(decisionSeed, 7)));
        AiCache.openScope();
        final Object prevIds1 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            GameCopier copier = new GameCopier(live, true);
            Game g = copier.makeCopy();
            Player me = (Player) copier.find(liveMe);
            List<SpellAbility> legal = new SpellAbilityPicker(me).getCandidateSpellsAndAbilities();
            try {
                legal.sort(ComputerUtilAbility.saEvaluator);
            } catch (IllegalArgumentException ignored) {
                // Forge's comparator is not always transitive; keep enumeration order.
            }
            Set<String> seen = new HashSet<>();
            for (Cand c : out) {
                seen.add(c.key());
            }
            for (SpellAbility sa : legal) {
                if (out.size() >= cfg.breadth) {
                    break;
                }
                Cand c = new Cand(sa, false);
                if (seen.add(c.key())) {
                    out.add(c);
                }
            }
        } catch (RuntimeException e) {
            // Enumeration failure: search nothing, play Forge's answer.
            return out.subList(0, 1);
        } finally {
            AiCache.closeScope();
            forge.util.IdScope.install(prevIds1);
            MyRandom.setThreadRandom(prev);
        }
        return out;
    }

    static SpellAbility locate(Game g, Player p, Cand c) {
        final Card host = g.findById(c.hostId);
        if (host == null) {
            return null;
        }
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(new CardCollection(host), p);
        all = ComputerUtilAbility.getOriginalAndAltCostAbilities(all, p);
        for (SpellAbility sa : all) {
            if (sa.isLandAbility() == c.land && c.desc.equals(sa.getDescription())) {
                return sa;
            }
        }
        for (SpellAbility sa : all) {
            if (sa.isLandAbility() == c.land && c.desc.startsWith(sa.getDescription())) {
                return sa;
            }
        }
        return null;
    }

    /** Prepare a candidate's choices in a copy: Forge's answer copies its live choices, others ask Forge AI. */
    private static SpellAbility prepare(Game g, Player me, Cand c, SpellAbility defSa, GameCopier copier) {
        SpellAbility sa = locate(g, me, c);
        if (sa == null) {
            return null;
        }
        sa.setActivatingPlayer(me);
        if (c.land) {
            return sa;
        }
        if (c.isDefault && defSa != null) {
            SpellAbility d = SpellAbilityChoiceCopier.copyCastChoices(defSa, sa, me);
            if (d == null) {
                return null;
            }
            // Never carry a target over unmapped: start clean, then map Forge's live choices in.
            for (SpellAbility sub = d; sub != null; sub = sub.getSubAbility()) {
                if (sub.usesTargeting()) {
                    sub.resetTargets();
                }
            }
            SpellAbilityChoiceCopier.copyTargets(defSa, d, copier::find);
            if (!targetsInGame(d, g)) {
                return null;
            }
            return d;
        }
        for (SpellAbility sub = sa; sub != null; sub = sub.getSubAbility()) {
            if (sub.usesTargeting()) {
                sub.resetTargets();
            }
        }
        AiPlayDecision dec = ((PlayerControllerAi) me.getController()).getAi().canPlaySa(sa);
        if (dec != AiPlayDecision.WillPlay && needsTargets(sa)) {
            return null;
        }
        if (!targetsInGame(sa, g)) {
            return null;
        }
        return sa;
    }

    /** Every chosen target of the ability (and its sub-abilities) lives in game {@code g}. */
    static boolean targetsInGame(SpellAbility sa, Game g) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (!s.usesTargeting()) {
                continue;
            }
            for (forge.game.GameObject o : s.getTargets()) {
                if (o instanceof Card && ((Card) o).getGame() != g) {
                    return false;
                }
                if (o instanceof Player && ((Player) o).getGame() != g) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean needsTargets(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() && !s.isTargetNumberValid()) {
                return true;
            }
        }
        return false;
    }

    private List<SpellAbility> mapToLive(PlayerControllerAi ctrl, Game live, Player me, Cand c) {
        if (c.pass) {
            return null;
        }
        SpellAbility sa = locate(live, me, c);
        if (sa == null) {
            return null;
        }
        sa.setActivatingPlayer(me);
        if (!c.land) {
            AiPlayDecision dec = ctrl.getAi().canPlaySa(sa);
            if (dec != AiPlayDecision.WillPlay && needsTargets(sa)) {
                return null;
            }
        }
        List<SpellAbility> l = new ArrayList<>();
        l.add(sa);
        return l;
    }

    // ------------------------------------------------------------------ rollout

    static final class Rollout {
        boolean ok = true;
        boolean capped = false;
        double value;
        /** C5: the seat's ForgeState at a non-terminal horizon (null if terminal or no model). */
        JsonObject leaf;
        /** C5: P(seat wins) of a terminal play-out (1, 0, or 0.5 for a draw); NaN if not terminal. */
        double pTerminal = Double.NaN;
        int steps;
        String fingerprint;
        long copyNanos, rolloutNanos;
    }

    /**
     * Forge AI for a play-out, with a loop breaker: after {@link #WINDOW_CAP} actions inside one
     * priority window (same turn, phase and stack size) it passes. Forge's own guard is 999
     * iterations of a full-game LKI copy each; a copy in which an AI action keeps failing to go on
     * the stack (seen: a planeswalker ability whose target is lost in the copy) otherwise spends
     * minutes in one play-out.
     */
    static class RolloutAi extends PlayerControllerAi {
        static final int WINDOW_CAP = 25;
        private String window = "";
        private int actions = 0;
        int breaks = 0;

        RolloutAi(Game g, Player p, forge.LobbyPlayer lp) {
            super(g, p, lp);
        }

        protected List<SpellAbility> capped(List<SpellAbility> chosen) {
            final Game g = getGame();
            final String w = g.getPhaseHandler().getTurn() + ":" + g.getPhaseHandler().getPhase() + ":" + g.getStack().size();
            if (!w.equals(window)) {
                window = w;
                actions = 0;
            }
            if (chosen != null && ++actions > WINDOW_CAP) {
                breaks++;
                return null;
            }
            return chosen;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return capped(super.chooseSpellAbilityToPlay());
        }
    }

    /** Plays the first action it was given, then is Forge AI (with the play-out loop breaker). */
    static final class ScriptedFirst extends RolloutAi {
        private List<SpellAbility> first;
        private boolean used = false;

        ScriptedFirst(Game g, Player p, forge.LobbyPlayer lp, List<SpellAbility> first) {
            super(g, p, lp);
            this.first = first;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (!used) {
                used = true;
                return first;
            }
            return super.chooseSpellAbilityToPlay();
        }
    }

    public static final class TurnWatch {
        final int turn;
        volatile boolean reached = false;
        final Player fpFor;
        String fingerprint;

        TurnWatch(int turn, Player fpFor) {
            this.turn = turn;
            this.fpFor = fpFor;
        }

        @Subscribe
        public void on(GameEventTurnBegan e) {
            if (e.turnNumber() >= turn && !reached) {
                reached = true;
                if (fpFor != null) {
                    fingerprint = LookaheadSearch.fingerprint(fpFor.getGame());
                }
            }
        }
    }

    Rollout rollout(Game live, Player liveMe, Cand c, SpellAbility defSa, long worldSeed, boolean resample, Boolean wantFp) {
        final Rollout r = new Rollout();
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(worldSeed, 1)));
        AiCache.openScope();
        final Object prevIds2 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            long a = System.nanoTime();
            final GameCopier copier;
            final Game g;
            final Player me;
            List<SpellAbility> first = null;
            // Everything that READS the live game runs one worker at a time: many of Forge's "getters"
            // build lists and views lazily (they write), so parallel copies of one live game raced and a
            // K=8 play-out under load could come out different from its replay.
            synchronized (live) {
                copier = new GameCopier(live, true);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                if (resample) {
                    resample(live, liveMe, g, me, new Random(mix(worldSeed, 2)));
                }
                MyRandom.setThreadRandom(new Random(mix(worldSeed, 3)));
                if (!c.pass) {
                    SpellAbility sa = prepare(g, me, c, defSa, copier);
                    if (sa == null) {
                        r.ok = false;
                        r.value = Double.NEGATIVE_INFINITY;
                        return r;
                    }
                    first = new ArrayList<>();
                    first.add(sa);
                }
            }
            r.copyNanos = System.nanoTime() - a;
            me.dangerouslySetController(new ScriptedFirst(g, me, me.getController().getLobbyPlayer(), first));
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new RolloutAi(g, o, o.getController().getLobbyPlayer()));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            givePriority(ph, me);
            final TurnWatch watch = new TurnWatch(ph.getTurn() + cfg.horizonTurns, Boolean.TRUE.equals(wantFp) ? me : null);
            g.subscribeToEvents(watch);
            long b = System.nanoTime();
            int steps = 0;
            while (!g.isGameOver() && !watch.reached && steps < cfg.maxSteps) {
                ph.mainLoopStep();
                steps++;
            }
            r.rolloutNanos = System.nanoTime() - b;
            r.steps = steps;
            r.capped = !g.isGameOver() && !watch.reached;
            r.value = value(g, me);
            if (model != null) {
                r.pTerminal = terminalP(g, me);
                if (Double.isNaN(r.pTerminal)) {
                    r.leaf = forge.bench.StateEncoder.encode(g, me);
                }
            }
            r.fingerprint = watch.fingerprint;
        } catch (RuntimeException | StackOverflowError e) {
            r.ok = false;
            r.value = Double.NEGATIVE_INFINITY;
            System.err.println("[lookahead] rollout failed: " + e);
            if (FAILURE_TRACES.getAndIncrement() < 20) {
                e.printStackTrace();
            }
        } finally {
            AiCache.closeScope();
            forge.util.IdScope.install(prevIds2);
            MyRandom.setThreadRandom(prev);
        }
        return r;
    }

    /** P(me wins) of a finished play-out, in the model's scale; NaN if the play-out did not end the game. */
    static double terminalP(Game g, Player me) {
        if (g.isGameOver() || !me.isInGame()) {
            if (me.hasWon()) {
                return 1.0;
            }
            if (me.hasLost() || !me.isInGame()) {
                return 0.0;
            }
            return 0.5;
        }
        for (Player o : me.getOpponents()) {
            if (o.hasLost()) {
                return 1.0;
            }
        }
        return Double.NaN;
    }

    /**
     * C5: replace the static leaf values of one decision by the served P(win). One request carries every
     * non-terminal leaf, world-major then candidate-minor (a fixed order, so the service is bit-identical). Terminal
     * play-outs score 1 / 0 / 0.5. On any failure the decision keeps Forge's static values (all of them, so a
     * decision never mixes the two scales) and the fallback is counted.
     */
    private void modelLeaves(Game live, Player me, double[][] values, Rollout[][] outs, boolean[] ok, int k) {
        final int n = values.length;
        final JsonArray leaves = new JsonArray();
        final List<int[]> at = new ArrayList<>();
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                Rollout r = outs[c][w];
                if (ok[c] && r != null && r.ok && r.leaf != null) {
                    leaves.add(r.leaf);
                    at.add(new int[] {c, w});
                }
            }
        }
        double[] p = null;
        if (leaves.size() > 0) {
            final JsonObject req = new JsonObject();
            req.addProperty("schema", ModelClient.REQUEST_SCHEMA);
            req.addProperty("seat", forge.bench.StateEncoder.playerIndex(live, me));
            req.addProperty("startingSeat", live.getStartingPlayer() == null ? -1
                    : forge.bench.StateEncoder.playerIndex(live, live.getStartingPlayer()));
            final JsonArray mull = new JsonArray();
            for (Player pl : live.getPlayers()) {
                mull.add(pl.getStats().getMulliganCount());
            }
            req.add("mulligans", mull);
            req.add("deck", deckOf(me));
            req.add("leaves", leaves);
            final long t = System.nanoTime();
            p = model.score(req, leaves.size());
            stats.modelNanos += System.nanoTime() - t;
            if (p == null) {
                stats.modelFallbacks++;
                stats.modelLastError = model.lastError;
                System.err.println("[lookahead] model leaf failed, static fallback: " + model.lastError);
                return;
            }
            stats.modelCalls++;
            stats.modelLeaves += leaves.size();
            stats.modelDigest = model.digestHex();
            stats.modelCheckpoint = model.checkpointSha256;
            stats.modelUnknownCards = model.unknownCards;
        }
        for (int w = 0; w < k; w++) {
            for (int c = 0; c < n; c++) {
                Rollout r = outs[c][w];
                if (ok[c] && r != null && r.ok && !Double.isNaN(r.pTerminal)) {
                    values[c][w] = r.pTerminal;
                }
            }
        }
        for (int i = 0; i < at.size(); i++) {
            values[at.get(i)[0]][at.get(i)[1]] = p[i];
        }
    }

    /** The seat's registered main deck as {name: copies} (fixed for the game). */
    private JsonObject deckOf(Player me) {
        if (modelDeck == null) {
            final JsonObject d = new JsonObject();
            forge.deck.Deck deck = me.getRegisteredPlayer() == null ? null : me.getRegisteredPlayer().getDeck();
            if (deck != null && deck.has(forge.deck.DeckSection.Main)) {
                final java.util.TreeMap<String, Integer> byName = new java.util.TreeMap<>();
                for (Map.Entry<forge.item.PaperCard, Integer> e : deck.get(forge.deck.DeckSection.Main)) {
                    byName.merge(e.getKey().getName(), e.getValue(), Integer::sum);
                }
                for (Map.Entry<String, Integer> e : byName.entrySet()) {
                    d.addProperty(e.getKey(), e.getValue());
                }
            }
            modelDeck = d;
        }
        return modelDeck;
    }

    static double value(Game g, Player me) {
        if (g.isGameOver() || !me.isInGame()) {
            if (me.hasWon()) {
                return TERMINAL;
            }
            if (me.hasLost() || !me.isInGame()) {
                return -TERMINAL;
            }
            return 0;
        }
        for (Player o : me.getOpponents()) {
            if (o.hasLost()) {
                return TERMINAL;
            }
        }
        return new GameStateEvaluator().getStaticScore(g, me).value;
    }

    private static Field fPrio, fFirst, fGive;

    static synchronized void initFields() {
        if (fPrio != null) {
            return;
        }
        try {
            fPrio = PhaseHandler.class.getDeclaredField("pPlayerPriority");
            fFirst = PhaseHandler.class.getDeclaredField("pFirstPriority");
            fGive = PhaseHandler.class.getDeclaredField("givePriorityToPlayer");
            fPrio.setAccessible(true);
            fFirst.setAccessible(true);
            fGive.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The copy resumes exactly where the live seat stands: it holds priority, it acted first. */
    static void givePriority(PhaseHandler ph, Player p) {
        initFields();
        try {
            fPrio.set(ph, p);
            fFirst.set(ph, p);
            fGive.setBoolean(ph, true);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ belief

    /**
     * Re-draw what the searching seat cannot see: each opponent's hand (keeping the cards it
     * can see) together with their library, and our own library order. Sizes are preserved.
     * The belief is uniform over the opponent's registered deck remainder, the same prior
     * the mtgx belief sampler starts from.
     */
    static void resample(Game live, Player liveMe, Game g, Player me, Random rng) {
        final PlayerView myView = liveMe.getView();
        for (Player opp : me.getOpponents()) {
            Player liveOpp = (Player) live.getPlayer(opp.getId());
            List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
            List<Card> lib = new ArrayList<>(opp.getCardsIn(ZoneType.Library));
            List<Card> keep = new ArrayList<>();
            List<Card> pool = new ArrayList<>(lib);
            for (Card c : hand) {
                Card lc = live.findById(c.getId());
                if (lc != null && lc.getView().canBeShownTo(myView)) {
                    keep.add(c);
                } else {
                    pool.add(c);
                }
            }
            // Known library cards (revealed tops) stay where they are.
            List<Card> libKnownTop = new ArrayList<>();
            List<Card> liveLib = liveOpp == null ? Collections.emptyList() : new ArrayList<>(liveOpp.getCardsIn(ZoneType.Library));
            for (Card lc : liveLib) {
                if (lc.getView().canBeShownTo(myView)) {
                    Card cc = g.findById(lc.getId());
                    if (cc != null) {
                        libKnownTop.add(cc);
                        pool.remove(cc);
                        continue;
                    }
                }
                break;
            }
            Collections.shuffle(pool, rng);
            int need = hand.size() - keep.size();
            List<Card> newHand = new ArrayList<>(keep);
            newHand.addAll(pool.subList(0, need));
            List<Card> newLib = new ArrayList<>(libKnownTop);
            newLib.addAll(pool.subList(need, pool.size()));
            opp.getZone(ZoneType.Hand).setCards(newHand);
            opp.getZone(ZoneType.Library).setCards(newLib);
        }
        // Our own library: keep the known top (scry/reveal), shuffle the rest.
        List<Card> myLib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        List<Card> liveMyLib = new ArrayList<>(liveMe.getCardsIn(ZoneType.Library));
        int known = 0;
        for (Card lc : liveMyLib) {
            if (lc.getView().canBeShownTo(myView)) {
                known++;
            } else {
                break;
            }
        }
        List<Card> rest = new ArrayList<>(myLib.subList(known, myLib.size()));
        Collections.shuffle(rest, rng);
        List<Card> nl = new ArrayList<>(myLib.subList(0, known));
        nl.addAll(rest);
        me.getZone(ZoneType.Library).setCards(nl);
    }

    // ------------------------------------------------------------------ probe

    private void probeExtras(JsonObject p, Game live, Player liveMe, List<Cand> cands, SpellAbility defSa, long decisionSeed, int turn) {
        // (1) Copy cost and copy fidelity, no resampling.
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(decisionSeed, 99)));
        AiCache.openScope();
        final Object prevIds3 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            JsonArray copyMs = new JsonArray();
            String liveFp = fingerprint(live);
            boolean fpSame = true;
            boolean scoreSame = true;
            GameStateEvaluator ev = new GameStateEvaluator();
            int liveScore = ev.getStaticScore(live, liveMe).value;
            for (int i = 0; i < 3; i++) {
                long a = System.nanoTime();
                GameCopier copier = new GameCopier(live, true);
                Game g = copier.makeCopy();
                copyMs.add((System.nanoTime() - a) / 1e6);
                Player me = (Player) copier.find(liveMe);
                fpSame &= liveFp.equals(fingerprint(g));
                scoreSame &= liveScore == ev.getStaticScore(g, me).value;
                if (i == 0 && !liveFp.equals(fingerprint(g))) {
                    p.addProperty("copyFpDiff", diff(liveFp, fingerprint(g)));
                }
            }
            p.add("copyMs", copyMs);
            p.addProperty("copyFingerprintSame", fpSame);
            p.addProperty("copyStaticScoreSame", scoreSame);
            // Resampling cost.
            long a = System.nanoTime();
            GameCopier copier = new GameCopier(live, true);
            Game g = copier.makeCopy();
            long b = System.nanoTime();
            resample(live, liveMe, g, (Player) copier.find(liveMe), new Random(1));
            p.addProperty("resampleMs", (System.nanoTime() - b) / 1e6);
            p.addProperty("copyPlusResampleMs", (System.nanoTime() - a) / 1e6);
            Player gm = (Player) copier.find(liveMe);
            for (Player o : gm.getOpponents()) {
                Player lo = live.getPlayer(o.getId());
                p.addProperty("oppHandChangedByResample", !names(lo.getCardsIn(ZoneType.Hand)).equals(names(o.getCardsIn(ZoneType.Hand))));
            }
        } catch (RuntimeException e) {
            p.addProperty("copyProbeError", e.toString());
        } finally {
            AiCache.closeScope();
            forge.util.IdScope.install(prevIds3);
            MyRandom.setThreadRandom(prev);
        }

        // (2) Determinism: candidate 0, world 0, twice.
        Rollout r1 = rollout(live, liveMe, cands.get(0), defSa, mix(decisionSeed, 1000), cfg.resample, true);
        Rollout r2 = rollout(live, liveMe, cands.get(0), defSa, mix(decisionSeed, 1000), cfg.resample, true);
        p.addProperty("detValueSame", r1.value == r2.value);
        p.addProperty("detFingerprintSame", r1.fingerprint != null && r1.fingerprint.equals(r2.fingerprint));
        p.addProperty("rolloutMs", r1.rolloutNanos / 1e6);
        p.addProperty("rolloutCopyMs", r1.copyNanos / 1e6);
        p.addProperty("rolloutSteps", r1.steps);

        // (3) Forge's own simulation AI, same position, its cost per decision (depth/budget as configured).
        final Random prev2 = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(decisionSeed, 98)));
        AiCache.openScope();
        final Object prevIds4 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            GameCopier copier = new GameCopier(live, true);
            Game g = copier.makeCopy();
            Player me = (Player) copier.find(liveMe);
            long sims0 = SimSearchBudget.getLifetimeSimulations();
            long a = System.nanoTime();
            SpellAbility simSa = new SpellAbilityPicker(me).chooseSpellAbilityToPlay(null);
            p.addProperty("simAiMs", (System.nanoTime() - a) / 1e6);
            p.addProperty("simAiSims", SimSearchBudget.getLifetimeSimulations() - sims0);
            p.addProperty("simAiChoice", simSa == null ? "pass" : new Cand(simSa, false).label);
        } catch (RuntimeException e) {
            p.addProperty("simAiError", e.toString());
        } finally {
            AiCache.closeScope();
            forge.util.IdScope.install(prevIds4);
            MyRandom.setThreadRandom(prev2);
        }

        // (4) Fidelity vs the live continuation (shadow mode, once per game, our own turn >= 3):
        // a TRUTH rollout (no resampling) of Forge's answer with stream s; then the live game is
        // put on stream s and watched to the same turn. Same code, same stream: any difference
        // is copy infidelity (or per-turn AI memory the copy does not carry).
        if (cfg.shadow && cfg.fidelity && fidelityArmed && turn >= 3 && liveWatch == null) {
            fidelityArmed = false;
            long s = mix(decisionSeed, 4242);
            Rollout tr = rolloutTruthWithGlobalStream(live, liveMe, cands.get(0), defSa, s);
            p.addProperty("fidelityRolloutOk", tr.ok);
            p.addProperty("fidelityTurn", live.getPhaseHandler().getTurn() + cfg.horizonTurns);
            liveWatch = new FidelityWatch(live.getPhaseHandler().getTurn() + cfg.horizonTurns, live, tr.fingerprint, p);
            live.subscribeToEvents(liveWatch);
            MyRandom.setRandom(new Random(s));
        }
    }

    /** A truth rollout that mimics the live game's RNG use: the copy runs on stream s from the first step. */
    private Rollout rolloutTruthWithGlobalStream(Game live, Player liveMe, Cand c, SpellAbility defSa, long s) {
        final Rollout r = new Rollout();
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new Random(mix(s, 1)));
        AiCache.openScope();
        final Object prevIds5 = forge.util.IdScope.capture();
        forge.util.IdScope.open();
        try {
            GameCopier copier = new GameCopier(live, true);
            Game g = copier.makeCopy();
            Player me = (Player) copier.find(liveMe);
            List<SpellAbility> first = null;
            if (!c.pass) {
                SpellAbility sa = prepare(g, me, c, defSa, copier);
                if (sa == null) {
                    r.ok = false;
                    return r;
                }
                first = new ArrayList<>();
                first.add(sa);
            }
            me.dangerouslySetController(new ScriptedFirst(g, me, me.getController().getLobbyPlayer(), first));
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new RolloutAi(g, o, o.getController().getLobbyPlayer()));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            givePriority(ph, me);
            final TurnWatch watch = new TurnWatch(ph.getTurn() + cfg.horizonTurns, me);
            g.subscribeToEvents(watch);
            MyRandom.setThreadRandom(new Random(s));
            int steps = 0;
            while (!g.isGameOver() && !watch.reached && steps < cfg.maxSteps) {
                ph.mainLoopStep();
                steps++;
            }
            r.fingerprint = watch.reached ? watch.fingerprint : ("END:" + fingerprint(g));
        } catch (RuntimeException e) {
            r.ok = false;
        } finally {
            AiCache.closeScope();
            forge.util.IdScope.install(prevIds5);
            MyRandom.setThreadRandom(prev);
        }
        return r;
    }

    public static final class FidelityWatch {
        final int turn;
        final Game live;
        final String expected;
        final JsonObject into;
        boolean done = false;

        FidelityWatch(int turn, Game live, String expected, JsonObject into) {
            this.turn = turn;
            this.live = live;
            this.expected = expected;
            this.into = into;
        }

        @Subscribe
        public void on(GameEventTurnBegan e) {
            if (!done && e.turnNumber() >= turn) {
                done = true;
                String got = fingerprint(live);
                into.addProperty("fidelitySame", got.equals(expected));
                if (expected != null && !got.equals(expected)) {
                    into.addProperty("fidelityDiff", diff(got, expected));
                }
            }
        }
    }

    /** Called by the runner at game end: a fidelity watch that never fired is recorded as such. */
    public void finishGame(Game live) {
        if (liveWatch != null && !liveWatch.done) {
            liveWatch.done = true;
            String got = "END:" + fingerprint(live);
            liveWatch.into.addProperty("fidelitySame", got.equals(liveWatch.expected));
            liveWatch.into.addProperty("fidelityEndedBeforeTurn", true);
            if (!got.equals(liveWatch.expected)) {
                liveWatch.into.addProperty("fidelityDiff", diff(got, liveWatch.expected));
            }
        }
    }

    // ------------------------------------------------------------------ util

    private static String liveProbe(Game live, SpellAbility defSa) {
        StringBuilder sb = new StringBuilder(fingerprint(live));
        if (defSa != null) {
            for (SpellAbility s = defSa; s != null; s = s.getSubAbility()) {
                sb.append("defTargets=").append(s.usesTargeting() ? s.getTargets().toString() : "-").append('\n');
            }
        }
        return sb.toString();
    }

    private static void checkLive(String where, String before, Game live, SpellAbility defSa, int index) {
        String after = liveProbe(live, defSa);
        if (!after.equals(before)) {
            System.err.println("[lookahead] LIVE MUTATED after " + where + " at decision " + index + ":\n" + diff(before, after));
        }
    }

    static List<String> names(Iterable<Card> cs) {
        List<String> l = new ArrayList<>();
        for (Card c : cs) {
            l.add(c.getName());
        }
        Collections.sort(l);
        return l;
    }

    /** A position fingerprint: public and private zones by name, life, counters, board state. */
    public static String fingerprint(Game g) {
        StringBuilder sb = new StringBuilder();
        sb.append("T").append(g.getPhaseHandler().getTurn()).append(' ').append(g.getPhaseHandler().getPhase()).append('\n');
        for (Player p : g.getPlayers()) {
            sb.append("P").append(p.getId()).append(" life=").append(p.getLife())
                    .append(" poison=").append(p.getPoisonCounters())
                    .append(" lib=").append(p.getCardsIn(ZoneType.Library).size())
                    .append(" lost=").append(p.hasLost()).append('\n');
            sb.append(" hand=").append(names(p.getCardsIn(ZoneType.Hand))).append('\n');
            sb.append(" gy=").append(names(p.getCardsIn(ZoneType.Graveyard))).append('\n');
            sb.append(" exile=").append(names(p.getCardsIn(ZoneType.Exile))).append('\n');
            List<String> bf = new ArrayList<>();
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                StringBuilder cb = new StringBuilder(c.getName());
                cb.append(c.isTapped() ? "/T" : "/U");
                if (c.isCreature()) {
                    cb.append('/').append(c.getNetPower()).append('/').append(c.getNetToughness()).append("/d").append(c.getDamage());
                }
                Map<String, Integer> cn = new TreeMap<>();
                for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
                    cn.put(String.valueOf(e.getElement()), e.getCount());
                }
                if (!cn.isEmpty()) {
                    cb.append(cn);
                }
                bf.add(cb.toString());
            }
            Collections.sort(bf);
            sb.append(" bf=").append(bf).append('\n');
        }
        return sb.toString();
    }

    static String diff(String a, String b) {
        if (a == null || b == null) {
            return "null fingerprint";
        }
        String[] la = a.split("\n"), lb = b.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(la.length, lb.length); i++) {
            String x = i < la.length ? la[i] : "", y = i < lb.length ? lb[i] : "";
            if (!x.equals(y)) {
                sb.append("- ").append(x).append("\n+ ").append(y).append('\n');
            }
        }
        return sb.length() > 1500 ? sb.substring(0, 1500) : sb.toString();
    }

    static long mix(long a, long b) {
        long x = a * 0x9E3779B97F4A7C15L + b;
        x ^= (x >>> 30);
        x *= 0xBF58476D1CE4E5B9L;
        x ^= (x >>> 27);
        x *= 0x94D049BB133111EBL;
        x ^= (x >>> 31);
        return x;
    }
}
