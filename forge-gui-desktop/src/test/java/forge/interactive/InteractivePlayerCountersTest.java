package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Every counter on a player reaches the browser as one generic map (lane player-counters-1107; owner 2026-11-07:
 * "Where can I see my energy counter total?"). Before, a seat's view carried life and poison and no other player counter,
 * so energy, experience, rad and tickets had nowhere to show.
 *
 * <p>Each view is one a seat's GUI sends and one a watch channel sends ({@link InteractiveState#seatView}), for both
 * seats. The bench {@link StateEncoder} (the bridge's state, the RL featurizer's parity input, the search's payloads) is
 * checked to stay byte-identical whether or not a browser view was built from it, and to carry no player counter map.</p>
 */
public class InteractivePlayerCountersTest extends AITest {

    private Game game;
    private Player seat0;
    private Player seat1;

    private void newGame() {
        game = initAndCreateGame();
        seat0 = game.getRegisteredPlayers().get(0);
        seat1 = game.getRegisteredPlayers().get(1);
        addCard("Grizzly Bears", seat0);
        game.getAction().checkStateEffects(true);
    }

    private List<JsonObject> views() {
        final List<JsonObject> out = new ArrayList<>();
        for (Player viewer : List.of(seat0, seat1)) {
            final int seat = StateEncoder.playerIndex(game, viewer);
            out.add(InteractiveState.seatView(game, viewer, seat));
            out.add(InteractiveState.seatView(game, viewer, seat, false));
        }
        return out;
    }

    private static JsonObject player(final JsonObject view, final int index) {
        for (JsonElement p : view.getAsJsonArray("players")) {
            if (p.getAsJsonObject().get("index").getAsInt() == index) {
                return p.getAsJsonObject();
            }
        }
        throw new AssertionError("no player " + index + " in " + view);
    }

    @Test
    public void everyPlayerCounterReachesEveryViewAsOneMap() {
        newGame();
        seat0.setCounters(CounterEnumType.ENERGY, 3, null, false);
        seat0.setCounters(CounterEnumType.TICKET, 1, null, false);
        seat1.setCounters(CounterEnumType.EXPERIENCE, 2, null, false);
        seat1.setCounters(CounterEnumType.RAD, 4, null, false);
        seat1.setCounters(CounterEnumType.POISON, 5, null, false);
        for (JsonObject view : views()) {
            final JsonObject mine = player(view, 0).getAsJsonObject(InteractiveState.PLAYER_COUNTERS);
            assertNotNull(mine, "seat 0's counters in " + view);
            assertEquals(mine.toString(), "{\"ENERGY\":3,\"TICKET\":1}", "sorted, as Forge names them");
            final JsonObject theirs = player(view, 1).getAsJsonObject(InteractiveState.PLAYER_COUNTERS);
            assertEquals(theirs.toString(), "{\"EXPERIENCE\":2,\"POISON\":5,\"RAD\":4}",
                    "public to both seats; poison is in the map and stays as its own scalar");
            assertEquals(player(view, 1).get("poison").getAsInt(), 5);
        }
    }

    @Test
    public void noCountersMeansNoField() {
        newGame();
        for (JsonObject view : views()) {
            assertFalse(player(view, 0).has(InteractiveState.PLAYER_COUNTERS), "a player with no counters: " + view);
            assertFalse(player(view, 1).has(InteractiveState.PLAYER_COUNTERS));
        }
        seat0.setCounters(CounterEnumType.ENERGY, 2, null, false);
        assertTrue(player(views().get(0), 0).has(InteractiveState.PLAYER_COUNTERS));
        assertTrue(seat0.payEnergy(2, null), "spend it all");
        assertEquals(seat0.getCounters(CounterEnumType.ENERGY), 0);
        for (JsonObject view : views()) {
            assertFalse(player(view, 0).has(InteractiveState.PLAYER_COUNTERS), "spent to zero: the field goes: " + view);
        }
    }

    @Test
    public void benchEncoderIsUnchanged() {
        newGame();
        seat0.setCounters(CounterEnumType.ENERGY, 3, null, false);
        seat1.setCounters(CounterEnumType.EXPERIENCE, 1, null, false);
        for (Player seat : List.of(seat0, seat1)) {
            final String before = StateEncoder.encode(game, seat).toString();
            views();
            final String after = StateEncoder.encode(game, seat).toString();
            assertEquals(after, before, "building a browser view does not change the bench encoding");
            for (JsonElement p : StateEncoder.encode(game, seat).getAsJsonArray("players")) {
                assertFalse(p.getAsJsonObject().has(InteractiveState.PLAYER_COUNTERS),
                        "the bench encoder's players carry no counter map");
            }
        }
    }
}
