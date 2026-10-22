package forge.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.simulation.PlayoutKeys;
import forge.card.CardStateName;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Lane replay-record-1021, review forge-mtgx#110 S2-2: when both seats are taped (a watch or bot table) and both have a
 * frame open, a lazily built trait belongs to the most recently begun frame, whichever tape was installed first.
 */
public class AiTapeLazyAttributionTest extends AITest {

    private static List<JsonObject> entries(final ByteArrayOutputStream out) {
        final List<JsonObject> list = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (line.startsWith(AiTape.PREFIX)) {
                final JsonObject o = JsonParser.parseString(line.substring(AiTape.PREFIX.length())).getAsJsonObject();
                if (o.has("n")) {
                    list.add(o);
                }
            }
        }
        return list;
    }

    private static int lazyChildren(final JsonObject entry, final String kind) {
        int n = 0;
        if (entry.has("sub")) {
            for (var e : entry.getAsJsonArray("sub")) {
                final JsonObject c = e.getAsJsonObject();
                if ("lazy".equals(AiTape.str(c, "k")) && kind.equals(AiTape.str(c, "m"))) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    public void aTraitBuiltWhileBothSeatsHaveAFrameOpenBelongsToTheInnermostFrame() {
        final Game game = initAndCreateGame();
        final Player p0 = game.getPlayers().get(0);
        final Player p1 = game.getPlayers().get(1);
        final Card land0 = addCardToZone("Forest", p0, ZoneType.Hand);
        final Card land1 = addCardToZone("Forest", p1, ZoneType.Hand);
        final ByteArrayOutputStream out0 = new ByteArrayOutputStream();
        final ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        final PlayoutKeys.TrackedRandom rng = new PlayoutKeys.TrackedRandom(7L);
        // seat 0's tape is installed first: the old dispatch gave it every build while it had a frame open
        final AiTape t0 = AiTape.record(game, p0, rng, new PrintStream(out0, true, StandardCharsets.UTF_8)).tagSeat();
        final AiTape t1 = AiTape.record(game, p1, rng, new PrintStream(out1, true, StandardCharsets.UTF_8)).tagSeat();
        t0.install();
        t1.install();
        try {
            t0.bindGameThread(Thread.currentThread());
            t1.bindGameThread(Thread.currentThread());
            // seat 0's execution is open, and inside it seat 1 decides (another player's choice during a resolution)
            final Object outer = t0.begin("m", "playChosenSpellAbility", true);
            final Object inner = t1.begin("m", "chooseCardsForEffect", false);
            land1.getState(CardStateName.Original).getLandManaForColor(MagicColor.Color.BLACK);
            t1.end(inner, new JsonArray());
            // back in seat 0's own frame: its build is its own
            land0.getState(CardStateName.Original).getLandManaForColor(MagicColor.Color.RED);
            t0.end(outer, new JsonArray());
        } finally {
            t1.uninstall();
            t0.uninstall();
        }
        final List<JsonObject> e0 = entries(out0);
        final List<JsonObject> e1 = entries(out1);
        assertEquals(e0.size(), 1);
        assertEquals(e1.size(), 1);
        assertEquals(lazyChildren(e1.get(0), "landMana:BLACK"), 1, "the inner frame's build is the inner seat's");
        assertEquals(lazyChildren(e0.get(0), "landMana:BLACK"), 0, "never the outer seat's");
        assertEquals(lazyChildren(e0.get(0), "landMana:RED"), 1, "the outer seat's own build after the inner frame closed");
        assertFalse(e1.get(0).toString().contains("landMana:RED"));
        assertTrue(e0.get(0).has("s") && e1.get(0).has("s"));
    }
}
