package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.combat.Combat;
import forge.game.player.Player;

/**
 * oppturn-1108 (solverCfg oppTurn, off by default): a verified line that wins after the opponent's turn (winTurn >= 1)
 * has that turn played by search from the opponent's own belief worlds: its attack declaration, its main-phase plays
 * aimed at us, its responses and blocks. The frames below differ only in what the opponent has on board; nothing in the
 * check names a card.
 */
public class OppTurnTest extends AITest {

    /**
     * Our main phase, turn 7, land already played: Thassa's Oracle (UU) and an Island in hand, one Island out, three
     * Islands in the library. This turn the Oracle cannot be cast; next turn (land, Oracle, devotion 2 >= library 2) it
     * wins: a next-turn line. We are at {@code life}; the opponent has {@code aiBattlefield}.
     */
    static String frame(int life, String aiBattlefield) {
        return ForcedWinSolverTest.frame("Thassa's Oracle;Island", "Island", life, "", aiBattlefield, 20, "human")
                .replace("humanlandsplayed=0", "humanlandsplayed=1")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island;Island");
    }

    static ForcedWinSolver.Config cfg(boolean on) {
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(k -> !on ? null
                : "oppTurn".equals(k) ? "true" : "oppTurnMs".equals(k) ? "60000" : null);
        c.horizon = 2;
        c.engine = false;
        c.nodes = 3000;
        c.planMs = 2000;
        c.budgetMs = 30000;
        c.worlds = 3;
        // the verification's opponent is a careless attacker (it never attacks): the line is "proven" before the check,
        // so the check alone decides what changes
        c.testNoAttackOpp = true;
        return c;
    }

    static ForcedWinSolver.Proposal best(Game g, ForcedWinSolver.Config c, String tag) {
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), c);
        ForcedWinSolver.Proposal b = null;
        for (ForcedWinSolver.Proposal p : s.propose(g)) {
            if (b == null || p.pBelief > b.pBelief) b = p;
        }
        Assert.assertNotNull(b, "the Oracle line is proposed: " + s.summary());
        System.err.println("[oppturn-test] " + tag + " " + b.text + " meter=" + s.meter.toJson());
        return b;
    }

    @Test
    public void anAttackTheVerificationNeverMadeBeatsTheNextTurnLine() {
        // two Bears against our 3 life: Forge AI (careless here) does not attack, so the line verifies; the opponent's
        // searched turn attacks with everything that can and wins first
        final String f = frame(3, "Grizzly Bears;Grizzly Bears");
        final ForcedWinSolver.Proposal off = best(ForcedWinSolverTest.game("Default", "Default", f), cfg(false), "off");
        Assert.assertEquals(off.winTurn, 1, off.text);
        Assert.assertEquals(off.pBelief, 1.0, 1e-9, "key off: proven against the careless opponent: " + off.text);
        Assert.assertTrue(Double.isNaN(off.pTurnBefore), "key off: no check: " + off.text);
        final ForcedWinSolver.Proposal on = best(ForcedWinSolverTest.game("Default", "Default", f), cfg(true), "on");
        Assert.assertEquals(on.winTurn, 1, on.text);
        Assert.assertEquals(on.pUnopposed, 1.0, 1e-9);
        Assert.assertEquals(on.pTurnBefore, 1.0, 1e-9, on.text);
        Assert.assertEquals(on.pBelief, 0.0, 1e-9, "every checked world dies to the attack: " + on.text);
        Assert.assertTrue(on.text.contains("oppTurn=1.00->0.00"), on.text);
        Assert.assertTrue(on.text.contains("attack declared"), "the refutation is logged: " + on.text);
    }

    @Test
    public void anAttackThatIsNotLethalLeavesTheLineProven() {
        // the same two Bears against 20 life: the searched turn finds nothing that beats the line
        final String f = frame(20, "Grizzly Bears;Grizzly Bears");
        final ForcedWinSolver.Proposal on = best(ForcedWinSolverTest.game("Default", "Default", f), cfg(true), "on-20");
        Assert.assertEquals(on.pBelief, 1.0, 1e-9, on.text);
        Assert.assertTrue(on.text.contains("beaten=0"), on.text);
    }

    @Test
    public void noAttackersNoChange() {
        final String f = frame(3, "Forest");
        final ForcedWinSolver.Proposal on = best(ForcedWinSolverTest.game("Default", "Default", f), cfg(true), "on-empty");
        Assert.assertEquals(on.pBelief, 1.0, 1e-9, on.text);
    }

    @Test
    public void theKeyNeverRaisesPBelief() {
        // with the default verification (Forge AI attacks as it would), the key can only lower P(belief)
        for (String f : List.of(frame(3, "Grizzly Bears;Grizzly Bears"), frame(20, "Grizzly Bears;Grizzly Bears"),
                frame(3, "Forest"), frame(2, "Grizzly Bears"))) {
            final ForcedWinSolver.Config off = cfg(false), on = cfg(true);
            off.testNoAttackOpp = on.testNoAttackOpp = false;
            final ForcedWinSolver.Proposal pOff = best(ForcedWinSolverTest.game("Default", "Default", f), off, "inv-off");
            final ForcedWinSolver.Proposal pOn = best(ForcedWinSolverTest.game("Default", "Default", f), on, "inv-on");
            Assert.assertTrue(pOn.pBelief <= pOff.pBelief + 1e-9, "key on " + pOn.text + " > key off " + pOff.text);
        }
    }

    @Test
    public void keysOffTheConfigIsUnchanged() {
        final ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        Assert.assertFalse(off.oppTurn);
        Assert.assertFalse(off.toString().contains("oppTurn"));
        final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(k -> "oppTurn".equals(k) ? "true" : null);
        Assert.assertTrue(on.toString().contains("oppTurn ms=3000 K=2 N=8 D=2 W=4 minP=1.0"), on.toString());
        Assert.assertThrows(IllegalArgumentException.class, () -> ForcedWinSolver.Config.fromProps(
                k -> "oppTurn".equals(k) ? "true" : "oppTurnBelief".equals(k) ? "0" : null));
    }

    @Test
    public void attackOptionsAreEveryLegalAttackerAndNone() {
        // their two Bears can attack us; Forge AI declared one of them: the options are both, and none
        final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("", "Island", 20, "",
                "Grizzly Bears;Grizzly Bears", 20, "ai"));
        final Player me = g.getPlayers().get(0), opp = g.getPlayers().get(1);
        final Combat c = new Combat(opp);
        c.addAttacker(opp.getCreaturesInPlay().get(0), me);
        final List<List<Integer>> opts = OppMin.attackOptions(g, c, opp, me);
        Assert.assertEquals(opts.size(), 2, opts.toString());
        Assert.assertEquals(opts.get(0).size(), 2, "every creature that can attack: " + opts);
        Assert.assertTrue(opts.get(1).isEmpty(), "no attack: " + opts);
        // Forge AI declared all of them already: only "none" is new
        c.addAttacker(opp.getCreaturesInPlay().get(1), me);
        Assert.assertEquals(OppMin.attackOptions(g, c, opp, me), List.of(List.of()));
    }

    @Test
    public void theOpponentPicksOneReplyAcrossItsWorlds() {
        // beliefMin with the oppTurn leaf: in its two worlds "attack" (point 0, answer 0) wins it the game (0) in one and
        // is neutral (0.5) in the other; pass is neutral in both. It picks the attack, which is then played in our world.
        final List<String> ours = new ArrayList<>();
        final OppMin.Cfg oc = new OppMin.Cfg(8, OppMin.CHARM_CAP, 2);
        oc.refute = true;
        oc.budgetByDepth = true;
        final OppMin.Out o = OppMin.beliefMin(oc, 2, 4, (u, s) -> {
            final boolean attack = s.policy != null ? "[1, 2]".equals(s.policy.get("oatk:8")) : s.respAt(0) == 0;
            final Map<String, String> r = new java.util.LinkedHashMap<>();
            if (attack) r.put("oatk:8", "[1, 2]");
            if (u == 0) ours.add(String.valueOf(attack));
            final double v = u == 0 ? (attack ? 0.0 : 1.0) : attack ? (u == 1 ? 0.0 : 0.5) : 0.5;
            return new OppMin.Leaf(v, "t", Collections.singletonList(new OppMin.Point(1, "p" + u, 4, 1))).realized(r);
        });
        Assert.assertEquals(ours, List.of("true"), "one play in our world, the attack: " + o.beliefQ);
        Assert.assertEquals(o.firstLeaf.v, 0.0, 1e-12, o.beliefQ);
    }
}
