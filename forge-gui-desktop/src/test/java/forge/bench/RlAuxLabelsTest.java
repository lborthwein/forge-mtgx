package forge.bench;

import java.util.Collections;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlSearch;

/**
 * Lane sdcc-aux-1111: with the search spec's {@code aux}, GAME_END and the tape carry {@code aux}, one
 * {@code [dec_idx, gf, gf_turn, gf_opp, gf_opp_turn, reach]} per decision the search saw: the seat's goldfish and its
 * turn, the opponent's, the seat's one-draw reach (null = absent). Labels for a learner's auxiliary OUTPUT head only:
 * they change nothing in the game (same digest, rows and overridden list as the same spec without the key), and
 * without the key no {@code aux} is written.
 *
 * <p>Plays real Forge games: cwd = forge-gui with res/; fixtures as RlActorBenchTest.
 */
public class RlAuxLabelsTest {
    static final String OFF = RlSearchOverriddenTest.SPEC;
    static final String ON = OFF.substring(0, OFF.length() - 1) + ",\"aux\":{\"gf\":true,\"opp\":true}}";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    @Test
    public void specKeyRoundTrips() {
        final RlSearch.Config off = RlSearch.Config.parse(JsonParser.parseString(OFF).getAsJsonObject());
        Assert.assertNull(off.aux);
        Assert.assertFalse(off.toJson().has("aux"), "off: absent from the spec's JSON");
        final RlSearch.Config on = RlSearch.Config.parse(JsonParser.parseString(ON).getAsJsonObject());
        Assert.assertNotNull(on.aux);
        Assert.assertEquals(on.toJson().get("aux"), JsonParser.parseString("{\"gf\":true,\"opp\":true}"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(JsonParser
                .parseString(OFF.substring(0, OFF.length() - 1) + ",\"aux\":{\"nope\":1}}").getAsJsonObject()));
    }

    @Test
    public void labelsAreDenseAndChangeNothing() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        int labels = 0, own = 0, opp = 0, rows = 0;
        for (int i = 0; i < 3; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true);
            final RlSearchOverriddenTest.Keeping e0 = new RlSearchOverriddenTest.Keeping(new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played off = RlImitLabelsTest.play(checker, game, OFF, e0);
            final RlSearchOverriddenTest.Keeping e1 = new RlSearchOverriddenTest.Keeping(new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played on = RlImitLabelsTest.play(checker, game, ON, e1);
            Assert.assertNull(off.guardError);
            Assert.assertNull(on.guardError);
            // ---- off: no key anywhere
            Assert.assertFalse(off.end.has("aux") || off.tape.has("aux"), "no aux without the spec key");
            // ---- on: the same game
            Assert.assertEquals(on.end.get("digest"), off.end.get("digest"), "game " + i + ": the labels change no answer");
            Assert.assertEquals(on.tape.get("dec"), off.tape.get("dec"));
            Assert.assertEquals(on.end.get("overridden"), off.end.get("overridden"));
            Assert.assertEquals(on.end.get("search_overridden"), off.end.get("search_overridden"));
            Assert.assertEquals(on.end.get("result"), off.end.get("result"));
            Assert.assertEquals(on.end.get("turns"), off.end.get("turns"));
            Assert.assertEquals(on.tape.get("aux"), on.end.get("aux"), "the tape's list is GAME_END's");
            // ---- the labels: one per decision at most, each a decision of the tape, in order, well formed
            final TreeSet<Integer> decs = new TreeSet<>();
            for (JsonElement e : on.tape.getAsJsonArray("dec")) {
                decs.add(e.getAsJsonArray().get(0).getAsInt());
            }
            rows += decs.size();
            int last = -1;
            for (JsonElement e : on.end.getAsJsonArray("aux")) {
                final JsonArray row = e.getAsJsonArray();
                Assert.assertEquals(row.size(), 6);
                final int d = row.get(0).getAsInt();
                Assert.assertTrue(d > last, "in decision order, one label per decision");
                last = d;
                Assert.assertTrue(decs.contains(d), "dec " + d + " is a decision of the tape");
                for (int k : new int[] {1, 3}) {
                    if (!row.get(k).isJsonNull()) {
                        final double g = row.get(k).getAsDouble();
                        Assert.assertTrue(g >= 0 && g <= 1, "dec " + d + ": a goldfish value in [0, 1]: " + g);
                        Assert.assertFalse(row.get(k + 1).isJsonNull(), "a goldfish value comes with its turn");
                        Assert.assertTrue(g > 0 == row.get(k + 1).getAsInt() >= 0, "a line has a turn, no line has none");
                        if (k == 1) {
                            own++;
                        } else {
                            opp++;
                        }
                    }
                }
                Assert.assertTrue(row.get(5).isJsonNull(), "reach is off in this spec");
                labels++;
            }
        }
        checker.close();
        System.err.println("[aux-labels] rows " + rows + " labels " + labels + " own " + own + " opp " + opp);
        Assert.assertTrue(labels > 50, "labels " + labels);
        Assert.assertTrue(own > 50 && opp > 0, "own " + own + " opp " + opp);
    }
}
