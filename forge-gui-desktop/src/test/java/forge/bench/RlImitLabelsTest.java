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
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlSeat;

/**
 * Lane sdcc-imit-1111: with the search spec's {@code imit}, GAME_END and the tape carry {@code imit}, one
 * {@code [dec_idx, reason, [steps played]]} per decision whose answer a search component chose: every entry is an
 * overridden decision, its steps are the tape row's, and its reason is the component's kind (here exploration, the one
 * search component a game against the stand-in policy can run without a service: reason {@link RlSeat#IMIT_EXPLORE},
 * which a learner never imitates). The labels change nothing in the game (same digest, rows and overridden list as the
 * same spec without the key), and without the key no {@code imit} is written.
 *
 * <p>Plays real Forge games: cwd = forge-gui with res/; fixtures as RlActorBenchTest.
 */
public class RlImitLabelsTest {
    static final String OFF = RlSearchOverriddenTest.SPEC;
    static final String ON = OFF.substring(0, OFF.length() - 1) + ",\"imit\":true}";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static RlActorBench.Played play(final FakeRlServer checker, final JsonObject game, final String spec,
            final RlSearchOverriddenTest.Keeping ep) throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("train");
        cfg.search = RlSearch.Config.parse(JsonParser.parseString(spec).getAsJsonObject());
        return RlActorBench.play(cfg, "train", game.deepCopy(), new RlFeaturizer(RlActorBenchTest.index), ep, null, "test");
    }

    @Test
    public void specKeyRoundTrips() {
        Assert.assertFalse(RlSearch.Config.parse(JsonParser.parseString(OFF).getAsJsonObject()).imit);
        final RlSearch.Config on = RlSearch.Config.parse(JsonParser.parseString(ON).getAsJsonObject());
        Assert.assertTrue(on.imit);
        Assert.assertTrue(on.toJson().get("imit").getAsBoolean());
        Assert.assertFalse(RlSearch.Config.parse(JsonParser.parseString(OFF).getAsJsonObject()).toJson().has("imit"),
                "off: absent from the spec's JSON");
    }

    @Test
    public void labelsNameTheAnswersTheSearchPlayedAndChangeNothing() throws Exception {
        final FakeRlServer checker = new FakeRlServer(0, "train", 7, null, Collections.emptyList());
        int labels = 0;
        for (int i = 0; i < 3; i++) {
            final JsonObject game = RlActorBenchTest.game(i, "rl:M", i % 2 == 0 ? "rl:M" : "forge", true);
            final RlSearchOverriddenTest.Keeping e0 = new RlSearchOverriddenTest.Keeping(new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played off = play(checker, game, OFF, e0);
            final RlSearchOverriddenTest.Keeping e1 = new RlSearchOverriddenTest.Keeping(new RlActorBenchTest.LocalEndpoint(checker));
            final RlActorBench.Played on = play(checker, game, ON, e1);
            Assert.assertNull(off.guardError);
            Assert.assertNull(on.guardError);
            // ---- off: no key anywhere
            for (String k : new String[] {"imit", "imit_hook_rows", "imit_map_failed"}) {
                Assert.assertFalse(off.end.has(k) || off.tape.has(k), "no " + k + " without the spec key");
            }
            // ---- on: the same game
            Assert.assertEquals(on.end.get("digest"), off.end.get("digest"), "game " + i + ": the labels change no answer");
            Assert.assertEquals(on.tape.get("dec"), off.tape.get("dec"));
            Assert.assertEquals(on.end.get("overridden"), off.end.get("overridden"));
            Assert.assertEquals(on.end.get("search_overridden"), off.end.get("search_overridden"));
            Assert.assertEquals(on.end.get("imit_hook_rows").getAsInt(), 0, "no line was followed");
            Assert.assertEquals(on.tape.get("imit"), on.end.get("imit"), "the tape's list is GAME_END's");
            // ---- the labels: exactly the answers the search changed, with the steps played
            final TreeSet<Integer> changed = RlSearchOverriddenTest.changed(on.tape, e1);
            final TreeSet<Integer> over = RlSearchOverriddenTest.ints(on.end.getAsJsonArray("overridden"));
            final java.util.Map<Integer, JsonArray> played = new java.util.HashMap<>();
            for (JsonElement e : on.tape.getAsJsonArray("dec")) {
                played.put(e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(4).getAsJsonArray());
            }
            final TreeSet<Integer> labelled = new TreeSet<>();
            for (JsonElement e : on.end.getAsJsonArray("imit")) {
                final JsonArray row = e.getAsJsonArray();
                final int d = row.get(0).getAsInt();
                Assert.assertTrue(labelled.add(d), "one label per decision");
                Assert.assertTrue(over.contains(d), "a labelled decision is overridden");
                Assert.assertEquals(row.get(1).getAsInt(), RlSeat.IMIT_EXPLORE, "dec " + d + ": an exploration deviation");
                Assert.assertEquals(row.get(2).getAsJsonArray(), played.get(d), "dec " + d + ": the steps played");
            }
            Assert.assertEquals(labelled, changed, "game " + i + ": the labels are the changed answers");
            labels += labelled.size();
        }
        checker.close();
        System.err.println("[imit-labels] labels " + labels);
        Assert.assertTrue(labels > 0);
    }
}
