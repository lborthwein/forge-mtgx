package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane sdcc-loops-1111 (solverCfg engineLoopCredit). The engine search found no kill that runs through an unbounded
 * resource loop: a round that only adds mana was no loop, an X cost was paid as Forge AI pays it (zero), a land's mana
 * was lost when a sacrifice took it, an optional trigger of ours that Forge AI declines was never taken, and only the
 * first targeting trigger of a move had its target searched. Small positions of each kind, with the cheap pilot's
 * solver config: no line with the key off, a proven line with it on. The cards are named here only.
 */
public class LoopCreditTest extends forge.ai.AITest {
    /** The cheap pilot's solver config (spec cpPca, as the coverage benchmark runs it), with a wall the test host can meet. */
    private static JsonObject cfg(final boolean on, final int engineMs) {
        final JsonObject o = new JsonObject();
        for (String k : new String[] {"copyBackFace", "cycles", "engineAfterPlanFail", "engineLongLoops", "engineLoopBisect",
                "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen", "engineRerun",
                "engineTriggerTargets", "engineVerify", "followPay", "hideOwnLibrary", "historyLki", "landEntersProbe",
                "lineUncapped", "loopMarginal", "offBattlefieldLimits", "paySearch", "planDiscardWall", "planExpandCap",
                "probeSettle", "readerPerGame", "sourceColorsLive", "sourceLeaves", "sourceOutput", "stackAbilityCopies",
                "stackHistory", "stalePlayerStatics", "staleStatics", "turnLimits", "tutorCast", "unknownWorlds",
                "verifyReplan"}) {
            o.addProperty(k, "true");
        }
        o.addProperty("planOrder", "7");
        o.addProperty("planMs", "1500");
        o.addProperty("engineMs", String.valueOf(engineMs));
        o.addProperty("budgetMs", String.valueOf(engineMs + 4000));
        o.addProperty("evalReserveMs", "3000");
        o.addProperty("act", "false");
        o.addProperty("defense", "false");
        if (on) {
            o.addProperty("engineLoopCredit", "true");
        }
        return o;
    }

    private static ForcedWinSolver.Proposal best(final String hand, final String battlefield, final int oppLife,
            final boolean on, final int engineMs) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(hand,
                battlefield, 20, "", "Forest;Forest;Forest;Forest", oppLife, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(cfg(on, engineMs)));
        final List<ForcedWinSolver.Proposal> props = s.propose(g);
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        System.out.println("LOOPCREDIT on=" + on + " " + battlefield + " -> " + (best == null ? "none" : "proven="
                + best.proven(1) + " " + best.text) + " || " + s.summary());
        return best;
    }

    private static void assertProven(final ForcedWinSolver.Proposal p, final String... parts) {
        Assert.assertNotNull(p, "a line");
        Assert.assertTrue(p.proven(1), "proven: " + p.text);
        for (String part : parts) {
            Assert.assertTrue(p.text.contains(part), "the line has " + part + ": " + p.text);
        }
    }

    /** The trigger answer of a step: one target (every line made with the key off), a chain, a yes. */
    @Test
    public void triggerRefsParse() {
        final ForcedWinEngine.TriggerTargets one = new ForcedWinEngine.TriggerTargets("C33+F2");
        Assert.assertEquals(one.chain, List.of("C33+F2"));
        Assert.assertFalse(one.yes);
        final ForcedWinEngine.TriggerTargets two = new ForcedWinEngine.TriggerTargets("C33>C32");
        Assert.assertEquals(two.chain, List.of("C33", "C32"));
        final ForcedWinEngine.TriggerTargets yes = new ForcedWinEngine.TriggerTargets("Y");
        Assert.assertTrue(yes.yes);
        Assert.assertTrue(yes.chain.isEmpty());
        final ForcedWinEngine.TriggerTargets both = new ForcedWinEngine.TriggerTargets("Y>C33>C32");
        Assert.assertTrue(both.yes);
        Assert.assertEquals(both.chain, List.of("C33", "C32"));
        Assert.assertTrue(new ForcedWinEngine.TriggerTargets(null).chain.isEmpty());
        Assert.assertEquals(ForcedWinEngine.TriggerTargets.chainLength("Y>C33>C32"), 2);
        Assert.assertEquals(ForcedWinEngine.TriggerTargets.chainLength("C33"), 1);
        Assert.assertEquals(ForcedWinEngine.TriggerTargets.chainLength(null), 0);
        Assert.assertTrue(ForcedWinEngine.TriggerTargets.saysYes("Y>C1"));
        Assert.assertFalse(ForcedWinEngine.TriggerTargets.saysYes("C1"));
    }

    /** The key is off by default and parsed from the solver config. */
    @Test
    public void keyDefaultsOff() {
        Assert.assertFalse(ForcedWinSolver.Config.fromProps(k -> null).engineLoopCredit);
        Assert.assertTrue(SolverDriver.config(cfg(true, 1000)).engineLoopCredit);
        Assert.assertFalse(SolverDriver.config(cfg(false, 1000)).engineLoopCredit);
    }

    /**
     * An untap loop that nets mana (the rock taps for three, its untap costs one here), and an X creature whose counters
     * are the kill: the round is a loop, its count covers the X, and X is paid above zero.
     */
    @Test
    public void manaLoopPaysAnXSink() {
        final String bf = "Basalt Monolith;Zirda, the Dawnwaker;Plateau;Plateau;Plateau";
        Assert.assertNull(best("Walking Ballista", bf, 20, false, 3000), "key off: no line");
        final ForcedWinSolver.Proposal p = best("Walking Ballista", bf, 20, true, 20000);
        assertProven(p, "REPEAT", "FLOAT:Basalt Monolith", "Walking Ballista X=");
    }

    /** A land replayed from the graveyard and sacrificed again: its mana is floated before the sacrifice takes it. */
    @Test
    public void landLoopFloatsBeforeTheSacrifice() {
        final String bf = "Fastbond;Crucible of Worlds;Zuran Orb;Forest;Forest;Forest";
        final ForcedWinSolver.Proposal p = best("Walking Ballista", bf, 20, true, 20000);
        assertProven(p, "LAND:Forest, FLOAT:Forest", "Walking Ballista X=");
    }

    /**
     * An optional trigger of ours that Forge AI declines (the equipment's return from the graveyard) is the loop: with
     * "yes" each activation sacrifices it again; the tokens attack a turn later.
     */
    @Test
    public void optionalTriggerYesMakesTheTokenLoop() {
        final String bf = "Sword of the Meek;Thopter Foundry;Underground Sea;Underground Sea;Underground Sea;Underground Sea";
        Assert.assertNull(best("", bf, 4, false, 3000), "key off: no line");
        final ForcedWinSolver.Proposal p = best("", bf, 4, true, 20000);
        assertProven(p, "Thopter Foundry trigger->Y", "PASS", "ATTACK");
    }

    /**
     * Two chained trigger targets: the cast trigger flickers the creature whose own enters trigger must untap the engine
     * creature. Only the first target was searched; the second was Forge AI's.
     */
    @Test
    public void twoChainedTriggerTargets() {
        final String bf = "Displacer Kitten;Emry, Lurker of the Loch;Pestermite;Lotus Petal;Aetherflux Reservoir;Tundra;Tundra;Tundra";
        final ForcedWinSolver.Proposal p = best("", bf, 20, true, 20000);
        assertProven(p, "REPEAT", "Aetherflux Reservoir");
        Assert.assertTrue(p.text.matches(".*trigger->C\\d+>C\\d+.*"), "a chained trigger target: " + p.text);
    }
}
