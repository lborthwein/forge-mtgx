package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane block-search-1019 (hidden-information sensitive). Thief of Sanity exiles a card face down that its controller
 * may look at and cast; Forge's may-look rule admitted the entry for that seat, but it went out in its face-down state
 * (no name; the cast control read "Card (73)"). The grantee's own channel now carries {@code faceDownIdentity}; the
 * card's owner without a grant and every watch channel never do. Every seat's exile list holds every exiled card: a
 * face-down card a seat may not look at is a redacted shell (it was dropped, so the owner's exile count was short).
 */
public class InteractiveMayLookExileTest extends AITest {

    private Game game;
    private Player thief;   // seat 0: the grantee
    private Player owner;   // seat 1: the card's owner

    private Card exiled;
    private long grant;

    /** The field is held behind -Dforge.interactive.faceDownIdentity=on (default off); these tests switch it on. */
    @BeforeMethod
    public void faceDownIdentityOn() {
        System.setProperty("forge.interactive.faceDownIdentity", "on");
    }

    @AfterMethod(alwaysRun = true)
    public void faceDownIdentityOff() {
        System.clearProperty("forge.interactive.faceDownIdentity");
    }

    /** Default (held): no seat gets the face, the label stays unnamed, and the exile counts are still right. */
    @Test(timeOut = 900000)
    public void heldByDefaultNothingIsSentButCountsAreRight() {
        System.clearProperty("forge.interactive.faceDownIdentity");
        board();
        for (Player viewer : List.of(thief, owner)) {
            final JsonObject v = view(viewer, true);
            assertFalse(v.toString().contains(InteractiveState.FACE_DOWN_IDENTITY), v.toString());
            assertFalse(v.toString().contains("Serra Angel"), v.toString());
            assertEquals(exileOf(v, 1).size(), 2, "both exiled cards counted");
        }
        assertFalse(InteractiveState.safeCardLabel(exiled.getView(), thief.getView()).contains("Serra Angel"));
    }

    private void board() {
        game = initAndCreateGame();
        thief = game.getRegisteredPlayers().get(0);
        owner = game.getRegisteredPlayers().get(1);
        addCardToZone("Lightning Bolt", owner, ZoneType.Exile);      // a face-up exiled card: public to all
        exiled = addCardToZone("Serra Angel", owner, ZoneType.Exile);
        exiled.turnFaceDown(true);
        grant = game.getNextTimestamp();
        exiled.addMayLookAt(grant, List.of(thief));
        exiled.updateStateForView();
        game.getAction().checkStateEffects(true);
    }

    private JsonObject view(final Player viewer, final boolean lookedAt) {
        return InteractiveState.seatView(game, viewer, StateEncoder.playerIndex(game, viewer), lookedAt);
    }

    private static JsonArray exileOf(final JsonObject view, final int seat) {
        for (JsonElement p : view.getAsJsonArray("players")) {
            if (p.getAsJsonObject().get("index").getAsInt() == seat) {
                return p.getAsJsonObject().getAsJsonArray("exile");
            }
        }
        throw new AssertionError("no seat " + seat);
    }

    private static JsonObject entry(final JsonArray zone, final int fid) {
        for (JsonElement e : zone) {
            if (e.getAsJsonObject().get("fid").getAsInt() == fid) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    @Test(timeOut = 900000)
    public void theGranteeGetsTheFaceAndNobodyElseDoes() {
        board();
        final JsonObject mine = entry(exileOf(view(thief, true), 1), exiled.getId());
        assertNotNull(mine, "the grantee lists the card");
        final JsonObject face = mine.getAsJsonObject(InteractiveState.FACE_DOWN_IDENTITY);
        assertNotNull(face, mine.toString());
        assertEquals(face.get("name").getAsString(), "Serra Angel");
        assertEquals(face.get("cmc").getAsInt(), 5);
        assertEquals(face.get("basePower").getAsInt(), 4);
        assertEquals(face.get("baseToughness").getAsInt(), 4);
        assertTrue(face.get("types").getAsString().contains("Angel"), face.toString());
        assertTrue(mine.get("faceDown").getAsBoolean(), "the entry itself stays face down: " + mine);
        assertEquals(InteractiveState.safeCardLabel(exiled.getView(), thief.getView()), "Serra Angel (" + exiled.getId() + ")");

        // the owner, who has no grant: a redacted shell, no face, no name anywhere on the wire
        final JsonObject ownerView = view(owner, true);
        final JsonObject theirs = entry(exileOf(ownerView, 1), exiled.getId());
        assertNotNull(theirs, "the owner's exile lists the card (as a shell)");
        assertTrue(theirs.get("identityRedacted").getAsBoolean(), theirs.toString());
        assertFalse(theirs.has(InteractiveState.FACE_DOWN_IDENTITY), theirs.toString());
        assertFalse(ownerView.toString().contains("Serra Angel"), ownerView.toString());
        assertEquals(InteractiveState.safeCardLabel(exiled.getView(), owner.getView()), "Face-down card (" + exiled.getId() + ")");

        // a watch channel, even the grantee's seat's: never the face
        for (Player seat : List.of(thief, owner)) {
            final JsonObject watch = view(seat, false);
            assertFalse(watch.toString().contains(InteractiveState.FACE_DOWN_IDENTITY), watch.toString());
            assertFalse(watch.toString().contains("Serra Angel"), watch.toString());
        }
    }

    @Test(timeOut = 900000)
    public void exileCountsAreRightForEverySeat() {
        board();
        for (Player viewer : List.of(thief, owner)) {
            for (boolean lookedAt : new boolean[] {true, false}) {
                final JsonArray ex = exileOf(view(viewer, lookedAt), 1);
                assertEquals(ex.size(), 2, "both exiled cards, seat " + viewer + " lookedAt " + lookedAt + ": " + ex);
                assertNotNull(entry(ex, exiled.getId()));
            }
        }
    }

    /**
     * Forge's own rule: a player granted a look at a card exiled face down keeps it while the card stays exiled face
     * down ({@code Card.mayLookFaceDownExile}); the field follows Forge and clears when the card leaves exile.
     */
    @Test(timeOut = 900000)
    public void theFaceFollowsForgesRuleInExileAndClearsWhenTheCardLeaves() {
        board();
        exiled.removeMayLookAt(grant);
        exiled.updateStateForView();
        game.getAction().checkStateEffects(true);
        assertEquals(exiled.mayPlayerLook(thief), true, "precondition: Forge keeps the look while it stays exiled");
        assertNotNull(entry(exileOf(view(thief, true), 1), exiled.getId()).get(InteractiveState.FACE_DOWN_IDENTITY));

        final Card moved = game.getAction().moveToGraveyard(exiled, null);
        game.getAction().checkStateEffects(true);
        final JsonObject v = view(thief, true);
        assertFalse(v.toString().contains(InteractiveState.FACE_DOWN_IDENTITY), v.toString());
        assertNull(entry(exileOf(v, 1), moved.getId()), "no longer in exile");
        assertEquals(exileOf(v, 1).size(), 1);
        for (Player viewer : List.of(thief, owner)) {
            assertEquals(exileOf(view(viewer, true), 1).size(), 1, "the count follows for every seat");
        }
    }

    /** A grant that ends (a temporary look at an opponent's face-down permanent): the field goes with it. */
    @Test(timeOut = 900000)
    public void theFaceClearsWhenTheGrantEnds() {
        game = initAndCreateGame();
        thief = game.getRegisteredPlayers().get(0);
        owner = game.getRegisteredPlayers().get(1);
        final Card morph = addCard("Ainok Tracker", owner);
        morph.turnFaceDown(true);
        morph.updateStateForView();
        game.getAction().checkStateEffects(true);
        final java.util.function.Supplier<JsonObject> battlefieldEntry = () -> {
            for (JsonElement p : view(thief, true).getAsJsonArray("players")) {
                for (JsonElement e : p.getAsJsonObject().getAsJsonArray("battlefield")) {
                    if (e.getAsJsonObject().get("fid").getAsInt() == morph.getId()) {
                        return e.getAsJsonObject();
                    }
                }
            }
            return null;
        };
        assertFalse(battlefieldEntry.get().has(InteractiveState.FACE_DOWN_IDENTITY), "no grant yet");
        morph.addMayLookTemp(thief);
        morph.updateStateForView();
        assertEquals(battlefieldEntry.get().getAsJsonObject(InteractiveState.FACE_DOWN_IDENTITY).get("name").getAsString(),
                "Ainok Tracker");
        assertFalse(view(owner, false).toString().contains(InteractiveState.FACE_DOWN_IDENTITY), "never on a watch channel");
        morph.removeMayLookTemp(thief);
        morph.updateStateForView();
        final JsonObject after = battlefieldEntry.get();
        assertFalse(after.has(InteractiveState.FACE_DOWN_IDENTITY), after.toString());
        assertFalse(view(thief, true).toString().contains("Ainok Tracker"), "the grant ended");
    }
}
