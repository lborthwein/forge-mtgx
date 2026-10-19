package forge.bench;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane block-search-1019 (addendum): {@link StateEncoder#encode} iterated {@code game.getPlayers()}, which drops a player
 * who has lost, so the terminal view of the owner's poison win (session d5b5c797) had no AI seat and the client drew it
 * at 20 life, 0 poison and empty zones. Every seat is encoded now; an eliminated one is flagged {@code "lost": true} and
 * keeps its final public state under the same visibility rules. A player still in the game is encoded as before.
 */
public class StateEncoderLostPlayerTest extends AITest {

    private static JsonObject seat(final JsonObject view, final int index) {
        final JsonArray ps = view.getAsJsonArray("players");
        for (int i = 0; i < ps.size(); i++) {
            final JsonObject p = ps.get(i).getAsJsonObject();
            if (p.get("index").getAsInt() == index) {
                return p;
            }
        }
        throw new AssertionError("seat " + index + " missing from " + ps);
    }

    @Test
    public void aPoisonLossKeepsTheLoserInTheTerminalView() {
        final Game game = initAndCreateGame();
        final Player loser = game.getRegisteredPlayers().get(0);
        final Player winner = game.getRegisteredPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DAMAGE, winner, 8);
        addCard("Forest", loser);
        addCard("Plains", loser).setTapped(true);
        addCardToZone("Black Lotus", loser, ZoneType.Graveyard);
        final Card hidden = addCardToZone("Titan of Industry", loser, ZoneType.Hand);
        addCardToZone("Plains", loser, ZoneType.Library);
        addCard("Blightsteel Colossus", winner);

        final JsonObject before = StateEncoder.encode(game, winner);
        assertEquals(before.getAsJsonArray("players").size(), 2);
        assertFalse(seat(before, 0).has("lost"), "a player in the game has no lost flag (encoded as before)");

        loser.setPoisonCounters(11, winner);
        game.getAction().checkStateEffects(true);
        assertTrue(loser.hasLost(), "ten or more poison counters lose the game");
        assertFalse(game.getPlayers().contains(loser), "the engine's in-game list drops the loser");

        for (Player viewer : new Player[] {winner, loser}) {
            final JsonObject v = StateEncoder.encode(game, viewer);
            assertEquals(v.getAsJsonArray("players").size(), 2, "every seat is in the view");
            assertEquals(v.getAsJsonArray("life").size(), 2);
            final JsonObject l = seat(v, 0);
            assertTrue(l.get("lost").getAsBoolean(), "the loser is flagged");
            assertTrue(l.get("poison").getAsInt() >= 10, "the loser's final poison: " + l.get("poison"));
            assertEquals(l.get("life").getAsInt(), loser.getLife());
            assertEquals(l.getAsJsonArray("battlefield").size(), 2, "its battlefield");
            assertEquals(l.getAsJsonArray("graveyard").size(), 1, "its graveyard");
            assertEquals(l.get("handSize").getAsInt(), 1);
            assertEquals(l.get("librarySize").getAsInt(), 1);
            assertFalse(seat(v, 1).has("lost"), "the winner is not flagged");
        }
        // hidden zones stay hidden: the winner gets the loser's hand card only if the engine shows it to the winner
        final JsonObject w = seat(StateEncoder.encode(game, winner), 0);
        assertEquals(w.getAsJsonArray("hand").size(), hidden.getView().canBeShownTo(winner.getView()) ? 1 : 0);
        assertEquals(seat(StateEncoder.encode(game, loser), 0).getAsJsonArray("hand").size(), 1, "its own hand");
    }
}
