package forge.interactive;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.ai.AITest;
import forge.bench.StateEncoder;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane block-search-1019: a token's zone entry on the browser wire carries its printed identity for the client's token
 * art: {@code tokenScript} (Forge's token script id), {@code tokenColors} (printed, WUBRG letters) and
 * {@code tokenBasePower} / {@code tokenBaseToughness} (printed, creature tokens only). Optional and additive: existing
 * fields are unchanged, a non-token gets none, a face-down object gets none, and the bench {@link StateEncoder} carries
 * none.
 */
public class InteractiveTokenIdentityTest extends AITest {

    private static final List<String> FIELDS = List.of("tokenScript", "tokenColors", "tokenBasePower", "tokenBaseToughness");

    private Game game;
    private Player seat0;
    private Player seat1;

    private void newGame() {
        game = initAndCreateGame();
        seat0 = game.getRegisteredPlayers().get(0);
        seat1 = game.getRegisteredPlayers().get(1);
    }

    private JsonObject entry(final Player viewer, final int fid) {
        final JsonObject view = InteractiveState.seatView(game, viewer, StateEncoder.playerIndex(game, viewer));
        for (JsonElement p : view.getAsJsonArray("players")) {
            for (JsonElement e : p.getAsJsonObject().getAsJsonArray("battlefield")) {
                if (e.getAsJsonObject().get("fid").getAsInt() == fid) {
                    return e.getAsJsonObject();
                }
            }
        }
        return null;
    }

    private static List<String> colors(final JsonObject o) {
        final List<String> out = new ArrayList<>();
        o.getAsJsonArray("tokenColors").forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test(timeOut = 900000)
    public void aCreatedWurmCarriesItsScriptColorsAndPrintedPowerToughness() {
        newGame();
        final Card crush = addCardToZone("Crush of Wurms", seat0, ZoneType.Hand);
        final SpellAbility sa = crush.getFirstSpellAbility();
        sa.setActivatingPlayer(seat0);
        AbilityUtils.resolve(sa);
        game.getAction().checkStateEffects(true);
        final List<Card> wurms = new ArrayList<>();
        for (Card c : seat0.getCardsIn(ZoneType.Battlefield)) {
            if (c.isToken()) {
                wurms.add(c);
            }
        }
        assertEquals(wurms.size(), 3, "Crush of Wurms made three Wurms");
        final Card wurm = wurms.get(0);
        wurm.setCounters(CounterEnumType.P1P1, 2);
        game.getAction().checkStateEffects(true);
        assertEquals(wurm.getNetPower(), 8, "precondition: the current power differs from the printed");
        for (Player viewer : List.of(seat0, seat1)) {
            final JsonObject o = entry(viewer, wurm.getId());
            assertNotNull(o);
            assertTrue(o.get("isToken").getAsBoolean(), o.toString());
            assertEquals(o.get("tokenScript").getAsString(), "g_6_6_wurm", o.toString());
            assertEquals(colors(o), List.of("G"), o.toString());
            assertEquals(o.get("tokenBasePower").getAsInt(), 6, "printed, not current: " + o);
            assertEquals(o.get("tokenBaseToughness").getAsInt(), 6, o.toString());
            assertEquals(o.get("power").getAsInt(), 8, "the existing current-power field is unchanged: " + o);
        }
    }

    @Test(timeOut = 900000)
    public void aRenamedTypeTokenAndANonCreatureTokenAndADeckCard() {
        newGame();
        final Card phyrexian = addToken("c_3_3_a_phyrexian_wurm_deathtouch", seat1);
        final Card treasure = addToken("c_a_treasure_sac", seat0);
        final Card bear = addCard("Grizzly Bears", seat0);
        game.getAction().checkStateEffects(true);
        for (Player viewer : List.of(seat0, seat1)) {
            final JsonObject p = entry(viewer, phyrexian.getId());
            assertEquals(p.get("tokenScript").getAsString(), "c_3_3_a_phyrexian_wurm_deathtouch", p.toString());
            assertEquals(colors(p), List.of(), "colorless: " + p);
            assertEquals(p.get("tokenBasePower").getAsInt(), 3);
            assertEquals(p.get("tokenBaseToughness").getAsInt(), 3);
            final JsonObject t = entry(viewer, treasure.getId());
            assertEquals(t.get("tokenScript").getAsString(), "c_a_treasure_sac", t.toString());
            assertFalse(t.has("tokenBasePower"), "a non-creature token has no P/T: " + t);
            final JsonObject b = entry(viewer, bear.getId());
            for (String f : FIELDS) {
                assertFalse(b.has(f), f + " on a deck card: " + b);
            }
        }
    }

    @Test(timeOut = 900000)
    public void aFaceDownTokenAndTheBenchEncoderCarryNone() {
        newGame();
        final Card hidden = addToken("g_6_6_wurm", seat0);
        hidden.turnFaceDown(true);
        final Card shown = addToken("g_6_6_wurm", seat0);
        game.getAction().checkStateEffects(true);
        final JsonObject o = entry(seat1, hidden.getId());
        assertNotNull(o, "the opponent sees a face-down permanent");
        for (String f : FIELDS) {
            assertFalse(o.has(f), f + " on a face-down object: " + o);
        }
        final String bench = StateEncoder.encode(game, seat0).toString() + StateEncoder.encode(game, seat1);
        for (String f : FIELDS) {
            assertFalse(bench.contains("\"" + f + "\""), "the bench encoder is unchanged: " + f);
        }
        assertTrue(entry(seat1, shown.getId()).has("tokenScript"));
    }
}
