package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Position;

/**
 * combo-assembly-1021 B2: {@link RouteProgress}, the shared route-progress estimate (role table + role routes + a Monte
 * Carlo over library order). Pure: Positions are built by hand, no game.
 */
public class RouteProgressTest {

    private int nextId = 100;

    private ACard card(String name) {
        final ACard a = new ACard();
        a.id = nextId++;
        a.name = name;
        a.land = Arrays.asList("Swamp", "Island", "Mountain", "Forest", "Plains").contains(name);
        return a;
    }

    /** Our main phase, land drop available; hand, untapped battlefield and library (unknown order) as given. */
    private Position pos(List<ACard> hand, List<ACard> bf, List<ACard> lib) {
        final Position p = new Position();
        p.life = 20;
        p.oppLife = 20;
        p.sorcerySpeed = true;
        p.landDrop = true;
        p.hand.addAll(hand);
        for (ACard a : bf) {
            p.battlefield.add(a);
            p.tapped.add(false);
            p.used.add(false);
        }
        p.library.addAll(lib);
        p.librarySize = lib.size();
        return p;
    }

    private List<ACard> cards(String... names) {
        final List<ACard> out = new ArrayList<>();
        for (String n : names) {
            out.add(card(n));
        }
        return out;
    }

    /** Filler library cards with no roles (unknown names), plus the given cards. */
    private List<ACard> library(int fillers, String... names) {
        final List<ACard> out = cards(names);
        for (int i = 0; i < fillers; i++) {
            out.add(card("Route Progress Test Filler"));
        }
        return out;
    }

    private static ACard named(List<ACard> l, String name) {
        for (ACard a : l) {
            if (name.equals(a.name)) {
                return a;
            }
        }
        throw new AssertionError(name);
    }

    @Test
    public void theRoleTableLoads() {
        Assert.assertTrue(RouteProgress.tableVersion().startsWith("route-roles-1021/"));
        Assert.assertEquals(RouteProgress.tableSha256().length(), 64);
        Assert.assertTrue(RouteProgress.roles("Entomb").containsAll(Arrays.asList("TUTOR_GY", "GY_ENABLER")));
        Assert.assertTrue(RouteProgress.roles("Reanimate").contains("REANIMATE"));
        Assert.assertTrue(RouteProgress.roles("Griselbrand").contains("PAYLOAD"));
        Assert.assertTrue(RouteProgress.roles("Doomsday").contains("DOOMSDAY"));
        Assert.assertTrue(RouteProgress.roles("Thassa's Oracle").contains("WINCON_EMPTY_LIBRARY"));
        Assert.assertTrue(RouteProgress.roles("Faithless Looting").contains("DISCARD_OUTLET"));
        Assert.assertTrue(RouteProgress.roles("Underworld Breach").contains("ESCAPE_ENGINE"));
        Assert.assertTrue(RouteProgress.roles("Lion's Eye Diamond").contains("FREE_MANA"));
        Assert.assertTrue(RouteProgress.roles("Pestermite").contains("UNTAPPER"));
        Assert.assertTrue(RouteProgress.roles("Splinter Twin").contains("COPY_ENABLER"));
        Assert.assertTrue(RouteProgress.roles("Sneak Attack").contains("CHEAT"));
        Assert.assertTrue(RouteProgress.roles("Vampiric Tutor").contains("TUTOR_TOP"));
        Assert.assertTrue(RouteProgress.roles("Swamp").contains("MANA:B"));
        Assert.assertTrue(RouteProgress.roles("No Such Card Anywhere").isEmpty());
    }

    private Position reanimatorBase(String... extraHand) {
        final List<ACard> hand = cards("Reanimate", "Swamp");
        hand.addAll(cards(extraHand));
        final List<String> lib = new ArrayList<>(Arrays.asList("Griselbrand", "Entomb"));
        for (String h : extraHand) {
            lib.remove(h);
        }
        return pos(hand, cards("Swamp", "Mountain"), library(30, lib.toArray(new String[0])));
    }

    /** The owner's RA3: Reanimate + lands in hand, Griselbrand and Entomb in the library. */
    @Test
    public void reanimatorEntombBeatsAStrandedPayload() {
        final Position base = reanimatorBase();
        final Position withEntomb = reanimatorBase("Entomb");
        final Position withGris = reanimatorBase("Griselbrand");
        final Position withGrisOutlet = reanimatorBase("Griselbrand", "Faithless Looting");
        final RouteProgress.Est e0 = RouteProgress.estimate(base, 2, 64, 7L);
        final RouteProgress.Est eE = RouteProgress.estimate(withEntomb, 2, 64, 7L);
        final RouteProgress.Est eG = RouteProgress.estimate(withGris, 2, 64, 7L);
        final RouteProgress.Est eGO = RouteProgress.estimate(withGrisOutlet, 2, 64, 7L);
        System.out.println("RA3 base " + e0 + "\nRA3 +Entomb " + eE + "\nRA3 +Griselbrand " + eG + "\nRA3 +Griselbrand+Looting " + eGO);
        Assert.assertTrue(eE.p > eG.p, "Entomb in hand > Griselbrand in hand without an outlet");
        Assert.assertTrue(eE.p > 0.9, "Entomb + Reanimate with BB is complete this turn");
        Assert.assertEquals(eE.family, "reanimator");
        Assert.assertEquals(eE.turn, 1);
        Assert.assertEquals(eG.p, 0.0, 1e-9, "no outlet, the only payload stranded in hand");
        Assert.assertTrue(eE.p > e0.p && eGO.p > e0.p, "an Entomb, or a payload with an outlet, both raise p");
        Assert.assertTrue(eGO.p > eG.p, "the outlet raises p");
        // fillers: the hand's role cards (Reanimate, Entomb as the tutor that fills the graveyard slot)
        final ACard ent = named(withEntomb.hand, "Entomb");
        final ACard rea = named(withEntomb.hand, "Reanimate");
        Assert.assertEquals(eE.fillers.get(rea.id), "REANIMATE");
        Assert.assertEquals(eE.fillers.get(ent.id), "TUTOR_GY");
        Assert.assertEquals(eGO.fillers.get(named(withGrisOutlet.hand, "Faithless Looting").id), "DISCARD_OUTLET");
        Assert.assertEquals(eGO.fillers.get(named(withGrisOutlet.hand, "Griselbrand").id), "PAYLOAD");
        // the base's best route misses the graveyard payload
        Assert.assertTrue(e0.missing.contains("PAYLOAD"), e0.toString());
        // the defense's discard question: taking the Entomb lowers his progress most
        final RouteProgress.Est noEnt = RouteProgress.estimateWithout(withEntomb, Collections.singleton(ent.id), 2, 64, 7L);
        final RouteProgress.Est noRea = RouteProgress.estimateWithout(withEntomb, Collections.singleton(rea.id), 2, 64, 7L);
        Assert.assertTrue(noEnt.p < eE.p && noRea.p < eE.p, noEnt + " / " + noRea);
    }

    private Position doomsday(boolean ddInHand, boolean ddInLibrary) {
        final List<ACard> hand = cards("Island");
        if (ddInHand) {
            hand.add(card("Doomsday"));
        }
        final List<ACard> lib = library(30, "Thassa's Oracle");
        if (ddInLibrary) {
            lib.add(card("Doomsday"));
        }
        return pos(hand, cards("Swamp", "Swamp", "Swamp"), lib);
    }

    @Test
    public void doomsdayNeedsDoomsdayBbbAndAWincon() {
        final Position dd = doomsday(true, false);
        final RouteProgress.Est in = RouteProgress.estimate(dd, 2, 64, 11L);
        final RouteProgress.Est none = RouteProgress.estimate(doomsday(false, false), 2, 64, 11L);
        final RouteProgress.Est lib = RouteProgress.estimate(doomsday(false, true), 2, 64, 11L);
        System.out.println("DD hand " + in + "\nDD none " + none + "\nDD library " + lib);
        Assert.assertTrue(in.p > 0.95, in.toString());
        Assert.assertEquals(in.route, "doomsday/pile");
        Assert.assertTrue(in.missing.isEmpty(), in.toString());
        Assert.assertEquals(in.turn, 1);
        Assert.assertEquals(none.p, 0.0, 1e-9);
        Assert.assertNotEquals(none.family, "doomsday");
        Assert.assertTrue(lib.p > 0 && lib.p < in.p, "Doomsday in the library is a draw away: " + lib);
        Assert.assertEquals(in.fillers.get(named(dd.hand, "Doomsday").id), "DOOMSDAY");
        // without BBB: no Swamps, only an Island in hand
        final Position poor = pos(cards("Doomsday", "Island"), cards(), library(30, "Thassa's Oracle"));
        Assert.assertTrue(RouteProgress.estimate(poor, 2, 64, 11L).p < 0.05);
    }

    /** The defense's pending pair: the spell on the stack (cast and paid: free) resolving vs countered. */
    @Test
    public void estimateWithoutThePendingSpellLowersP() {
        final Position p = pos(cards("Doomsday"), cards(), library(30, "Thassa's Oracle"));
        final ACard dd = named(p.hand, "Doomsday");
        p.pendingId = dd.id;
        p.sorcerySpeed = false; // the stack is not empty
        p.landDrop = false;
        final RouteProgress.Est resolves = RouteProgress.estimate(p, 2, 64, 3L);
        final RouteProgress.Est countered = RouteProgress.estimateWithout(p, Collections.singleton(p.pendingId), 2, 64, 3L);
        System.out.println("pending resolves " + resolves + "\npending countered " + countered);
        Assert.assertTrue(resolves.p > 0.95, resolves.toString());
        Assert.assertEquals(countered.p, 0.0, 1e-9);
    }

    @Test
    public void knownTopStaysFixed() {
        // the next turn's draw is the known top: Doomsday on top, BBB on the battlefield, not our turn
        final Position p = pos(cards(), cards("Swamp", "Swamp", "Swamp"), library(30, "Thassa's Oracle"));
        p.sorcerySpeed = false;
        p.landDrop = false;
        final RouteProgress.Est unknown = RouteProgress.estimate(p, 1, 64, 5L);
        p.knownTop.add(card("Doomsday"));
        p.librarySize++;
        final RouteProgress.Est top = RouteProgress.estimate(p, 1, 64, 5L);
        Assert.assertEquals(unknown.p, 0.0, 1e-9);
        Assert.assertTrue(top.p > 0.99, top.toString());
    }

    /** Each role route completes from a ready position (the table's families are generic over its roles). */
    @Test
    public void everyFamilyCompletesFromAReadyPosition() {
        final Object[][] cases = {
            {"twin", cards("Pestermite", "Splinter Twin", "Island"), cards("Mountain", "Mountain", "Island", "Island"), library(30)},
            {"tinker", cards("Tinker"), cards("Sol Ring", "Island"), library(30, "Blightsteel Colossus")},
            {"sneak-show", cards("Show and Tell", "Griselbrand"), cards("Island", "Island", "Island"), library(30)},
            {"breach", cards("Underworld Breach", "Lion's Eye Diamond", "Brain Freeze"), cards("Mountain", "Island", "Island", "Island"), library(30)},
            {"natural-order", cards("Natural Order"), cards("Forest", "Forest", "Forest", "Forest"), library(30, "Craterhoof Behemoth")},
        };
        for (Object[] c : cases) {
            @SuppressWarnings("unchecked")
            final Position p = pos((List<ACard>) c[1], (List<ACard>) c[2], (List<ACard>) c[3]);
            final RouteProgress.Est e = RouteProgress.estimate(p, 2, 64, 9L);
            System.out.println(c[0] + " " + e);
            Assert.assertEquals(e.family, c[0], e.toString());
            Assert.assertTrue(e.p > 0.95, e.toString());
        }
    }

    private Position fortyCardDeck() {
        final List<ACard> hand = cards("Reanimate", "Swamp", "Island", "Brainstorm", "Ponder", "Faithless Looting", "Doomsday");
        final List<ACard> lib = library(14, "Griselbrand", "Entomb", "Thassa's Oracle", "Demonic Tutor", "Vampiric Tutor",
                "Dark Ritual", "Lion's Eye Diamond", "Underworld Breach", "Brain Freeze", "Show and Tell", "Archon of Cruelty",
                "Swamp", "Swamp", "Island", "Underground Sea", "Polluted Delta", "Sol Ring", "Lotus Petal", "Animate Dead");
        return pos(hand, cards("Swamp", "Badlands"), lib);
    }

    @Test
    public void deterministicForAPositionAndSeed() {
        final Position p = fortyCardDeck();
        final String a = RouteProgress.estimate(p, 3, 64, 42L).toString();
        RouteProgress.estimate(p, 3, 64, 43L);
        final String b = RouteProgress.estimate(p, 3, 64, 42L).toString();
        Assert.assertEquals(b, a);
        final List<Integer> none = Collections.emptyList();
        Assert.assertEquals(RouteProgress.estimateWithout(p, none, 3, 64, 42L).toString(), a);
    }

    @Test
    public void costBoundAtSixtyFourDraws() {
        final Position p = fortyCardDeck();
        for (int i = 0; i < 300; i++) {
            RouteProgress.estimate(p, 3, 64, i);
        }
        final int calls = 400;
        final long t0 = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            RouteProgress.estimate(p, 3, 64, 1000 + i);
        }
        final double ms = (System.nanoTime() - t0) / 1e6 / calls;
        System.out.println(String.format(java.util.Locale.ROOT, "RouteProgress cost: %.3f ms per estimate (draws=64, turns=3, 40 cards) %s",
                ms, RouteProgress.estimate(p, 3, 64, 1L)));
        Assert.assertTrue(ms <= 5.0, "mean " + ms + " ms per call");
    }
}
