package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

/**
 * Lane line-adoption-1107 (solverCfg anyWindow): from our upkeep the planner goes on to this turn's main phase with a MAIN
 * step (drawing the draw step's card), not to our next turn; and the follower waits for main phase 1 at the MAIN step.
 */
public class AnyWindowTest extends forge.ai.AITest {
    private static String upkeepFrame() {
        // our upkeep, turn 7: Thassa's Oracle in hand, two Islands; a two-card library, so after the draw step's card the
        // Oracle (devotion 2) wins
        return ForcedWinSolverTest.frame("Thassa's Oracle", "Island;Island", 20, "", "Island", 20, "human")
                .replace("activephase=MAIN1", "activephase=UPKEEP")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island");
    }

    @Test
    public void upkeepPlansThroughThisTurnsMainPhase() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", upkeepFrame());
        Assert.assertTrue(g.getPhaseHandler().is(PhaseType.UPKEEP));
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = 1;
        // key off: in the upkeep only instants, and no pass within this turn: no line
        final ForcedWinModel.Position off = s.readPosition(g, s.knownTop(g));
        Assert.assertTrue(ForcedWinPlanner.plan(off, lim).lines.isEmpty(), off.describe());
        // key on: MAIN, then the Oracle this turn
        final ForcedWinModel.Position on = s.readPosition(g, s.knownTop(g));
        s.markWindow(on, g.getPhaseHandler());
        Assert.assertTrue(on.preMain && on.preMainDraw && on.offMain);
        final ForcedWinPlanner.Result res = ForcedWinPlanner.plan(on, lim);
        Assert.assertFalse(res.lines.isEmpty(), res.summary() + " " + on.describe());
        final ForcedWinPlanner.Line l = res.lines.get(0);
        Assert.assertEquals(l.winTurn, 0, String.valueOf(l));
        Assert.assertEquals(l.steps.get(0).kind, AbilKind.MAIN, String.valueOf(l));
        Assert.assertTrue(l.offMain);
        // followed live from the upkeep: MAIN waits for main phase 1, then the Oracle wins this turn
        s.adopt(l);
        ForcedWinSolverTest.play(g, 7);
        Assert.assertTrue(g.isGameOver(), "the line won this turn");
        Assert.assertTrue(g.getOutcome().isWinner(me.getLobbyPlayer()), "our seat won");
    }
}
