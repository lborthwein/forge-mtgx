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

import com.google.gson.JsonObject;

import forge.ai.PlayerControllerAi;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.LibraryInfoListener;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * order-memory-1102 layer 1: {@link InfoStates}, per observer. The AUDIT §5 matrix (each case checked on the next turn,
 * after our draw, and on the turn after, after an opponent action that does not touch our library and our second
 * draw), the DESIGN-2B §5 cases (Courser known to both, Citadel owner-only, a shuffle under Courser, persistence after
 * the static ends, the opponent's library), the consumers' reads, and the generic LeakCheck. Every step also checks
 * {@link InfoStates#assertConsistent} (computed offsets equal Forge's true index).
 */
public class InfoStateTest extends forge.ai.AITest {

    // ------------------------------------------------------------------ helpers

    static Player me(final Game g) {
        return KnownLibTest.me(g);
    }

    static Player opp(final Game g) {
        return KnownLibTest.opp(g);
    }

    static InfoStates track(final Game g) {
        final InfoStates s = InfoStates.attach(g);
        Assert.assertSame(InfoStates.attach(g), s, "one per game");
        return s;
    }

    /** The names {@code o} knows in {@code w}'s library, top first. */
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

    /** The next turn: our draw (consistency checked). */
    static void nextTurn(final Game g, final InfoStates s) {
        me(g).drawCards(1);
        s.assertConsistent();
    }

    /** The turn after: an opponent action that does not touch our library (their draw), then our draw. */
    static void turnAfter(final Game g, final InfoStates s) {
        opp(g).drawCards(1);
        s.assertConsistent();
        me(g).drawCards(1);
        s.assertConsistent();
    }

    static Game game(final String hand, final String bf, final String lib) {
        return KnownLibTest.game(hand, bf, lib, 20);
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

    /** No listener: nothing is built (the default path); with one, the channel reports reveals with their observer set. */
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

    // ------------------------------------------------------------------ AUDIT §5 (core, per observer)

    /** T1 Brainstorm: our two put-backs are known to us (not to them); drawn one per turn. */
    @Test
    public void t01BrainstormPutBacks() {
        final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final InfoStates s = track(g);
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        final List<String> k = known(s, me(g), me(g));
        Assert.assertEquals(k.size(), 2, k + " lib " + top(me(g), 3));
        Assert.assertEquals(k, top(me(g), 2));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty(), "they did not see our hand");
        nextTurn(g, s);
        Assert.assertEquals(known(s, me(g), me(g)), List.of(k.get(1)));
        turnAfter(g, s);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
    }

    /** T2 / T18 a public card put on our top (Sensei's Top's return, their Memory Lapse): known to both. */
    @Test
    public void t02PublicCardToTopKnownToBoth() {
        final Game g = game("", "Sensei's Divining Top;Island", LIB8);
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Sensei's Divining Top"), 0, null);
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Sensei's Divining Top"));
        Assert.assertEquals(known(s, opp(g), me(g)), List.of("Sensei's Divining Top"));
        nextTurn(g, s);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty() && known(s, opp(g), me(g)).isEmpty());
    }

    /** T3 Ponder keep (known 2, drawn one per turn) / T3s Ponder shuffle (nothing). */
    @Test
    public void t03PonderKeepAndShuffle() {
        for (boolean shuffled : new boolean[] {false, true}) {
            final Game g = game("Ponder", "Island", "Island;Lightning Bolt;Swamp;Island;Swamp;Island;Swamp");
            me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), List.of("Island", "Lightning Bolt", "Swamp"), shuffled));
            final InfoStates s = track(g);
            KnownLibTest.castAndResolve(g, "Ponder");
            Assert.assertEquals(known(s, me(g), me(g)).size(), shuffled ? 0 : 2, "shuffled=" + shuffled);
            Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
            nextTurn(g, s);
            Assert.assertEquals(known(s, me(g), me(g)).size(), shuffled ? 0 : 1);
            turnAfter(g, s);
            Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
        }
    }

    /** T5 scry 2 to the bottom (B1 above B2): the bottom run in that order, kept through the draws; not known to them. */
    @Test
    public void t05ScryBottomInOrder() {
        final Game g = game("", "Island", "Lightning Bolt;Shock;Island;Swamp;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new Arrange(g, me(g), List.of("Lightning Bolt", "Shock")));
        final InfoStates s = track(g);
        g.getAction().scry(List.of(me(g)), 2, null);
        Assert.assertEquals(bottom(me(g), 2), List.of("Lightning Bolt", "Shock"), "Bolt to the bottom first, then Shock under it");
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Lightning Bolt", "Shock"));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        nextTurn(g, s);
        turnAfter(g, s);
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Lightning Bolt", "Shock"), "bottoms survive draws from the top");
        final Map<Integer, Integer> idx = s.knownIndex(me(g), me(g));
        final int n = me(g).getCardsIn(ZoneType.Library).size();
        Assert.assertEquals(new ArrayList<>(idx.values()), List.of(n - 2, n - 1));
    }

    /** T6 surveil, kept on top (Consider): known to us. */
    @Test
    public void t06SurveilKeep() {
        final Game g = game("Consider", "Island", "Lightning Bolt;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new Arrange(g, me(g), List.of()));
        final InfoStates s = track(g);
        KnownLibTest.castAndResolve(g, "Consider");
        // Consider: surveil 1, then draw: the kept card is drawn by Consider itself
        System.out.println("INFOSTATE consider hand=" + me(g).getCardsIn(ZoneType.Hand) + " lib=" + top(me(g), 2));
        Assert.assertNotNull(ForcedWinSolverTest.inZone(me(g), ZoneType.Hand, "Lightning Bolt"), "kept and drawn");
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty(), "the kept card was drawn: nothing stale");
        Assert.assertTrue(s.observations > 0, "the surveil arrangement was observed");
    }

    /** T7 dig, the rest to the bottom in our order (Impulse): known to us at the bottom, through the draws. */
    @Test
    public void t07DigRestToTheBottom() {
        final Game g = game("Impulse", "Island;Island", "Lightning Bolt;Shock;Giant Growth;Ponder;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final InfoStates s = track(g);
        KnownLibTest.castAndResolve(g, "Impulse");
        final List<String> k = known(s, me(g), me(g));
        Assert.assertEquals(k.size(), 3, k + " lib " + bottom(me(g), 4));
        Assert.assertEquals(k, bottom(me(g), 3));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        nextTurn(g, s);
        turnAfter(g, s);
        Assert.assertEquals(known(s, me(g), me(g)), k);
    }

    /** T8 a random rest (Abundant Harvest) and T9 cascade (Shardless Agent): no order known to anyone. */
    @Test
    public void t08t09RandomOrdersAreNeverKnown() {
        final Game g = game("Abundant Harvest", "Forest", "Island;Swamp;Mountain;Lightning Bolt;Island;Swamp;Island;Swamp");
        final KnownLibTest.Ordered c = new KnownLibTest.Ordered(g, me(g), null, false);
        c.pick = "Nonland";
        me(g).dangerouslySetController(c);
        final InfoStates s = track(g);
        KnownLibTest.castAndResolve(g, "Abundant Harvest");
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty(), known(s, me(g), me(g)).toString());
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        final Game g2 = game("Shardless Agent", "Tropical Island;Island;Forest",
                "Island;Swamp;Mountain;Lightning Bolt;Island;Swamp;Island;Swamp;Island;Swamp");
        me(g2).dangerouslySetController(new KnownLibTest.Ordered(g2, me(g2), null, false));
        final InfoStates s2 = track(g2);
        KnownLibTest.playAndResolve(g2, me(g2), ChooseACardTest.find(me(g2), ZoneType.Hand, "Shardless Agent").getFirstSpellAbility(), null);
        Assert.assertTrue(known(s2, me(g2), me(g2)).isEmpty(), known(s2, me(g2), me(g2)).toString());
        Assert.assertTrue(known(s2, opp(g2), me(g2)).isEmpty());
        // cascade reveals the exiled cards to everyone: their SET is known to both, the order to nobody
        for (Player o : new Player[] {me(g2), opp(g2)}) {
            final List<Set<Integer>> runs = s2.runs(o, me(g2));
            Assert.assertEquals(runs.size(), 1, "observer " + o + ": " + s2.describe());
            Assert.assertEquals(runs.get(0).size(), 3, s2.describe());
        }
    }

    /**
     * T10 Endurance (our graveyard to the bottom in a random order): the order is known to nobody, the set is known to
     * both (a RUN of 3); our belief worlds permute it only within the bottom three slots (and do permute it).
     */
    @Test
    public void t10EnduranceIsASetKnownRun() {
        final String frame = ForcedWinSolverTest.frame("Endurance", "Forest;Forest;Forest", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island")
                .replace("humangraveyard=", "humangraveyard=Lightning Bolt;Shock;Giant Growth");
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        final InfoStates s = track(g);
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Endurance").getFirstSpellAbility(), sa -> {
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
        s.assertConsistent();
        nextTurn(g, s);
        turnAfter(g, s);
        Assert.assertEquals(s.runs(me(g), me(g)).get(0).size(), 3, "the run survives draws from the top");
        // belief worlds: the three stay in the bottom three slots, in some order; at least one seed moves them
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

    /** T11 Doomsday's pile: known to us; not to them. */
    @Test
    public void t11DoomsdayPile() {
        final Game g = game("Doomsday", "Swamp;Swamp;Swamp", "Island;Thassa's Oracle;Island");
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), List.of("Thassa's Oracle", "Island"), false));
        final InfoStates s = track(g);
        KnownLibTest.castAndResolve(g, "Doomsday");
        Assert.assertEquals(known(s, me(g), me(g)).size(), 3, s.describe());
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty(), s.describe());
        nextTurn(g, s);
        Assert.assertEquals(known(s, me(g), me(g)).size(), 2);
    }

    /** T12 Vampiric Tutor: the shuffle comes first, then the card on top: known to us; not to them (no reveal). */
    @Test
    public void t12TutorToTheTop() {
        final Game g = game("Vampiric Tutor", "Swamp", "Island;Swamp;Island;Lightning Bolt;Island;Swamp");
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final InfoStates s = track(g);
        KnownLibTest.castAndResolve(g, "Vampiric Tutor");
        Assert.assertEquals(known(s, me(g), me(g)), top(me(g), 1));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        nextTurn(g, s);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
    }

    /** T14 / TS a shuffle (a fetch) after a put-back: nothing known by anyone. */
    @Test
    public void t14ShuffleForgets() {
        final Game g = game("Lightning Bolt", "Island", LIB8);
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Lightning Bolt"));
        me(g).shuffle(null);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
        nextTurn(g, s);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
    }

    /** T15 London tucks (hand to the bottom, outside any resolution): known to us at the bottom; not to them. */
    @Test
    public void t15MulliganTucks() {
        final Game g = game("Lightning Bolt;Shock", "Island", LIB8);
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), -1, null);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Shock"), -1, null);
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Lightning Bolt", "Shock"));
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        nextTurn(g, s);
        turnAfter(g, s);
        Assert.assertEquals(known(s, me(g), me(g)), List.of("Lightning Bolt", "Shock"));
    }

    /**
     * T16 Mishra's Bauble: on our own library we learn our top in place (they do not); on THEIR library we learn their
     * top (they do not: a peek is the peeker's).
     */
    @Test
    public void t16BaublePeeks() {
        for (boolean theirs : new boolean[] {false, true}) {
            final Game g = game("", "Mishra's Bauble;Island", LIB8);
            final InfoStates s = track(g);
            final Player lib = theirs ? opp(g) : me(g);
            final SpellAbility sa = ability(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Mishra's Bauble"),
                    forge.game.ability.ApiType.PeekAndReveal);
            sa.setActivatingPlayer(me(g));
            sa.resetTargets();
            sa.getTargets().add(lib);
            KnownLibTest.playAndResolve(g, me(g), sa, null);
            Assert.assertEquals(known(s, me(g), lib), top(lib, 1), "theirs=" + theirs + " " + s.describe());
            Assert.assertTrue(known(s, opp(g), lib).isEmpty(), "theirs=" + theirs + " " + s.describe());
            if (!theirs) {
                nextTurn(g, s);
                Assert.assertTrue(known(s, me(g), me(g)).isEmpty(), "the peeked top was drawn");
            } else {
                me(g).drawCards(1);
                Assert.assertEquals(known(s, me(g), lib), top(lib, 1), "our draw does not touch their library");
                opp(g).drawCards(1);
                Assert.assertTrue(known(s, me(g), lib).isEmpty(), "they drew it");
            }
        }
    }

    /** T19 their Teferi -3: third from the top, known to both, non-contiguous; 0 after two draws. */
    @Test
    public void t19TeferiThirdFromTheTop() {
        final Game g = game2("", "Baleful Strix;Island", "Island;Swamp;Island;Swamp;Island;Swamp", "",
                "Teferi, Hero of Dominaria|Counters:LOYALTY=4");
        final InfoStates s = track(g);
        final Card teferi = ChooseACardTest.find(opp(g), ZoneType.Battlefield, "Teferi, Hero of Dominaria");
        final SpellAbility minus3 = ability(teferi, forge.game.ability.ApiType.ChangeZone);
        minus3.setActivatingPlayer(opp(g));
        minus3.resetTargets();
        minus3.getTargets().add(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Baleful Strix"));
        KnownLibTest.playAndResolve(g, opp(g), minus3, null);
        for (Player o : new Player[] {me(g), opp(g)}) {
            Assert.assertEquals(new ArrayList<>(s.knownIndex(o, me(g)).values()), List.of(2), s.describe());
        }
        nextTurn(g, s);
        Assert.assertEquals(new ArrayList<>(s.knownIndex(me(g), me(g)).values()), List.of(1));
        turnAfter(g, s);
        Assert.assertEquals(new ArrayList<>(s.knownIndex(me(g), me(g)).values()), List.of(0), "on top after two draws");
        Assert.assertEquals(new ArrayList<>(s.knownIndex(opp(g), me(g)).values()), List.of(0));
        Assert.assertEquals(top(me(g), 1), List.of("Baleful Strix"));
    }

    /** T21 Approach of the Second Sun: seventh from the top (index 6, behind a Branch), known to both, one up per draw. */
    @Test
    public void t21ApproachSeventhFromTheTop() {
        final Game g = game("Approach of the Second Sun", "Plains;Plains;Plains;Plains;Plains;Plains;Plains",
                "Island;Swamp;Island;Swamp;Island;Swamp;Island;Swamp;Island;Swamp");
        final InfoStates s = track(g);
        KnownLibTest.castAndResolve(g, "Approach of the Second Sun");
        Assert.assertEquals(me(g).getCardsIn(ZoneType.Library).get(6).getName(), "Approach of the Second Sun");
        for (Player o : new Player[] {me(g), opp(g)}) {
            Assert.assertEquals(new ArrayList<>(s.knownIndex(o, me(g)).values()), List.of(6), s.describe());
        }
        nextTurn(g, s);
        Assert.assertEquals(new ArrayList<>(s.knownIndex(me(g), me(g)).values()), List.of(5));
        turnAfter(g, s);
        Assert.assertEquals(new ArrayList<>(s.knownIndex(opp(g), me(g)).values()), List.of(4));
    }

    /** T22 their Portent over our two put-backs: we forget them; they know what they arranged (unless they shuffled). */
    @Test
    public void t22TheirPortentOverOurTop() {
        final Game g = game2("Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", "Portent", "Island");
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Shock"), 0, null);
        Assert.assertEquals(known(s, me(g), me(g)).size(), 2);
        final SpellAbility portent = ChooseACardTest.find(opp(g), ZoneType.Hand, "Portent").getFirstSpellAbility();
        portent.setActivatingPlayer(opp(g));
        portent.resetTargets();
        portent.getTargets().add(me(g));
        final int sh = s.shuffles;
        KnownLibTest.playAndResolve(g, opp(g), portent, null);
        System.out.println("INFOSTATE portent shuffled=" + (s.shuffles > sh) + " " + s.describe());
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty(), "their order of our top three");
        if (s.shuffles == sh) {
            Assert.assertTrue(known(s, opp(g), me(g)).size() >= 2, "they arranged them: " + s.describe());
        } else {
            Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        }
    }

    /** T23 a silent reorder (setCards): forgotten by every observer. */
    @Test
    public void t23SilentReorderForgets() {
        final Game g = game("", "Sensei's Divining Top;Island", LIB8);
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Sensei's Divining Top"), 0, null);
        final List<Card> order = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
        Collections.swap(order, 0, 1);
        me(g).getZone(ZoneType.Library).setCards(order);
        Assert.assertTrue(known(s, me(g), me(g)).isEmpty());
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
        Assert.assertTrue(s.silent > 0);
    }

    // ------------------------------------------------------------------ DESIGN-2B §5

    /**
     * Courser of Kruphix (MayLookAt Player): our top is known to both, and each new top at once; after Courser dies the
     * last top stays known to both until it moves.
     */
    @Test
    public void courserTopIsKnownToBothAndOutlivesCourser() {
        final Game g = game("", "Courser of Kruphix;Forest", LIB8);
        final InfoStates s = track(g);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, me(g), me(g)), top(me(g), 1));
        Assert.assertEquals(known(s, opp(g), me(g)), top(me(g), 1));
        nextTurn(g, s);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, opp(g), me(g)), top(me(g), 1), "the new top at once");
        final String last = top(me(g), 1).get(0);
        g.getAction().moveToGraveyard(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Courser of Kruphix"), null);
        g.getAction().checkStaticAbilities();
        Assert.assertFalse(me(g).getCardsIn(ZoneType.Library).get(0).getView().canBeShownTo(opp(g).getView()),
                "Forge no longer shows it");
        opp(g).drawCards(1);
        Assert.assertEquals(known(s, opp(g), me(g)), List.of(last), "still known to them after Courser died");
        Assert.assertEquals(known(s, me(g), me(g)), List.of(last));
        me(g).drawCards(1);
        Assert.assertTrue(known(s, opp(g), me(g)).isEmpty(), "the new top was never shown");
    }

    /** Bolas's Citadel (MayLookAt You): our top is known to us only; the opponent's worlds sample it (16 seeds). */
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
            // our view: kept; their view (they resample OUR library as their opponent's): sampled
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

    /** A shuffle under Courser (a fetch): everything forgotten, then the new top known to both at the very next read. */
    @Test
    public void aShuffleUnderCourserTeachesTheNewTopAtOnce() {
        final Game g = game("Lightning Bolt", "Courser of Kruphix;Forest", LIB8);
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), -1, null);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, me(g), me(g)).size(), 2);
        me(g).shuffle(null);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, me(g), me(g)), top(me(g), 1));
        Assert.assertEquals(known(s, opp(g), me(g)), top(me(g), 1));
    }

    /**
     * Persistence after Courser dies: a seen card put on top shifts the old top by exactly +1; an unseen card put in at
     * an unnamed place voids it; a shuffle forgets it.
     */
    @Test
    public void persistenceControls() {
        for (int kase = 0; kase < 3; kase++) {
            final Game g = game("Lightning Bolt", "Courser of Kruphix;Forest;Sensei's Divining Top", LIB8);
            final InfoStates s = track(g);
            g.getAction().checkStaticAbilities();
            final String last = top(me(g), 1).get(0);
            // a read (in play: the next priority) while Courser shows it
            Assert.assertEquals(known(s, opp(g), me(g)), List.of(last));
            g.getAction().moveToGraveyard(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Courser of Kruphix"), null);
            g.getAction().checkStaticAbilities();
            Assert.assertEquals(known(s, opp(g), me(g)), List.of(last));
            if (kase == 0) {
                g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Sensei's Divining Top"), 0, null);
                Assert.assertEquals(new ArrayList<>(s.knownIndex(opp(g), me(g)).values()), List.of(0, 1), s.describe());
                Assert.assertEquals(known(s, opp(g), me(g)), List.of("Sensei's Divining Top", last));
            } else if (kase == 1) {
                // a hidden card of our hand put in second from the top outside any resolution: no named place
                g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 1, null);
                Assert.assertTrue(known(s, opp(g), me(g)).isEmpty(), s.describe());
            } else {
                me(g).shuffle(null);
                Assert.assertTrue(known(s, opp(g), me(g)).isEmpty());
            }
        }
    }

    /** Their Courser top is pinned in our belief worlds (16 seeds), and stays pinned after their Courser dies. */
    @Test
    public void theirCourserTopIsPinnedInOurWorlds() {
        final Game g = game2("", "Island", LIB8, "", "Courser of Kruphix;Forest");
        final InfoStates s = track(g);
        g.getAction().checkStaticAbilities();
        Assert.assertEquals(known(s, me(g), opp(g)), top(opp(g), 1));
        g.getAction().moveToGraveyard(ChooseACardTest.find(opp(g), ZoneType.Battlefield, "Courser of Kruphix"), null);
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

    // ------------------------------------------------------------------ consumers

    /** R3 / choose-a-card memory (OwnLibraryMemory infoState mode): the seat's InfoState view; copy reads are read-only. */
    @Test
    public void ownLibraryMemoryReadsInfoState() {
        final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final OwnLibraryMemory m = new OwnLibraryMemory(me(g), false, true);
        m.watch(g);
        Assert.assertTrue(m.delegating());
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        Assert.assertEquals(m.known(me(g)).size(), 2);
        Assert.assertSame(m.infoStates(), InfoStates.find(g), "the game's shared instance");
        // a copy: read-only; the ids carry over only in the watched order (review #171 S1), none from another order
        final Game c = new GameCopier(g, true).makeCopy();
        final Player cm = c.getPlayer(me(g).getId());
        Assert.assertEquals(m.known(cm).size(), 2, "an unchanged copy: the ids survive");
        final List<Card> l = new ArrayList<>(cm.getCardsIn(ZoneType.Library));
        Collections.reverse(l);
        cm.getZone(ZoneType.Library).setCards(l);
        Assert.assertTrue(m.known(cm).isEmpty(), "a reordered copy: none");
        Assert.assertEquals(m.known(me(g)).size(), 2, "the live state was not wiped by the copy read");
        // a pinned reorder of the watched game re-syncs
        OwnLibraryMemory.shuffleUnknownCanonical(me(g), m.known(me(g)), new Random(5));
        m.resync();
        Assert.assertEquals(m.known(me(g)).size(), 2, "pins survive a resync");
    }

    /** The solver (key infoState): its known top is InfoState's; a random-order block is not placed; the key is off by default. */
    @Test
    public void solverReadsInfoState() {
        Assert.assertFalse(new ForcedWinSolver.Config().infoState);
        final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final ForcedWinSolver.Config cfg = new ForcedWinSolver.Config();
        cfg.infoState = true;
        final ForcedWinSolver s = new ForcedWinSolver(me(g), cfg);
        Assert.assertEquals(s.knownTopCount(g), 0);
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        Assert.assertEquals(s.knownTopCount(g), 2);
        me(g).shuffle(null);
        Assert.assertEquals(s.knownTopCount(g), 0);
    }

    /** Spec keys: off by default, in the identity only when on; infoState turns knownLib's path on. */
    @Test
    public void specKeysAreOffByDefaultAndOnlyInTheShaWhenOn() {
        final JsonObject base = KnownLibTest.spec(false, false);
        final HonestLabel.Spec off = HonestLabel.Spec.parse(base);
        Assert.assertFalse(off.infoState);
        Assert.assertFalse(off.toJson().has("infoState"));
        final JsonObject on = base.deepCopy();
        on.addProperty("infoState", true);
        final HonestLabel.Spec onS = HonestLabel.Spec.parse(on);
        Assert.assertTrue(onS.infoState && onS.knownLib);
        Assert.assertNotEquals(onS.sha(), off.sha());
        final JsonObject explicitOff = base.deepCopy();
        explicitOff.addProperty("infoState", false);
        Assert.assertEquals(HonestLabel.Spec.parse(explicitOff).sha(), off.sha());
        Assert.assertFalse(new LookaheadSearch.Config().infoState);
        Assert.assertFalse(new LookaheadSearch.Config().toJson().has("infoState"));
        Assert.assertFalse(new forge.bench.rl.RlSearch.Config().infoState);
    }

    // ------------------------------------------------------------------ LeakCheck

    /**
     * Re-randomise everything InfoState(o) marks unknown (both libraries' unknown slots, the opponent's hidden hand
     * against their unknown library cards), keeping counts and the known cards in place, then re-sync the state.
     */
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
                // swap one hidden hand card with one unknown library card (their zones' contents, no events)
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

    /** A consumer's output for observer o (worlds and memories): ids by zone and slot. */
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
            // R3 / choose-a-card world shuffle and the solver's play-out shuffle, canonical
            final Game c2 = new GameCopier(g, true).makeCopy();
            final Player c2o = c2.getPlayer(o.getId());
            OwnLibraryMemory.shuffleUnknownCanonical(c2o, s.known(o, o), new Random(seed));
            sb.append(" m").append(seed).append(OwnLibraryMemory.order(c2o));
            final Game c3 = new GameCopier(g, true).makeCopy();
            final Player c3o = c3.getPlayer(o.getId());
            ForcedWinSolver.shuffleUnknownOwnLibrary(c3o, 0, s.known(o, o), new Random(seed), true);
            sb.append(" f").append(seed).append(OwnLibraryMemory.order(c3o));
        }
        return sb.toString();
    }

    /**
     * LeakCheck over positions with every knowledge kind (put-backs, a bottom, Courser's persisting top, a peek of their
     * library), for both observers: the consumers' output is byte-identical after re-randomising what o does not know.
     */
    @Test
    public void leakCheck() {
        final Game g = game2("Lightning Bolt;Shock", "Island;Mishra's Bauble;Courser of Kruphix;Forest", LIB8,
                "Giant Growth;Ponder;Brainstorm", "Island");
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Shock"), -1, null);
        g.getAction().checkStaticAbilities();
        final SpellAbility peek = ability(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Mishra's Bauble"),
                forge.game.ability.ApiType.PeekAndReveal);
        peek.setActivatingPlayer(me(g));
        peek.resetTargets();
        peek.getTargets().add(opp(g));
        KnownLibTest.playAndResolve(g, me(g), peek, null);
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

    /**
     * LeakCheck on the label (HonestLabel infoState): our seat's label row is byte-identical after re-randomising
     * everything InfoState(us) marks unknown.
     */
    @Test
    public void leakCheckLabel() {
        final Game g = game2("Lightning Bolt", "Mountain;Mountain", LIB8, "Giant Growth;Ponder", "Island");
        final InfoStates s = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        final JsonObject on = KnownLibTest.spec(false, false);
        on.addProperty("infoState", true);
        final HonestLabel.Spec spec = HonestLabel.Spec.parse(on);
        final String before = labelRow(g, s, spec);
        for (long seed = 21; seed <= 22; seed++) {
            rerandomise(g, s, me(g), seed);
            Assert.assertEquals(labelRow(g, s, spec), before, "seed " + seed);
        }
    }

    static String labelRow(final Game g, final InfoStates s, final HonestLabel.Spec spec) {
        final HonestLabel.Seat seat = new HonestLabel.Seat(spec);
        final Set<Integer> known = s.known(me(g), me(g));
        final JsonObject r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> seat.label(g, me(g), 42L, known)))
                .toJson();
        r.remove("ms");
        r.remove("meter");
        System.out.println("INFOSTATE label " + r);
        return r.toString();
    }

    /** The engine channel's own observer sets: a REVEAL is everyone's; PEEK and ARRANGE are one player's. */
    @Test
    public void observationKindsCarryTheirObservers() {
        final Game g = game("", "Mishra's Bauble;Island", LIB8);
        final List<String> got = new ArrayList<>();
        g.addLibraryInfoListener((LibraryInfoListener.Kind kind, List<Player> obs, List<Card> cards, ZoneType z) ->
                got.add(kind + ":" + obs.size()));
        final SpellAbility sa = ability(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Mishra's Bauble"),
                forge.game.ability.ApiType.PeekAndReveal);
        sa.setActivatingPlayer(me(g));
        sa.resetTargets();
        sa.getTargets().add(me(g));
        KnownLibTest.playAndResolve(g, me(g), sa, null);
        Assert.assertTrue(got.contains("PEEK:1"), got.toString());
    }
    /**
     * Review #171 S1 (= #168 S1): a query made with a COPY's player returns the watched knowledge only when the copy's
     * library is in exactly the watched order. A tutor-shuffled apply copy pins nothing (X would sit at a random index);
     * an unshuffled copy keeps X at the same index. The same for set-known runs.
     */
    @Test
    public void aCopyQueryNeedsTheWatchedOrder() {
        final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp;Island;Swamp");
        me(g).dangerouslySetController(new KnownLibTest.Ordered(g, me(g), null, false));
        final InfoStates s = track(g);
        KnownLibTest.playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        final Set<Integer> live = s.known(me(g), me(g));
        Assert.assertEquals(live.size(), 2);
        final Game c1 = new GameCopier(g, true).makeCopy();
        final Player m1 = c1.getPlayer(me(g).getId());
        Assert.assertEquals(s.known(me(g), m1), live, "an unshuffled copy: the same ids");
        Assert.assertEquals(s.knownIndex(me(g), m1), s.knownIndex(me(g), me(g)), "at the same indices");
        final Game c2 = new GameCopier(g, true).makeCopy();
        final Player m2 = c2.getPlayer(me(g).getId());
        forge.util.MyRandom.setRandom(new Random(5));
        m2.shuffle(null); // the tutor's shuffle in the applied copy (no tracker there)
        Assert.assertTrue(s.known(me(g), m2).isEmpty(), "a shuffled copy pins nothing: " + s.knownIndex(me(g), m2));
        Assert.assertTrue(s.runs(me(g), m2).isEmpty());
        Assert.assertEquals(s.known(me(g), me(g)), live, "the live state is untouched");
    }
}
