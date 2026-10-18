package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.ai.AiCache;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Lane steps-back-1016 (bench only; no seat ever calls it): the REACH check of the reverse-curriculum generator.
 *
 * <p>Question: from a position whose immediate forced win is gone (the solver proves nothing here), can a solver-proven
 * win still be reached within a horizon of our own turns, through at most {@code maxFixes} fixing actions and passes?
 * A fixing action is one of our playable abilities whose PROBED affordance ({@link ForcedWinProbe}) draws, digs,
 * tutors or puts back (DRAW, PILE, PUTBACK = "cantrip-dig"; TUTOR, TUTOR_TOP = "tutor"). A pass makes this turn's land
 * drop (a land that is not a piece, when there is one) and plays nothing else; our next turn's draw step happens
 * ("draw-step"). Nothing here names a card.</p>
 *
 * <p>The search runs on Forge game copies of the position, breadth-first in steps. The opponent acts only
 * through its visible board (its hidden hand removed, as the solver's world 0): this is an existence check on the
 * actual position, so our library keeps its real order. Our seat, while a move is played, keeps the line's pieces
 * (named by the caller): a library search offers them first, put-backs, discards and library orders avoid them. At
 * every node the forced-win solver is asked exactly as a seat would ask it ({@link ForcedWinSolver#prove}); a proof
 * whose win falls inside the horizon is a reach.</p>
 */
public final class StepsBackReach {
    private StepsBackReach() {
    }

    public static final class Config {
        /**
         * Steps allowed: fixing actions plus passes. The search is breadth-first in steps, so the first reach found has
         * the fewest steps: that count is the position's DISTANCE from a solver-proven win.
         */
        public int maxSteps = 2;
        /** Fixing actions allowed (at most maxSteps). */
        public int maxFixes = 2;
        /** Own turns in the horizon, this one included: the proof's win must come by our turn index maxOwnTurns-1. */
        public int maxOwnTurns = 4;
        public int nodes = 60;
        public long budgetMs = 120_000;
        public long seed = 20261016L;
        /** Library-search picks tried per tutor move beyond the pieces. */
        public int extraPicks = 1;
        public int loopSteps = 3000;
        public ForcedWinSolver.Config solver = new ForcedWinSolver.Config();
        /** The proven line's pieces (card names) the caller moved away: kept and searched for first. */
        public final Set<String> pieces = new HashSet<>();
    }

    public static final class Result {
        public boolean reached;
        public String why = "";
        public int nodes, proofs, expanded;
        public long ms;
        /** The reaching path: one entry per move, "class:card" or "pass". */
        public final List<String> path = new ArrayList<>();
        /** Per move: tutor, cantrip-dig, draw-step. */
        public final List<String> classes = new ArrayList<>();
        public int fixes, passes;
        /** The proof found at the end of the path. */
        public String proofLine;
        public int proofWinTurn = -1;
        public double pUnopposed, pBelief;
        /** Own turns used: passes + the proof's winTurn + 1. */
        public int ownTurns;
        /** The minimal fixing class: the distinct classes of the path, in order (draw-step only when passes alone). */
        public String label = "";

        @Override
        public String toString() {
            return (reached ? "REACH " + label + " path=" + path + " ownTurns=" + ownTurns + " proof=" + proofLine : "NO " + why)
                    + " nodes=" + nodes + " proofs=" + proofs + " ms=" + ms;
        }
    }

    /** A search node: a game copy at our main-phase priority with an empty stack. */
    private static final class Node {
        Game g;
        Player me;
        Object ids;
        int turn;
        int passes, fixes;
        long order;
        final List<String> path = new ArrayList<>();
        final List<String> classes = new ArrayList<>();
    }

    /** One candidate move: a fixing ability (with its forced pick) or a pass. */
    private static final class Move {
        final Step step;
        final String cls;
        String pick;

        Move(Step step, String cls) {
            this.step = step;
            this.cls = cls;
        }

        @Override
        public String toString() {
            return step.pass ? "pass" : cls + ":" + step.hostName + (pick == null ? "" : "->" + pick);
        }
    }

    /** Search from {@code live} (our priority, empty stack, our main phase). Never throws. */
    public static Result search(Game live, Player liveMe, Config cfg) {
        final Result r = new Result();
        final long t0 = System.nanoTime();
        final long deadline = t0 + cfg.budgetMs * 1_000_000L;
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(cfg.seed));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final ForcedWinModel.Reader reader = new ForcedWinModel.Reader();
            reader.seed = cfg.solver.seed;
            final Node root = new Node();
            synchronized (live) {
                final GameCopier copier = new GameCopier(live, true);
                copier.setCopyFidelity(1);
                root.g = copier.makeCopy();
                root.me = (Player) copier.find(liveMe);
                final Random rng = new Random(LookaheadSearch.mix(cfg.seed, 2));
                for (Player o : root.me.getOpponents()) {
                    ForcedWinSolver.hideOrSample(live, liveMe, (Player) live.getPlayer(o.getId()), o, true, rng);
                }
            }
            root.ids = ForcedWinEngine.cloneScope(forge.util.IdScope.capture());
            root.turn = root.g.getPhaseHandler().getTurn();
            // breadth-first in steps (fixes + passes); same-turn fixes before passes at equal steps
            final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.<Node>comparingInt(n -> n.passes + n.fixes)
                    .thenComparingInt(n -> n.passes).thenComparingLong(n -> n.order));
            open.add(root);
            final Set<String> seen = new HashSet<>();
            long order = 1;
            while (!open.isEmpty()) {
                if (System.nanoTime() > deadline) {
                    r.why = "budget";
                    break;
                }
                if (r.nodes >= cfg.nodes) {
                    r.why = "nodes";
                    break;
                }
                final Node n = open.poll();
                r.nodes++;
                forge.util.IdScope.install(ForcedWinEngine.cloneScope(n.ids));
                LookaheadSearch.givePriority(n.g.getPhaseHandler(), n.me);
                // the solver, exactly as a seat asks it, at every node but the root: the root's verdict is the live
                // seat's own (the caller's "immediate forced win gone"); a copy's root can differ from it (the engine
                // search sees the copy's library order, and the verification worlds are sampled anew)
                ForcedWinSolver.Proof proof = null;
                if (n.passes + n.fixes > 0) {
                    final ForcedWinSolver solver = new ForcedWinSolver(n.me, cfg.solver, reader);
                    r.proofs++;
                    proof = solver.prove(n.g);
                }
                forge.util.IdScope.install(ForcedWinEngine.cloneScope(n.ids));
                if (proof != null && n.passes + proof.winTurn <= cfg.maxOwnTurns - 1) {
                    r.reached = true;
                    r.path.addAll(n.path);
                    r.classes.addAll(n.classes);
                    r.fixes = n.fixes;
                    r.passes = n.passes;
                    r.proofLine = proof.line;
                    r.proofWinTurn = proof.winTurn;
                    r.pUnopposed = proof.pUnopposed;
                    r.pBelief = proof.pBelief;
                    r.ownTurns = n.passes + proof.winTurn + 1;
                    r.label = label(n.classes);
                    break;
                }
                if (n.passes + n.fixes >= cfg.maxSteps) {
                    continue;
                }
                r.expanded++;
                final List<Move> moves = new ArrayList<>();
                if (n.fixes < cfg.maxFixes) {
                    moves.addAll(fixingMoves(n, reader, cfg));
                }
                if (n.passes < cfg.maxOwnTurns - 1) {
                    moves.add(new Move(Step.PASS, "draw-step"));
                }
                for (int mi = 0; mi < moves.size(); mi++) {
                    if (System.nanoTime() > deadline) {
                        break;
                    }
                    final Move mv = moves.get(mi);
                    final Applied a = apply(n, mv, cfg);
                    if (a == null) {
                        continue;
                    }
                    if (a.child != null) {
                        ForcedWinSolver.receipt("STEPS_BACK child " + n.path + " + " + mv + " -> turn " + a.child.turn);
                    }
                    if (mv.pick == null && !a.pickCandidates.isEmpty()) {
                        // a library search: the pieces first, then a few others (Forge AI's own pick was this try)
                        final List<String> order2 = new ArrayList<>();
                        for (String nm : a.pickCandidates) {
                            if (cfg.pieces.contains(nm)) {
                                order2.add(nm);
                            }
                        }
                        int extra = 0;
                        for (String nm : a.pickCandidates) {
                            if (!order2.contains(nm) && extra++ < cfg.extraPicks) {
                                order2.add(nm);
                            }
                        }
                        int k = 0;
                        for (String nm : order2) {
                            final Move v = new Move(mv.step, mv.cls);
                            v.pick = nm;
                            moves.add(mi + 1 + k++, v);
                        }
                    }
                    if (a.won) {
                        r.reached = true;
                        r.path.addAll(n.path);
                        r.path.add(mv.toString());
                        r.classes.addAll(n.classes);
                        r.classes.add(mv.cls);
                        r.fixes = n.fixes + (mv.step.pass ? 0 : 1);
                        r.passes = n.passes + (mv.step.pass ? 1 : 0);
                        r.proofLine = "won during the move";
                        r.proofWinTurn = 0;
                        r.ownTurns = r.passes + 1;
                        r.label = label(r.classes);
                        open.clear();
                        break;
                    }
                    if (a.child == null || !seen.add(a.key + "|" + (n.fixes + (mv.step.pass ? 0 : 1)))) {
                        continue;
                    }
                    final Node c = a.child;
                    c.passes = n.passes + (mv.step.pass ? 1 : 0);
                    c.fixes = n.fixes + (mv.step.pass ? 0 : 1);
                    c.path.addAll(n.path);
                    c.path.add(mv.toString());
                    c.classes.addAll(n.classes);
                    c.classes.add(mv.cls);
                    c.order = order++;
                    open.add(c);
                }
            }
            if (!r.reached && r.why.isEmpty()) {
                r.why = "exhausted";
            }
        } catch (RuntimeException | StackOverflowError ex) {
            r.reached = false;
            r.why = "error:" + ex;
        } finally {
            r.ms = (System.nanoTime() - t0) / 1_000_000;
            AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            MyRandom.setThreadRandom(prev);
        }
        return r;
    }

    /** The distinct classes of a path in order; "draw-step" only when the path is passes alone. */
    static String label(List<String> classes) {
        final List<String> out = new ArrayList<>();
        boolean fix = false;
        for (String c : classes) {
            fix |= !"draw-step".equals(c);
        }
        for (String c : classes) {
            if (fix && "draw-step".equals(c)) {
                continue;
            }
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        return out.isEmpty() ? "none" : String.join("+", out);
    }

    /** Our playable abilities whose probed affordance draws, digs, tutors or puts back; "tutor" first. */
    private static List<Move> fixingMoves(Node n, ForcedWinModel.Reader reader, Config cfg) {
        final List<Move> tutors = new ArrayList<>(), digs = new ArrayList<>();
        final Set<String> keys = new HashSet<>();
        final Player me = n.me;
        for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile,
                ZoneType.Command}) {
            for (Card c : new ArrayList<>(me.getCardsIn(z))) {
                final List<SpellAbility> all;
                try {
                    all = c.getAllPossibleAbilities(me, true);
                } catch (RuntimeException e) {
                    continue;
                }
                ACard ac = null;
                for (SpellAbility sa0 : all) {
                    if (sa0.isManaAbility() || sa0.isLandAbility()) {
                        continue;
                    }
                    final String desc = sa0.getDescription();
                    final String costKey = sa0.getPayCosts() == null ? "" : sa0.getPayCosts().toString();
                    if (!keys.add(c.getId() + "|" + desc + "|" + costKey)) {
                        continue;
                    }
                    final SpellAbility sa = sa0.copy(me);
                    sa.setActivatingPlayer(me);
                    try {
                        if (!sa.canPlay() || !ComputerUtilCost.canPayCost(sa, me, false)) {
                            continue;
                        }
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (ac == null) {
                        try {
                            ac = reader.card(n.g, me, c);
                        } catch (RuntimeException e) {
                            break;
                        }
                    }
                    final String cls = affordance(ac, c, desc, costKey);
                    if (cls == null) {
                        continue;
                    }
                    final Step s = Step.engine(AbilKind.ENGINE, c.getId(), c.getName(), desc, costKey, sa.isSpell());
                    if (ForcedWinProbe.targetsAnything(sa)) {
                        // Forge AI's own targets (a player target: usually ourselves for a draw)
                        try {
                            final SpellAbility ai = sa0.copy(me);
                            ai.setActivatingPlayer(me);
                            if (((PlayerControllerAi) me.getController()).getAi().canPlaySa(ai) != AiPlayDecision.WillPlay
                                    || !ai.isTargetNumberValid()) {
                                continue;
                            }
                            s.targetRefs.addAll(ForcedWinEngine.refs(ai));
                        } catch (RuntimeException e) {
                            continue;
                        }
                    }
                    ("tutor".equals(cls) ? tutors : digs).add(new Move(s, cls));
                }
            }
        }
        final List<Move> out = new ArrayList<>(tutors);
        out.addAll(digs);
        return out;
    }

    /** The probed class of one ability of a card: tutor, cantrip-dig, or null (neither). */
    static String affordance(ACard ac, Card c, String desc, String costKey) {
        if (ac == null) {
            return null;
        }
        final List<Abil> abils = new ArrayList<>(c.isInZone(ZoneType.Battlefield) ? ac.onBattlefield : ac.fromHand);
        for (Abil a : abils) {
            if (!desc.equals(a.desc) || !costKey.equals(a.costKey) || a.unplayable) {
                continue;
            }
            if (a.has(EffKind.TUTOR) || a.has(EffKind.TUTOR_TOP)) {
                return "tutor";
            }
            if (a.has(EffKind.DRAW) || a.has(EffKind.PILE) || a.has(EffKind.PUTBACK)) {
                return "cantrip-dig";
            }
            return null;
        }
        return null;
    }

    private static final class Applied {
        boolean won;
        Node child;
        String key;
        List<String> pickCandidates = List.of();
    }

    private static Applied apply(Node node, Move mv, Config cfg) {
        final Random prev = MyRandom.getThreadRandom();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(cfg.seed, node.order * 131 + 7)));
        try {
            forge.util.IdScope.install(ForcedWinEngine.cloneScope(node.ids));
            final GameCopier copier = new GameCopier(node.g, true);
            copier.setCopyFidelity(1);
            final Game g = copier.makeCopy();
            final Player me = (Player) copier.find(node.me);
            final ReachAi ai = new ReachAi(g, me, me.getController().getLobbyPlayer(), mv, node.turn, cfg.pieces);
            me.dangerouslySetController(ai);
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new LookaheadSearch.RolloutAi(g, o, o.getController().getLobbyPlayer()));
                }
            }
            final PhaseHandler ph = g.getPhaseHandler();
            LookaheadSearch.givePriority(ph, me);
            int steps = 0;
            final int lastTurn = mv.step.pass ? node.turn + 2 : node.turn;
            while (!g.isGameOver() && !ai.rest && !ai.failed && ph.getTurn() <= lastTurn && steps < cfg.loopSteps) {
                ph.mainLoopStep();
                steps++;
            }
            final Applied a = new Applied();
            a.pickCandidates = ai.picks.candidates;
            a.won = me.hasWon() || me.getOpponents().stream().anyMatch(Player::hasLost);
            if (a.won || !ai.rest || g.isGameOver()) {
                ForcedWinSolver.receipt("STEPS_BACK apply " + mv + " from turn " + node.turn + ": won=" + a.won + " rest=" + ai.rest
                        + " failed=" + ai.failed + " over=" + g.isGameOver() + " turn=" + ph.getTurn() + " steps=" + steps);
                return a;
            }
            final Node child = new Node();
            child.g = g;
            child.me = me;
            child.turn = ph.getTurn();
            child.ids = ForcedWinEngine.cloneScope(forge.util.IdScope.capture());
            a.child = child;
            a.key = PlayoutKeys.stateKey(g);
            return a;
        } catch (RuntimeException | StackOverflowError ex) {
            ForcedWinSolver.receipt("STEPS_BACK apply " + mv + " from turn " + node.turn + ": error " + ex);
            return null;
        } finally {
            MyRandom.setThreadRandom(prev);
        }
    }

    /**
     * Our seat while one move is played: the move (its pick forced when given), then pass until our next main-phase
     * priority with an empty stack (for a pass: the next turn's). It keeps the pieces: put-backs, discards, scry and
     * library orders avoid them; otherwise it answers as Forge AI.
     */
    static final class ReachAi extends LookaheadSearch.RolloutAi {
        final Move move;
        final int fromTurn;
        final Set<String> keep;
        boolean acted, rest, failed;
        final ForcedWinEngine.LibraryPicks picks;

        ReachAi(Game g, Player p, forge.LobbyPlayer lp, Move move, int fromTurn, Set<String> keep) {
            super(g, p, lp);
            this.move = move;
            this.fromTurn = fromTurn;
            this.keep = keep;
            this.picks = new ForcedWinEngine.LibraryPicks(move.pick);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final Game g = getGame();
            if (!acted) {
                acted = true;
                if (move.step.pass) {
                    // a turn passes: this turn's land drop is made first (a land that is not a piece, else any), as a
                    // player does before passing; nothing else is played
                    final SpellAbility land = landDrop(getPlayer(), keep);
                    return land == null ? null : capped(List.of(land));
                }
                final SpellAbility sa = ForcedWinSolver.Follower.locate(g, getPlayer(), move.step);
                if (sa == null) {
                    failed = true;
                    return null;
                }
                return capped(List.of(sa));
            }
            final PhaseHandler ph = g.getPhaseHandler();
            if (g.getStack().isEmpty() && ph.isPlayerTurn(getPlayer()) && (ph.is(PhaseType.MAIN1) || ph.is(PhaseType.MAIN2))
                    && (!move.step.pass || ph.getTurn() > fromTurn && ph.is(PhaseType.MAIN1))) {
                rest = true;
            }
            return null;
        }

        /** A playable land from our hand for this turn's drop: not a piece when we can help it; null = none. */
        static SpellAbility landDrop(Player me, Set<String> keep) {
            SpellAbility piece = null;
            for (Card c : me.getCardsIn(ZoneType.Hand)) {
                if (!c.isLand()) {
                    continue;
                }
                for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                    if (!sa.isLandAbility() || !sa.canPlay()) {
                        continue;
                    }
                    if (!keep.contains(c.getName())) {
                        return sa;
                    }
                    if (piece == null) {
                        piece = sa;
                    }
                }
            }
            return piece;
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                CardCollection fetchList, forge.game.player.DelayedReveal delayedReveal, String selectPrompt,
                boolean isOptional, Player decider) {
            final Card c = picks.pick(getPlayer(), origin, sa, fetchList, decider);
            if (c != null) {
                return c;
            }
            if (decider == getPlayer() && origin != null && origin.contains(ZoneType.Hand) && destination == ZoneType.Library
                    && fetchList != null && !fetchList.isEmpty()) {
                // a put-back from our hand: not a piece when we can help it
                for (Card x : fetchList) {
                    if (!keep.contains(x.getName())) {
                        return x;
                    }
                }
            }
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                    isOptional, decider);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
            if (destinationZone != ZoneType.Library) {
                return super.orderMoveToZoneList(cards, destinationZone, source);
            }
            // Forge moves each card to the top in the returned order (the last ends on top): pieces last = on top
            final CardCollection out = new CardCollection();
            for (Card c : cards) {
                if (!keep.contains(c.getName())) {
                    out.add(c);
                }
            }
            for (Card c : cards) {
                if (keep.contains(c.getName())) {
                    out.add(c);
                }
            }
            return out;
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
            return keepPiecesOnTop(topN);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
            return keepPiecesOnTop(topN);
        }

        private ImmutablePair<CardCollection, CardCollection> keepPiecesOnTop(CardCollection topN) {
            final CardCollection top = new CardCollection(), bottom = new CardCollection();
            boolean any = false;
            for (Card c : topN) {
                any |= keep.contains(c.getName());
            }
            for (Card c : topN) {
                (keep.contains(c.getName()) || !any ? top : bottom).add(c);
            }
            return ImmutablePair.of(top, bottom);
        }

        @Override
        public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
                Card cardToShow, java.util.Map<String, Object> params) {
            if (message != null && message.toLowerCase().contains("shuffle")) {
                return false;
            }
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }

        @Override
        public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min,
                int max, CardCollectionView visibleToChooser) {
            final CardCollection ordinary = super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
            return p == getPlayer() ? avoid(ordinary, validCards) : ordinary;
        }

        @Override
        public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
            final CardCollectionView ordinary = super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
            return avoid(new CardCollection(ordinary), new CardCollection(getPlayer().getCardsIn(ZoneType.Hand)));
        }

        private CardCollection avoid(CardCollection ordinary, CardCollectionView valid) {
            if (ordinary == null) {
                return null;
            }
            final List<Card> spare = new ArrayList<>();
            for (Card v : valid) {
                if (!keep.contains(v.getName()) && !ordinary.contains(v)) {
                    spare.add(v);
                }
            }
            Collections.reverse(spare);
            final CardCollection out = new CardCollection();
            for (Card c : ordinary) {
                out.add(keep.contains(c.getName()) && !spare.isEmpty() ? spare.remove(spare.size() - 1) : c);
            }
            return out;
        }

        /** Tap or untap: the rules' default (untap our own, tap theirs). */
        @Override
        public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kind, Boolean defaultVal) {
            if (kind == BinaryChoiceType.TapOrUntap && defaultVal != null) {
                return defaultVal;
            }
            return super.chooseBinary(sa, question, kind, defaultVal);
        }
    }
}
