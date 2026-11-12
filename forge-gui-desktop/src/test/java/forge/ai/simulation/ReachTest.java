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

    /** The knobs' variant of {@link #reach}: the same frame, with {@code o}. */
    private static ForcedWinSolver.Reach reachWith(final String library, final ForcedWinSolver.ReachOpts o) {
        final String frame = ForcedWinSolverTest.frame("", "Island;Island", 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final ForcedWinSolver.Reach r = s.reach(g, s.knownTop(g), o);
        Assert.assertFalse(r.failed, library);
        return r;
    }

    private static ForcedWinSolver.ReachOpts opts(final boolean onePass, final boolean sig, final int nameNodes) {
        final ForcedWinSolver.ReachOpts o = new ForcedWinSolver.ReachOpts();
        o.nodes = 4000;
        o.ms = -1L;
        o.maxNames = 40;
        o.window = true;
        o.nameOnePass = onePass;
        o.sig = sig;
        o.nameNodes = nameNodes;
        return o;
    }

    /**
     * The cost knobs keep reach on these frames (three cards: after next turn's draw two are left, devotion 2 wins); sig
     * screens two vanilla bears once and sums their counts.
     */
    @Test
    public void knobsKeepReach() {
        final String lib = "Thassa's Oracle;Grizzly Bears;Balduvian Bears";
        final ForcedWinSolver.Reach base = reachWith(lib, opts(false, false, -1));
        Assert.assertEquals(base.p, 1 / 3.0, 1e-9);
        Assert.assertEquals(base.names, 3);
        Assert.assertTrue(base.nodes > 0);
        Assert.assertEquals(reachWith(lib, opts(true, false, -1)).p, 1 / 3.0, 1e-9, "one pass");
        final ForcedWinSolver.Reach sig = reachWith(lib, opts(true, true, 1000));
        Assert.assertEquals(sig.p, 1 / 3.0, 1e-9, "sig");
        Assert.assertEquals(sig.groups, 2, "bears share a screen");
        Assert.assertEquals(reachWith("Grizzly Bears;Thassa's Oracle;Thassa's Oracle", opts(true, true, 1000)).p, 2 / 3.0,
                1e-9, "counts");
        Assert.assertEquals(reachWith("Grizzly Bears;Balduvian Bears;Island", opts(true, true, 1000)).p, 0.0, 1e-9);
    }

    /** A line on our next turn counts gamma in the discounted reach; with gamma 1 it is the reach. */
    @Test
    public void discountByTheLinesTurn() {
        final ForcedWinSolver.ReachOpts o = opts(true, false, -1);
        final ForcedWinSolver.Reach plain = reachWith("Thassa's Oracle;Island", o);
        Assert.assertEquals(plain.p, 0.5, 1e-9);
        Assert.assertEquals(plain.pd, 0.5, 1e-9);
        o.gamma = 0.5;
        final ForcedWinSolver.Reach disc = reachWith("Thassa's Oracle;Island", o);
        Assert.assertEquals(disc.p, 0.5, 1e-9);
        Assert.assertEquals(disc.pd, 0.25, 1e-9, "the Oracle is cast on our next turn");
    }

    @Test
    public void reachIsTheShareOfWinningDraws() {
        Assert.assertEquals(reach("Island;Island"), 0.0, 1e-9);
        Assert.assertEquals(reach("Thassa's Oracle;Island"), 0.5, 1e-9);
        Assert.assertEquals(reach("Island;Thassa's Oracle"), 0.5, 1e-9, "order unknown: the same");
        Assert.assertEquals(reach("Thassa's Oracle;Thassa's Oracle"), 1.0, 1e-9);
    }
}
