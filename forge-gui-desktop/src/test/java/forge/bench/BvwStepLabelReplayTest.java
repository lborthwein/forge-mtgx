package forge.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.BeliefSampler;
import forge.ai.simulation.HonestLabel;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlReplayLabeller;
import forge.bench.rl.StepSearch;

/**
 * Lane bvw-step1-build-1024: the step-1 labeller end to end on real replayed games (RlLabelReplayTest's fixture games)
 * against the test search service: every replay stays digest-equal, every row is a mtgx-bvw-value/2 row whose target
 * agrees with its proof and its search, the search's leaves are scored by the service (LEAVES, no fallback), and two
 * labelled replays give identical rows. Run inside a broker test lease, cwd = forge-gui with res/.
 */
public class BvwStepLabelReplayTest {

    static final String SHA = "a".repeat(64);

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlLabelReplayTest.tapes.isEmpty()) {
            RlLabelReplayTest.setUp();
        }
    }

    static JsonObject counterLabel(final Path labels) {
        final JsonObject o = new JsonObject();
        o.addProperty("redraw", "belief");
        o.addProperty("worlds", 2);
        o.addProperty("lossWallMs", 0);
        o.addProperty("out", labels.toString());
        final com.google.gson.JsonArray band = new com.google.gson.JsonArray();
        band.add(0);
        band.add(2);
        o.add("band", band);
        for (String k : new String[] {"solverCfg", "lossCfg"}) {
            final JsonObject c = new JsonObject();
            c.addProperty("hideOwnLibrary", "true");
            c.addProperty("budget", "counter");
            c.addProperty("engineSteps", "500");
            c.addProperty("evalSteps", "500");
            c.addProperty("engineCards", "8000");
            c.addProperty("enginePathCards", "8000");
            o.add(k, c);
        }
        return o;
    }

    static JsonObject search(final int port) {
        return JsonParser.parseString("{\"rlsearch\":{\"server\":\"127.0.0.1:" + port + "\",\"policySha\":\"" + SHA
                + "\",\"worlds\":2,\"breadth\":3,\"horizon\":1,\"leaf\":\"value\",\"playout\":\"policy\",\"threads\":1,"
                + "\"belief\":\"uniform\",\"beliefCube\":\"" + BeliefSampler.LIVE_CUBE + "\",\"beliefCubeSha256\":\""
                + BeliefSampler.LIVE_CUBE_SHA256 + "\",\"beliefSingleton\":false,\"plans\":{\"samples\":1,"
                + "\"minProvenWorlds\":2,\"solverCfg\":{\"budget\":\"counter\",\"hideOwnLibrary\":\"true\",\"worlds\":\"2\","
                + "\"engineSteps\":\"500\",\"evalSteps\":\"500\",\"engineCards\":\"8000\",\"enginePathCards\":\"8000\"}}}}")
                .getAsJsonObject();
    }

    static RlActorBench.Cfg cfg(final Path labels, final int port) {
        final JsonObject l = counterLabel(labels);
        l.add("search", search(port));
        final JsonObject c = new JsonObject();
        c.add("label", l);
        final RlActorBench.Cfg rc = RlActorBench.parse(c);
        final RlActorBench.Cfg base = RlLabelReplayTest.replayCfg(labels, 2);
        base.labelSpec = rc.labelSpec;
        base.labelBandLo = rc.labelBandLo;
        base.labelTurns = rc.labelTurns;
        base.labelSearch = rc.labelSearch;
        return base;
    }

    static Map<String, JsonObject> rows(final Path dir) throws Exception {
        final Map<String, JsonObject> out = new TreeMap<>();
        for (JsonObject r : RlLabelReplayTest.jsonl(dir, "index-")) {
            final JsonObject x = r.deepCopy();
            x.remove("ms");
            if (x.has("search") && x.get("search").isJsonObject()) {
                x.getAsJsonObject("search").remove("ms");
            }
            if (x.has("meter") && x.get("meter").isJsonObject()) {
                x.getAsJsonObject("meter").remove("engine_ms");
            }
            out.put(r.get("game_uid").getAsString() + "/" + r.get("dec_idx").getAsInt(), x);
        }
        return out;
    }

    @Test(timeOut = 1800000)
    public void stepRowsCarryHonestSearchTargetsAndRepeat() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.leafValue = p -> -0.2; // P(win) 0.4 at every leaf
            final Path a = Files.createTempDirectory(RlLabelReplayTest.out, "step-a-");
            Assert.assertEquals(RlActorBench.replay(cfg(a, svc.port()), RlLabelReplayTest.index, "test"), 0);
            final List<JsonObject> games = RlLabelReplayTest.jsonl(a, "games-");
            Assert.assertEquals(games.size(), RlLabelReplayTest.tapes.size());
            for (JsonObject g : games) {
                Assert.assertTrue(g.get("equal").getAsBoolean(), "digest of " + g.get("game_uid"));
                Assert.assertEquals(g.get("schema").getAsString(), RlReplayLabeller.SCHEMA_STEP);
            }
            final List<Object[]> recs = RlLabelReplayTest.records(a);
            Assert.assertTrue(recs.size() > 0, "no step rows");
            final Map<String, Integer> src = new TreeMap<>();
            for (Object[] r : recs) {
                final JsonObject h = (JsonObject) r[0];
                Assert.assertEquals(h.get("schema").getAsString(), RlReplayLabeller.SCHEMA_STEP);
                final int tbe = h.get("turns_before_end").getAsInt();
                Assert.assertTrue(tbe >= 0 && tbe < 2, "band " + tbe);
                final String s = h.get("target_source").getAsString();
                src.merge(s, 1, Integer::sum);
                final String label = h.get("label").getAsString();
                final JsonElement proof = h.get("proof");
                if (label.equals("win") || label.equals("loss")) {
                    Assert.assertEquals(proof.getAsString(), label);
                    Assert.assertEquals(s, "proof");
                    Assert.assertEquals(h.get("target").getAsDouble(), label.equals("win") ? 1.0 : 0.0);
                } else {
                    Assert.assertTrue(proof.isJsonNull());
                }
                if (s.equals("search")) {
                    final JsonObject sr = h.getAsJsonObject("search");
                    Assert.assertFalse(sr.get("leaf_fallback").getAsBoolean());
                    Assert.assertEquals(h.get("target").getAsDouble(), sr.get("v").getAsDouble(), 1e-9);
                    Assert.assertTrue(h.get("target").getAsDouble() >= 0 && h.get("target").getAsDouble() <= 1);
                }
                if (s.equals("none")) {
                    Assert.assertTrue(h.get("target").isJsonNull());
                }
                Assert.assertEquals(h.get("search_sha").getAsString(), StepSearch.Spec.parse(search(svc.port()), 2).sha());
            }
            System.err.println("[bvw-step test] rows " + recs.size() + " by target source " + src + " leaves "
                    + svc.leaves.get() + " decides " + svc.decides.get());
            Assert.assertTrue(src.getOrDefault("search", 0) > 0, "no search target: " + src);
            Assert.assertTrue(svc.leaves.get() > 0, "the service scored no leaves");
            // the same replay again: identical rows (counter budgets; the test service is deterministic)
            final Path b = Files.createTempDirectory(RlLabelReplayTest.out, "step-b-");
            Assert.assertEquals(RlActorBench.replay(cfg(b, svc.port()), RlLabelReplayTest.index, "test"), 0);
            final Map<String, JsonObject> ra = rows(a), rb = rows(b);
            Assert.assertEquals(rb.keySet(), ra.keySet());
            for (String k : ra.keySet()) {
                // the search_sha names the service's port, the same in both runs
                Assert.assertEquals(rb.get(k), ra.get(k), k);
            }
        }
    }

    @Test
    public void aStepSearchNeedsCounterProofs() {
        final JsonObject l = counterLabel(java.nio.file.Paths.get("/tmp/never"));
        for (String k : new String[] {"solverCfg", "lossCfg"}) {
            l.getAsJsonObject(k).remove("budget");
        }
        l.add("search", search(1));
        final JsonObject c = new JsonObject();
        c.add("label", l);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(c));
        Assert.assertFalse(HonestLabel.Spec.parse(l).counterBudgets());
    }
}
