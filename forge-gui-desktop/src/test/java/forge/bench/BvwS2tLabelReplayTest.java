package forge.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.FakeSearchService;
import forge.bench.rl.PlanSearch;
import forge.bench.rl.RlReplayLabeller;

/**
 * Lane bvw-step2-fork-1027 (F7): the step-2 labeller (S2T, T-sym with OPR) end to end on RlLabelReplayTest's real
 * replayed games against the test service: every replay stays digest-equal; every row is a mtgx-bvw-value/3 row with
 * its {@code s2t} object; a search row's target is its T-sym value; OPR runs on at most one row per seat and game,
 * always one with a target; and the smoke flag (every variant and the pairs logged) leaves every row's target, target
 * source, variants and OPR bit-identical. Run inside a broker test lease, cwd = forge-gui with res/.
 */
public class BvwS2tLabelReplayTest {

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlLabelReplayTest.tapes.isEmpty()) {
            RlLabelReplayTest.setUp();
        }
    }

    static JsonObject block(final int port, final boolean smoke) {
        final JsonObject b = BvwStepLabelReplayTest.search(port);
        final JsonObject rs = b.getAsJsonObject("rlsearch");
        rs.addProperty("opponent", "policy");
        rs.addProperty("leafSym", true);
        rs.addProperty("belief", "codraft");
        rs.addProperty("beliefPrior", PlanSearch.DEFEND_PRIOR);
        rs.addProperty("beliefPriorSha256", forge.bench.rl.BvwS2tSpecTest.PRIOR_SHA);
        rs.addProperty("beliefPriorGain", 20);
        b.add("target", JsonParser.parseString("{\"variant\":\"T-sym\",\"k\":2}"));
        b.add("opr", JsonParser.parseString("{\"k\":2,\"maxTurns\":4,\"maxSteps\":4000}"));
        if (smoke) {
            b.addProperty("smoke", true);
        }
        return b;
    }

    @Test(timeOut = 3600000)
    public void s2tRowsCarryTheirVariantAndTheSmokeFlagMovesNoTarget() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(BvwStepLabelReplayTest.SHA, 2)) {
            svc.leafValue = p -> (java.util.Arrays.hashCode(p) % 1000) / 1000.0;
            final Path a = Files.createTempDirectory(RlLabelReplayTest.out, "s2t-smoke-");
            Assert.assertEquals(RlActorBench.replay(BvwStepLabelReplayTest.cfg(a, block(svc.port(), true)),
                    RlLabelReplayTest.index, "test"), 0);
            final Path b = Files.createTempDirectory(RlLabelReplayTest.out, "s2t-main-");
            Assert.assertEquals(RlActorBench.replay(BvwStepLabelReplayTest.cfg(b, block(svc.port(), false)),
                    RlLabelReplayTest.index, "test"), 0);
            Assert.assertEquals(BvwStepLabelReplayTest.cfg(a, block(svc.port(), true)).labelSearch.sha(),
                    BvwStepLabelReplayTest.cfg(b, block(svc.port(), false)).labelSearch.sha());
            for (Path d : new Path[] {a, b}) {
                for (JsonObject g : RlLabelReplayTest.jsonl(d, "games-")) {
                    Assert.assertTrue(g.get("equal").getAsBoolean(), "digest of " + g.get("game_uid"));
                    Assert.assertEquals(g.get("schema").getAsString(), RlReplayLabeller.SCHEMA_STEP3);
                }
            }
            final Map<String, JsonObject> ra = rows(a), rb = rows(b);
            Assert.assertEquals(rb.keySet(), ra.keySet());
            Assert.assertFalse(ra.isEmpty(), "no rows");
            final Map<String, Integer> oprBySeat = new HashMap<>();
            final Map<String, Integer> src = new TreeMap<>();
            int t = 0, worlds = 0;
            for (Map.Entry<String, JsonObject> e : ra.entrySet()) {
                final JsonObject h = e.getValue(), m = rb.get(e.getKey());
                Assert.assertEquals(h.get("schema").getAsString(), RlReplayLabeller.SCHEMA_STEP3);
                final JsonObject s = h.getAsJsonObject("s2t");
                Assert.assertEquals(s.get("variant").getAsString(), "T-sym");
                final String source = h.get("target_source").getAsString();
                src.merge(source, 1, Integer::sum);
                if ("search".equals(source)) {
                    Assert.assertEquals(h.get("target").getAsDouble(),
                            s.getAsJsonObject("targets").get("T-sym").getAsDouble(), 0.0, h.toString());
                    t++;
                }
                if (s.has("worlds")) {
                    worlds++;
                }
                if (s.get("opr").isJsonObject()) {
                    Assert.assertFalse(h.get("target").isJsonNull(), "OPR on a row without a target: " + h);
                    oprBySeat.merge(h.get("game_uid").getAsString() + "/" + h.get("seat").getAsInt(), 1, Integer::sum);
                }
                // the smoke flag moves nothing of the spec variant's: target, its source, the variants, OPR
                Assert.assertEquals(m.get("target"), h.get("target"), e.getKey());
                Assert.assertEquals(m.get("target_source"), h.get("target_source"), e.getKey());
                Assert.assertEquals(m.getAsJsonObject("s2t").get("targets").isJsonNull(), s.get("targets").isJsonNull());
                if (!s.get("targets").isJsonNull()) {
                    Assert.assertEquals(m.getAsJsonObject("s2t").getAsJsonObject("targets").get("T-sym"),
                            s.getAsJsonObject("targets").get("T-sym"), e.getKey());
                    Assert.assertEquals(m.getAsJsonObject("s2t").getAsJsonObject("targets").get("H-sym"),
                            s.getAsJsonObject("targets").get("H-sym"), e.getKey());
                }
                Assert.assertEquals(m.getAsJsonObject("s2t").get("opr"), s.get("opr"), e.getKey());
                Assert.assertEquals(m.get("search"), h.get("search"), e.getKey());
                Assert.assertFalse(m.getAsJsonObject("s2t").has("worlds"), "pairs logged without smoke");
            }
            System.err.println("[s2t-replay] rows " + ra.size() + " by source " + src + " T targets " + t + " OPR rows "
                    + oprBySeat + " smoke pairs on " + worlds + " rows; leaves " + svc.leaves.get());
            Assert.assertTrue(t > 0, "no T-sym search target: " + src);
            Assert.assertTrue(worlds > 0, "the smoke logged no pairs");
            Assert.assertFalse(oprBySeat.isEmpty(), "OPR never ran");
            for (Map.Entry<String, Integer> e : oprBySeat.entrySet()) {
                Assert.assertEquals((int) e.getValue(), 1, "more than one OPR row for " + e.getKey());
            }
        }
    }

    static Map<String, JsonObject> rows(final Path dir) throws Exception {
        final Map<String, JsonObject> out = new TreeMap<>();
        for (Object[] r : RlLabelReplayTest.records(dir)) {
            final JsonObject x = ((JsonObject) r[0]).deepCopy();
            x.remove("ms");
            if (x.has("search") && x.get("search").isJsonObject()) {
                x.getAsJsonObject("search").remove("ms");
            }
            if (x.has("meter") && x.get("meter").isJsonObject()) {
                x.getAsJsonObject("meter").remove("engine_ms");
            }
            out.put(x.get("game_uid").getAsString() + "/" + x.get("dec_idx").getAsInt(), x);
        }
        return out;
    }
}
