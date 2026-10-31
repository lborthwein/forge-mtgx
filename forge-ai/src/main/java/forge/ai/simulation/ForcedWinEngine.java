package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import forge.ai.AiCache;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Lane doomsday-solver-1012: the forced-win solver's engine search, for what the cheap width search cannot model
 * (abilities that target cards, costs that sacrifice or exile other cards, casting from the graveyard, combat). A
 * depth-first search on Forge game copies over OUR legal actions this turn: each node is a copy at our main-phase
 * priority with an empty stack; a move is one legal ability (its targets as Forge AI chooses them, or each of a few of
 * our own permanents) or "attack with everything"; the move is played in a fresh copy of the node with the opponent
 * acting through its visible board (its hidden hand removed) until our next main-phase priority with an empty stack.
 * A transposition set on {@link PlayoutKeys#stateKey} prunes repeated positions; the previous move's ability is tried
 * first (loops). Budgets: nodes, depth and wall time.
 *
 * <p>Copies of copies keep their own id counters (a clone of the parent's), so no copy ever touches the live game's
 * counters or reuses an id its own game already holds.</p>
 */
final class ForcedWinEngine {

    static final class Stats {
        int nodes, copies, moves;
        /** labeller-v2 (a): moves applied to rank choice variants (part of {@link #moves}). */
        int cacApplies;
        /** solver-gaps-1015: moves cut at the wall (a step still running at the search's deadline, or a refused attack). */
        int cut;
        /** solver-gaps-1015: our attack declarations the rules refused (handed to Forge AI). */
        int refusedAttacks;
        long nanos;
        /** search-bones-1015 (c): nodes pruned by the progress table, loops found, body repetitions applied. */
        int pruned, loops, loopReps, loopWins;
        /**
         * bvw-step1-build-1024 (read-only): move steps played (mainLoopStep calls inside moves), searches stopped by the
         * deadline, and by a node or step cap.
         */
        long steps;
        int deadlineStops, capStops;
        /**
         * bvw-step1-build-1024 (review #10): searches whose pass stopped at the node cap with nodes spent by EARLIER
         * searches (the cap compares the solver's lifetime node count; known generator limit), counted apart from
         * {@link #capStops} (this search's own node, step or card cap).
         */
        int lifetimeStops;
        /**
         * bvw-step1-build-1024 (review #11, read-only): fast-forwards stopped because the path's copies plus the loop's
         * live copies would exceed pathCards (also counted in {@link #pathPrunes}), and the most cards the path and a
         * fast-forward's live copies held at once (by the copy-time card counts pathCards uses).
         */
        int loopCardStops;
        long peakLive;
        /** bvw-step1-build-1024 (read-only): cards in the game copies the searches made (a copy's cost, time and memory). */
        long cards;
        /** bvw-step1-build-1024 (read-only): branches not entered because the path's copies would exceed pathCards. */
        int pathPrunes;
        /** solver-cap12 round 3 (R3-3): nodes whose moves were value-ordered, and value passes that failed. */
        int ordered, orderFails;

        String summary() {
            return "engineNodes=" + nodes + " engineCopies=" + copies + " moves=" + moves + " engineMs=" + nanos / 1_000_000
                    + (cut > 0 ? " engineCut=" + cut : "") + (refusedAttacks > 0 ? " refusedAttacks=" + refusedAttacks : "")
                    + (loops > 0 || pruned > 0 ? " pruned=" + pruned + " loops=" + loops + " loopReps=" + loopReps
                            + " loopWins=" + loopWins : "")
                    + (ordered > 0 || orderFails > 0 ? " ordered=" + ordered + " orderFails=" + orderFails : "");
        }
    }

    // ------------------------------------------------------------------ the wall (solver-gaps-1015)

    /**
     * A step of a game loop the solver drives ran past its wall, or the rules refused a seat's attack declaration twice
     * in one combat. Thrown from inside the rules engine's own loops (the seat's controller); the play-out that runs the
     * step catches it and drops the play-out. No stack trace: it is control flow.
     */
    static final class OutOfBudget extends RuntimeException {
        OutOfBudget(String why) {
            super(why, null, false, false);
        }
    }

    /** Throw {@link OutOfBudget} when {@code wall} (System.nanoTime(); 0 = none) has passed. */
    static void checkWall(long wall, String where) {
        if (wall != 0 && System.nanoTime() > wall) {
            throw new OutOfBudget("wall: " + where);
        }
    }

    /**
     * One combat's attack declarations by a seat the solver drives. When the rules refuse a declaration (an attack
     * restriction such as "no more than one creature can attack", or a requirement such as "attacks Gideon if able"
     * broken by an attack at the player) they ask the same seat again with the same Combat, for ever if the answer does
     * not change: exploit-panel-1015's games hung 110 minutes in the engine's alpha strike. The first refusal clears the
     * declaration and hands it to Forge AI; a second ends the play-out.
     */
    static final class AttackGuard {
        private Combat last;
        private int refused;
        /** Declarations the rules refused, over all combats. */
        int total;

        /** 0 = this combat's first declaration; 1 = the rules refused it (the combat is cleared). Throws after that. */
        int next(Combat combat, long wall) {
            checkWall(wall, "attack declaration");
            if (combat == last) {
                refused++;
            } else {
                last = combat;
                refused = 0;
            }
            if (refused > 1) {
                throw new OutOfBudget("attack declaration refused " + refused + " times");
            }
            if (refused == 1) {
                combat.clearAttackers();
                total++;
            }
            return refused;
        }
    }

    /**
     * Forge AI as a seat in the solver's play-outs, bounded: its attack declarations go through an {@link AttackGuard},
     * and its declarations and priority answers check the wall. Subclasses script the first declaration of a combat.
     */
    static class GuardedAi extends LookaheadSearch.RolloutAi {
        /** The wall (System.nanoTime()); 0 = none. */
        long wall;
        private final AttackGuard attackGuard = new AttackGuard();
        /**
         * bvw-step1-build-1024 (0 = none, the default, and then nothing changes): budget=counter's deterministic stand-in
         * for the wall: this seat's priority answers and combat declarations in one play-out or engine move, at most.
         * Past it the play-out (or move) is cut as at the wall (an engine loop that keeps asking the seat, such as a
         * declaration the rules refuse for ever, ends here instead of running without bound).
         */
        long callLimit;
        private long calls;

        GuardedAi(Game g, Player p, forge.LobbyPlayer lp) {
            super(g, p, lp);
        }

        GuardedAi bounded(long wall) {
            this.wall = wall;
            return this;
        }

        /** bvw-step1-build-1024: cap this seat's calls (budget=counter; 0 = none). */
        GuardedAi callLimited(long limit) {
            this.callLimit = limit;
            return this;
        }

        private void checkCalls(String where) {
            if (callLimit > 0 && ++calls > callLimit) {
                throw new OutOfBudget("calls: " + where + " (" + callLimit + ")");
            }
        }

        /** Attack declarations of this seat the rules refused. */
        int refusedAttacks() {
            return attackGuard.total;
        }

        /** This combat's first attack declaration: Forge AI's, unless a subclass scripts it. */
        void declareFirst(Player attacker, Combat combat) {
            super.declareAttackers(attacker, combat);
        }

        @Override
        public final void declareAttackers(Player attacker, Combat combat) {
            checkCalls("attack declaration");
            if (attackGuard.next(combat, wall) == 0) {
                declareFirst(attacker, combat);
            } else {
                super.declareAttackers(attacker, combat);
            }
        }

        @Override
        public void declareBlockers(Player defender, Combat combat) {
            checkWall(wall, "block declaration");
            checkCalls("block declaration");
            super.declareBlockers(defender, combat);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            checkWall(wall, "priority");
            checkCalls("priority");
            return super.chooseSpellAbilityToPlay();
        }
    }

    static final class Limits {
        int nodes = 250;
        /** solver-cap12-1030 (CAP-2; false = off): pass 1's share of {@link #nodes} counted from the search's start (pass 2 always was). */
        boolean perSearchNodes;
        int depth = 64;
        int branching = 8;
        int targetVariants = 3;
        /**
         * labeller-v2 (a) (false = v1): every choice of our seat in a move's window (own-zone picks, scry, surveil,
         * discard, library order, shuffle) branches: up to cacVariants answers per choice (Forge AI's included), the
         * others ranked by the static value of the position after the move, from at most cacCap enumerated answers.
         */
        boolean cac;
        int cacVariants = 6, cacCap = 48;
        /** labeller-v2 (e) (false = v1): payment variants of a move: distinct untapped remainders, up to payVariants. */
        boolean pay;
        int payVariants = 3;
        int maxSteps = 1500;
        /** Also search lines that pass the turn once (win by our next turn). */
        boolean passTurn = true;
        /**
         * solver-gaps-1015: the hard wall (System.nanoTime()) no step of a move may run past; 0 = the search's deadline.
         * The deadline only stops new nodes, so a move begun before it may finish up to the wall (a Brain Freeze move
         * that wins just after the deadline is kept).
         */
        long wall;
        /**
         * search-bones-1015 (c), off by default: the transposition table keys a node by its state with the monotone
         * counters taken out ({@link #coarseKey}) and keeps their vector ({@link #progress}); a node that a seen one
         * dominates is pruned, and a move sequence that repeats with progress is fast-forwarded as one REPEAT move.
         */
        boolean cycles;
        /** (c): the most repetitions of a loop body one REPEAT move plays. */
        int repeatCap = 64;
        /**
         * solver-cap12-1030 round 2 (R2-3; false = off, byte-identical): the general loop abstraction, with {@link #cycles}.
         * A child whose {@link #macroKey} equals an ancestor's on this turn's path (up to {@link #loopWindow} moves back)
         * and whose counters dominate the ancestor's with a strict gain (progress, mana in pool or hand) is a loop; the
         * fast-forward keeps checkpoints at 1, 2, 4, ... repetitions and the last, the DFS continues from each (largest
         * first) with the body's own moves dropped (the outlet search), and a repetition is progress only while a gaining
         * counter is below its generic ceiling ({@link #ceilings}: the stop rule). The cap is {@link #loopCap}.
         */
        boolean loopMacro;
        /** Review #154 S2 (loopMacro3; false = off): the loop ceilings count a repeatable mana sink (D5). */
        boolean loopMacro3;
        /**
         * R2-4 (c), engineAnyWindow (false = off): a search rooted outside our main phase (the opponent's end step, say)
         * rests a move at our next priority with an empty stack in the root's turn and phase, not only at our main phase.
         */
        boolean anyWindow;
        int loopWindow = 8;
        int loopCap = 128;
        /**
         * solver-cap12-1030 R3-3 (null = off, byte-identical): the engine's move order. At each node up to {@link #orderCap}
         * distinct moves (and their variants) are applied first, a win returns at once, and the rest are searched in the
         * order of one batched value pass of this valuer (Forge's order on ties or a failed pass); the branching cap then
         * applies in that order.
         */
        ChooseACard.Valuer order;
        int orderCap = 24;
        /** R3-4 (a) (false = off): X branches inside engine moves (up to {@link #xVariants} values, see xValues). */
        boolean xChoice;
        int xVariants = 8;
        /**
         * R3-5 (false = off): the root may be our priority with the opponent's items on the stack (a response): a move
         * also rests at our next priority in the root's turn and phase when the top of the stack is not ours.
         */
        boolean stack;
        /** R3-4 (b) (false = off): an engine move's cost cards branch (COST kind, through MoveAi's own CacHook). */
        boolean costChoice;
        /**
         * search-bones-1015: our library's known top in the root copy (pile memory); the rest is shuffled there. -1 (the
         * default) keeps the real order.
         */
        int ownKnownTop = -1;
        /** honest-belief-1020: the solver's re-draw of the opponents' hidden cards at the root (null = off). */
        ForcedWinSolver.Redraw redraw;
        /**
         * bvw-step1-build-1024 (0 = none, the default, and then nothing changes): the move steps one search may play;
         * past it no new node starts (budget=counter's deterministic stand-in for the engine deadline).
         */
        int totalSteps;
        /** bvw-step1-build-1024 (0 = none): budget=counter's per-seat call cap in each move ({@link GuardedAi#callLimit}). */
        long callLimit;
        /**
         * bvw-step1-build-1024 (0 = none, the default): the cards one search's game copies may hold in all; past it no new
         * node starts. Copying dominates the engine's time and memory, so this is budget=counter's stand-in for the engine
         * deadline (a deep loop line on a large board grows the copies; steps alone do not see it).
         */
        long totalCards;
        /**
         * bvw-step1-build-1024 (0 = none, the default): budget=counter's memory guard: the cards the copies on the current
         * DFS path (every one alive) may hold; a deeper node is not entered (as at the depth limit).
         */
        long pathCards;
    }

    /** A search node: a game copy at our priority, with its own id scope. */
    static final class Node {
        Game g;
        Player me;
        Object ids;
        /** The node's game turn, and whether the path has passed the turn once (horizon: our next turn). */
        int turn;
        boolean passed;
        final List<Step> path = new ArrayList<>();
        /** search-bones-1015 (c): the node this one was reached from, and this node's progress vector. */
        Node parent;
        long[] pv;
        /** bvw-step1-build-1024: the cards in this node's copy. */
        int cards;
        /** R2-3 (loopMacro): this node's {@link #macroKey}, and on a loop checkpoint node the body whose moves it drops. */
        String mk;
        List<Step> afterLoop;
        /** review #152-6: the loop bodies (bodyKey) already fast-forwarded on this node's path. */
        Set<String> loopsDone;
    }

    static String bodyKey(List<Step> body) {
        final StringBuilder sb = new StringBuilder();
        for (Step b : body) {
            sb.append(b.loopKey()).append(';');
        }
        return sb.toString();
    }

    private static boolean done(Node n, List<Step> body) {
        return n.loopsDone != null && n.loopsDone.contains(bodyKey(body));
    }

    // ------------------------------------------------------------------ (c) cycles with progress (search-bones-1015)

    /**
     * The monotone counters of a position, each "more is better for us": the opponents' life, library and hand taken
     * negative, their poison, our tokens on the battlefield, the storm count, our life. A loop with progress raises one
     * and lowers none.
     */
    static long[] progress(Game g, Player me) {
        long oppLife = 0, oppLib = 0, oppHand = 0, poison = 0;
        for (Player o : me.getOpponents()) {
            oppLife += o.getLife();
            oppLib += o.getCardsIn(ZoneType.Library).size();
            oppHand += o.getCardsIn(ZoneType.Hand).size();
            poison += o.getPoisonCounters();
        }
        int tokens = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isToken()) {
                tokens++;
            }
        }
        return new long[] {-oppLife, -oppLib, -oppHand, poison, tokens, g.getStack().getSpellsCastThisTurn().size(),
                me.getLife(), me.getManaPool().totalMana(), me.getCardsIn(ZoneType.Hand).size()};
    }

    /** The first this-many entries of {@link #progress} are progress; the rest (mana in pool, hand size) are resources. */
    static final int PROGRESS = 7;

    /** a >= b in every progress counter and > in one. */
    static boolean progressed(long[] a, long[] b) {
        boolean more = false;
        for (int i = 0; i < PROGRESS; i++) {
            if (a[i] < b[i]) {
                return false;
            }
            more |= a[i] > b[i];
        }
        return more;
    }

    /** a >= b in every entry (progress and resources): a position b is no better than a. */
    static boolean dominates(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] < b[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * A position's key with the monotone counters and the tokens taken out: the turn and phase, each player's hand (ours
     * by name, theirs by size), battlefield non-token permanents by name with their tapped, sick and counter state,
     * graveyard and exile by name, and the top seven of our library in order. Ids and timestamps are left out, so a
     * loop that returns to the same board with more tokens, less opponent life or a higher storm count has one key.
     */
    static String coarseKey(Game g, Player me) {
        final StringBuilder sb = new StringBuilder(1024);
        final PhaseHandler ph = g.getPhaseHandler();
        sb.append(ph.getTurn()).append(ph.getPhase()).append('|');
        for (Player p : g.getPlayers()) {
            sb.append(p == me ? "me" : "op").append('|');
            if (p == me) {
                names(sb, p.getCardsIn(ZoneType.Hand), false);
            } else {
                sb.append("h").append(p.getCardsIn(ZoneType.Hand).size());
            }
            sb.append("|b");
            names(sb, p.getCardsIn(ZoneType.Battlefield), true);
            sb.append("|g");
            names(sb, p.getCardsIn(ZoneType.Graveyard), false);
            sb.append("|x");
            names(sb, p.getCardsIn(ZoneType.Exile), false);
            if (p == me) {
                sb.append("|l");
                int n = 0;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (n++ >= 7) {
                        break;
                    }
                    sb.append(c.getName()).append(',');
                }
            }
            sb.append(';');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ R2-3: the general loop abstraction (loopMacro)

    /**
     * R2-3: the loop key: the turn and phase, our hand by name, the opponents' hand sizes, and every player's non-token
     * battlefield permanents by name with their tapped, sick, counter and attachment state. The graveyard, exile and
     * library are left out: a loop consumes or fills them (escape fuel, self-mill, a land played from the graveyard), so
     * they are the loop's fuel or output, not its state. A coarse key only proposes a loop: a body that can no longer be
     * played fails on replay and ends the fast-forward.
     */
    static String macroKey(Game g, Player me) {
        final StringBuilder sb = new StringBuilder(512);
        final PhaseHandler ph = g.getPhaseHandler();
        sb.append(ph.getTurn()).append(ph.getPhase()).append('|');
        for (Player p : g.getPlayers()) {
            sb.append(p == me ? "me" : "op").append('|');
            if (p == me) {
                names(sb, p.getCardsIn(ZoneType.Hand), false);
            } else {
                sb.append("h").append(p.getCardsIn(ZoneType.Hand).size());
            }
            sb.append("|b");
            names(sb, p.getCardsIn(ZoneType.Battlefield), true);
            sb.append(';');
        }
        return sb.toString();
    }

    /**
     * R2-3: a's counters dominate b's with a strict gain: no progress counter fell, and either some progress counter rose
     * or the resources (mana in pool, hand) did not fall and one rose (an unbounded-mana or draw loop).
     */
    static boolean macroGain(long[] a, long[] b) {
        boolean prog = false;
        for (int i = 0; i < PROGRESS; i++) {
            if (a[i] < b[i]) {
                return false;
            }
            prog |= a[i] > b[i];
        }
        if (prog) {
            return true;
        }
        for (int i = PROGRESS; i < a.length; i++) {
            if (a[i] < b[i]) {
                return false;
            }
        }
        for (int i = PROGRESS; i < a.length; i++) {
            if (a[i] > b[i]) {
                return true;
            }
        }
        return false;
    }

    /** The {@link #progress} index of our life, mana in pool and hand size (the counters with a ceiling). */
    static final int LIFE = 6, MANA = 7, HAND = 8;

    /**
     * R2-3 (the stop rule): the generic ceiling of each counter, past which more of it earns nothing, from the position
     * at the loop's start. Our life: 1 + max(20, the largest life payment among our cards' abilities, twice the
     * opponents' board power). Our tokens: the tokens now plus their life plus their creatures plus one. Mana: the total mana value of our cards outside the library plus the mana costs of our
     * battlefield abilities, plus twice the opponents' life when any of those has X. Hand: our hand plus library (a draw
     * past it loses). The others (the opponents' life, library, hand and poison, the storm count) have none:
     * they end in a terminal state, a failed repetition or the cap.
     */
    static long[] ceilings(Game g, Player me) {
        return ceilings(g, me, false);
    }

    /** As above; {@code sinks} (loopMacro3): plus a repeatable mana sink's mana once per point of their life. */
    static long[] ceilings(Game g, Player me, boolean sinks) {
        final long[] c = new long[PROGRESS + 2];
        java.util.Arrays.fill(c, Long.MAX_VALUE);
        int payLife = 0, oppPower = 0, oppLife = 0;
        long mana = 0, sink = 0;
        boolean x = false;
        for (Player o : me.getOpponents()) {
            oppLife += Math.max(0, o.getLife());
            for (Card k : o.getCreaturesInPlay()) {
                oppPower += Math.max(0, k.getNetCombatDamage());
            }
        }
        for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
                ZoneType.Command}) {
            for (Card k : me.getCardsIn(z)) {
                if (z != ZoneType.Battlefield) {
                    mana += Math.max(0, k.getCMC());
                    x |= k.getManaCost() != null && k.getManaCost().countX() > 0;
                }
                for (SpellAbility sa : k.getSpellAbilities()) {
                    if (sa.getPayCosts() == null) {
                        continue;
                    }
                    for (forge.game.cost.CostPart part : sa.getPayCosts().getCostParts()) {
                        if (part instanceof forge.game.cost.CostPayLife pl) {
                            try {
                                payLife = Math.max(payLife, pl.getAbilityAmount(sa));
                            } catch (RuntimeException e) {
                                // an amount the static read cannot compute: no ceiling from it
                            }
                        }
                    }
                    if (z == ZoneType.Battlefield && !sa.isManaAbility() && sa.getPayCosts().hasManaCost()) {
                        final forge.card.mana.ManaCost mc = sa.getPayCosts().getTotalMana();
                        mana += mc.getCMC();
                        x |= mc.countX() > 0;
                        if (!sa.getPayCosts().hasTapCost() && sa.getPayCosts().getCostParts().stream().noneMatch(
                                p -> p instanceof forge.game.cost.CostSacrifice)) {
                            sink = Math.max(sink, mc.getCMC()); // R3-5: a repeatable mana sink (no tap, no sacrifice)
                        }
                    }
                }
            }
        }
        // our tokens: enough to attack past every blocker for their life at one power each (more earn nothing)
        int oppCreatures = 0;
        for (Player o : me.getOpponents()) {
            oppCreatures += o.getCreaturesInPlay().size();
        }
        int tokens = 0;
        for (Card k : me.getCardsIn(ZoneType.Battlefield)) {
            if (k.isToken()) {
                tokens++;
            }
        }
        c[4] = tokens + oppLife + oppCreatures + 1L;
        c[LIFE] = 1L + Math.max(20, Math.max(payLife, 2L * oppPower));
        // a repeatable mana sink (an activated ability that neither taps nor sacrifices) turns mana into its effect again
        // and again: enough mana for it once per point of their life (and one more) earns value (solver-cap12 round 3)
        c[MANA] = mana + (x ? 2L * oppLife : 0L) + (sinks ? sink * (oppLife + 1L) : 0L);
        c[HAND] = me.getCardsIn(ZoneType.Hand).size() + me.getCardsIn(ZoneType.Library).size();
        return c;
    }

    /** R2-3: a rose over b in some counter that was still below its ceiling (a repetition that is worth playing). */
    static boolean relevantGain(long[] a, long[] b, long[] ceil) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] > b[i] && b[i] < ceil[i]) {
                return true;
            }
        }
        return false;
    }

    private static void names(StringBuilder sb, Iterable<Card> cards, boolean state) {
        final List<String> xs = new ArrayList<>();
        for (Card c : cards) {
            if (state && c.isToken()) {
                continue;
            }
            xs.add(state ? c.getName() + (c.isTapped() ? "T" : "") + (c.isSick() ? "s" : "") + c.getCounters()
                    + (c.getEntityAttachedTo() == null ? "" : "@" + c.getEntityAttachedTo().getName()) : c.getName());
        }
        java.util.Collections.sort(xs);
        sb.append(xs);
    }

    /** (c) the transposition table: coarse key -> the counter vectors seen. */
    private final java.util.Map<String, List<long[]>> table = new java.util.HashMap<>();

    /** (c): true when a seen node dominates this one (it is pruned); else the node is recorded. */
    private boolean seenDominated(Node n) {
        final String k = coarseKey(n.g, n.me);
        final List<long[]> xs = table.computeIfAbsent(k, x -> new ArrayList<>());
        for (long[] v : xs) {
            if (dominates(v, n.pv)) {
                return true;
            }
        }
        xs.removeIf(v -> dominates(n.pv, v));
        xs.add(n.pv);
        return false;
    }

    /**
     * A copy of a played step for replaying it (a loop's repetition, the repeat-first move): with costChoice (R3-4 b) it
     * forces the choice tokens the step played, so each repetition pays its cost with the same cards (by name); off, a
     * plain copy.
     */
    private Step replayCopy(Step b) {
        final Step c = b.copy();
        if (lim.costChoice && b.cacPlayed != null && !b.cacPlayed.isEmpty()) {
            c.cac = new ArrayList<>(b.cacPlayed);
            c.cacPlayed = null;
        }
        return c;
    }

    /** The k-th ancestor of a node (0 = itself), or null. */
    private static Node ancestor(Node n, int k) {
        Node a = n;
        for (int i = 0; i < k && a != null; i++) {
            a = a.parent;
        }
        return a;
    }

    /**
     * (c): the loop body that ends at {@code child}: its last L moves (L <= 3) repeat the L before them, each repetition
     * made progress, and the body has no pass, attack or REPEAT. Null when there is none.
     */
    private static List<Step> loopBody(Node child) {
        final List<Step> p = child.path;
        for (int len = 1; len <= 3; len++) {
            if (p.size() < 2 * len) {
                break;
            }
            boolean same = true;
            for (int i = 0; i < len && same; i++) {
                final Step a = p.get(p.size() - len + i), b = p.get(p.size() - 2 * len + i);
                same = !a.pass && a.kind != AbilKind.ATTACK && a.kind != AbilKind.REPEAT && a.loopKey().equals(b.loopKey());
            }
            if (!same) {
                continue;
            }
            final Node a1 = ancestor(child, len), a2 = ancestor(child, 2 * len);
            if (a1 != null && a2 != null && a1.pv != null && a2.pv != null && progressed(child.pv, a1.pv)
                    && progressed(a1.pv, a2.pv)) {
                return new ArrayList<>(p.subList(p.size() - len, p.size()));
            }
        }
        return null;
    }

    /** The result of fast-forwarding a loop: a win (its path), or the node after the last repetition that progressed. */
    static final class Forward {
        List<Step> win;
        Node node;
    }

    /**
     * (c): play {@code body} again and again from {@code from} (which ends two repetitions already played) until we win,
     * a body step cannot be played or the game would end otherwise, a repetition makes no progress, the cap, or the
     * budget. The path becomes prefix + REPEAT(body, repetitions) (+ the steps of a winning partial repetition).
     */
    private Forward fastForward(Node from, List<Step> body0) {
        st.loops++;
        // R3-4 (b), costChoice only: each repetition replays the cost cards the body's steps played (off: the steps as is)
        final List<Step> body;
        if (lim.costChoice) {
            body = new ArrayList<>();
            for (Step b : body0) {
                body.add(replayCopy(b));
            }
        } else {
            body = body0;
        }
        final int len = body.size();
        final List<Step> prefix = new ArrayList<>(from.path.subList(0, from.path.size() - 2 * len));
        int reps = 2;
        Node cur = from;
        final Forward f = new Forward();
        // R2-3 (loopMacro): the stop rule's ceilings and loopCap apply to the literal fast-forward too (off: unchanged)
        final long[] ceil = lim.loopMacro ? ceilings(from.g, from.me, lim.loopMacro3) : null;
        final int cap = lim.loopMacro ? lim.loopCap : lim.repeatCap;
        while (reps < cap && !out()) {
            Node next = cur;
            final List<Step> partial = new ArrayList<>();
            boolean failed = false;
            for (Step b : body) {
                final Applied a = apply(next, b);
                st.loopReps++;
                if (a == null) {
                    failed = true;
                    break;
                }
                partial.add(b);
                if (a.won) {
                    final List<Step> win = new ArrayList<>(prefix);
                    win.add(Step.repeat(body, reps));
                    if (partial.size() == len) {
                        win.set(win.size() - 1, Step.repeat(body, reps + 1));
                    } else {
                        win.addAll(partial);
                    }
                    st.loopWins++;
                    f.win = win;
                    return f;
                }
                if (a.child == null) {
                    failed = true;
                    break;
                }
                // bvw-step1-build-1024 (review #11): the loop's live copies count against pathCards with the path: from
                // (the DFS child, held by the returned node's parent link), the last progressed repetition, the step
                // being replaced and the new copy. Past the cap the fast-forward stops at the last full repetition.
                final long live = pathCards + from.cards + (cur != from ? cur.cards : 0) + (next != cur ? next.cards : 0)
                        + a.child.cards;
                if (lim.pathCards > 0 && live > lim.pathCards) {
                    st.pathPrunes++;
                    st.loopCardStops++;
                    failed = true;
                    break;
                }
                st.peakLive = Math.max(st.peakLive, live);
                // No parent link inside the loop: only the returned node needs one (parent = from, below); a chain of
                // links kept every repetition's game copy alive until the loop ended.
                a.child.pv = progress(a.child.g, a.child.me);
                a.child.passed = next.passed;
                next = a.child;
            }
            if (failed || !progressed(next.pv, cur.pv) || ceil != null && !relevantGain(next.pv, cur.pv, ceil)) {
                break;
            }
            cur = next;
            reps++;
            if (lethalPastBlockers(cur)) {
                // enough attackers for the opponent's life past their blockers: stop here, so the next node's first
                // move is the attack (its own lethal check), not 64 repetitions and a slow combat with every token
                break;
            }
        }
        // the node after the last repetition that progressed: a REPEAT move to go on from
        final Node n = new Node();
        n.g = cur.g;
        n.cards = cur.cards;
        n.me = cur.me;
        n.ids = cur.ids;
        n.turn = cur.turn;
        n.passed = cur.passed;
        n.parent = from;
        n.pv = cur.pv;
        n.path.addAll(prefix);
        n.path.add(Step.repeat(body, reps));
        f.node = n;
        return f;
    }

    /**
     * R2-3: the loop that ends at {@code child}: the nearest ancestor on this turn's path, up to {@link Limits#loopWindow}
     * moves back with no pass, attack or REPEAT between, whose {@link #macroKey} equals the child's and whose counters the
     * child's dominate with a strict gain worth having ({@link #macroGain}, {@link #relevantGain}). Returns the body (the
     * moves between them; one repetition is already played) or null.
     */
    private List<Step> macroBody(Node child, long[] ceil) {
        final List<Step> p = child.path;
        Node a = child.parent;
        for (int len = 1; len <= lim.loopWindow && a != null && len <= p.size(); len++, a = a.parent) {
            final Step s = p.get(p.size() - len);
            if (s.pass || s.kind == AbilKind.ATTACK || s.kind == AbilKind.REPEAT || a.turn != child.turn
                    || a.afterLoop != null) {
                return null;
            }
            if (a.mk != null && a.pv != null && a.mk.equals(child.mk) && macroGain(child.pv, a.pv)
                    && relevantGain(child.pv, a.pv, ceil)) {
                return new ArrayList<>(p.subList(p.size() - len, p.size()));
            }
        }
        return null;
    }

    /**
     * R2-3: play {@code body} again and again from {@code from} (which ends one repetition) until we win, a body step
     * cannot be played, a repetition gains nothing below the ceilings (the stop rule), {@link Limits#loopCap} or the
     * budget. Returns a win, or the checkpoint nodes (2, 4, 8, ... repetitions and the last; the largest first), each
     * with path prefix + REPEAT(body, n) and the body to drop from its moves (the outlet search).
     */
    private Object macroForward(Node from, List<Step> body0, long[] ceil) {
        st.loops++;
        // review #152-4: the body's own copies (the path's steps are shared with sibling subtrees), located by name (the
        // cards a loop moves around are fungible; Follower.locate)
        final List<Step> body = new ArrayList<>();
        for (Step b : body0) {
            final Step c = replayCopy(b);
            c.byName = true;
            body.add(c);
        }
        final int len = body.size();
        final List<Step> prefix = new ArrayList<>(from.path.subList(0, from.path.size() - len));
        int reps = 1;
        Node cur = from;
        final List<Node> marks = new ArrayList<>();
        final List<Integer> markReps = new ArrayList<>();
        while (reps < lim.loopCap && !out()) {
            Node next = cur;
            final List<Step> partial = new ArrayList<>();
            boolean failed = false;
            for (Step b : body) {
                final Applied a = apply(next, b);
                st.loopReps++;
                if (a == null) {
                    failed = true;
                    break;
                }
                partial.add(b);
                if (a.won) {
                    final List<Step> win = new ArrayList<>(prefix);
                    if (partial.size() == len) {
                        win.add(Step.repeat(body, reps + 1));
                    } else {
                        win.add(Step.repeat(body, reps));
                        win.addAll(partial);
                    }
                    st.loopWins++;
                    return win;
                }
                if (a.child == null) {
                    failed = true;
                    break;
                }
                final long live = pathCards + from.cards + (cur != from ? cur.cards : 0) + (next != cur ? next.cards : 0)
                        + a.child.cards + marksCards(marks) - (marks.contains(cur) ? cur.cards : 0);
                if (lim.pathCards > 0 && live > lim.pathCards) {
                    st.pathPrunes++;
                    st.loopCardStops++;
                    failed = true;
                    break;
                }
                st.peakLive = Math.max(st.peakLive, live);
                a.child.pv = progress(a.child.g, a.child.me);
                a.child.passed = next.passed;
                next = a.child;
            }
            if (failed || !macroGain(next.pv, cur.pv) || !relevantGain(next.pv, cur.pv, ceil)) {
                break;
            }
            cur = next;
            reps++;
            if (Integer.bitCount(reps) == 1) {
                marks.add(cur);
                markReps.add(reps);
                // review #152-5: every power of two is kept (N = 2, 4, ... stay searchable; their copies count in
                // the path-card guard above and while the checkpoints are searched)
            }
            if (lethalPastBlockers(cur)) {
                break;
            }
        }
        if (marks.isEmpty() || marks.get(marks.size() - 1) != cur) {
            marks.add(cur);
            markReps.add(reps);
        }
        final List<Node> outNodes = new ArrayList<>();
        for (int i = marks.size() - 1; i >= 0; i--) {
            final Node c = marks.get(i);
            final int r = markReps.get(i);
            if (r <= 1) {
                continue; // one repetition is the DFS child itself
            }
            final Node n = new Node();
            n.g = c.g;
            n.cards = c.cards;
            n.me = c.me;
            n.ids = c.ids;
            n.turn = c.turn;
            n.passed = c.passed;
            n.parent = from;
            n.pv = c.pv;
            n.mk = macroKey(c.g, c.me);
            n.afterLoop = body;
            n.path.addAll(prefix);
            n.path.add(Step.repeat(body, r));
            outNodes.add(n);
        }
        return outNodes;
    }

    private static long marksCards(List<Node> marks) {
        long n = 0;
        for (Node m : marks) {
            n += m.cards;
        }
        return n;
    }

    private final Limits lim;
    private final Stats st;
    private final long deadline;
    private final long seed;
    private final Set<String> seen = new HashSet<>();
    private int startTurn;
    /** bvw-step1-build-1024: the move steps when this search began, and which budget stopped it (diagnostics). */
    private long stepBase, cardBase, pathCards;
    private boolean stoppedByTime, stoppedByCap, stoppedByLifetime;
    /** bvw-step1-build-1024 (review #10): the current pass's node budget and the node count when it began. */
    private int passCap;
    private long passStart;
    /**
     * Part B (solver-gaps-1015): when not null, the search also keeps this turn's non-winning lines with the best static
     * value of the position they reach (Forge's GameStateEvaluator), as likely-win candidates.
     */
    private List<Cand> collect;
    private int keep;
    private final GameStateEvaluator evaluator = new GameStateEvaluator();

    /** A likely-win candidate: a path of this turn's moves and the static value of the position it reaches. */
    static final class Cand {
        final List<Step> path;
        final int score;
        final String key;

        Cand(List<Step> path, int score, String key) {
            this.path = path;
            this.score = score;
            this.key = key;
        }
    }

    private ForcedWinEngine(Limits lim, Stats st, long deadline, long seed) {
        this.lim = lim;
        this.st = st;
        this.deadline = deadline;
        this.seed = seed;
    }

    /** A this-turn win from the live position (our main-phase priority, empty stack), or null. Never throws. */
    static Line search(Game live, Player liveMe, Limits lim, Stats st, long deadline, long seed) {
        return search(live, liveMe, lim, st, deadline, seed, null, 0);
    }

    /**
     * As {@link #search(Game, Player, Limits, Stats, long, long)}; with {@code likelyOut} (Part B) also the best
     * {@code keep} non-winning lines of this turn whose position values beat the root's, best first.
     */
    static Line search(Game live, Player liveMe, Limits lim, Stats st, long deadline, long seed, List<Line> likelyOut,
            int keep) {
        final long t0 = System.nanoTime();
        final ForcedWinEngine[] held = new ForcedWinEngine[1];
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(seed));
        AiCache.openScope();
        forge.util.IdScope.open();
        // review #149 S2-2: the caller's limits are restored in finally (an engine error must not leave pass state)
        final int nodeCap0 = lim.nodes;
        final boolean passTurn0 = lim.passTurn;
        try {
            final ForcedWinEngine e = new ForcedWinEngine(lim, st, deadline, seed);
            e.stepBase = st.steps;
            e.cardBase = st.cards;
            held[0] = e;
            if (likelyOut != null) {
                e.collect = new ArrayList<>();
                e.keep = Math.max(1, keep) * 3;
            }
            final Node root = new Node();
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1); // storm count, per-turn records
                copyStackIf(copier, live, lim.stack); // R3-5
                root.g = copier.makeCopy();
                root.me = (Player) copier.find(liveMe);
                st.copies++;
                root.cards = cardCount(root.g);
                st.cards += root.cards;
                final Random rng = new Random(LookaheadSearch.mix(seed, 2));
                if (lim.redraw != null) {
                    lim.redraw.apply(live, liveMe, root.g, root.me, rng); // honest-belief-1020
                }
                for (Player o : root.me.getOpponents()) {
                    ForcedWinSolver.hideOrSample(live, liveMe, (Player) live.getPlayer(o.getId()), o, true, rng);
                }
                if (lim.ownKnownTop >= 0) {
                    ForcedWinSolver.shuffleUnknownOwnLibrary(root.me, lim.ownKnownTop, rng);
                }
            }
            root.ids = cloneScope(forge.util.IdScope.capture());
            e.startTurn = root.g.getPhaseHandler().getTurn();
            root.turn = e.startTurn;
            if (lim.cycles) {
                root.pv = progress(root.g, root.me);
                if (lim.loopMacro) {
                    root.mk = macroKey(root.g, root.me);
                }
            }
            // this turn first (same-turn wins are preferred and cheaper); then lines that pass the turn once
            final boolean twoTurns = lim.passTurn;
            lim.passTurn = false;
            final int nodeCap = lim.nodes;
            lim.nodes = twoTurns ? nodeCap / 2 : nodeCap;
            e.passCap = lim.nodes;
            if (lim.perSearchNodes) {
                lim.nodes += st.nodes; // solver-cap12-1030: this pass's share from here, not from the solver's birth
            }
            e.passStart = st.nodes;
            List<Step> win = e.dfs(root, 0);
            if (win == null && twoTurns && System.nanoTime() < deadline) {
                lim.passTurn = true;
                lim.nodes = st.nodes + nodeCap / 2;
                e.passCap = nodeCap / 2;
                e.passStart = st.nodes;
                e.seen.clear();
                e.table.clear();
                win = e.dfs(root, 0);
            }
            lim.passTurn = twoTurns;
            lim.nodes = nodeCap;
            if (likelyOut != null && e.collect != null) {
                final int rootScore = e.evaluator.getStaticScore(root.g, root.me).value;
                e.collect.sort((x, y) -> Integer.compare(y.score, x.score));
                final Set<String> keys = new HashSet<>();
                for (Cand c : e.collect) {
                    if (likelyOut.size() >= keep) {
                        break;
                    }
                    if (c.score > rootScore && keys.add(c.key)) {
                        final Line l = new Line(c.path, List.of(), 0, root.me.getLife(), 0, 0, List.of(), "likely");
                        likelyOut.add(l);
                    }
                }
            }
            if (win == null) {
                return null;
            }
            return new Line(win, List.of(), win.contains(Step.PASS) ? 1 : 0, root.me.getLife(), 0, 0, List.of(), "engine");
        } catch (RuntimeException | StackOverflowError ex) {
            ForcedWinSolver.receipt("FORCED_WIN engine error " + ex);
            return null;
        } finally {
            lim.nodes = nodeCap0;
            lim.passTurn = passTurn0;
            st.nanos += System.nanoTime() - t0;
            if (held[0] != null) {
                // bvw-step1-build-1024 (diagnostics): which budget stopped this search, if any
                if (held[0].stoppedByCap) {
                    st.capStops++;
                } else if (held[0].stoppedByTime) {
                    st.deadlineStops++;
                }
                if (held[0].stoppedByLifetime) {
                    st.lifetimeStops++;
                }
            }
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
    }

    /** bvw-step1-build-1024: the cards in a game (every player's zones and the stack): a copy's size. */
    static int cardCount(Game g) {
        int n = g.getStack().size();
        for (Player p : g.getPlayers()) {
            for (ZoneType z : COUNTED_ZONES) {
                n += p.getCardsIn(z).size();
            }
        }
        return n;
    }

    private static final ZoneType[] COUNTED_ZONES = {ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard,
            ZoneType.Battlefield, ZoneType.Exile, ZoneType.Command};

    /**
     * R3-5 (engineStack only; {@code on} false = nothing changes): copy the source game's stack too when it holds items
     * GameCopier can copy faithfully (a response's root, worlds and nodes).
     */
    static void copyStackIf(GameCopier copier, Game src, boolean on) {
        if (on && !src.getStack().isEmpty() && GameCopier.stackUnsupported(src) == null) {
            copier.setCopyStack(true);
        }
    }

    static Object cloneScope(Object captured) {
        if (!(captured instanceof AtomicInteger[] a)) {
            return captured;
        }
        final AtomicInteger[] b = new AtomicInteger[a.length];
        for (int i = 0; i < a.length; i++) {
            b[i] = new AtomicInteger(a[i].get());
        }
        return b;
    }

    private boolean out() {
        if (st.nodes >= lim.nodes) {
            if (st.nodes - passStart < passCap) {
                stoppedByLifetime = true; // review #10: the cap was reached with earlier searches' nodes
            } else {
                stoppedByCap = true;
            }
            return true;
        }
        if (lim.totalSteps > 0 && st.steps - stepBase >= lim.totalSteps) {
            stoppedByCap = true; // bvw-step1-build-1024: budget=counter's step cap (0 = off: never here)
            return true;
        }
        if (lim.totalCards > 0 && st.cards - cardBase >= lim.totalCards) {
            stoppedByCap = true; // bvw-step1-build-1024: budget=counter's copied-card cap (0 = off: never here)
            return true;
        }
        if (System.nanoTime() > deadline) {
            stoppedByTime = true;
            return true;
        }
        return false;
    }

    private List<Step> dfs(Node node, int depth) {
        if (lim.pathCards > 0 && pathCards + node.cards > lim.pathCards) {
            st.pathPrunes++; // bvw-step1-build-1024: budget=counter's memory guard (0 = off: never here)
            return null;
        }
        pathCards += node.cards;
        st.peakLive = Math.max(st.peakLive, pathCards);
        try {
            return dfs0(node, depth);
        } finally {
            pathCards -= node.cards;
        }
    }

    private List<Step> dfs0(Node node, int depth) {
        if (lim.order != null) {
            return dfs0Ordered(node, depth); // R3-3
        }
        if (out() || depth >= lim.depth) {
            return null;
        }
        st.nodes++;
        forge.util.IdScope.install(cloneScope(node.ids));
        // a node is our priority with an empty stack (the copy was taken just after we passed it)
        LookaheadSearch.givePriority(node.g.getPhaseHandler(), node.me);
        final List<Step> moves = moves(node);
        if (TRACE) {
            ForcedWinSolver.receipt("FW_ENGINE node depth=" + depth + " path=" + node.path + " moves=" + moves);
        }
        int tried = 0;
        final Set<Step> variants = new HashSet<>();
        // labeller-v2 (a)/(e): variants applied while ranking, kept for their turn in the loop (by identity)
        final java.util.Map<Step, Applied> pre = new java.util.IdentityHashMap<>();
        for (int mi = 0; mi < moves.size(); mi++) {
            final Step mv = moves.get(mi);
            // solver-gaps-1015: the branching cap counts distinct moves only; the forced-answer variants of a move (its
            // picks, its trigger targets) have their own cap and no longer crowd the other moves out (Natural Order's
            // variants used every branch, so casting Llanowar Elves first was never tried)
            // R3-4 (a): an X variant (xValue >= 0, engineX only) is a variant of its move, like a forced pick
            final boolean variant = variants.contains(mv) || mv.xValue >= 0;
            if (out() || !variant && tried >= lim.branching) {
                if (out()) {
                    break;
                }
                continue;
            }
            if (!variant) {
                tried++;
            }
            final Applied a;
            if (pre.containsKey(mv)) {
                a = pre.remove(mv);
            } else {
                st.moves++;
                a = apply(node, mv);
            }
            if (TRACE) {
                ForcedWinSolver.receipt("FW_ENGINE   move " + mv + " -> " + (a == null ? "error " + lastError
                        : a.won ? "WON" : a.child == null ? "no-rest(" + a.why + ")" : "rest"));
            }
            if (a == null) {
                continue;
            }
            if (a.rec != null) {
                if (a.rec.miss && !a.won) {
                    continue; // a forced answer that did not apply: the same play as another variant
                }
                mv.cacPlayed = new ArrayList<>(a.rec.played);
            }
            if (!a.won && (a.rec != null || lim.pay)) {
                int at = mi + 1;
                if (a.rec != null) {
                    at = expandCac(node, mv, a.rec, moves, at, variants, pre);
                }
                if (lim.pay && mv.payFloats == null && (mv.cac == null || mv.cac.isEmpty())) {
                    expandPay(node, mv, a, moves, at, variants, pre);
                }
            }
            if (mv.pickName == null && !a.pickCandidates.isEmpty()) {
                // the move searched our library: try it again with each of a few forced picks next (biggest first)
                int k = 0;
                for (String name : a.pickCandidates) {
                    if (k++ >= lim.targetVariants) {
                        break;
                    }
                    final Step v = mv.copy();
                    v.pickName = name;
                    moves.add(mi + k, v);
                    variants.add(v);
                }
            }
            if (mv.triggerRef == null && !a.triggerCandidates.isEmpty()) {
                // the move caused a targeting trigger of ours: try it again with each of a few forced targets next
                int k = 0;
                for (String ref : a.triggerCandidates) {
                    if (k++ >= lim.targetVariants) {
                        break;
                    }
                    final Step v = mv.copy();
                    v.triggerRef = ref;
                    moves.add(mi + k, v);
                    variants.add(v);
                }
            }
            if (a.won) {
                final List<Step> path = new ArrayList<>(node.path);
                path.add(mv);
                return path;
            }
            if (collect != null && a.child != null && !node.passed && !mv.pass && a.child.turn == startTurn) {
                // Part B: a this-turn position, valued statically
                try {
                    final int score = evaluator.getStaticScore(a.child.g, a.child.me).value;
                    final List<Step> path = new ArrayList<>(node.path);
                    path.add(mv);
                    collect.add(new Cand(path, score, a.key));
                    if (collect.size() > 4 * keep) {
                        collect.sort((x, y) -> Integer.compare(y.score, x.score));
                        collect.subList(keep, collect.size()).clear();
                    }
                } catch (RuntimeException ex) {
                    // no value
                }
            }
            if (a.child == null) {
                continue;
            }
            final List<Step> r = descend(node, mv, a, depth);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /**
     * R3-3 (engineOrder): as {@link #dfs0}, but the node's candidates (up to orderCap distinct moves and their variants)
     * are applied first; a win returns at once; the children are then searched in the order of one batched value pass
     * (Forge's move order on ties or a failed pass) and the branching cap counts distinct moves in that order. Children
     * past the cap are released before the descent; the kept ones count in the path-card guard while pending.
     */
    private List<Step> dfs0Ordered(Node node, int depth) {
        if (out() || depth >= lim.depth) {
            return null;
        }
        st.nodes++;
        forge.util.IdScope.install(cloneScope(node.ids));
        LookaheadSearch.givePriority(node.g.getPhaseHandler(), node.me);
        final List<Step> moves = moves(node);
        if (TRACE) {
            ForcedWinSolver.receipt("FW_ENGINE node(ordered) depth=" + depth + " path=" + node.path + " moves=" + moves);
        }
        final Set<Step> variants = new HashSet<>();
        final java.util.Map<Step, Applied> pre = new java.util.IdentityHashMap<>();
        final java.util.Map<Step, Step> baseOf = new java.util.IdentityHashMap<>();
        final List<Step> kidMoves = new ArrayList<>();
        final List<Applied> kids = new ArrayList<>();
        long heldApplied = 0;
        int distinct = 0;
        for (int mi = 0; mi < moves.size(); mi++) {
            if (out()) {
                break;
            }
            final Step mv = moves.get(mi);
            final boolean variant = variants.contains(mv) || mv.xValue >= 0;
            if (!variant && distinct >= lim.orderCap) {
                continue;
            }
            if (!variant) {
                distinct++;
                baseOf.put(mv, mv);
            } else if (!baseOf.containsKey(mv)) {
                // review #154-4: an X variant shares its X-less move's branch (as in dfs0, it does not count apart)
                Step base = mv;
                for (Step o : moves) {
                    if (o.xValue < 0 && o.hostId == mv.hostId && o.desc.equals(mv.desc) && o.costKey.equals(mv.costKey)
                            && o.targetRefs.equals(mv.targetRefs)) {
                        base = baseOf.getOrDefault(o, o);
                        break;
                    }
                }
                baseOf.put(mv, base);
            }
            final Applied a;
            if (pre.containsKey(mv)) {
                a = pre.remove(mv);
            } else {
                st.moves++;
                a = apply(node, mv);
            }
            if (a == null) {
                continue;
            }
            if (a.rec != null) {
                if (a.rec.miss && !a.won) {
                    continue;
                }
                mv.cacPlayed = new ArrayList<>(a.rec.played);
            }
            final int before = moves.size();
            if (!a.won && (a.rec != null || lim.pay)) {
                int at = mi + 1;
                if (a.rec != null) {
                    at = expandCac(node, mv, a.rec, moves, at, variants, pre);
                }
                if (lim.pay && mv.payFloats == null && (mv.cac == null || mv.cac.isEmpty())) {
                    expandPay(node, mv, a, moves, at, variants, pre);
                }
            }
            if (mv.pickName == null && !a.pickCandidates.isEmpty()) {
                int k = 0;
                for (String name : a.pickCandidates) {
                    if (k++ >= lim.targetVariants) {
                        break;
                    }
                    final Step v = mv.copy();
                    v.pickName = name;
                    moves.add(mi + k, v);
                    variants.add(v);
                }
            }
            if (mv.triggerRef == null && !a.triggerCandidates.isEmpty()) {
                int k = 0;
                for (String ref : a.triggerCandidates) {
                    if (k++ >= lim.targetVariants) {
                        break;
                    }
                    final Step v = mv.copy();
                    v.triggerRef = ref;
                    moves.add(mi + k, v);
                    variants.add(v);
                }
            }
            for (int vi = mi + 1; vi < moves.size() && vi <= mi + (moves.size() - before); vi++) {
                final Step vs = moves.get(vi);
                if (variants.contains(vs) && !baseOf.containsKey(vs)) {
                    baseOf.put(vs, baseOf.get(mv)); // this move's new variants share its branch
                }
            }
            if (a.won) {
                final List<Step> path = new ArrayList<>(node.path);
                path.add(mv);
                pathCards -= heldApplied;
                return path;
            }
            if (collect != null && a.child != null && !node.passed && !mv.pass && a.child.turn == startTurn) {
                try {
                    final int score = evaluator.getStaticScore(a.child.g, a.child.me).value;
                    final List<Step> path = new ArrayList<>(node.path);
                    path.add(mv);
                    collect.add(new Cand(path, score, a.key));
                    if (collect.size() > 4 * keep) {
                        collect.sort((x, y) -> Integer.compare(y.score, x.score));
                        collect.subList(keep, collect.size()).clear();
                    }
                } catch (RuntimeException ex) {
                    // no value
                }
            }
            if (a.child != null) {
                kidMoves.add(mv);
                kids.add(a);
                pathCards += a.child.cards; // review #154-9: applied children count in the guard while held
                heldApplied += a.child.cards;
            }
        }
        pathCards -= heldApplied;
        for (Applied left : pre.values()) {
            left.child = null; // ranked variants never reached (the node was cut): released
        }
        // one batched value pass over the children (Forge's order kept on ties and on a failed pass)
        final Integer[] idx = new Integer[kids.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        double[] v = null;
        if (kids.size() > 1) {
            final Object prevIds = forge.util.IdScope.capture();
            try {
                final List<Object> payloads = new ArrayList<>();
                for (Applied k : kids) {
                    forge.util.IdScope.install(cloneScope(k.child.ids));
                    payloads.add(lim.order.capture(lim.order.onCopy(k.child.g, k.child.me), k.child.g, k.child.me));
                }
                v = lim.order.values(payloads);
            } catch (RuntimeException ex) {
                v = null;
            } finally {
                forge.util.IdScope.install(prevIds);
            }
            if (v == null || v.length != kids.size()) {
                st.orderFails++;
                v = null;
            } else {
                st.ordered++;
                final double[] vv = v;
                java.util.Arrays.sort(idx, (x, y) -> Double.compare(vv[y], vv[x])); // stable: ties keep Forge's order
            }
        }
        if (TRACE) {
            final StringBuilder sb = new StringBuilder();
            for (Integer i : idx) {
                sb.append(kidMoves.get(i)).append('=').append(v == null ? "?" : String.format("%.3f", v[i])).append(' ');
            }
            ForcedWinSolver.receipt("FW_ENGINE   order " + sb);
        }
        // the branching cap over distinct moves, in value order; the children past it are released now
        final List<Integer> keepIdx = new ArrayList<>();
        final Set<Step> bases = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        long pending = 0;
        for (Integer i : idx) {
            final Step base = baseOf.getOrDefault(kidMoves.get(i), kidMoves.get(i));
            if (!bases.contains(base) && bases.size() >= lim.branching) {
                kids.get(i).child = null;
                continue;
            }
            bases.add(base);
            keepIdx.add(i);
            pending += kids.get(i).child.cards;
        }
        for (Integer i : keepIdx) {
            final Applied a = kids.get(i);
            if (out()) {
                a.child = null;
                continue;
            }
            pending -= a.child.cards;
            pathCards += pending; // the siblings still waiting stay alive while this child is searched
            final List<Step> r;
            try {
                r = descend(node, kidMoves.get(i), a, depth);
            } finally {
                pathCards -= pending;
            }
            a.child = null;
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** A move's child (not a win): its path, the progress table and loops (cycles, loopMacro), then the DFS below it. */
    private List<Step> descend(Node node, Step mv, Applied a, int depth) {
        a.child.path.addAll(node.path);
        a.child.path.add(mv);
        a.child.passed = node.passed || mv.pass;
        a.child.loopsDone = node.loopsDone; // R2-3 (loopMacro only reads it)
        if (lim.cycles) {
            // search-bones-1015 (c): the progress table instead of the exact-key set, and loops fast-forwarded
            a.child.parent = node;
            a.child.pv = progress(a.child.g, a.child.me);
            if (lim.loopMacro) {
                // R2-3: a loop by state recurrence (any body up to loopWindow), N chosen by the search
                a.child.mk = macroKey(a.child.g, a.child.me);
                final long[] ceil = ceilings(node.g, node.me, lim.loopMacro3);
                List<Step> mb = macroBody(a.child, ceil);
                if (mb != null && done(node, mb)) {
                    mb = null; // review #152-6: this path already fast-forwarded the same loop (N = 1 continues)
                }
                if (mb != null) {
                    a.child.loopsDone = new HashSet<>(node.loopsDone == null ? Set.of() : node.loopsDone);
                    a.child.loopsDone.add(bodyKey(mb));
                    final Object fw = macroForward(a.child, mb, ceil);
                    if (fw instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Step) {
                        @SuppressWarnings("unchecked")
                        final List<Step> win = (List<Step>) fw;
                        return win;
                    }
                    @SuppressWarnings("unchecked")
                    final List<Node> cps = (List<Node>) fw;
                    long cpsCards = 0;
                    for (Node cp : cps) {
                        cp.loopsDone = a.child.loopsDone;
                        cpsCards += cp.cards;
                    }
                    for (Node cp : cps) {
                        if (out()) {
                            break;
                        }
                        if (seenDominated(cp)) {
                            st.pruned++;
                            continue;
                        }
                        // review #152-3: the DFS child and the other checkpoints stay alive while this one is searched
                        final long held = (cp.g != a.child.g ? a.child.cards : 0) + cpsCards - cp.cards;
                        pathCards += held;
                        final List<Step> r;
                        try {
                            r = dfs(cp, depth + 1);
                        } finally {
                            pathCards -= held;
                        }
                        if (r != null) {
                            return r;
                        }
                    }
                    // N = 1 (the child itself) is searched below like any child
                }
            }
            final List<Step> body = loopBody(a.child);
            if (body != null) {
                final Forward fw = fastForward(a.child, body);
                if (fw.win != null) {
                    return fw.win;
                }
                if (!seenDominated(fw.node)) {
                    // review #11: while the REPEAT node is searched, its parent link keeps the DFS child's copy too
                    final long held = fw.node.g != a.child.g ? a.child.cards : 0;
                    pathCards += held;
                    final List<Step> r;
                    try {
                        r = dfs(fw.node, depth + 1);
                    } finally {
                        pathCards -= held;
                    }
                    if (r != null) {
                        return r;
                    }
                } else {
                    st.pruned++;
                }
                return null;
            }
            if (seenDominated(a.child)) {
                st.pruned++;
                return null;
            }
        } else if (!seen.add(a.key)) {
            return null;
        }
        return dfs(a.child, depth + 1);
    }

    /** Our legal moves at a node, best first: the previous move's ability, permanents' abilities, spells, lands, attack. */
    private List<Step> moves(Node node) {
        final Game g = node.g;
        final Player me = node.me;
        final List<Step> rep = new ArrayList<>(), act = new ArrayList<>(), spells = new ArrayList<>(), lands = new ArrayList<>();
        final List<Step> wide = new ArrayList<>(); // engineX in Forge's order: wide target variants, searched last
        final Step last = node.path.isEmpty() ? null : node.path.get(node.path.size() - 1);
        final Set<String> keys = new HashSet<>();
        final List<Card> cards = new ArrayList<>();
        for (ZoneType z : new ZoneType[] {ZoneType.Battlefield, ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile,
                ZoneType.Command}) {
            cards.addAll(me.getCardsIn(z));
        }
        for (Card c : cards) {
            List<SpellAbility> all;
            try {
                all = c.getAllPossibleAbilities(me, true);
            } catch (RuntimeException e) {
                continue;
            }
            if (TRACE && c.isInZone(ZoneType.Graveyard) && !c.isLand()) {
                final StringBuilder sb = new StringBuilder();
                for (SpellAbility x : all) {
                    final SpellAbility y = x.copy(me);
                    y.setActivatingPlayer(me);
                    sb.append(" [").append(x.getDescription()).append(" | ").append(x.getPayCosts()).append(" canPlay=")
                            .append(y.canPlay()).append(" pay=").append(ComputerUtilCost.canPayCost(y, me, false)).append(']');
                }
                ForcedWinSolver.receipt("FW_ENGINE   graveyard " + c.getName() + " abilities=" + all.size() + sb);
            }
            for (SpellAbility sa0 : all) {
                if (sa0.isManaAbility()) {
                    continue;
                }
                final String desc = sa0.getDescription();
                final String costKey = sa0.getPayCosts() == null ? "" : sa0.getPayCosts().toString();
                final String k = c.getId() + "|" + desc + "|" + costKey;
                if (!keys.add(k)) {
                    continue;
                }
                if (sa0.isLandAbility()) {
                    final Step s = Step.engine(AbilKind.LAND, c.getId(), c.getName(), "land", "", false);
                    lands.add(s);
                    continue;
                }
                for (Step s : variants(g, me, c, sa0, desc, costKey)) {
                    final boolean repeat = last != null && last.hostId == s.hostId && last.desc.equals(s.desc);
                    if (repeat && (last.triggerRef != null || last.pickName != null)) {
                        // a loop: the same ability with the same forced answers first
                        final Step same = s.copy();
                        same.triggerRef = last.triggerRef;
                        same.pickName = last.pickName;
                        rep.add(same);
                    } else if (repeat && lim.costChoice && last.cacPlayed != null && !last.cacPlayed.isEmpty()
                            && s.targetRefs.equals(last.targetRefs)) {
                        // R3-4 (b): a loop: the same ability paying its cost with the same cards first
                        final Step same = s.copy();
                        same.cac = new ArrayList<>(last.cacPlayed);
                        rep.add(same);
                    }
                    (repeat ? rep : s.wideTarget ? wide : c.isInPlay() ? act : spells).add(s);
                }
            }
        }
        // one-shot mana (a source that sacrifices itself): float it in a colour some spell of ours needs
        int needed = 0;
        for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Graveyard}) {
            for (Card c : me.getCardsIn(z)) {
                for (forge.card.mana.ManaCostShard sh : c.getManaCost()) {
                    for (int i = 0; i < 5; i++) {
                        if (sh.isColor(ForcedWinModel.FORGE_COLOR[i])) {
                            needed |= 1 << i;
                        }
                    }
                }
            }
        }
        final List<Step> floats = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            for (SpellAbility m : c.getManaAbilities()) {
                if (m.getPayCosts() == null || m.getPayCosts().getCostParts().stream().noneMatch(
                        p -> p instanceof forge.game.cost.CostSacrifice cs && cs.payCostFromSource())) {
                    continue;
                }
                final SpellAbility mc = m.copy(me);
                mc.setActivatingPlayer(me);
                if (!mc.canPlay() || !ComputerUtilCost.canPayCost(mc, me, false) || mc.getManaPart() == null) {
                    continue;
                }
                for (int i = 0; i < 5; i++) {
                    if ((needed & (1 << i)) != 0 && mc.canProduce(ForcedWinModel.SHORT[i])) {
                        final Step f = Step.floatMana(c.getId(), c.getName(), m.getDescription(), i);
                        if (keys.add("float|" + c.getId() + "|" + i)) {
                            floats.add(f);
                        }
                    }
                }
            }
        }
        if (lim.loopMacro) {
            // R2-3: tapping an untapped nonland mana permanent (no sacrifice) is a move too: a loop that untaps its own
            // source (an untap ability, a flicker) starts with it. One float per source, in the first colour it makes.
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                if (c.isLand() || c.isTapped()) {
                    continue;
                }
                for (SpellAbility m : c.getManaAbilities()) {
                    if (m.getPayCosts() == null || m.getPayCosts().getCostParts().stream().anyMatch(
                            p -> p instanceof forge.game.cost.CostSacrifice)) {
                        continue;
                    }
                    final SpellAbility mc = m.copy(me);
                    mc.setActivatingPlayer(me);
                    if (!mc.canPlay() || !ComputerUtilCost.canPayCost(mc, me, false) || mc.getManaPart() == null) {
                        continue;
                    }
                    int colour = -1;
                    for (int i = 0; i < 6 && colour < 0; i++) {
                        if (mc.canProduce(ForcedWinModel.SHORT[i])) {
                            colour = i;
                        }
                    }
                    if (colour >= 0 && keys.add("float|" + c.getId() + "|" + colour)) {
                        floats.add(Step.floatMana(c.getId(), c.getName(), m.getDescription(), colour));
                    }
                    break;
                }
            }
        }
        final List<Step> out = new ArrayList<>(rep);
        out.addAll(act);
        out.addAll(spells);
        out.addAll(floats);
        out.addAll(lands);
        if (node.afterLoop != null) {
            // R2-3 (the outlet search): after a loop's checkpoint its body's own moves are not searched again
            final Set<String> bodyKeys = new HashSet<>();
            for (Step b : node.afterLoop) {
                bodyKeys.add(b.kind + "|" + b.hostName + "|" + b.desc + "|" + b.floatColor);
            }
            out.removeIf(m -> bodyKeys.contains(m.kind + "|" + m.hostName + "|" + m.desc + "|" + m.floatColor));
        }
        if (!node.passed && lim.cycles && oppDecked(me)) {
            // (c): an opponent with no library loses at their draw: passing is the first move, in the this-turn
            // phase too (a mill loop's kill is the pass; the engine still plays their turn up to the draw)
            out.add(0, Step.PASS);
        } else if (!node.passed && lim.passTurn) {
            out.add(Step.PASS);
        }
        // R3-5: a root outside our turn (a response at their main phase) never attacks there (off: the root is ours)
        if (g.getPhaseHandler().is(PhaseType.MAIN1) && canAttack(me)
                && (!lim.stack || g.getPhaseHandler().isPlayerTurn(me))) {
            final Step atk = Step.engine(AbilKind.ATTACK, -1, "attack", "attack", "", false);
            if (lethalOnBoard(me)) {
                out.add(0, atk);
            } else {
                out.add(atk);
            }
        }
        if (!wide.isEmpty()) {
            if (node.afterLoop != null) {
                final Set<String> bodyKeys = new HashSet<>();
                for (Step b : node.afterLoop) {
                    bodyKeys.add(b.kind + "|" + b.hostName + "|" + b.desc + "|" + b.floatColor);
                }
                wide.removeIf(m -> bodyKeys.contains(m.kind + "|" + m.hostName + "|" + m.desc + "|" + m.floatColor));
            }
            out.addAll(wide);
        }
        return out;
    }

    private static boolean oppDecked(Player me) {
        for (Player o : me.getOpponents()) {
            if (o.getCardsIn(ZoneType.Library).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean canAttack(Player me) {
        for (Card c : me.getCreaturesInPlay()) {
            for (Player o : me.getOpponents()) {
                if (CombatUtil.canAttack(c, o)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * (c): our attackers' power, less the strongest few that the opponent's untapped creatures could block, covers the
     * opponent's life (in our first main phase). Used to end a loop's fast-forward, not to order moves.
     */
    private static boolean lethalPastBlockers(Node n) {
        final Player me = n.me;
        if (!n.g.getPhaseHandler().is(PhaseType.MAIN1)) {
            return false;
        }
        Player opp = null;
        for (Player o : me.getOpponents()) {
            opp = o;
        }
        if (opp == null) {
            return false;
        }
        final List<Integer> pw = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c, opp)) {
                pw.add(Math.max(0, c.getNetCombatDamage()));
            }
        }
        int blockers = 0;
        for (Card c : opp.getCreaturesInPlay()) {
            if (!c.isTapped()) {
                blockers++;
            }
        }
        pw.sort(java.util.Collections.reverseOrder());
        int power = 0;
        for (int i = blockers; i < pw.size(); i++) {
            power += pw.get(i);
        }
        return power >= opp.getLife();
    }

    private static boolean lethalOnBoard(Player me) {
        int power = 0;
        Player opp = null;
        for (Player o : me.getOpponents()) {
            opp = o;
        }
        if (opp == null) {
            return false;
        }
        for (Card c : me.getCreaturesInPlay()) {
            if (CombatUtil.canAttack(c, opp)) {
                power += Math.max(0, c.getNetCombatDamage());
            }
        }
        return power >= opp.getLife();
    }

    /** One step per target variant of a legal ability; with {@link Limits#xChoice} also its X variants (R3-4 a). */
    private List<Step> variants(Game g, Player me, Card c, SpellAbility sa0, String desc, String costKey) {
        final List<Step> out = variants0(g, me, c, sa0, desc, costKey);
        if (!lim.xChoice || out.isEmpty()) {
            return out;
        }
        final int max;
        try {
            final forge.game.cost.Cost pc = sa0.getPayCosts();
            if (pc == null || !pc.hasManaCost() || pc.getTotalMana().countX() == 0) {
                return out;
            }
            final SpellAbility sa = sa0.copy(me);
            sa.setActivatingPlayer(me);
            max = forge.ai.ComputerUtilMana.determineLeftoverMana(sa, me, false);
        } catch (RuntimeException e) {
            return out;
        }
        final List<Step> all = new ArrayList<>(out);
        for (Step base : out) {
            for (int x : xValues(max, lim.xVariants)) {
                final Step v = base.copy();
                v.xValue = x;
                all.add(v);
            }
        }
        return all;
    }

    /**
     * R3-4 (a): the X values searched for an X cost our mana pays up to {@code max}: every value when there are at most
     * {@code k}, else max, max-1, 3max/4, max/2, max/4, 2, 1, 0 (largest first, deduped).
     */
    static List<Integer> xValues(int max, int k) {
        final List<Integer> xs = new ArrayList<>();
        if (max < 0) {
            return xs;
        }
        if (max + 1 <= k) {
            for (int x = max; x >= 0; x--) {
                xs.add(x);
            }
            return xs;
        }
        for (int x : new int[] {max, max - 1, 3 * max / 4, max / 2, max / 4, 2, 1, 0}) {
            if (x >= 0 && x <= max && !xs.contains(x)) {
                xs.add(x);
            }
        }
        return xs;
    }

    private List<Step> variants0(Game g, Player me, Card c, SpellAbility sa0, String desc, String costKey) {
        final List<Step> out = new ArrayList<>();
        final SpellAbility sa = sa0.copy(me);
        sa.setActivatingPlayer(me);
        if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) {
            return out;
        }
        if (!ForcedWinProbe.targetsAnything(sa)) {
            out.add(Step.engine(AbilKind.ENGINE, c.getId(), c.getName(), desc, costKey, sa.isSpell()));
            return out;
        }
        final Set<String> tried = new HashSet<>();
        // Forge AI's own targets
        try {
            final SpellAbility ai = sa0.copy(me);
            ai.setActivatingPlayer(me);
            if (((PlayerControllerAi) me.getController()).getAi().canPlaySa(ai) == AiPlayDecision.WillPlay
                    && ai.isTargetNumberValid()) {
                final Step s = Step.engine(AbilKind.ENGINE, c.getId(), c.getName(), desc, costKey, sa.isSpell());
                s.targetRefs.addAll(refs(ai));
                if (tried.add(s.targetRefs.toString())) {
                    out.add(s);
                }
            }
        } catch (RuntimeException e) {
            // no AI targets
        }
        // single-target abilities: each of a few of our own permanents, then each player
        final SpellAbility slot = LookaheadSearch.singleTargetSlot(sa);
        if (slot != null && firstTargeting(sa) == slot) {
            int n = 0;
            final List<GameObject> cands = new ArrayList<>();
            if (lim.order != null) {
                // R3-3 (and D8: engineX): every legal single target is a candidate (players, then every permanent), up
                // to orderCap, not a fixed few of our own permanents (which left the opponent out when we had three)
                cands.addAll(g.getPlayers());
                cands.addAll(g.getCardsIn(ZoneType.Battlefield));
            } else if (lim.xChoice) {
                // D8 in Forge's order (review fix, the kiki flip of the ladder-frequency run): players, our permanents,
                // then the rest; past the first targetVariants the variants are wide (searched after the node's other
                // moves), so 24 targets of one ability no longer fill the branching cap ahead of every other move
                cands.addAll(g.getPlayers());
                cands.addAll(me.getCardsIn(ZoneType.Battlefield));
                for (Card p : g.getCardsIn(ZoneType.Battlefield)) {
                    if (!p.getController().equals(me)) {
                        cands.add(p);
                    }
                }
            } else {
                for (Card p : me.getCardsIn(ZoneType.Battlefield)) {
                    cands.add(p);
                }
                cands.addAll(g.getPlayers());
            }
            final int cap = lim.order != null || lim.xChoice ? lim.orderCap : lim.targetVariants;
            for (GameObject o : cands) {
                if (n >= cap) {
                    break;
                }
                if (!slot.canTarget(o)) {
                    continue;
                }
                final String ref = "0:" + LookaheadSearch.targetRef(g, o);
                if (!tried.add(List.of(ref).toString())) {
                    continue;
                }
                final Step s = Step.engine(AbilKind.ENGINE, c.getId(), c.getName(), desc, costKey, sa.isSpell());
                s.targetRefs.add(ref);
                s.wideTarget = lim.order == null && lim.xChoice && n >= lim.targetVariants;
                out.add(s);
                n++;
            }
        }
        return out;
    }

    private static SpellAbility firstTargeting(SpellAbility sa) {
        for (SpellAbility l = sa; l != null; l = l.getSubAbility()) {
            if (l.usesTargeting()) {
                return l;
            }
        }
        return null;
    }

    /** The targets an ability holds, as "link:ref" strings. */
    static List<String> refs(SpellAbility sa) {
        final List<String> out = new ArrayList<>();
        int link = 0;
        for (SpellAbility l = sa; l != null; l = l.getSubAbility(), link++) {
            if (!l.usesTargeting()) {
                continue;
            }
            for (GameObject o : l.getTargets()) {
                out.add(link + ":" + LookaheadSearch.targetRef(sa.getHostCard().getGame(), o));
            }
        }
        return out;
    }

    /** Set an ability's targets from refs; false when one is missing or illegal. */
    static boolean applyRefs(SpellAbility sa, List<String> refs, Game g) {
        int link = 0;
        for (SpellAbility l = sa; l != null; l = l.getSubAbility(), link++) {
            if (!l.usesTargeting()) {
                continue;
            }
            boolean any = false;
            for (String r : refs) {
                final int colon = r.indexOf(':');
                if (Integer.parseInt(r.substring(0, colon)) != link) {
                    continue;
                }
                if (!any) {
                    l.resetTargets();
                    any = true;
                }
                if (!LookaheadSearch.applyTarget(l, r.substring(colon + 1), g)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The first targeting trigger of ours in a step's window: its legal targets are recorded (our own tapped
     * permanents with an activated ability first: re-enabling an engine; then our other permanents; then the
     * opponent's), and when a forced target is given it is put on the stack with that target instead of Forge AI's.
     */
    /**
     * labeller-v2 (a): the variants of {@code mv} at each of its free choice points j (prefix = the answers it played
     * before j, then one other answer at j): each is applied now and ranked by the static value of the position after
     * the move (a win first, a failed move last; ties in enumeration order); the best cacVariants - 1 (Forge AI's own
     * answer is {@code mv} itself) go into {@code moves} at {@code at}, their results kept in {@code pre}. Returns the
     * next insertion index.
     */
    private int expandCac(Node node, Step mv, ForcedWinChoices.Recorder rec, List<Step> moves, int at, Set<Step> variants,
            java.util.Map<Step, Applied> pre) {
        for (int pi = 0; pi < rec.freeAt.size(); pi++) {
            final int j = rec.freeAt.get(pi);
            final List<String> cands = rec.candidates.get(pi);
            if (cands.size() < 2) {
                continue;
            }
            final List<Object[]> ranked = new ArrayList<>();
            for (int ci = 1; ci < cands.size(); ci++) {
                if (out()) {
                    break;
                }
                final Step v = mv.copy();
                v.cac = new ArrayList<>(rec.played.subList(0, j));
                v.cac.add(cands.get(ci));
                v.cacPlayed = null;
                st.moves++;
                st.cacApplies++;
                final Applied av = apply(node, v);
                if (av == null || av.rec != null && av.rec.miss && !av.won) {
                    continue;
                }
                long score;
                if (av.won) {
                    score = Long.MAX_VALUE;
                } else if (av.child == null) {
                    score = Long.MIN_VALUE;
                } else {
                    try {
                        score = evaluator.getStaticScore(av.child.g, av.child.me).value;
                    } catch (RuntimeException ex) {
                        score = Long.MIN_VALUE;
                    }
                }
                ranked.add(new Object[] {score, ci, v, av});
            }
            ranked.sort((x, y) -> {
                final int c = Long.compare((Long) y[0], (Long) x[0]);
                return c != 0 ? c : Integer.compare((Integer) x[1], (Integer) y[1]);
            });
            for (int k = 0; k < ranked.size(); k++) {
                final Step v = (Step) ranked.get(k)[2];
                final Applied av = (Applied) ranked.get(k)[3];
                if (k >= lim.cacVariants - 1) {
                    av.child = null; // dropped by rank: released
                    continue;
                }
                moves.add(at++, v);
                variants.add(v);
                pre.put(v, av);
            }
        }
        return at;
    }

    /**
     * labeller-v2 (e): payment variants of {@code mv}: each distinct untapped remainder of our untapped one-tap land
     * sources after paying its mana cost (by the sorted names left untapped), other than Forge AI's own payment's, up
     * to payVariants - 1; a variant floats its sources first (FLOAT steps) and Forge AI pays from the pool. Costs with
     * X, hybrid, Phyrexian or snow pips are left to Forge AI (stated gap).
     */
    private void expandPay(Node node, Step mv, Applied a, List<Step> moves, int at, Set<Step> variants,
            java.util.Map<Step, Applied> pre) {
        if (mv.pass || mv.kind == AbilKind.ATTACK || mv.kind == AbilKind.FLOAT || mv.kind == AbilKind.LAND
                || mv.kind == AbilKind.REPEAT) {
            return;
        }
        final List<Step> plans;
        final Object prevIds = forge.util.IdScope.capture();
        try {
            forge.util.IdScope.install(cloneScope(node.ids));
            plans = paymentPlans(node.g, node.me, mv, a);
        } catch (RuntimeException ex) {
            return;
        } finally {
            forge.util.IdScope.install(prevIds); // review S3: restored
        }
        for (Step v : plans) {
            moves.add(at++, v);
            variants.add(v);
        }
    }

    /** The payment variants of {@code mv} at {@code g} (see {@link #expandPay}). */
    private List<Step> paymentPlans(Game g, Player me, Step mv, Applied a) {
        final List<Step> out = new ArrayList<>();
        final SpellAbility sa = ForcedWinSolver.Follower.locate(g, me, mv);
        if (sa == null || sa.getPayCosts() == null || !sa.getPayCosts().hasManaCost()) {
            return out;
        }
        final forge.card.mana.ManaCost mc = sa.getPayCosts().getTotalMana();
        if (mc == null || mc.countX() > 0) {
            return out;
        }
        final List<Integer> need = new ArrayList<>(); // colour index per coloured pip (5 = {C})
        for (forge.card.mana.ManaCostShard sh : mc) {
            if (sh == forge.card.mana.ManaCostShard.COLORLESS) {
                need.add(ForcedWinModel.C);
            } else if (sh.isMonoColor() && !sh.isOr2Generic() && !sh.isPhyrexian() && !sh.isSnow()
                    && Integer.bitCount(sh.getColorMask() & 0x1f) == 1) {
                need.add(Integer.numberOfTrailingZeros(sh.getColorMask()));
            } else if (!sh.isGeneric() || sh.isSnow() || sh.isOr2Generic()) {
                return out;
            }
        }
        final int total = need.size() + mc.getGenericCost();
        final Card host = g.findById(mv.hostId);
        final List<Card> src = new ArrayList<>();
        final java.util.Map<Integer, Integer> masks = new java.util.HashMap<>();
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c == host || !c.isLand() || c.isTapped()) {
                continue;
            }
            int mask = 0;
            for (SpellAbility ma : c.getManaAbilities()) {
                if (ma.getPayCosts() == null || !ma.getPayCosts().hasTapCost() || ma.getPayCosts().getCostParts().size() != 1) {
                    continue;
                }
                for (int col = 0; col < 6; col++) {
                    if (ma.canProduce(ForcedWinModel.SHORT[col])) {
                        mask |= 1 << col;
                    }
                }
            }
            if (mask != 0) {
                src.add(c);
                masks.put(c.getId(), mask);
            }
        }
        if (total == 0 || src.size() <= total) {
            return out; // no choice of sources (or other mana pays: Forge AI's)
        }
        src.sort((x, y) -> Integer.compare(x.getId(), y.getId()));
        // Forge AI's own remainder: which of these sources its payment left untapped
        final java.util.Set<String> seen = new java.util.HashSet<>();
        if (a.child != null) {
            final List<Card> left = new ArrayList<>();
            for (Card c : src) {
                final Card cc = a.child.g.findById(c.getId());
                if (cc != null && cc.isInPlay() && !cc.isTapped()) {
                    left.add(c);
                }
            }
            seen.add(ForcedWinChoices.remainderKey(left));
        }
        final List<List<Card>> sets = new ArrayList<>();
        ChooseACard.combos(src, total, 512, sets::add);
        for (List<Card> set : sets) {
            if (out.size() >= lim.payVariants - 1) {
                break;
            }
            final int[] colour = assign(set, need, masks);
            if (colour == null) {
                continue;
            }
            final List<Card> left = new ArrayList<>(src);
            left.removeAll(set);
            if (!seen.add(ForcedWinChoices.remainderKey(left))) {
                continue;
            }
            final Step v = mv.copy();
            v.payFloats = new ArrayList<>();
            for (int i = 0; i < set.size(); i++) {
                v.payFloats.add(Step.floatMana(set.get(i).getId(), set.get(i).getName(), "", colour[i]));
            }
            out.add(v);
        }
        return out;
    }

    /** A colour per source paying {@code need} (coloured pips) with the rest generic, or null (backtracking). */
    static int[] assign(List<Card> set, List<Integer> need, java.util.Map<Integer, Integer> masks) {
        final int[] colour = new int[set.size()];
        java.util.Arrays.fill(colour, -1);
        if (!assignRec(set, need, masks, 0, colour)) {
            return null;
        }
        for (int i = 0; i < colour.length; i++) {
            if (colour[i] < 0) {
                final int m = masks.get(set.get(i).getId());
                colour[i] = (m & (1 << ForcedWinModel.C)) != 0 ? ForcedWinModel.C : Integer.numberOfTrailingZeros(m);
            }
        }
        return colour;
    }

    private static boolean assignRec(List<Card> set, List<Integer> need, java.util.Map<Integer, Integer> masks, int k,
            int[] colour) {
        if (k >= need.size()) {
            return true;
        }
        final int col = need.get(k);
        for (int i = 0; i < set.size(); i++) {
            if (colour[i] < 0 && (masks.get(set.get(i).getId()) & (1 << col)) != 0) {
                colour[i] = col;
                if (assignRec(set, need, masks, k + 1, colour)) {
                    return true;
                }
                colour[i] = -1;
            }
        }
        return false;
    }

    static final class TriggerTargets {
        final String forced;
        boolean seen;
        List<String> candidates = List.of();
        /** The host of the trigger we forced (its optional "you may" is then answered yes). */
        Card forcedHost;

        boolean confirms(forge.game.trigger.WrappedAbility w) {
            return forcedHost != null && w != null && w.getHostCard() != null && w.getHostCard().getId() == forcedHost.getId();
        }

        TriggerTargets(String forced) {
            this.forced = forced;
        }

        /** Handle the forced trigger (played here); the rest go back to Forge AI. */
        List<SpellAbility> apply(Game g, Player me, List<SpellAbility> abilities) {
            if (seen) {
                return abilities;
            }
            final List<SpellAbility> rest = new ArrayList<>();
            for (SpellAbility sa : abilities) {
                final SpellAbility slot = seen || !sa.isTrigger() || sa.getActivatingPlayer() != me ? null
                        : LookaheadSearch.singleTargetSlot(sa);
                if (slot == null || firstTargeting(sa) != slot) {
                    rest.add(sa);
                    continue;
                }
                seen = true;
                final List<String> ours = new ArrayList<>(), mine = new ArrayList<>(), theirs = new ArrayList<>();
                for (Card c : g.getCardsIn(ZoneType.Battlefield)) {
                    if (!slot.canTarget(c)) {
                        continue;
                    }
                    final String ref = "C" + c.getId();
                    if (c.getController() == me && c.isTapped() && hasTapAbility(c)) {
                        ours.add(ref);
                    } else if (c.getController() == me) {
                        mine.add(ref);
                    } else {
                        theirs.add(ref);
                    }
                }
                final List<String> all = new ArrayList<>(ours);
                all.addAll(mine);
                all.addAll(theirs);
                candidates = all;
                if (TRACE) {
                    final StringBuilder sb = new StringBuilder();
                    for (String r : all) {
                        final Card c = g.findById(Integer.parseInt(r.substring(1)));
                        sb.append(r).append('=').append(c == null ? "?" : c.getName() + (c.isTapped() ? "(T)" : "")
                                + (c.getController() == me ? "" : "(opp)")).append(' ');
                    }
                    ForcedWinSolver.receipt("FW_ENGINE   trigger " + sa.getHostCard().getName() + " candidates " + sb);
                }
                if (forced != null && LookaheadSearch.applyTarget(slot, forced, g) && sa.isTargetNumberValid()) {
                    forcedHost = sa.getHostCard();
                    forge.ai.ComputerUtil.playStack(sa, me, g);
                    continue;
                }
                rest.add(sa);
            }
            return rest;
        }

        private static boolean hasTapAbility(Card c) {
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (sa.isActivatedAbility() && !sa.isManaAbility() && sa.getPayCosts() != null && sa.getPayCosts().hasTapCost()) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The first choice of one of our own cards our ability makes in a step's window (a tutor, Natural Order; since
     * solver-gaps-1015 also a card from our hand or graveyard: Show and Tell, Sneak Attack): the distinct names offered are
     * recorded, highest mana value first (the big payoffs), and a forced pick is taken by name.
     */
    static final class LibraryPicks {
        final String forced;
        boolean seen;
        List<String> candidates = List.of();

        LibraryPicks(String forced) {
            this.forced = forced;
        }

        Card pick(Player me, List<ZoneType> origin, SpellAbility sa, forge.game.card.CardCollection fetchList, Player decider) {
            // solver-gaps-1015: any of our own zones (a creature put from our hand by Show and Tell or Sneak Attack, a
            // card returned from our graveyard), not only our library
            if (seen || decider != me || origin == null || !(origin.contains(ZoneType.Library) || origin.contains(ZoneType.Hand)
                    || origin.contains(ZoneType.Graveyard)) || sa == null
                    || sa.getActivatingPlayer() != me || fetchList == null || fetchList.isEmpty()
                    || fetchList.stream().anyMatch(c -> c.getOwner() != me)) {
                return null;
            }
            seen = true;
            final List<Card> sorted = new ArrayList<>(fetchList);
            sorted.sort((x, y) -> x.getCMC() != y.getCMC() ? Integer.compare(y.getCMC(), x.getCMC())
                    : Integer.compare(x.getId(), y.getId()));
            final List<String> names = new ArrayList<>();
            for (Card c : sorted) {
                if (!names.contains(c.getName())) {
                    names.add(c.getName());
                }
            }
            candidates = names;
            if (forced != null) {
                for (Card c : sorted) {
                    if (c.getName().equals(forced)) {
                        return c;
                    }
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ applying a move

    static final class Applied {
        boolean won;
        Node child;
        String key;
        String why = "";
        List<String> triggerCandidates = List.of();
        List<String> pickCandidates = List.of();
        /** labeller-v2 (a): the move's choice points (null = v1). */
        ForcedWinChoices.Recorder rec;
    }

    static final boolean TRACE = Boolean.getBoolean("forge.ai.forcedWin.engineTrace");
    private String lastError;

    private Applied apply(Node node, Step mv) {
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, st.copies)));
        final MoveAi[] held = new MoveAi[1];
        try {
            forge.util.IdScope.install(cloneScope(node.ids));
            final GameCopier copier = new GameCopier(node.g, true);
                copier.setCopyFidelity(1); // storm count, per-turn records
            copyStackIf(copier, node.g, lim.stack); // R3-5
            final Game g = copier.makeCopy();
            final Player me = (Player) copier.find(node.me);
            st.copies++;
            final int copyCards = cardCount(g);
            st.cards += copyCards;
            final MoveAi ai = new MoveAi(g, me, me.getController().getLobbyPlayer(), mv, node.turn);
            if (lim.anyWindow && !(g.getPhaseHandler().isPlayerTurn(me) && (g.getPhaseHandler().is(PhaseType.MAIN1)
                    || g.getPhaseHandler().is(PhaseType.MAIN2)))) {
                ai.restPhase = g.getPhaseHandler().getPhase(); // R2-4 (c): rest again in the root's window
            }
            if (lim.stack && !g.getStack().isEmpty()) {
                ai.restPhase = g.getPhaseHandler().getPhase(); // R3-5: a response: rest in the root's turn and phase
                ai.restOnTheirs = true;
            }
            if (lim.cac) {
                ai.rec = new ForcedWinChoices.Recorder(mv.cac, lim.cacCap); // labeller-v2 (a)
            }
            ai.lim = lim;
            if (lim.costChoice) {
                // R3-4 (b): the cost cards of the move's abilities branch too (E2's CacHook.costCards point)
                if (ai.rec == null) {
                    ai.rec = new ForcedWinChoices.Recorder(mv.cac, lim.cacCap);
                    ai.rec.costOnly = true;
                }
                ai.setCacHook(ai.costHook());
            }
            // solver-gaps-1015: every step of the move, combat declarations included, is bounded by the wall
            // (bvw-step1-build-1024: none with budget=counter: no wall and no deadline, so per-move maxSteps bounds it)
            final long wall = lim.wall != 0 ? lim.wall : deadline == Long.MAX_VALUE ? 0L : deadline;
            ai.bounded(wall).callLimited(lim.callLimit);
            held[0] = ai;
            me.dangerouslySetController(ai);
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new GuardedAi(g, o, o.getController().getLobbyPlayer()).bounded(wall)
                            .callLimited(lim.callLimit));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            LookaheadSearch.givePriority(ph, me);
            int steps = 0;
            final int lastTurn = mv.pass ? node.turn + 2 : node.turn;
            while (!g.isGameOver() && !ai.rest && !ai.failed && ph.getTurn() <= lastTurn && steps < lim.maxSteps) {
                checkWall(wall, "engine step");
                ph.mainLoopStep();
                steps++;
                st.steps++;
            }
            final Applied a = new Applied();
            a.triggerCandidates = ai.triggers.candidates;
            a.pickCandidates = ai.picks.candidates;
            a.rec = ai.rec;
            a.won = me.hasWon() || me.getOpponents().stream().anyMatch(Player::hasLost);
            if (a.won || !ai.rest || g.isGameOver()) {
                a.why = ai.failed ? "failed" : g.isGameOver() ? "over" : ph.getTurn() > lastTurn ? "turn" : "steps" + steps;
                return a;
            }
            final Node child = new Node();
            child.g = g;
            child.cards = copyCards;
            child.me = me;
            if (ai.snapshot != null) {
                // R3-5: rested at our priority with the opponent's item on the stack: the node is the copy taken there
                // (our pass that followed would resolve it), a fresh copy as the root is
                child.g = ai.snapshot;
                child.me = ai.snapshotMe;
                child.cards = cardCount(child.g); // review #154-6: the snapshot's own size and key
                st.copies++;
                st.cards += child.cards;
            }
            child.turn = ph.getTurn();
            child.ids = cloneScope(forge.util.IdScope.capture());
            a.child = child;
            a.key = PlayoutKeys.stateKey(child.g);
            return a;
        } catch (OutOfBudget ex) {
            // the move did not finish by the wall (or the rules refused its attack twice): dropped
            st.cut++;
            lastError = String.valueOf(ex);
            ForcedWinSolver.receipt("FORCED_WIN engine cut move=" + mv + " " + ex.getMessage());
            return null;
        } catch (RuntimeException | StackOverflowError ex) {
            lastError = String.valueOf(ex);
            if (TRACE) {
                ex.printStackTrace();
            }
            return null;
        } finally {
            if (held[0] != null) {
                st.refusedAttacks += held[0].refusedAttacks();
            }
            MyRandom.setThreadRandom(prev);
        }
    }

    /** Our seat while one move is played: the move, then pass until our next main-phase priority with an empty stack. */
    static final class MoveAi extends GuardedAi {
        final Step move;
        boolean acted, rest, failed, attacking;
        final TriggerTargets triggers;
        final LibraryPicks picks;

        final int fromTurn;
        /** R2-4 (c): the root's phase when it is not our main phase (null = rest only at our main phase). */
        PhaseType restPhase;
        /** R3-5: also rest at our priority in the root's window when the top of the stack is not ours (a response). */
        boolean restOnTheirs;
        /** R3-5: the copy taken at that rest (null = none). */
        Game snapshot;
        Player snapshotMe;

        /** R3-4 (b): the search's limits (engineCostChoice's trigger kind reads them). */
        Limits lim = new Limits();

        MoveAi(Game g, Player p, forge.LobbyPlayer lp, Step move, int fromTurn) {
            super(g, p, lp);
            this.move = move;
            this.fromTurn = fromTurn;
            this.triggers = new TriggerTargets(move.triggerRef);
            this.picks = new LibraryPicks(move.pickName);
        }

        /** labeller-v2 (a): the move's choice points (null = v1: only the first own-zone pick, by {@link #picks}). */
        ForcedWinChoices.Recorder rec;
        /** labeller-v2 (e): the payment floats played so far (the move's recorded sources, before its action). */
        int floated;

        /** labeller-v2 (a)'s kinds record (a recorder that is not COST-only, R3-4 b). */
        private boolean recAll() {
            return rec != null && !rec.costOnly;
        }

        /**
         * R3-4 (b): the move's cost cards as a COST choice point: a forced token's cards, else Forge AI's, recorded with
         * every other distinct multiset of names as candidates.
         */
        forge.ai.CacHook costHook() {
            return new forge.ai.CacHook() {
                @Override
                public forge.game.card.CardCollectionView costCards(forge.ai.PlayerControllerAi c, SpellAbility sa,
                        String part, forge.game.card.CardCollectionView valid, int amount,
                        forge.game.card.CardCollectionView def) {
                    if (rec == null || c != MoveAi.this || valid == null || valid.size() <= amount || amount < 1) {
                        return def;
                    }
                    final String t = rec.at(ForcedWinChoices.COST);
                    if (t != null) {
                        final forge.game.card.CardCollection r = ForcedWinChoices.applyCost(t, valid, amount);
                        if (r != null) {
                            rec.played(t);
                            return r;
                        }
                        rec.miss = true;
                    }
                    final String dt = ForcedWinChoices.costTok(def);
                    if (rec.forcedPoint()) {
                        rec.played(dt);
                    } else {
                        rec.free(ForcedWinChoices.costs(valid, amount, def, rec.cap), dt);
                    }
                    return def;
                }
            };
        }

        /** An own-zone pick of ours (LibraryPicks' scope, every pick of the move, not only the first). */
        static boolean ownPick(Player me, List<ZoneType> origin, SpellAbility sa,
                forge.game.card.CardCollectionView fetchList, Player decider) {
            return decider == me && origin != null && (origin.contains(ZoneType.Library) || origin.contains(ZoneType.Hand)
                    || origin.contains(ZoneType.Graveyard)) && sa != null && sa.getActivatingPlayer() == me
                    && fetchList != null && !fetchList.isEmpty() && fetchList.stream().allMatch(c -> c.getOwner() == me);
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                forge.game.card.CardCollection fetchList, forge.game.player.DelayedReveal delayedReveal, String selectPrompt,
                boolean isOptional, Player decider) {
            if (recAll()) {
                if (!ownPick(getPlayer(), origin, sa, fetchList, decider)) {
                    return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                            selectPrompt, isOptional, decider);
                }
                final String t = rec.at(ForcedWinChoices.PICK);
                if (t != null) {
                    final Card[] r = ForcedWinChoices.applyPick(t, fetchList);
                    if (r != null && (r[0] != null || isOptional)) {
                        rec.played(t);
                        return r[0];
                    }
                    rec.miss = true;
                }
                final Card def = super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                        selectPrompt, isOptional, decider);
                if (rec.forcedPoint()) {
                    rec.played(ForcedWinChoices.pickTok(def));
                } else {
                    rec.free(ForcedWinChoices.picks(fetchList, isOptional, def, rec.cap), ForcedWinChoices.pickTok(def));
                }
                return def;
            }
            final Card c = picks.pick(getPlayer(), origin, sa, fetchList, decider);
            return c != null ? c : super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                    selectPrompt, isOptional, decider);
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<forge.game.card.CardCollection, forge.game.card.CardCollection>
                arrangeForScry(forge.game.card.CardCollection topN) {
            return !recAll() || topN == null || topN.isEmpty() ? super.arrangeForScry(topN)
                    : arrange(ForcedWinChoices.SCRY, topN);
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<forge.game.card.CardCollection, forge.game.card.CardCollection>
                arrangeForSurveil(forge.game.card.CardCollection topN) {
            return !recAll() || topN == null || topN.isEmpty() ? super.arrangeForSurveil(topN)
                    : arrange(ForcedWinChoices.SURV, topN);
        }

        private org.apache.commons.lang3.tuple.ImmutablePair<forge.game.card.CardCollection, forge.game.card.CardCollection>
                arrange(String kind, forge.game.card.CardCollection topN) {
            final String t = rec.at(kind);
            if (t != null) {
                final org.apache.commons.lang3.tuple.ImmutablePair<forge.game.card.CardCollection, forge.game.card.CardCollection> r
                        = ForcedWinChoices.applyArr(t, topN);
                if (r != null) {
                    rec.played(t);
                    return r;
                }
                rec.miss = true;
            }
            final org.apache.commons.lang3.tuple.ImmutablePair<forge.game.card.CardCollection, forge.game.card.CardCollection> def
                    = kind.equals(ForcedWinChoices.SCRY) ? super.arrangeForScry(topN) : super.arrangeForSurveil(topN);
            final String dt = ForcedWinChoices.arrTok(kind, def.getLeft(), def.getRight());
            if (rec.forcedPoint()) {
                rec.played(dt);
            } else {
                rec.free(ForcedWinChoices.arrangements(kind, topN, def, rec.cap), dt);
            }
            return def;
        }

        @Override
        public forge.game.card.CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa,
                forge.game.card.CardCollection validCards, int min, int max,
                forge.game.card.CardCollectionView visibleToChooser) {
            if (!recAll() || p != getPlayer() || min != max || min < 1 || validCards == null || validCards.size() <= min) {
                return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
            }
            final String t = rec.at(ForcedWinChoices.DISC);
            if (t != null) {
                final forge.game.card.CardCollection r = ForcedWinChoices.applyDisc(t, validCards, min, max);
                if (r != null) {
                    rec.played(t);
                    return r;
                }
                rec.miss = true;
            }
            final forge.game.card.CardCollection def = super.chooseCardsToDiscardFrom(p, sa, validCards, min, max,
                    visibleToChooser);
            if (def == null) {
                return null;
            }
            final String dt = ForcedWinChoices.discTok(def);
            if (rec.forcedPoint()) {
                rec.played(dt);
            } else {
                rec.free(ForcedWinChoices.discards(validCards, min, def, rec.cap), dt);
            }
            return def;
        }

        @Override
        public forge.game.card.CardCollectionView orderMoveToZoneList(forge.game.card.CardCollectionView cards,
                ZoneType destinationZone, SpellAbility source) {
            if (!recAll() || destinationZone != ZoneType.Library || cards == null || cards.size() < 2
                    || !cards.stream().allMatch(c -> c.getOwner() == getPlayer())) {
                return super.orderMoveToZoneList(cards, destinationZone, source);
            }
            final String t = rec.at(ForcedWinChoices.ORDER);
            if (t != null) {
                final forge.game.card.CardCollectionView r = ForcedWinChoices.applyOrder(t, cards);
                if (r != null) {
                    rec.played(t);
                    return r;
                }
                rec.miss = true;
            }
            final forge.game.card.CardCollectionView def = super.orderMoveToZoneList(cards, destinationZone, source);
            final String dt = ForcedWinChoices.orderTok(def);
            if (rec.forcedPoint()) {
                rec.played(dt);
            } else {
                rec.free(ForcedWinChoices.orders(cards, def, rec.cap), dt);
            }
            return def;
        }

        @Override
        public boolean confirmAction(SpellAbility sa, forge.game.player.PlayerActionConfirmMode mode, String message,
                List<String> options, Card cardToShow, java.util.Map<String, Object> params) {
            if (!recAll() || message == null || !message.toLowerCase().contains("shuffle")) {
                return super.confirmAction(sa, mode, message, options, cardToShow, params);
            }
            final String t = rec.at(ForcedWinChoices.SHUF);
            if (t != null) {
                rec.played(t);
                return ForcedWinChoices.applyShuf(t);
            }
            final boolean def = super.confirmAction(sa, mode, message, options, cardToShow, params);
            if (rec.forcedPoint()) {
                rec.played(ForcedWinChoices.shufTok(def));
            } else {
                rec.free(ForcedWinChoices.shuffles(def), ForcedWinChoices.shufTok(def));
            }
            return def;
        }

        @Override
        public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
            super.orderAndPlaySimultaneousSa(triggers.apply(getGame(), getPlayer(), abilities));
        }

        @Override
        public boolean confirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
            if (triggers.confirms(wrapper)) {
                return true;
            }
            if (rec != null && lim.costChoice && wrapper != null && wrapper.getHostCard() != null) {
                // R3-4 (b), engineCostChoice: our optional trigger ("you may") is a choice point: Forge AI's answer and
                // the other one
                final String host = ForcedWinChoices.trigHost(wrapper);
                final String t = rec.at(ForcedWinChoices.TRIG);
                if (t != null && t.startsWith(ForcedWinChoices.TRIG + ":" + host + "=")) {
                    rec.played(t);
                    return t.endsWith("=y");
                }
                if (t != null) {
                    rec.miss = true;
                }
                final boolean def = super.confirmTrigger(wrapper);
                final String dt = ForcedWinChoices.trigTok(host, def);
                if (rec.forcedPoint()) {
                    rec.played(dt);
                } else {
                    rec.free(List.of(dt, ForcedWinChoices.trigTok(host, !def)), dt);
                }
                return def;
            }
            return super.confirmTrigger(wrapper);
        }

        /** Tap or untap: the rules' default (untap our own, tap theirs) instead of Forge AI's "always tap". */
        @Override
        public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kind, Boolean defaultVal) {
            if (kind == BinaryChoiceType.TapOrUntap && defaultVal != null) {
                return defaultVal;
            }
            return super.chooseBinary(sa, question, kind, defaultVal);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            if (!acted && move.payFloats != null && floated < move.payFloats.size()) {
                // labeller-v2 (e): the move's recorded sources float first; Forge AI then pays from the pool
                final SpellAbility fa = ForcedWinSolver.Follower.locate(g, getPlayer(), move.payFloats.get(floated++));
                if (fa == null) {
                    failed = true;
                    acted = true;
                    return null;
                }
                return capped(List.of(fa));
            }
            if (!acted) {
                acted = true;
                if (move.pass) {
                    return null;
                }
                if (move.kind == AbilKind.ATTACK) {
                    attacking = true;
                    return null;
                }
                final SpellAbility sa = ForcedWinSolver.Follower.locate(g, getPlayer(), move);
                if (sa == null) {
                    failed = true;
                    return null;
                }
                return capped(List.of(sa));
            }
            final PhaseHandler ph = g.getPhaseHandler();
            if (TRACE && restOnTheirs) {
                ForcedWinSolver.receipt("FW_ENGINE     moveai t" + ph.getTurn() + " " + ph.getPhase() + " stack=" + g.getStack().size()
                        + (g.getStack().isEmpty() ? "" : " top=" + g.getStack().peek().getActivatingPlayer()));
            }
            if (g.getStack().isEmpty() && ph.isPlayerTurn(getPlayer())
                    && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2))
                    && (!move.pass || ph.getTurn() > fromTurn && ph.is(PhaseType.MAIN1))) {
                rest = true;
            } else if (restPhase != null && !move.pass && g.getStack().isEmpty() && ph.getTurn() == fromTurn
                    && ph.is(restPhase)) {
                rest = true; // R2-4 (c): our priority again in the root's window, the stack empty
            } else if (restOnTheirs && !move.pass && !g.getStack().isEmpty() && ph.getTurn() == fromTurn && ph.is(restPhase)
                    && g.getStack().peek().getActivatingPlayer() != getPlayer()) {
                // R3-5: our priority again in the root's window, the opponent's item on top: the node is a copy taken
                // here, before this pass lets their item resolve (review #154-2: a stack GameCopier cannot copy
                // faithfully drops the move instead of resting on a copy that lost their item)
                if (GameCopier.stackUnsupported(g) != null) {
                    failed = true;
                    return null;
                }
                final GameCopier rc = new GameCopier(g, true);
                rc.setCopyFidelity(1);
                copyStackIf(rc, g, true);
                snapshot = rc.makeCopy();
                snapshotMe = (Player) rc.find(getPlayer());
                rest = true;
            }
            return null;
        }

        /** "Attack with everything" (if the rules refuse it, the guard hands the declaration to Forge AI). */
        @Override
        void declareFirst(Player attacker, Combat combat) {
            if (attacking) {
                for (Card c : attacker.getCreaturesInPlay()) {
                    for (Player o : attacker.getOpponents()) {
                        if (CombatUtil.canAttack(c, o)) {
                            combat.addAttacker(c, o);
                            break;
                        }
                    }
                }
                return;
            }
            super.declareFirst(attacker, combat);
        }
    }
}
