package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.bench.PlayerControllerBridge;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/**
 * e1-q8-1101 (DESIGN-R3 ruling 2026-11-01 06:40:01, the q^8 blocker): PROVEN only for lines that read no hidden card of
 * the seat's own library ({@link HonestLabel.Spec#libLabels}). The watch's mechanism ({@link ForcedWinSolver.HiddenWatch}
 * on a live game: draws, put-backs, searches, shuffles, exile, peeks, silent changes), the labels (a Doomsday pile and a
 * no-dependence control unchanged, a deep self-mill and an Ad Nauseam dig sampled, a tutor in the window sampled, two
 * unknown draws exact) and the solver's exact-draw fraction (two unknown draws, one of them must be the Mountain).
 */
public class LibLabelsTest extends forge.ai.AITest {

    // ------------------------------------------------------------------ the watch's mechanism

    /** Our seat ("human") with {@code n} distinct library cards (top first: L0, L1, ...: Island/Swamp alternating). */
    static Game lib(final int n) {
        final List<String> cards = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cards.add(i % 2 == 0 ? "Island" : "Swamp");
        }
        final String frame = ForcedWinSolverTest.frame("Lightning Bolt;Giant Growth;Shock", "Mountain", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + String.join(";", cards));
        return ForcedWinSolverTest.game("Default", "Default", frame);
    }

    static Player me(final Game g) {
        return g.getPlayers().get(0);
    }

    static ForcedWinSolver.HiddenWatch watch(final Game g, final int knownTop) {
        final ForcedWinSolver.HiddenWatch hw = new ForcedWinSolver.HiddenWatch(me(g), knownTop, null);
        g.subscribeToEvents(hw);
        return hw;
    }

    static Card at(final Game g, final int i) {
        return me(g).getCardsIn(ZoneType.Library).get(i);
    }

    static Card hand(final Game g, final String name) {
        return ForcedWinSolverTest.inZone(me(g), ZoneType.Hand, name);
    }

    static int reads(final ForcedWinSolver.HiddenWatch hw) {
        hw.end();
        return hw.takes();
    }

    @Test
    public void drawingAHiddenCardIsARead() {
        final Game g = lib(10);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 0);
        me(g).drawCards(1);
        Assert.assertEquals(reads(hw), 1, hw.why);
        Assert.assertTrue(hw.dependent());
    }

    @Test
    public void drawingTheKnownTopIsNotARead() {
        final Game g = lib(10);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 2);
        me(g).drawCards(2);
        Assert.assertEquals(reads(hw), 0, hw.why);
        me(g).drawCards(1);
        Assert.assertEquals(reads(hw), 1, "the third draw is below the known top");
    }

    @Test
    public void aDeepSearchIsNotAReadButATutorInTheWindowFailsClosed() {
        final Game g = lib(30);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 0);
        final Card deep = at(g, 20);
        hw.searchPick(List.of(ZoneType.Library), deep);
        g.getAction().moveToHand(deep, null);
        Assert.assertEquals(reads(hw), 0, "a search picked a card 20 deep: identity, not order");
        final Card unsearched = at(g, 20);
        g.getAction().moveToHand(unsearched, null);
        Assert.assertEquals(reads(hw), 1, "a deep take no search picked fails closed");
        final Card near = at(g, 5);
        hw.searchPick(List.of(ZoneType.Library), near);
        g.getAction().moveToHand(near, null);
        Assert.assertEquals(reads(hw), 2, "a tutor in the top-12 window is a read (a false dependence, fail closed)");
        final Card bottom = at(g, me(g).getCardsIn(ZoneType.Library).size() - 1);
        hw.searchPick(List.of(ZoneType.Library), bottom);
        g.getAction().moveToHand(bottom, null);
        Assert.assertEquals(reads(hw), 3, "the bottom card is positional");
    }

    @Test
    public void aShuffleForgetsTheKnownTop() {
        final Game g = lib(10);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 5);
        me(g).shuffle(null);
        me(g).drawCards(1);
        Assert.assertEquals(reads(hw), 1, "known before the shuffle, hidden after it");
    }

    /** Brainstorm: draw three, put two back on top, draw them again: the put-backs are known. */
    @Test
    public void brainstormPutBacksAreKnown() {
        final Game g = lib(10);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 0);
        me(g).drawCards(3);
        Assert.assertEquals(reads(hw), 3, "Brainstorm's three draws from a hidden top");
        g.getAction().moveToLibrary(hand(g, "Lightning Bolt"), 0, null);
        g.getAction().moveToLibrary(hand(g, "Shock"), 0, null);
        me(g).drawCards(2);
        Assert.assertEquals(reads(hw), 3, "the two put-backs are drawn again: known, no new read");
        final Game g2 = lib(10);
        final ForcedWinSolver.HiddenWatch hw2 = watch(g2, 3);
        me(g2).drawCards(3);
        g2.getAction().moveToLibrary(hand(g2, "Giant Growth"), 0, null);
        g2.getAction().moveToLibrary(hand(g2, "Shock"), 0, null);
        me(g2).drawCards(2);
        Assert.assertEquals(reads(hw2), 0, "from a known top of three: no read at all");
    }

    /** Doomsday: five cards searched to the top (library -> library), the rest exiled, the pile rearranged, then drawn. */
    @Test
    public void aDoomsdayPileIsKnownAndItsExileIsNoRead() {
        final Game g = lib(20);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 0);
        final Player me = me(g);
        final List<Card> pile = new ArrayList<>();
        for (int i : new int[] {17, 3, 11, 8, 0}) {
            pile.add(at(g, i));
        }
        for (Card c : pile) {
            hw.searchPick(List.of(ZoneType.Graveyard, ZoneType.Library), c);
            g.getAction().moveToLibrary(c, 0, null);
        }
        for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Library))) {
            if (!pile.contains(c)) {
                g.getAction().exile(c, null, AbilityKey.newMap());
            }
        }
        Assert.assertEquals(me.getCardsIn(ZoneType.Library).size(), 5);
        // RearrangeTopOfLibrary (review #161 S1-1: orderMoveToZoneList, then moveToLibrary(next, 0) per card): moves of
        // known cards only; the rest's exile left no hidden card in the library, so it is no read
        for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Library))) {
            g.getAction().moveToLibrary(c, 0, null);
        }
        hw.peekOwnLibrary(me.getCardsIn(ZoneType.Library));
        me.drawCards(2);
        Assert.assertEquals(reads(hw), 0, hw.why);
        Assert.assertFalse(hw.dependent());
    }

    /**
     * Necropotence / an impulse draw / a dig's exile (review #161 S3-6): a hidden card exiled from the library while
     * hidden cards remain there is a read; moving on from exile to our hand adds none.
     */
    @Test
    public void aHiddenCardExiledFromTheLibraryIsARead() {
        final Game g = lib(10);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 0);
        final Card top = at(g, 0);
        final Card exiled = g.getAction().exile(top, null, AbilityKey.newMap());
        Assert.assertEquals(reads(hw), 1, "the exile of one hidden card with nine left");
        g.getAction().moveToHand(exiled, null);
        Assert.assertEquals(reads(hw), 1, "the same card reaching our hand");
    }

    /**
     * Review #161 S1-1: a hidden card the seat looks at and moves within the library is a read: a scry kept on top
     * (GameAction.scry: moveToLibrary per kept card), a card bottomed (a dig's rest), a reorder shown to the seat
     * (orderMoveToZoneList / arrangeForScry / tempShowCards); a known card moved is not, nor a card a search picked.
     */
    @Test
    public void looksAndMovesWithinTheLibraryAreReads() {
        final Game g = lib(10);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 1);
        g.getAction().moveToLibrary(at(g, 0), 0, null);
        Assert.assertEquals(reads(hw), 0, "the known top kept on top");
        g.getAction().moveToLibrary(at(g, 1), 0, null);
        Assert.assertEquals(reads(hw), 1, "a hidden card scried and kept on top");
        me(g).drawCards(1);
        Assert.assertEquals(reads(hw), 1, "drawing it adds no new card: it was read");
        g.getAction().moveToLibrary(at(g, 1), -1, null);
        Assert.assertEquals(reads(hw), 2, "a hidden card put on the bottom (a dig's rest, scry to the bottom)");
        final Card picked = at(g, 4);
        hw.searchPick(List.of(ZoneType.Library), picked);
        g.getAction().moveToLibrary(picked, 0, null);
        Assert.assertEquals(reads(hw), 2, "a searched card put on top (Doomsday's pile) is not a read");
        hw.peekOwnLibrary(List.of(at(g, 2), at(g, 3)));
        Assert.assertEquals(reads(hw), 4, "a reorder or scry shows two hidden cards");
    }

    @Test
    public void silentChangesFailClosed() {
        final Game g = lib(30);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 0);
        final Player me = me(g);
        // a reorder with no event brings a deep hidden card to the top (and swaps two others); even a card a search
        // picked is then a read when taken
        final List<Card> order = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final Card deep = order.remove(25);
        order.add(0, deep);
        Collections.swap(order, 3, 4);
        me.getZone(ZoneType.Library).setCards(order);
        hw.searchPick(List.of(ZoneType.Library), deep);
        me.drawCards(1);
        Assert.assertEquals(reads(hw), 1, "a take after an unexplained change is positional");
        // a hidden card that leaves the library with no event: a read at the end
        final List<Card> less = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        less.remove(10);
        me.getZone(ZoneType.Library).setCards(less);
        Assert.assertEquals(reads(hw), 2, "a silent loss");
    }

    @Test
    public void peeksAreReadsOutsideASearch() {
        final Game g = lib(10);
        final ForcedWinSolver.HiddenWatch hw = watch(g, 1);
        hw.peekCards(List.of(at(g, 0), at(g, 1)), ZoneType.Library, me(g));
        Assert.assertEquals(reads(hw), 1, "the known top is not a read; the second card is");
        hw.inSearch = true;
        hw.peekCards(new ArrayList<>(me(g).getCardsIn(ZoneType.Library)), ZoneType.Library, me(g));
        hw.inSearch = false;
        Assert.assertEquals(reads(hw), 1, "a search's look at the whole library is not a peek");
        hw.peekCards(List.of(at(g, 5)), ZoneType.Hand, me(g));
        Assert.assertEquals(reads(hw), 1, "not our library");
    }

    // ------------------------------------------------------------------ labels (HonestLabel, the step-1 kill spec)

    static JsonObject spec(final boolean libLabels) {
        final JsonObject o = WinConditionCoverageTest.spec();
        if (libLabels) {
            o.addProperty("libLabels", true);
        }
        return o;
    }

    static JsonObject label(final WinConditionCoverageTest.P p, final boolean libLabels, final long key) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", p.frame());
        final Player me = g.getPlayers().get(0);
        final HonestLabel.Seat seat = new HonestLabel.Seat(HonestLabel.Spec.parse(spec(libLabels)));
        final JsonObject r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> seat.label(g, me, key))).toJson();
        r.remove("ms");
        System.out.println("LIB " + p.name + " libLabels=" + libLabels + " key=" + key + " row=" + r);
        return r;
    }

    static WinConditionCoverageTest.P puzzle(final String name) {
        for (WinConditionCoverageTest.P p : WinConditionCoverageTest.puzzles()) {
            if (p.name.equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException(name);
    }

    static String str(final JsonObject r, final String k) {
        return WinConditionCoverageTest.str(r, k);
    }

    @Test
    public void specKeyIsOffByDefaultAndOnlyInTheShaWhenOn() {
        final HonestLabel.Spec off = HonestLabel.Spec.parse(spec(false));
        final HonestLabel.Spec on = HonestLabel.Spec.parse(spec(true));
        Assert.assertFalse(off.libLabels);
        Assert.assertFalse(off.toJson().has("libLabels"));
        Assert.assertFalse(off.winConfig().libWatch || off.lossConfig().libWatch);
        Assert.assertTrue(on.libLabels && on.winConfig().libWatch && on.lossConfig().libWatch);
        Assert.assertNotEquals(on.sha(), off.sha());
        final JsonObject explicitOff = spec(false);
        explicitOff.addProperty("libLabels", false);
        Assert.assertEquals(HonestLabel.Spec.parse(explicitOff).sha(), off.sha(), "libLabels false = the v1 sha");
    }

    /** The ruling's Doomsday test: a pile with a known order, drawn by Ancestral Recall, Oracle: PROVEN, unchanged. */
    @Test(timeOut = 900000)
    public void doomsdayKnownPileStaysProven() {
        final WinConditionCoverageTest.P p = puzzle("doomsday-recall-oracle");
        final JsonObject off = label(p, false, 42L), on = label(p, true, 42L);
        Assert.assertEquals(str(off, "label"), "win", off.toString());
        Assert.assertEquals(on, off, "the pile is known: the row is byte-identical with libLabels on");
    }

    /** The ruling's control: a line with no library interaction is PROVEN and byte-identical on and off. */
    @Test(timeOut = 900000)
    public void noDependenceControlIsByteIdentical() {
        for (String name : new String[] {"tendrils-storm3", "kiki-pestermite"}) {
            final WinConditionCoverageTest.P p = puzzle(name);
            final JsonObject off = label(p, false, 42L), on = label(p, true, 42L);
            Assert.assertEquals(str(off, "label"), "win", name + " " + off);
            Assert.assertEquals(on, off, name);
        }
    }

    /** A deep dig (Brain Freeze mills three hidden cards of ours, then Oracle): sampled k/W, never PROVEN. */
    @Test(timeOut = 900000)
    public void aDeepSelfMillIsSampled() {
        final WinConditionCoverageTest.P p = puzzle("brainfreeze-self-oracle");
        final JsonObject off = label(p, false, 42L), on = label(p, true, 42L);
        Assert.assertEquals(str(off, "label"), "win", off.toString());
        Assert.assertEquals(str(on, "label"), "unresolved", on.toString());
        Assert.assertEquals(str(on, "lib_tag"), "sampled", on.toString());
        Assert.assertEquals(on.get("lib_w").getAsInt(), 8, on.toString());
        Assert.assertEquals(on.get("p_win").getAsDouble(), 1.0, 1e-9, "k/W = 8/8, still not proven");
        Assert.assertTrue(on.get("lib_takes").getAsInt() > 2 || on.get("lib_takes").getAsInt() == -1, on.toString());
    }

    /**
     * The loss side (review #161 S3-5): their Jace, Wielder of Mysteries +1 mills their own two hidden cards and draws
     * from the empty library: a proven loss without libLabels; with them, sampled and p_loss withheld (review S2-1).
     */
    @Test(timeOut = 900000)
    public void aLossByTheirLibraryReadIsSampled() {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("their-jace-mill-self", WinConditionCoverageTest.V.PROVEN,
                "their Jace +1 on themselves: mill 2, draw from empty")
                .k("activeplayer", "ai").k("aibattlefield", "Jace, Wielder of Mysteries|Counters:LOYALTY=4;Island")
                .k("ailibrary", "Island;Island");
        final JsonObject off = label(p, false, 42L), on = label(p, true, 42L);
        Assert.assertEquals(str(off, "check"), "loss", off.toString());
        Assert.assertEquals(str(off, "label"), "loss", "precondition: proven loss without libLabels: " + off);
        Assert.assertEquals(str(on, "label"), "unresolved", on.toString());
        Assert.assertEquals(str(on, "lib_tag"), "sampled", on.toString());
        Assert.assertTrue(on.get("p_loss").isJsonNull(), "p_loss withheld: " + on);
    }

    /**
     * A tutor in the window (Demonic Tutor for the one Bolt in a ten-card library, then Bolt their last 3): an identity
     * search, but within the top twelve hidden cards, so it fails closed: not PROVEN with libLabels.
     */
    @Test(timeOut = 900000)
    public void aTutorInTheWindowFailsClosed() {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("tutor-bolt", WinConditionCoverageTest.V.PROVEN,
                "Demonic Tutor for Lightning Bolt, Bolt their last 3")
                .k("humanhand", "Demonic Tutor").k("humanbattlefield", "Swamp;Swamp;Mountain").k("ailife", "3")
                .k("humanlibrary", WinConditionCoverageTest.j(WinConditionCoverageTest.n("Island", 9), "Lightning Bolt"));
        final JsonObject off = label(p, false, 42L), on = label(p, true, 42L);
        Assert.assertEquals(str(off, "label"), "win", "the tutor line is proven without libLabels: " + off);
        Assert.assertNotEquals(str(on, "label"), "win", on.toString());
        Assert.assertEquals(str(on, "lib_tag"), "sampled", on.toString());
    }

    /** Two unknown draws (Divination draws our whole two-card library), then Oracle: exact-draw, fraction 1. */
    @Test(timeOut = 900000)
    public void twoUnknownDrawsThatCannotMatterAreAnExactWin() {
        final WinConditionCoverageTest.P p = new WinConditionCoverageTest.P("divination-two-oracle",
                WinConditionCoverageTest.V.PROVEN, "Divination draws both library cards, Oracle on an empty library")
                .k("humanhand", "Divination;Thassa's Oracle").k("humanbattlefield", WinConditionCoverageTest.n("Island", 5))
                .k("humanlibrary", "Mountain;Forest");
        final JsonObject off = label(p, false, 42L), on = label(p, true, 42L);
        Assert.assertEquals(str(off, "label"), "win", off.toString());
        Assert.assertEquals(str(on, "label"), "win", on.toString());
        Assert.assertEquals(str(on, "lib_tag"), "exact-draw", on.toString());
        Assert.assertEquals(on.get("lib_seqs").getAsInt(), 2, "Mountain-Forest and Forest-Mountain");
        Assert.assertEquals(on.get("lib_takes").getAsInt(), 2, on.toString());
    }

    // ------------------------------------------------------------------ the solver's exact-draw fraction

    /**
     * Exact-mode solvers (the position as it stands) with the watch on: LabellerV2MinTest's engine config, and the kill
     * spec's loss-check config (its engine root hides our library order).
     */
    static List<ForcedWinSolver.Config> exactCfgs() {
        final ForcedWinSolver.Config a = LabellerV2MinTest.cfgEngine(false);
        a.libWatch = true;
        final ForcedWinSolver.Config b = HonestLabel.Spec.parse(spec(true)).lossConfig();
        return List.of(a, b);
    }

    /**
     * Two unknown draws needing a specific land: the line draws two (Gush returning two Islands, or Divination) and plays
     * the one Mountain to Bolt their last 3. The planner finds the line on a known top (pile memory: Mountain, Island);
     * then the test forgets that top (line.knownTop = 0: the seat did not know it) and re-verifies the line in the
     * position as it stands (exact mode): it wins, and reads two hidden cards. Over our unknown library (one Mountain,
     * three Islands) the exact-draw fraction is P(the Mountain in the top two) = 1/2: Mountain-Island 1/4 and
     * Island-Mountain 1/4 win, Island-Island 1/2 does not.
     */
    @Test(timeOut = 900000)
    public void twoUnknownDrawsNeedingAMountainAreAnExactFraction() {
        final String[][] lines = {{"Gush;Lightning Bolt", "Island;Island"}, {"Divination;Lightning Bolt", "Island;Island;Island"}};
        int ran = 0;
        for (String[] l : lines) for (int ci = 0; ci < 2; ci++) {
            final String frame = ForcedWinSolverTest.frame(l[0], l[1], 20, "", "", 3, "human")
                    .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Mountain;Island;Island;Island");
            final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
            final Player me = g.getPlayers().get(0);
            g.AI_TIMEOUT = 600;
            final ForcedWinSolver s = new ForcedWinSolver(me, exactCfgs().get(ci));
            final List<Card> top2 = new ArrayList<>(me.getCardsIn(ZoneType.Library).subList(0, 2));
            Assert.assertEquals(top2.get(0).getName(), "Mountain");
            s.notePlaced(top2);
            final Object[] got = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
                for (ForcedWinSolver.Proposal p0 : s.propose(g)) {
                    if (!p0.proven(1)) {
                        continue;
                    }
                    final ForcedWinPlanner.Line line = (ForcedWinPlanner.Line) p0.line;
                    line.knownTop = 0; // the seat did not know its top
                    final ForcedWinSolver.Proposal p = new ForcedWinSolver.Proposal(s.evaluate(g, line, 7L, Long.MAX_VALUE));
                    return new Object[] {p0, p, p.proven(1) ? s.libExact(g, p, 64) : null};
                }
                return new Object[] {null, null, null};
            }));
            final ForcedWinSolver.Proposal p0 = (ForcedWinSolver.Proposal) got[0], p = (ForcedWinSolver.Proposal) got[1];
            System.out.println("LIBX " + l[0] + " cfg=" + ci + " planned=" + (p0 == null ? null : p0.text) + " forgotten="
                    + (p == null ? null : p.text + " dep=" + p.libDependent + " takes=" + p.hiddenTakes));
            if (p0 == null) {
                continue; // the solver found no line for this variant (reported, not a pass)
            }
            ran++;
            Assert.assertFalse(p0.libDependent, "on the known top the line reads nothing hidden: " + p0.text);
            Assert.assertTrue(p.proven(1), "the position as it stands has the Mountain on top: " + p.text);
            Assert.assertTrue(p.libDependent, p.text);
            Assert.assertEquals(p.hiddenTakes, 2, p.text);
            final ForcedWinSolver.LibExact x = (ForcedWinSolver.LibExact) got[2];
            System.out.println("LIBX " + l[0] + " cfg=" + ci + " exact=" + x.exact + " fraction=" + x.fraction + " seqs="
                    + x.seqs + " why=" + x.why);
            Assert.assertTrue(x.exact, x.why);
            Assert.assertEquals(x.seqs, 3, "MI, IM, II");
            Assert.assertEquals(x.fraction, 0.5, 1e-9);
        }
        Assert.assertTrue(ran > 0, "neither two-draw line was found");
    }

    /**
     * Review #161 S1-1: Opt (scry 1, draw), Ponder (look at three, reorder, draw) and Impulse (look at four, one to
     * hand, the rest to the bottom) each find the one Mountain to Bolt their last 3. Planned on a fully known library,
     * then the known top is forgotten: the re-verified line reads hidden cards, and its label is not a win: sampled
     * (more than two reads or not exact) or an exact fraction below 1.
     */
    @Test(timeOut = 900000)
    public void looksThenDrawsAreNotProvenWhenTheyNeedTheCard() {
        final String[][] lines = {{"Opt;Lightning Bolt", "Island"}, {"Ponder;Lightning Bolt", "Island"},
                {"Impulse;Lightning Bolt", "Island;Island"}};
        final List<String> found = new ArrayList<>();
        for (String[] l : lines) {
            boolean any = false;
            for (int ci = 0; ci < 2 && !any; ci++) {
                final String frame = ForcedWinSolverTest.frame(l[0], l[1], 20, "", "", 3, "human")
                        .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Mountain;Island;Island;Island");
                final Game g = ForcedWinSolverTest.game("Default", "Default", frame);
                final Player me = g.getPlayers().get(0);
                g.AI_TIMEOUT = 600;
                final ForcedWinSolver s = new ForcedWinSolver(me, exactCfgs().get(ci));
                s.notePlaced(new ArrayList<>(me.getCardsIn(ZoneType.Library)));
                final Object[] got = PlayerControllerBridge.isolated(g, () -> IdScope.detached(() -> {
                    for (ForcedWinSolver.Proposal p0 : s.propose(g)) {
                        if (!p0.proven(1)) {
                            continue;
                        }
                        final ForcedWinPlanner.Line line = (ForcedWinPlanner.Line) p0.line;
                        line.knownTop = 0;
                        final ForcedWinSolver.Proposal p = new ForcedWinSolver.Proposal(s.evaluate(g, line, 7L, Long.MAX_VALUE));
                        final ForcedWinSolver.LibExact x = p.proven(1) && p.hiddenTakes <= 2 ? s.libExact(g, p, 64, 9L) : null;
                        return new Object[] {p0, p, x};
                    }
                    return new Object[] {null, null, null};
                }));
                final ForcedWinSolver.Proposal p0 = (ForcedWinSolver.Proposal) got[0], p = (ForcedWinSolver.Proposal) got[1];
                final ForcedWinSolver.LibExact x = (ForcedWinSolver.LibExact) got[2];
                System.out.println("LIBL " + l[0] + " cfg=" + ci + " planned=" + (p0 == null ? null : p0.text) + " forgotten="
                        + (p == null ? null : p.text + " dep=" + p.libDependent + " takes=" + p.hiddenTakes) + " exact="
                        + (x == null ? null : x.exact + " " + x.fraction + " seqs=" + x.seqs + " why=" + x.why));
                if (p0 == null) {
                    continue;
                }
                any = true;
                found.add(l[0]);
                if (!p.proven(1)) {
                    continue; // the position as it stands no longer wins: not proven either way
                }
                Assert.assertTrue(p.libDependent, l[0] + ": " + p.text);
                final boolean wouldWin = p.hiddenTakes <= 2 && x != null && x.exact && x.fraction >= 1.0 - 1e-9;
                Assert.assertFalse(wouldWin, l[0] + " labelled a win: " + p.text);
            }
        }
        System.out.println("LIBL found " + found);
        Assert.assertEquals(found.size(), lines.length, "every look line was found: " + found);
    }
}
