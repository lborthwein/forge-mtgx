package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane block-search-1019 (review S3 moved this here): the browser's terminal view keeps an eliminated seat, flagged
 * {@code "lost": true}, with its final public state (the owner's poison win, session d5b5c797, showed the AI at 20 life
 * and 0 poison). The shared {@link StateEncoder} (bridge state, search payloads) still lists in-game players only.
 */
public class InteractiveLostPlayerTest extends AITest {

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
    public void aPoisonLossKeepsTheLoserInTheBrowserViewOnly() {
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

        final JsonObject before = InteractiveState.seatView(game, winner, 1);
        assertEquals(before.getAsJsonArray("players").size(), 2);
        assertFalse(seat(before, 0).has("lost"), "a player in the game has no lost flag");

        loser.setPoisonCounters(11, winner);
        game.getAction().checkStateEffects(true);
        assertTrue(loser.hasLost(), "ten or more poison counters lose the game");

        // the shared encoder is unchanged: in-game players only
        assertEquals(StateEncoder.encode(game, winner).getAsJsonArray("players").size(), 1,
                "the bench/search encoder still drops the loser");

        for (Player viewer : new Player[] {winner, loser}) {
            final JsonObject v = InteractiveState.seatView(game, viewer, StateEncoder.playerIndex(game, viewer));
            assertEquals(v.getAsJsonArray("players").size(), 2, "every seat is in the browser view");
            final JsonObject l = seat(v, 0);
            assertTrue(l.get("lost").getAsBoolean(), "the loser is flagged");
            assertTrue(l.get("poison").getAsInt() >= 10, "the loser's final poison: " + l.get("poison"));
            assertEquals(l.get("life").getAsInt(), loser.getLife());
            assertEquals(l.getAsJsonArray("battlefield").size(), 2, "its battlefield");
            assertEquals(l.getAsJsonArray("graveyard").size(), 1, "its graveyard");
            assertEquals(l.get("handSize").getAsInt(), 1);
            assertEquals(l.get("librarySize").getAsInt(), 1);
            assertFalse(seat(v, 1).has("lost"), "the winner is not flagged");
            assertEquals(v.getAsJsonArray("life").size(), 2);
        }
        // hidden zones stay hidden: the winner gets the loser's hand card only if the engine shows it to the winner
        final JsonObject w = seat(InteractiveState.seatView(game, winner, 1), 0);
        assertEquals(w.getAsJsonArray("hand").size(), hidden.getView().canBeShownTo(winner.getView()) ? 1 : 0);
        assertFalse(InteractiveState.seatView(game, winner, 1).toString().contains("Titan of Industry")
                && !hidden.getView().canBeShownTo(winner.getView()), "a hidden hand card is never named");
    }
}
