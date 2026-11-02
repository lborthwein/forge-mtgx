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

    /**
     * A card put on the bottom from a seen zone is known relative to the bottom; draws do not move it; a hidden card
     * moved from the top to the bottom (a fateseal) stays unknown but shifts the bottom by one, publicly; an unexplained
     * reorder forgets everything.
     */
    @Test
    public void bottomsAreKnownAndASilentChangeForgets() {
        final Game g = game("Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island", 20);
        final KnownOwnLibrary k = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), -1, null);
        me(g).drawCards(2);
        Assert.assertEquals(names(g, k.known(me(g))), List.of("Lightning Bolt"), "the bottom stays known");
        final Card hidden = me(g).getCardsIn(ZoneType.Library).get(0);
        g.getAction().moveToLibrary(hidden, -1, null);
        Assert.assertEquals(names(g, k.known(me(g))), List.of("Lightning Bolt"), "an unknown card under it: still one up");
        Assert.assertFalse(k.known(me(g)).contains(hidden.getId()), "the moved card itself was never seen");
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Shock"), 0, null);
        Assert.assertEquals(names(g, k.known(me(g))), List.of("Shock", "Lightning Bolt"));
        final List<Card> order = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
        Collections.swap(order, 0, 1);
        me(g).getZone(ZoneType.Library).setCards(order);
        Assert.assertTrue(k.known(me(g)).isEmpty(), "a silent change forgets everything");
        Assert.assertTrue(k.silent > 0);
    }

    /** A hidden card taken from the middle (a tutor's take) voids a known card with unknown cards above it. */
    @Test
    public void aHiddenTakeFromTheMiddleVoidsWhatItCouldShift() {
        final Game g = game("", "Island", "Island;Swamp;Lightning Bolt;Swamp;Island;Swamp;Island", 20);
        final KnownOwnLibrary k = track(g);
        final Card c = ChooseACardTest.find(me(g), ZoneType.Battlefield, "Island");
        g.getAction().moveToLibrary(c, 2, null); // a public card put third from the top (no resolution: an offset of 2)
        // outside any resolution a middle place has no named offset: unknown
        Assert.assertTrue(k.known(me(g)).isEmpty(), names(g, k.known(me(g))).toString());
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
     * The control: the Bolt is somewhere in our library and nothing is known. Strix's draw is an unknown card: not proven
     * either way; with knownLib the row carries the line's exact-draw fraction (per-draw planning on a small library).
     */
    @Test(timeOut = 900000)
    public void anUnknownDrawIsNotProvenAndTheRowIsUnchanged() {
        final Game g = game("Baleful Strix", "Underground Sea;Island;Mountain",
                "Lightning Bolt;Island;Island;Swamp;Island;Swamp;Island;Swamp;Island;Swamp", 3);
        final KnownOwnLibrary k = track(g);
        final JsonObject off = label(g, k, false, true, "unknown-off");
        final JsonObject on = label(g, k, true, true, "unknown-on");
        Assert.assertNotEquals(str(off, "label"), "win", off.toString());
        Assert.assertFalse(off.has("lib_tag"), "the base: no line at all " + off);
        Assert.assertNotEquals(str(on, "label"), "win", on.toString());
        // the library is a small set (Bolt and two land names): knownLib plans per draw and labels the line by its exact
        // fraction: Strix's draw plus the next turn's draw find the one Bolt among ten with p = 1 - 9/10 * 8/9 = 0.2
        Assert.assertEquals(str(on, "lib_tag"), "exact-draw", on.toString());
        Assert.assertEquals(on.get("p_win").getAsDouble(), 0.2, 1e-9, on.toString());
    }

    // ------------------------------------------------------------------ review #163 S1-1 / S3-1: orders nobody of ours chose

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

    static Player opp(final Game g) {
        return g.getPlayers().get(1);
    }

    /**
     * Cascade (Shardless Agent): the exiled cards go to the bottom in a random order (ChangeZoneAll RandomOrder, built
     * in): their order is never learnt, though the trigger is ours.
     */
    @Test
    public void aCascadeBottomIsNotKnown() {
        final Game g = game("Shardless Agent", "Tropical Island;Island;Forest",
                "Island;Swamp;Mountain;Lightning Bolt;Island;Swamp;Island;Swamp;Island;Swamp", 20);
        me(g).dangerouslySetController(new Ordered(g, me(g), null, false));
        final KnownOwnLibrary k = track(g);
        playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Shardless Agent").getFirstSpellAbility(), null);
        final List<String> lib = top(g, 20);
        System.out.println("KLIB cascade library=" + lib + " known=" + names(g, k.known(me(g))));
        Assert.assertTrue(lib.subList(lib.size() - 3, lib.size()).containsAll(List.of("Island", "Swamp", "Mountain")),
                "the three lands went to the bottom: " + lib);
        Assert.assertTrue(k.known(me(g)).isEmpty(), "a random order is never learnt: " + names(g, k.known(me(g))));
    }

    /**
     * The opponent's Endurance targeting us: our graveyard to the bottom of our library in a random order: unknown (their
     * resolution, and a random order); our own Endurance on ourselves: unknown too (a random order).
     */
    @Test
    public void anEnduranceBottomIsNotKnown() {
        for (boolean theirs : new boolean[] {true, false}) {
            final String frame = ForcedWinSolverTest.frame(theirs ? "" : "Endurance", theirs ? "Island" : "Forest;Forest;Forest",
                    20, theirs ? "Endurance" : "", theirs ? "Forest;Forest;Forest" : "", 20, "human")
                    .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island")
                    .replace("humangraveyard=", "humangraveyard=Lightning Bolt;Shock;Giant Growth");
            final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
            final KnownOwnLibrary k = track(g);
            final Player caster = theirs ? opp(g) : me(g);
            final Player target = me(g);
            playAndResolve(g, caster, ChooseACardTest.find(caster, ZoneType.Hand, "Endurance").getFirstSpellAbility(), sa -> {
                if (sa != null && sa.isTrigger() && sa.usesTargeting()) {
                    sa.resetTargets();
                    sa.getTargets().add(target);
                }
            });
            final List<String> lib = top(g, 20);
            System.out.println("KLIB endurance theirs=" + theirs + " library=" + lib + " known=" + names(g, k.known(me(g))));
            Assert.assertTrue(me(g).getCardsIn(ZoneType.Graveyard).isEmpty(), "our graveyard went to the bottom");
            Assert.assertEquals(lib.size(), 8, lib.toString());
            Assert.assertTrue(k.known(me(g)).isEmpty(), "theirs=" + theirs + ": " + names(g, k.known(me(g))));
        }
    }

    /** S3-1: their Agonizing Memories puts two cards of our hand on top in THEIR order: the order is unknown to us. */
    @Test
    public void theirOrderOfOurHandIsNotKnown() {
        final String frame = ForcedWinSolverTest.frame("Lightning Bolt;Shock;Giant Growth", "Island", 20, "Agonizing Memories",
                "Swamp;Swamp;Swamp;Swamp", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island");
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        final KnownOwnLibrary k = track(g);
        final SpellAbility sa = ChooseACardTest.find(opp(g), ZoneType.Hand, "Agonizing Memories").getFirstSpellAbility();
        sa.setActivatingPlayer(opp(g));
        sa.resetTargets();
        sa.getTargets().add(me(g));
        playAndResolve(g, opp(g), sa, null);
        System.out.println("KLIB agonizing library=" + top(g, 3) + " hand=" + me(g).getCardsIn(ZoneType.Hand));
        Assert.assertEquals(me(g).getCardsIn(ZoneType.Hand).size(), 1, "two of our cards went to the top");
        Assert.assertTrue(k.known(me(g)).isEmpty(), names(g, k.known(me(g))).toString());
    }

    /** The control: our own Brainstorm (we choose the two and their order) is still learnt. */
    @Test
    public void ourBrainstormPutBackIsStillKnown() {
        final Game g = game("Brainstorm;Lightning Bolt;Shock", "Island", "Island;Swamp;Island;Swamp;Island;Swamp", 20);
        me(g).dangerouslySetController(new Ordered(g, me(g), null, false));
        final KnownOwnLibrary k = track(g);
        playAndResolve(g, me(g), ChooseACardTest.find(me(g), ZoneType.Hand, "Brainstorm").getFirstSpellAbility(), null);
        System.out.println("KLIB brainstorm library=" + top(g, 3) + " known=" + names(g, k.known(me(g))));
        Assert.assertEquals(k.topCount(me(g)), 2, "the two put-backs, in our order");
    }

    // ------------------------------------------------------------------ same-turn Doomsday, and a fetch after the pile

    /**
     * The HOF row 2988847130 (Doomsday this turn after Thassa's Oracle's look): the look at the top hidden cards was a
     * read, though Doomsday's search then rebuilt the library from cards chosen by identity and exiled the rest. With
     * knownLib the whole-library reset erases the looks: no read. A look whose card is drawn before the reset still is.
     */
    @Test
    public void aWholeLibraryResetErasesLooks() {
        for (boolean erase : new boolean[] {false, true}) {
            final Game g = LibLabelsTest.lib(20);
            final ForcedWinSolver.HiddenWatch hw = LibLabelsTest.watch(g, 0);
            hw.erase = erase;
            final Player me = me(g);
            // Oracle's look at the top seven, one kept on top and the rest bottomed
            final List<Card> seven = new ArrayList<>(me.getCardsIn(ZoneType.Library).subList(0, 7));
            hw.peekOwnLibrary(seven);
            for (Card c : seven.subList(1, 7)) {
                g.getAction().moveToLibrary(c, -1, null);
            }
            // Doomsday: five searched to the top, the rest exiled
            final List<Card> pile = new ArrayList<>();
            for (int i : new int[] {17, 3, 11, 8, 0}) {
                pile.add(me.getCardsIn(ZoneType.Library).get(i));
            }
            for (Card c : pile) {
                hw.searchPick(List.of(ZoneType.Graveyard, ZoneType.Library), c);
                g.getAction().moveToLibrary(c, 0, null);
            }
            for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Library))) {
                if (!pile.contains(c)) {
                    g.getAction().exile(c, null, forge.game.ability.AbilityKey.newMap());
                }
            }
            me.drawCards(2);
            final int reads = LibLabelsTest.reads(hw);
            System.out.println("KLIB reset erase=" + erase + " reads=" + reads + " why=" + hw.why);
            Assert.assertEquals(reads == 0, erase, "erase=" + erase + " reads=" + reads);
        }
        // a looked card drawn before the reset was used: still a read
        final Game g = LibLabelsTest.lib(20);
        final ForcedWinSolver.HiddenWatch hw = LibLabelsTest.watch(g, 0);
        hw.erase = true;
        hw.peekOwnLibrary(new ArrayList<>(me(g).getCardsIn(ZoneType.Library).subList(0, 3)));
        me(g).drawCards(1);
        for (Card c : new ArrayList<>(me(g).getCardsIn(ZoneType.Library))) {
            g.getAction().exile(c, null, forge.game.ability.AbilityKey.newMap());
        }
        Assert.assertTrue(LibLabelsTest.reads(hw) >= 1, "the drawn looked card stays a read");
    }

    /**
     * Same-turn Doomsday into a draw into the win (the coverage suite's doomsday-recall-oracle and doomsday-jace-recall,
     * and Thassa's Oracle looked first): PROVEN with knownLib and libLabels on, no lib_tag.
     */
    @Test(timeOut = 900000)
    public void sameTurnDoomsdayIsProven() {
        final List<WinConditionCoverageTest.P> ps = new ArrayList<>(List.of(LibLabelsTest.puzzle("doomsday-recall-oracle"),
                LibLabelsTest.puzzle("doomsday-jace-recall")));
        ps.add(new WinConditionCoverageTest.P("oracle-look-then-doomsday", WinConditionCoverageTest.V.PROVEN,
                "Thassa's Oracle looks first, then Doomsday, Ancestral Recall, a second Oracle")
                .k("humanhand", "Thassa's Oracle;Doomsday;Ancestral Recall;Thassa's Oracle")
                .k("humanbattlefield", WinConditionCoverageTest.n("Underground Sea", 8))
                .k("humanlibrary", WinConditionCoverageTest.n("Island", 15)));
        for (WinConditionCoverageTest.P p : ps) {
            final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
            final KnownOwnLibrary k = track(g);
            final JsonObject on = label(g, k, true, true, p.name);
            Assert.assertEquals(str(on, "label"), "win", p.name + " " + on);
            Assert.assertFalse(on.has("lib_tag"), p.name + " " + on);
        }
    }

    /**
     * The owner's case: a Doomsday pile of five (Oracle and four Islands), then a fetch (Polluted Delta takes an Island
     * and shuffles): the library is the known SET of four, order unknown. Strix (one draw) then Oracle needs the Oracle
     * on top: exact-draw 1/4 when it must win this turn (their three attackers are lethal), 1/2 when it can also wait
     * for next turn's draw (the better line). Divination (two draws) then Oracle: 1/2. Never PROVEN, never sampled.
     * Without the fetch the pile is known and each line is PROVEN.
     */
    @Test(timeOut = 900000)
    public void aFetchAfterThePileIsAnExactFraction() {
        // Delver of Secrets adds one blue devotion, so the unshuffled pile of five also wins (the control).
        // {our card, their board, our life, fraction, takes}: Strix with their three attackers lethal next turn must win
        // now (one draw: 1/4); unthreatened, Strix can also wait for next turn's draw (two draws: 1/2, the better line);
        // Divination draws two now (1/2)
        final String[][] cases = {{"Baleful Strix", "Memnite;Memnite;Memnite", "4", "0.25", "1"}, {"Baleful Strix", "", "20", "0.5", "2"},
                {"Divination", "", "20", "0.5", "2"}};
        for (String[] cs : cases) for (boolean fetch : new boolean[] {true, false}) {
            final String frame = ForcedWinSolverTest.frame("Doomsday;" + cs[0],
                    "Delver of Secrets;Swamp;Swamp;Swamp;Swamp;Polluted Delta;Island;Island;Island;Island", Integer.parseInt(cs[2]), "", cs[1], 20, "human")
                    .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island;Island;Island;Thassa's Oracle");
            final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
            g.AI_TIMEOUT = 600;
            me(g).dangerouslySetController(new Ordered(g, me(g), List.of("Thassa's Oracle", "Island"), false));
            final KnownOwnLibrary k = track(g);
            castAndResolve(g, "Doomsday");
            Assert.assertEquals(me(g).getCardsIn(ZoneType.Library).size(), 5);
            Assert.assertEquals(k.known(me(g)).size(), 5);
            if (fetch) {
                final Card delta = ChooseACardTest.find(me(g), ZoneType.Battlefield, "Polluted Delta");
                final SpellAbility crack = delta.getSpellAbilities().stream().filter(SpellAbility::isActivatedAbility)
                        .findFirst().orElseThrow();
                playAndResolve(g, me(g), crack, null);
                Assert.assertEquals(me(g).getCardsIn(ZoneType.Library).size(), 4, "the fetch took an Island");
                Assert.assertTrue(k.known(me(g)).isEmpty(), "shuffled");
            }
            final List<String> lib = top(g, 10);
            Assert.assertEquals(new java.util.TreeSet<>(lib), new java.util.TreeSet<>(List.of("Island", "Thassa's Oracle")),
                    "the pool is the remaining pile: " + lib);
            final JsonObject on = label(g, k, true, true, "fetch-" + cs[0] + "-" + cs[1] + "-" + fetch);
            if (fetch) {
                Assert.assertNotEquals(str(on, "label"), "win", on.toString());
                Assert.assertEquals(str(on, "lib_tag"), "exact-draw", on.toString());
                Assert.assertEquals(on.get("p_win").getAsDouble(), Double.parseDouble(cs[3]), 1e-9, on.toString());
                Assert.assertEquals(on.get("lib_takes").getAsInt(), Integer.parseInt(cs[4]), on.toString());
            } else {
                Assert.assertEquals(str(on, "label"), "win", on.toString());
                Assert.assertFalse(on.has("lib_tag"), on.toString());
            }
        }
    }

    // ------------------------------------------------------------------ owner 2026-11-02: the opponent's fixed placements

    /** Their Memory Lapse counters our Bolt: it goes on top of our library (public card, fixed place): known; drawn next. */
    @Test
    public void theirMemoryLapseTopIsKnown() {
        final String frame = ForcedWinSolverTest.frame("Lightning Bolt", "Mountain", 20, "Memory Lapse", "Island;Island", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island");
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        final KnownOwnLibrary k = track(g);
        final SpellAbility bolt = ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt").getFirstSpellAbility();
        bolt.setActivatingPlayer(me(g));
        bolt.resetTargets();
        bolt.getTargets().add(opp(g));
        Assert.assertTrue(((PlayerControllerAi) me(g).getController()).playChosenSpellAbility(bolt));
        final SpellAbility ml = ChooseACardTest.find(opp(g), ZoneType.Hand, "Memory Lapse").getFirstSpellAbility();
        ml.setActivatingPlayer(opp(g));
        ml.resetTargets();
        ml.getTargets().add(g.getStack().peekAbility());
        playAndResolve(g, opp(g), ml, null);
        System.out.println("KLIB memory-lapse library=" + top(g, 3) + " known=" + names(g, k.known(me(g))));
        Assert.assertEquals(top(g, 1), List.of("Lightning Bolt"), "countered onto our top");
        Assert.assertEquals(names(g, k.known(me(g))), List.of("Lightning Bolt"));
        me(g).drawCards(1);
        Assert.assertNotNull(ForcedWinSolverTest.inZone(me(g), ZoneType.Hand, "Lightning Bolt"), "drawn next turn");
    }

    /**
     * Their Teferi, Hero of Dominaria -3 on our Baleful Strix: third from the top (a fixed offset under two unknown
     * cards): known at index 2, then at index 0 after two draws; with a mill of one in between, at index 1 then 0.
     */
    @Test
    public void theirTeferiThirdFromTheTopIsKnown() {
        for (boolean mill : new boolean[] {false, true}) {
            final String frame = ForcedWinSolverTest.frame("", "Baleful Strix;Island", 20, "",
                    "Teferi, Hero of Dominaria|Counters:LOYALTY=4", 20, "human")
                    .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island;Swamp");
            final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
            final KnownOwnLibrary k = track(g);
            final Card teferi = ChooseACardTest.find(opp(g), ZoneType.Battlefield, "Teferi, Hero of Dominaria");
            final SpellAbility minus3 = teferi.getSpellAbilities().stream()
                    .filter(x -> x.getApi() == forge.game.ability.ApiType.ChangeZone).findFirst().orElseThrow();
            minus3.setActivatingPlayer(opp(g));
            minus3.resetTargets();
            minus3.getTargets().add(ChooseACardTest.find(me(g), ZoneType.Battlefield, "Baleful Strix"));
            playAndResolve(g, opp(g), minus3, null);
            final List<Card> lib = new ArrayList<>(me(g).getCardsIn(ZoneType.Library));
            System.out.println("KLIB teferi mill=" + mill + " library=" + top(g, 4) + " known=" + names(g, k.known(me(g))));
            Assert.assertEquals(lib.get(2).getName(), "Baleful Strix", "third from the top");
            Assert.assertEquals(k.known(me(g)), java.util.Set.of(lib.get(2).getId()), "known at index 2, the two above unknown");
            if (mill) {
                g.getAction().moveToGraveyard(me(g).getCardsIn(ZoneType.Library).get(0), null);
                Assert.assertEquals(k.known(me(g)), java.util.Set.of(me(g).getCardsIn(ZoneType.Library).get(1).getId()),
                        "after a mill of one: index 1");
                me(g).drawCards(1);
            } else {
                me(g).drawCards(2);
            }
            Assert.assertEquals(k.known(me(g)), java.util.Set.of(me(g).getCardsIn(ZoneType.Library).get(0).getId()),
                    "after the draws: on top");
            Assert.assertEquals(top(g, 1), List.of("Baleful Strix"));
        }
    }

    /** The control: their Portent reorders our top three (we see neither the cards nor their order): unknown. */
    @Test
    public void theirReorderOfOurTopIsNotKnown() {
        final String frame = ForcedWinSolverTest.frame("Lightning Bolt;Shock", "Island", 20, "Portent", "Island", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Swamp;Island;Swamp;Island;Swamp");
        final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
        final KnownOwnLibrary k = track(g);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Lightning Bolt"), 0, null);
        g.getAction().moveToLibrary(ChooseACardTest.find(me(g), ZoneType.Hand, "Shock"), 0, null);
        Assert.assertEquals(k.topCount(me(g)), 2, "our two put-backs");
        final SpellAbility portent = ChooseACardTest.find(opp(g), ZoneType.Hand, "Portent").getFirstSpellAbility();
        portent.setActivatingPlayer(opp(g));
        portent.resetTargets();
        portent.getTargets().add(me(g));
        playAndResolve(g, opp(g), portent, null);
        System.out.println("KLIB portent library=" + top(g, 4) + " known=" + names(g, k.known(me(g))));
        Assert.assertTrue(k.known(me(g)).isEmpty(), "their order of our top three: " + names(g, k.known(me(g))));
    }

    /** Pinned positions: the solver's shuffle keeps a pinned card at its index; the q^8 watch does not read it. */
    @Test
    public void pinnedPositionsSurviveShufflesAndAreNotReads() {
        final Game g = LibLabelsTest.lib(12);
        final Player me = me(g);
        final Card third = me.getCardsIn(ZoneType.Library).get(2);
        for (long seed = 1; seed <= 20; seed++) {
            ForcedWinSolver.shuffleUnknownOwnLibrary(me, 0, java.util.Set.of(third.getId()), new Random(seed));
            Assert.assertEquals(me.getCardsIn(ZoneType.Library).get(2).getId(), third.getId(), "seed " + seed);
        }
        final ForcedWinSolver.HiddenWatch hw = new ForcedWinSolver.HiddenWatch(me, 0, java.util.Set.of(third.getId()), null);
        g.subscribeToEvents(hw);
        me.drawCards(3);
        Assert.assertEquals(LibLabelsTest.reads(hw), 2, "the two above are reads, the pinned third is not");
    }
}
