package forge.ai.simulation;

import java.util.ArrayList;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.game.Game;
import forge.game.GameStage;
import forge.game.player.Player;

/**
 * integrate-1110: places where solver-distill-cc-1109 changed what happens with every key of that lane off, restored to
 * the live branch's behavior on the integration branch. The frames are card facts used as tests only (Thassa's Oracle
 * wins when our library is at most our devotion to blue).
 */
public class IntegrateKeysOffTest extends forge.ai.AITest {

    /** A generator spec records planLoops only when it is on: a tape of a live-branch jar still compares equal. */
    @Test
    public void planLoopsIsRecordedOnlyWhenOn() {
        final ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        Assert.assertFalse(off.planLoops);
        Assert.assertFalse(HonestGenerator.solverCfgJson(off).has("planLoops"), HonestGenerator.solverCfgJson(off).toString());
        final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(k -> "planLoops".equals(k) ? "true" : null);
        Assert.assertTrue(HonestGenerator.solverCfgJson(on).get("planLoops").getAsBoolean());
    }

    /** Two Islands in play, the Oracle in hand, a two-card library: cast it and win. */
    private static Game oracleGame(final boolean started) {
        final String frame = ForcedWinSolverTest.frame("Thassa's Oracle", "Island;Island", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Island;Island");
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        if (!started) {
            g.setAge(GameStage.Mulligan);
        }
        return g;
    }

    private static ForcedWinSolver solver(final Game g) {
        final Player me = g.getPlayers().get(0);
        return ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
    }

    /**
     * Only the potential check (keepGate's question before the first turn) plays a not-started game's sandboxes as a
     * started one. A plain screen of a not-started game is the live branch's: its probes learn nothing there.
     */
    @Test
    public void onlyThePotentialCheckStartsAGameThatHasNotStarted() {
        Assert.assertFalse(ForcedWinProbe.PRE_GAME.get());
        final Game live = oracleGame(true);
        Assert.assertTrue(solver(live).screen(live, new ArrayList<>(), 4000, -1L).lines > 0, "a started game: the line");
        final Game pre = oracleGame(false);
        final ForcedWinSolver.Screen sc = solver(pre).screen(pre, new ArrayList<>(), 4000, -1L);
        Assert.assertEquals(sc.lines, 0, "a not-started game, a plain screen: as on the live branch (" + sc.best + ")");
        Assert.assertFalse(ForcedWinProbe.PRE_GAME.get());
        final Game pre2 = oracleGame(false);
        final ForcedWinSolver.GoldfishOpts o = new ForcedWinSolver.GoldfishOpts();
        o.ms = -1L;
        o.window = false;
        Assert.assertTrue(solver(pre2).potential(pre2, new ArrayList<>(), o, 40, true, 12_000L), "the potential check sees it");
        Assert.assertFalse(ForcedWinProbe.PRE_GAME.get(), "and leaves the switch as it found it");
        Assert.assertEquals(pre2.getAge(), GameStage.Mulligan, "the live game is untouched");
    }
}
