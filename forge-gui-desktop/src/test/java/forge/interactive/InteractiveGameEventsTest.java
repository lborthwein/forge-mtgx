package forge.interactive;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnPhase;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;

/**
 * A cast event says which zone the spell was cast from ({@code fromZone}): owner report
 * 2026-10-10T06-30-52, where Venser, Shaper Savant returned Squee, Goblin Nabob from the stack to its
 * owner's hand and the AI cast it again before the human had priority, so the browser saw Squee on the
 * stack in both of its views and could not say where it had been.
 */
public class InteractiveGameEventsTest extends AITest {

    /** Cast {@code card} from where it is, the way a cast moves it (GameAction.moveToStack), and encode the event. */
    private static List<JsonObject> cast(final Game game, final Card card, final Player caster) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        sa.setHostCard(game.getAction().moveToStack(card, sa));
        final List<JsonObject> encoded = new ArrayList<>();
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void on(final GameEvent event) {
                if (event instanceof GameEventSpellAbilityCast) {
                    encoded.add(InteractiveGameEvents.encode(event, game, game.getPlayers().get(0).getView()));
                }
            }
        });
        game.getStack().add(sa);
        return encoded;
    }

    @Test
    public void aSpellCastFromHandSaysHand() {
        final Game game = initAndCreateGame();
        final Player foe = game.getPlayers().get(1);
        final List<JsonObject> events = cast(game, addCardToZone("Squee, Goblin Nabob", foe, ZoneType.Hand), foe);
        assertEquals(events.size(), 1, events.toString());
        assertEquals(events.get(0).get("kind").getAsString(), "spell_cast");
        assertEquals(events.get(0).get("card").getAsString(), "Squee, Goblin Nabob");
        assertEquals(events.get(0).get("fromZone").getAsString(), "Hand");
    }

    @Test
    public void aSpellCastFromTheGraveyardSaysGraveyard() {
        final Game game = initAndCreateGame();
        final Player foe = game.getPlayers().get(1);
        final List<JsonObject> events = cast(game, addCardToZone("Squee, Goblin Nabob", foe, ZoneType.Graveyard), foe);
        assertEquals(events.get(0).get("fromZone").getAsString(), "Graveyard");
    }

    /** A spell put on the stack without being cast (a test frame's putonstack) has no zone to name. */
    @Test
    public void aSpellNotCastFromAZoneSaysNothing() {
        final Game game = initAndCreateGame();
        final Player foe = game.getPlayers().get(1);
        final Card card = addCardToZone("Time Walk", foe, ZoneType.Hand);
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(foe);
        game.getStackZone().add(card);
        final List<JsonObject> encoded = new ArrayList<>();
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void on(final GameEvent event) {
                if (event instanceof GameEventSpellAbilityCast) {
                    encoded.add(InteractiveGameEvents.encode(event, game, game.getPlayers().get(0).getView()));
                }
            }
        });
        game.getStack().add(sa);
        assertEquals(encoded.get(0).get("kind").getAsString(), "spell_cast");
        assertFalse(encoded.get(0).has("fromZone"), encoded.toString());
    }

    /**
     * table-live-view-1022 (owner friend table 2026-10-22T19-53-19): the step the game enters reaches every seat as
     * {@code phase_changed}, the shape the client's adapter ({@code forgePhaseChanged}) reads: the ACTIVE player's
     * seat, Forge's PhaseType name and the global turn number, for the active player and for the other seat alike.
     */
    @Test
    public void aPhaseChangeNamesTheActiveSeatThePhaseAndTheGlobalTurn() {
        final Game game = initAndCreateGame();
        final Player first = game.getPlayers().get(0);
        final Player second = game.getPlayers().get(1);
        final List<JsonObject> toFirst = new ArrayList<>();
        final List<JsonObject> toSecond = new ArrayList<>();
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void on(final GameEvent event) {
                if (event instanceof GameEventTurnPhase) {
                    toFirst.add(InteractiveGameEvents.encode(event, game, first.getView()));
                    toSecond.add(InteractiveGameEvents.encode(event, game, second.getView()));
                }
            }
        });
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, second, 4);
        assertEquals(toFirst.size(), 1, toFirst.toString());
        for (JsonObject e : List.of(toFirst.get(0), toSecond.get(0))) {
            assertEquals(e.get("kind").getAsString(), "phase_changed");
            assertEquals(e.get("playerSeat").getAsInt(), 1, "the active player, whoever reads it: " + e);
            assertEquals(e.get("phase").getAsString(), "MAIN1");
            assertEquals(e.get("turn").getAsInt(), 4);
        }
        assertEquals(toFirst.get(0).toString(), toSecond.get(0).toString(), "public: the same for both seats");
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, first, 5);
        assertEquals(toSecond.get(1).get("phase").getAsString(), "COMBAT_DECLARE_ATTACKERS");
        assertEquals(toSecond.get(1).get("playerSeat").getAsInt(), 0);
        assertEquals(toSecond.get(1).get("turn").getAsInt(), 5);
    }
}
