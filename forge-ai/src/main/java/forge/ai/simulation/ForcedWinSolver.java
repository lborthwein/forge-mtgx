package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.ai.AiCache;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.ai.simulation.ForcedWinModel.Position;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Lane doomsday-solver-1012: a general forced-win solver for one Forge seat. Off unless a profile turns it on.
 *
 * <p><b>What it does.</b> At our main-phase priority with an empty stack it asks: can we force a win this turn or by
 * our next turn, and with what chance against an opponent that interacts? Three stages, cheapest first.</p>
 * <ol>
 * <li><b>Affordances</b> ({@link ForcedWinProbe}): what each of our cards does, learned by playing it in a sandbox
 * Forge copy. Cached per card name.</li>
 * <li><b>Width search</b> ({@link ForcedWinPlanner}): an exact-mana depth-first search over cast and draw sequences
 * of the affordance model, horizon this turn plus our next turn, with lazily filled ordered library choices. When it
 * finds no line (the common case) the decision costs a few milliseconds and the seat plays on as before.</li>
 * <li><b>Engine evaluation</b>: the top few lines are played in Forge game copies. World 0 is the opponent acting only
 * through its visible board (its hidden hand removed): P(win | unopposed). Worlds 1..K sample the opponent's hidden
 * hand from its deck remainder; in each, the opponent is Forge AI and, when the sampled hand holds a counterspell, also
 * an adversary that counters each of our spells in turn: the world's value is the worst of those answers, with the
 * stack war played by the engine (our follower counters back with its own counterspells). P(win | belief) is the mean.
 * The line with the best P(win | belief) is taken when it reaches the threshold.</li>
 * </ol>
 * <p>The live seat then follows the line step by step with the same follower its play-outs used, answers the choice
 * asks the line planned (picks, orders, put-backs, targets, no shuffle) and searches again when the line breaks.</p>
 *
 * <p>Determinism: the solver reads the live game and never advances its random stream or id counters (copies run in
 * their own scopes); its own randomness is seeded from the position.</p>
 */
public final class ForcedWinSolver {

    public static final class Config {
        public int worlds = 6;
        public int top = 3;
        public double threshold = 0.5;
        public int nodes = 30_000;
        public int horizon = 2;
        public long budgetMs = 8000;
        public boolean adversary = true;
        public boolean engine = true;
        public boolean defense = true;
        public int defenseWorlds = 4;
        public double counterDelta = 0.25;
        public int engineNodes = 250;
        public long engineMs = 4000;
        /**
         * solver-gaps-1015: the engine search may run until the decision's 8 s budget minus this reserve for its line's
         * evaluation (at least engineMs); the evaluation stops at the budget. A long storm loop (Underworld Breach, Brain
         * Freeze) needed about 140 engine nodes, 6 s under a 4-thread load.
         */
        public long evalReserveMs = 2000;
        public int maxSteps = 4000;
        public long seed = 20261012L;
        /**
         * EVAL only (solver-gaps-1015): a frame that starts mid-pile hands the seat its library as built by the seat (the
         * frame lost the seat's memory of it). Off by default; never on in natural games.
         */
        public boolean frameMemory;
        /**
         * solver-gaps-1015 (off by default): the width search searches the payment of its own casts (a bounded set of
         * payment orders: greedy, one-shot sources first, most flexible first, keep each colour, Phyrexian in life)
         * instead of paying greedily.
         */
        public boolean paySearch;
        /** The forced-win solver (width search, engine search, defense). False when only Part B is on. */
        public boolean forced = true;
        /**
         * Part B (solver-gaps-1015): likely-win lines, off by default. Engine-search lines that are not forced are scored
         * by P(win) over belief worlds against Forge AI rollouts of both seats, against the seat's own play.
         */
        public boolean likely;
        public int likelyWorlds = 4;
        public int likelyTop = 2;
        /** The rollouts' horizon in turns (both seats' turns counted); a game still running there scores 0.5. */
        public int likelyCapTurns = 16;
        /**
         * With Part B on, the engine search leaves this much of the decision budget (the same reserve as for its line's
         * evaluation by default, so Part B does not shorten the forced-win search); the rollouts use what is left and
         * stop at the budget.
         */
        public long likelyReserveMs = 2000;
        /**
         * solver-gaps-1015: the decision's hard wall = its budget plus this grace. The budget's deadlines stop new work
         * (nodes, worlds); a step already running is cut only at the wall (engine moves, verification play-outs).
         */
        public long wallGraceMs = 2000;
        /**
         * search-bones-1015 (true = as before): false = the solver never acts on its own; it follows a line it was handed
         * ({@link #adopt}), counters back a counter of that line, answers the line's choice asks and proposes lines
         * ({@link #propose}), but runs no offense or defence of its own at a priority.
         */
        public boolean act = true;
        /** search-bones-1015 (c), off by default: the engine search's cycle table and REPEAT moves. */
        public boolean cycles = false;
        /**
         * search-bones-1015, off by default: the engine search's root copy shuffles our library below the known top
         * (pile memory), as the verification worlds do; without it the engine plays on our real library order, hidden
         * information (a line that needs the top card was found on the real order and taken when the worlds agreed).
         */
        public boolean hideOwnLibrary = false;
        /** search-bones-1015: the width search's wall-time cap per call, ms (1500 = PF5's). */
        public long planMs = 1500;
        /**
         * search-bones-1015 (d), off by default: lines are verified in the position as it stands (a search's world copy,
         * where every hidden card is a sample): one world, nothing hidden or re-drawn, the adversary (the other seat)
         * countering each of the line's spells in turn. P(unopposed) = the line wins with the other seat as Forge AI;
         * P(belief) = the worst over the adversary's counters.
         */
        public boolean exact = false;

        public static Config fromProps(java.util.function.Function<String, String> get) {
            final Config c = new Config();
            c.frameMemory = "true".equals(get.apply("frameMemory"));
            c.likely = "true".equals(get.apply("likely"));
            c.paySearch = "true".equals(get.apply("paySearch"));
            c.likelyWorlds = intOr(get.apply("likelyWorlds"), c.likelyWorlds);
            c.likelyTop = intOr(get.apply("likelyTop"), c.likelyTop);
            c.likelyCapTurns = intOr(get.apply("likelyCapTurns"), c.likelyCapTurns);
            c.likelyReserveMs = intOr(get.apply("likelyReserveMs"), (int) c.likelyReserveMs);
            c.wallGraceMs = intOr(get.apply("wallGraceMs"), (int) c.wallGraceMs);
            c.worlds = intOr(get.apply("worlds"), c.worlds);
            c.top = intOr(get.apply("top"), c.top);
            c.threshold = dblOr(get.apply("threshold"), c.threshold);
            c.nodes = intOr(get.apply("nodes"), c.nodes);
            c.horizon = intOr(get.apply("horizon"), c.horizon);
            c.budgetMs = intOr(get.apply("budgetMs"), (int) c.budgetMs);
            c.adversary = !"false".equals(get.apply("adversary"));
            c.engine = !"false".equals(get.apply("engine"));
            c.defense = !"false".equals(get.apply("defense"));
            c.defenseWorlds = intOr(get.apply("defenseWorlds"), c.defenseWorlds);
            c.counterDelta = dblOr(get.apply("counterDelta"), c.counterDelta);
            c.engineNodes = intOr(get.apply("engineNodes"), c.engineNodes);
            c.engineMs = intOr(get.apply("engineMs"), (int) c.engineMs);
            c.evalReserveMs = intOr(get.apply("evalReserveMs"), (int) c.evalReserveMs);
            c.act = !"false".equals(get.apply("act"));
            c.cycles = "true".equals(get.apply("cycles"));
            c.hideOwnLibrary = "true".equals(get.apply("hideOwnLibrary"));
            c.exact = "true".equals(get.apply("exact"));
            c.planMs = intOr(get.apply("planMs"), (int) c.planMs);
            return c;
        }

        private static int intOr(String s, int d) {
            try {
                return s == null || s.isEmpty() ? d : Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return d;
            }
        }

        private static double dblOr(String s, double d) {
            try {
                return s == null || s.isEmpty() ? d : Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return d;
            }
        }

        @Override
        public String toString() {
            return "worlds=" + worlds + " top=" + top + " threshold=" + threshold + " nodes=" + nodes + " horizon="
                    + horizon + " budgetMs=" + budgetMs + " adversary=" + adversary + " defense=" + defense + " engine=" + engine + " engineNodes="
                + engineNodes + " engineMs=" + engineMs + " forced=" + forced + " likely=" + likely + " likelyWorlds=" + likelyWorlds
                + " likelyTop=" + likelyTop + " likelyCapTurns=" + likelyCapTurns + " paySearch=" + paySearch;
        }
    }

    /** Receipts to stderr (diagnostics only): -Dforge.ai.forcedWin.receipts=true. */
    static final boolean RECEIPTS = Boolean.getBoolean("forge.ai.forcedWin.receipts");

    static void receipt(String s) {
        if (RECEIPTS) {
            System.err.println(s);
        }
    }

    /** Receipts name the seat's deck (diagnostics: which deck a decision belongs to). */
    private String tag() {
        try {
            return " deck=" + me.getRegisteredPlayer().getDeck().getName().replace(' ', '_') + " seat="
                    + me.getGame().getRegisteredPlayers().indexOf(me.getRegisteredPlayer()) + " thr="
                    + Thread.currentThread().getId();
        } catch (RuntimeException e) {
            return " deck=?";
        }
    }

    static long mix(long a, long b) {
        return LookaheadSearch.mix(a, b);
    }

    // ------------------------------------------------------------------ per-seat state

    private final Player me;
    private final Config cfg;
    final ForcedWinModel.Reader reader;
    private Follower active;
    private String noLineKey;
    private int decisionIndex;
    /** Diagnostics: decisions asked, gated off by the width search, engine-evaluated, lines taken. */
    public int decisions, gated, evaluated, taken, replans;
    public long plannerNanos, engineNanos;
    public int copies;

    public ForcedWinSolver(Player me, Config cfg) {
        this(me, cfg, null);
    }

    /** A solver whose affordance reader is shared (null = its own): search-bones-1015 (d), and steps-back-1016 for
     *  another game's player reusing an existing solver's cache. */
    ForcedWinSolver(Player me, Config cfg, ForcedWinModel.Reader shared) {
        this.me = me;
        this.cfg = cfg;
        this.reader = shared != null ? shared : new ForcedWinModel.Reader();
        this.reader.seed = cfg.seed;
    }

    // ------------------------------------------------------------------ steps-back-1016: bench hooks and proofs

    /**
     * Bench-only observers (lane steps-back-1016; null = off, the default, and then nothing below runs). They read the
     * live game and the solver's verdicts; they never change a decision unless {@link #beforeDecide} asks the seat to
     * pass (a probe that ends the game).
     */
    public interface Hooks {
        /** At the start of every live decision of a solver seat; true = the seat passes at once (no search). */
        default boolean beforeDecide(Game live, Player me, ForcedWinSolver solver) {
            return false;
        }

        /** The live seat took a line (a proven win: P(unopposed) = 1, P(belief) at or above the threshold). */
        default void taken(Game live, Player me, Proof proof) {
        }
    }

    public static volatile Hooks HOOKS = null;

    /** A proven line, in plain terms (card ids of the game it was proven in). */
    public static final class Proof {
        public final String line;
        public final int winTurn;
        public final double pUnopposed, pBelief;
        public final boolean engine;
        /** The card ids the line's own steps play (casts, activations, land plays, floats), in step order. */
        public final List<Integer> stepHostIds = new ArrayList<>();
        public final List<String> stepHostNames = new ArrayList<>();
        /** Library cards the line picks (tutor picks, pile picks), by id. */
        public final List<Integer> libraryPickIds = new ArrayList<>();
        public final List<String> steps = new ArrayList<>();

        Proof(Line l, Eval e) {
            line = l.toString();
            winTurn = l.winTurn;
            pUnopposed = e == null ? -1 : e.pUnopposed;
            pBelief = e == null ? -1 : e.pBelief;
            engine = "engine".equals(l.how);
            for (Step s : l.steps) {
                steps.add(s.toString());
                if (s.pass || s.hostId < 0) {
                    continue;
                }
                stepHostIds.add(s.hostId);
                stepHostNames.add(s.hostName);
                libraryPickIds.addAll(s.pile);
                libraryPickIds.addAll(s.picks);
            }
        }
    }

    /** The last line offense took, with its engine evaluation. */
    private Eval lastTake;

    /**
     * steps-back-1016: does the solver prove a win from {@code live} for its seat (exactly what the seat's offense would
     * take here)? Fresh per call: no cached verdict, no active line. Null = no line taken.
     */
    public Proof prove(Game live) {
        active = null;
        noLineKey = null;
        engineTurn = -1;
        lastTake = null;
        final Line l = offense(live);
        return l == null ? null : new Proof(l, lastTake);
    }

    public Config config() {
        return cfg;
    }

    public boolean lineActive() {
        return active != null && !active.done;
    }

    /** A decision at our priority: act (sa), pass (null with acted), or no opinion. */
    public static final class Decision {
        public final boolean owned;
        public final SpellAbility action;

        Decision(boolean owned, SpellAbility action) {
            this.owned = owned;
            this.action = action;
        }

        static final Decision NONE = new Decision(false, null);
        static final Decision PASS = new Decision(true, null);
    }

    /** The live seat's priority: follow the active line, or look for one. Never throws. */
    public Decision decide(Game live) {
        try {
            return decide0(live);
        } catch (RuntimeException | StackOverflowError e) {
            receipt("FORCED_WIN error " + e);
            active = null;
            return Decision.NONE;
        }
    }

    private Decision decide0(Game live) {
        final Hooks hooks = HOOKS;
        if (hooks != null && hooks.beforeDecide(live, me, this)) {
            return Decision.PASS;
        }
        if (active != null && !active.done && !live.getStack().isEmpty()) {
            // a counter of our line's spell: counter it back when we can
            final SpellAbility guard = protect(live, me);
            if (guard != null) {
                receipt("FORCED_WIN protect turn=" + live.getPhaseHandler().getTurn() + " with=" + guard.getHostCard().getName());
                return new Decision(true, guard);
            }
        }
        if (active != null && !active.done) {
            final Follower.Next n = active.next(live, me);
            if (n.action != null) {
                return new Decision(true, n.action);
            }
            if (n.waiting) {
                return Decision.PASS;
            }
            if (n.broken) {
                receipt("FORCED_WIN broken" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " at=" + active.cursor + " "
                        + n.why);
                replans++;
                active = null;
                noLineKey = null;
            } else {
                // the line is played out: it has given up the turn or is waiting for its win to resolve
                return n.waitingEnd ? Decision.PASS : Decision.NONE;
            }
        }
        if (!cfg.act) {
            // search-bones-1015: a follower only (the search decides when to take a line)
            return Decision.NONE;
        }
        final PhaseHandler ph = live.getPhaseHandler();
        if (!live.getStack().isEmpty()) {
            // defense: the opponent's spell on top, when resolving it would give them a forced win
            return defendCounter(live);
        }
        final boolean ourMain = ph.isPlayerTurn(me) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
        final boolean theirEnd = !ph.isPlayerTurn(me) && ph.is(PhaseType.END_OF_TURN);
        if (!ourMain && !theirEnd) {
            return Decision.NONE;
        }
        final Line line = offense(live);
        if (line == null) {
            return Decision.NONE;
        }
        active = new Follower(line);
        taken++;
        if (hooks != null) {
            hooks.taken(live, me, new Proof(line, lastTake));
        }
        final Follower.Next n = active.next(live, me);
        if (n.action != null) {
            return new Decision(true, n.action);
        }
        active = null;
        return Decision.NONE;
    }

    // ------------------------------------------------------------------ pile memory (solver-gaps-1015)

    /**
     * Ids of our library cards whose position the seat knows: cards that moved into our library (we put them back, a
     * pile we chose, a card from a public zone) or whose order we chose. A shuffle of our library forgets them all.
     */
    final Set<Integer> placed = new HashSet<>();
    private boolean watching;

    /** Live-game listener (read-only): moves into our library and shuffles of it. */
    public static final class LibraryWatch {
        final ForcedWinSolver solver;

        LibraryWatch(ForcedWinSolver solver) {
            this.solver = solver;
        }

        @com.google.common.eventbus.Subscribe
        public void on(forge.game.event.GameEventShuffle e) {
            if (e.player() != null && e.player().getId() == solver.me.getId()) {
                solver.placed.clear();
            }
        }

        @com.google.common.eventbus.Subscribe
        public void on(forge.game.event.GameEventCardChangeZone e) {
            if (e.card() != null && e.to() != null && e.to().zoneType() == ZoneType.Library && e.to().player() != null
                    && e.to().player().getId() == solver.me.getId()) {
                solver.placed.add(e.card().getId());
            }
        }
    }

    /** Start watching our library (once; the seat's first decision). */
    void watch(Game live) {
        if (watching) {
            return;
        }
        watching = true;
        live.subscribeToEvents(new LibraryWatch(this));
        if (cfg.frameMemory) {
            // EVAL only (frames mined mid-pile): the seat built its library top before the frame began
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                placed.add(c.getId());
            }
        }
    }

    /** Our seat chose the order of these cards going to our library (a pile, a put-back, a look at our top). */
    public void notePlaced(Iterable<Card> cards) {
        if (cards == null) {
            return;
        }
        for (Card c : cards) {
            if (c != null && c.getOwner() == me) {
                placed.add(c.getId());
            }
        }
    }

    /** The known top of our library: the longest prefix whose cards the seat placed; null when none. */
    List<Integer> knownTop(Game live) {
        watch(live);
        final List<Integer> out = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            if (!placed.contains(c.getId())) {
                break;
            }
            out.add(c.getId());
        }
        return out.isEmpty() ? null : out;
    }

    private int placedCount(Player p) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            if (!placed.contains(c.getId())) {
                break;
            }
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ offense: width search, then engine

    /** The position key the width search's verdict is cached under (same key, same verdict). */
    private String positionKey(Game live, Player me) {
        final StringBuilder sb = new StringBuilder();
        sb.append(live.getPhaseHandler().getTurn()).append(live.getPhaseHandler().getPhase()).append('|')
                .append(me.getLife()).append('|').append(me.getManaPool().totalMana()).append('|')
                .append(me.getLandsPlayedThisTurn()).append('|').append(live.getStack().getSpellsCastThisTurn().size())
                .append('|').append(me.getCardsIn(ZoneType.Library).size()).append('h');
        sb.append('k').append(placedCount(me)).append('|');
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            sb.append(c.getId()).append(',');
        }
        sb.append('b');
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            sb.append(c.getId()).append(c.isTapped() ? 't' : 'u').append(',');
        }
        for (Player o : me.getOpponents()) {
            sb.append('o').append(o.getLife()).append('/').append(o.getCardsIn(ZoneType.Hand).size()).append('/')
                    .append(o.getCardsIn(ZoneType.Library).size());
            for (Card c : o.getCardsIn(ZoneType.Battlefield)) {
                sb.append(c.getId()).append(c.isTapped() ? 't' : 'u').append(',');
            }
        }
        return sb.toString();
    }

    /** Evaluate the position; the chosen line, or null. */
    Line offense(Game live) {
        final String key = positionKey(live, me);
        if (key.equals(noLineKey)) {
            return null;
        }
        final long t0 = System.nanoTime();
        final int copies0 = copies + engineStats.copies, probes0 = reader.probeStats.probes, eng0 = engineStats.nodes;
        lastOutcome = "?";
        lastPlannerNodes = 0;
        final Line l = offense0(live, key);
        receipt("FORCED_WIN cost" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " phase=" + live.getPhaseHandler().getPhase()
                + " outcome=" + lastOutcome + " ms=" + (System.nanoTime() - t0) / 1_000_000 + " plannerNodes="
                + lastPlannerNodes + " engineNodes=" + (engineStats.nodes - eng0) + " copies="
                + (copies + engineStats.copies - copies0) + " probes=" + (reader.probeStats.probes - probes0));
        return l;
    }

    private String lastOutcome = "?";
    private int lastPlannerNodes;

    private Line offense0(Game live, String key) {
        decisions++;
        final int index = decisionIndex++;
        final long t0 = System.nanoTime();
        decisionWall = t0 + (cfg.budgetMs + cfg.wallGraceMs) * 1_000_000L;
        final Position pos = readPosition(live, knownTop(live));
        if (pos == null) {
            noLineKey = key;
            return null;
        }
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.nodes = cfg.nodes;
        lim.ms = cfg.planMs;
        lim.payPolicies = 1;
        boolean payOnly = false;
        // POST-FREEZE 4: same-turn lines first (they are preferred, and a pass-the-turn search can fill the line cap
        // before it reaches one); lines that pass the turn only when this turn has none
        lim.horizon = 1;
        ForcedWinPlanner.Result res = cfg.forced ? ForcedWinPlanner.plan(pos, lim) : new ForcedWinPlanner.Result();
        if (res.lines.isEmpty() && cfg.horizon >= 2 && cfg.forced) {
            final int first = res.nodes;
            lim.horizon = cfg.horizon;
            res = ForcedWinPlanner.plan(pos, lim);
            res.nodes += first;
        }
        if (res.lines.isEmpty() && cfg.forced && cfg.paySearch) {
            // solver-gaps-1015 payment search: only where the greedy payment found no line, search the payment orders
            // (this turn, then by our next turn), within the planner's own node and time caps
            final int before = res.nodes;
            lim.payPolicies = ForcedWinPlanner.PAY_POLICIES;
            lim.horizon = 1;
            res = ForcedWinPlanner.plan(pos, lim);
            if (res.lines.isEmpty() && cfg.horizon >= 2) {
                final int first = res.nodes;
                lim.horizon = cfg.horizon;
                res = ForcedWinPlanner.plan(pos, lim);
                res.nodes += first;
            }
            res.nodes += before;
            if (!res.lines.isEmpty()) {
                paySearchFound++;
                payOnly = true;
            }
        }
        plannerNanos += System.nanoTime() - t0;
        lastPlannerNodes = res.nodes;
        if (res.lines.isEmpty()) {
            lastOutcome = "gate";
            final Line engine = engineFallback(live, pos, index, t0);
            if (engine != null) {
                if (!"likely-take".equals(lastOutcome)) {
                    lastOutcome = "engine-take";
                }
                return engine;
            }
            gated++;
            noLineKey = key;
            receipt("FORCED_WIN gate" + tag() + " turn=" + pos.turn + " none " + res.summary() + " probes=" + reader.probeStats.summary());
            return null;
        }
        evaluated++;
        receipt("FORCED_WIN candidates" + tag() + " turn=" + pos.turn + " " + res.summary() + " probes=" + reader.probeStats.summary()
                + " best=" + res.lines.get(0));
        if (ForcedWinEngine.TRACE) {
            receipt("FW_POSITION " + pos.describe());
        }
        final long t1 = System.nanoTime();
        final Eval best = evaluateTop(live, res.lines, index, cfg.paySearch ? t0 : System.nanoTime());
        engineNanos += System.nanoTime() - t1;
        if (best == null || best.pBelief < cfg.threshold || best.pUnopposed < 1.0) {
            lastOutcome = "declined";
            noLineKey = key;
            if (best == null) {
                // diagnostics (solver-gaps-1015): a width-model mismatch, with the position the width search saw
                receipt("FORCED_WIN mismatch" + tag() + " turn=" + pos.turn + " phase=" + live.getPhaseHandler().getPhase()
                        + " pos=" + pos.describe());
            }
            receipt("FORCED_WIN declined" + tag() + " turn=" + pos.turn + " best=" + (best == null ? "none" : best)
                    + " ms=" + (System.nanoTime() - t0) / 1_000_000);
            return null;
        }
        receipt("FORCED_WIN take" + tag() + " turn=" + pos.turn + " " + best + " ms=" + (System.nanoTime() - t0) / 1_000_000
                + " copies=" + copies + (payOnly ? " paySearch" : ""));
        lastOutcome = "take";
        lastTake = best;
        return best.line;
    }

    // ------------------------------------------------------------------ search-bones-1015: lines as proposals

    /** search-bones-1015: every line the current offense evaluated (null = not collecting). */
    private List<Eval> evalSink;

    /** A line the solver proposes to a search, with its own engine verification. */
    public static final class Proposal {
        /** The line (opaque outside this package): {@link #adopt} it to follow it. */
        public final Object line;
        public final double pUnopposed, pBelief;
        public final int worldsRun, steps, winTurn;
        public final boolean engine;
        public final String text;
        /** Our library's known top the line relies on (its play-outs keep that many cards in place). */
        public final int knownTop;

        Proposal(Eval e) {
            this.line = e.line;
            this.pUnopposed = e.pUnopposed;
            this.pBelief = e.pBelief;
            this.worldsRun = e.worldsRun;
            this.steps = e.line.steps.size();
            this.winTurn = e.line.winTurn;
            this.engine = "engine".equals(e.line.how);
            this.text = String.valueOf(e);
            this.knownTop = e.line.knownTop;
        }

        /** Verified forced: won unopposed and in every belief world it ran (at least {@code minWorlds}), adversary on. */
        public boolean proven(int minWorlds) {
            return pUnopposed >= 1.0 && pBelief >= 1.0 && worldsRun >= minWorlds;
        }
    }

    /**
     * search-bones-1015: the offense's lines at this position, as proposals (each with the solver's own verification:
     * P(unopposed) and P(belief) over its belief worlds with the adversary), whether or not one would be taken. Only
     * lines that won unopposed are returned. Same gates and caches as the offense (our main phase or their end step,
     * an empty stack, the position's no-line cache, the engine search once per turn in main phase 1). Never throws.
     */
    public List<Proposal> propose(Game live) {
        final List<Proposal> out = new ArrayList<>();
        final PhaseHandler ph = live.getPhaseHandler();
        final boolean ourMain = ph.isPlayerTurn(me) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
        final boolean theirEnd = !ph.isPlayerTurn(me) && ph.is(PhaseType.END_OF_TURN);
        if (!live.getStack().isEmpty() || !ourMain && !theirEnd || lineActive()) {
            return out;
        }
        evalSink = new ArrayList<>();
        try {
            offense(live);
            final Set<String> seen = new HashSet<>();
            for (Eval e : evalSink) {
                if (e.pUnopposed >= 1.0 && seen.add(String.valueOf(e.line))) {
                    out.add(new Proposal(e));
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            receipt("FORCED_WIN propose error " + e);
        } finally {
            evalSink = null;
        }
        return out;
    }

    /** search-bones-1015: follow {@code line} (a {@link Proposal#line}) from the next priority on. */
    public void adopt(Object line) {
        active = new Follower((Line) line);
        taken++;
    }

    /** search-bones-1015: the active line has been played to its end or broke (or none was adopted). */
    public boolean lineDone() {
        return active == null || active.done || active.broken;
    }

    /** search-bones-1015: the active line broke (a step could not be played). */
    public boolean lineBroken() {
        return active != null && active.broken;
    }

    /** search-bones-1015: our library's known top (pile memory) in the live game, as a card count. */
    public int knownTopCount(Game live) {
        final List<Integer> k = knownTop(live);
        return k == null ? 0 : k.size();
    }

    /**
     * Lane r5-prereq-1018 (R5 labels): the first step of {@code line} (a {@link Proposal#line}) our priority menu can
     * name, skipping its mana floats: {@code {hostId (Integer), description (String), land (Boolean), pass (Boolean)}},
     * or null when the line starts with an attack or a repeat (no menu entry). Read only.
     */
    public static Object[] firstMenuStep(Object line) {
        for (ForcedWinPlanner.Step st : ((Line) line).steps) {
            if (st.pass) {
                return new Object[] {-1, "", Boolean.FALSE, Boolean.TRUE};
            }
            if (st.kind == ForcedWinModel.AbilKind.FLOAT) {
                continue;
            }
            if (st.kind == ForcedWinModel.AbilKind.ATTACK || st.kind == ForcedWinModel.AbilKind.REPEAT) {
                return null;
            }
            return new Object[] {st.hostId, st.desc, st.kind == ForcedWinModel.AbilKind.LAND, Boolean.FALSE};
        }
        return null;
    }

    /** search-bones-1015: a line's text for receipts. */
    public static String describeLine(Object line) {
        return String.valueOf(line);
    }

    // ------------------------------------------------------------------ defense: the same search from their seat

    /** Reads the opponent's cards (probed from their seat), for threat checks. */
    final ForcedWinModel.Reader oppReader = new ForcedWinModel.Reader();
    private int defendedStackId = -1;
    public int threatChecks, threats, counters, holds, discardChoices;

    /** A threat verdict: the share of belief worlds in which the opponent has a forced win, and one such line. */
    static final class Threat {
        double p;
        int worlds;
        Line line;

        @Override
        public String toString() {
            return String.format("P=%.2f/%d", p, worlds) + (line == null ? "" : " e.g. " + line);
        }
    }

    /**
     * Can the opponent force a win by their next turn if {@code pending} (their spell on the stack; null = none)
     * resolves? The width search from their seat, over belief worlds: the hand cards we can see (all of them when
     * {@code revealed}), the rest sampled from their unseen cards (their registered deck remainder, the belief sampler's
     * convention), their library holding the rest. {@code without}: cards taken out (a discard candidate).
     */
    Threat threat(Game live, Player opp, Card pending, List<Card> without, boolean revealed, int worlds) {
        final long t0 = System.nanoTime();
        threatChecks++;
        final Threat t = new Threat();
        final Position base = oppReader.read(live, opp, null, true, revealed ? opp : me, pending);
        if (base == null) {
            return t;
        }
        if (without != null) {
            for (Card w : without) {
                base.hand.removeIf(a -> a.id == w.getId());
            }
        }
        final PhaseHandler ph = live.getPhaseHandler();
        base.sorcerySpeed = ph.isPlayerTurn(opp) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2));
        // the unseen cards: their library plus the hand cards we cannot see
        final List<ACard> unseen = new ArrayList<>(base.library);
        int hidden = 0;
        for (Card c : opp.getCardsIn(ZoneType.Hand)) {
            if (!revealed && !c.getView().canBeShownTo(me.getView())) {
                unseen.add(oppReader.card(live, opp, c));
                hidden++;
            }
        }
        final int n = hidden == 0 ? 1 : Math.max(1, worlds);
        int wins = 0;
        for (int w = 0; w < n; w++) {
            final Position pos = copyPosition(base);
            if (hidden > 0) {
                final List<ACard> pool = new ArrayList<>(unseen);
                Collections.shuffle(pool, new Random(mix(cfg.seed, 31L * w + ph.getTurn())));
                pos.hand.addAll(pool.subList(0, Math.min(hidden, pool.size())));
                pos.library.clear();
                pos.library.addAll(pool.subList(Math.min(hidden, pool.size()), pool.size()));
                pos.library.sort((x, y) -> Integer.compare(x.id, y.id));
            }
            final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
            lim.nodes = cfg.nodes / 2;
            // POST-FREEZE 3: when they cannot act at sorcery speed now (our turn, or their non-main step), their
            // threat window is their next two turns (a setup spell next turn, the win the turn after: a
            // pass-the-turn Doomsday pile); in their own main phase it is this turn and their next
            lim.horizon = base.sorcerySpeed ? 2 : 3;
            final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, lim);
            if (!res.lines.isEmpty()) {
                wins++;
                if (t.line == null) {
                    t.line = res.lines.get(0);
                }
            }
        }
        t.worlds = n;
        t.p = (double) wins / n;
        plannerNanos += System.nanoTime() - t0;
        return t;
    }

    static Position copyPosition(Position b) {
        final Position p = new Position();
        p.life = b.life;
        p.oppLife = b.oppLife;
        p.turn = b.turn;
        p.oppLibrary = b.oppLibrary;
        p.landDrop = b.landDrop;
        p.sorcerySpeed = b.sorcerySpeed;
        p.storm = b.storm;
        System.arraycopy(b.pool, 0, p.pool, 0, 6);
        p.hand.addAll(b.hand);
        p.battlefield.addAll(b.battlefield);
        p.tapped.addAll(b.tapped);
        p.used.addAll(b.used);
        p.oppPowers.addAll(b.oppPowers);
        p.devotionBlue = b.devotionBlue;
        p.emptyDrawWinLive = b.emptyDrawWinLive;
        p.library.addAll(b.library);
        p.graveyard.addAll(b.graveyard);
        p.knownOrder = b.knownOrder;
        p.knownTop.addAll(b.knownTop);
        p.librarySize = b.librarySize;
        p.canDraw = b.canDraw;
        p.pendingId = b.pendingId;
        return p;
    }

    /** Our counterspell for the opponent's spell on top when resolving it gives them a forced win; else no opinion. */
    private Decision defendCounter(Game live) {
        if (!cfg.defense || !cfg.forced) {
            return Decision.NONE;
        }
        final SpellAbilityStackInstance si = live.getStack().peek();
        final SpellAbility top = si == null ? null : si.getSpellAbility();
        if (top == null || top.getActivatingPlayer() == me || !top.isSpell() || si.getId() == defendedStackId) {
            return Decision.NONE;
        }
        final SpellAbility counter = counterFor(live, top);
        if (counter == null) {
            return Decision.NONE;
        }
        defendedStackId = si.getId();
        final long t0 = System.nanoTime();
        final Player opp = top.getActivatingPlayer();
        // their forced-win chance if it resolves, and if it is countered (the spell gone)
        final Threat resolves = threat(live, opp, top.getHostCard(), null, false, cfg.defenseWorlds);
        final Threat countered = threat(live, opp, null, List.of(top.getHostCard()), false, cfg.defenseWorlds);
        final double delta = resolves.p - countered.p;
        final String verdict = delta >= cfg.counterDelta ? "counter" : countered.p >= cfg.counterDelta ? "hold" : "native";
        receipt("FORCED_WIN defense" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " spell=" + top.getHostCard().getName()
                + " resolves=" + resolves + " countered=" + countered + " -> " + verdict + " with=" + counter.getHostCard().getName()
                + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        if ("counter".equals(verdict)) {
            threats++;
            counters++;
            return new Decision(true, counter);
        }
        if ("hold".equals(verdict)) {
            // a bigger threat is likely still to come: keep the counter for it
            holds++;
            return Decision.PASS;
        }
        return Decision.NONE;
    }

    private SpellAbility counterFor(Game g, SpellAbility top) {
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                if (sa.getApi() != ApiType.Counter || !sa.usesTargeting()) {
                    continue;
                }
                final SpellAbility cs = sa.copy(me);
                cs.setActivatingPlayer(me);
                if (!cs.canTargetSpellAbility(top)) {
                    continue;
                }
                cs.resetTargets();
                cs.getTargets().add(top);
                if (!cs.canPlay() || !ComputerUtilCost.canPayCost(cs, me, false)) {
                    continue;
                }
                return cs;
            }
        }
        return null;
    }

    /**
     * Our choice of the opponent's cards to discard (Thoughtseize, Duress): the card whose loss removes their forced
     * win by their next turn, searched from their seat with each candidate removed; null = no opinion (no candidate
     * changes the verdict, or they have no forced win anyway).
     */
    public CardCollection answerDiscard(Player p, SpellAbility sa, CardCollection valid, int min, int max) {
        if (!cfg.defense || !cfg.forced || p == me || sa == null || sa.getActivatingPlayer() != me || valid == null
                || valid.isEmpty() || max != 1) {
            return null;
        }
        final long t0 = System.nanoTime();
        try {
            final Game live = me.getGame();
            final Threat nowT = threat(live, p, null, null, true, 1);
            final Line now = nowT.line;
            if (now == null) {
                receipt("FORCED_WIN discard turn=" + live.getPhaseHandler().getTurn() + " threat=none");
                return null;
            }
            discardChoices++;
            final List<String> tried = new ArrayList<>();
            for (Card c : valid) {
                final Line after = threat(live, p, null, List.of(c), true, 1).line;
                tried.add(c.getName() + (after == null ? ":stops" : ":still"));
                if (after == null) {
                    receipt("FORCED_WIN discard turn=" + live.getPhaseHandler().getTurn() + " take=" + c.getName()
                            + " threat=" + now + " tried=" + tried + " ms=" + (System.nanoTime() - t0) / 1_000_000);
                    final CardCollection out = new CardCollection();
                    out.add(c);
                    return out;
                }
            }
            receipt("FORCED_WIN discard turn=" + live.getPhaseHandler().getTurn() + " no single card stops it tried=" + tried);
        } catch (RuntimeException e) {
            receipt("FORCED_WIN discard error " + e);
        }
        return null;
    }

    /** Engine search statistics. */
    final ForcedWinEngine.Stats engineStats = new ForcedWinEngine.Stats();
    private int engineTurn = -1;
    public int engineRuns, engineFound;

    /**
     * The engine search, once per turn in main phase 1, only when something the width model cannot express is on our
     * side (an ability that targets a card or has a cost outside the model) or our board threatens lethal.
     */
    private Line engineFallback(Game live, Position pos, int index, long t0) {
        if (!cfg.engine || engineTurn == pos.turn || !live.getPhaseHandler().is(PhaseType.MAIN1)) {
            return null;
        }
        boolean unmodeled = false;
        for (ACard a : pos.hand) {
            unmodeled |= a.unmodeled;
        }
        for (ACard a : pos.battlefield) {
            unmodeled |= a.unmodeled;
        }
        int power = 0;
        for (Card c : me.getCreaturesInPlay()) {
            power += Math.max(0, c.getNetCombatDamage());
        }
        if (!unmodeled && power < pos.oppLife) {
            return null;
        }
        if (!cfg.forced && !cfg.likely) {
            return null;
        }
        engineTurn = pos.turn;
        engineRuns++;
        lastOutcome = "gate+engine";
        final ForcedWinEngine.Limits el = new ForcedWinEngine.Limits();
        el.nodes = cfg.engineNodes;
        el.cycles = cfg.cycles;
        if (cfg.hideOwnLibrary) {
            final List<Integer> kt = knownTop(live);
            el.ownKnownTop = kt == null ? 0 : kt.size();
        }
        el.wall = decisionWall;
        final long t1 = System.nanoTime();
        final long reserve = cfg.likely ? Math.max(cfg.evalReserveMs, cfg.likelyReserveMs) : cfg.evalReserveMs;
        final long engineDeadline = Math.max(t1 + Math.min(cfg.engineMs, cfg.budgetMs - reserve) * 1_000_000L,
                t0 + (cfg.budgetMs - reserve) * 1_000_000L);
        final List<Line> likelyCands = cfg.likely ? new ArrayList<>() : null;
        final Line line = ForcedWinEngine.search(live, me, el, engineStats, engineDeadline,
                mix(cfg.seed, 7_000_000L + index), likelyCands, cfg.likelyTop);
        engineNanos += System.nanoTime() - t1;
        receipt("FORCED_WIN engine" + tag() + " turn=" + pos.turn + " found=" + (line != null) + " " + engineStats.summary()
                + (line == null ? "" : " " + line) + (likelyCands == null ? "" : " likelyCands=" + likelyCands.size()));
        if (line == null || !cfg.forced) {
            if (line != null) {
                likelyCands.add(0, line);
            }
            return likely(live, likelyCands, index, t0);
        }
        engineFound++;
        evaluated++;
        final Eval e = evaluate(live, line, mix(cfg.seed, index * 1000L + 999),
                Math.max(t0 + cfg.budgetMs * 1_000_000L, System.nanoTime() + cfg.evalReserveMs * 1_000_000L / 2));
        receipt("FORCED_WIN eval engine" + tag() + " turn=" + pos.turn + " " + e);
        if (evalSink != null) {
            evalSink.add(e);
        }
        if (e.pUnopposed < 1.0 || e.pBelief < cfg.threshold) {
            if (likelyCands != null) {
                // not forced: a likely-win candidate like the others (Part B)
                likelyCands.add(0, line);
                return likely(live, likelyCands, index, t0);
            }
            return null;
        }
        receipt("FORCED_WIN take" + tag() + " turn=" + pos.turn + " " + e + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        lastTake = e;
        return line;
    }

    // ------------------------------------------------------------------ Part B: likely-win lines (solver-gaps-1015)

    public int likelyRuns, likelyTaken;
    /** solver-gaps-1015: play-outs and rollouts cut at their wall (or after an attack the rules refused twice). */
    public int wallCuts;
    /** solver-gaps-1015: the current offense decision's hard wall (System.nanoTime()): its budget plus the grace. */
    private long decisionWall;
    /** Payment search: decisions where only the searched payment found width lines. */
    public int paySearchFound;

    /**
     * Part B: score the candidate lines and the seat's own play (Forge AI) by P(win) over the same belief worlds, each
     * a rollout of both seats (Forge AI; ours follows the line first) to the game's end or the turn cap. The best line is
     * returned only when it beats the seat's own play; no probability threshold. Null = no opinion.
     */
    Line likely(Game live, List<Line> cands, int index, long t0) {
        if (cands == null || cands.isEmpty()) {
            return null;
        }
        final long l0 = System.nanoTime();
        // stop the rollouts half a second before the decision's budget: measured at the full budget, Part B decisions
        // ended at 8.0-8.4 s (a rollout in flight, the line's set-up)
        final long deadline = t0 + (cfg.budgetMs - 500) * 1_000_000L;
        if (deadline - l0 < 300_000_000L) {
            // no time left in the decision's budget for even one world
            receipt("FORCED_WIN likely" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " no budget left");
            return null;
        }
        likelyRuns++;
        final int k = Math.min(cands.size(), cfg.likelyTop + 1);
        final double[] sum = new double[k];
        double alt = 0;
        int worlds = 0;
        for (int w = 1; w <= cfg.likelyWorlds; w++) {
            if (System.nanoTime() > deadline) {
                break;
            }
            final long ws = mix(cfg.seed, index * 1000L + 500 + w);
            // a world counts only when every arm finished in it before the budget (paired worlds)
            final double a = rollout(live, null, ws, w, deadline);
            final double[] v = new double[k];
            boolean complete = a >= 0;
            for (int i = 0; i < k && complete; i++) {
                v[i] = rollout(live, cands.get(i), ws, w, deadline);
                complete = v[i] >= 0;
            }
            if (!complete) {
                break;
            }
            alt += a;
            for (int i = 0; i < k; i++) {
                sum[i] += v[i];
            }
            worlds++;
        }
        if (worlds == 0) {
            return null;
        }
        int best = -1;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k; i++) {
            sb.append(String.format(" #%d P=%.2f %s;", i, sum[i] / worlds, cands.get(i).steps));
            if (best < 0 || sum[i] > sum[best]) {
                best = i;
            }
        }
        final double pAlt = alt / worlds, pBest = sum[best] / worlds;
        final boolean take = pBest > pAlt + 1e-9;
        receipt("FORCED_WIN likely" + tag() + " turn=" + live.getPhaseHandler().getTurn() + " worlds=" + worlds
                + String.format(" P(own play)=%.2f P(best)=%.2f", pAlt, pBest) + " -> " + (take ? "take #" + best : "keep own play")
                + " ms=" + (System.nanoTime() - l0) / 1_000_000 + sb);
        if (!take) {
            return null;
        }
        likelyTaken++;
        lastOutcome = "likely-take";
        return cands.get(best);
    }

    /**
     * Part B: one rollout in a belief world (world w samples the opponent's hidden hand): our seat follows {@code line}
     * (null = Forge AI's own play) and then plays on as Forge AI; the opponent is Forge AI. 1 = we win, 0 = we lose, 0.5
     * = still running at the turn cap.
     */
    double rollout(Game live, Line line, long seed, int world, long deadline) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player cm;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1);
                g = copier.makeCopy();
                cm = (Player) copier.find(me);
                copies++;
                final Random rng = new Random(mix(seed, 2));
                for (Player o : cm.getOpponents()) {
                    hideOrSample(live, me, (Player) live.getPlayer(o.getId()), o, world == 0, rng);
                }
                shuffleUnknownOwnLibrary(cm, line == null ? placedCount(me) : line.knownTop, rng);
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 3)));
            if (line != null) {
                final LineAi ai = new LineAi(g, cm, cm.getController().getLobbyPlayer(), new Follower(line));
                ai.continueAfter = true;
                ai.bounded(deadline);
                cm.dangerouslySetController(ai);
            } else {
                cm.dangerouslySetController(new ForcedWinEngine.GuardedAi(g, cm, cm.getController().getLobbyPlayer()).bounded(deadline));
            }
            for (Player o : g.getPlayers()) {
                if (o != cm) {
                    o.dangerouslySetController(new ForcedWinEngine.GuardedAi(g, o, o.getController().getLobbyPlayer()).bounded(deadline));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            LookaheadSearch.givePriority(ph, cm);
            final int last = ph.getTurn() + cfg.likelyCapTurns;
            int steps = 0;
            while (!g.isGameOver() && ph.getTurn() <= last && steps < 20 * cfg.maxSteps) {
                ph.mainLoopStep();
                steps++;
                if ((steps & 31) == 0 && System.nanoTime() > deadline) {
                    return -1; // out of budget: the world is dropped
                }
            }
            if (cm.hasWon() || cm.getOpponents().stream().anyMatch(Player::hasLost)) {
                return 1;
            }
            return cm.hasLost() ? 0 : 0.5;
        } catch (ForcedWinEngine.OutOfBudget ex) {
            wallCuts++;
            return -1; // cut at the wall: the world is dropped
        } catch (RuntimeException | StackOverflowError ex) {
            receipt("FORCED_WIN likely rollout error " + ex);
            return 0.5;
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** Read the position; the library's affordances are learned only when something we hold could use them. */
    Position readPosition(Game live, List<Integer> knownOrder) {
        final Position pos = reader.read(live, me, knownOrder, true);
        return pos;
    }

    /** A line's engine result. */
    static final class Eval {
        final Line line;
        double pUnopposed;
        double pBelief;
        int worldsRun;
        String protection = "";
        String failures = "";

        Eval(Line line) {
            this.line = line;
        }

        @Override
        public String toString() {
            return "P(unopposed)=" + pUnopposed + " P(belief)=" + String.format("%.2f", pBelief) + " worlds=" + worldsRun
                    + " " + line + (protection.isEmpty() ? "" : " protectedBy=" + protection)
                    + (failures.isEmpty() ? "" : " fails=" + failures);
        }
    }

    /** Engine-evaluate up to {@code cfg.top} distinct candidates; the best, or null. */
    Eval evaluateTop(Game live, List<Line> lines, int index) {
        return evaluateTop(live, lines, index, System.nanoTime());
    }

    /** As above; the evaluation's budget counts from {@code from} (the decision's start with the payment search on). */
    Eval evaluateTop(Game live, List<Line> lines, int index, long from) {
        final long deadline = from + cfg.budgetMs * 1_000_000L;
        final List<Line> picked = new ArrayList<>();
        final Set<String> shapes = new HashSet<>();
        for (Line l : lines) {
            final String shape = l.pileNames + "|" + l.steps.size() + "|" + l.winTurn + "|" + l.protectionNames;
            if (shapes.add(shape)) {
                picked.add(l);
            }
            if (picked.size() >= cfg.top) {
                break;
            }
        }
        Eval best = null;
        for (int i = 0; i < picked.size(); i++) {
            if (System.nanoTime() > deadline && best != null) {
                break;
            }
            final Eval e = evaluate(live, picked.get(i), mix(cfg.seed, index * 1000L + i), deadline);
            receipt("FORCED_WIN eval #" + i + tag() + " turn=" + live.getPhaseHandler().getTurn() + " " + e);
            if (evalSink != null) {
                evalSink.add(e);
            }
            if (e.pUnopposed < 1.0) {
                continue;
            }
            if (best == null || e.pBelief > best.pBelief + 1e-9) {
                best = e;
            }
            if (best.pBelief >= 1.0) {
                break;
            }
        }
        return best;
    }

    /**
     * P(win | unopposed) and P(win | belief worlds, adversarial counters) of one line. {@code deadline} stops new worlds;
     * a play-out already running is cut only at the wall (the decision's, and at least the deadline plus the grace).
     */
    Eval evaluate(Game live, Line line, long seed, long deadline) {
        if (cfg.exact) {
            return evaluateExact(live, line, seed, deadline);
        }
        final Eval e = new Eval(line);
        final long wall = Math.max(decisionWall, deadline + cfg.wallGraceMs * 1_000_000L);
        final Outcome u = playout(live, line, mix(seed, 0), 0, -1, wall);
        e.pUnopposed = u.won ? 1 : 0;
        if (!u.won) {
            e.failures = "unopposed:" + u.why;
            return e;
        }
        int spells = 0;
        for (Step s : line.steps) {
            if (s.isSpell()) {
                spells++;
            }
        }
        double sum = 0;
        int n = 0;
        final Map<String, Integer> fails = new HashMap<>();
        final Set<String> prot = new HashSet<>();
        for (int w = 1; w <= cfg.worlds; w++) {
            if (System.nanoTime() > deadline && n > 0) {
                break;
            }
            final long ws = mix(seed, w);
            Outcome worst = playout(live, line, ws, w, -1, wall);
            if (worst.protectedBy != null) {
                prot.add(worst.protectedBy);
            }
            if (worst.won && cfg.adversary && worst.oppHasCounter) {
                for (int j = 0; j < spells && worst.won; j++) {
                    final Outcome o = playout(live, line, ws, w, j, wall);
                    if (o.protectedBy != null) {
                        prot.add(o.protectedBy);
                    }
                    if (!o.won) {
                        worst = o;
                    }
                }
            }
            if ("wall".equals(worst.why)) {
                break; // solver-gaps-1015: a world cut at the wall is dropped, not counted as a loss
            }
            sum += worst.won ? 1 : 0;
            n++;
            if (!worst.won) {
                fails.merge(worst.why, 1, Integer::sum);
            }
        }
        e.worldsRun = n;
        e.pBelief = n == 0 ? 0 : sum / n;
        e.protection = String.join(",", new java.util.TreeSet<>(prot));
        e.failures = fails.toString();
        return e;
    }

    /** search-bones-1015 (d): {@link Config#exact}'s evaluation (one world, the position as it stands). */
    Eval evaluateExact(Game live, Line line, long seed, long deadline) {
        final Eval e = new Eval(line);
        final long wall = Math.max(decisionWall, deadline + cfg.wallGraceMs * 1_000_000L);
        Outcome worst = playout(live, line, mix(seed, 0), -1, -1, wall);
        e.pUnopposed = worst.won ? 1 : 0;
        e.worldsRun = 1;
        if (worst.won && cfg.adversary && worst.oppHasCounter) {
            int spells = 0;
            for (Step s : line.steps) {
                if (s.isSpell()) {
                    spells++;
                }
            }
            for (int j = 0; j < spells && worst.won; j++) {
                if (System.nanoTime() > wall) {
                    worst = new Outcome();
                    worst.why = "wall";
                    break;
                }
                final Outcome o = playout(live, line, mix(seed, 0), -1, j, wall);
                if (!o.won) {
                    worst = o;
                }
            }
        }
        e.pBelief = worst.won ? 1 : 0;
        e.failures = worst.won ? "" : worst.why;
        return e;
    }

    /** One play-out's result. */
    static final class Outcome {
        boolean won;
        boolean oppHasCounter;
        String why = "";
        String protectedBy;
    }

    /**
     * Play {@code line} in a copy of the live game: world 0 removes the opponent's hidden hand; world w > 0 samples it
     * from their deck remainder. {@code counterAt}: the adversary counters our spell of that index (-1: Forge AI only).
     * {@code wall} (System.nanoTime(); 0 = none, solver-gaps-1015): no step of the play-out, combat declarations
     * included, runs past it; a play-out cut there is not a win.
     */
    Outcome playout(Game live, Line line, long seed, int world, int counterAt, long wall) {
        final Outcome out = new Outcome();
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g;
            final Player cm;
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1); // storm count, per-turn records
                g = copier.makeCopy();
                cm = (Player) copier.find(me);
                copies++;
                final Random rng = new Random(mix(seed, 2));
                if (world >= 0) {
                    // search-bones-1015 (d): world -1 = the position as it stands (exact)
                    for (Player o : cm.getOpponents()) {
                        final Player liveOpp = (Player) live.getPlayer(o.getId());
                        hideOrSample(live, me, liveOpp, o, world == 0, rng);
                    }
                    shuffleUnknownOwnLibrary(cm, line, rng);
                }
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 3)));
            final Follower f = new Follower(line);
            final LineAi lineAi = new LineAi(g, cm, cm.getController().getLobbyPlayer(), f);
            lineAi.bounded(wall);
            cm.dangerouslySetController(lineAi);
            AdversaryAi adv = null;
            for (Player o : g.getPlayers()) {
                if (o != cm) {
                    adv = new AdversaryAi(g, o, o.getController().getLobbyPlayer(), cm, counterAt);
                    adv.bounded(wall);
                    o.dangerouslySetController(adv);
                    out.oppHasCounter |= AdversaryAi.holdsCounter(o);
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            LookaheadSearch.givePriority(ph, cm);
            final LookaheadSearch.TurnWatch watch = new LookaheadSearch.TurnWatch(ph.getTurn() + 1 + 2 * line.winTurn, null);
            g.subscribeToEvents(watch);
            int steps = 0;
            while (!g.isGameOver() && !watch.reached && steps < cfg.maxSteps) {
                ForcedWinEngine.checkWall(wall, "play-out step");
                ph.mainLoopStep();
                steps++;
            }
            out.won = cm.hasWon() || cm.getOpponents().stream().anyMatch(Player::hasLost);
            out.protectedBy = lineAi.protectedBy;
            if (ForcedWinEngine.TRACE) {
                final StringBuilder sb = new StringBuilder();
                for (Player o : cm.getOpponents()) {
                    sb.append(" oppLife=").append(o.getLife()).append(" oppLib=").append(o.getCardsIn(ZoneType.Library).size())
                            .append(" oppHand=").append(o.getCardsIn(ZoneType.Hand).size());
                }
                receipt("FW_PLAYOUT world=" + world + " counterAt=" + counterAt + " won=" + out.won + " turn="
                        + g.getPhaseHandler().getTurn() + " cursor=" + f.cursor + "/" + line.steps.size() + " broken=" + f.broken
                        + " " + f.why + " life=" + cm.getLife() + sb + " stormCast=" + g.getStack().getSpellsCastThisTurn().size());
            }
            if (!out.won) {
                out.why = f.broken ? "broken@" + f.cursor + ":" + f.why
                        : adv != null && adv.countered != null ? "countered:" + adv.countered
                        : g.isGameOver() ? "lost" : steps >= cfg.maxSteps ? "steps" : "no-win@" + f.cursor;
                if (!f.broken) {
                    out.why += " {" + endState(g, cm, f) + "}";
                }
            }
        } catch (ForcedWinEngine.OutOfBudget ex) {
            // a step still running at the wall, or an attack the rules refused twice: not a win
            out.won = false;
            out.why = "wall";
            wallCuts++;
            receipt("FORCED_WIN playout cut world=" + world + " " + ex.getMessage());
        } catch (RuntimeException | StackOverflowError ex) {
            out.won = false;
            out.why = "error:" + ex.getClass().getSimpleName();
            receipt("FORCED_WIN playout error " + ex);
        } finally {
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        return out;
    }

    /**
     * The opponent's hidden hand: world 0 puts it on the bottom of their library (they interact only through their
     * board); belief worlds draw it again from their hand + library (uniform over their deck remainder, keeping cards
     * we have seen). Their library is shuffled either way.
     */
    static void hideOrSample(Game live, Player liveMe, Player liveOpp, Player opp, boolean hide, Random rng) {
        final forge.game.player.PlayerView myView = liveMe.getView();
        final List<Card> hand = new ArrayList<>(opp.getCardsIn(ZoneType.Hand));
        final List<Card> lib = new ArrayList<>(opp.getCardsIn(ZoneType.Library));
        final List<Card> keep = new ArrayList<>();
        final List<Card> pool = new ArrayList<>(lib);
        for (Card c : hand) {
            final Card lc = live.findById(c.getId());
            if (lc != null && lc.getView().canBeShownTo(myView)) {
                keep.add(c);
            } else {
                pool.add(c);
            }
        }
        Collections.shuffle(pool, rng);
        final List<Card> newHand = new ArrayList<>(keep);
        final List<Card> hidden = new ArrayList<>();
        if (!hide) {
            final int need = hand.size() - keep.size();
            newHand.addAll(pool.subList(0, need));
            pool.subList(0, need).clear();
        } else {
            // solver-gaps-1015: the hidden hand leaves the game (exile), not into their library: their library keeps its
            // size (a milled-out opponent stays milled out in the unopposed world)
            for (Card c : hand) {
                if (!keep.contains(c)) {
                    hidden.add(c);
                    pool.remove(c);
                }
            }
        }
        opp.getZone(ZoneType.Hand).setCards(newHand);
        opp.getZone(ZoneType.Library).setCards(pool);
        if (!hidden.isEmpty()) {
            final List<Card> ex = new ArrayList<>(opp.getCardsIn(ZoneType.Exile));
            ex.addAll(hidden);
            opp.getZone(ZoneType.Exile).setCards(ex);
        }
    }

    /**
     * Our library order is unknown to us except the top the seat put there (pile memory, solver-gaps-1015): shuffle the
     * rest in each world.
     */
    static void shuffleUnknownOwnLibrary(Player me, Line line, Random rng) {
        shuffleUnknownOwnLibrary(me, line.knownTop, rng);
    }

    static void shuffleUnknownOwnLibrary(Player me, int knownTop, Random rng) {
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final int k = Math.min(knownTop, lib.size());
        if (k >= lib.size()) {
            return;
        }
        final List<Card> rest = new ArrayList<>(lib.subList(k, lib.size()));
        Collections.shuffle(rest, rng);
        final List<Card> out = new ArrayList<>(lib.subList(0, k));
        out.addAll(rest);
        me.getZone(ZoneType.Library).setCards(out);
    }

    // ------------------------------------------------------------------ diagnostics (solver-gaps-1015)

    private static final String[] RESTRICTION_PARAMS = {"CheckSVar", "SVarCompare", "IsPresent", "PresentCompare",
            "PresentZone", "Activation", "ActivationLimit", "ActivationPhases", "SorcerySpeed", "PlayerTurn",
            "ActivationLifeTotal", "ActivationZone"};

    private static String names(Game g, List<Integer> ids) {
        final List<String> out = new ArrayList<>();
        for (int id : ids) {
            final Card c = id < 0 ? null : g.findById(id);
            out.add(c == null ? "?" : c.getName());
        }
        return out.toString();
    }

    private static String cardNames(Iterable<Card> cs, int max) {
        final List<String> out = new ArrayList<>();
        for (Card c : cs) {
            if (out.size() >= max) {
                out.add("...");
                break;
            }
            out.add(c.getName());
        }
        return out.toString();
    }

    /**
     * Diagnostics only: why a line's step cannot be played in this game copy. Reports where the host card is, which
     * check refuses the ability, and the width model's expected hand and library top before the step against the
     * game's. Never changes the game (read-only checks on a copy of the ability).
     */
    static String diagnose(Game g, Player me, Step st) {
        final StringBuilder sb = new StringBuilder();
        try {
            final PhaseHandler ph = g.getPhaseHandler();
            sb.append("t").append(ph.getTurn()).append(' ').append(ph.getPhase());
            final Card host = g.findById(st.hostId);
            if (host == null) {
                sb.append(" host=gone");
            } else {
                final ZoneType z = host.getZone() == null ? null : host.getZone().getZoneType();
                sb.append(" host=").append(z);
                if (z == ZoneType.Library) {
                    sb.append('#').append(me.getCardsIn(ZoneType.Library).indexOf(host));
                }
                final boolean onBf = st.kind == AbilKind.ACTIVATE || st.kind == AbilKind.FLOAT;
                if (onBf ? z == ZoneType.Battlefield : z == ZoneType.Hand || st.kind == AbilKind.ENGINE) {
                    SpellAbility sa = null;
                    if (st.kind == AbilKind.FLOAT) {
                        for (SpellAbility m : host.getManaAbilities()) {
                            if (st.desc.isEmpty() || st.desc.equals(m.getDescription())) {
                                sa = m;
                                break;
                            }
                        }
                    } else if (st.kind == AbilKind.LAND) {
                        sb.append(" landDrop=").append(me.getLandsPlayedThisTurn()).append('/').append(me.getMaxLandPlays());
                    } else {
                        sa = ForcedWinProbe.locate(host, me, st.desc, st.costKey, st.kind == AbilKind.ACTIVATE);
                    }
                    if (sa == null && st.kind != AbilKind.LAND) {
                        sb.append(" abil=none");
                    } else if (sa != null) {
                        sa = sa.copy(me);
                        sa.setActivatingPlayer(me);
                        if (st.targetVariant != null && !ForcedWinProbe.setPlayerTargets(sa, me, st.targetVariant)) {
                            sb.append(" target=no");
                        } else if (!sa.canPlay()) {
                            final forge.game.spellability.SpellAbilityRestriction r = sa.getRestrictions();
                            sb.append(" canPlay=no");
                            if (r != null) {
                                sb.append(!sa.canCastTiming(host, me) ? ":timing" : "")
                                        .append(!r.checkTimingRestrictions(host, sa) ? ":timingR" : "")
                                        .append(!r.checkZoneRestrictions(host, sa) ? ":zone" : "")
                                        .append(!r.checkActivatorRestrictions(host, sa) ? ":activator" : "")
                                        .append(!r.checkOtherRestrictions(host, sa, me) ? ":other" : "");
                            }
                            for (String k : RESTRICTION_PARAMS) {
                                if (sa.hasParam(k)) {
                                    sb.append(' ').append(k).append('=').append(sa.getParam(k));
                                }
                            }
                            sb.append(" activations=").append(sa.getActivationsThisTurn());
                        } else if (!ComputerUtilCost.canPayCost(sa, me, false)) {
                            sb.append(" pay=no");
                        } else {
                            sb.append(" playable");
                        }
                    }
                }
            }
            final List<Integer> hand = new ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                hand.add(c.getId());
            }
            final List<Integer> lib = new ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                if (lib.size() >= 6) {
                    break;
                }
                lib.add(c.getId());
            }
            if (st.expHandIdx != null) {
                final List<Integer> missing = new ArrayList<>(), extra = new ArrayList<>(hand);
                int unseen = 0;
                for (int id : st.expHand) {
                    if (id < 0) {
                        unseen++;
                    } else if (!extra.remove(Integer.valueOf(id))) {
                        missing.add(id);
                    }
                }
                sb.append(" handMissing=").append(names(g, missing)).append(" handExtra=").append(names(g, extra))
                        .append(" handUnseen=").append(unseen);
                sb.append(" expLib=").append(names(g, st.expLib));
            }
            sb.append(" hand=").append(names(g, hand)).append(" lib=").append(names(g, lib)).append(" libSize=")
                    .append(me.getCardsIn(ZoneType.Library).size());
            final StringBuilder pool = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                final int n = me.getManaPool().getAmountOfColor(ForcedWinModel.FORGE_COLOR[i]);
                if (n > 0) {
                    pool.append(n).append(ForcedWinModel.SHORT[i]);
                }
            }
            final List<String> untapped = new ArrayList<>();
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isTapped() && !c.getManaAbilities().isEmpty()) {
                    untapped.add(c.getName());
                }
            }
            sb.append(" pool=").append(pool).append(" untappedSources=").append(untapped).append(" life=").append(me.getLife())
                    .append(" storm=").append(g.getStack().getSpellsCastThisTurn().size()).append(" gy=")
                    .append(me.getCardsIn(ZoneType.Graveyard).size());
        } catch (RuntimeException e) {
            sb.append(" diag-error=").append(e);
        }
        return sb.toString();
    }

    /** Diagnostics only: the play-out's end state when a line ran without breaking and did not win. */
    static String endState(Game g, Player me, Follower f) {
        final StringBuilder sb = new StringBuilder();
        try {
            final PhaseHandler ph = g.getPhaseHandler();
            int devotion = me.getDevotionMod();
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                devotion += ForcedWinModel.bluePips(c);
            }
            sb.append("t").append(ph.getTurn()).append(' ').append(ph.getPhase()).append(" cursor=").append(f.cursor).append('/')
                    .append(f.line.steps.size()).append(f.done ? " done" : "").append(f.attackPending ? " attackPending" : "")
                    .append(" life=").append(me.getLife()).append(" libSize=").append(me.getCardsIn(ZoneType.Library).size())
                    .append(" devotionU=").append(devotion).append(" hand=").append(cardNames(me.getCardsIn(ZoneType.Hand), 10))
                    .append(" lib=").append(cardNames(me.getCardsIn(ZoneType.Library), 4));
            for (Player o : me.getOpponents()) {
                sb.append(" oppLife=").append(o.getLife()).append(" oppLib=").append(o.getCardsIn(ZoneType.Library).size());
            }
            sb.append(" stack=").append(g.getStack().size()).append(" over=").append(g.isGameOver());
        } catch (RuntimeException e) {
            sb.append(" diag-error=").append(e);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ live answers (choice asks of the line)

    public Card answerPick(ZoneType destination, List<ZoneType> origin, SpellAbility source, CardCollection choices,
            Player decider) {
        final Card c = active == null || decider != me ? null : active.pick(source, choices);
        if (c != null && destination == ZoneType.Library) {
            notePlaced(List.of(c));
        }
        return c;
    }

    public CardCollectionView answerOrder(CardCollectionView cards, ZoneType destination, SpellAbility source) {
        if (destination == ZoneType.Library) {
            // pile memory: whichever order is chosen for our library, it is ours
            notePlaced(cards);
        }
        return active == null ? null : active.order(cards, destination, source);
    }

    public Boolean answerConfirm(SpellAbility sa, String message) {
        if (active == null || active.done || message == null || !message.toLowerCase().contains("shuffle")) {
            return null;
        }
        return Boolean.FALSE;
    }

    public ImmutablePair<CardCollection, CardCollection> answerLook(CardCollection topN) {
        return active == null || active.done ? null : ImmutablePair.of(new CardCollection(topN), new CardCollection());
    }

    /** Our triggers going on the stack: the active line's forced trigger target, if any; the rest for Forge AI. */
    public List<SpellAbility> answerTriggers(List<SpellAbility> abilities) {
        if (active == null || active.triggers.forced == null) {
            return abilities;
        }
        return active.triggers.apply(me.getGame(), me, abilities);
    }

    /** The optional trigger whose target the active line forced: yes. False = not ours. */
    public boolean answerConfirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
        return active != null && active.triggers.confirms(wrapper);
    }

    /** Tap or untap while a line is active: the rules' default (untap our own, tap theirs); null = not ours. */
    public Boolean answerBinary(forge.game.player.PlayerController.BinaryChoiceType kind, Boolean defaultVal) {
        if (active == null || active.done || kind != forge.game.player.PlayerController.BinaryChoiceType.TapOrUntap
                || defaultVal == null) {
            return null;
        }
        return defaultVal;
    }

    /** Card ids the active line still needs in hand (discard choices avoid them). */
    public Set<Integer> reserved() {
        return active == null || active.done ? Collections.emptySet() : active.reserved();
    }

    // ------------------------------------------------------------------ the follower (live seat and play-outs)

    /** Executes a line step by step in any game where its card ids exist. */
    static final class Follower {
        final Line line;
        int cursor;
        int passTurn = -1;
        boolean done, broken;
        String why = "";
        /** Executed steps by host id: the choice asks of a resolving step are answered from it. */
        final Map<Integer, Step> executed = new HashMap<>();
        final Map<Integer, Integer> pilePicked = new HashMap<>();
        /** An ATTACK step is waiting for our declare-attackers step. */
        boolean attackPending;
        /** The forced trigger target of the step being resolved (ENGINE steps). */
        ForcedWinEngine.TriggerTargets triggers = new ForcedWinEngine.TriggerTargets(null);
        /** The forced library pick of the step being resolved (ENGINE steps). */
        ForcedWinEngine.LibraryPicks picks = new ForcedWinEngine.LibraryPicks(null);

        Follower(Line line) {
            this.line = line;
        }

        static final class Next {
            SpellAbility action;
            boolean waiting, broken, waitingEnd;
            String why = "";
        }

        Set<Integer> reserved() {
            final Set<Integer> out = new HashSet<>();
            for (int i = cursor; i < line.steps.size(); i++) {
                final Step s = line.steps.get(i);
                if (s.kind == AbilKind.REPEAT) {
                    for (Step b : s.body) {
                        out.add(b.hostId);
                    }
                } else if (!s.pass) {
                    out.add(s.hostId);
                }
            }
            return out;
        }

        /** search-bones-1015 (c): the position inside the current REPEAT step (repetition, body index). */
        int repIter, repPos;

        Next next(Game g, Player me) {
            final Next n = new Next();
            if (!g.getStack().isEmpty()) {
                // our own spell resolves first; other stack objects are answered by the caller's protection logic
                n.waiting = true;
                return n;
            }
            final PhaseHandler ph = g.getPhaseHandler();
            if (passTurn < 0 && cursor < line.steps.size() && line.steps.get(cursor).pass) {
                // solver-gaps-1015: a PASS ends the turn it is reached in, ours or theirs (a line taken at their end step
                // starts with PASS: it means "to our next turn", not "skip our next turn")
                passTurn = ph.getTurn();
            }
            if (!ph.isPlayerTurn(me) || !(ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2))) {
                n.waiting = !done;
                if (done) {
                    n.waitingEnd = false;
                }
                return n;
            }
            while (cursor < line.steps.size() && line.steps.get(cursor).pass) {
                if (passTurn < 0) {
                    passTurn = ph.getTurn();
                    n.waiting = true;
                    return n;
                }
                if (ph.getTurn() == passTurn) {
                    n.waiting = true;
                    return n;
                }
                cursor++;
            }
            if (cursor >= line.steps.size()) {
                done = true;
                n.waitingEnd = false;
                return n;
            }
            final Step st0 = line.steps.get(cursor);
            // search-bones-1015 (c): a REPEAT step plays its body steps in order, its number of times
            final boolean inRepeat = st0.kind == AbilKind.REPEAT;
            final Step st = inRepeat ? st0.body.get(repPos) : st0;
            if (st.kind == AbilKind.ATTACK) {
                if (!ph.is(PhaseType.MAIN1)) {
                    // the attack happened (or its chance passed): go on with the line in main phase 2
                    attackPending = false;
                    cursor++;
                    return next(g, me);
                }
                attackPending = true;
                n.waiting = true;
                return n;
            }
            final SpellAbility sa = locate(g, me, st);
            if (sa == null) {
                broken = true;
                why = "missing:" + st + " {" + diagnose(g, me, st) + "}";
                n.broken = true;
                n.why = why;
                return n;
            }
            if (inRepeat) {
                repPos++;
                if (repPos >= st0.body.size()) {
                    repPos = 0;
                    repIter++;
                    if (repIter >= st0.times) {
                        repIter = 0;
                        cursor++;
                    }
                }
            } else {
                cursor++;
            }
            executed.put(st.hostId, st);
            triggers = new ForcedWinEngine.TriggerTargets(st.triggerRef);
            picks = new ForcedWinEngine.LibraryPicks(st.pickName);
            n.action = sa;
            return n;
        }

        /** The live/copy ability for a step, prepared (targets, mana colour), or null when it cannot be played now. */
        static SpellAbility locate(Game g, Player me, Step st) {
            final Card host = g.findById(st.hostId);
            if (host == null) {
                return null;
            }
            if (st.kind == AbilKind.FLOAT) {
                // solver-gaps-1015: the colour the line spends it on (a returned land's float, or any-colour mana)
                final int colour = st.floatColor >= 0 ? st.floatColor : firstColour(st.anyColors);
                for (SpellAbility m : host.getManaAbilities()) {
                    final SpellAbility a = m.copy(me);
                    a.setActivatingPlayer(me);
                    if (!st.desc.isEmpty() && !st.desc.equals(a.getDescription())) {
                        continue;
                    }
                    if (colour >= 0 && colour < 5) {
                        if (a.getManaPart() == null || !a.canProduce(ForcedWinModel.SHORT[colour])) {
                            continue;
                        }
                        a.setManaExpressChoice(ColorSet.fromMask(ForcedWinModel.FORGE_COLOR[colour]));
                    }
                    if (!a.canPlay() || !ComputerUtilCost.canPayCost(a, me, false)) {
                        continue;
                    }
                    return a;
                }
                return null;
            }
            if (st.kind == AbilKind.LAND) {
                for (SpellAbility sa : host.getAllPossibleAbilities(me, true)) {
                    if (sa.isLandAbility()) {
                        return sa;
                    }
                }
                return null;
            }
            if (st.kind == AbilKind.ENGINE) {
                for (SpellAbility sa0 : host.getAllPossibleAbilities(me, true)) {
                    final String ck = sa0.getPayCosts() == null ? "" : sa0.getPayCosts().toString();
                    if (!st.desc.equals(sa0.getDescription()) || !st.costKey.equals(ck)) {
                        continue;
                    }
                    final SpellAbility sa = sa0.copy(me);
                    sa.setActivatingPlayer(me);
                    if (!st.targetRefs.isEmpty() && !ForcedWinEngine.applyRefs(sa, st.targetRefs, g)) {
                        continue;
                    }
                    if (st.targetRefs.isEmpty() && ForcedWinProbe.targetsAnything(sa)) {
                        continue;
                    }
                    if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false) || !sa.isTargetNumberValid()) {
                        continue;
                    }
                    return sa;
                }
                return null;
            }
            SpellAbility sa = ForcedWinProbe.locate(host, me, st.desc, st.costKey, st.kind == AbilKind.ACTIVATE);
            if (sa == null) {
                return null;
            }
            sa = sa.copy(me);
            sa.setActivatingPlayer(me);
            if (st.targetVariant != null && !ForcedWinProbe.setPlayerTargets(sa, me, st.targetVariant)) {
                return null;
            }
            if (st.phyrexianLife && sa.hasParam("AIPhyrexianPayment")) {
                sa.getMapParams().remove("AIPhyrexianPayment");
            }
            directAnyMana(sa, st);
            if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) {
                return null;
            }
            return sa;
        }

        /** The first coloured choice of a step's any-colour mana, or -1. */
        static int firstColour(List<Integer> cols) {
            for (int c : cols) {
                if (c >= 0 && c < 5) {
                    return c;
                }
            }
            return -1;
        }

        /**
         * solver-gaps-1015: make a step's any-colour mana (Manamorphose, a ritual of any colour) come out in the colours the
         * line spends it on, through the mana part's express choice (as Forge AI's own payment does).
         */
        static void directAnyMana(SpellAbility sa, Step st) {
            if (st.anyColors.isEmpty()) {
                return;
            }
            final int first = firstColour(st.anyColors);
            if (first < 0) {
                return;
            }
            final StringBuilder sb = new StringBuilder();
            for (int c : st.anyColors) {
                sb.append(sb.length() == 0 ? "" : " ").append(ForcedWinModel.SHORT[c >= 0 && c < 5 ? c : first]);
            }
            for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
                if (l.getManaPart() != null) {
                    l.getManaPart().setExpressChoice(sb.toString());
                }
            }
        }

        /** A pick of a resolving step: its pile / tutor / put-back answer among the offered cards. */
        Card pick(SpellAbility source, CardCollection choices) {
            if (source == null || source.getHostCard() == null) {
                return null;
            }
            if (picks.forced != null) {
                final Card forced = picks.pick(source.getActivatingPlayer(), List.of(ZoneType.Library), source, choices,
                        source.getActivatingPlayer());
                if (forced != null) {
                    return forced;
                }
            }
            final Step st = executed.get(source.getHostCard().getId());
            if (st == null) {
                return null;
            }
            final List<Integer> want = !st.pile.isEmpty() ? st.pile : !st.picks.isEmpty() ? st.picks : st.putBack;
            for (int id : want) {
                if (pilePicked.containsKey(id) && pilePicked.get(id) == System.identityHashCode(source)) {
                    continue;
                }
                for (Card c : choices) {
                    if (c.getId() == id) {
                        pilePicked.put(id, System.identityHashCode(source));
                        return c;
                    }
                }
            }
            return null;
        }

        /** An order of a resolving step's cards going to the library: the line's order (first = top). */
        CardCollectionView order(CardCollectionView cards, ZoneType destination, SpellAbility source) {
            if (destination != ZoneType.Library || source == null || source.getHostCard() == null) {
                return null;
            }
            final Step st = executed.get(source.getHostCard().getId());
            if (st == null) {
                return null;
            }
            final List<Integer> want = !st.pile.isEmpty() ? st.pile : st.putBack;
            if (want.isEmpty()) {
                // a look at our own top cards (Ponder): keep them as they are
                final CardCollection keep = new CardCollection(cards);
                Collections.reverse(keep);
                return keep;
            }
            final List<Card> top = new ArrayList<>();
            for (int id : want) {
                for (Card c : cards) {
                    if (c.getId() == id) {
                        top.add(c);
                    }
                }
            }
            final List<Card> rest = new ArrayList<>();
            for (Card c : cards) {
                if (!top.contains(c)) {
                    rest.add(c);
                }
            }
            // Forge moves each card to the top in the returned order: the last one ends on top
            final CardCollection ordered = new CardCollection();
            for (int i = rest.size() - 1; i >= 0; i--) {
                ordered.add(rest.get(i));
            }
            for (int i = top.size() - 1; i >= 0; i--) {
                ordered.add(top.get(i));
            }
            return ordered;
        }
    }

    /** Our counterspell for the opponent's spell on top of the stack when it targets one of our spells, or null. */
    static SpellAbility protect(Game g, Player me) {
        if (g.getStack().isEmpty()) {
            return null;
        }
        final SpellAbility top = g.getStack().peekAbility();
        if (top == null || top.getActivatingPlayer() == me || !targetsOurStack(g, top, me)) {
            return null;
        }
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                if (sa.getApi() != ApiType.Counter || !sa.usesTargeting()) {
                    continue;
                }
                final SpellAbility cs = sa.copy(me);
                cs.setActivatingPlayer(me);
                if (!cs.canTargetSpellAbility(top)) {
                    continue;
                }
                cs.resetTargets();
                cs.getTargets().add(top);
                if (!cs.canPlay() || !ComputerUtilCost.canPayCost(cs, me, false)) {
                    continue;
                }
                return cs;
            }
        }
        return null;
    }

    static boolean targetsOurStack(Game g, SpellAbility sa, Player me) {
        for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
            if (!l.usesTargeting()) {
                continue;
            }
            for (SpellAbility t : l.getTargets().getTargetSpells()) {
                if (t.getActivatingPlayer() == me) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ play-out controllers

    /** Our seat in a play-out: the line, our counterspells against counters of it, Forge AI for the rest. */
    static final class LineAi extends ForcedWinEngine.GuardedAi {
        final Follower f;
        String protectedBy;
        /** Part B rollouts: after the line our seat plays on as Forge AI (forced-win play-outs stop acting). */
        boolean continueAfter;

        LineAi(Game g, Player p, forge.LobbyPlayer lp, Follower f) {
            super(g, p, lp);
            this.f = f;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            final SpellAbility guard = protect(g, getPlayer());
            if (guard != null) {
                protectedBy = guard.getHostCard().getName();
                return capped(List.of(guard));
            }
            if (!f.done && !f.broken) {
                final Follower.Next n = f.next(g, getPlayer());
                if (n.action != null) {
                    return capped(List.of(n.action));
                }
                if (n.waiting) {
                    return null;
                }
                if (n.broken) {
                    return null;
                }
            }
            if (f.done && continueAfter) {
                return super.chooseSpellAbilityToPlay();
            }
            if (f.done && !g.getStack().isEmpty()) {
                return null;
            }
            return f.done ? null : super.chooseSpellAbilityToPlay();
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional,
                Player decider) {
            final Card c = decider == getPlayer() ? f.pick(sa, fetchList) : null;
            return c != null ? c : super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                    selectPrompt, isOptional, decider);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                SpellAbility source) {
            final CardCollectionView o = f.order(cards, destinationZone, source);
            return o != null ? o : super.orderMoveToZoneList(cards, destinationZone, source);
        }

        @Override
        public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
                Card cardToShow, Map<String, Object> params) {
            if (message != null && message.toLowerCase().contains("shuffle")) {
                return false;
            }
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
            return ImmutablePair.of(new CardCollection(topN), new CardCollection());
        }

        @Override
        public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
            super.orderAndPlaySimultaneousSa(f.triggers.forced == null ? abilities : f.triggers.apply(getGame(), getPlayer(), abilities));
        }

        @Override
        public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kind, Boolean defaultVal) {
            if (kind == BinaryChoiceType.TapOrUntap && defaultVal != null) {
                return defaultVal;
            }
            return super.chooseBinary(sa, question, kind, defaultVal);
        }

        @Override
        public boolean confirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
            return f.triggers.confirms(wrapper) || super.confirmTrigger(wrapper);
        }

        /** The line's alpha strike (if the rules refuse it, the guard hands the declaration to Forge AI). */
        @Override
        void declareFirst(Player attacker, forge.game.combat.Combat combat) {
            if (f.attackPending && !f.done) {
                alphaStrike(attacker, combat);
                f.attackPending = false;
                f.cursor++;
                return;
            }
            super.declareFirst(attacker, combat);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
            return ImmutablePair.of(new CardCollection(topN), new CardCollection());
        }

        @Override
        public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min,
                int max, CardCollectionView visibleToChooser) {
            final CardCollection ordinary = super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
            return avoid(ordinary, validCards, f.reserved());
        }
    }

    /** Attack with every creature that can attack the opponent. */
    static void alphaStrike(Player attacker, forge.game.combat.Combat combat) {
        for (Card c : attacker.getCreaturesInPlay()) {
            for (Player o : attacker.getOpponents()) {
                if (forge.game.combat.CombatUtil.canAttack(c, o)) {
                    combat.addAttacker(c, o);
                    break;
                }
            }
        }
    }

    /** The combat of the live seat's last alpha strike (solver-gaps-1015: the rules may refuse it and ask again). */
    private forge.game.combat.Combat alphaCombat;

    /** The live seat's attack: the line's alpha strike when it is waiting for one; null = not ours. */
    public boolean answerAttack(Player attacker, forge.game.combat.Combat combat) {
        if (combat == alphaCombat) {
            // solver-gaps-1015: asked again in the same combat = the rules refused the alpha strike (an attack restriction
            // or requirement); clear it and let the seat declare natively, or the rules ask for ever
            alphaCombat = null;
            combat.clearAttackers();
            receipt("FORCED_WIN attack refused turn=" + me.getGame().getPhaseHandler().getTurn() + " -> native declaration");
            return false;
        }
        if (active == null || active.done || !active.attackPending || attacker != me) {
            return false;
        }
        alphaStrike(attacker, combat);
        alphaCombat = combat;
        active.attackPending = false;
        active.cursor++;
        receipt("FORCED_WIN attack turn=" + me.getGame().getPhaseHandler().getTurn() + " attackers=" + combat.getAttackers().size());
        return true;
    }

    /** Swap reserved cards out of a discard choice for unreserved valid ones (size kept). */
    static CardCollection avoid(CardCollection ordinary, CardCollectionView valid, Set<Integer> reserved) {
        if (ordinary == null || reserved.isEmpty()) {
            return ordinary;
        }
        final CardCollection out = new CardCollection();
        for (Card c : ordinary) {
            if (!reserved.contains(c.getId())) {
                out.add(c);
                continue;
            }
            Card alt = null;
            for (Card v : valid) {
                if (!reserved.contains(v.getId()) && !ordinary.contains(v) && !out.contains(v)) {
                    alt = v;
                    break;
                }
            }
            out.add(alt != null ? alt : c);
        }
        return out;
    }

    /** The opponent in a play-out: Forge AI, plus (when asked) a counter of our spell number {@code counterAt}. */
    static final class AdversaryAi extends ForcedWinEngine.GuardedAi {
        final Player victim;
        final int counterAt;
        final Set<Integer> seenSpells = new HashSet<>();
        int seen;
        String countered;

        AdversaryAi(Game g, Player p, forge.LobbyPlayer lp, Player victim, int counterAt) {
            super(g, p, lp);
            this.victim = victim;
            this.counterAt = counterAt;
        }

        static boolean holdsCounter(Player p) {
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                for (SpellAbility sa : c.getSpellAbilities()) {
                    if (sa.getApi() == ApiType.Counter) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            if (!g.getStack().isEmpty()) {
                final SpellAbilityStackInstance si = g.getStack().peek();
                final SpellAbility top = si.getSpellAbility();
                if (top != null && top.getActivatingPlayer() == victim && top.isSpell() && seenSpells.add(si.getId())) {
                    final int index = seen++;
                    if (index == counterAt) {
                        final SpellAbility cs = counterFor(top);
                        if (cs != null) {
                            countered = top.getHostCard().getName() + " with " + cs.getHostCard().getName();
                            return capped(List.of(cs));
                        }
                    }
                }
            }
            return super.chooseSpellAbilityToPlay();
        }

        private SpellAbility counterFor(SpellAbility top) {
            final Player p = getPlayer();
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                for (SpellAbility sa : c.getAllPossibleAbilities(p, true)) {
                    if (sa.getApi() != ApiType.Counter || !sa.usesTargeting()) {
                        continue;
                    }
                    final SpellAbility cs = sa.copy(p);
                    cs.setActivatingPlayer(p);
                    if (!cs.canTargetSpellAbility(top)) {
                        continue;
                    }
                    cs.resetTargets();
                    cs.getTargets().add(top);
                    if (!cs.canPlay() || !ComputerUtilCost.canPayCost(cs, p, false)) {
                        continue;
                    }
                    return cs;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ diagnostics

    public String summary() {
        return "decisions=" + decisions + " gated=" + gated + " evaluated=" + evaluated + " taken=" + taken + " likelyRuns="
                + likelyRuns + " likelyTaken=" + likelyTaken + " replans="
                + replans + " plannerMs=" + plannerNanos / 1_000_000 + " engineMs=" + engineNanos / 1_000_000
                + " copies=" + copies + " " + reader.probeStats.summary();
    }

    /** Effects of a line's steps (for reports). */
    static String describe(Line l) {
        final StringBuilder sb = new StringBuilder();
        for (Step s : l.steps) {
            sb.append(s).append(' ');
            if (!s.effects.isEmpty()) {
                sb.append(s.effects).append(' ');
            }
        }
        return sb.toString().trim();
    }

    static boolean drawsAnything(List<Eff> effs) {
        for (Eff e : effs) {
            if (e.kind == EffKind.DRAW) {
                return true;
            }
        }
        return false;
    }

    static ACard first(List<ACard> xs) {
        return xs.isEmpty() ? null : xs.get(0);
    }

    static PlayerControllerAi ai(Player p) {
        return p.getController() instanceof PlayerControllerAi a ? a : null;
    }
}
