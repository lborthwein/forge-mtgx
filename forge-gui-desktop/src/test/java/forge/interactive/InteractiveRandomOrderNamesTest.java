package forge.interactive;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardChangeZone;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Lane order-memory-1102 (live info leak). Endurance puts a graveyard on the bottom of its owner's library in a random
 * order. Forge shuffles the cards, then moves them one at a time, and each move is a {@code zone_changed}. A card
 * leaving a graveyard was public, so the event named it, and the names in event order told both seats the hidden
 * random order of that library's bottom. Those moves now carry no name for either seat. A card put into a library at a
 * place the rules fix (Memory Lapse: the top) is still named.
 */
public class InteractiveRandomOrderNamesTest extends AITest {

    /** Every zone_changed into a library, as each seat's browser receives it. */
    private static List<List<JsonObject>> watchLibraryMoves(final Game game) {
        final List<List<JsonObject>> out = List.of(new ArrayList<>(), new ArrayList<>());
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void on(final GameEvent event) {
                if (event instanceof GameEventCardChangeZone e && e.to() != null && e.to().zoneType() == ZoneType.Library) {
                    for (int s = 0; s < 2; s++) {
                        out.get(s).add(InteractiveGameEvents.encode(event, game, game.getPlayers().get(s).getView()));
                    }
                }
            }
        });
        return out;
    }

    /** Plays {@code sa} for {@code who} and resolves the stack; triggers that target get {@code target}. */
    private static void playAndResolve(final Game game, final Player who, final SpellAbility sa, final Player target) {
        sa.setActivatingPlayer(who);
        assertTrue(((PlayerControllerAi) who.getController()).playChosenSpellAbility(sa), "play " + sa);
        MyRandom.setRandom(new Random(42));
        int guard = 0;
        game.getAction().checkStateEffects(true);
        game.getStack().addAllTriggeredAbilitiesToStack();
        while (!game.getStack().isEmpty() && guard++ < 12) {
            final SpellAbility top = game.getStack().peekAbility();
            if (top != null && top.isTrigger() && top.usesTargeting() && target != null) {
                top.resetTargets();
                top.getTargets().add(target);
            }
            game.getStack().resolveStack();
            game.getAction().checkStateEffects(true);
            game.getStack().addAllTriggeredAbilitiesToStack();
        }
    }

    private void endurance(final boolean ours) {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(0);
        final Player foe = game.getPlayers().get(1);
        final Player caster = ours ? me : foe;
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, caster, 2);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Forest", caster, ZoneType.Battlefield);
        }
        fillLibrary(me, 5);
        fillLibrary(foe, 5);
        for (String n : new String[] {"Lightning Bolt", "Shock", "Giant Growth"}) {
            addCardToZone(n, me, ZoneType.Graveyard);
        }
        final Card endurance = addCardToZone("Endurance", caster, ZoneType.Hand);
        final List<List<JsonObject>> seen = watchLibraryMoves(game);
        playAndResolve(game, caster, endurance.getFirstSpellAbility(), me);
        assertTrue(me.getCardsIn(ZoneType.Graveyard).isEmpty(), "our graveyard went to the bottom");
        for (int s = 0; s < 2; s++) {
            final List<JsonObject> moves = seen.get(s);
            assertEquals(moves.size(), 3, "seat " + s + ": " + moves);
            for (JsonObject m : moves) {
                assertEquals(m.get("kind").getAsString(), "zone_changed");
                assertEquals(m.get("fromZone").getAsString(), "Graveyard");
                assertFalse(m.has("card"), "ours=" + ours + " seat " + s + " learns the random order: " + moves);
                assertFalse(m.has("fid"), m.toString());
            }
        }
    }

    @Test
    public void ourEnduranceSendsNoOrderedNames() {
        endurance(true);
    }

    @Test
    public void theirEnduranceSendsNoOrderedNames() {
        endurance(false);
    }

    /** The control: their Memory Lapse puts our spell on top (a place the rules fix): both seats still read its name. */
    @Test
    public void aMemoryLapseTopIsStillNamed() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(0);
        final Player foe = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me, 2);
        addCardToZone("Mountain", me, ZoneType.Battlefield);
        for (int i = 0; i < 2; i++) {
            addCardToZone("Island", foe, ZoneType.Battlefield);
        }
        fillLibrary(me, 5);
        fillLibrary(foe, 5);
        final SpellAbility bolt = addCardToZone("Lightning Bolt", me, ZoneType.Hand).getFirstSpellAbility();
        bolt.setActivatingPlayer(me);
        bolt.resetTargets();
        bolt.getTargets().add(foe);
        assertTrue(((PlayerControllerAi) me.getController()).playChosenSpellAbility(bolt));
        final SpellAbility lapse = addCardToZone("Memory Lapse", foe, ZoneType.Hand).getFirstSpellAbility();
        lapse.setActivatingPlayer(foe);
        lapse.resetTargets();
        lapse.getTargets().add(game.getStack().peekAbility());
        final List<List<JsonObject>> seen = watchLibraryMoves(game);
        playAndResolve(game, foe, lapse, null);
        assertEquals(me.getCardsIn(ZoneType.Library).get(0).getName(), "Lightning Bolt", "countered onto our top");
        for (int s = 0; s < 2; s++) {
            assertEquals(seen.get(s).size(), 1, seen.get(s).toString());
            assertEquals(seen.get(s).get(0).get("card").getAsString(), "Lightning Bolt", "seat " + s);
        }
        assertFalse(InteractiveGameEvents.randomOrderResolution(game), "nothing resolves now");
    }
}
