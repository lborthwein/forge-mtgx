package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/** Lane unified-search-1106: zero verified worlds is unknown, not a refutation. */
public class UnverifiedLineTest {
    private static ForcedWinSolver.Eval eval(final double unopposed, final double belief, final int worlds) {
        final ForcedWinSolver.Eval e = new ForcedWinSolver.Eval(
                new ForcedWinPlanner.Line(List.of(), List.of(), 0, 20, 0, 0, List.of(), "engine"));
        e.pUnopposed = unopposed;
        e.pBelief = belief;
        e.worldsRun = worlds;
        return e;
    }

    @Test
    public void unknownVersusRefuted() {
        AssertJUnit.assertTrue("no world verified", eval(1, 0, 0).unverified());
        AssertJUnit.assertFalse("refuted in a world", eval(1, 0, 3).unverified());
        AssertJUnit.assertFalse("did not win unopposed", eval(0, 0, 0).unverified());
    }
}
