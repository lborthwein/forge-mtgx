package forge.ai.simulation;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane ledger-sweep2-1107, ledger row 66 (solverCfg planExpandCap). After Doomsday the library is a pile of lazily chosen
 * positions; a Memory Jar activation then draws them, and each draw branches over every class of card the pile may hold,
 * all inside one expansion, before the node limit is ever checked. In the wu-reads-1107 void that was 15^5 states and the
 * JVM's heap ran out. With the key off one expansion builds thousands of branches past the node budget; with it on it
 * builds at most what the planner has nodes left for, and the cut is reported.
 */
public class PlanExpandCapTest extends forge.ai.AITest {
    private static final String LIB = "Brainstorm;Ponder;Preordain;Dark Ritual;Lotus Petal;Gitaxian Probe;Thassa's Oracle;"
            + "Consider;Island;Island";

    private static ForcedWinModel.Position position() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                "Doomsday", "Swamp;Swamp;Swamp;Memory Jar", 20, "", "Island", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + LIB));
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        return s.readPosition(g, s.knownTop(g));
    }

    private static ForcedWinPlanner.Limits limits(final boolean cap) {
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = 1;
        lim.nodes = 400;
        lim.ms = -1L; // nodes only: the test does not depend on the host's speed
        lim.expandCap = cap;
        return lim;
    }

    @Test
    public void oneExpansionBuildsNoMoreBranchesThanTheNodesLeft() {
        final ForcedWinModel.Position pos = position();
        final ForcedWinPlanner.Result off = ForcedWinPlanner.plan(pos, limits(false));
        // off: Jar's draws over the lazy pile branch far past the 400-node budget inside one expansion, uncounted
        Assert.assertTrue(off.maxExpand > 2_000, "off: " + off.maxExpand + " " + off.summary() + " " + pos.describe());
        Assert.assertEquals(off.expandStops, 0, off.summary());
        final ForcedWinPlanner.Result on = ForcedWinPlanner.plan(pos, limits(true));
        Assert.assertTrue(on.maxExpand <= 401, "on: " + on.maxExpand + " " + on.summary());
        Assert.assertTrue(on.expandStops > 0, on.summary());
        Assert.assertTrue(on.exhausted, on.summary());
        Assert.assertTrue(on.summary().contains("expandStops="), on.summary());
        Assert.assertFalse(off.summary().contains("expandStops"), off.summary());
    }

    @Test
    public void theKeyIsOffByDefaultAndReachesThePlannerLimits() {
        Assert.assertFalse(new ForcedWinPlanner.Limits().expandCap);
        Assert.assertEquals(ForcedWinSolver.Config.fromProps(k -> null).planExpandCap, forge.game.EngineFixes.liveFixes()); // live-fixes-1107: default follows the switch
        final Map<String, String> on = Map.of("planExpandCap", "true");
        Assert.assertTrue(ForcedWinSolver.Config.fromProps(on::get).planExpandCap);
    }
}
