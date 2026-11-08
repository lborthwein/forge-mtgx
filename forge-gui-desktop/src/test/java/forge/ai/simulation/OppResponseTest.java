package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;

/**
 * opp-response-1108 (solverCfg oppResponse, off by default): a verified line's worlds are re-checked against the
 * opponent's legal actions from each sampled world's own hand and board (the engine's castable answers, legal blocks).
 * Nothing in the check names a card: the frames below differ only in what the opponent's deck holds and whether its
 * mana is open.
 */
public class OppResponseTest extends AITest {

    static String frame(String aiLibCard, String aiBattlefield) {
        final StringBuilder lib = new StringBuilder();
        for (int i = 0; i < 20; i++) lib.append(i == 0 ? "" : ";").append(aiLibCard);
        return "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=3\n"
                + "humanlandsplayed=0\nailandsplayed=0\naihand=Forest;Forest;Forest\nailibrary=" + lib + "\naigraveyard=\naiexile=\n"
                + "aibattlefield=" + aiBattlefield + "\nhumanhand=Lightning Bolt\nhumanbattlefield=Mountain\n"
                + "humangraveyard=\nhumanexile=\nhumanlibrary=" + ForcedWinSolverTest.OURLIB + "\n";
    }

    static ForcedWinSolver.Config cfg(boolean on) {
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(k -> !on ? null
                : "oppResponse".equals(k) ? "true" : "oppResponseMs".equals(k) ? "60000" : null);
        c.horizon = 1;
        c.engine = false;
        c.nodes = 3000;
        c.planMs = 1000;
        c.budgetMs = 20000;
        c.worlds = 4;
        // the verification's opponent passes while our spell is on the stack (as a Forge AI that misses the answer):
        // the line is "proven" before the check, so the check alone decides what changes
        c.testPassiveOpp = true;
        return c;
    }

    static ForcedWinSolver.Proposal best(Game g, boolean on) {
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, cfg(on));
        final List<ForcedWinSolver.Proposal> ps = s.propose(g);
        ForcedWinSolver.Proposal b = null;
        for (ForcedWinSolver.Proposal p : ps) {
            if (b == null || p.pBelief > b.pBelief) b = p;
        }
        Assert.assertNotNull(b, "the Bolt line is proposed: " + s.summary());
        System.err.println("[opp-response-test] on=" + on + " " + b.text + " meter=" + s.meter.toJson());
        return b;
    }

    @Test
    public void anInstantAnswerInTheirSampledHandBreaksTheLine() {
        // their deck is all counterspells and two Islands are open: every sampled hand holds the answer
        final String f = frame("Counterspell", "Island;Island");
        final ForcedWinSolver.Proposal off = best(ForcedWinSolverTest.game("Default", "Default", f), false);
        Assert.assertEquals(off.pBelief, 1.0, 1e-9, "keys off: proven against the passive opponent: " + off.text);
        final ForcedWinSolver.Proposal on = best(ForcedWinSolverTest.game("Default", "Default", f), true);
        Assert.assertEquals(on.pUnopposed, 1.0, 1e-9);
        Assert.assertTrue(on.pBelief < 1.0, "the counter refutes the line: " + on.text);
        Assert.assertTrue(on.text.contains("resp=1.00->"), on.text);
        Assert.assertTrue(on.text.contains("Counterspell"), "the refutation is logged: " + on.text);
    }

    @Test
    public void anAnswerTheyCannotPayForDoesNotBreakIt() {
        // the same deck with their Islands tapped: no legal response, the line stays proven
        final String f = frame("Counterspell", "Island|Tapped;Island|Tapped");
        final ForcedWinSolver.Proposal on = best(ForcedWinSolverTest.game("Default", "Default", f), true);
        Assert.assertEquals(on.pBelief, 1.0, 1e-9, on.text);
        Assert.assertTrue(on.text.contains("refuted=0"), on.text);
    }

    @Test
    public void aDeckWithNoInstantAnswerDoesNotBreakIt() {
        final String f = frame("Forest", "Island;Island");
        final ForcedWinSolver.Proposal on = best(ForcedWinSolverTest.game("Default", "Default", f), true);
        Assert.assertEquals(on.pBelief, 1.0, 1e-9, on.text);
    }

    @Test
    public void keysOffTheConfigIsUnchanged() {
        final ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        Assert.assertFalse(off.oppResponse);
        Assert.assertFalse(off.toString().contains("oppResponse"));
        final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(k -> "oppResponse".equals(k) ? "true" : null);
        Assert.assertTrue(on.toString().contains("oppResponse ms=1500 N=12 D=2"), on.toString());
        Assert.assertThrows(IllegalArgumentException.class, () -> ForcedWinSolver.Config.fromProps(
                k -> "oppResponse".equals(k) ? "true" : "oppResponseNodes".equals(k) ? "0" : null));
    }

    // ------------------------------------------------------------------ the refutation tree (OppMin.Cfg#refute)

    static OppMin.Cfg refute(int nodes) {
        final OppMin.Cfg c = new OppMin.Cfg(nodes, OppMin.CHARM_CAP, 2);
        c.refute = true;
        c.budgetByDepth = true;
        return c;
    }

    @Test
    public void theRefutationSkipsForgeAiAndTreatsGapsAsUnknownNotLost() {
        final List<String> played = new ArrayList<>();
        final OppMin.Out o = OppMin.min(refute(50), s -> {
            played.add(s.toString());
            return new OppMin.Leaf(1.0, "won", Collections.singletonList(new OppMin.Point(0, "p", 1, 0, "unknown-api")));
        });
        Assert.assertEquals(o.v, 1.0, 1e-12, "a gap is not a refutation");
        Assert.assertFalse(played.contains("forge"), "the verification already played Forge AI's root: " + played);
        Assert.assertEquals(played, List.of("[]"));
        Assert.assertEquals(o.caps.get("gap:unknown-api"), Integer.valueOf(1));
    }

    @Test
    public void singleResponsesComeBeforePairsAndTheFirstLossStops() {
        // three points of two answers each; only the pair (point 0 answer 1, point 2 answer 0) beats the line
        final List<String> order = new ArrayList<>();
        final OppMin.Out o = OppMin.min(refute(200), s -> {
            order.add(s.toString());
            final List<OppMin.Point> pts = new ArrayList<>();
            for (int j = 0; j < 3; j++) pts.add(new OppMin.Point(2, "p" + j + s, 1, 2));
            final boolean lost = s.respAt(0) == 1 && s.respAt(2) == 0;
            return new OppMin.Leaf(lost ? 0.0 : 1.0, "t", pts);
        });
        Assert.assertEquals(o.v, 0.0, 1e-12);
        int firstPair = -1;
        for (int i = 0; i < order.size() && firstPair < 0; i++) if (order.get(i).contains(",")) firstPair = i;
        Assert.assertEquals(firstPair, 1 + 6, "pass, then all six single responses, then pairs: " + order);
        Assert.assertEquals(order.get(order.size() - 1), "[0:1,2:0]");
        // a node budget too small to reach the pair leaves the world not refuted (its verified win stands)
        final OppMin.Out cut = OppMin.min(refute(5), s -> {
            final List<OppMin.Point> pts = new ArrayList<>();
            for (int j = 0; j < 3; j++) pts.add(new OppMin.Point(2, "p" + j + s, 1, 2));
            return new OppMin.Leaf(s.respAt(0) == 1 && s.respAt(2) == 0 ? 0.0 : 1.0, "t", pts);
        });
        Assert.assertEquals(cut.v, 1.0, 1e-12);
        Assert.assertEquals(cut.nodes, 5);
    }

    @Test
    public void blockOptionsAreTheLegalAssignmentsOnly() {
        // our 2/2 and a 1/1 flyer attack; their two 2/2s can block the 2/2 but not the flyer
        final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("",
                "Grizzly Bears;Suntail Hawk", 20, "", "Grizzly Bears;Grizzly Bears", 20, "human"));
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Combat c = new Combat(me);
        for (Card a : me.getCreaturesInPlay()) c.addAttacker(a, opp);
        final List<List<int[]>> opts = OppMin.blockOptions(c, opp, 12);
        final List<String> sigs = new ArrayList<>();
        for (List<int[]> o : opts) sigs.add(OppMin.blockNames(g, o));
        // nothing is declared yet, so "no blocks" is the declaration in place (the point's default), not an option
        Assert.assertFalse(sigs.contains("no-blocks"), sigs.toString());
        Assert.assertEquals(opts.size(), 2, "each of their two Bears on our Bears: " + sigs);
        for (String s : sigs) Assert.assertFalse(s.contains(">Suntail Hawk"), "a flyer cannot be blocked: " + sigs);
        Assert.assertTrue(c.getAllBlockers().isEmpty(), "the declaration in place is left as it was");
    }
}
