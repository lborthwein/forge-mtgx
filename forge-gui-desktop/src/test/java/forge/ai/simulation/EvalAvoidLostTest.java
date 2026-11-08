package forge.ai.simulation;

import java.util.List;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.ForcedWinModel.AbilKind;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane next-turn-1107 (solverCfg evalAvoidLost): owner dd2 T9 evaluated three next-turn lines that differed only in the
 * pile's protection card, all tapping Urborg on the next turn; Urborg was gone after the opponent's turn in every world.
 * The key's parts: lines that differ only in pile filler share a skeleton; the cards a line uses after its pass; a card
 * in a graveyard or exile, or another player's, is gone.
 */
public class EvalAvoidLostTest extends SimulationTest {
    private static ForcedWinPlanner.Step step(final AbilKind kind, final Card c) {
        final ForcedWinModel.ACard a = new ForcedWinModel.ACard();
        a.id = c.getId();
        a.name = c.getName();
        return new ForcedWinPlanner.Step(kind, a, null, -1);
    }

    @Test
    public void skeletonUsedAfterPassAndGone() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0);
        final Player opp = g.getPlayers().get(1);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        final Card doomsday = addCardToZone("Doomsday", me, ZoneType.Hand);
        final Card urborg = addCardToZone("Urborg, Tomb of Yawgmoth", me, ZoneType.Battlefield);
        final Card gush = addCardToZone("Gush", me, ZoneType.Library);
        final Card swamp = addCardToZone("Swamp", me, ZoneType.Graveyard);
        final Card theirs = addCardToZone("Island", opp, ZoneType.Battlefield);

        final ForcedWinPlanner.Step dd1 = step(AbilKind.SPELL, doomsday);
        dd1.pile.addAll(List.of(gush.getId(), 1001));
        final ForcedWinPlanner.Step dd2 = step(AbilKind.SPELL, doomsday);
        dd2.pile.addAll(List.of(gush.getId(), 1002));
        final ForcedWinPlanner.Step tap = step(AbilKind.FLOAT, urborg);
        final ForcedWinPlanner.Step g1 = step(AbilKind.SPELL, gush);
        final ForcedWinPlanner.Line a = new ForcedWinPlanner.Line(List.of(dd1, ForcedWinPlanner.Step.PASS, tap, g1), List.of("Gush", "x"),
                1, 10, 0, 0, List.of(), "t");
        final ForcedWinPlanner.Line b = new ForcedWinPlanner.Line(List.of(dd2, ForcedWinPlanner.Step.PASS, tap, g1), List.of("Gush", "y"),
                1, 10, 0, 0, List.of(), "t");
        final ForcedWinPlanner.Line c = new ForcedWinPlanner.Line(List.of(dd1, ForcedWinPlanner.Step.PASS, g1), List.of("Gush", "x"),
                1, 10, 0, 0, List.of(), "t");
        AssertJUnit.assertEquals("pile filler only: one skeleton", ForcedWinSolver.skeleton(a), ForcedWinSolver.skeleton(b));
        AssertJUnit.assertFalse("a different step list: another skeleton", ForcedWinSolver.skeleton(a).equals(ForcedWinSolver.skeleton(c)));
        AssertJUnit.assertEquals(Set.of(urborg.getId(), gush.getId()), ForcedWinSolver.usedAfterPass(a));
        AssertJUnit.assertEquals(Set.of(gush.getId()), ForcedWinSolver.usedAfterPass(c));

        AssertJUnit.assertFalse("hand", ForcedWinSolver.gone(g, me, doomsday.getId()));
        AssertJUnit.assertFalse("our battlefield", ForcedWinSolver.gone(g, me, urborg.getId()));
        AssertJUnit.assertFalse("library is not lost", ForcedWinSolver.gone(g, me, gush.getId()));
        AssertJUnit.assertTrue("graveyard", ForcedWinSolver.gone(g, me, swamp.getId()));
        AssertJUnit.assertTrue("another player's", ForcedWinSolver.gone(g, me, theirs.getId()));
        AssertJUnit.assertTrue("no such card", ForcedWinSolver.gone(g, me, 987654));

        final ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        AssertJUnit.assertFalse("default off", off.evalAvoidLost);
        AssertJUnit.assertFalse("default off", off.putBackKeep);
        final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(k -> "evalAvoidLost".equals(k) || "putBackKeep".equals(k) ? "true" : null);
        AssertJUnit.assertTrue(on.evalAvoidLost);
        AssertJUnit.assertTrue(on.putBackKeep);
    }
}
