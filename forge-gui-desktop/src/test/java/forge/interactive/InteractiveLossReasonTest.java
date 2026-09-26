package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

/**
 * The terminal's {@code losses}: each losing seat's GameLossReason, read off a real
 * finished game. GameEndReason says AllOpponentsLost for every one of these.
 */
public class InteractiveLossReasonTest extends AITest {

    /** Seat order as the match was configured. Read before the loss: a loser leaves game.getPlayers(). */
    private static List<RegisteredPlayer> registered(final Game game) {
        final List<RegisteredPlayer> out = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            out.add(player.getRegisteredPlayer());
        }
        return out;
    }

    private static JsonObject only(final JsonArray losses) {
        assertEquals(losses.size(), 1, losses.toString());
        return losses.get(0).getAsJsonObject();
    }

    @Test
    public void aConcessionIsReportedAsConceded() {
        final Game game = initAndCreateGame();
        final List<RegisteredPlayer> seats = registered(game);
        game.getPlayers().get(0).concede();
        game.getAction().checkGameOverCondition();
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.AllOpponentsLost);
        final JsonObject loss = only(InteractiveMain.lossesOf(game.getOutcome(), seats));
        assertEquals(loss.get("seat").getAsInt(), 0);
        assertEquals(loss.get("reason").getAsString(), "Conceded");
        assertFalse(loss.has("spell"));
    }

    @Test
    public void aLifeZeroLossIsReportedAsLifeReachedZero() {
        final Game game = initAndCreateGame();
        final List<RegisteredPlayer> seats = registered(game);
        game.getPlayers().get(1).setLife(0, null);
        game.getAction().checkGameOverCondition();
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.AllOpponentsLost);
        final JsonObject loss = only(InteractiveMain.lossesOf(game.getOutcome(), seats));
        assertEquals(loss.get("seat").getAsInt(), 1);
        assertEquals(loss.get("reason").getAsString(), "LifeReachedZero");
    }

    @Test
    public void poisonIsReportedAsPoisoned() {
        final Game game = initAndCreateGame();
        final List<RegisteredPlayer> seats = registered(game);
        game.getPlayers().get(0).setPoisonCounters(10, null);
        game.getAction().checkGameOverCondition();
        final JsonObject loss = only(InteractiveMain.lossesOf(game.getOutcome(), seats));
        assertEquals(loss.get("seat").getAsInt(), 0);
        assertEquals(loss.get("reason").getAsString(), "Poisoned");
    }

    @Test
    public void anAlternateWinIsReportedAsOpponentWonWithItsCard() {
        final Game game = initAndCreateGame();
        final List<RegisteredPlayer> seats = registered(game);
        game.getPlayers().get(1).altWinBySpellEffect("Thassa's Oracle");
        game.getAction().checkGameOverCondition();
        assertEquals(game.getOutcome().getWinCondition(), GameEndReason.WinsGameSpellEffect);
        final JsonObject loss = only(InteractiveMain.lossesOf(game.getOutcome(), seats));
        assertEquals(loss.get("seat").getAsInt(), 0);
        assertEquals(loss.get("reason").getAsString(), "OpponentWon");
        assertEquals(loss.get("spell").getAsString(), "Thassa's Oracle");
    }

    @Test
    public void theTerminalCarriesThemAndAnOlderCallerStillOmitsThem() throws Exception {
        final JsonArray losses = new JsonArray();
        final JsonObject loss = new JsonObject();
        loss.addProperty("seat", 0);
        loss.addProperty("reason", "Conceded");
        losses.add(loss);
        assertEquals(terminalLine(losses).getAsJsonArray("losses"), losses);
        assertFalse(terminalLine(null).has("losses"));
    }

    private static JsonObject terminalLine(final JsonArray losses) throws Exception {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream out = new java.io.PrintStream(bytes, true, "UTF-8");
        final InteractiveProtocol.Channel channel = new InteractiveProtocol.Channel(
                new java.io.BufferedReader(new java.io.StringReader("")), out, "session-1");
        channel.terminal("g1", 1, "AllOpponentsLost", 1, losses);
        final String[] lines = bytes.toString("UTF-8").trim().split("\n");
        return com.google.gson.JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
    }
}
