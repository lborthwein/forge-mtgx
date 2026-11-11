package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import forge.ai.AiCache;
import forge.ai.ComputerUtilAbility;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.game.Game;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * sdcc-whatif-1111 (off unless a spec's {@code choiceScorer.whatIf} or a measuring tool asks): a "what-if" value for the
 * options of a card choice (a tutor's target, a discard, a put-back, a kept hand), from the engine search alone. Each
 * option's resulting position is played on as a goldfish: in each of {@code worlds} sampled orders of the seat's unknown
 * library, for {@code horizon} of our own turns, the engine search ({@link ForcedWinEngine#search}, with the solver
 * config's engine keys, a small node allowance) is asked at our main phase whether a kill is there; when it is not, the
 * turn is developed (a land drop, or Forge AI's own turn without an attack) and passed. The value is the mean over
 * worlds of {@code gamma^t}, t the own turn the kill was found on (0 = none inside the horizon).
 *
 * <p>Honest about hidden cards: the seat's library below its known top is re-ordered per world by a hash of the world's
 * seed and the card's id (the real order is never read; two options that differ by one moved card see the same
 * draws); the opponent's unseen hand is taken out of the game, and the opponent passes: a goldfish, optimistic by
 * construction (no counterspell, no removal, no clock). No card names, no route tables: the engine finds a kill or it
 * does not. Never a policy input: an answer and a label only.
 */
public final class WhatIf {
    private WhatIf() {
    }

    /** The budgets (spec keys of {@code choiceScorer.whatIf}). */
    public static final class Opts {
        /** K: sampled orders of our unknown library. */
        public int worlds = 3;
        /** T: our own turns, the first being the one the option's position stands in (or our next one). */
        public int horizon = 4;
        /** Engine nodes of one probe, and its allowance on the budget clock, ms. */
        public int nodes = 60;
        public long probeMs = 1500;
        /** The whole ask's allowance on the budget clock, ms: past it no further stage starts. */
        public long askMs = 60_000;
        public double gamma = 0.8;
        /** A probe also searches lines that pass the turn once (the engine's own two-turn lines). */
        public boolean pass = false;
        /** What the seat does with a turn that has no kill: "land" (a land drop) or "ai" (Forge AI's turn, no attack). */
        public String dev = "ai";
        /** Main-loop steps of one advance (to our next main phase), at most. */
        public int maxSteps = 600;
        public long seed;
    }

    /** One option's position: the applied copy itself (never played on; every world is a copy of it). */
    public static final class Snapshot {
        final Game g;
        final Player me;
        final Object ids;
        final int knownTop;
        final int prioSeat;
        final boolean pregame;
        public String fail;

        Snapshot(final Game g, final Player me, final Object ids, final int knownTop, final int prioSeat,
                final boolean pregame) {
            this.g = g;
            this.me = me;
            this.ids = ids;
            this.knownTop = knownTop;
            this.prioSeat = prioSeat;
            this.pregame = pregame;
        }

        /** The position as a Forge puzzle state (seat keys p0 / p1), for stored-position tools. Never throws (null). */
        public String dump() {
            try {
                final GameState st = new GameState();
                st.initFromGame(g);
                return st.toString();
            } catch (RuntimeException | StackOverflowError e) {
                return null;
            }
        }

        public int seat() {
            return g.getRegisteredPlayers().indexOf(me);
        }

        public int knownTop() {
            return knownTop;
        }

        public boolean pregame() {
            return pregame;
        }
    }

    /**
     * The position {@code g} stands in, as an option's snapshot. Call it inside the id scope {@code g} was made in (a
     * {@link ChoiceApply} leaf): the scope's counters are kept for the worlds. {@code knownTop}: how many cards on
     * top of our library the seat knows (they stay in place in every world). {@code pregame}: an opening-hand position
     * (the worlds start at our first main phase).
     */
    public static Snapshot snapshot(final Game g, final Player me, final int knownTop, final boolean pregame) {
        final Player prio = g.getPhaseHandler().getPriorityPlayer();
        return new Snapshot(g, me, ForcedWinEngine.cloneScope(forge.util.IdScope.capture()), Math.max(0, knownTop),
                prio == null ? -1 : g.getRegisteredPlayers().indexOf(prio), pregame);
    }

    /** One option's result. */
    public static final class Result {
        /** Mean over the finished worlds of gamma^(kill turn); NaN = not evaluated. */
        public double value = Double.NaN;
        /** Share of the finished worlds with a kill inside the horizon. */
        public double p = Double.NaN;
        public int worlds, kills, probes, bestTurn = -1;
        public long nodes, copies, steps, nanos;
        /** The earliest kill's line (the engine's own text). */
        public String line;
        public String fail;
        /** Per world: the own turn of its kill (-1 none), and the own turns it was played for. */
        public final List<Integer> killTurns = new ArrayList<>();
        public final List<Integer> turnsRun = new ArrayList<>();
        /** Probes stopped by the node cap or the clock (the search did not exhaust). */
        public int capped;
    }

    /** The whole ask's result. */
    public static final class Ask {
        public final List<Result> results = new ArrayList<>();
        /** Stages every option finished (a stage = one own turn of one world), and whether the budget cut the rest. */
        public int stages;
        public boolean cut;
        public long nanos, workNanos;
        public int probes;
        public long nodes;
    }

    private static final class World {
        Game g;
        Player me;
        Object ids;
        int turn;
        int killTurn = -1;
        boolean dead, started;
        String line;
    }

    /** Our seat between probes: rests at our main phase with an empty stack; develops the turn it was given. */
    private static final class DevAi extends ForcedWinEngine.GuardedAi {
        final boolean first;
        final String dev;
        final int fromTurn;
        boolean rest, landTried;

        DevAi(final Game g, final Player p, final forge.LobbyPlayer lp, final boolean first, final String dev,
                final int fromTurn) {
            super(g, p, lp);
            this.first = first;
            this.dev = dev;
            this.fromTurn = fromTurn;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            ForcedWinEngine.checkWall(wall, "whatif priority");
            final Game g = getGame();
            final PhaseHandler ph = g.getPhaseHandler();
            final Player me = getPlayer();
            final boolean ourMain = g.getStack().isEmpty() && ph.isPlayerTurn(me)
                    && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
            if (first) {
                rest = ourMain;
                return null;
            }
            if (g.getStack().isEmpty() && ph.isPlayerTurn(me) && ph.is(PhaseType.MAIN1) && ph.getTurn() > fromTurn) {
                rest = true;
                return null;
            }
            if (ph.getTurn() != fromTurn || !ph.isPlayerTurn(me)) {
                return null;
            }
            if ("ai".equals(dev)) {
                return super.chooseSpellAbilityToPlay();
            }
            if (ourMain && !landTried) {
                landTried = true;
                final CardCollection lands = ComputerUtilAbility.getAvailableLandsToPlay(g, me);
                if (lands != null && !lands.isEmpty()) {
                    Card best = null;
                    int bestScore = -1;
                    for (Card c : lands) {
                        if (!c.isInZone(ZoneType.Hand)) {
                            continue;
                        }
                        int cols = 0;
                        for (SpellAbility ma : c.getManaAbilities()) {
                            cols += ma.getManaPart() == null ? 0 : Math.max(1, ma.getManaPart().getOrigProduced().split(" ").length);
                        }
                        if (cols > bestScore || cols == bestScore && c.getId() < best.getId()) {
                            best = c;
                            bestScore = cols;
                        }
                    }
                    if (best != null) {
                        final List<SpellAbility> abilities = best.getAllPossibleAbilities(me, true);
                        abilities.removeIf(sa -> !sa.isLandAbility());
                        if (!abilities.isEmpty()) {
                            return abilities;
                        }
                    }
                }
            }
            return null;
        }

        @Override
        void declareFirst(final Player attacker, final Combat combat) {
            // a goldfish turn does not attack: only a kill the engine search finds counts
        }
    }

    /** The other seat between probes: passes, never attacks (a goldfish has no opponent). */
    private static final class PassiveAi extends ForcedWinEngine.GuardedAi {
        PassiveAi(final Game g, final Player p, final forge.LobbyPlayer lp) {
            super(g, p, lp);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            ForcedWinEngine.checkWall(wall, "whatif priority");
            return null;
        }

        @Override
        void declareFirst(final Player attacker, final Combat combat) {
        }
    }

    /** The engine search's limits from the solver config: {@code ForcedWinSolver.engineLimits} without its play-out
     * verification, its belief re-draw and its own library shuffle (a world's order is the world's). */
    static ForcedWinEngine.Limits limits(final ForcedWinSolver.Config cfg, final Opts o) {
        final ForcedWinEngine.Limits el = new ForcedWinEngine.Limits();
        el.nodes = o.nodes;
        el.perSearchNodes = true;
        el.passTurn = o.pass;
        el.staleStatics = cfg.staleStatics;
        el.loopMacro = cfg.loopMacro;
        el.loopMacro3 = cfg.loopMacro3;
        el.triggerTargets = cfg.engineTriggerTargets;
        el.triggerVariants = cfg.triggerVariants;
        el.passScale = cfg.enginePassScale;
        el.longLoops = cfg.engineLongLoops;
        el.turnLimits = cfg.turnLimits;
        if (cfg.engineMoves) {
            el.oppTargets = true;
            el.floatMana = true;
            el.floatFirst = true;
            el.libraryTop = true;
        }
        if (cfg.engineLoopReach) {
            el.loopOutlets = true;
            el.looseCycles = true;
            el.outletNodes = cfg.engineOutletNodes;
            el.loopHoist = cfg.engineLoopHoist;
            el.loopBisect = cfg.engineLoopBisect;
        }
        el.loopCredit = cfg.engineLoopCredit;
        el.creditCap = cfg.engineLoopCreditCap;
        el.stalePlayerStatics = cfg.stalePlayerStatics;
        el.offBattlefieldLimits = cfg.offBattlefieldLimits;
        el.historyLki = cfg.historyLki;
        el.copyBackFace = cfg.copyBackFace;
        el.stackAbilityCopies = cfg.stackAbilityCopies;
        el.stackHistory = cfg.stackHistory;
        el.cycles = cfg.cycles;
        el.cac = cfg.cacPicks;
        el.cacVariants = cfg.cacVariants;
        el.cacCap = cfg.cacCap;
        el.pay = cfg.payRecord || cfg.enginePay;
        el.payVariants = cfg.payVariants;
        el.xChoice = cfg.engineX;
        el.xVariants = cfg.xVariants;
        el.costChoice = cfg.engineCostChoice;
        el.focus = cfg.engineFocus;
        el.ownKnownTop = -1; // the world's own order (sampled in start), never the real one
        el.ownCanonical = true; // sdcc-leak-1111: their hidden cards in id order before the root's shuffle
        return el;
    }

    /**
     * The what-if values of {@code options} (one {@link Result} each, in order). Stages run option by option: every
     * option plays own turn t of world w before any plays the next, so a budget cut leaves every option with the same
     * worlds and turns. Runs in its own random stream, id scope and AI cache scope; the snapshots are not changed.
     * Never throws.
     */
    public static Ask evaluate(final List<Snapshot> options, final ForcedWinSolver.Config cfg, final Opts o) {
        final Ask ask = new Ask();
        final long real0 = System.nanoTime();
        final long t0 = forge.game.WorkClock.nanoTime();
        final long askEnd = o.askMs > 0 ? t0 + o.askMs * 1_000_000L : Long.MAX_VALUE;
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(o.seed, 0x77A7)));
        AiCache.openScope();
        try {
            final int n = options.size();
            final java.util.Set<String> killers = new java.util.HashSet<>();
            for (int i = 0; i < n; i++) {
                final Result r = new Result();
                r.fail = options.get(i) == null ? "noSnapshot" : options.get(i).fail;
                ask.results.add(r);
            }
            final int K = Math.max(1, o.worlds), T = Math.max(1, o.horizon);
            // turn 0 of world 0 stands for turn 0 of every world when probes do not pass (nothing was drawn yet)
            final boolean share0 = !o.pass;
            final int[] kill0 = new int[n];
            final String[] line0 = new String[n];
            java.util.Arrays.fill(kill0, -2);
            boolean cutNow = false;
            for (int w = 0; w < K && !cutNow; w++) {
                final World[] ws = new World[n];
                final int[] killTurn = new int[n];
                java.util.Arrays.fill(killTurn, -1);
                int done = 0;
                for (int t = 0; t < T && !cutNow; t++) {
                    // a stage: own turn t of world w, for every option still open
                    final long[] nodes = new long[n], copies = new long[n], steps = new long[n];
                    final int[] probes = new int[n], capped = new int[n];
                    final int[] found = new int[n];
                    final String[] lines = new String[n];
                    final String[] fails = new String[n];
                    java.util.Arrays.fill(found, -1);
                    boolean any = false;
                    for (int i = 0; i < n; i++) {
                        final Result r = ask.results.get(i);
                        if (r.fail != null || killTurn[i] >= 0 || ws[i] != null && ws[i].dead) {
                            continue;
                        }
                        if (forge.game.WorkClock.nanoTime() >= askEnd) {
                            cutNow = true; // the open stage is dropped whole: every option keeps the same stages
                            break;
                        }
                        any = true;
                        if (ws[i] == null) {
                            ws[i] = start(options.get(i), LookaheadSearch.mix(o.seed, 0x5EED00L + w), o, askEnd);
                            if (ws[i].dead && !ws[i].started) {
                                if (w == 0) {
                                    fails[i] = ws[i].line == null ? "start" : ws[i].line;
                                }
                                continue;
                            }
                        } else if (!advance(ws[i], false, o, askEnd)) {
                            continue;
                        }
                        if (ws[i].dead) {
                            continue;
                        }
                        if (t == 0 && share0 && w > 0 && kill0[i] != -2) {
                            if (kill0[i] >= 0) {
                                found[i] = kill0[i];
                                lines[i] = line0[i];
                            }
                            continue;
                        }
                        final ForcedWinEngine.Limits lim = limits(cfg, o);
                        lim.focusKillers = killers;
                        lim.rootIds = ws[i].ids;
                        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
                        final long now = forge.game.WorkClock.nanoTime();
                        final long end = Math.min(askEnd, o.probeMs > 0 ? now + o.probeMs * 1_000_000L : Long.MAX_VALUE);
                        final Line l = ForcedWinEngine.search(ws[i].g, ws[i].me, lim, st, end,
                                LookaheadSearch.mix(LookaheadSearch.mix(o.seed, 0x9B0BEL + w * 64L + t), i));
                        probes[i]++;
                        nodes[i] += st.nodes;
                        copies[i] += st.copies;
                        steps[i] += st.steps;
                        if (st.capStops > 0 || st.deadlineStops > 0) {
                            capped[i]++;
                        }
                        if (l != null) {
                            found[i] = t + l.winTurn;
                            lines[i] = String.valueOf(l);
                        }
                        if (t == 0 && share0 && w == 0) {
                            kill0[i] = l == null ? -1 : found[i];
                            line0[i] = lines[i];
                        }
                    }
                    if (cutNow) {
                        ask.cut = true;
                        break;
                    }
                    // the stage is whole: commit it
                    for (int i = 0; i < n; i++) {
                        final Result r = ask.results.get(i);
                        r.probes += probes[i];
                        r.nodes += nodes[i];
                        r.copies += copies[i];
                        r.steps += steps[i];
                        r.capped += capped[i];
                        ask.probes += probes[i];
                        ask.nodes += nodes[i];
                        if (fails[i] != null && r.fail == null) {
                            r.fail = fails[i];
                        }
                        if (found[i] >= 0 && killTurn[i] < 0) {
                            killTurn[i] = found[i];
                            if (r.bestTurn < 0 || found[i] < r.bestTurn) {
                                r.bestTurn = found[i];
                                r.line = lines[i];
                            }
                        }
                    }
                    ask.stages++;
                    done = t + 1;
                    if (!any) {
                        break;
                    }
                }
                if (done > 0) {
                    // the world counts with the own turns every option played in it (all of them unless the budget cut it)
                    for (int i = 0; i < n; i++) {
                        final Result r = ask.results.get(i);
                        if (r.fail == null) {
                            r.worlds++;
                            r.killTurns.add(killTurn[i]);
                            r.turnsRun.add(done);
                            if (killTurn[i] >= 0) {
                                r.kills++;
                            }
                        }
                    }
                }
            }
            for (Result r : ask.results) {
                if (r.fail == null && r.worlds > 0) {
                    double sum = 0;
                    for (int k : r.killTurns) {
                        sum += k < 0 ? 0 : Math.pow(o.gamma, k);
                    }
                    r.value = sum / r.worlds;
                    r.p = r.kills / (double) r.worlds;
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            ForcedWinSolver.receipt("FORCED_WIN whatif error " + e);
            for (Result r : ask.results) {
                if (Double.isNaN(r.value) && r.fail == null) {
                    r.fail = "error";
                }
            }
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
            ask.workNanos = forge.game.WorkClock.nanoTime() - t0;
            ask.nanos = System.nanoTime() - real0;
        }
        return ask;
    }

    /** A world of an option: a copy of its snapshot, the unknown library in the world's order, at our first main phase. */
    private static World start(final Snapshot s, final long seed, final Opts o, final long wall) {
        final World w = new World();
        try {
            forge.util.IdScope.install(ForcedWinEngine.cloneScope(s.ids));
            if (!s.g.getStack().isEmpty() && GameCopier.stackUnsupported(s.g) != null) {
                w.dead = true;
                w.line = "stack";
                return w;
            }
            final Player prio;
            synchronized (s.g) {
                final GameCopier copier = new GameCopier(s.g, true);
                copier.setCopyFidelity(1);
                copier.setCopyPending(GameCopier.SOLVER_COPY_PENDING);
                ForcedWinEngine.copyStackIf(copier, s.g, true);
                w.g = copier.makeCopy();
                w.me = (Player) copier.find(s.me);
                prio = s.prioSeat < 0 ? null : w.g.getRegisteredPlayers().get(s.prioSeat);
                final Random rng = new Random(LookaheadSearch.mix(seed, 2));
                for (Player opp : w.me.getOpponents()) {
                    // their unseen hand leaves the game (the solver's unopposed world)
                    ForcedWinSolver.hideOrSample(s.g, s.me, (Player) s.g.getPlayer(opp.getId()), opp, true, rng, null, true);
                }
            }
            reorder(w.me, s.knownTop, seed);
            if (s.pregame) {
                ForcedWinProbe.toFirstMain(w.g, w.me);
            } else if (prio != null) {
                LookaheadSearch.givePriority(w.g.getPhaseHandler(), prio);
            }
            w.ids = ForcedWinEngine.cloneScope(forge.util.IdScope.capture());
            w.started = true;
            if (!s.pregame || !restsNow(w)) {
                advance(w, true, o, wall);
            }
            return w;
        } catch (RuntimeException | StackOverflowError e) {
            w.dead = true;
            w.line = "start:" + e.getClass().getSimpleName();
            return w;
        }
    }

    private static boolean restsNow(final World w) {
        final PhaseHandler ph = w.g.getPhaseHandler();
        return w.g.getStack().isEmpty() && ph.isPlayerTurn(w.me) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
    }

    /**
     * Our library below the known top in the world's order: a card's place is a hash of the world's seed and its id, so
     * the real order is never read and two options see the same draws.
     */
    public static void reorder(final Player me, final int knownTop, final long seed) {
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final int k = Math.min(knownTop, lib.size());
        final List<Card> rest = new ArrayList<>(lib.subList(k, lib.size()));
        rest.sort(Comparator.comparingLong((Card c) -> LookaheadSearch.mix(seed, c.getId())).thenComparingInt(Card::getId));
        final List<Card> out = new ArrayList<>(lib.subList(0, k));
        out.addAll(rest);
        me.getZone(ZoneType.Library).setCards(out);
    }

    /**
     * Play the world on to our next rest: {@code first}: our first main phase with an empty stack, nothing played;
     * else the turn it stands in is developed, passed, and the world rests at our next first main phase. False (and the
     * world is dead) when the game ended or the rest was not reached.
     */
    private static boolean advance(final World w, final boolean first, final Opts o, final long wall) {
        try {
            forge.util.IdScope.install(ForcedWinEngine.cloneScope(w.ids));
            final PhaseHandler ph = w.g.getPhaseHandler();
            final DevAi ai = new DevAi(w.g, w.me, w.me.getController().getLobbyPlayer(), first, o.dev, ph.getTurn());
            ai.bounded(wall == Long.MAX_VALUE ? 0L : wall);
            w.me.dangerouslySetController(ai);
            for (Player p : w.g.getPlayers()) {
                if (p != w.me) {
                    p.dangerouslySetController(new PassiveAi(w.g, p, p.getController().getLobbyPlayer())
                            .bounded(wall == Long.MAX_VALUE ? 0L : wall));
                }
            }
            if (!first) {
                LookaheadSearch.givePriority(ph, w.me);
            }
            int steps = 0;
            while (!w.g.isGameOver() && !ai.rest && steps < o.maxSteps) {
                ph.mainLoopStep();
                steps++;
            }
            w.ids = ForcedWinEngine.cloneScope(forge.util.IdScope.capture());
            if (!ai.rest || w.g.isGameOver()) {
                w.dead = true;
                return false;
            }
            if (!first) {
                w.turn++;
            }
            return true;
        } catch (RuntimeException | StackOverflowError e) {
            w.dead = true;
            return false;
        }
    }

    /** The receipt of one ask (under {@code -Dforge.ai.forcedWin.receipts}): the options, their values and kill lines. */
    public static void receipt(final String tag, final List<String> labels, final Ask a, final Opts o) {
        final StringBuilder sb = new StringBuilder("FORCED_WIN whatif ").append(tag).append(" K=").append(o.worlds)
                .append(" T=").append(o.horizon).append(" nodes=").append(o.nodes).append(" dev=").append(o.dev)
                .append(o.pass ? " pass" : "").append(" stages=").append(a.stages).append(a.cut ? " CUT" : "")
                .append(" probes=").append(a.probes).append(" engineNodes=").append(a.nodes).append(" ms=")
                .append(a.nanos / 1_000_000).append(" workMs=").append(a.workNanos / 1_000_000)
                .append(" (goldfish: no opponent, their hand unknown)");
        ForcedWinSolver.receipt(sb.toString());
        for (int i = 0; i < a.results.size(); i++) {
            final Result r = a.results.get(i);
            ForcedWinSolver.receipt("FORCED_WIN whatif   option " + (i < labels.size() ? labels.get(i) : "#" + i) + " v="
                    + (Double.isNaN(r.value) ? "n/a" : String.valueOf(Math.round(r.value * 1000) / 1000.0)) + " kills=" + r.kills
                    + "/" + r.worlds + " killTurns=" + r.killTurns + " probes=" + r.probes + " nodes=" + r.nodes
                    + (r.capped > 0 ? " capped=" + r.capped : "") + (r.fail != null ? " fail=" + r.fail : "")
                    + (r.line != null ? " line(t" + r.bestTurn + ")=" + r.line : ""));
        }
    }
}
