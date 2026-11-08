package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane unified-search-1106: the loop breaker cuts actions in one window. A followed line's long REPEAT stopped part way in
 * verification, not broken, and read as no win (Displacer Kitten + Venser + Mana Crypt: REPEAT64x, no-win@0). Key off: cut.
 */
public class LineWindowCapTest extends SimulationTest {
    private ForcedWinSolver.LineAi ai(final Game g, final Player me, final boolean uncapped) {
        final ForcedWinPlanner.Line line = new ForcedWinPlanner.Line(List.of(), List.of(), 0, 20, 0, 0, List.of(), "engine");
        final ForcedWinSolver.LineAi a = new ForcedWinSolver.LineAi(g, me, me.getController().getLobbyPlayer(), new ForcedWinSolver.Follower(line));
        a.uncapped = uncapped;
        return a;
    }

    private static int until(final ForcedWinSolver.LineAi a, final SpellAbility sa) {
        for (int i = 0; i < 1000; i++) {
            if (a.lineAction(sa) == null) {
                return i;
            }
        }
        return 1000;
    }

    @Test
    public void aLongLineIsNotCut() {
        final Game g = initAndCreateGame();
        final Player me = g.getPlayers().get(0);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        final Card c = addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        final SpellAbility sa = c.getFirstSpellAbility();
        AssertJUnit.assertTrue("key off: the window cap cuts the line", until(ai(g, me, false), sa) < 1000);
        AssertJUnit.assertEquals("key on: never cut", 1000, until(ai(g, me, true), sa));
    }
}
