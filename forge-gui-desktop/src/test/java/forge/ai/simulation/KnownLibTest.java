package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.PlayerControllerAi;
import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.IdScope;
import forge.util.MyRandom;

/**
 * known-lib-1102 (GOAL N5b / N10; {@link HonestLabel.Spec#knownLib}): the root's known own-library positions
 * ({@link KnownOwnLibrary}, from the live game's events) are kept in every belief world, and the label solver plans the
 * lines that draw them and cast them. The tracker's mechanism (put-backs, Doomsday's pile, Ponder's keep, a tutor to the
 * top, bottoms, shuffles, the opponent's reorders, silent changes), then labels on the step-1 kill spec (K = 8): a
 * Doomsday pile drawn by Baleful Strix into Thassa's Oracle, a Brainstorm put-back drawn next turn, a Ponder keep, each
 * PROVEN only while the cards are known (a shuffle: not), with the q^8 watch (libLabels) reading no hidden card; an
 * unknown draw is not proven and byte-identical with the key on and off.
 */
public class KnownLibTest extends forge.ai.AITest {

    // ------------------------------------------------------------------ helpers

    /** Our seat's controller: orders library moves by {@code topFirst} (names, top first) and answers shuffles. */
    static final class Ordered extends PlayerControllerAi {
        final List<String> topFirst;
        final boolean shuffle;
        int orders = 0;

        Ordered(final Game g, final Player p, final List<String> topFirst, final boolean shuffle) {
            super(g, p, p.getController().getLobbyPlayer());
            this.topFirst = topFirst;
            this.shuffle = shuffle;
        }

        @Override
        public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType dest, final SpellAbility sa) {
            if (dest != ZoneType.Library || topFirst == null) {
                return super.orderMoveToZoneList(cards, dest, sa);
            }
            orders++;
            final List<Card> l = new ArrayList<>();
            for (Card c : cards) {
                l.add(c);
            }
            l.sort((a, b) -> Integer.compare(rank(a), rank(b)));
            // RearrangeTopOfLibrary moves each to the top in list order: the last one ends on top
            Collections.reverse(l);
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
                return shuffle;
            }
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }
    }

    static Game game(final String hand, final String bf, final String lib, final int oppLife) {
        final String frame = ForcedWinSolverTest.frame(hand, bf, 20, "", "", oppLife, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + lib);
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        g.AI_TIMEOUT = 600;
        return g;
    }

    static Player me(final Game g) {
        return g.getPlayers().get(0);
    }

    static KnownOwnLibrary track(final Game g) {
        final KnownOwnLibrary k = new KnownOwnLibrary(me(g));
        k.watch(g);
        return k;
    }

    static List<String> names(final Game g, final Set<Integer> ids) {
        final List<String> out = new ArrayList<>();
        for (Card c : me(g).getCardsIn(ZoneType.Library)) {
            if (ids.contains(c.getId())) {
                out.add(c.getName());
            }
        }
        return out;
    }

    static List<String> top(final Game g, final int n) {
        final List<String> out = new ArrayList<>();
        for (Card c : me(g).getCardsIn(ZoneType.Library)) {
            if (out.size() < n) {
                out.add(c.getName());
            }
        }
        return out;
    }

    static void castAndResolve(final Game g, final String name) {
        final Player a = me(g);
        final Card c = ChooseACardTest.find(a, ZoneType.Hand, name);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        Assert.assertTrue(((PlayerControllerAi) a.getController()).playChosenSpellAbility(sa), "cast " + name);
        MyRandom.setRandom(new Random(42));
        int guard = 0;
        while (!g.getStack().isEmpty() && guard++ < 10) {
            g.getStack().resolveStack();
            g.getAction().checkStateEffects(true);
        }
    }

    static JsonObject spec(final boolean knownLib, final boolean libLabels) {
        final JsonObject o = WinConditionCoverageTest.spec();
        if (knownLib) {
            o.addProperty("knownLib", true);
        }
        if (libLabels) {
            o.addProperty("libLabels", true);
        }
        return o;
    }

    /** The label of our seat's position; with knownLib, the tracker's known set is passed (as RlReplayLabeller does). */
    static JsonObject label(final Game g, final KnownOwnLibrary k, final boolean knownLib, final boolean libLabels,
            final String tag) {
        final HonestLabel.Spec spec = HonestLabel.Spec.parse(spec(knownLib, libLabels));
        final HonestLabel.Seat seat = new HonestLabel.Seat(spec);
        final Set<Integer> known = knownLib ? k.known(me(g)) : null;
        final JsonObject r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> seat.label(g, me(g), 42L, known)))
                .toJson();
        r.remove("ms");
        System.out.println("KLIB " + tag + " knownLib=" + knownLib + " libLabels=" + libLabels + " known="
                + (known == null ? null : names(g, known)) + " row=" + r);
        return r;
    }

    static String str(final JsonObject r, final String k) {
        return WinConditionCoverageTest.str(r, k);
    }

    // ------------------------------------------------------------------ the tracker

    @Test
    public void specKeyIsOffByDefaultAndOnlyInTheShaWhenOn() {
        final HonestLabel.Spec off = HonestLabel.Spec.parse(spec(false, false));
        final HonestLabel.Spec on = HonestLabel.Spec.parse(spec(true, false));
        Assert.assertFalse(off.knownLib);
        Assert.assertFalse(off.toJson().has("knownLib"));
        Assert.assertTrue(on.knownLib);
        Assert.assertNotEquals(on.sha(), off.sha());
        final JsonObject explicitOff = spec(false, false);
        explicitOff.addProperty("knownLib", false);
        Assert.assertEquals(HonestLabel.Spec.parse(explicitOff).sha(), off.sha(), "knownLib false = the base sha");
    }

    /** Brainstorm: draw three, put two back: both known on top; the draws keep the rest known; a shuffle forgets. */
    @Test
    public void putBacksAreKnownUntilAShuffle() {
        final Game g = game("Baleful Strix", "Island", "Lightning Bolt;Island;Swamp;Island;Swamp;Island;Swamp;Island", 20);
        final KnownOwnLibrary k = track(g);
        Assert.assertTrue(k.known(me(g)).isEmpty());
        me(g).drawCards(3);
        Assert.assertTrue(k.known(me(g)).isEmpty(), "draws teach nothing about the rest");
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Swamp"), 0, null);
        Assert.assertEquals(names(g, k.known(me(g))), List.of("Swamp", "Lightning Bolt"));
        Assert.assertEquals(k.topCount(me(g)), 2);
        me(g).drawCards(1);
        Assert.assertEquals(names(g, k.known(me(g))), List.of("Lightning Bolt"), "the next turn's draw takes the first");
        me(g).shuffle(null);
        Assert.assertTrue(k.known(me(g)).isEmpty(), "a shuffle forgets every position");
    }

    /** A card put on the bottom from a seen zone is known relative to the bottom; draws do not move it. */
    @Test
    public void bottomsAreKnownAndAnUnknownInsertVoidsWhatItShifts() {
        final Game g = game("Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island", 20);
        final KnownOwnLibrary k = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), -1, null);
        me(g).drawCards(2);
        Assert.assertEquals(names(g, k.known(me(g))), List.of("Lightning Bolt"), "the bottom stays known");
        // a hidden card moved within the library to the bottom (a dig's rest, a scry bottom: maybe in a random order)
        // is unknown, and the known bottom above it no longer is known relative to the bottom
        final Card hidden = me(g).getCardsIn(ZoneType.Library).get(0);
        g.getAction().moveToLibrary(hidden, -1, null);
        Assert.assertTrue(k.known(me(g)).isEmpty(), names(g, k.known(me(g))).toString());
        // a card put on top from our hand is known; an unexplained reorder forgets it
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Shock"), 0, null);
        Assert.assertEquals(names(g, k.known(me(g))), List.of("Shock"));
        final List<Card> order = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
        Collections.swap(order, 0, 1);
        me(g).getZone(ZoneType.Library).setCards(order);
        Assert.assertTrue(k.known(me(g)).isEmpty(), "a silent change forgets everything");
        Assert.assertTrue(k.silent > 0);
    }

    /** Library-to-library moves outside our own resolving spell (the opponent's Portent, a stray move) are unknown. */
    @Test
    public void aMoveWithinTheLibraryNotOursIsUnknown() {
        final Game g = game("", "Island", "Island;Swamp;Lightning Bolt;Swamp;Island", 20);
        final KnownOwnLibrary k = track(g);
        final Card bolt = ChooseACardTest.find(me(g), ZoneType.Library, "Lightning Bolt");
        g.getAction().moveToLibrary(bolt, 0, null);
        Assert.assertTrue(k.known(me(g)).isEmpty(), "no spell of ours resolving: nobody chose and saw it");
    }

    /** Doomsday: the pile (searched and rearranged by our own resolving spell) is known; a shuffle after forgets it. */
    @Test
    public void aDoomsdayPileIsKnown() {
        for (boolean shuffled : new boolean[] {false, true}) {
            final Game g = game("Doomsday", "Swamp;Swamp;Swamp", "Island;Thassa's Oracle;Island", 20);
            final Ordered c = new Ordered(g, me(g), List.of("Thassa's Oracle", "Island"), false);
            me(g).dangerouslySetController(c);
            final KnownOwnLibrary k = track(g);
            castAndResolve(g, "Doomsday");
            Assert.assertEquals(me(g).getCardsIn(ZoneType.Library).size(), 3, "the whole library is the pile");
            Assert.assertEquals(top(g, 1), List.of("Thassa's Oracle"), "ordered: " + top(g, 3) + " orders=" + c.orders);
            if (shuffled) {
                me(g).shuffle(null);
            }
            Assert.assertEquals(k.known(me(g)).size(), shuffled ? 0 : 3, "shuffled=" + shuffled + " " + names(g, k.known(me(g))));
        }
    }

    /** Ponder: the two kept cards are known after its draw; "shuffle" forgets them. */
    @Test
    public void aPonderKeepIsKnown() {
        for (boolean shuffled : new boolean[] {false, true}) {
            final Game g = game("Ponder", "Island", "Island;Lightning Bolt;Swamp;Island;Swamp;Island;Swamp", 20);
            me(g).dangerouslySetController(new Ordered(g, me(g), List.of("Island", "Lightning Bolt", "Swamp"), shuffled));
            final KnownOwnLibrary k = track(g);
            castAndResolve(g, "Ponder");
            Assert.assertEquals(k.known(me(g)).size(), shuffled ? 0 : 2, "shuffled=" + shuffled + " " + top(g, 3));
            if (!shuffled) {
                Assert.assertEquals(top(g, 2), List.of("Lightning Bolt", "Swamp"), "kept in our order, the Island drawn");
            }
        }
    }

    /** Vampiric Tutor: search, shuffle, then the card on top: the shuffle comes first, the card is known. */
    @Test
    public void aTutorToTheTopIsKnown() {
        final Game g = game("Vampiric Tutor", "Swamp", "Island;Swamp;Island;Lightning Bolt;Island;Swamp", 20);
        me(g).dangerouslySetController(new Ordered(g, me(g), null, false));
        final KnownOwnLibrary k = track(g);
        castAndResolve(g, "Vampiric Tutor");
        final Set<Integer> known = k.known(me(g));
        Assert.assertEquals(known.size(), 1, top(g, 3).toString());
        Assert.assertTrue(known.contains(me(g).getCardsIn(ZoneType.Library).get(0).getId()), "the tutored card on top");
    }

    // ------------------------------------------------------------------ labels

    /**
     * Doomsday cast earlier: the pile is Oracle, Island, Island. Baleful Strix draws the Oracle, then Oracle wins with
     * devotion 3 against a library of 2. PROVEN only when the pile is known (knownLib on), with no q^8 read; with the
     * pile unknown (the key off: the base) or shuffled, not proven (the Oracle is on top in a third of the worlds).
     */
    @Test(timeOut = 900000)
    public void doomsdayPileThenStrixDrawsTheOracle() {
        for (boolean shuffled : new boolean[] {false, true}) {
            final Game g = game("Doomsday;Baleful Strix", "Swamp;Swamp;Swamp;Underground Sea;Island;Island;Island",
                    "Island;Island;Thassa's Oracle", 20);
            me(g).dangerouslySetController(new Ordered(g, me(g), List.of("Thassa's Oracle", "Island"), false));
            final KnownOwnLibrary k = track(g);
            // Doomsday picks from library and graveyard: the library holds exactly three and the graveyard none, so the
            // pile is the whole library, in our order
            castAndResolve(g, "Doomsday");
            if (shuffled) {
                me(g).shuffle(null);
            }
            final List<String> lib = top(g, 10);
            System.out.println("KLIB doomsday shuffled=" + shuffled + " library=" + lib + " gy="
                    + me(g).getCardsIn(ZoneType.Graveyard));
            if (!shuffled) {
                Assert.assertEquals(lib.get(0), "Thassa's Oracle", lib.toString());
            }
            final JsonObject off = label(g, k, false, false, "dd-off");
            final JsonObject on = label(g, k, true, false, "dd-on");
            final JsonObject onQ8 = label(g, k, true, true, "dd-on-q8");
            Assert.assertNotEquals(str(off, "label"), "win", "base: the pile is reshuffled away: " + off);
            if (shuffled) {
                Assert.assertNotEquals(str(on, "label"), "win", "a shuffled pile is unknown: " + on);
                Assert.assertNotEquals(str(onQ8, "label"), "win", onQ8.toString());
                Assert.assertFalse(on.has("known_top"), on.toString());
            } else {
                Assert.assertEquals(str(on, "label"), "win", on.toString());
                Assert.assertEquals(on.get("known_top").getAsInt(), lib.size(), on.toString());
                Assert.assertEquals(str(onQ8, "label"), "win", onQ8.toString());
                Assert.assertFalse(onQ8.has("lib_tag"), "drawing the known pile is no read: " + onQ8);
            }
        }
    }

    /**
     * Brainstorm's put-back, drawn the next turn: Bolt and an Island put back (Island on top); the next draw step takes
     * the Island; Baleful Strix then draws the known Bolt for their last 3. PROVEN with the key on; not with it off; not
     * after a shuffle.
     */
    @Test(timeOut = 900000)
    public void aBrainstormPutBackIsDrawnTheNextTurn() {
        for (boolean shuffled : new boolean[] {false, true}) {
            final Game g = game("Baleful Strix", "Underground Sea;Island;Mountain",
                    "Lightning Bolt;Island;Island;Swamp;Island;Swamp;Island;Swamp;Island;Swamp", 3);
            final KnownOwnLibrary k = track(g);
            me(g).drawCards(3); // Brainstorm's draws
            g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
            g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Island"), 0, null);
            me(g).drawCards(1); // the next turn's draw step
            if (shuffled) {
                me(g).shuffle(null);
            }
            final JsonObject off = label(g, k, false, false, "bs-off");
            final JsonObject on = label(g, k, true, true, "bs-on-q8");
            Assert.assertNotEquals(str(off, "label"), "win", off.toString());
            if (shuffled) {
                Assert.assertNotEquals(str(on, "label"), "win", on.toString());
            } else {
                Assert.assertEquals(top(g, 1), List.of("Lightning Bolt"));
                Assert.assertEquals(str(on, "label"), "win", on.toString());
                Assert.assertFalse(on.has("lib_tag"), on.toString());
            }
        }
    }

    /** Ponder keeps Island, Bolt on top and draws the Island; Strix then draws the known Bolt for their last 3. */
    @Test(timeOut = 900000)
    public void aPonderKeepIsDrawn() {
        for (boolean shuffled : new boolean[] {false, true}) {
            final Game g = game("Ponder;Baleful Strix", "Island;Underground Sea;Underground Sea;Mountain",
                    "Island;Lightning Bolt;Swamp;Island;Swamp;Island;Swamp;Island;Swamp", 3);
            me(g).dangerouslySetController(new Ordered(g, me(g), List.of("Island", "Lightning Bolt", "Swamp"), shuffled));
            final KnownOwnLibrary k = track(g);
            castAndResolve(g, "Ponder");
            final JsonObject off = label(g, k, false, false, "ponder-off");
            final JsonObject on = label(g, k, true, true, "ponder-on-q8");
            Assert.assertNotEquals(str(off, "label"), "win", off.toString());
            if (shuffled) {
                Assert.assertNotEquals(str(on, "label"), "win", on.toString());
            } else {
                Assert.assertEquals(top(g, 1), List.of("Lightning Bolt"));
                Assert.assertEquals(str(on, "label"), "win", on.toString());
                Assert.assertFalse(on.has("lib_tag"), on.toString());
            }
        }
    }

    /**
     * The control: the Bolt is somewhere in our library and nothing is known. Strix's draw is an unknown card: not proven,
     * and the row is byte-identical with knownLib on and off.
     */
    @Test(timeOut = 900000)
    public void anUnknownDrawIsNotProvenAndTheRowIsUnchanged() {
        final Game g = game("Baleful Strix", "Underground Sea;Island;Mountain",
                "Lightning Bolt;Island;Island;Swamp;Island;Swamp;Island;Swamp;Island;Swamp", 3);
        final KnownOwnLibrary k = track(g);
        final JsonObject off = label(g, k, false, true, "unknown-off");
        final JsonObject on = label(g, k, true, true, "unknown-on");
        Assert.assertNotEquals(str(on, "label"), "win", on.toString());
        Assert.assertEquals(on, off, "nothing known: the same row");
    }
}
