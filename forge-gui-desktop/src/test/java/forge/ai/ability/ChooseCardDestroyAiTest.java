package forge.ai.ability;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Chaos Defiler's Battle Cannon ("for each opponent, choose a nonland permanent that player controls. Destroy one of
 * them chosen at random") must not choose an indestructible permanent when the opponent controls one that can be
 * destroyed: the random pick only draws from cards without indestructible, so choosing Blightsteel Colossus made the
 * whole trigger do nothing (owner-friend report 2026-09-28T02-35-39: Mana Crypt was available).
 */
public class ChooseCardDestroyAiTest extends AITest {

    private Game setup(boolean withCrypt) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        addCard("Blightsteel Colossus", opp);
        addCard("The One Ring", opp);
        if (withCrypt) {
            addCard("Mana Crypt", opp);
        }
        addCard("Island", opp);
        for (int i = 0; i < 5; i++) {
            addCard("Swamp", ai);
        }
        fillLibrary(ai, 10);
        fillLibrary(opp, 10);
        game.getAction().checkStateEffects(true);
        // Chaos Defiler enters the battlefield under the AI (as it did from Necromancy); its ETB goes on the stack.
        game.getAction().moveToPlay(addCardToZone("Chaos Defiler", ai, ZoneType.Hand), null, null);
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        AssertJUnit.assertEquals("the ETB trigger is on the stack", 1, game.getStack().size());
        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
        return game;
    }

    @Test
    public void destroysTheDestroyableChoice() {
        Game game = setup(true);
        AssertJUnit.assertEquals("Mana Crypt is destroyed", 1, countCardsWithName(game, "Mana Crypt", ZoneType.Graveyard));
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Blightsteel Colossus", ZoneType.Battlefield));
        AssertJUnit.assertEquals(1, countCardsWithName(game, "The One Ring", ZoneType.Battlefield));
    }

    @Test
    public void onlyIndestructibleOptionsStillResolve() {
        Game game = setup(false);
        AssertJUnit.assertTrue(game.getStack().isEmpty());
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Blightsteel Colossus", ZoneType.Battlefield));
        AssertJUnit.assertEquals(1, countCardsWithName(game, "The One Ring", ZoneType.Battlefield));
    }
}
