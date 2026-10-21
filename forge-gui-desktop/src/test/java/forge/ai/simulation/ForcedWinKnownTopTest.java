package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * combo-assembly-1021 (G1 attempt 2): a card the seat knows on top of its library (B0: its own top-tutor placement)
 * is the solver's known top, and hideOwnLibrary hides only the unknown rest.
 */
public class ForcedWinKnownTopTest extends SimulationTest {

    private Game board() {
        final Game game = initAndCreateGame();
        final Player a = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        final String[] lib = {"Doomsday", "Island", "Swamp", "Ponder", "Forest", "Lightning Bolt", "Mountain", "Plains"};
        for (int i = 0; i < 16; i++) {
            addCardToZone(lib[i % lib.length], a, ZoneType.Library);
        }
        return game;
    }

    private static ForcedWinSolver solver(final Player me) {
        final ForcedWinSolver.Config c = new ForcedWinSolver.Config();
        c.hideOwnLibrary = true;
        return new ForcedWinSolver(me, c);
    }

    @Test
    public void aNotedTopCardIsTheKnownTop() {
        final Game g = board();
        final Player a = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(a);
        AssertJUnit.assertEquals(0, s.knownTopCount(g));
        final Card top = a.getCardsIn(ZoneType.Library).get(0);
        s.notePlaced(List.of(top));
        AssertJUnit.assertEquals(1, s.knownTopCount(g));
        // a known card under an unknown top is not a known top (the known top is a prefix)
        final ForcedWinSolver s2 = solver(a);
        s2.notePlaced(List.of(a.getCardsIn(ZoneType.Library).get(1)));
        AssertJUnit.assertEquals(0, s2.knownTopCount(g));
    }

    @Test
    public void hidingKeepsTheKnownTopAndShufflesOnlyTheRest() {
        final Game g = board();
        final Player a = g.getPlayers().get(0);
        final List<Card> before = new ArrayList<>(a.getCardsIn(ZoneType.Library));
        ForcedWinSolver.shuffleUnknownOwnLibrary(a, 1, new Random(7));
        final List<Card> after = new ArrayList<>(a.getCardsIn(ZoneType.Library));
        AssertJUnit.assertSame("the known top stays", before.get(0), after.get(0));
        AssertJUnit.assertEquals(before.size(), after.size());
        AssertJUnit.assertTrue(after.containsAll(before));
        AssertJUnit.assertFalse("the unknown rest is re-dealt", before.subList(1, before.size()).equals(after.subList(1, after.size())));
    }

    @Test
    public void aShuffleForgetsTheKnownTop() {
        final Game g = board();
        final Player a = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(a);
        AssertJUnit.assertEquals(0, s.knownTopCount(g)); // starts watching
        s.notePlaced(List.of(a.getCardsIn(ZoneType.Library).get(0)));
        AssertJUnit.assertEquals(1, s.knownTopCount(g));
        a.shuffle(null);
        AssertJUnit.assertEquals(0, s.knownTopCount(g));
    }
}
