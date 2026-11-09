package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.game.Game;
import forge.game.player.Player;

/**
 * solver-distill-cc-1109 (REACH): the planner screen in expectation over our next unknown draw. Two Islands in play, an
 * empty hand, a two-card library: next turn's draw step draws one card; a drawn Thassa's Oracle (devotion 2, one card
 * left) wins, a drawn Island does not. The plain screen sees the draw as a blank and finds nothing; reach is the share
 * of Oracles in the library.
 */
public class ReachTest extends forge.ai.AITest {

    private static double reach(final String library) {
        final String frame = ForcedWinSolverTest.frame("", "Island;Island", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final ForcedWinSolver.Screen plain = s.screen(g, s.knownTop(g), 4000, -1L);
        Assert.assertEquals(plain.lines, 0, "the plain screen sees the draw as a blank: " + plain.best);
        final ForcedWinSolver.Reach r = s.reach(g, s.knownTop(g), 4000, -1L, 40, true);
        Assert.assertFalse(r.failed, library);
        Assert.assertFalse(r.direct, library);
        return r.p;
    }

    @Test
    public void reachIsTheShareOfWinningDraws() {
        Assert.assertEquals(reach("Island;Island"), 0.0, 1e-9);
        Assert.assertEquals(reach("Thassa's Oracle;Island"), 0.5, 1e-9);
        Assert.assertEquals(reach("Island;Thassa's Oracle"), 0.5, 1e-9, "order unknown: the same");
        Assert.assertEquals(reach("Thassa's Oracle;Thassa's Oracle"), 1.0, 1e-9);
    }
}
