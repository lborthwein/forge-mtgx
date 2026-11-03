package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.SkipException;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;

/**
 * endstep-rows-1103 harness (not a unit test; skipped unless -Dforge.frameLabel.rows is set): HonestLabel on Forge
 * positions exported from mtgx tapes (tools/forge/from-frame.mjs serializeFrame). Rows: JSON {rows: [{id, frame, seat}]}
 * (seat: the mtgx seat; from-frame writes seat 0 as "human" = Forge player 0). Spec: -Dforge.frameLabel.spec = a label
 * block (the A6 rev-3 PRIMARY label + overrides). Out: -Dforge.frameLabel.out (JSONL: id + HonestLabel.Result#toJson).
 * The opponent's hidden hand and library are re-drawn from the belief in every world (the label's honest mode), and our
 * library below its known top is shuffled, exactly as for a Forge tape row. R4 evaluation material: measurement only.
 */
public class FrameLabelRun extends SimulationTest {

    static Game install(final String text) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        final LobbyPlayerAi a = new LobbyPlayerAi("Seat0", null);
        a.setAiProfile("Default");
        final LobbyPlayerAi b = new LobbyPlayerAi("Seat1", null);
        b.setAiProfile("Default");
        players.add(new RegisteredPlayer(new Deck()).setPlayer(a));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(b));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Match match = new Match(rules, players, "FrameLabelRun");
        final Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        final ForcedWinSolverTest.NowState gs = new ForcedWinSolverTest.NowState();
        gs.parse(Arrays.asList(text.split("\\R")));
        gs.applyNow(game);
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    @Test
    public void run() throws Exception {
        final String rowsPath = System.getProperty("forge.frameLabel.rows");
        if (rowsPath == null) {
            throw new SkipException("harness only");
        }
        final JsonObject spec = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(
                System.getProperty("forge.frameLabel.spec"))), StandardCharsets.UTF_8)).getAsJsonObject();
        final JsonArray rows = JsonParser.parseString(new String(Files.readAllBytes(Paths.get(rowsPath)),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rows");
        final StringBuilder out = new StringBuilder();
        long k = 0;
        for (JsonElement e : rows) {
            final JsonObject r = e.getAsJsonObject();
            final JsonObject o = new JsonObject();
            o.addProperty("id", r.get("id").getAsString());
            try {
                final Game g = install(new String(Files.readAllBytes(Paths.get(r.get("frame").getAsString())),
                        StandardCharsets.UTF_8));
                final Player me = g.getPlayers().get(r.get("seat").getAsInt());
                final HonestLabel.Seat seat = new HonestLabel.Seat(HonestLabel.Spec.parse(spec));
                final long key = ForcedWinSolver.mix(spec.has("seed") ? spec.get("seed").getAsLong() : 1L, ++k);
                final HonestLabel.Result res = seat.label(g, me, key);
                o.add("label", res.toJson());
                o.addProperty("phase", String.valueOf(g.getPhaseHandler().getPhase()));
                o.addProperty("active", g.getPhaseHandler().getPlayerTurn() == me ? "me" : "opp");
            } catch (RuntimeException | Error ex) {
                o.addProperty("error", String.valueOf(ex));
            }
            out.append(o).append('\n');
            System.err.println("[frame-label] " + o.toString().substring(0, Math.min(400, o.toString().length())));
        }
        Files.write(Paths.get(System.getProperty("forge.frameLabel.out")), out.toString().getBytes(StandardCharsets.UTF_8));
    }
}
