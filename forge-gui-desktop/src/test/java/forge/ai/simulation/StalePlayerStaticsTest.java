package forge.ai.simulation;

import java.util.ArrayList;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Engine-generated fixture: no card-specific search logic. */
public class StalePlayerStaticsTest extends SimulationTest {
    @Test
    public void removedHiddenSourceMustNotLeaveItsPlayerEffect() {
        checkRemovedSource("Unlimited");
    }

    @Test
    public void removedHiddenSourceMustNotLeaveItsNumericHandLimit() {
        checkRemovedSource("3");
    }

    private void checkRemovedSource(String limit) {
        Game live = initAndCreateGame();
        Player owner = live.getPlayers().get(1);
        live.getPhaseHandler().devModeSet(PhaseType.MAIN2, live.getPlayers().get(0));
        Card source = addCardToZone("Plains", owner, ZoneType.Library);
        source.addStaticAbility("Mode$ Continuous | EffectZone$ Library | Affected$ You | SetMaxHandSize$ " + limit);
        live.getAction().checkStateEffects(true);
        boolean unlimited = limit.equals("Unlimited");
        AssertJUnit.assertEquals("fixture applies a player-only effect", unlimited, owner.isUnlimitedHandSize());
        if (!unlimited) AssertJUnit.assertEquals(3, owner.getMaxHandSize());
        Game world = live;
        world.getPlayers().get(1).getZone(ZoneType.Library).setCards(new ArrayList<Card>());
        GameCopier copier = new GameCopier(world, true);
        copier.setStaleStatics(true);
        Game oldNode = copier.makeCopy();
        AssertJUnit.assertEquals("p1 alone incorrectly retains the player effect", unlimited, oldNode.getPlayers().get(1).isUnlimitedHandSize());
        if (!unlimited) AssertJUnit.assertEquals(3, oldNode.getPlayers().get(1).getMaxHandSize());
        copier = new GameCopier(world, true);
        copier.setStaleStatics(true);
        copier.setStalePlayerStatics(true);
        Game node = copier.makeCopy();
        AssertJUnit.assertFalse("removed source no longer grants unlimited hand size", node.getPlayers().get(1).isUnlimitedHandSize());
        AssertJUnit.assertEquals(node.getPlayers().get(1).getStartingHandSize(), node.getPlayers().get(1).getMaxHandSize());
        AssertJUnit.assertEquals("live player must not be modified", unlimited, owner.isUnlimitedHandSize());
        if (!unlimited) AssertJUnit.assertEquals(3, owner.getMaxHandSize());
        AssertJUnit.assertTrue("the flag propagates to later copies", node.stalePlayerStatics);
        AssertJUnit.assertFalse(new GameCopier(node, true).makeCopy().getPlayers().get(1).isUnlimitedHandSize());
        world.stalePlayerStatics = true;
        GameCopier inherited = new GameCopier(world, true);
        inherited.setStaleStatics(true);
        AssertJUnit.assertFalse("a flagged belief world also repairs its next copy",
                inherited.makeCopy().getPlayers().get(1).isUnlimitedHandSize());
    }
}
