package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * opp-belief-1104 (ARCH-APPENDIX-1104 A2): the opponent's replies from its own belief worlds (test scenarios only: card
 * names appear here, never in the search). (1) Off = unrecorded. (2) Leak test, world level: the opponent's worlds do
 * not depend on where our hidden cards really are. (3) Leak test, solver level: its picks do not change when only our
 * hidden cards change. (4) An enabler flashed in at their end step against open counter mana and a tapping-out
 * alternative: under belief + hold points it is worth more than the same enabler shown in our main phase.
 */
public class OppBeliefTest extends SimulationTest {

    static final String ON = SolverGapsTest.BASE + ",enginePassFirst=true,enginePassToEnd=true,oppMinRevealPoints=true,worlds=16";

    static ForcedWinSolver solver(final Game g, final String keys) {
        final java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String kv : keys.split(",")) {
            if (!kv.isEmpty()) m.put(kv.split("=")[0], kv.split("=")[1]);
        }
        return new ForcedWinSolver(g.getPlayers().get(0), ForcedWinSolver.Config.fromProps(m::get));
    }

    @Test
    public void offKeysAreUnrecorded() {
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(k -> null);
        for (String k : new String[] {"oppBelief", "oppBeliefCands", "oppHoldPoints"}) {
            AssertJUnit.assertFalse(k, HonestGenerator.solverCfgJson(c).has(k));
        }
        AssertJUnit.assertFalse(c.toString().contains("oppBelief"));
        final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(java.util.Map.of("oppBelief", "3")::get);
        AssertJUnit.assertTrue(HonestGenerator.solverCfgJson(on).has("oppBelief"));
        final com.google.gson.JsonObject r3 = new com.google.gson.JsonObject();
        AssertJUnit.assertFalse(R3LabelSearch.Spec.parse(r3).identity().has("oppBelief"));
        r3.addProperty("oppBelief", 2);
        AssertJUnit.assertTrue(R3LabelSearch.Spec.parse(r3).identity().has("oppBelief"));
    }

    /**
     * Their library: two cards they cannot cast (no land to draw: three lands on their turn, so the creature leaves them
     * below counter mana). Our worlds draw their hidden two-card hand from hand + library: half hold the counter.
     */
    static final String THEIRLIB = "Emrakul, the Aeons Torn;Emrakul, the Aeons Torn";

    /**
     * The B frame (solver-gaps-1104): they tapped out last turn and hold a counter and a creature, hidden from us.
     * {@code shown}: the main-phase setup already cast (the enabler on our battlefield, three of our lands tapped).
     */
    static String bFrame(final String extraHand, final String lib, final boolean shownSetup) {
        return "[state]\nturn=8\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20"
                + "\nhumanlandsplayed=1\nailandsplayed=0\naihand=Counterspell;Grizzly Bears\nailibrary=" + THEIRLIB
                + "\naigraveyard=\naiexile=\naibattlefield=Island|Tapped;Island|Tapped;Forest|Tapped"
                + "\nhumanhand=" + (shownSetup ? "" : "Pestermite;") + "Kiki-Jiki, Mirror Breaker" + extraHand
                + "\nhumanbattlefield=" + (shownSetup ? "Pestermite|SummonSick;Volcanic Island|Tapped;Mountain|Tapped;Mountain|Tapped;"
                        + "Volcanic Island;Mountain" : "Volcanic Island;Volcanic Island;Mountain;Mountain;Mountain")
                + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + lib + "\n";
    }

    static String bFrame(final String extraHand, final String lib) {
        return bFrame(extraHand, lib, false);
    }

    /** Swap our hand card {@code a} with our library card {@code b} (both hidden from the opponent). */
    static void swap(final Player me, final String a, final String b) {
        final List<Card> hand = new ArrayList<>(me.getCardsIn(ZoneType.Hand));
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        int i = -1, j = -1;
        for (int k = 0; k < hand.size(); k++) if (hand.get(k).getName().equals(a)) i = k;
        for (int k = 0; k < lib.size(); k++) if (lib.get(k).getName().equals(b)) j = k;
        AssertJUnit.assertTrue(i >= 0 && j >= 0);
        final Card x = hand.get(i);
        hand.set(i, lib.get(j));
        lib.set(j, x);
        me.getZone(ZoneType.Hand).setCards(hand);
        me.getZone(ZoneType.Library).setCards(lib);
    }

    static String redealt(final Game live, final long seed) {
        final GameCopier copier = new GameCopier(live, true);
        final Game g = copier.makeCopy();
        final Player me = (Player) copier.find(live.getPlayers().get(0));
        OppBeliefWorlds.redeal(live, live.getPlayers().get(1), me, null, new Random(seed));
        return OppBeliefWorlds.layout(me);
    }

    @Test
    public void opponentWorldsDoNotDependOnWhereOurHiddenCardsAre() {
        final Game g = ForcedWinSolverTest.game("Default", "Default",
                bFrame(";Emrakul, the Aeons Torn", "Ulamog, the Infinite Gyre;" + ForcedWinSolverTest.OURLIB));
        final Player me = g.getPlayers().get(0);
        final String before = OppBeliefWorlds.layout(me);
        final List<String> a = new ArrayList<>();
        for (long s = 1; s <= 4; s++) a.add(redealt(g, s));
        swap(me, "Emrakul, the Aeons Torn", "Ulamog, the Infinite Gyre");
        swap(me, "Kiki-Jiki, Mirror Breaker", "Island");
        AssertJUnit.assertFalse(before.equals(OppBeliefWorlds.layout(me)));
        for (long s = 1; s <= 4; s++) AssertJUnit.assertEquals("world " + s, a.get((int) s - 1), redealt(g, s));
        // and the worlds differ from the real layout (our hidden cards are re-dealt)
        boolean moved = false;
        for (String x : a) moved |= !x.equals(before);
        AssertJUnit.assertTrue(moved);
    }

    @Test
    public void opponentPicksDoNotChangeWhenOnlyOurHiddenCardsChange() {
        final String keys = ON + ",oppBelief=3,oppHoldPoints=true";
        final Game g = ForcedWinSolverTest.game("Default", "Default",
                bFrame(";Emrakul, the Aeons Torn", "Ulamog, the Infinite Gyre;" + ForcedWinSolverTest.OURLIB));
        final ForcedWinSolver a = solver(g, keys);
        a.beliefPicks = new ArrayList<>();
        final List<ForcedWinSolver.Proposal> pa = a.propose(g);
        swap(g.getPlayers().get(0), "Emrakul, the Aeons Torn", "Ulamog, the Infinite Gyre");
        final ForcedWinSolver b = solver(g, keys);
        b.beliefPicks = new ArrayList<>();
        final List<ForcedWinSolver.Proposal> pb = b.propose(g);
        System.err.println("[opp-belief-test] leak A " + a.beliefPicks.size() + " picks, B " + b.beliefPicks.size()
                + " | A " + (pa.isEmpty() ? "none" : pa.get(0).pBelief + " " + pa.get(0).text)
                + " | B " + (pb.isEmpty() ? "none" : pb.get(0).pBelief + " " + pb.get(0).text));
        for (String x : a.beliefPicks) System.err.println("[opp-belief-test]   A " + x);
        for (String x : b.beliefPicks) System.err.println("[opp-belief-test]   B " + x);
        AssertJUnit.assertFalse("picks recorded", a.beliefPicks.isEmpty());
        // the same lines are evaluated in the same order; each world's pick must be the same
        final int n = Math.min(a.beliefPicks.size(), b.beliefPicks.size());
        AssertJUnit.assertTrue(n > 0);
        for (int i = 0; i < n; i++) AssertJUnit.assertEquals("pick " + i, a.beliefPicks.get(i), b.beliefPicks.get(i));
    }

    static double best(final List<ForcedWinSolver.Proposal> ps) {
        double p = -1;
        for (ForcedWinSolver.Proposal x : ps) p = Math.max(p, x.pBelief);
        return p;
    }

    static List<ForcedWinSolver.Proposal> run(final String keys, final boolean shownSetup, final String tag) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", bFrame("", ForcedWinSolverTest.OURLIB, shownSetup));
        final ForcedWinSolver s = solver(g, keys);
        s.beliefPicks = new ArrayList<>();
        final List<ForcedWinSolver.Proposal> p = s.propose(g);
        System.err.println("[opp-belief-test] " + tag + " proposals=" + p.size() + " meter=" + s.meter.toJson());
        for (ForcedWinSolver.Proposal x : p) System.err.println("[opp-belief-test]   " + tag + " p=" + x.pBelief + " " + x.text);
        for (String x : s.beliefPicks) System.err.println("[opp-belief-test]   " + tag + " pick " + x);
        return p;
    }

    @Test
    public void endStepFlashBeatsAMainPhaseSetupUnderBelief() {
        // (a) the enabler still in hand (the end-step line available) against (b) the enabler cast in our main phase
        // (on our battlefield, seen on their turn). Their own main phase is a hold point: Forge AI casts the creature
        // (below counter mana), or they hold the counter. Our worlds: half of them give them the counter.
        final String det = ON + ",evalSteps=40000";
        final String bel = ON + ",evalSteps=40000,oppBelief=4,oppHoldPoints=true";
        final double dA = best(run(det, false, "det-a")), dB = best(run(det, true, "det-b"));
        final double bA = best(run(bel, false, "belief-a")), bB = best(run(bel, true, "belief-b"));
        System.err.println("[opp-belief-test] det a(end)=" + dA + " b(main)=" + dB + " | belief a(end)=" + bA + " b(main)=" + bB);
        AssertJUnit.assertTrue("belief: a line with the enabler in hand", bA > 0);
        AssertJUnit.assertTrue("belief: holding the enabler beats showing it (" + bA + " vs " + bB + ")", bA > bB);
    }
}
