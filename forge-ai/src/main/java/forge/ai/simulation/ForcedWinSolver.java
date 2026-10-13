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
        public int maxSteps = 4000;
        public long seed = 20261012L;

        public static Config fromProps(java.util.function.Function<String, String> get) {
            final Config c = new Config();
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
                + engineNodes + " engineMs=" + engineMs;
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
                    + me.getGame().getRegisteredPlayers().indexOf(me.getRegisteredPlayer());
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
    final ForcedWinModel.Reader reader = new ForcedWinModel.Reader();
    private Follower active;
    private String noLineKey;
    private int decisionIndex;
    /** Diagnostics: decisions asked, gated off by the width search, engine-evaluated, lines taken. */
    public int decisions, gated, evaluated, taken, replans;
    public long plannerNanos, engineNanos;
    public int copies;

    public ForcedWinSolver(Player me, Config cfg) {
        this.me = me;
        this.cfg = cfg;
        this.reader.seed = cfg.seed;
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
        final Follower.Next n = active.next(live, me);
        if (n.action != null) {
            return new Decision(true, n.action);
        }
        active = null;
        return Decision.NONE;
    }

    // ------------------------------------------------------------------ offense: width search, then engine

    /** The position key the width search's verdict is cached under (same key, same verdict). */
    private static String positionKey(Game live, Player me) {
        final StringBuilder sb = new StringBuilder();
        sb.append(live.getPhaseHandler().getTurn()).append(live.getPhaseHandler().getPhase()).append('|')
                .append(me.getLife()).append('|').append(me.getManaPool().totalMana()).append('|')
                .append(me.getLandsPlayedThisTurn()).append('|').append(live.getStack().getSpellsCastThisTurn().size())
                .append('|').append(me.getCardsIn(ZoneType.Library).size()).append('h');
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
        final Position pos = readPosition(live, null);
        if (pos == null) {
            noLineKey = key;
            return null;
        }
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.nodes = cfg.nodes;
        // POST-FREEZE 4: same-turn lines first (they are preferred, and a pass-the-turn search can fill the line cap
        // before it reaches one); lines that pass the turn only when this turn has none
        lim.horizon = 1;
        ForcedWinPlanner.Result res = ForcedWinPlanner.plan(pos, lim);
        if (res.lines.isEmpty() && cfg.horizon >= 2) {
            final int first = res.nodes;
            lim.horizon = cfg.horizon;
            res = ForcedWinPlanner.plan(pos, lim);
            res.nodes += first;
        }
        plannerNanos += System.nanoTime() - t0;
        lastPlannerNodes = res.nodes;
        if (res.lines.isEmpty()) {
            lastOutcome = "gate";
            final Line engine = engineFallback(live, pos, index, t0);
            if (engine != null) {
                lastOutcome = "engine-take";
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
        final Eval best = evaluateTop(live, res.lines, index);
        engineNanos += System.nanoTime() - t1;
        if (best == null || best.pBelief < cfg.threshold || best.pUnopposed < 1.0) {
            lastOutcome = "declined";
            noLineKey = key;
            receipt("FORCED_WIN declined" + tag() + " turn=" + pos.turn + " best=" + (best == null ? "none" : best)
                    + " ms=" + (System.nanoTime() - t0) / 1_000_000);
            return null;
        }
        receipt("FORCED_WIN take" + tag() + " turn=" + pos.turn + " " + best + " ms=" + (System.nanoTime() - t0) / 1_000_000
                + " copies=" + copies);
        lastOutcome = "take";
        return best.line;
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
        p.devotionBlue = b.devotionBlue;
        p.emptyDrawWinLive = b.emptyDrawWinLive;
        p.library.addAll(b.library);
        p.graveyard.addAll(b.graveyard);
        p.knownOrder = b.knownOrder;
        p.librarySize = b.librarySize;
        p.canDraw = b.canDraw;
        p.pendingId = b.pendingId;
        return p;
    }

    /** Our counterspell for the opponent's spell on top when resolving it gives them a forced win; else no opinion. */
    private Decision defendCounter(Game live) {
        if (!cfg.defense) {
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
        if (!cfg.defense || p == me || sa == null || sa.getActivatingPlayer() != me || valid == null || valid.isEmpty()
                || max != 1) {
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
        engineTurn = pos.turn;
        engineRuns++;
        lastOutcome = "gate+engine";
        final ForcedWinEngine.Limits el = new ForcedWinEngine.Limits();
        el.nodes = cfg.engineNodes;
        final long t1 = System.nanoTime();
        final Line line = ForcedWinEngine.search(live, me, el, engineStats, t1 + cfg.engineMs * 1_000_000L,
                mix(cfg.seed, 7_000_000L + index));
        engineNanos += System.nanoTime() - t1;
        receipt("FORCED_WIN engine" + tag() + " turn=" + pos.turn + " found=" + (line != null) + " " + engineStats.summary()
                + (line == null ? "" : " " + line));
        if (line == null) {
            return null;
        }
        engineFound++;
        evaluated++;
        final Eval e = evaluate(live, line, mix(cfg.seed, index * 1000L + 999), System.nanoTime() + cfg.budgetMs * 1_000_000L);
        receipt("FORCED_WIN eval engine " + e);
        if (e.pUnopposed < 1.0 || e.pBelief < cfg.threshold) {
            return null;
        }
        receipt("FORCED_WIN take" + tag() + " turn=" + pos.turn + " " + e + " ms=" + (System.nanoTime() - t0) / 1_000_000);
        return line;
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
        final long deadline = System.nanoTime() + cfg.budgetMs * 1_000_000L;
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
            receipt("FORCED_WIN eval #" + i + " " + e);
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

    /** P(win | unopposed) and P(win | belief worlds, adversarial counters) of one line. */
    Eval evaluate(Game live, Line line, long seed, long deadline) {
        final Eval e = new Eval(line);
        final Outcome u = playout(live, line, mix(seed, 0), 0, -1);
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
            Outcome worst = playout(live, line, ws, w, -1);
            if (worst.protectedBy != null) {
                prot.add(worst.protectedBy);
            }
            if (worst.won && cfg.adversary && worst.oppHasCounter) {
                for (int j = 0; j < spells && worst.won; j++) {
                    final Outcome o = playout(live, line, ws, w, j);
                    if (o.protectedBy != null) {
                        prot.add(o.protectedBy);
                    }
                    if (!o.won) {
                        worst = o;
                    }
                }
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
     */
    Outcome playout(Game live, Line line, long seed, int world, int counterAt) {
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
                for (Player o : cm.getOpponents()) {
                    final Player liveOpp = (Player) live.getPlayer(o.getId());
                    hideOrSample(live, me, liveOpp, o, world == 0, rng);
                }
                shuffleUnknownOwnLibrary(cm, line, rng);
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(mix(seed, 3)));
            final Follower f = new Follower(line);
            final LineAi lineAi = new LineAi(g, cm, cm.getController().getLobbyPlayer(), f);
            cm.dangerouslySetController(lineAi);
            AdversaryAi adv = null;
            for (Player o : g.getPlayers()) {
                if (o != cm) {
                    adv = new AdversaryAi(g, o, o.getController().getLobbyPlayer(), cm, counterAt);
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
            }
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
        if (!hide) {
            final int need = hand.size() - keep.size();
            newHand.addAll(pool.subList(0, need));
            pool.subList(0, need).clear();
        }
        opp.getZone(ZoneType.Hand).setCards(newHand);
        opp.getZone(ZoneType.Library).setCards(pool);
    }

    /** Our library order is unknown to us unless the line built it: shuffle it in each world. */
    static void shuffleUnknownOwnLibrary(Player me, Line line, Random rng) {
        if (line.knownLibrary) {
            return;
        }
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        Collections.shuffle(lib, rng);
        me.getZone(ZoneType.Library).setCards(lib);
    }

    // ------------------------------------------------------------------ live answers (choice asks of the line)

    public Card answerPick(ZoneType destination, List<ZoneType> origin, SpellAbility source, CardCollection choices,
            Player decider) {
        return active == null || decider != me ? null : active.pick(source, choices);
    }

    public CardCollectionView answerOrder(CardCollectionView cards, ZoneType destination, SpellAbility source) {
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
                if (!line.steps.get(i).pass) {
                    out.add(line.steps.get(i).hostId);
                }
            }
            return out;
        }

        Next next(Game g, Player me) {
            final Next n = new Next();
            if (!g.getStack().isEmpty()) {
                // our own spell resolves first; other stack objects are answered by the caller's protection logic
                n.waiting = true;
                return n;
            }
            final PhaseHandler ph = g.getPhaseHandler();
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
            final Step st = line.steps.get(cursor);
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
                why = "missing:" + st;
                n.broken = true;
                n.why = why;
                return n;
            }
            cursor++;
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
                for (SpellAbility m : host.getManaAbilities()) {
                    final SpellAbility a = m.copy(me);
                    a.setActivatingPlayer(me);
                    if (!st.desc.isEmpty() && !st.desc.equals(a.getDescription())) {
                        continue;
                    }
                    if (st.floatColor >= 0) {
                        if (a.getManaPart() == null || !a.canProduce(ForcedWinModel.SHORT[st.floatColor])) {
                            continue;
                        }
                        a.setManaExpressChoice(ColorSet.fromMask(ForcedWinModel.FORGE_COLOR[st.floatColor]));
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
            if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) {
                return null;
            }
            return sa;
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
    static final class LineAi extends LookaheadSearch.RolloutAi {
        final Follower f;
        String protectedBy;

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

        @Override
        public void declareAttackers(Player attacker, forge.game.combat.Combat combat) {
            if (f.attackPending && !f.done) {
                alphaStrike(attacker, combat);
                f.attackPending = false;
                f.cursor++;
                return;
            }
            super.declareAttackers(attacker, combat);
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

    /** The live seat's attack: the line's alpha strike when it is waiting for one; null = not ours. */
    public boolean answerAttack(Player attacker, forge.game.combat.Combat combat) {
        if (active == null || active.done || !active.attackPending || attacker != me) {
            return false;
        }
        alphaStrike(attacker, combat);
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
    static final class AdversaryAi extends LookaheadSearch.RolloutAi {
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
        return "decisions=" + decisions + " gated=" + gated + " evaluated=" + evaluated + " taken=" + taken + " replans="
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
