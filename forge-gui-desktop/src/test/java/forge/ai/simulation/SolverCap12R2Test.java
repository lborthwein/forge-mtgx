package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.player.Player;

/**
 * solver-cap12-1030 round 2 (PREDECLARATION-solver-cap12-APPENDIX-round2 R2-3, R2-4): the general loop abstraction
 * (loopMacro) and the engine reach keys (engineAfterPlanFail, engineAnyWindow, engineGateWide). All off by default and
 * absent from a recorded config at their defaults.
 */
public class SolverCap12R2Test extends forge.ai.AITest {

    static final String[] KEYS = {"loopMacro", "engineAfterPlanFail", "engineAnyWindow", "engineGateWide"};

    @Test
    public void theRoundTwoKeysAreOffAndUnrecordedByDefault() {
        final ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        Assert.assertFalse(off.loopMacro || off.engineAfterPlanFail || off.engineAnyWindow || off.engineGateWide);
        for (String k : KEYS) {
            Assert.assertFalse(HonestGenerator.solverCfgJson(off).has(k), k + " absent at its default");
            Assert.assertFalse(off.toString().contains(k), k);
            final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(x -> k.equals(x) ? "true" : null);
            Assert.assertTrue(HonestGenerator.solverCfgJson(on).get(k).getAsBoolean(), k + " recorded when on");
        }
        Assert.assertFalse(new ForcedWinEngine.Limits().loopMacro);
    }

    static Game game(final String bf, final String gy) {
        final String f = ForcedWinSolverTest.frame("", bf, 20, "", "", 20, "human")
                .replace("humangraveyard=", "humangraveyard=" + gy).replace("humanlandsplayed=0", "humanlandsplayed=1");
        return ForcedWinSolverTest.game("Default", "Default", f);
    }

    static ForcedWinPlanner.Line search(final Game g, final boolean macro, final ForcedWinEngine.Stats st) {
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        lim.nodes = 120;
        lim.cycles = true;
        lim.passTurn = false;
        lim.loopMacro = macro;
        return ForcedWinEngine.search(g, g.getPlayers().get(0), lim, st, System.nanoTime() + 60_000_000_000L, 7L);
    }

    @Test(timeOut = 300000)
    public void aLifeLoopWithAnOutletIsALoopThenTheOutlet() {
        // a two-move land loop gains one life an iteration (no card names in the engine: it sees a repeated state with
        // a gain); the outlet search after the loop finds the 50-life payment
        final Game g = game("Fastbond;Zuran Orb;Crucible of Worlds;Aetherflux Reservoir;Forest", "Forest");
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        final ForcedWinPlanner.Line l = search(g, true, st);
        Assert.assertNotNull(l, st.summary());
        Assert.assertTrue(l.toString().contains("REPEAT"), l.toString());
        Assert.assertTrue(l.toString().contains("Aetherflux Reservoir"), l.toString());
        Assert.assertTrue(st.loops >= 1, st.summary());
    }

    @Test(timeOut = 300000)
    public void aLifeLoopWithNoOutletStopsAtItsCeiling() {
        // the stop rule: life past 1 + max(20, the largest life payment, twice their power) earns nothing, so the loop
        // stops near 21 life (about one repetition a life point here), far below the cap, and nothing is proven
        final Game g = game("Fastbond;Zuran Orb;Crucible of Worlds;Forest", "Forest");
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        Assert.assertNull(search(g, true, st), st.summary());
        Assert.assertTrue(st.loops >= 1, st.summary());
        Assert.assertTrue(st.loopReps / Math.max(1, st.loops) <= 2 * 8, "a loop's repetitions stay near the ceiling: "
                + st.summary());
        final long[] c = ForcedWinEngine.ceilings(g, g.getPlayers().get(0));
        Assert.assertEquals(c[ForcedWinEngine.LIFE], 21L);
    }

    @Test
    public void theCeilingsReadLifePaymentsAndXCosts() {
        final Game g = game("Aetherflux Reservoir;Forest", "");
        final Player me = g.getPlayers().get(0);
        Assert.assertEquals(ForcedWinEngine.ceilings(g, me)[ForcedWinEngine.LIFE], 51L, "pay 50 life needs 51");
    }

    @Test
    public void theLoopKeyLeavesOutTheGraveyardAndExile() {
        final Game a = game("Forest", "Forest"), b = game("Forest", "Island;Island");
        Assert.assertEquals(ForcedWinEngine.macroKey(a, a.getPlayers().get(0)),
                ForcedWinEngine.macroKey(b, b.getPlayers().get(0)));
        Assert.assertNotEquals(ForcedWinEngine.coarseKey(a, a.getPlayers().get(0)),
                ForcedWinEngine.coarseKey(b, b.getPlayers().get(0)));
    }

    @Test
    public void aGainInManaAloneIsALoopGain() {
        final long[] b = {0, 0, 0, 0, 0, 0, 20, 3, 1}, a = {0, 0, 0, 0, 0, 0, 20, 4, 1};
        Assert.assertTrue(ForcedWinEngine.macroGain(a, b));
        Assert.assertFalse(ForcedWinEngine.progressed(a, b), "v1 sees no progress (the Basalt/Kinnan gap)");
        Assert.assertFalse(ForcedWinEngine.macroGain(b, a));
        final long[] ceil = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                Long.MAX_VALUE, 21, 3, 99};
        Assert.assertFalse(ForcedWinEngine.relevantGain(a, b, ceil), "mana past its ceiling earns nothing");
    }
}
