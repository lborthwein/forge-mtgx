package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * pr209-followups (review of #209): {@code solverCfg unknownWorlds}, key off vs key on, at both places the solver caches
 * a position as having no line (gated: no planner line; declined: no line taken).
 */
public class UnknownWorldsCacheTest {
    private static ForcedWinSolver.Eval eval(final double unopposed, final int worlds) {
        final ForcedWinSolver.Eval e = new ForcedWinSolver.Eval(
                new ForcedWinPlanner.Line(List.of(), List.of(), 0, 20, 0, 0, List.of(), "engine"));
        e.pUnopposed = unopposed;
        e.pBelief = 0;
        e.worldsRun = worlds;
        return e;
    }

    private static ForcedWinSolver.Config cfg(final boolean key) {
        return ForcedWinSolver.Config.fromProps(k -> "unknownWorlds".equals(k) ? String.valueOf(key) : null);
    }

    @Test
    public void keyOffCachesEveryDeclinedAndGatedPosition() {
        final ForcedWinSolver.Config off = cfg(false);
        AssertJUnit.assertFalse(off.unknownWorlds);
        AssertJUnit.assertTrue("no world verified", ForcedWinSolver.cachesDeclined(off, eval(1, 0)));
        AssertJUnit.assertTrue("refuted in a world", ForcedWinSolver.cachesDeclined(off, eval(1, 3)));
        AssertJUnit.assertTrue("no win unopposed", ForcedWinSolver.cachesDeclined(off, eval(0, 0)));
        AssertJUnit.assertTrue("no best line", ForcedWinSolver.cachesDeclined(off, null));
        AssertJUnit.assertTrue("engine line unverified", ForcedWinSolver.cachesGated(off, true));
        AssertJUnit.assertTrue("engine line verified or none", ForcedWinSolver.cachesGated(off, false));
    }

    @Test
    public void keyOnLeavesOnlyUnverifiedLinesUncached() {
        final ForcedWinSolver.Config on = cfg(true);
        AssertJUnit.assertTrue(on.unknownWorlds);
        AssertJUnit.assertFalse("no world verified: unknown, not cached", ForcedWinSolver.cachesDeclined(on, eval(1, 0)));
        AssertJUnit.assertTrue("refuted in a world", ForcedWinSolver.cachesDeclined(on, eval(1, 3)));
        AssertJUnit.assertTrue("no win unopposed", ForcedWinSolver.cachesDeclined(on, eval(0, 0)));
        AssertJUnit.assertTrue("no best line", ForcedWinSolver.cachesDeclined(on, null));
        AssertJUnit.assertFalse("engine line unverified: not cached", ForcedWinSolver.cachesGated(on, true));
        AssertJUnit.assertTrue("engine line verified or none", ForcedWinSolver.cachesGated(on, false));
    }
}
