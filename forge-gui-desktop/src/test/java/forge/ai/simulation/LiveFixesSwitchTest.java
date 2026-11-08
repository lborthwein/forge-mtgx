package forge.ai.simulation;

import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.EngineFixes;

/** live-fixes-1107: forge.liveFixes sets the default of the solver fix keys; an explicit key wins; the line names it. */
public class LiveFixesSwitchTest {
    private static ForcedWinSolver.Config cfg(final Map<String, String> kv) {
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    @Test
    public void switchSetsTheSolverDefaults() {
        final boolean was = EngineFixes.liveFixes();
        try {
            EngineFixes.setLiveFixesForTest(true);
            final ForcedWinSolver.Config on = cfg(Map.of());
            AssertJUnit.assertTrue(on.staleStatics && on.turnLimits && on.lineUncapped);
            final ForcedWinSolver.Config explicit = cfg(Map.of("staleStatics", "false", "turnLimits", "false", "lineUncapped", "false"));
            AssertJUnit.assertFalse(explicit.staleStatics || explicit.turnLimits || explicit.lineUncapped);
            EngineFixes.setLiveFixesForTest(false);
            final ForcedWinSolver.Config off = cfg(Map.of());
            AssertJUnit.assertFalse(off.staleStatics || off.turnLimits || off.lineUncapped);
            final ForcedWinSolver.Config keyed = cfg(Map.of("staleStatics", "true"));
            AssertJUnit.assertTrue(keyed.staleStatics);
            AssertJUnit.assertTrue(EngineFixes.describe().startsWith("[live-fixes] forge.liveFixes=false"));
        } finally {
            EngineFixes.setLiveFixesForTest(was);
        }
    }

    /** The JVM flags resolve from the switch at class load (surefire runs this with or without -Dforge.liveFixes). */
    @Test
    public void flagsFollowTheSwitchAtLoad() {
        final boolean expect = Boolean.parseBoolean(System.getProperty("forge.liveFixes", "true"));
        if (System.getProperty("forge.copyRobust") == null) {
            AssertJUnit.assertEquals(expect, EngineFixes.copyRobust());
        }
        if (System.getProperty("forge.copyAltCost") == null) {
            AssertJUnit.assertEquals(expect, EngineFixes.copyAltCost());
        }
        if (System.getProperty("forge.researchFixes") == null) {
            AssertJUnit.assertEquals(expect, EngineFixes.researchFixes());
        }
    }
}
