package forge.interactive;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.event.GameEvent;
import forge.game.player.Player;
import forge.game.spellability.LandAbility;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Public card facts on the browser wire (lane bridgemeta-1018): {@code ownerSeat}, {@code isToken}, {@code isCopy}
 * and a stable {@code fid} on zone and stack entries, and {@code fid}, {@code ownerSeat}, {@code fromZone},
 * {@code isToken}, {@code isCopy} on cast, land and zone-change events, only where the viewing seat may already see
 * the card. mtgx PR #1043's reviews showed that a client inferring any of these from names, controllers or hidden
 * decks leaks hidden deck membership; the owner's ruling was "Bridge metadata first".
 *
 * <p>Both seats view every case. The bench {@link StateEncoder} is checked to carry none of the new fields and to
 * encode the same bytes whether or not the browser payload was built from it.</p>
 */
public class InteractivePublicCardFactsTest extends AITest {

    private static final String[] ZONES = {"hand", "battlefield", "graveyard", "exile", "command"};

    private Game game;
    private Player seat0;
    private Player seat1;

    private void newGame() {
        game = initAndCreateGame();
        seat0 = game.getRegisteredPlayers().get(0);
        seat1 = game.getRegisteredPlayers().get(1);
    }

    private JsonObject view(final Player viewer) {
        return InteractiveState.seatView(game, viewer, StateEncoder.playerIndex(game, viewer));
    }

    /** The zone entry for {@code fid} in {@code viewer}'s view, or null when the view lists no such card. */
    private JsonObject zoneEntry(final Player viewer, final int fid) {
        for (JsonElement player : view(viewer).getAsJsonArray("players")) {
            for (String zone : ZONES) {
                for (JsonElement entry : player.getAsJsonObject().getAsJsonArray(zone)) {
                    if (entry.getAsJsonObject().get("fid").getAsInt() == fid) {
                        return entry.getAsJsonObject();
                    }
                }
            }
        }
        return null;
    }

    private JsonObject stackEntry(final Player viewer, final int fid) {
        for (JsonElement entry : view(viewer).getAsJsonArray("stack")) {
            if (entry.getAsJsonObject().get("fid").getAsInt() == fid) {
                return entry.getAsJsonObject();
            }
        }
        return null;
    }

    private static void assertFacts(final JsonObject object, final int ownerSeat, final boolean token,
                                    final boolean copy) {
        assertNotNull(object);
        assertEquals(object.get("ownerSeat").getAsInt(), ownerSeat, object.toString());
        assertEquals(object.get("isToken").getAsBoolean(), token, object.toString());
        assertEquals(object.get("isCopy").getAsBoolean(), copy, object.toString());
    }

    private static void assertNoIdentityFacts(final JsonObject object) {
        for (String field : List.of("fid", "ownerSeat", "isToken", "isCopy", "card")) {
            assertFalse(object.has(field), field + " must be absent: " + object);
        }
    }

    /** Every event, encoded for both seats as each seat's GUI encodes it (one {@link InteractiveGameEvents.Moves} each). */
    private static final class Recorder {
        private final Game game;
        private final Player[] viewers;
        private final InteractiveGameEvents.Moves[] moves;
        private final List<List<JsonObject>> events = new ArrayList<>();

        Recorder(final Game game, final Player... viewers) {
            this.game = game;
            this.viewers = viewers;
            this.moves = new InteractiveGameEvents.Moves[viewers.length];
            for (int i = 0; i < viewers.length; i++) {
                moves[i] = new InteractiveGameEvents.Moves();
                events.add(new ArrayList<>());
            }
            game.subscribeToEvents(this);
        }

        @Subscribe
        public void on(final GameEvent event) {
            for (int i = 0; i < viewers.length; i++) {
                events.get(i).add(InteractiveGameEvents.encode(event, game, viewers[i].getView(), moves[i]));
            }
        }

        List<JsonObject> ofKind(final int viewer, final String kind) {
            final List<JsonObject> out = new ArrayList<>();
            for (JsonObject event : events.get(viewer)) {
                if (event.has("kind") && kind.equals(event.get("kind").getAsString())) {
                    out.add(event);
                }
            }
            return out;
        }

        String wire(final int viewer) {
            return events.get(viewer).toString();
        }
    }

    private SpellAbility cast(final Card card, final Player caster) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        sa.setHostCard(game.getAction().moveToStack(card, sa));
        game.getStack().add(sa);
        return sa;
    }

    // ----------------------------------------------------------------------------------------- ownership

    @Test(timeOut = 900000)
    public void aStolenPermanentShowsItsTrueOwnerToBothSeats() {
        newGame();
        final Card bear = addCard("Grizzly Bears", seat0);
        bear.addTempController(seat1, game.getNextTimestamp());
        game.getAction().controllerChangeZoneCorrection(bear);
        game.getAction().checkStateEffects(true);
        assertEquals(bear.getController(), seat1, "precondition: seat 1 controls seat 0's card");
        for (Player viewer : List.of(seat0, seat1)) {
            final JsonObject entry = zoneEntry(viewer, bear.getId());
            assertFacts(entry, 0, false, false);
            assertEquals(entry.get("controllerSeat").getAsInt(), 1);
            assertEquals(entry.get("zone").getAsString(), "Battlefield");
        }
    }

    @Test(timeOut = 900000)
    public void aStolenSpellOnTheStackShowsItsTrueOwnerAndWhereItWasCastFrom() {
        newGame();
        final Card bear = addCardToZone("Grizzly Bears", seat0, ZoneType.Exile);
        final Recorder events = new Recorder(game, seat0, seat1);
        cast(bear, seat1);
        for (Player viewer : List.of(seat0, seat1)) {
            final JsonObject entry = stackEntry(viewer, bear.getId());
            assertFacts(entry, 0, false, false);
            assertEquals(entry.get("controllerSeat").getAsInt(), 1);
        }
        for (int viewer = 0; viewer < 2; viewer++) {
            final List<JsonObject> casts = events.ofKind(viewer, "spell_cast");
            assertEquals(casts.size(), 1, events.wire(viewer));
            final JsonObject cast = casts.get(0);
            assertEquals(cast.get("playerSeat").getAsInt(), 1);
            assertEquals(cast.get("fid").getAsInt(), bear.getId());
            assertEquals(cast.get("fromZone").getAsString(), "Exile");
            assertFacts(cast, 0, false, false);
            final List<JsonObject> moves = events.ofKind(viewer, "zone_changed");
            assertEquals(moves.size(), 1, events.wire(viewer));
            assertEquals(moves.get(0).get("fromZone").getAsString(), "Exile");
            assertEquals(moves.get(0).get("toZone").getAsString(), "Stack");
            assertEquals(moves.get(0).get("fid").getAsInt(), bear.getId());
            assertEquals(moves.get(0).get("card").getAsString(), "Grizzly Bears");
            assertFacts(moves.get(0), 0, false, false);
        }
    }

    // ---------------------------------------------------------------------------------- tokens and copies

    @Test(timeOut = 900000)
    public void aTokenAndACloneAreFlaggedAndADeckCardIsNot() {
        newGame();
        final Card treasure = addToken("c_a_treasure_sac", seat0);
        final Card bear = addCard("Grizzly Bears", seat0);
        final Card clone = addCard("Clone", seat1);
        clone.addCloneState(CardFactory.getCloneStates(bear, clone, clone.getFirstSpellAbility()),
                game.getNextTimestamp());
        clone.updateStateForView();
        game.getAction().checkStateEffects(true);
        assertEquals(clone.getName(), "Grizzly Bears", "precondition: the Clone copies the bear");
        for (Player viewer : List.of(seat0, seat1)) {
            assertFacts(zoneEntry(viewer, treasure.getId()), 0, true, false);
            assertFacts(zoneEntry(viewer, bear.getId()), 0, false, false);
            final JsonObject copied = zoneEntry(viewer, clone.getId());
            assertFacts(copied, 1, false, true);
            assertEquals(copied.get("name").getAsString(), "Grizzly Bears");
        }
    }

    /**
     * #1043 recheck 2's production-shaped case: a copy of another player's permanent spell has its own fid and owner,
     * and becomes a token as it resolves (CR 111.11). It is flagged as a copy on the stack and as a token copy after.
     */
    @Test(timeOut = 900000)
    public void aCopiedPermanentSpellIsACopyOnTheStackAndATokenCopyOnceItResolves() {
        newGame();
        final Card bear = addCardToZone("Grizzly Bears", seat0, ZoneType.Hand);
        final SpellAbility original = cast(bear, seat0);
        final Recorder events = new Recorder(game, seat0, seat1);
        final SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(original, original, seat1);
        game.getStack().add(copy);
        final Card copyCard = copy.getHostCard();
        assertTrue(copyCard.getId() != bear.getId(), "precondition: the copy has its own fid");
        for (Player viewer : List.of(seat0, seat1)) {
            assertFacts(stackEntry(viewer, bear.getId()), 0, false, false);
            assertFacts(stackEntry(viewer, copyCard.getId()), 1, false, true);
        }
        for (int viewer = 0; viewer < 2; viewer++) {
            final List<JsonObject> casts = events.ofKind(viewer, "spell_cast");
            assertEquals(casts.size(), 1, events.wire(viewer));
            assertEquals(casts.get(0).get("fid").getAsInt(), copyCard.getId());
            assertFacts(casts.get(0), 1, false, true);
            assertFalse(casts.get(0).has("fromZone"), "a copy was cast from nowhere: " + casts.get(0));
        }

        game.getStack().resolveStack();
        game.getAction().checkStateEffects(true);
        final Card token = game.findById(copyCard.getId());
        assertNotNull(token, "the resolved copy is on the battlefield");
        assertTrue(token.isInZone(ZoneType.Battlefield), String.valueOf(token.getZone()));
        assertTrue(token.isToken(), "precondition: CR 111.11");
        for (Player viewer : List.of(seat0, seat1)) {
            final JsonObject entry = zoneEntry(viewer, token.getId());
            assertFacts(entry, 1, true, true);
            assertEquals(entry.get("name").getAsString(), "Grizzly Bears");
        }
    }

    // --------------------------------------------------------------------------------------- face down

    @Test(timeOut = 900000)
    public void aFaceDownCardStaysRedactedAndCarriesNoFacts() {
        newGame();
        final Card exiled = addCardToZone("Lightning Bolt", seat0, ZoneType.Exile);
        exiled.turnFaceDown(true);
        final Card morph = addCard("Ainok Tracker", seat0);
        morph.turnFaceDown(true);
        game.getAction().checkStateEffects(true);
        // A face-down exiled card is not listed for the opponent at all (CardView#canBeShownTo).
        assertNull(zoneEntry(seat1, exiled.getId()));
        // A face-down permanent is listed, redacted, and gains none of the facts.
        final JsonObject entry = zoneEntry(seat1, morph.getId());
        assertNotNull(entry, "the opponent sees a face-down permanent");
        assertTrue(entry.get("identityRedacted").getAsBoolean(), entry.toString());
        assertEquals(entry.get("name").getAsString(), "Face-down card");
        assertFalse(entry.has("isToken"), entry.toString());
        assertFalse(entry.has("isCopy"), entry.toString());
        final String wire = view(seat1).toString();
        assertFalse(wire.contains("Lightning Bolt"), wire);
        assertFalse(wire.contains("Ainok Tracker"), wire);
    }

    /**
     * Forge turns a card exiled "face down" face down only after it arrives (ChangeZoneEffect's ExileFaceDown), so the
     * zone-change event fires while the card is still face up. A move into exile therefore never names its card.
     */
    @Test(timeOut = 900000)
    public void aMoveIntoExileNamesNoCardEvenBeforeItIsTurnedFaceDown() {
        newGame();
        final Card hidden = addCardToZone("Ancestral Recall", seat0, ZoneType.Hand);
        final Recorder events = new Recorder(game, seat0, seat1);
        final Card moved = game.getAction().exile(hidden, null, null);
        moved.turnFaceDown(true);
        for (int viewer = 0; viewer < 2; viewer++) {
            final List<JsonObject> moves = events.ofKind(viewer, "zone_changed");
            assertEquals(moves.size(), 1, events.wire(viewer));
            assertEquals(moves.get(0).get("fromZone").getAsString(), "Hand");
            assertEquals(moves.get(0).get("toZone").getAsString(), "Exile");
            assertNoIdentityFacts(moves.get(0));
        }
        assertFalse(events.wire(1).contains("Ancestral Recall"), events.wire(1));
    }

    // ------------------------------------------------------------------------------------ land plays

    @Test(timeOut = 900000)
    public void aLandPlayedFromAnotherPlayersExileShowsFromZoneAndItsOwner() {
        newGame();
        final Card forest = addCardToZone("Forest", seat0, ZoneType.Exile);
        final Card island = addCardToZone("Island", seat1, ZoneType.Hand);
        final Recorder events = new Recorder(game, seat0, seat1);
        seat1.playLand(forest, new LandAbility(forest, forest.getCurrentState()));
        seat1.playLand(island, new LandAbility(island, island.getCurrentState()));
        for (int viewer = 0; viewer < 2; viewer++) {
            final List<JsonObject> lands = events.ofKind(viewer, "land_played");
            assertEquals(lands.size(), 2, events.wire(viewer));
            final JsonObject stolen = lands.get(0);
            assertEquals(stolen.get("playerSeat").getAsInt(), 1);
            assertEquals(stolen.get("card").getAsString(), "Forest");
            assertEquals(stolen.get("fid").getAsInt(), forest.getId());
            assertEquals(stolen.get("fromZone").getAsString(), "Exile");
            assertFacts(stolen, 0, false, false);
            final JsonObject own = lands.get(1);
            assertEquals(own.get("fid").getAsInt(), island.getId());
            assertEquals(own.get("fromZone").getAsString(), "Hand");
            assertFacts(own, 1, false, false);
            // The move onto the battlefield names no card (see IDENTITY_DESTINATIONS); the land event does.
            for (JsonObject move : events.ofKind(viewer, "zone_changed")) {
                assertEquals(move.get("toZone").getAsString(), "Battlefield");
                assertNoIdentityFacts(move);
            }
        }
        for (Player viewer : List.of(seat0, seat1)) {
            final JsonObject entry = zoneEntry(viewer, forest.getId());
            assertFacts(entry, 0, false, false);
            assertEquals(entry.get("controllerSeat").getAsInt(), 1);
        }
    }

    /** Without the stream's moves (the three-argument encoder) a land event names no source zone rather than guess. */
    @Test(timeOut = 900000)
    public void aLandEventWithoutTheStreamsMovesNamesNoSourceZone() {
        newGame();
        final Card forest = addCardToZone("Forest", seat1, ZoneType.Hand);
        final List<JsonObject> plain = new ArrayList<>();
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void on(final GameEvent event) {
                final JsonObject encoded = InteractiveGameEvents.encode(event, game, seat0.getView());
                if (encoded.has("kind") && "land_played".equals(encoded.get("kind").getAsString())) {
                    plain.add(encoded);
                }
            }
        });
        seat1.playLand(forest, new LandAbility(forest, forest.getCurrentState()));
        assertEquals(plain.size(), 1);
        assertFalse(plain.get(0).has("fromZone"), plain.toString());
        assertFacts(plain.get(0), 1, false, false);
    }

    // ------------------------------------------------------------------------------------ hidden zones

    @Test(timeOut = 900000)
    public void anOpponentsHiddenZonesNeverAppear() {
        newGame();
        fillLibrary(seat1, 3);
        final Card top = createCard("Time Walk", seat1);
        top.setGameTimestamp(game.getNextTimestamp());
        seat1.getZone(ZoneType.Library).add(top, 0);
        final Card inHand = addCardToZone("Ancestral Recall", seat1, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final Recorder events = new Recorder(game, seat0, seat1);

        seat1.drawCard();
        game.getAction().moveToLibrary(inHand, null);
        game.getAction().checkStateEffects(true);

        final JsonObject view0 = view(seat0);
        final String wire0 = view0.toString();
        for (String hidden : List.of("Time Walk", "Ancestral Recall", "Runeclaw Bear")) {
            assertFalse(wire0.contains(hidden), hidden + " on seat 0's wire: " + wire0);
            assertFalse(events.wire(0).contains(hidden), hidden + " in seat 0's events: " + events.wire(0));
        }
        assertNull(zoneEntry(seat0, top.getId()));
        assertNull(zoneEntry(seat0, inHand.getId()));
        final List<JsonObject> seen0 = events.ofKind(0, "zone_changed");
        assertEquals(seen0.size(), 2, events.wire(0));
        assertEquals(seen0.get(0).get("fromZone").getAsString(), "Library");
        assertEquals(seen0.get(0).get("toZone").getAsString(), "Hand");
        assertEquals(seen0.get(1).get("fromZone").getAsString(), "Hand");
        assertEquals(seen0.get(1).get("toZone").getAsString(), "Library");
        for (JsonObject move : seen0) {
            assertNoIdentityFacts(move);
        }

        // Its owner sees the draw and its own hand card, with its facts.
        final List<JsonObject> seen1 = events.ofKind(1, "zone_changed");
        assertEquals(seen1.size(), 2, events.wire(1));
        assertEquals(seen1.get(0).get("card").getAsString(), "Time Walk");
        assertEquals(seen1.get(0).get("fid").getAsInt(), top.getId());
        assertFacts(seen1.get(0), 1, false, false);
        assertFacts(zoneEntry(seat1, top.getId()), 1, false, false);
        // A card put into its library is hidden from its owner too.
        assertNoIdentityFacts(seen1.get(1));
    }

    // ----------------------------------------------------------------------------------- the version

    @Test(timeOut = 900000)
    public void everySeatViewDeclaresTheFactsVersion() {
        newGame();
        for (Player viewer : List.of(seat0, seat1)) {
            assertEquals(view(viewer).get(InteractiveCardFacts.FIELD).getAsInt(), InteractiveCardFacts.VERSION);
        }
        assertEquals(InteractiveCardFacts.VERSION, 1);
    }

    // ------------------------------------------------------------------------- the observation is unchanged

    @Test(timeOut = 900000)
    public void theBenchEncoderCarriesNoneOfTheFactsAndIsNotChangedByTheBrowserPayload() {
        newGame();
        addToken("c_a_treasure_sac", seat0);
        final Card bear = addCard("Grizzly Bears", seat0);
        bear.addTempController(seat1, game.getNextTimestamp());
        game.getAction().controllerChangeZoneCorrection(bear);
        final Card clone = addCard("Clone", seat1);
        clone.addCloneState(CardFactory.getCloneStates(bear, clone, clone.getFirstSpellAbility()),
                game.getNextTimestamp());
        clone.updateStateForView();
        cast(addCardToZone("Grizzly Bears", seat0, ZoneType.Exile), seat1);
        game.getAction().checkStateEffects(true);
        for (Player viewer : List.of(seat0, seat1)) {
            final String before = StateEncoder.encode(game, viewer).toString();
            view(viewer);
            final String after = StateEncoder.encode(game, viewer).toString();
            assertEquals(after, before, "building the browser payload must not change the bench encoding");
            for (String field : List.of("isToken", "isCopy", "ownerSeat", InteractiveCardFacts.FIELD)) {
                assertFalse(before.contains("\"" + field + "\""), field + " in the bench encoding: " + before);
            }
        }
    }
}
