package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.event.GameEvent;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane block-search-1019 (owner report, dies-on-entry: Persist returned Putrid Imp with a -1/-1 counter and it died at
 * once, and the client's log showed nothing after the cast). The browser events now carry a move out of a public zone
 * ({@code zone_changed} with the card's name, and its {@code fid} where the viewer may see it now) and counter changes
 * on public objects ({@code counters_changed}). Hidden-information rules: a hidden draw is never named to the opponent;
 * a card returned from a graveyard to an opponent's hidden hand is named (it was public) but gets no id.
 */
public class InteractiveZoneAndCounterEventsTest extends AITest {

    private Game game;
    private Player seat0;
    private Player seat1;

    private void newGame() {
        game = initAndCreateGame();
        seat0 = game.getRegisteredPlayers().get(0);
        seat1 = game.getRegisteredPlayers().get(1);
    }

    /** Every event, encoded for both seats in order (only the ones with a kind). */
    private final class Recorder {
        final List<List<JsonObject>> events = List.of(new ArrayList<>(), new ArrayList<>());
        final InteractiveGameEvents.Moves[] moves = {new InteractiveGameEvents.Moves(), new InteractiveGameEvents.Moves()};

        Recorder() {
            game.subscribeToEvents(this);
        }

        @Subscribe
        public void on(final GameEvent event) {
            final Player[] seats = {seat0, seat1};
            for (int i = 0; i < 2; i++) {
                final JsonObject o = InteractiveGameEvents.encode(event, game, seats[i].getView(), moves[i]);
                if (o.has("kind")) {
                    events.get(i).add(o);
                }
            }
        }

        List<JsonObject> of(final int viewer, final String... kinds) {
            final List<JsonObject> out = new ArrayList<>();
            for (JsonObject o : events.get(viewer)) {
                if (List.of(kinds).contains(o.get("kind").getAsString())) {
                    out.add(o);
                }
            }
            return out;
        }
    }

    @Test(timeOut = 900000)
    public void aCreatureReturnedWithAMinusCounterThatDiesIsMoveCountersMove() {
        newGame();
        final Card imp = addCardToZone("Putrid Imp", seat0, ZoneType.Graveyard);
        final Card persist = addCardToZone("Persist", seat0, ZoneType.Hand);
        final Recorder rec = new Recorder();
        final SpellAbility sa = persist.getFirstSpellAbility();
        sa.setActivatingPlayer(seat0);
        sa.getTargets().add(imp);
        AbilityUtils.resolve(sa);
        game.getAction().checkStateEffects(true);
        final Card now = game.findById(imp.getId());
        assertTrue(now.isInZone(ZoneType.Graveyard), "precondition: the 0/0 Imp died: " + now.getZone());
        for (int viewer = 0; viewer < 2; viewer++) {
            final List<JsonObject> seq = new ArrayList<>();
            for (JsonObject o : rec.of(viewer, "zone_changed", "counters_changed")) {
                if (o.has("fid") && o.get("fid").getAsInt() == imp.getId()) {
                    seq.add(o);
                }
            }
            final String wire = rec.events.get(viewer).toString();
            // Forge places ETB counters inside the move, before it fires the move's event: counters, return, death
            assertEquals(seq.size(), 3, "counters + move + move for viewer " + viewer + ": " + wire);
            final JsonObject counter = seq.get(0);
            assertEquals(counter.get("kind").getAsString(), "counters_changed", wire);
            assertEquals(counter.get("counter").getAsString(), CounterEnumType.M1M1.toString());
            assertEquals(counter.get("oldCount").getAsInt(), 0);
            assertEquals(counter.get("newCount").getAsInt(), 1);
            assertEquals(counter.get("zone").getAsString(), "Battlefield");
            assertEquals(counter.get("card").getAsString(), "Putrid Imp");
            final JsonObject back = seq.get(1);
            assertEquals(back.get("kind").getAsString(), "zone_changed", wire);
            assertEquals(back.get("fromZone").getAsString(), "Graveyard");
            assertEquals(back.get("toZone").getAsString(), "Battlefield");
            assertEquals(back.get("card").getAsString(), "Putrid Imp");
            assertEquals(back.get("ownerSeat").getAsInt(), 0);
            assertEquals(back.get("controllerSeat").getAsInt(), 0);
            assertEquals(back.get("fromSeat").getAsInt(), 0);
            final JsonObject died = seq.get(2);
            assertEquals(died.get("kind").getAsString(), "zone_changed", wire);
            assertEquals(died.get("fromZone").getAsString(), "Battlefield");
            assertEquals(died.get("toZone").getAsString(), "Graveyard");
            assertEquals(died.get("card").getAsString(), "Putrid Imp", "a death during state-based actions is named: " + wire);
            assertEquals(died.get("ownerSeat").getAsInt(), 0);
        }
    }

    @Test(timeOut = 900000)
    public void aHiddenDrawIsNotNamedToTheOpponentAndAReturnToAHiddenHandHasNoId() {
        newGame();
        fillLibrary(seat1, 3);
        final Card top = createCard("Time Walk", seat1);
        top.setGameTimestamp(game.getNextTimestamp());
        seat1.getZone(ZoneType.Library).add(top, 0);
        final Card dead = addCardToZone("Grizzly Bears", seat1, ZoneType.Graveyard);
        game.getAction().checkStateEffects(true);
        final Recorder rec = new Recorder();
        seat1.drawCard();
        game.getAction().moveToHand(dead, null);
        final List<JsonObject> opp = rec.of(0, "zone_changed");
        assertEquals(opp.size(), 2, rec.events.get(0).toString());
        final JsonObject draw = opp.get(0);
        assertEquals(draw.get("fromZone").getAsString(), "Library");
        assertEquals(draw.get("toZone").getAsString(), "Hand");
        assertEquals(draw.get("fromSeat").getAsInt(), 1, "whose library is public");
        for (String f : List.of("card", "fid", "ownerSeat", "controllerSeat", "isToken", "isCopy")) {
            assertFalse(draw.has(f), f + " on a hidden draw: " + draw);
        }
        assertFalse(rec.events.get(0).toString().contains("Time Walk"), rec.events.get(0).toString());
        final JsonObject back = opp.get(1);
        assertEquals(back.get("card").getAsString(), "Grizzly Bears", "it was public in the graveyard: " + back);
        assertFalse(back.has("fid"), "no id for a card now in a hidden hand: " + back);
        // its owner sees both, with ids
        final List<JsonObject> own = rec.of(1, "zone_changed");
        assertEquals(own.get(0).get("card").getAsString(), "Time Walk");
        assertEquals(own.get(1).get("fid").getAsInt(), dead.getId());
    }

    @Test(timeOut = 900000)
    public void countersInAHiddenZoneAreNotEncoded() {
        newGame();
        final Card inHand = addCardToZone("Grizzly Bears", seat1, ZoneType.Hand);
        final Card bear = addCard("Grizzly Bears", seat1);
        final Recorder rec = new Recorder();
        game.fireEvent(new forge.game.event.GameEventCardCounters(inHand, CounterEnumType.P1P1, 0, 1));
        game.fireEvent(new forge.game.event.GameEventCardCounters(bear, CounterEnumType.P1P1, 0, 2));
        final List<JsonObject> opp = rec.of(0, "counters_changed");
        assertEquals(opp.size(), 1, rec.events.get(0).toString());
        assertEquals(opp.get(0).get("fid").getAsInt(), bear.getId());
        assertEquals(opp.get(0).get("newCount").getAsInt(), 2);
    }
}
