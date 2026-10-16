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
        /** solver-gaps-1015: moves cut at the wall (a step still running at the search's deadline, or a refused attack). */
        int cut;
        /** solver-gaps-1015: our attack declarations the rules refused (handed to Forge AI). */
        int refusedAttacks;
        long nanos;
        /** search-bones-1015 (c): nodes pruned by the progress table, loops found, body repetitions applied. */
        int pruned, loops, loopReps, loopWins;

        String summary() {
            return "engineNodes=" + nodes + " engineCopies=" + copies + " moves=" + moves + " engineMs=" + nanos / 1_000_000
                    + (cut > 0 ? " engineCut=" + cut : "") + (refusedAttacks > 0 ? " refusedAttacks=" + refusedAttacks : "")
                    + (loops > 0 || pruned > 0 ? " pruned=" + pruned + " loops=" + loops + " loopReps=" + loopReps
                            + " loopWins=" + loopWins : "");
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

        GuardedAi(Game g, Player p, forge.LobbyPlayer lp) {
            super(g, p, lp);
        }

        GuardedAi bounded(long wall) {
            this.wall = wall;
            return this;
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
            if (attackGuard.next(combat, wall) == 0) {
                declareFirst(attacker, combat);
            } else {
                super.declareAttackers(attacker, combat);
            }
        }

        @Override
        public void declareBlockers(Player defender, Combat combat) {
            checkWall(wall, "block declaration");
            super.declareBlockers(defender, combat);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            checkWall(wall, "priority");
            return super.chooseSpellAbilityToPlay();
        }
    }

    static final class Limits {
        int nodes = 250;
        int depth = 64;
        int branching = 8;
        int targetVariants = 3;
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
    private Forward fastForward(Node from, List<Step> body) {
        st.loops++;
        final int len = body.size();
        final List<Step> prefix = new ArrayList<>(from.path.subList(0, from.path.size() - 2 * len));
        int reps = 2;
        Node cur = from;
        final Forward f = new Forward();
        while (reps < lim.repeatCap && !out()) {
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
                a.child.parent = next;
                a.child.pv = progress(a.child.g, a.child.me);
                a.child.passed = next.passed;
                next = a.child;
            }
            if (failed || !progressed(next.pv, cur.pv)) {
                break;
            }
            cur = next;
            reps++;
        }
        // the node after the last repetition that progressed: a REPEAT move to go on from
        final Node n = new Node();
        n.g = cur.g;
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

    private final Limits lim;
    private final Stats st;
    private final long deadline;
    private final long seed;
    private final Set<String> seen = new HashSet<>();
    private int startTurn;
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
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(seed));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final ForcedWinEngine e = new ForcedWinEngine(lim, st, deadline, seed);
            if (likelyOut != null) {
                e.collect = new ArrayList<>();
                e.keep = Math.max(1, keep) * 3;
            }
            final Node root = new Node();
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1); // storm count, per-turn records
                root.g = copier.makeCopy();
                root.me = (Player) copier.find(liveMe);
                st.copies++;
                final Random rng = new Random(LookaheadSearch.mix(seed, 2));
                for (Player o : root.me.getOpponents()) {
                    ForcedWinSolver.hideOrSample(live, liveMe, (Player) live.getPlayer(o.getId()), o, true, rng);
                }
            }
            root.ids = cloneScope(forge.util.IdScope.capture());
            e.startTurn = root.g.getPhaseHandler().getTurn();
            root.turn = e.startTurn;
            if (lim.cycles) {
                root.pv = progress(root.g, root.me);
            }
            // this turn first (same-turn wins are preferred and cheaper); then lines that pass the turn once
            final boolean twoTurns = lim.passTurn;
            lim.passTurn = false;
            final int nodeCap = lim.nodes;
            lim.nodes = twoTurns ? nodeCap / 2 : nodeCap;
            List<Step> win = e.dfs(root, 0);
            if (win == null && twoTurns && System.nanoTime() < deadline) {
                lim.passTurn = true;
                lim.nodes = st.nodes + nodeCap / 2;
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
            st.nanos += System.nanoTime() - t0;
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
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
        return st.nodes >= lim.nodes || System.nanoTime() > deadline;
    }

    private List<Step> dfs(Node node, int depth) {
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
        for (int mi = 0; mi < moves.size(); mi++) {
            final Step mv = moves.get(mi);
            // solver-gaps-1015: the branching cap counts distinct moves only; the forced-answer variants of a move (its
            // picks, its trigger targets) have their own cap and no longer crowd the other moves out (Natural Order's
            // variants used every branch, so casting Llanowar Elves first was never tried)
            if (out() || !variants.contains(mv) && tried >= lim.branching) {
                if (out()) {
                    break;
                }
                continue;
            }
            if (!variants.contains(mv)) {
                tried++;
            }
            st.moves++;
            final Applied a = apply(node, mv);
            if (TRACE) {
                ForcedWinSolver.receipt("FW_ENGINE   move " + mv + " -> " + (a == null ? "error " + lastError
                        : a.won ? "WON" : a.child == null ? "no-rest(" + a.why + ")" : "rest"));
            }
            if (a == null) {
                continue;
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
            a.child.path.addAll(node.path);
            a.child.path.add(mv);
            a.child.passed = node.passed || mv.pass;
            if (lim.cycles) {
                // search-bones-1015 (c): the progress table instead of the exact-key set, and loops fast-forwarded
                a.child.parent = node;
                a.child.pv = progress(a.child.g, a.child.me);
                final List<Step> body = loopBody(a.child);
                if (body != null) {
                    final Forward fw = fastForward(a.child, body);
                    if (fw.win != null) {
                        return fw.win;
                    }
                    if (!seenDominated(fw.node)) {
                        final List<Step> r = dfs(fw.node, depth + 1);
                        if (r != null) {
                            return r;
                        }
                    } else {
                        st.pruned++;
                    }
                    continue;
                }
                if (seenDominated(a.child)) {
                    st.pruned++;
                    continue;
                }
            } else if (!seen.add(a.key)) {
                continue;
            }
            final List<Step> r = dfs(a.child, depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** Our legal moves at a node, best first: the previous move's ability, permanents' abilities, spells, lands, attack. */
    private List<Step> moves(Node node) {
        final Game g = node.g;
        final Player me = node.me;
        final List<Step> rep = new ArrayList<>(), act = new ArrayList<>(), spells = new ArrayList<>(), lands = new ArrayList<>();
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
                    }
                    (repeat ? rep : c.isInPlay() ? act : spells).add(s);
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
        final List<Step> out = new ArrayList<>(rep);
        out.addAll(act);
        out.addAll(spells);
        out.addAll(floats);
        out.addAll(lands);
        if (!node.passed && lim.passTurn) {
            out.add(Step.PASS);
        }
        if (g.getPhaseHandler().is(PhaseType.MAIN1) && canAttack(me)) {
            final Step atk = Step.engine(AbilKind.ATTACK, -1, "attack", "attack", "", false);
            if (lethalOnBoard(me)) {
                out.add(0, atk);
            } else {
                out.add(atk);
            }
        }
        return out;
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

    /** One step per target variant of a legal ability (Forge AI's targets, then a few of our own permanents). */
    private List<Step> variants(Game g, Player me, Card c, SpellAbility sa0, String desc, String costKey) {
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
            for (Card p : me.getCardsIn(ZoneType.Battlefield)) {
                cands.add(p);
            }
            cands.addAll(g.getPlayers());
            for (GameObject o : cands) {
                if (n >= lim.targetVariants) {
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
            final Game g = copier.makeCopy();
            final Player me = (Player) copier.find(node.me);
            st.copies++;
            final MoveAi ai = new MoveAi(g, me, me.getController().getLobbyPlayer(), mv, node.turn);
            // solver-gaps-1015: every step of the move, combat declarations included, is bounded by the wall
            final long wall = lim.wall != 0 ? lim.wall : deadline;
            ai.bounded(wall);
            held[0] = ai;
            me.dangerouslySetController(ai);
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new GuardedAi(g, o, o.getController().getLobbyPlayer()).bounded(wall));
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
            }
            final Applied a = new Applied();
            a.triggerCandidates = ai.triggers.candidates;
            a.pickCandidates = ai.picks.candidates;
            a.won = me.hasWon() || me.getOpponents().stream().anyMatch(Player::hasLost);
            if (a.won || !ai.rest || g.isGameOver()) {
                a.why = ai.failed ? "failed" : g.isGameOver() ? "over" : ph.getTurn() > lastTurn ? "turn" : "steps" + steps;
                return a;
            }
            final Node child = new Node();
            child.g = g;
            child.me = me;
            child.turn = ph.getTurn();
            child.ids = cloneScope(forge.util.IdScope.capture());
            a.child = child;
            a.key = PlayoutKeys.stateKey(g);
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

        MoveAi(Game g, Player p, forge.LobbyPlayer lp, Step move, int fromTurn) {
            super(g, p, lp);
            this.move = move;
            this.fromTurn = fromTurn;
            this.triggers = new TriggerTargets(move.triggerRef);
            this.picks = new LibraryPicks(move.pickName);
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                forge.game.card.CardCollection fetchList, forge.game.player.DelayedReveal delayedReveal, String selectPrompt,
                boolean isOptional, Player decider) {
            final Card c = picks.pick(getPlayer(), origin, sa, fetchList, decider);
            return c != null ? c : super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                    selectPrompt, isOptional, decider);
        }

        @Override
        public void orderAndPlaySimultaneousSa(List<SpellAbility> abilities) {
            super.orderAndPlaySimultaneousSa(triggers.apply(getGame(), getPlayer(), abilities));
        }

        @Override
        public boolean confirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
            return triggers.confirms(wrapper) || super.confirmTrigger(wrapper);
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
            if (g.getStack().isEmpty() && ph.isPlayerTurn(getPlayer())
                    && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2))
                    && (!move.pass || ph.getTurn() > fromTurn && ph.is(PhaseType.MAIN1))) {
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
