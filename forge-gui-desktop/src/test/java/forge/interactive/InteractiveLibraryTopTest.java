package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The top card of a library on the browser wire, {@code players[i].libraryTop} (lane forge-top-card-reveal-1015;
 * the owner's report 2026-10-14T04-17-09, Courser of Kruphix in play: "I can't see the top card of my deck").
 *
 * <p>Both kinds of permission, from both seats, and the no-leak half: a seat Forge does not let look at a library's
 * top card never receives that card's name, nor a {@code libraryTop} field for that library at all. The bench
 * {@link StateEncoder} is checked to stay library-free.</p>
 */
public class InteractiveLibraryTopTest extends AITest {

    /** A game with both libraries stacked: {@code top0} and {@code top1} are the cards each seat draws next. */
    private static final class Table {
        private Game game;
        private Player seat0;
        private Player seat1;
        private Card top0;
        private Card top1;
    }

    private Table table(final String top0, final String top1) {
        final Table t = new Table();
        t.game = initAndCreateGame();
        t.seat0 = t.game.getRegisteredPlayers().get(0);
        t.seat1 = t.game.getRegisteredPlayers().get(1);
        for (Player p : List.of(t.seat0, t.seat1)) {
            for (int i = 0; i < 3; i++) {
                addCardToZone("Runeclaw Bear", p, ZoneType.Library);
            }
        }
        t.top0 = putOnTop(top0, t.seat0);
        t.top1 = putOnTop(top1, t.seat1);
        return t;
    }

    private Card putOnTop(final String name, final Player owner) {
        final Card card = createCard(name, owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());
        owner.getZone(ZoneType.Library).add(card, 0);
        return card;
    }

    private static void settle(final Table t) {
        t.game.getAction().checkStateEffects(true);
    }

    /** What {@code viewer}'s browser receives about {@code owner}'s library top: the card JSON, or null. */
    private static JsonObject libraryTopSeenBy(final Table t, final Player viewer, final Player owner) {
        final int seat = StateEncoder.playerIndex(t.game, viewer);
        final JsonObject view = InteractiveState.seatView(t.game, viewer, seat);
        final int ownerIndex = StateEncoder.playerIndex(t.game, owner);
        for (JsonElement element : view.getAsJsonArray("players")) {
            final JsonObject player = element.getAsJsonObject();
            if (player.get("index").getAsInt() == ownerIndex) {
                return player.has(InteractiveState.LIBRARY_TOP)
                        ? player.getAsJsonObject(InteractiveState.LIBRARY_TOP) : null;
            }
        }
        throw new AssertionError("no player " + ownerIndex + " in " + view);
    }

    /** The whole payload {@code viewer}'s browser receives, as text: a hidden card's name must not be in it. */
    private static String wireOf(final Table t, final Player viewer) {
        return InteractiveState.seatView(t.game, viewer, StateEncoder.playerIndex(t.game, viewer)).toString();
    }

    private static void assertShows(final JsonObject top, final Card card, final int ownerSeat) {
        assertNotNull(top, card.getName() + " must be on the wire");
        assertEquals(top.get("name").getAsString(), card.getName());
        assertEquals(top.get("fid").getAsInt(), card.getId());
        assertEquals(top.get("zone").getAsString(), "Library");
        assertEquals(top.get("ownerSeat").getAsInt(), ownerSeat);
        assertEquals(top.get("controllerSeat").getAsInt(), ownerSeat);
    }

    // ------------------------------------------------------------------------------------- nobody may look

    @Test(timeOut = 900000)
    public void noPermissionNoLibraryTopForEitherSeat() {
        final Table t = table("Lightning Bolt", "Counterspell");
        addCard("Grizzly Bears", t.seat0);
        settle(t);
        for (Player viewer : List.of(t.seat0, t.seat1)) {
            assertNull(libraryTopSeenBy(t, viewer, t.seat0));
            assertNull(libraryTopSeenBy(t, viewer, t.seat1));
            final String wire = wireOf(t, viewer);
            assertFalse(wire.contains(InteractiveState.LIBRARY_TOP), wire);
            assertFalse(wire.contains("Lightning Bolt"), wire);
            assertFalse(wire.contains("Counterspell"), wire);
        }
    }

    // ----------------------------------------------------- public: "play with the top card ... revealed"

    @Test(timeOut = 900000)
    public void courserRevealsItsControllersTopToBothSeatsAndNothingElse() {
        final Table t = table("Lightning Bolt", "Counterspell");
        addCard("Courser of Kruphix", t.seat0);
        settle(t);
        assertShows(libraryTopSeenBy(t, t.seat0, t.seat0), t.top0, 0);
        assertShows(libraryTopSeenBy(t, t.seat1, t.seat0), t.top0, 0);
        // The other library stays hidden from both, its owner included: Courser reveals only its controller's.
        assertNull(libraryTopSeenBy(t, t.seat0, t.seat1));
        assertNull(libraryTopSeenBy(t, t.seat1, t.seat1));
        assertFalse(wireOf(t, t.seat0).contains("Counterspell"));
        assertFalse(wireOf(t, t.seat1).contains("Counterspell"));
    }

    @Test(timeOut = 900000)
    public void anOpponentsCourserRevealsTheirTopToTheOtherSeat() {
        final Table t = table("Lightning Bolt", "Counterspell");
        addCard("Courser of Kruphix", t.seat1);
        settle(t);
        assertShows(libraryTopSeenBy(t, t.seat0, t.seat1), t.top1, 1);
        assertShows(libraryTopSeenBy(t, t.seat1, t.seat1), t.top1, 1);
        assertNull(libraryTopSeenBy(t, t.seat0, t.seat0));
        assertFalse(wireOf(t, t.seat1).contains("Lightning Bolt"));
    }

    @Test(timeOut = 900000)
    public void futureSightIsPublicToo() {
        final Table t = table("Lightning Bolt", "Counterspell");
        addCard("Future Sight", t.seat1);
        settle(t);
        assertShows(libraryTopSeenBy(t, t.seat0, t.seat1), t.top1, 1);
        assertShows(libraryTopSeenBy(t, t.seat1, t.seat1), t.top1, 1);
    }

    // ------------------------------------------------ private: "you may look at the top card ... any time"

    @Test(timeOut = 900000)
    public void citadelShowsItsControllerOnlyAndNeverLeaksToTheOpponent() {
        final Table t = table("Lightning Bolt", "Counterspell");
        addCard("Bolas's Citadel", t.seat1);
        settle(t);
        assertShows(libraryTopSeenBy(t, t.seat1, t.seat1), t.top1, 1);
        // The no-leak assertion: the other seat gets no field for that library, and the card's name is nowhere.
        assertNull(libraryTopSeenBy(t, t.seat0, t.seat1));
        assertFalse(wireOf(t, t.seat0).contains("Counterspell"), wireOf(t, t.seat0));
        assertFalse(wireOf(t, t.seat0).contains(InteractiveState.LIBRARY_TOP), wireOf(t, t.seat0));
    }

    @Test(timeOut = 900000)
    public void vizierShowsSeatZeroItsOwnTopAndSeatOneNothing() {
        final Table t = table("Lightning Bolt", "Counterspell");
        addCard("Vizier of the Menagerie", t.seat0);
        settle(t);
        assertShows(libraryTopSeenBy(t, t.seat0, t.seat0), t.top0, 0);
        assertNull(libraryTopSeenBy(t, t.seat1, t.seat0));
        assertFalse(wireOf(t, t.seat1).contains("Lightning Bolt"), wireOf(t, t.seat1));
    }

    // ----------------------------------------------------------------------------- it follows the game

    @Test(timeOut = 900000)
    public void theNextCardIsShownAfterADrawAndNothingOnceTheSourceLeaves() {
        final Table t = table("Lightning Bolt", "Counterspell");
        final Card next = createCard("Shock", t.seat0);
        next.setGameTimestamp(t.game.getNextTimestamp());
        t.seat0.getZone(ZoneType.Library).add(next, 1);
        final Card courser = addCard("Courser of Kruphix", t.seat0);
        settle(t);
        assertShows(libraryTopSeenBy(t, t.seat1, t.seat0), t.top0, 0);

        t.seat0.drawCard();
        settle(t);
        assertShows(libraryTopSeenBy(t, t.seat1, t.seat0), next, 0);
        // The card drawn is in its owner's hand now: the other seat no longer receives it.
        assertFalse(wireOf(t, t.seat1).contains("Lightning Bolt"), wireOf(t, t.seat1));

        t.game.getAction().exile(courser, null, null);
        settle(t);
        assertNull(libraryTopSeenBy(t, t.seat0, t.seat0));
        assertNull(libraryTopSeenBy(t, t.seat1, t.seat0));
        assertFalse(wireOf(t, t.seat1).contains("Shock"), wireOf(t, t.seat1));
    }

    /**
     * A card that reaches the library while the view tracker is frozen has no zone on its view yet, and
     * {@code CardView#canBeShownTo} reads a zoneless card as "visible to all". The library top waits for the zone.
     */
    @Test(timeOut = 900000)
    public void aCardWhoseViewHasNoZoneYetIsNotShown() {
        final Table t = table("Lightning Bolt", "Counterspell");
        settle(t);
        t.game.getTracker().freeze();
        try {
            final Card frozen = putOnTop("Ancestral Recall", t.seat1);
            assertNull(frozen.getView().getZone(), "precondition: the zone change is still queued");
            assertTrue(frozen.getView().canBeShownTo(t.seat0.getView()), "precondition: the hazard is real");
            assertFalse(InteractiveState.mayReceiveLibraryTop(frozen.getView(), t.seat0.getView()));
            assertNull(libraryTopSeenBy(t, t.seat0, t.seat1));
            assertFalse(wireOf(t, t.seat0).contains("Ancestral Recall"), wireOf(t, t.seat0));
        } finally {
            t.game.getTracker().unfreeze();
        }
    }

    // ------------------------------------------------------------------------------ the play affordance

    /**
     * Playing the shown card is a selectCard control on the same id: the bridge offers a control for every card whose
     * {@code getAllPossibleAbilities} is non-empty ({@code InteractiveGuiGame}), with {@code cardId} = the card's id,
     * which is the {@code fid} of {@code libraryTop}.
     */
    @Test(timeOut = 900000)
    public void aLandOnTopUnderCourserIsPlayableByItsController() {
        final Table t = table("Lightning Bolt", "Forest");
        final Player active = t.game.getPhaseHandler().getPlayerTurn();
        assertEquals(active, t.seat1, "precondition: seat 1 is in its main phase");
        addCard("Courser of Kruphix", t.seat1);
        settle(t);
        final JsonObject shown = libraryTopSeenBy(t, t.seat1, t.seat1);
        assertShows(shown, t.top1, 1);
        final List<SpellAbility> abilities = t.top1.getAllPossibleAbilities(t.seat1, true);
        assertTrue(abilities.stream().anyMatch(SpellAbility::isLandAbility), "Courser lets it be played: " + abilities);
        assertEquals(shown.get("fid").getAsInt(), t.top1.getId());
    }

    // ----------------------------------------------------------------------- the observation is unchanged

    @Test(timeOut = 900000)
    public void theBenchEncoderStillEnumeratesNoLibrary() {
        final Table t = table("Lightning Bolt", "Counterspell");
        addCard("Courser of Kruphix", t.seat0);
        addCard("Bolas's Citadel", t.seat1);
        settle(t);
        for (Player viewer : List.of(t.seat0, t.seat1)) {
            final JsonObject bench = StateEncoder.encode(t.game, viewer);
            final JsonArray players = bench.getAsJsonArray("players");
            for (JsonElement player : players) {
                assertFalse(player.getAsJsonObject().has(InteractiveState.LIBRARY_TOP));
                assertFalse(player.getAsJsonObject().has("revealedTop"));
            }
            assertFalse(bench.toString().contains("Lightning Bolt"), bench.toString());
            assertFalse(bench.toString().contains("Counterspell"), bench.toString());
        }
    }
}
