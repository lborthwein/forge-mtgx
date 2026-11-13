package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import forge.ai.AiCache;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * choice-scorer-1106 (off unless a search spec's {@code choiceScorer} or a measuring tool asks): apply one answer to a
 * choice on a copy of the position, inside the copy's own scopes (random stream, id scope, AI caches), and hand the
 * position it leads to back as one value frame. The live game is never touched. The same copy-and-replay as
 * {@link LookaheadSearch#prepareTutor}: the resolving ability is on the copy's stack, not yet resolved there.
 */
public final class ChoiceApply {
    private ChoiceApply() {
    }

    /** The frame side: the seat's knowledge on a copy, and the value frame of a position. */
    public interface Frames {
        Object onCopy(Game copy, Player me);

        byte[] capture(Object ctx, Game g, Player me);

        /** The seat arranged these cards going to its library (what the live bridge reports as a look). */
        default void onOrder(Object ctx, Game g, Player me, List<Card> cards) {
        }

        /** At the leaf too: the planner screen of this position, or null (off). */
        default ForcedWinSolver.Screen screen(Object ctx, Game g, Player me) {
            return null;
        }

        /**
         * sdcc-leak-1111: the ids of the copy's cards the seat does not know, among its own library and the other
         * players' hands, before the answer is replayed; null = the hidden-read count is off (as before).
         */
        default java.util.Set<Integer> hidden(Object ctx, Game g, Player me) {
            return null;
        }
    }

    /** One applied answer. */
    public static final class Result {
        public byte[] payload;
        /** The copy's game ended: 1 won, 0 lost, 0.5 drawn; NaN otherwise. */
        public double terminal = Double.NaN;
        /** Why no position came of it, or null. */
        public String fail;
        public long copyNanos, runNanos;
        public int steps;
        /** Further picks of the same resolution that Forge AI answered (0 = this answer settled the whole choice). */
        public int tailAsks;
        public ForcedWinSolver.Screen screen;
        /**
         * sdcc-leak-1111 ({@link Frames#hidden} only): cards the seat did not know that the replay took out of our
         * library, other than the forced picks (a draw, a mill, a dig), and that left another player's hand, before the
         * frame. A frame after such a move shows a card the seat could not have known when it answered.
         */
        public int hiddenOwn, hiddenOpp;
    }

    /** sdcc-leak-1111: the copy's listener behind {@link Result#hiddenOwn} and {@link Result#hiddenOpp}. */
    public static final class HiddenReads {
        private final Result r;
        private final int me;
        private final java.util.Set<Integer> hidden, forced;
        private final boolean[] reached;

        HiddenReads(final Result r, final int me, final java.util.Set<Integer> hidden, final java.util.Set<Integer> forced,
                final boolean[] reached) {
            this.r = r;
            this.me = me;
            this.hidden = hidden;
            this.forced = forced;
            this.reached = reached;
        }

        @com.google.common.eventbus.Subscribe
        public void on(final forge.game.event.GameEventCardChangeZone e) {
            if (reached[0] || e.card() == null || e.from() == null || e.from().player() == null) {
                return;
            }
            final int id = e.card().getId();
            if (!hidden.contains(id) || forced.contains(id)) {
                return;
            }
            final boolean mine = e.from().player().getId() == me;
            if (mine && e.from().zoneType() == ZoneType.Library) {
                if (!(e.to() != null && e.to().zoneType() == ZoneType.Library && e.to().player() != null
                        && e.to().player().getId() == me)) {
                    r.hiddenOwn++;
                }
            } else if (!mine && e.from().zoneType() == ZoneType.Hand) {
                r.hiddenOpp++;
            }
        }
    }

    /** Why a resolving choice can't be replayed on a copy, or null. */
    public static String unsupported(final Game live, final SpellAbility sa) {
        final String why = GameCopier.stackUnsupported(live, true);
        if (why != null) {
            return why;
        }
        final SpellAbility top = live.getStack().isEmpty() ? null : live.getStack().peekAbility();
        if (!live.getStack().isResolving() || top == null) {
            return "notResolving";
        }
        if (sa != null && sa.getRootAbility() != sa) {
            return "subAbility"; // effects before the choice in the same ability would be replayed with it
        }
        if (sa != null && top != sa) {
            if (top instanceof forge.game.trigger.WrappedAbility wa && wa.getWrappedAbility() == sa) {
                final forge.game.trigger.Trigger t = wa.getTrigger();
                if (t == null || t.isStatic() || LookaheadSearch.triggerIndex(wa.getHostCard(), t) < 0) {
                    return "triggerForm";
                }
                return null;
            }
            return "notTop";
        }
        return null;
    }

    /** The seat on a copy: forced card picks, then Forge AI; its first priority (either seat's) is the leaf. */
    static final class Forced extends LookaheadSearch.RolloutAi {
        final List<Integer> picks;
        int next = 0;
        /** Top first: the answer to the library order ask of this resolution, or null. */
        List<Integer> order;
        java.util.function.Consumer<List<Card>> looked;
        final Runnable leaf;
        boolean missed = false;
        int tail = 0;
        /**
         * integrate-1110: false = this seat answers its own discards as before choice-search-1109 (Forge AI, not forced
         * from the picks, not counted as a tail ask). Set false only for the solver-distill-cc-1109 arms' CARDS asks,
         * whose copies must stay what that lane read ({@code ChoiceScorer.ownDiscards}).
         */
        boolean ownDiscards = true;
        Forced(final Game g, final Player p, final forge.LobbyPlayer lp, final List<Integer> picks, final Runnable leaf) {
            super(g, p, lp);
            this.picks = picks;
            this.leaf = leaf;
        }

        @Override
        public Card chooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
                final SpellAbility sa, final CardCollection fetchList, final forge.game.player.DelayedReveal delayedReveal,
                final String selectPrompt, final boolean isOptional, final Player decider) {
            if (next < picks.size()) {
                final int id = picks.get(next++);
                if (id < 0 && isOptional) {
                    return null;
                }
                for (Card c : fetchList) {
                    if (c.getId() == id) {
                        return c;
                    }
                }
                missed = true;
            } else {
                tail++;
            }
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                    isOptional, decider);
        }

        @Override
        public CardCollectionView chooseCardsForEffect(final CardCollectionView sourceList, final SpellAbility sa,
                final String title, final int min, final int max, final boolean isOptional,
                final Map<String, Object> params) {
            if (next < picks.size()) {
                final CardCollection out = new CardCollection();
                while (next < picks.size() && out.size() < max) {
                    final int id = picks.get(next++);
                    if (id < 0) {
                        break;
                    }
                    Card hit = null;
                    for (Card c : sourceList) {
                        if (c.getId() == id) {
                            hit = c;
                            break;
                        }
                    }
                    if (hit == null) {
                        missed = true;
                    } else {
                        out.add(hit);
                    }
                }
                if (!missed && out.size() >= min) {
                    return out;
                }
            } else {
                tail++;
            }
            return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        }

        /** choice-search-1109: the forced cards out of {@code from} (min..max of them), or null (missed: Forge AI answers). */
        private CardCollection forcedCards(final CardCollectionView from, final int min, final int max) {
            final CardCollection out = new CardCollection();
            while (next < picks.size() && out.size() < max) {
                final int id = picks.get(next++);
                if (id < 0) {
                    break;
                }
                Card hit = null;
                for (Card c : from) {
                    if (c.getId() == id) {
                        hit = c;
                        break;
                    }
                }
                if (hit == null) {
                    missed = true;
                    return null;
                }
                out.add(hit);
            }
            if (out.size() < min) {
                missed = true;
                return null;
            }
            return out;
        }

        /**
         * choice-search-1109: this seat's own discard choice in the resolving ability (the bridge's discardFrom ask): the
         * forced cards in one answer; past the list Forge AI answers. Another player's discard is Forge AI's.
         */
        @Override
        public CardCollection chooseCardsToDiscardFrom(final Player p, final SpellAbility sa, final CardCollection validCards,
                final int min, final int max, final CardCollectionView visibleToChooser) {
            if (!ownDiscards) {
                return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser); // integrate-1110
            }
            if (p == getPlayer() && next < picks.size()) {
                final CardCollection out = forcedCards(validCards, min, max);
                if (out != null) {
                    return out;
                }
            } else if (p == getPlayer()) {
                tail++;
            }
            return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
        }

        /** choice-search-1109: the cleanup discard to hand size, forced like the others. */
        @Override
        public CardCollectionView chooseCardsToDiscardToMaximumHandSize(final int numDiscard) {
            if (!ownDiscards) {
                return super.chooseCardsToDiscardToMaximumHandSize(numDiscard); // integrate-1110
            }
            if (next < picks.size()) {
                final CardCollection out = forcedCards(getPlayer().getCardsIn(ZoneType.Hand), numDiscard, numDiscard);
                if (out != null) {
                    return out;
                }
            } else {
                tail++;
            }
            return super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType destinationZone,
                final SpellAbility source) {
            if (order != null && destinationZone == ZoneType.Library) {
                if (looked != null) {
                    looked.accept(new ArrayList<>(new CardCollection(cards)));
                }
                final CardCollection top = new CardCollection();
                for (int id : order) {
                    for (Card c : cards) {
                        if (c.getId() == id) {
                            top.add(c);
                        }
                    }
                }
                final CardCollection ordered = new CardCollection();
                for (Card c : cards) {
                    if (!top.contains(c)) {
                        ordered.add(c);
                    }
                }
                // Forge moves each card to the top in the returned order: the last one ends on top
                for (int i = top.size() - 1; i >= 0; i--) {
                    ordered.add(top.get(i));
                }
                return ordered;
            }
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            leaf.run();
            return null;
        }
    }

    /** The other seat on a copy: Forge AI's answers; its priority is the leaf too. */
    static final class Other extends LookaheadSearch.RolloutAi {
        final Runnable leaf;

        Other(final Game g, final Player p, final forge.LobbyPlayer lp, final Runnable leaf) {
            super(g, p, lp);
            this.leaf = leaf;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            leaf.run();
            return null;
        }
    }

    private static GameCopier copier(final Game live, final int fidelity, final boolean stack) {
        final GameCopier c = new GameCopier(live, true);
        if (fidelity > 0) {
            c.setCopyFidelity(fidelity);
        }
        c.setCopyPending(GameCopier.SOLVER_COPY_PENDING);
        if (stack) {
            c.setCopyStack(true);
            c.setSkipResolvingTrigger(true);
        }
        return c;
    }

    /**
     * The resolving ability, replayed on a copy with {@code picks} forced (card ids in order; -1 = decline an optional
     * pick; past the list Forge AI answers), settled to the next priority of either seat, whose frame is the result.
     */
    public static Result applyPicks(final Game live, final Player liveMe, final List<Integer> picks, final long seed,
            final int settleSteps, final int fidelity, final Frames frames) {
        return applyPicks(live, liveMe, picks, null, seed, settleSteps, fidelity, frames);
    }

    /**
     * The planner's piles for the spell now resolving: on a copy the spell goes back to hand with the mana it cost
     * floating, so the planner sees it as castable now and searches what to pile for it (top first, shortest win first).
     */
    public static List<List<Integer>> pileChoices(final Game live, final Player liveMe, final ForcedWinSolver.Config cfg,
            final int nodes, final long ms, final int top, final long seed) {
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 7)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final SpellAbility liveTop = live.getStack().peekAbility();
            if (!liveTop.isSpell() || liveTop.getActivatingPlayer() != liveMe) {
                return new ArrayList<>();
            }
            final int hostId = liveTop.getHostCard().getId();
            final Game g;
            final Player me;
            synchronized (live) {
                final GameCopier copier = copier(live, 1, true);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
            }
            final forge.game.spellability.SpellAbilityStackInstance si = g.getStack().peek();
            final Card host = si.getSourceCard();
            g.getStack().remove(si);
            g.getAction().moveToHand(host, null);
            final forge.game.cost.CostPartMana cm = liveTop.getPayCosts() == null ? null : liveTop.getPayCosts().getCostMana();
            if (cm != null && cm.getMana() != null) {
                for (forge.card.mana.ManaCostShard sh : cm.getMana()) {
                    final byte col = sh.getColorMask();
                    final byte one = col == 0 ? (byte) forge.card.MagicColor.COLORLESS : (byte) Integer.lowestOneBit(col);
                    me.getManaPool().addMana(new forge.game.mana.Mana(one, host, null, me));
                }
                for (int i = 0; i < cm.getMana().getGenericCost(); i++) {
                    me.getManaPool().addMana(new forge.game.mana.Mana((byte) forge.card.MagicColor.COLORLESS, host, null, me));
                }
            }
            return new ForcedWinSolver(me, cfg).pileChoices(g, hostId, nodes, ms, top);
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("[choice-apply] pile " + e);
            return new ArrayList<>();
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    public static Result applyPicks(final Game live, final Player liveMe, final List<Integer> picks,
            final List<Integer> order, final long seed, final int settleSteps, final int fidelity, final Frames frames) {
        return applyPicks(live, liveMe, picks, order, seed, settleSteps, fidelity, frames, 0);
    }

    /**
     * choice-search-1109: the index of {@code sa} in the resolving entry's chain (0 = its root, as a trigger's wrapped
     * ability), or -1 when it is not in the chain (or nothing resolves).
     */
    public static int chainOf(final Game live, final SpellAbility sa) {
        if (sa == null || live.getStack().isEmpty() || !live.getStack().isResolving()) {
            return -1;
        }
        SpellAbility root = live.getStack().peekAbility();
        if (root instanceof forge.game.trigger.WrappedAbility wa) {
            root = wa.getWrappedAbility();
        }
        int i = 0;
        for (SpellAbility s = root; s != null; s = s.getSubAbility(), i++) {
            if (s == sa) {
                return i;
            }
        }
        return -1;
    }

    /**
     * As above; choice-search-1109: {@code chain} > 0 = the ask came from that sub-ability of the resolving entry, whose
     * earlier effects already happened in the copy (Bazaar's draw before its discard): the copy resolves from that
     * sub-ability on ({@link forge.game.zone.MagicStack#resolveStackFrom}), so nothing before the choice happens twice.
     */
    public static Result applyPicks(final Game live, final Player liveMe, final List<Integer> picks,
            final List<Integer> order, final long seed, final int settleSteps, final int fidelity, final Frames frames,
            final int chain) {
        return applyPicks(live, liveMe, picks, order, seed, settleSteps, fidelity, frames, chain, true);
    }

    /**
     * As above; integrate-1110: {@code ownDiscards} false = the copy's seat answers its own discards as before
     * choice-search-1109 ({@link Forced#ownDiscards}); true is the call above.
     */
    public static Result applyPicks(final Game live, final Player liveMe, final List<Integer> picks,
            final List<Integer> order, final long seed, final int settleSteps, final int fidelity, final Frames frames,
            final int chain, final boolean ownDiscards) {
        final Result r = new Result();
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final long t0 = System.nanoTime();
            final SpellAbility liveTop = live.getStack().peekAbility();
            final forge.game.trigger.WrappedAbility liveWrap = liveTop instanceof forge.game.trigger.WrappedAbility w ? w : null;
            final Game g;
            final Player me;
            SpellAbility trig = null;
            final Object ctx;
            synchronized (live) {
                final GameCopier copier = copier(live, fidelity, true);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                if (liveWrap != null) {
                    trig = LookaheadSearch.rebuildTrigger(g, me, liveWrap);
                }
                ctx = frames.onCopy(g, me);
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 3)));
            r.copyNanos = System.nanoTime() - t0;
            final boolean[] reached = {false};
            final java.util.Set<Integer> hidden = frames.hidden(ctx, g, me);
            if (hidden != null && !hidden.isEmpty()) {
                final java.util.Set<Integer> forced = new java.util.HashSet<>(picks);
                if (order != null) {
                    forced.addAll(order);
                }
                g.subscribeToEvents(new HiddenReads(r, me.getId(), hidden, forced, reached));
            }
            final Runnable leaf = () -> {
                if (!reached[0]) {
                    reached[0] = true;
                    r.payload = frames.capture(ctx, g, me);
                    r.screen = frames.screen(ctx, g, me);
                }
            };
            final Forced sc = new Forced(g, me, me.getController().getLobbyPlayer(), picks, leaf);
            sc.ownDiscards = ownDiscards;
            sc.order = order;
            sc.looked = cs -> frames.onOrder(ctx, g, me, cs);
            me.dangerouslySetController(sc);
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new Other(g, o, o.getController().getLobbyPlayer(), leaf));
                }
            }
            final long t1 = System.nanoTime();
            final PhaseHandler ph = g.getPhaseHandler();
            if (chain > 0 && trig != null) {
                r.fail = "triggerSub";
                return r;
            }
            if (trig != null) {
                me.getController().playSpellAbilityNoStack(trig, false);
                g.getAction().checkStaticAbilities();
                ph.resetPriority();
                ph.onStackResolved();
            } else if (chain > 0) {
                SpellAbility sub = g.getStack().peekAbility();
                for (int i = 0; i < chain && sub != null; i++) {
                    sub = sub.getSubAbility();
                }
                if (sub == null) {
                    r.fail = "chain";
                    return r;
                }
                g.getStack().resolveStackFrom(sub);
            } else {
                g.getStack().resolveStack();
            }
            if (sc.missed) {
                r.fail = "pickMissed";
                return r;
            }
            int steps = 0;
            while (!g.isGameOver() && !reached[0] && steps < settleSteps) {
                ph.mainLoopStep();
                steps++;
            }
            r.steps = steps;
            r.tailAsks = sc.tail;
            r.runNanos = System.nanoTime() - t1;
            if (g.isGameOver() || !me.isInGame()) {
                r.terminal = me.hasWon() ? 1.0 : me.hasLost() || !me.isInGame() ? 0.0 : 0.5;
                r.payload = null;
            } else if (!reached[0]) {
                r.fail = "settle";
            }
            return r;
        } catch (RuntimeException | StackOverflowError e) {
            r.fail = "error:" + e;
            return r;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /**
     * choice-search-1109 (COST_CARDS): the cast whose card cost is asked: {@code liveSa}'s root is a spell of
     * {@code liveMe}, its card on the stack zone (moved there for the cast, not yet a stack entry), cast from the hand.
     * Null when {@link #applyCost} can replay it, else why not.
     */
    public static String castUnsupported(final Game live, final Player liveMe, final SpellAbility liveSa) {
        if (liveSa == null) {
            return "noAsk";
        }
        final SpellAbility root = liveSa.getRootAbility();
        final Card host = root.getHostCard();
        if (!root.isSpell() || host == null || root.getActivatingPlayer() != liveMe) {
            return "notSpell";
        }
        if (host.getZone() == null || !host.getZone().is(ZoneType.Stack)) {
            return "notCasting";
        }
        if (host.getCastFrom() == null || !host.getCastFrom().is(ZoneType.Hand)) {
            return "castFrom";
        }
        if (saIndexOf(host, root) < 0) {
            return "saIndex";
        }
        // the stack is frozen for the cast in progress (that is expected); a held simultaneous entry is not replayed
        return live.getStack().hasSimultaneousStackEntries() ? "simultaneous" : null;
    }

    /**
     * choice-search-1109: the index of a cast's ability among its card's spell abilities: the same object, else the one it
     * was copied from (Forge copies the ability to add keyword costs), else the first with the same description; -1.
     */
    static int saIndexOf(final Card host, final SpellAbility root) {
        final List<SpellAbility> sas = new ArrayList<>(host.getSpellAbilities());
        int i = sas.indexOf(root);
        if (i < 0 && root.getOriginalAbility() != null) {
            i = sas.indexOf(root.getOriginalAbility());
        }
        for (int k = 0; i < 0 && k < sas.size(); k++) {
            if (sas.get(k).isSpell() && String.valueOf(sas.get(k).getDescription()).equals(String.valueOf(root.getDescription()))) {
                i = k;
            }
        }
        return i;
    }

    /**
     * choice-search-1109 (COST_CARDS): the card cost of a spell being cast, paid on a copy. Forge decides every cost of a
     * cast before paying any, so the copy (taken at the ask) holds the spell's card on the stack zone with nothing paid.
     * On the copy the spell's own mana cost is paid by Forge AI, the asked card part with {@code picks} ({@code part}: the
     * live cost part's class, CostDiscard or CostExile), and the card goes to the graveyard UNRESOLVED: its effects are
     * not part of a line check, and resolving it could read the opponent's real hidden hand. Then the copy settles to the
     * next priority (the leaf), as {@link #applyPicks}. A cost with any other non-mana part is not replayed.
     */
    public static Result applyCost(final Game live, final Player liveMe, final SpellAbility liveSa, final String part,
            final List<Integer> picks, final long seed, final int settleSteps, final int fidelity, final Frames frames) {
        final Result r = new Result();
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final long t0 = System.nanoTime();
            String why = castUnsupported(live, liveMe, liveSa);
            if (why == null && !"CostDiscard".equals(part) && !"CostExile".equals(part)) {
                why = "costPart:" + part;
            }
            if (why == null && liveSa.getRootAbility().getPayCosts() != null) {
                for (forge.game.cost.CostPart cp : liveSa.getRootAbility().getPayCosts().getCostParts()) {
                    if (!(cp instanceof forge.game.cost.CostPartMana) && !cp.getClass().getSimpleName().equals(part)) {
                        why = "costParts:" + cp.getClass().getSimpleName();
                    }
                }
            }
            if (why != null) {
                r.fail = why;
                return r;
            }
            final SpellAbility liveRoot = liveSa.getRootAbility();
            final int saIndex = saIndexOf(liveRoot.getHostCard(), liveRoot);
            final Game g;
            final Player me;
            final Object ctx;
            synchronized (live) {
                final GameCopier copier = copier(live, fidelity, false);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                ctx = frames.onCopy(g, me);
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 3)));
            r.copyNanos = System.nanoTime() - t0;
            final Card host = g.findById(liveRoot.getHostCard().getId());
            if (host == null || host.getZone() == null || !host.getZone().is(ZoneType.Stack)
                    || saIndex >= host.getSpellAbilities().size()) {
                r.fail = "copyHost";
                return r;
            }
            final boolean[] reached = {false};
            final Runnable leaf = () -> {
                if (!reached[0]) {
                    reached[0] = true;
                    r.payload = frames.capture(ctx, g, me);
                    r.screen = frames.screen(ctx, g, me);
                }
            };
            final Forced sc = new Forced(g, me, me.getController().getLobbyPlayer(), picks, leaf);
            me.dangerouslySetController(sc);
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new Other(g, o, o.getController().getLobbyPlayer(), leaf));
                }
            }
            final long t1 = System.nanoTime();
            final SpellAbility csa = host.getSpellAbilities().get(saIndex);
            csa.setActivatingPlayer(me);
            final forge.game.cost.Cost liveCost = liveRoot.getPayCosts();
            if (liveCost != null && liveCost.hasManaCost()
                    && !forge.ai.ComputerUtilMana.payManaCost(liveCost, me, csa, false)) {
                r.fail = "mana";
                return r;
            }
            final CardCollection hand = new CardCollection(me.getCardsIn(ZoneType.Hand));
            final List<Card> paid = new ArrayList<>();
            for (int id : picks) {
                Card c = null;
                for (Card h : hand) {
                    if (h.getId() == id) {
                        c = h;
                    }
                }
                if (c == null) {
                    r.fail = "pickMissed";
                    return r;
                }
                paid.add(c);
            }
            for (Card c : paid) {
                if ("CostDiscard".equals(part)) {
                    me.discard(c, csa, false, forge.game.ability.AbilityKey.newMap());
                } else {
                    g.getAction().exile(c, csa, forge.game.ability.AbilityKey.newMap());
                }
            }
            g.getAction().moveToGraveyard(host, null);
            if (g.getStack().isFrozen()) {
                g.getStack().unfreezeStack(); // the cast is over on the copy (waiting triggers go on its stack)
            }
            g.getAction().checkStateEffects(true);
            final PhaseHandler ph = g.getPhaseHandler();
            int steps = 0;
            while (!g.isGameOver() && !reached[0] && steps < settleSteps) {
                ph.mainLoopStep();
                steps++;
            }
            r.steps = steps;
            r.tailAsks = sc.tail;
            r.runNanos = System.nanoTime() - t1;
            if (g.isGameOver() || !me.isInGame()) {
                r.terminal = me.hasWon() ? 1.0 : me.hasLost() || !me.isInGame() ? 0.0 : 0.5;
                r.payload = null;
            } else if (!reached[0]) {
                r.fail = "settle";
            }
            return r;
        } catch (RuntimeException | StackOverflowError e) {
            r.fail = "error:" + e;
            return r;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /**
     * choice-search-1109: the live game is inside {@code liveMe}'s own cleanup step (CR 514.1, discard to hand size), with
     * nothing on the stack: the ask {@link #applyCleanup} replays.
     */
    public static boolean cleanupAsk(final Game live, final Player liveMe) {
        final PhaseHandler ph = live.getPhaseHandler();
        return ph.is(forge.game.phase.PhaseType.CLEANUP) && ph.isPlayerTurn(liveMe) && live.getStack().isEmpty();
    }

    /**
     * choice-search-1109: the cleanup's discard to hand size, replayed on a copy with {@code picks} forced (the copy was
     * taken inside the live step's turn-based actions, so it replays them: {@link PhaseHandler#devReplayPhaseBegin}), then
     * settled to the next priority of either seat (the leaf), as {@link #applyPicks}.
     */
    public static Result applyCleanup(final Game live, final Player liveMe, final List<Integer> picks, final long seed,
            final int settleSteps, final int fidelity, final Frames frames) {
        final Result r = new Result();
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final long t0 = System.nanoTime();
            if (!cleanupAsk(live, liveMe)) {
                r.fail = "notCleanup";
                return r;
            }
            final Game g;
            final Player me;
            final Object ctx;
            synchronized (live) {
                final GameCopier copier = copier(live, fidelity, false);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                ctx = frames.onCopy(g, me);
            }
            MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 3)));
            r.copyNanos = System.nanoTime() - t0;
            final boolean[] reached = {false};
            final Runnable leaf = () -> {
                if (!reached[0]) {
                    reached[0] = true;
                    r.payload = frames.capture(ctx, g, me);
                    r.screen = frames.screen(ctx, g, me);
                }
            };
            final Forced sc = new Forced(g, me, me.getController().getLobbyPlayer(), picks, leaf);
            me.dangerouslySetController(sc);
            for (Player o : g.getPlayers()) {
                if (o != me) {
                    o.dangerouslySetController(new Other(g, o, o.getController().getLobbyPlayer(), leaf));
                }
            }
            final long t1 = System.nanoTime();
            final PhaseHandler ph = g.getPhaseHandler();
            if (!ph.is(forge.game.phase.PhaseType.CLEANUP)) {
                r.fail = "copyPhase";
                return r;
            }
            ph.devReplayPhaseBegin();
            if (sc.missed || sc.next < picks.size()) {
                r.fail = "pickMissed";
                return r;
            }
            int steps = 0;
            while (!g.isGameOver() && !reached[0] && steps < settleSteps) {
                ph.mainLoopStep();
                steps++;
            }
            r.steps = steps;
            r.tailAsks = sc.tail;
            r.runNanos = System.nanoTime() - t1;
            if (g.isGameOver() || !me.isInGame()) {
                r.terminal = me.hasWon() ? 1.0 : me.hasLost() || !me.isInGame() ? 0.0 : 0.5;
                r.payload = null;
            } else if (!reached[0]) {
                r.fail = "settle";
            }
            return r;
        } catch (RuntimeException | StackOverflowError e) {
            r.fail = "error:" + e;
            return r;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }

    /**
     * Opening-hand asks, before the game loop. A copy of the position, optionally the London mulligan's new hand
     * ({@code redraw}: the hand shuffled back, the library shuffled by this seed, seven drawn), then {@code bottoms}:
     * the hand cards at these indices in the hand's order go to the library's bottom. One frame of the seat.
     */
    public static Result applyHand(final Game live, final Player liveMe, final boolean redraw, final List<Integer> bottoms,
            final long seed, final int fidelity, final Frames frames) {
        final Result r = new Result();
        final Random prev = MyRandom.getThreadRandom();
        final Object prevIds = forge.util.IdScope.capture();
        final Object prevCache = AiCache.captureScope();
        MyRandom.setThreadRandom(new PlayoutKeys.TrackedRandom(LookaheadSearch.mix(seed, 1)));
        AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final long t0 = System.nanoTime();
            final Game g;
            final Player me;
            final Object ctx;
            synchronized (live) {
                final GameCopier copier = copier(live, fidelity, false);
                g = copier.makeCopy();
                me = (Player) copier.find(liveMe);
                ctx = frames.onCopy(g, me);
            }
            r.copyNanos = System.nanoTime() - t0;
            final long t1 = System.nanoTime();
            if (redraw) {
                for (Card c : new CardCollection(me.getCardsIn(ZoneType.Hand))) {
                    g.getAction().moveToLibrary(c, null);
                }
                me.shuffle(null);
                me.drawCards(7);
            }
            if (bottoms != null && !bottoms.isEmpty()) {
                final List<Card> hand = new CardCollection(me.getCardsIn(ZoneType.Hand));
                final List<Card> go = new ArrayList<>();
                for (int i : bottoms) {
                    go.add(hand.get(i));
                }
                for (Card c : go) {
                    g.getAction().moveToLibrary(c, -1, null);
                }
            }
            r.payload = frames.capture(ctx, g, me);
            r.runNanos = System.nanoTime() - t1;
            return r;
        } catch (RuntimeException | StackOverflowError e) {
            r.fail = "error:" + e;
            return r;
        } finally {
            forge.util.IdScope.install(prevIds);
            AiCache.installScope(prevCache);
            MyRandom.setThreadRandom(prev);
        }
    }
}
