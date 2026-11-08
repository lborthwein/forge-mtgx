package forge.ai.simulation;

import java.util.ArrayList;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.CubeComboPlayerController;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane ledger-sweep-1107, ERROR-LEDGER row 45 (census frame jace-empty.a: our library empty, Jace, Wielder of Mysteries
 * in hand, four Islands). Jace's +1 mills two and draws a card; with Jace out and the library empty that draw wins now.
 * The probe ran in a copy of this position, so the draw was replaced by Jace's win and no card moved: the template's +1
 * had no DRAW, and the planner found only the next draw step's win (turn+1, as the pilot took it). Key
 * {@code forge.probeEmptyDraw} off: no DRAW, no same-turn line. On: the probe repeats with our library stocked from our
 * exile, the +1 has its DRAW, and the planner finds the same-turn empty-draw win.
 */
public class EmptyDrawProbeTest extends AITest {

    /** live-fixes-1107: forge.liveFixes defaults the key on in this jar; each test starts from key off, as written. */
    @BeforeMethod
    public void keyOffFirst() {
        reset();
    }

    @AfterMethod
    public void reset() {
        ForcedWinProbe.setEmptyDrawProbeForTest(false);
    }

    private static Game jaceEmpty() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", ForcedWinSolverTest.frame(
                "Jace, Wielder of Mysteries", "Island;Island;Island;Island", 20, "", "Plains;Plains;Plains", 20, "human"));
        final Player me = g.getPlayers().get(0);
        for (Card c : new ArrayList<>(me.getCardsIn(ZoneType.Library))) {
            g.getAction().exile(c, null, null);
        }
        Assert.assertTrue(me.getCardsIn(ZoneType.Library).isEmpty());
        Assert.assertFalse(me.getCardsIn(ZoneType.Exile).isEmpty());
        return g;
    }

    private static boolean plusOneDraws(ACard jace) {
        for (Abil a : jace.onBattlefield) {
            if (a.kind == AbilKind.ACTIVATE && !a.unplayable && a.has(EffKind.DRAW)) {
                return true;
            }
        }
        return false;
    }

    private static ForcedWinPlanner.Result sameTurn(Game g) {
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ((CubeComboPlayerController) me.getController()).cubeCombo().forcedWin();
        final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
        final ForcedWinPlanner.Limits lim = new ForcedWinPlanner.Limits();
        lim.horizon = 1;
        return ForcedWinPlanner.plan(pos, lim);
    }

    @Test
    public void keyOffThePlusOneHasNoDrawAndNoSameTurnLine() {
        final Game g = jaceEmpty();
        final Player me = g.getPlayers().get(0);
        final ACard jace = ForcedWinProbe.learn(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand,
                "Jace, Wielder of Mysteries"), 1, new ForcedWinProbe.Stats());
        Assert.assertTrue(jace.emptyDrawWin, jace.toString());
        Assert.assertFalse(plusOneDraws(jace), jace.onBattlefield.toString());
        Assert.assertTrue(sameTurn(g).lines.isEmpty(), "no same-turn line with the key off");
    }

    @Test
    public void keyOnThePlusOneDrawsAndTheSameTurnLineIsFound() {
        ForcedWinProbe.setEmptyDrawProbeForTest(true);
        final Game g = jaceEmpty();
        final Player me = g.getPlayers().get(0);
        final ACard jace = ForcedWinProbe.learn(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand,
                "Jace, Wielder of Mysteries"), 1, new ForcedWinProbe.Stats());
        Assert.assertTrue(jace.emptyDrawWin, jace.toString());
        Assert.assertTrue(plusOneDraws(jace), jace.onBattlefield.toString());
        final ForcedWinPlanner.Result r = sameTurn(g);
        Assert.assertFalse(r.lines.isEmpty(), r.summary());
        Assert.assertTrue(r.lines.get(0).toString().contains("empty-draw"), r.lines.get(0).toString());
    }
}
