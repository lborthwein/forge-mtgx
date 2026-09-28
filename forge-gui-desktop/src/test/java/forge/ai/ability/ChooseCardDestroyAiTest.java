package forge.ai.ability;

import forge.ai.AITest;
import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
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
 *
 * <p>The fix is behind the per-player option aiFixes0928 (lane yardstick-0928): ON for the AI here; with it off (the
 * default, upstream Forge AI) the AI still chooses Blightsteel Colossus; shadow decides as off and counts the spot.
 */
public class ChooseCardDestroyAiTest extends AITest {

    private AiFixes.Counters counters;

    private Game setup(boolean withCrypt) {
        return setup(withCrypt, AiFixes.Mode.ON);
    }

    private Game setup(boolean withCrypt, AiFixes.Mode mode) {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        final LobbyPlayerAi lobby = (LobbyPlayerAi) ai.getLobbyPlayer();
        lobby.setAiFixes0928(mode);
        counters = AiFixes.count(lobby, game);
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

    @Test
    public void optionOffChoosesTheUpstreamBestCard() {
        Game game = setup(true, AiFixes.Mode.OFF);
        AssertJUnit.assertEquals("upstream: Blightsteel chosen, nothing destroyed", 0,
                countCardsWithName(game, "Mana Crypt", ZoneType.Graveyard));
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Mana Crypt", ZoneType.Battlefield));
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Blightsteel Colossus", ZoneType.Battlefield));
        AssertJUnit.assertEquals("off records nothing", 0, counters.fired(AiFixes.Kind.CHOOSE_DESTROY));
    }

    @Test
    public void shadowDecidesAsOffAndCountsTheSpot() {
        Game game = setup(true, AiFixes.Mode.SHADOW);
        AssertJUnit.assertEquals(0, countCardsWithName(game, "Mana Crypt", ZoneType.Graveyard));
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Mana Crypt", ZoneType.Battlefield));
        AssertJUnit.assertEquals(1, counters.fired(AiFixes.Kind.CHOOSE_DESTROY));
        AssertJUnit.assertEquals(1, counters.changed(AiFixes.Kind.CHOOSE_DESTROY));
    }

    @Test
    public void onCountsTheSpotItChanged() {
        setup(true, AiFixes.Mode.ON);
        AssertJUnit.assertEquals(1, counters.fired(AiFixes.Kind.CHOOSE_DESTROY));
        AssertJUnit.assertEquals(1, counters.changed(AiFixes.Kind.CHOOSE_DESTROY));
    }
}
