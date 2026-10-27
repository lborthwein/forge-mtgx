package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.bench.StateEncoder;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

/**
 * The room a dungeon is in reaches the browser (lane undercity-room-1027; owner report 2026-10-27T19-40-28, Gauntlet
 * forge-mvr2ph2e: "I don't think Secret Entrance is my Next step in the undercity"). The game had ventured into Secret
 * Entrance and then Forge, so the next room was Trap! or Arena. The seat's view of its Undercity carried no room, and the
 * client reads a dungeon with no room as "not ventured, next: Secret Entrance".
 *
 * <p>Each venture is Forge's own {@code VentureEffect} ({@code DB$ Venture | Dungeon$ Undercity}, resolved for seat 0), and
 * the branch at a fork is chosen by seat 0's controller, as in a game. Every view is the one a seat's GUI sends and the one
 * a watch channel sends ({@link InteractiveState#seatView}), for both seats.</p>
 *
 * <p>The bench {@link StateEncoder} (the bridge's state, the search's payloads, the label dumps) is checked to carry no
 * room and to encode the same bytes whether or not the browser view was built from it.</p>
 */
public class InteractiveDungeonRoomTest extends AITest {

    private static final String ROOM = "currentRoom";

    private Game game;
    private Player seat0;
    private Player seat1;
    private Card host;
    private RoomChooser chooser;

    /** Seat 0's controller: Forge's AI, except that it takes the named room at a dungeon's fork. */
    private static final class RoomChooser extends PlayerControllerAi {
        private String next;
        private final List<Set<String>> offered = new ArrayList<>();

        RoomChooser(final Game game, final Player player) {
            super(game, player, player.getLobbyPlayer());
        }

        @Override
        public SpellAbility chooseSingleSpellForEffect(final List<SpellAbility> spells, final SpellAbility sa,
                                                       final String title, final Map<String, Object> params) {
            final Set<String> rooms = new TreeSet<>();
            for (SpellAbility room : spells) {
                rooms.add(room.getParam("RoomName"));
            }
            offered.add(rooms);
            for (SpellAbility room : spells) {
                if (room.getParam("RoomName").equals(next)) {
                    return room;
                }
            }
            throw new AssertionError("the fork offers " + rooms + ", not " + next);
        }
    }

    private void newGame() {
        game = initAndCreateGame();
        seat0 = game.getRegisteredPlayers().get(0);
        seat1 = game.getRegisteredPlayers().get(1);
        chooser = new RoomChooser(game, seat0);
        seat0.dangerouslySetController(chooser);
        host = addCard("Grizzly Bears", seat0);
        game.getAction().checkStateEffects(true);
    }

    /** Seat 0 ventures into Undercity; at a fork it takes {@code branch}. Returns seat 0's Undercity. */
    private Card venture(final String branch) {
        chooser.next = branch;
        final SpellAbility sa = AbilityFactory.getAbility("DB$ Venture | Defined$ You | Dungeon$ Undercity", host);
        sa.setActivatingPlayer(seat0);
        AbilityUtils.resolve(sa);
        game.getAction().checkStateEffects(true);
        final Card undercity = undercity(seat0);
        assertNotNull(undercity, "seat 0 has an Undercity in its command zone");
        return undercity;
    }

    private static Card undercity(final Player player) {
        for (Card card : player.getCardsIn(ZoneType.Command)) {
            if ("Undercity".equals(card.getName())) {
                return card;
            }
        }
        return null;
    }

    /** Every view of the table: each seat's GUI view and each seat's watch-channel view. */
    private List<JsonObject> views() {
        final List<JsonObject> out = new ArrayList<>();
        for (Player viewer : List.of(seat0, seat1)) {
            final int seat = StateEncoder.playerIndex(game, viewer);
            out.add(InteractiveState.seatView(game, viewer, seat));
            out.add(InteractiveState.seatView(game, viewer, seat, false));
        }
        return out;
    }

    /** The zone entry for {@code fid} in {@code view} (any player's hand, battlefield, graveyard, exile or command). */
    private static JsonObject entry(final JsonObject view, final int fid) {
        for (JsonElement player : view.getAsJsonArray("players")) {
            for (String zone : List.of("hand", "battlefield", "graveyard", "exile", "command")) {
                final JsonElement cards = player.getAsJsonObject().get(zone);
                if (cards == null || !cards.isJsonArray()) {
                    continue;
                }
                for (JsonElement card : cards.getAsJsonArray()) {
                    final JsonObject object = card.getAsJsonObject();
                    if (object.has("fid") && object.get("fid").getAsInt() == fid) {
                        return object;
                    }
                }
            }
        }
        return null;
    }

    private void assertRoomInEveryView(final Card dungeon, final String room) {
        assertEquals(dungeon.getCurrentRoom(), room, "the engine's room");
        for (JsonObject view : views()) {
            final JsonObject listed = entry(view, dungeon.getId());
            assertNotNull(listed, "the Undercity is listed: " + view);
            assertEquals(listed.has(ROOM) ? listed.get(ROOM).getAsString() : null, room, listed.toString());
        }
    }

    private void assertNoRoomInAnyView(final Card card) {
        for (JsonObject view : views()) {
            final JsonObject listed = entry(view, card.getId());
            assertNotNull(listed, card + " is listed: " + view);
            assertFalse(listed.has(ROOM), card + " carries no room: " + listed);
        }
    }

    /** The owner's game: Secret Entrance, then Forge. The table must say Forge (next: Trap! or Arena). */
    @Test(timeOut = 600000)
    public void ventureTwiceSendsForge() {
        newGame();
        Card undercity = venture(null);
        assertRoomInEveryView(undercity, "Secret Entrance");
        undercity = venture("Forge");
        assertEquals(chooser.offered, List.of(Set.of("Forge", "Lost Well")), "the fork after Secret Entrance");
        assertRoomInEveryView(undercity, "Forge");
        assertNoRoomInAnyView(host);
    }

    /** The room is Forge's own RoomName$, punctuation and all: Trap!, Archive (the printed card says Archives), Throne. */
    @Test(timeOut = 600000)
    public void roomNamesAreForgesOwn() {
        newGame();
        venture(null);
        venture("Forge");
        assertRoomInEveryView(venture("Trap!"), "Trap!");
        assertRoomInEveryView(venture(null), "Archive");
        assertRoomInEveryView(venture(null), "Throne of the Dead Three");
        assertEquals(chooser.offered, List.of(Set.of("Forge", "Lost Well"), Set.of("Arena", "Trap!")), "the two forks");
    }

    /** Nothing in a room sends no room: an Undercity nobody has ventured into, a designation, an ordinary permanent. */
    @Test(timeOut = 600000)
    public void noRoomWithoutAVenture() {
        newGame();
        final Card unventured = CardFactory.getCard(StaticData.instance().getAllTokens().getToken("undercity"), seat1, game);
        unventured.setGamePieceType(GamePieceType.DUNGEON);
        game.getAction().moveToCommand(unventured, null);
        game.getAction().becomeMonarch(seat1, "CN2");
        game.getAction().checkStateEffects(true);
        assertNull(unventured.getCurrentRoom());
        assertNoRoomInAnyView(unventured);
        assertNoRoomInAnyView(host);
        for (JsonObject view : views()) {
            assertFalse(view.toString().contains("\"" + ROOM + "\""), "no object is in a room: " + view);
        }
    }

    /** The field is the browser's only: the bench encoder sends no room and its bytes do not depend on the view. */
    @Test(timeOut = 600000)
    public void theBenchEncoderIsUntouched() {
        newGame();
        venture(null);
        venture("Forge");
        for (Player seat : List.of(seat0, seat1)) {
            final String before = StateEncoder.encode(game, seat).toString();
            views();
            final String after = StateEncoder.encode(game, seat).toString();
            assertEquals(after, before, "building the browser views leaves the bench encoding as it was");
            assertFalse(before.contains("\"" + ROOM + "\""), "the bench encoder sends no room: " + before);
        }
    }
}
