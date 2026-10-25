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
        o.addProperty("sequentialAi", true);
        o.addProperty("aiTimeoutSec", 600);
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
        return search(port, SHA, null);
    }

    /** The test block: {@code valueSha} null = a control block (the service's policy v(o)). */
    static JsonObject search(final int port, final String policySha, final String valueSha) {
        final JsonObject b = JsonParser.parseString("{\"rlsearch\":{\"server\":\"127.0.0.1:" + port + "\",\"policySha\":\"" + policySha
                + "\",\"worlds\":2,\"breadth\":3,\"horizon\":1,\"leaf\":\"value\",\"playout\":\"policy\",\"threads\":1,"
                + "\"belief\":\"uniform\",\"beliefCube\":\"" + BeliefSampler.LIVE_CUBE + "\",\"beliefCubeSha256\":\""
                + BeliefSampler.LIVE_CUBE_SHA256 + "\",\"beliefSingleton\":false,\"plans\":{\"samples\":1,"
                + "\"minProvenWorlds\":2,\"solverCfg\":{\"budget\":\"counter\",\"hideOwnLibrary\":\"true\",\"worlds\":\"2\","
                + "\"engineSteps\":\"500\",\"evalSteps\":\"500\",\"engineCards\":\"8000\",\"enginePathCards\":\"8000\"}}},"
                + "\"maxCopyCards\":200000}").getAsJsonObject();
        if (valueSha == null) {
            b.addProperty("control", true);
        } else {
            b.addProperty("valueSha", valueSha);
        }
        return b;
    }

    static RlActorBench.Cfg cfg(final Path labels, final int port) {
        return cfg(labels, search(port));
    }

    static RlActorBench.Cfg cfg(final Path labels, final JsonObject searchBlock) {
        final JsonObject l = counterLabel(labels);
        l.add("search", searchBlock);
        final JsonObject c = new JsonObject();
        c.addProperty("aiTimeoutSec", 600);
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
                if (s.equals(forge.bench.rl.HiddenExile.TARGET_SOURCE)) {
                    // OPEN-6 ruling: a face-down exile hidden from the seat: no search, no target, the proof withheld
                    Assert.assertTrue(proof.isJsonNull() && h.get("target").isJsonNull(), h.toString());
                    Assert.assertTrue(h.getAsJsonArray("hidden_exile").size() > 0);
                    Assert.assertEquals(h.getAsJsonObject("search").get("why").getAsString(),
                            forge.bench.rl.HiddenExile.TARGET_SOURCE);
                    if (label.equals("win") || label.equals("loss")) {
                        Assert.assertEquals(h.get("unsupported_proof").getAsString(), label);
                    } else {
                        Assert.assertFalse(h.has("unsupported_proof"));
                    }
                } else if (label.equals("win") || label.equals("loss")) {
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
                Assert.assertEquals(h.get("search_sha").getAsString(), cfg(a, svc.port()).labelSearch.sha());
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
                Assert.assertEquals(rb.get(k), ra.get(k), k);
            }
        }
    }

    /**
     * Review #16b, perspective: every leaf frame is the searching seat's own observation, and the target is that seat's
     * P(win). The service values a leaf by the frame's seat (seat 0 +0.6, seat 1 -0.6), so a searched row of seat 0 must
     * come out near 0.8 and one of seat 1 near 0.2 (terminal worlds aside); a flipped perspective inverts them.
     */
    @Test(timeOut = 1800000)
    public void searchTargetsAreTheSearchingSeatsPWin() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.leafValue = p -> forge.bench.rl.RlWire.decodeDecide(p, 2).seat == 0 ? 0.6 : -0.6;
            final Path a = Files.createTempDirectory(RlLabelReplayTest.out, "step-persp-");
            Assert.assertEquals(RlActorBench.replay(cfg(a, svc.port()), RlLabelReplayTest.index, "test"), 0);
            int n0 = 0, n1 = 0, near0 = 0, near1 = 0;
            double s0 = 0, s1 = 0;
            for (Object[] r : RlLabelReplayTest.records(a)) {
                final JsonObject h = (JsonObject) r[0];
                if (!"search".equals(h.get("target_source").getAsString())) {
                    continue;
                }
                final double t = h.get("target").getAsDouble();
                if (h.get("seat").getAsInt() == 0) {
                    n0++;
                    s0 += t;
                    near0 += Math.abs(t - 0.8) < 0.11 ? 1 : 0;
                } else {
                    n1++;
                    s1 += t;
                    near1 += Math.abs(t - 0.2) < 0.11 ? 1 : 0;
                }
            }
            System.err.println("[bvw-step test] perspective: seat 0 " + n0 + " rows mean " + (s0 / Math.max(1, n0))
                    + " near 0.8 " + near0 + "; seat 1 " + n1 + " rows mean " + (s1 / Math.max(1, n1)) + " near 0.2 " + near1);
            Assert.assertTrue(n0 > 0 && n1 > 0, "searched rows of both seats: " + n0 + " / " + n1);
            Assert.assertTrue(s0 / n0 > 0.6 && s1 / n1 < 0.4, "seat 0 mean " + s0 / n0 + ", seat 1 mean " + s1 / n1);
            Assert.assertTrue(near0 * 2 > n0 && near1 * 2 > n1, "most rows sit at their seat's leaf value");
        }
    }

    /** Review #9 / #16c: a pin the service does not match stops the run (exit 2) and writes no rows. */
    @Test(timeOut = 1800000)
    public void pinMismatchesStopTheRun() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            final JsonObject vb = new JsonObject();
            vb.addProperty("value_sha", "c".repeat(64));
            svc.ackValueBackend = vb;
            // the block pins another tower than the service serves
            final Path a = Files.createTempDirectory(RlLabelReplayTest.out, "step-pin-value-");
            Assert.assertEquals(RlActorBench.replay(cfg(a, search(svc.port(), SHA, "b".repeat(64))),
                    RlLabelReplayTest.index, "test"), 2);
            Assert.assertTrue(RlLabelReplayTest.records(a).isEmpty(), "rows written under a wrong tower");
            // a control block (no tower) against a service that serves one
            final Path b = Files.createTempDirectory(RlLabelReplayTest.out, "step-pin-control-");
            Assert.assertEquals(RlActorBench.replay(cfg(b, search(svc.port(), SHA, null)), RlLabelReplayTest.index,
                    "test"), 2);
            Assert.assertTrue(RlLabelReplayTest.records(b).isEmpty());
            // the right tower: runs
            final Path ok = Files.createTempDirectory(RlLabelReplayTest.out, "step-pin-ok-");
            Assert.assertEquals(RlActorBench.replay(cfg(ok, search(svc.port(), SHA, "c".repeat(64))),
                    RlLabelReplayTest.index, "test"), 0);
            Assert.assertFalse(RlLabelReplayTest.records(ok).isEmpty());
            // the block pins another policy
            svc.ackValueBackend = null;
            final Path c = Files.createTempDirectory(RlLabelReplayTest.out, "step-pin-policy-");
            Assert.assertEquals(RlActorBench.replay(cfg(c, search(svc.port(), "d".repeat(64), null)),
                    RlLabelReplayTest.index, "test"), 2);
            Assert.assertTrue(RlLabelReplayTest.records(c).isEmpty());
        }
        // no service at all
        final Path d = Files.createTempDirectory(RlLabelReplayTest.out, "step-pin-none-");
        Assert.assertEquals(RlActorBench.replay(cfg(d, search(1, SHA, null)), RlLabelReplayTest.index, "test"), 2);
        Assert.assertTrue(RlLabelReplayTest.records(d).isEmpty());
    }

    @Test
    public void aStepSearchNeedsCounterProofs() {
        final JsonObject l = counterLabel(java.nio.file.Paths.get("/tmp/never"));
        for (String k : new String[] {"solverCfg", "lossCfg"}) {
            l.getAsJsonObject(k).remove("budget");
        }
        l.add("search", search(1));
        final JsonObject c = new JsonObject();
        c.addProperty("aiTimeoutSec", 600);
        c.add("label", l);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(c));
        Assert.assertFalse(HonestLabel.Spec.parse(l).counterBudgets());
    }
}
