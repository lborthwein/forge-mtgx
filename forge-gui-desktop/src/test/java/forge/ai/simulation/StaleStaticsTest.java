package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.List;

/**
 * Lane unified-search-1106. A copy whose opponent's hidden cards were swapped (a belief world) still lists static effects of
 * the swapped-out card. Copying that copy threw "Couldn't map", which is every engine search node. Key off: still throws.
 */
public class StaleStaticsTest extends SimulationTest {

    private Game gameWithStaticInLibrary(final String name) {
        final Game game = initAndCreateGame();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0));
        final Player opp = game.getPlayers().get(1);
        addCardToZone(name, opp, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** What the belief redraw does to a copy: the opponent's hidden cards leave the zone lists. */
    private static void swapOutLibrary(final Game copy) {
        final Player opp = copy.getPlayers().get(1);
        final List<Card> none = new ArrayList<>();
        opp.getZone(ZoneType.Library).setCards(none);
    }

    @Test
    public void copyOfABeliefWorldWithAnAllZonesStatic() {
        final Game live = gameWithStaticInLibrary("Grist, the Hunger Tide");
        final Game world = new GameCopier(live, true).makeCopy();
        swapOutLibrary(world);
        try {
            new GameCopier(world, true).makeCopy();
            AssertJUnit.fail("key off: the copy still throws");
        } catch (RuntimeException e) {
            AssertJUnit.assertTrue(e.getMessage(), e.getMessage().startsWith("Couldn't map Grist"));
        }
        final GameCopier on = new GameCopier(world, true);
        on.setStaleStatics(true);
        final Game node = on.makeCopy();
        AssertJUnit.assertEquals(0, node.getPlayers().get(1).getCardsIn(ZoneType.Library).size());
    }

    @Test
    public void keyOnKeepsAStaticOfACardStillInTheGame() {
        final Game live = gameWithStaticInLibrary("Grist, the Hunger Tide");
        final Game world = new GameCopier(live, true).makeCopy();
        final GameCopier on = new GameCopier(world, true);
        on.setStaleStatics(true);
        final Game node = on.makeCopy();
        final Card g = node.getPlayers().get(1).getCardsIn(ZoneType.Library).get(0);
        AssertJUnit.assertTrue("the static effect is reapplied in the copy", g.isCreature());
    }
}
