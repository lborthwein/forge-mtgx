package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameLogFormatter;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventZoneChangeReplaced;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Owner report 2026-10-28T22-48-42 (live, build trunk-4172f1e12): the opponent's Touch the Spirit Realm exiled the
 * owner's Hullbreacher "until Touch the Spirit Realm leaves the battlefield"; the owner's Ulamog trigger destroyed the
 * enchantment and Hullbreacher stayed in exile. The opponent controlled Containment Priest ("If a nontoken creature
 * would enter and it wasn't cast, exile it instead"), so staying in exile is the rules, but nothing in the log or on the
 * board said so.
 *
 * <p>This board, in the engine: the return does fire (without the Priest, Hullbreacher comes back), the Priest replaces
 * it, and the replacement is now reported: a {@link GameEventZoneChangeReplaced}, a game log line naming both cards,
 * and a {@code zone_change_replaced} browser event with both ids and the intended and actual zones.</p>
 */
public class InteractiveReplacedZoneChangeTest extends AITest {

    private Game game;
    private Player owner;    // seat 0: Hullbreacher's owner (the human in the report)
    private Player opponent; // seat 1: Touch the Spirit Realm, Containment Priest

    private final List<GameEventZoneChangeReplaced> replaced = new ArrayList<>();
    private final List<List<JsonObject>> wire = List.of(new ArrayList<>(), new ArrayList<>());

    @Subscribe
    public void on(final GameEvent event) {
        if (event instanceof GameEventZoneChangeReplaced e) {
            replaced.add(e);
        }
        final Player[] seats = {owner, opponent};
        for (int i = 0; i < 2; i++) {
            final JsonObject o = InteractiveGameEvents.encode(event, game, seats[i].getView());
            if (o.has("kind") && "zone_change_replaced".equals(o.get("kind").getAsString())) {
                wire.get(i).add(o);
            }
        }
    }

    /** Touch the Spirit Realm's enters trigger exiles {@code target} until Touch leaves the battlefield. */
    private void touchExiles(final Card touch, final Card target) {
        SpellAbility etb = null;
        for (Trigger t : touch.getTriggers()) {
            final SpellAbility sa = t.ensureAbility();
            if (sa != null && sa.getApi() == ApiType.ChangeZone) {
                etb = sa;
                break;
            }
        }
        assertNotNull(etb, "Touch the Spirit Realm's enters trigger");
        etb.setActivatingPlayer(opponent);
        etb.getTargets().add(target);
        AbilityUtils.resolve(etb);
        assertTrue(game.findById(target.getId()).isInZone(ZoneType.Exile), "precondition: exiled by Touch");
    }

    private Card board(final boolean withPriest) {
        game = initAndCreateGame();
        owner = game.getRegisteredPlayers().get(0);
        opponent = game.getRegisteredPlayers().get(1);
        replaced.clear();
        wire.get(0).clear();
        wire.get(1).clear();
        if (withPriest) {
            addCard("Containment Priest", opponent);
        }
        final Card hull = addCard("Hullbreacher", owner);
        final Card touch = addCard("Touch the Spirit Realm", opponent);
        game.getAction().checkStateEffects(true);
        touchExiles(touch, hull);
        game.subscribeToEvents(this);
        // Ulamog's cast trigger destroys it
        game.getAction().destroy(touch, null, false, null);
        game.getAction().checkStateEffects(true);
        return hull;
    }

    private List<String> zoneChangeLog() {
        final List<String> out = new ArrayList<>();
        for (GameLogEntry e : game.getGameLog().getLogEntriesExact(GameLogEntryType.ZONE_CHANGE)) {
            out.add(e.message());
        }
        return out;
    }

    @Test(timeOut = 900000)
    public void withoutThePriestTheExiledCreatureReturns() {
        final Card hull = board(false);
        assertTrue(game.findById(hull.getId()).isInZone(ZoneType.Battlefield),
                "the until-leaves return fires when Touch the Spirit Realm is destroyed");
        assertTrue(replaced.isEmpty(), "nothing replaced: " + replaced);
        assertTrue(wire.get(0).isEmpty() && wire.get(1).isEmpty(), "no zone_change_replaced: " + wire);
    }

    @Test(timeOut = 900000)
    public void containmentPriestReplacesTheReturnAndTheLogSaysSo() {
        final Card hull = board(true);
        final Card priest = findCardWithName(game, "Containment Priest");
        assertTrue(game.findById(hull.getId()).isInZone(ZoneType.Exile),
                "Containment Priest exiles the returning creature instead");

        assertEquals(replaced.size(), 1, replaced.toString());
        final GameEventZoneChangeReplaced e = replaced.get(0);
        assertEquals(e.card().getId(), hull.getId());
        assertEquals(e.source().getId(), priest.getId());
        assertEquals(e.from(), ZoneType.Exile);
        assertEquals(e.intended(), ZoneType.Battlefield);
        assertEquals(e.actual(), ZoneType.Exile);
        assertFalse(e.prevented());

        final String line = "Containment Priest exiles Hullbreacher instead of letting it enter the battlefield.";
        assertTrue(zoneChangeLog().contains(line), "game log: " + zoneChangeLog());
        for (GameLogEntry entry : game.getGameLog().getAllEntries()) {
            assertFalse(entry.message().contains("Forge"), "player-facing text never says Forge: " + entry.message());
        }

        for (int viewer = 0; viewer < 2; viewer++) {
            assertEquals(wire.get(viewer).size(), 1, "viewer " + viewer + ": " + wire);
            final JsonObject o = wire.get(viewer).get(0);
            assertEquals(o.get("fromZone").getAsString(), "Exile", o.toString());
            assertEquals(o.get("intendedZone").getAsString(), "Battlefield", o.toString());
            assertEquals(o.get("toZone").getAsString(), "Exile", o.toString());
            assertFalse(o.get("prevented").getAsBoolean(), o.toString());
            assertEquals(o.get("card").getAsString(), "Hullbreacher", o.toString());
            assertEquals(o.get("fid").getAsInt(), hull.getId(), o.toString());
            assertEquals(o.get("ownerSeat").getAsInt(), 0, o.toString());
            assertEquals(o.get("source").getAsString(), "Containment Priest", o.toString());
            assertEquals(o.get("sourceFid").getAsInt(), priest.getId(), o.toString());
            assertEquals(o.get("sourceControllerSeat").getAsInt(), 1, o.toString());
            assertFalse(o.has("playerSeat"), o.toString());
        }
    }

    @Test(timeOut = 900000)
    public void restInPeaceExilesADyingCreature() {
        game = initAndCreateGame();
        owner = game.getRegisteredPlayers().get(0);
        opponent = game.getRegisteredPlayers().get(1);
        replaced.clear();
        addCard("Rest in Peace", opponent);
        final Card bear = addCard("Grizzly Bears", owner);
        game.getAction().checkStateEffects(true);
        game.subscribeToEvents(this);
        game.getAction().destroy(bear, null, false, null);
        assertTrue(game.findById(bear.getId()).isInZone(ZoneType.Exile));
        assertEquals(replaced.size(), 1, replaced.toString());
        assertEquals(replaced.get(0).intended(), ZoneType.Graveyard);
        assertTrue(zoneChangeLog().contains(
                "Rest in Peace exiles Grizzly Bears instead of letting it go to its owner's graveyard."),
                "game log: " + zoneChangeLog());
    }

    @Test(timeOut = 900000)
    public void anEntersTappedLandIsNotReported() {
        game = initAndCreateGame();
        owner = game.getRegisteredPlayers().get(0);
        opponent = game.getRegisteredPlayers().get(1);
        replaced.clear();
        final Card land = addCardToZone("Dismal Backwater", owner, ZoneType.Hand);
        game.subscribeToEvents(this);
        game.getAction().moveToPlay(land, null, null);
        final Card now = game.findById(land.getId());
        assertTrue(now.isInZone(ZoneType.Battlefield) && now.isTapped(), "precondition: it entered tapped");
        assertTrue(replaced.isEmpty(), "an updated move that still arrives is not a replaced zone change: " + replaced);
    }

    @Test(timeOut = 900000)
    public void theSentenceForms() {
        game = initAndCreateGame();
        owner = game.getRegisteredPlayers().get(0);
        final CardView looting = CardView.get(addCardToZone("Faithless Looting", owner, ZoneType.Exile));
        final CardView hidden = CardView.get(addCardToZone("Grizzly Bears", owner, ZoneType.Library));
        final CardView priest = CardView.get(addCard("Containment Priest", owner));
        // a card replacing its own move gets the passive form
        assertEquals(GameLogFormatter.describeReplacedZoneChange(new GameEventZoneChangeReplaced(looting, looting,
                        ZoneType.Stack, ZoneType.Graveyard, ZoneType.Exile, false)),
                "Faithless Looting is exiled instead of going to its owner's graveyard.");
        // a move between hidden zones never names the card
        assertEquals(GameLogFormatter.describeReplacedZoneChange(new GameEventZoneChangeReplaced(hidden, priest,
                        ZoneType.Hand, ZoneType.Battlefield, ZoneType.Library, false)),
                "Containment Priest puts a card into its owner's library instead of letting it enter the battlefield.");
        assertEquals(GameLogFormatter.describeReplacedZoneChange(new GameEventZoneChangeReplaced(looting, priest,
                        ZoneType.Exile, ZoneType.Battlefield, ZoneType.Exile, true)),
                "Containment Priest keeps Faithless Looting from entering the battlefield.");
    }
}
