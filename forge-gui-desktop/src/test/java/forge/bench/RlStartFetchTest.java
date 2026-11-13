package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Castable position starts, a fetch land with two possible lands (lane sdcc-startfix-1113): the judge must try each
 * land a fetch land in play can fetch, not only the first in library order. Smoke case: Time Warp {3}{U}{U} rejected
 * with Flooded Strand + Tropical Island dealt, because the Strand was judged as a Plains (here: Tundra, W/U). Own
 * bank, so the pairs and seeds of {@link RlStartCastTest} are unchanged.
 */
public class RlStartFetchTest {

    static Path bank;
    static List<String[]> pairs;

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlSeedingTest.root == null) {
            RlSeedingTest.setUp();
        }
        bank = RlSeedingTest.root.resolve("data/train/cast13f");
        Files.createDirectories(bank.resolve("decks"));
        final JsonArray m = new JsonArray();
        final String[] main = {"14 Plains\n1 Flooded Strand\n1 Tundra\n1 Island\n1 Counterspell\n"
                + "1 Swords to Plowshares\n21 Ornithopter\n", "17 Mountain\n23 Lightning Bolt\n"};
        for (int s = 0; s < 2; s++) {
            final String file = "f00-strand-p" + s + ".dck";
            final Path p = bank.resolve("decks").resolve(file);
            Files.write(p, ("[metadata]\nName=f00-strand-p" + s + "\n[Main]\n" + main[s]).getBytes(StandardCharsets.UTF_8));
            m.add(RlActorBenchTest.entry(file, p));
        }
        RlActorBenchTest.manifest(bank, "cast13f", "train", m);
        pairs = RlFakeRun.pairs(bank);
        Assert.assertEquals(pairs.size(), 1);
    }

    @Test
    public void aFetchLandIsJudgedAsEachLandItCanFetch() throws Exception {
        final List<String> pc = Arrays.asList("Counterspell", "Swords to Plowshares");
        for (int j = 0; j < 6; j++) {
            final JsonObject st = RlStartCastTest.spec(pc, Arrays.asList("hand", "hand"), 2, pc);
            final JsonObject g = RlFakeRun.schedule(bank, j, 1, RlSeedingTest.seed0, new String[] {"forge", "forge"},
                    false, false, 7000L).get(0);
            g.add("start_state", st);
            final RlSeedingTest.Run r = RlSeedingTest.play("train", g);
            Assert.assertNull(r.p.guardError, String.valueOf(r.p.guardError));
            final JsonObject s = RlStartCastTest.started(r);
            // two lands pay both cards (Tundra + Island, or Tundra + the Strand fetching an Island): never rejected,
            // always castable as judged
            Assert.assertTrue(s.get("applied").getAsBoolean(), "j=" + j + " " + s);
            Assert.assertEquals(s.getAsJsonObject("cast").get("check").getAsString(), "now", "j=" + j + " " + s);
        }
    }
}
