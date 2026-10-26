package forge.ai.simulation;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.IdScope;

/** Exercise the actual root-copy function shared by win/loss labelling; these fixtures never become corpus rows. */
public class HonestLabelWorldTest extends SimulationTest {
    static List<String> names(Player p, ZoneType zone) {
        List<String> out = new ArrayList<>();
        for (Card c : p.getCardsIn(zone)) out.add(c.getName());
        return out;
    }

    @Test
    public void rootWorldsRedrawHiddenOpponentAndShuffleOurLibrary() throws Exception {
        Game live = initAndCreateGame();
        Player me = live.getPlayers().get(0), opp = live.getPlayers().get(1);
        live.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        for (String card : new String[] {"Island", "Mountain", "Forest", "Plains", "Swamp", "Shock"}) {
            addCardToZone(card, me, ZoneType.Library);
        }
        addCardToZone("Lightning Bolt", me, ZoneType.Hand);
        for (int i = 0; i < 12; i++) addCardToZone("Raging Goblin", opp, ZoneType.Library);
        for (int i = 0; i < 3; i++) addCardToZone("Craw Wurm", opp, ZoneType.Hand);
        JsonArray cards = new JsonArray();
        for (String name : new String[] {"Counterspell", "Giant Growth", "Hill Giant"}) {
            JsonObject card = new JsonObject();
            card.addProperty("name", name);
            card.add("tags", new JsonArray());
            cards.add(card);
        }
        JsonObject cube = new JsonObject();
        cube.addProperty("schema", "mtgx-belief-cube/1");
        cube.add("cards", cards);
        Path file = Files.createTempFile("honest-label-cube-", ".json");
        Files.writeString(file, cube.toString());
        HonestLabel.Spec spec = new HonestLabel.Spec();
        spec.redraw = "belief";
        spec.solverCfg.addProperty("hideOwnLibrary", "true");
        spec.lossCfg.addProperty("hideOwnLibrary", "true");
        spec.beliefCube = file.toString();
        spec.beliefCubeSha256 = null; // a test-owned pool, never a corpus configuration
        spec.beliefBasics = 2;
        spec.beliefSingleton = false;
        HonestLabel.Seat seat = new HonestLabel.Seat(spec);
        Method world = HonestLabel.Seat.class.getDeclaredMethod("world", Game.class, Player.class, Player.class,
                Random.class);
        world.setAccessible(true);
        String before = LookaheadSearch.fingerprint(live);
        List<String> own = names(me, ZoneType.Library);
        Set<List<String>> orders = new HashSet<>();
        IdScope.open();
        try {
            for (int seed = 0; seed < 8; seed++) {
                Object[] w = (Object[]) world.invoke(seat, live, me, opp, new Random(seed));
                Player cm = (Player) w[1], co = (Player) w[2];
                Assert.assertEquals(names(cm, ZoneType.Hand), names(me, ZoneType.Hand));
                List<String> shuffled = names(cm, ZoneType.Library);
                Assert.assertEquals(new HashSet<>(shuffled), new HashSet<>(own));
                Assert.assertEquals(shuffled.size(), own.size());
                orders.add(shuffled);
                for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Library}) {
                    Assert.assertEquals(names(co, z).size(), names(opp, z).size());
                    for (String name : names(co, z)) {
                        Assert.assertFalse(name.equals("Craw Wurm") || name.equals("Raging Goblin"), name);
                        Assert.assertTrue(Set.of("Counterspell", "Giant Growth", "Hill Giant").contains(name)
                                || BeliefSampler.isBasic(name), name);
                    }
                }
            }
        } finally {
            IdScope.close();
            Files.delete(file);
        }
        Assert.assertTrue(orders.size() > 1, "unknown own library order was not shuffled");
        Assert.assertEquals(LookaheadSearch.fingerprint(live), before);
    }
}
