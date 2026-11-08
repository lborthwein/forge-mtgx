package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane wu-reads-1107 (solverCfg windowWorkMs): with the window cap on, offense's planner searches run within the solver's
 * one deadline (none when it has passed); with it off the planner is called exactly as before.
 */
public class WindowWorkMsTest extends forge.ai.AITest {
    @Test
    public void plannerRunsWithinTheWindowDeadline() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", AnyWindowTestFrames.upkeep());
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = 1;
        final long ms0 = lim.ms;
        final ForcedWinModel.Position on = s.readPosition(g, s.knownTop(g));
        s.markWindow(on, g.getPhaseHandler());
        // off: the plain planner (the window line exists)
        s.setWindowCap(false);
        s.setHardDeadline(forge.game.WorkClock.nanoTime() - 1);
        Assert.assertFalse(s.planCapped(on, lim).lines.isEmpty());
        // on, deadline passed: no planner search, stopped by time
        s.setWindowCap(true);
        final ForcedWinPlanner.Result none = s.planCapped(on, lim);
        Assert.assertTrue(none.lines.isEmpty());
        Assert.assertTrue(none.exhausted);
        Assert.assertEquals(none.stoppedBy, "time");
        // on, deadline ahead: the planner runs and finds the line; its own limit is restored after
        s.setHardDeadline(forge.game.WorkClock.nanoTime() + 60_000L * 1_000_000L);
        Assert.assertFalse(s.planCapped(on, lim).lines.isEmpty());
        Assert.assertEquals(lim.ms, ms0);
        s.setWindowCap(false);
        s.setHardDeadline(0L);
    }
}

/** The upkeep frame of {@link AnyWindowTest}. */
final class AnyWindowTestFrames {
    private AnyWindowTestFrames() {
    }

    static String upkeep() {
        return ForcedWinSolverTest.frame("Thassa's Oracle", "Island;Island", 20, "", "Island", 20, "human")
                .replace("activephase=MAIN1", "activephase=UPKEEP")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island");
    }
}
