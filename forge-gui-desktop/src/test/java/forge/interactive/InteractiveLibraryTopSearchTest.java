package forge.interactive;

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

/**
 * afford-jar-1021, the owner's report 2026-10-21T16-56-19: the bridge sent {@code libraryTop} for the human's own
 * library during a SEARCH (swampcycling, Expedition Map), and the table showed "Top: Narset" mid-search. A search
 * shows the searcher the library through Forge's temporary look ({@code PlayerControllerHuman.tempShow}: the whole
 * library as the search's reveal, or just the matching cards); that never makes the top card itself known. The rule:
 * {@code libraryTop} only when an effect makes the top card known (a lasting look: Courser, Citadel; or a temporary
 * look at the top cards alone: scry, surveil, look at the top N).
 *
 * <p>The temporary look is set the way {@code PlayerControllerHuman.tempShowCard} sets it, {@code addMayLookTemp}.</p>
 */
public class InteractiveLibraryTopSearchTest extends AITest {

    private Game game;
    private Player me;
    private Card top;

    private void library() {
        game = initAndCreateGame();
        me = game.getRegisteredPlayers().get(0);
        for (String name : new String[] {"Swamp", "Island", "Runeclaw Bear", "Swamp", "Preordain"}) {
            addCardToZone(name, me, ZoneType.Library);
        }
        top = createCard("Narset, Parter of Veils", me);
        top.setGameTimestamp(game.getNextTimestamp());
        me.getZone(ZoneType.Library).add(top, 0);
        game.getAction().checkStateEffects(true);
    }

    private JsonObject libraryTopSeen() {
        final int seat = StateEncoder.playerIndex(game, me);
        final JsonObject view = InteractiveState.seatView(game, me, seat);
        for (JsonElement element : view.getAsJsonArray("players")) {
            final JsonObject player = element.getAsJsonObject();
            if (player.get("index").getAsInt() == seat) {
                return player.has(InteractiveState.LIBRARY_TOP) ? player.getAsJsonObject(InteractiveState.LIBRARY_TOP) : null;
            }
        }
        throw new AssertionError("no seat " + seat);
    }

    private void tempShow(final Iterable<Card> cards) {
        for (Card c : cards) c.addMayLookTemp(me);
    }

    private void endTempShow() {
        for (Card c : me.getCardsIn(ZoneType.Library)) c.removeMayLookTemp(me);
    }

    @Test
    public void aSearchsWholeLibraryRevealSendsNoTop() {
        library();
        assertNull(libraryTopSeen(), "precondition: nobody may look");
        tempShow(me.getCardsIn(ZoneType.Library));   // ChangeZoneEffect's DelayedReveal of the whole library
        assertNull(libraryTopSeen(), "a search never makes the top known");
        assertFalse(InteractiveState.seatView(game, me, StateEncoder.playerIndex(game, me)).toString().contains("Narset"));
        endTempShow();
        assertNull(libraryTopSeen());
    }

    @Test
    public void aResolvingSearchShowingOnlyTheMatchingCardsSendsNoTop() {
        library();
        final Card map = addCard("Expedition Map", me);
        final SpellAbility search = map.getSpellAbilities().stream().filter(sa -> !sa.isSpell()).findFirst().orElseThrow();
        search.setActivatingPlayer(me);
        game.getStack().add(search);
        game.getStack().setResolving(true);
        try {
            // The multi-pick path temp-shows the choices only; Narset is "matching" here to stand for the worst case.
            tempShow(List.of(top, me.getCardsIn(ZoneType.Library).get(1)));
            assertNull(libraryTopSeen(), "a top seen only as a search choice is not known");
        } finally {
            game.getStack().setResolving(false);
        }
    }

    @Test
    public void surveilShowsTheTop() {
        library();
        tempShow(me.getCardsIn(ZoneType.Library, 2));   // arrangeForSurveil(topN)
        final JsonObject shown = libraryTopSeen();
        assertNotNull(shown, "surveil looks at the top cards themselves");
        assertEquals(shown.get("name").getAsString(), "Narset, Parter of Veils");
        endTempShow();
        assertNull(libraryTopSeen());
    }

    @Test
    public void aLastingLookStillShowsTheTopDuringASearch() {
        library();
        addCard("Courser of Kruphix", me);
        game.getAction().checkStateEffects(true);
        tempShow(me.getCardsIn(ZoneType.Library));
        assertNotNull(libraryTopSeen(), "Courser reveals the top whatever else is going on");
    }
}
