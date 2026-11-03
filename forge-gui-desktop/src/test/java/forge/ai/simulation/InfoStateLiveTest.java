package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * order-memory-1102 live port (PR-B): {@link InfoStates} and its look-ahead consumer as the live seat runs them. The
 * research InfoStateTest cases the live seat needs (forge-mtgx #171 at 7b089076252: the engine channel, Brainstorm
 * put-backs, a scry bottom, surveil, Endurance's set-known run, a tutor top, a shuffle, Courser for both seats, Citadel
 * owner-only, their Courser top pinned in our worlds, the copy-order rule, LeakCheck on the look-ahead worlds) with the
 * research-only helpers inlined, plus the live consumer: {@link LookaheadSearch#redraw} keeps a known card at its index
 * in 16 worlds with {@code infoState} on and not with it off, on the plain re-draw and on the live honest belief.
 */
public class InfoStateLiveTest extends forge.ai.AITest {

    // ------------------------------------------------------------------ helpers (KnownLibTest / ChooseACardTest's, inlined)

    static Player me(final Game g) {
        return g.getPlayers().get(0);
    }

    static Player opp(final Game g) {
        return g.getPlayers().get(1);
    }

    static Card find(final Player p, final ZoneType z, final String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no " + name + " in " + z);
    }

    static InfoStates track(final Game g) {
        final InfoStates s = InfoStates.attach(g);
        Assert.assertSame(InfoStates.attach(g), s, "one per game");
        return s;
    }

    /** The names {@code o} knows in {@code w}'s library, top first (consistency checked). */
    static List<String> known(final InfoStates s, final Player o, final Player w) {
        final Set<Integer> k = s.known(o, w);
        final List<String> out = new ArrayList<>();
        for (Card c : w.getCardsIn(ZoneType.Library)) {
            if (k.contains(c.getId())) {
                out.add(c.getName());
            }
        }
        s.assertConsistent();
        return out;
    }

    static List<String> top(final Player w, final int n) {
        final List<String> out = new ArrayList<>();
        for (Card c : w.getCardsIn(ZoneType.Library)) {
            if (out.size() < n) {
                out.add(c.getName());
            }
        }
        return out;
    }

    static List<String> bottom(final Player w, final int n) {
        final List<Card> l = new ArrayList<>(w.getCardsIn(ZoneType.Library));
        final List<String> out = new ArrayList<>();
        for (Card c : l.subList(Math.max(0, l.size() - n), l.size())) {
            out.add(c.getName());
        }
        return out;
    }

    static void nextTurn(final Game g, final InfoStates s) {
        me(g).drawCards(1);
        s.assertConsistent();
    }

    static void turnAfter(final Game g, final InfoStates s) {
        opp(g).drawCards(1);
        s.assertConsistent();
        me(g).drawCards(1);
        s.assertConsistent();
    }

    static Game game(final String hand, final String bf, final String lib) {
        return game2(hand, bf, lib, "", "");
    }

    static Game game2(final String ourHand, final String ourBf, final String ourLib, final String theirHand,
            final String theirBf) {
        final String frame = ForcedWinSolverTest.frame(ourHand, ourBf, 20, theirHand, theirBf, 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + ourLib);
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        g.AI_TIMEOUT = 600;
        return g;
    }

    static final String LIB8 = "Island;Swamp;Mountain;Forest;Plains;Island;Swamp;Mountain";

    /** Plays {@code sa} for {@code who} and resolves the stack; {@code beforeResolve} sees each top item first. */
    static void playAndResolve(final Game g, final Player who, final SpellAbility sa,
            final java.util.function.Consumer<SpellAbility> beforeResolve) {
        sa.setActivatingPlayer(who);
        Assert.assertTrue(((PlayerControllerAi) who.getController()).playChosenSpellAbility(sa), "play " + sa);
        MyRandom.setRandom(new Random(42));
        int guard = 0;
        g.getAction().checkStateEffects(true);
        g.getStack().addAllTriggeredAbilitiesToStack();
        while (!g.getStack().isEmpty() && guard++ < 12) {
            if (beforeResolve != null) {
                beforeResolve.accept(g.getStack().peekAbility());
            }
            g.getStack().resolveStack();
            g.getAction().checkStateEffects(true);
            g.getStack().addAllTriggeredAbilitiesToStack();
        }
    }

    static void castAndResolve(final Game g, final String name) {
        final Player a = me(g);
        final SpellAbility sa = find(a, ZoneType.Hand, name).getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        Assert.assertTrue(((PlayerControllerAi) a.getController()).playChosenSpellAbility(sa), "cast " + name);
        MyRandom.setRandom(new Random(42));
        int guard = 0;
        while (!g.getStack().isEmpty() && guard++ < 10) {
            g.getStack().resolveStack();
            g.getAction().checkStateEffects(true);
        }
    }

    /** Our seat's controller: orders library moves by {@code topFirst} (names, top first; null = Forge AI's). */
    static final class Ordered extends PlayerControllerAi {
        final List<String> topFirst;

        Ordered(final Game g, final Player p, final List<String> topFirst) {
            super(g, p, p.getController().getLobbyPlayer());
            this.topFirst = topFirst;
        }

        @Override
        public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType dest, final SpellAbility sa) {
            if (dest != ZoneType.Library || topFirst == null) {
                return super.orderMoveToZoneList(cards, dest, sa);
            }
            final List<Card> l = new ArrayList<>();
            for (Card c : cards) {
                l.add(c);
            }
            l.sort((a, b) -> Integer.compare(rank(a), rank(b)));
            Collections.reverse(l); // RearrangeTopOfLibrary moves each to the top in list order: the last ends on top
            return new CardCollection(l);
        }

        private int rank(final Card c) {
            final int i = topFirst.indexOf(c.getName());
            return i < 0 ? topFirst.size() : i;
        }

        @Override
        public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String message,
                final List<String> options, final Card cardToShow, final Map<String, Object> params) {
            if (message != null && message.toLowerCase().contains("shuffle")) {
                return false;
            }
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }
    }

    /** Our controller with a fixed scry (everything to the bottom, in this order) and surveil (keep everything). */
    static final class Arrange extends PlayerControllerAi {
        final List<String> bottomFirst;

        Arrange(final Game g, final Player p, final List<String> bottomFirst) {
            super(g, p, p.getController().getLobbyPlayer());
            this.bottomFirst = bottomFirst;
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForScry(final CardCollection topN) {
            final List<Card> l = new ArrayList<>(topN);
            l.sort((a, b) -> Integer.compare(bottomFirst.indexOf(a.getName()), bottomFirst.indexOf(b.getName())));
            return ImmutablePair.of(new CardCollection(), new CardCollection(l));
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(final CardCollection topN) {
            return ImmutablePair.of(new CardCollection(topN), new CardCollection());
        }
    }

    static SpellAbility ability(final Card c, final forge.game.ability.ApiType api) {
        return c.getSpellAbilities().stream().filter(x -> x.getApi() == api).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ the engine channel

    /** No listener: nothing is built (the default live path); with one, reveals carry their observer set. */
    @Test
    public void theChannelIsANoOpWithoutAListenerAndReportsObserverSets() {
        final Game g = game("", "Island", LIB8);
        Assert.assertFalse(g.hasLibraryInfoListeners());
        Assert.assertNull(InfoStates.find(g));
        final List<String> seen = new ArrayList<>();
        g.addLibraryInfoListener((kind, observers, cards, zone) -> seen.add(kind + ":" + observers.size() + ":" + cards.size()));
        final Card top = me(g).getCardsIn(ZoneType.Library).get(0);
        g.getAction().reveal(new CardCollection(top), me(g), true);
        g.getAction().revealTo(new CardCollection(top), opp(g));
        Assert.assertEquals(seen, List.of("REVEAL:2:1", "REVEAL:1:1"), "reveal = every player incl. the owner; revealTo = the set");
        Assert.assertNull(InfoStates.find(g), "a foreign listener is not an InfoStates");
    }

    // ------------------------------------------------------------------ the core cases

    /** Brainstorm: our two put-backs are known to us (not to them); drawn one per turn. */
    @Test
    public void brainstormPutBacks() {
        final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new Ordered(g, me(g), null));
        final InfoStates s = track(g);
        playAndResolve(g, me(g), find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        final List<String> k = known(s, me(g), me(g));
        Assert.assertEquals(k.size(), 2, k + " lib " + top(me(g), 3));
        Assert.assertEquals(k, top(me(g), 2));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty(), "they did not see our hand");
        nextTurn(g, s);
        Assert.assertEquals(known(s, me(g), me(g)), List.of(k.get(1)));
        turnAfter(g, s);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
    }

    /** Scry 2 to the bottom (Bolt above Shock): the bottom run in that order, kept through the draws; not known to them. */
    @Test
    public void scryBottomInOrder() {
        final Game g = game("", "Island", "Lightning Bolt;Shock;Island;Swamp;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new Arrange(g, me(g), List.of("Lightning Bolt", "Shock")));
        final InfoStates s = track(g);
        g.getAction().scry(List.of(me(g)), 2, null);
        Assert.assertEquals(bottom(me(g), 2), List.of("Lightning Bolt", "Shock"));
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Lightning Bolt", "Shock"));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        nextTurn(g, s);
        turnAfter(g, s);
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Lightning Bolt", "Shock"), "bottoms survive draws from the top");
        final Map<Integer, Integer> idx = s.knownIndex(me(g), me(g));
        final int n = me(g).getCardsIn(ZoneType.Library).size();
        Assert.assertEquals(new ArrayList<>(idx.values()), List.of(n - 2, n - 1));
    }

    /** Surveil kept on top (Consider): observed, and the kept card was drawn by Consider itself (nothing stale). */
    @Test
    public void surveilKeep() {
        final Game g = game("Consider", "Island", "Lightning Bolt;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new Arrange(g, me(g), List.of()));
        final InfoStates s = track(g);
        castAndResolve(g, "Consider");
        Assert.assertNotNull(ForcedWinSolverTest.inZone(me(g), ZoneType.Hand, "Lightning Bolt"), "kept and drawn");
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty(), "the kept card was drawn: nothing stale");
        Assert.assertTrue(s.observations > 0, "the surveil arrangement was observed");
    }

    /**
     * Endurance (our graveyard to the bottom in a random order): the order is known to nobody, the set to both (a RUN of
     * 3); the worlds permute it only within the bottom three slots, and do permute it.
     */
    @Test
    public void enduranceIsASetKnownRandomOrderRun() {
        final String frame = ForcedWinSolverTest.frame("Endurance", "Forest;Forest;Forest", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island")
                .replace("humangraveyard=", "humangraveyard=Lightning Bolt;Shock;Giant Growth");
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        final InfoStates s = track(g);
        playAndResolve(g, me(g), find(me(g), ZoneType.Hand, "Endurance").getFirstSpellAbility(), sa -> {
            if (sa != null && sa.isTrigger() && sa.usesTargeting()) {
                sa.resetTargets();
                sa.getTargets().add(me(g));
            }
        });
        Assert.assertTrue(me(g).getCardsIn(ZoneType.Graveyard).isEmpty());
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty(), "no order: " + s.describe());
        for (Player o : new Player[] {me(g), opp(g)}) {
            final List<Set<Integer>> runs = s.runs(o, me(g));
            Assert.assertEquals(runs.size(), 1, "observer " + o + ": " + s.describe());
            Assert.assertEquals(runs.get(0).size(), 3);
        }
        nextTurn(g, s);
        turnAfter(g, s);
        Assert.assertEquals(s.runs(me(g), me(g)).get(0).size(), 3, "the run survives draws from the top");
        final Set<String> orders = new HashSet<>();
        for (long seed = 1; seed <= 16; seed++) {
            final Game c = new GameCopier(g, true).makeCopy();
            final Player cm = c.getPlayer(me(g).getId());
            InfoStates.keepOwnLibrary(s, me(g), cm, new Random(seed));
            final List<String> b = bottom(cm, 3);
            Assert.assertTrue(b.containsAll(List.of("Lightning Bolt", "Shock", "Giant Growth")), "seed " + seed + ": " + b);
            orders.add(b.toString());
        }
        Assert.assertTrue(orders.size() > 1, "the run's order is sampled: " + orders);
    }

    /** Vampiric Tutor: the shuffle first, then the card on top: known to us; not to them (no reveal). */
    @Test
    public void tutorToTheTop() {
        final Game g = game("Vampiric Tutor", "Swamp", "Island;Swamp;Island;Lightning Bolt;Island;Swamp");
        me(g).dangerouslySetController(new Ordered(g, me(g), null));
        final InfoStates s = track(g);
        castAndResolve(g, "Vampiric Tutor");
        Assert.assertEquals(known(s, me(g), me(g)), top(me(g), 1));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        nextTurn(g, s);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
    }

    /** A shuffle (a fetch) after a put-back: nothing known by anyone. */
    @Test
    public void shuffleForgets() {
        final Game g = game("Lightning Bolt", "Island", LIB8);
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Lightning Bolt"));
        me(g).shuffle(null);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
        nextTurn(g, s);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
    }

    /** Courser of Kruphix: our top known to both seats, the new top at once, and the last top outlives Courser. */
    @Test
    public void courserTopIsKnownToBothSeatsAndOutlivesCourser() {
        final Game g = game("", "Courser of Kruphix;Forest", LIB8);
        final InfoStates s = track(g);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, me(g), me(g)), top(me(g), 1));
        Assert.assertEquals(known(s, opp(g), me(g)), top(me(g), 1));
        nextTurn(g, s);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, opp(g), me(g)), top(me(g), 1), "the new top at once");
        final String last = top(me(g), 1).get(0);
        g.getAction().moveToGraveyard(find(me(g), ZoneType.Battlefield, "Courser of Kruphix"), null);
        g.getAction().checkStaticAbilities();
        Assert.assertFalse(me(g).getCardsIn(ZoneType.Library).get(0).getView().canBeShownTo(opp(g).getView()),
                "Forge no longer shows it");
        opp(g).drawCards(1);
        Assert.assertEquals(known(s, opp(g), me(g)), List.of(last), "still known to them after Courser died");
        Assert.assertEquals(known(s, me(g), me(g)), List.of(last));
        me(g).drawCards(1);
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty(), "the new top was never shown");
    }

    /** Bolas's Citadel (MayLookAt You): our top known to us only; the opponent's worlds sample it (16 seeds). */
    @Test
    public void citadelTopIsOwnerOnly() {
        final Game g = game("", "Bolas's Citadel;Swamp", "Island;Swamp;Mountain;Forest;Plains;Island;Swamp;Mountain;Forest;Plains");
        final InfoStates s = track(g);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, me(g), me(g)), top(me(g), 1));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        nextTurn(g, s);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, me(g), me(g)), top(me(g), 1));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        final int topId = me(g).getCardsIn(ZoneType.Library).get(0).getId();
        int moved = 0;
        for (long seed = 1; seed <= 16; seed++) {
            final Game c1 = new GameCopier(g, true).makeCopy();
            LookaheadSearch.resample(g, me(g), c1, c1.getPlayer(me(g).getId()), new Random(seed), s);
            Assert.assertEquals(c1.getPlayer(me(g).getId()).getCardsIn(ZoneType.Library).get(0).getId(), topId, "seed " + seed);
            final Game c2 = new GameCopier(g, true).makeCopy();
            LookaheadSearch.resample(g, opp(g), c2, c2.getPlayer(opp(g).getId()), new Random(seed), s);
            if (c2.getPlayer(me(g).getId()).getCardsIn(ZoneType.Library).get(0).getId() != topId) {
                moved++;
            }
        }
        Assert.assertTrue(moved > 0, "their worlds move our Citadel top");
    }

    /** Their Courser top is pinned in our worlds (16 seeds), and stays pinned after their Courser dies. */
    @Test
    public void theirCourserTopIsPinnedInOurWorlds() {
        final Game g = game2("", "Island", LIB8, "", "Courser of Kruphix;Forest");
        final InfoStates s = track(g);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, me(g), opp(g)), top(opp(g), 1));
        g.getAction().moveToGraveyard(find(opp(g), ZoneType.Battlefield, "Courser of Kruphix"), null);
        g.getAction().checkStaticAbilities();
        final int topId = opp(g).getCardsIn(ZoneType.Library).get(0).getId();
        Assert.assertFalse(opp(g).getCardsIn(ZoneType.Library).get(0).getView().canBeShownTo(me(g).getView()));
        int legacyMoved = 0;
        for (long seed = 1; seed <= 16; seed++) {
            final Game c = new GameCopier(g, true).makeCopy();
            LookaheadSearch.resample(g, me(g), c, c.getPlayer(me(g).getId()), new Random(seed), s);
            Assert.assertEquals(c.getPlayer(opp(g).getId()).getCardsIn(ZoneType.Library).get(0).getId(), topId, "seed " + seed);
            final Game c0 = new GameCopier(g, true).makeCopy();
            LookaheadSearch.resample(g, me(g), c0, c0.getPlayer(me(g).getId()), new Random(seed));
            if (c0.getPlayer(opp(g).getId()).getCardsIn(ZoneType.Library).get(0).getId() != topId) {
                legacyMoved++;
            }
        }
        Assert.assertTrue(legacyMoved > 0, "the legacy visible-prefix worlds forget it (non-vacuous)");
    }

    /** Review #171 S1: a query with a COPY's player returns the watched knowledge only in exactly the watched order. */
    @Test
    public void aCopyQueryNeedsTheWatchedOrder() {
        final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new Ordered(g, me(g), null));
        final InfoStates s = track(g);
        playAndResolve(g, me(g), find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        final Set<Integer> live = s.known(me(g), me(g));
        Assert.assertEquals(live.size(), 2);
        final Game c1 = new GameCopier(g, true).makeCopy();
        final Player m1 = c1.getPlayer(me(g).getId());
        Assert.assertEquals(s.known(me(g), m1), live, "an unshuffled copy: the same ids");
        Assert.assertEquals(s.knownIndex(me(g), m1), s.knownIndex(me(g), me(g)), "at the same indices");
        final Game c2 = new GameCopier(g, true).makeCopy();
        final Player m2 = c2.getPlayer(me(g).getId());
        MyRandom.setRandom(new Random(5));
        m2.shuffle(null);
        Assert.assertTrue(s.known(me(g), m2).isEmpty(), "a shuffled copy pins nothing: " + s.knownIndex(me(g), m2));
        Assert.assertTrue(s.runs(me(g), m2).isEmpty());
        Assert.assertEquals(s.known(me(g), me(g)), live, "the live state is untouched");
    }

    // ------------------------------------------------------------------ the live consumer: LookaheadSearch.redraw

    private static LookaheadSearch search(final boolean infoState, final boolean honest) {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 2;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 1102L;
        c.infoState = infoState;
        if (honest) {
            // the live seats' belief (InteractiveMain.honest / RlLiveSeat.Spec): uniform over the jar's live pool
            c.belief = "uniform";
            c.beliefCube = BeliefSampler.LIVE_CUBE;
            c.beliefCubeSha256 = BeliefSampler.LIVE_CUBE_SHA256;
            c.beliefSingleton = BeliefSampler.LIVE_CUBE_SINGLETON;
        }
        return new LookaheadSearch(c);
    }

    /**
     * The live seat's world re-draw: after Brainstorm, our two put-backs (index 0 and 1, which Forge shows nobody) stay at
     * their index in all 16 worlds with infoState on; with it off (today's live seat) at least one world moves them. A
     * scry bottom stays at index n-1 the same way. Both the plain re-draw and the honest belief (the live K8 and policy
     * seats' path: resampleBelief -> our library) are checked; with the key off nothing is attached to the game.
     */
    @Test
    public void redrawKeepsKnownCardsAtTheirIndexOnlyWithTheKeyOn() {
        for (boolean honest : new boolean[] {false, true}) {
            final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island",
                    "Island;Swamp;Mountain;Forest;Plains;Island;Swamp;Mountain;Forest;Plains;Island;Swamp");
            me(g).dangerouslySetController(new Ordered(g, me(g), null));
            // key off first: the game has no InfoStates and the off re-draw attaches none
            final LookaheadSearch off = search(false, honest);
            playAndResolve(g, me(g), find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
            forge.util.IdScope.open(); // copies draw their object ids from a scope, as the search's do
            final List<Integer> want = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                want.add(me(g).getCardsIn(ZoneType.Library).get(i).getId());
                Assert.assertFalse(me(g).getCardsIn(ZoneType.Library).get(i).getView().canBeShownTo(me(g).getView()),
                        "Forge does not show the put-back to its owner");
            }
            int offMoved = 0;
            for (long seed = 1; seed <= 16; seed++) {
                final GameCopier cp = new GameCopier(g, true);
                final Game c = cp.makeCopy();
                final Player cm = (Player) cp.find(me(g));
                off.redraw(g, me(g), c, cm, new Random(seed));
                for (int i = 0; i < 2; i++) {
                    if (cm.getCardsIn(ZoneType.Library).get(i).getId() != want.get(i)) {
                        offMoved++;
                        break;
                    }
                }
            }
            Assert.assertTrue(offMoved > 0, "honest=" + honest + ": key off forgets the put-backs (today's live worlds)");
            Assert.assertNull(InfoStates.find(g), "key off attaches nothing");
            Assert.assertFalse(g.hasLibraryInfoListeners());
            off.shutdown();
            forge.util.IdScope.close();
        }
        for (boolean honest : new boolean[] {false, true}) {
            final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island",
                    "Island;Swamp;Mountain;Forest;Plains;Island;Swamp;Mountain;Forest;Plains;Island;Swamp");
            me(g).dangerouslySetController(new Ordered(g, me(g), null));
            final InfoStates s = track(g); // as the live bind attaches it (before the mulligans)
            final LookaheadSearch on = search(true, honest);
            playAndResolve(g, me(g), find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
            forge.util.IdScope.open();
            final List<Integer> want = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                want.add(me(g).getCardsIn(ZoneType.Library).get(i).getId());
            }
            Assert.assertEquals(new ArrayList<>(s.knownIndex(me(g), me(g)).keySet()), want, s.describe());
            for (long seed = 1; seed <= 16; seed++) {
                final GameCopier cp = new GameCopier(g, true);
                final Game c = cp.makeCopy();
                final Player cm = (Player) cp.find(me(g));
                on.redraw(g, me(g), c, cm, new Random(seed));
                for (int i = 0; i < 2; i++) {
                    Assert.assertEquals(cm.getCardsIn(ZoneType.Library).get(i).getId(), (int) want.get(i),
                            "honest=" + honest + " seed " + seed + " index " + i);
                }
                Assert.assertEquals(cm.getCardsIn(ZoneType.Library).size(), me(g).getCardsIn(ZoneType.Library).size());
            }
            Assert.assertSame(InfoStates.find(g), s, "the consumer reads the attached state");
            on.shutdown();
            forge.util.IdScope.close();
        }
        // a scry bottom (index n-1), key on vs off, honest belief
        for (boolean key : new boolean[] {false, true}) {
            final Game g = game("", "Island", "Lightning Bolt;Island;Swamp;Mountain;Forest;Plains;Island;Swamp;Mountain;Forest");
            me(g).dangerouslySetController(new Arrange(g, me(g), List.of("Lightning Bolt")));
            if (key) {
                track(g);
            }
            g.getAction().scry(List.of(me(g)), 1, null);
            final List<Card> lib = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
            final int n = lib.size(), bolt = lib.get(n - 1).getId();
            Assert.assertEquals(lib.get(n - 1).getName(), "Lightning Bolt");
            final LookaheadSearch ls = search(key, true);
            forge.util.IdScope.open();
            int kept = 0;
            for (long seed = 1; seed <= 16; seed++) {
                final GameCopier cp = new GameCopier(g, true);
                final Game c = cp.makeCopy();
                final Player cm = (Player) cp.find(me(g));
                ls.redraw(g, me(g), c, cm, new Random(seed));
                if (new ArrayList<>(cm.getCardsIn(ZoneType.Library)).get(n - 1).getId() == bolt) {
                    kept++;
                }
            }
            Assert.assertEquals(kept == 16, key, "key " + key + ": the scry bottom kept in " + kept + "/16 worlds");
            ls.shutdown();
            forge.util.IdScope.close();
        }
    }

    /** The key is off by default and absent from the config JSON unless on. */
    @Test
    public void theKeyIsOffByDefaultAndOnlyInTheJsonWhenOn() {
        final LookaheadSearch.Config c = new LookaheadSearch.Config();
        Assert.assertFalse(c.infoState);
        Assert.assertFalse(c.toJson().has("infoState"));
        c.infoState = true;
        Assert.assertTrue(c.toJson().get("infoState").getAsBoolean());
        Assert.assertFalse(new forge.bench.rl.RlSearch.Config().infoState);
        Assert.assertTrue(LookaheadSearch.Config.infoStateKey("infoState", "on"));
        Assert.assertFalse(LookaheadSearch.Config.infoStateKey("infoState", "off"));
        Assert.expectThrows(IllegalArgumentException.class, () -> LookaheadSearch.Config.infoStateKey("infoState", "1"));
        Assert.expectThrows(IllegalArgumentException.class, () -> LookaheadSearch.Config.infoStateKey("infoState", "true"));
        // the live policy spec (RlLiveSeat): absent = off and absent from the search JSON; on; any other value refused
        final String base = "server=127.0.0.1:1,policySha=" + "0".repeat(64) + ",cardIndex=x,budgetMs=8000,threads=8,obs=2";
        final forge.bench.rl.RlLiveSeat.Spec offSpec = forge.bench.rl.RlLiveSeat.Spec.parse(base);
        Assert.assertFalse(offSpec.search.infoState);
        Assert.assertFalse(offSpec.search.toJson().has("infoState"));
        Assert.assertEquals(forge.bench.rl.RlLiveSeat.Spec.parse(base + ",infoState=off").search.toJson().toString(),
                offSpec.search.toJson().toString(), "an explicit off is the default byte for byte");
        final forge.bench.rl.RlLiveSeat.Spec onSpec = forge.bench.rl.RlLiveSeat.Spec.parse(base + ",infoState=on");
        Assert.assertTrue(onSpec.search.infoState);
        Assert.assertTrue(onSpec.search.toJson().get("infoState").getAsBoolean());
        Assert.expectThrows(IllegalArgumentException.class, () -> forge.bench.rl.RlLiveSeat.Spec.parse(base + ",infoState=1"));
    }

    /** The K8 seat's bind attaches InfoStates only with the key on (and so, live, before the mulligans). */
    @Test
    public void theK8BindAttachesOnlyWithTheKeyOn() {
        for (boolean key : new boolean[] {false, true}) {
            final Game g = game("", "Island", LIB8);
            final LobbyPlayerLookahead lp = new LobbyPlayerLookahead("k8");
            final LookaheadSearch ls = search(key, false);
            lp.bind(g, ls);
            Assert.assertEquals(InfoStates.find(g) != null, key, "key " + key);
            Assert.assertEquals(g.hasLibraryInfoListeners(), key);
            ls.shutdown();
        }
    }

    // ------------------------------------------------------------------ LeakCheck (the look-ahead worlds)

    /** Re-randomise everything InfoState(o) marks unknown, keeping counts and the known cards in place; re-sync. */
    static void rerandomise(final Game g, final InfoStates s, final Player o, final long seed) {
        final Random r = new Random(seed);
        final Player them = o == me(g) ? opp(g) : me(g);
        for (Player w : new Player[] {o, them}) {
            final Set<Integer> k = s.known(o, w);
            final List<Card> lib = new ArrayList<>(w.getCardsIn(ZoneType.Library));
            final List<Card> unk = new ArrayList<>();
            for (Card c : lib) {
                if (!k.contains(c.getId())) {
                    unk.add(c);
                }
            }
            if (w == them && !w.getCardsIn(ZoneType.Hand).isEmpty() && !unk.isEmpty()) {
                final List<Card> hand = new ArrayList<>(w.getCardsIn(ZoneType.Hand));
                final int hi = r.nextInt(hand.size()), li = r.nextInt(unk.size());
                final Card h = hand.get(hi), l = unk.get(li);
                hand.set(hi, l);
                unk.set(li, h);
                w.getZone(ZoneType.Hand).setCards(hand);
            }
            Collections.shuffle(unk, r);
            final List<Card> out = new ArrayList<>();
            int u = 0;
            for (Card c : lib) {
                out.add(k.contains(c.getId()) ? c : unk.get(u++));
            }
            w.getZone(ZoneType.Library).setCards(out);
            s.resync(w);
        }
    }

    /** The look-ahead worlds for observer o (infoState resample): ids by zone and slot. */
    static String output(final Game g, final InfoStates s, final Player o) {
        final StringBuilder sb = new StringBuilder();
        sb.append("known=").append(s.known(o, o)).append(s.known(o, o == me(g) ? opp(g) : me(g)));
        for (long seed = 1; seed <= 4; seed++) {
            final Game c = new GameCopier(g, true).makeCopy();
            final Player co = c.getPlayer(o.getId());
            LookaheadSearch.resample(g, o, c, co, new Random(seed), s);
            for (Player p : c.getRegisteredPlayers()) {
                sb.append(" w").append(seed).append(':').append(p.getId()).append(":L");
                for (Card x : p.getCardsIn(ZoneType.Library)) {
                    sb.append(x.getId()).append(',');
                }
                if (p.getId() != o.getId()) {
                    final List<Integer> h = new ArrayList<>();
                    for (Card x : p.getCardsIn(ZoneType.Hand)) {
                        h.add(x.getId());
                    }
                    Collections.sort(h);
                    sb.append(":H").append(h);
                }
            }
        }
        return sb.toString();
    }

    /**
     * LeakCheck over a position with put-backs, a bottom, Courser's top and a peek of their library, for both observers:
     * the worlds are byte-identical after re-randomising what o does not know.
     */
    @Test
    public void leakCheck() {
        final Game g = game2("Lightning Bolt;Shock", "Island;Mishra's Bauble;Courser of Kruphix;Forest", LIB8,
                "Giant Growth;Ponder;Brainstorm", "Island");
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        g.getAction().moveToLibrary(find(me(g), ZoneType.Hand, "Shock"), -1, null);
        g.getAction().checkStaticAbilities();
        final SpellAbility peek = ability(find(me(g), ZoneType.Battlefield, "Mishra's Bauble"),
                forge.game.ability.ApiType.PeekAndReveal);
        peek.setActivatingPlayer(me(g));
        peek.resetTargets();
        peek.getTargets().add(opp(g));
        playAndResolve(g, me(g), peek, null);
        Assert.assertEquals(known(s, me(g), opp(g)).size(), 1);
        Assert.assertEquals(known(s, me(g), me(g)).size(), 2, s.describe());
        Assert.assertEquals(known(s, opp(g), me(g)).size(), 1, "Courser's top only: " + s.describe());
        for (Player o : new Player[] {me(g), opp(g)}) {
            final String before = output(g, s, o);
            for (long seed = 11; seed <= 14; seed++) {
                rerandomise(g, s, o, seed);
                s.assertConsistent();
                Assert.assertEquals(output(g, s, o), before, "observer " + o + " seed " + seed);
            }
        }
    }
}
