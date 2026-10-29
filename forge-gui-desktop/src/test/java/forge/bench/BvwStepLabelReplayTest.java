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
        return counterLabel(labels, 0, 2);
    }

    static JsonObject counterLabel(final Path labels, final int lo, final int hi) {
        final JsonObject o = new JsonObject();
        o.addProperty("redraw", "belief");
        o.addProperty("worlds", 2);
        o.addProperty("lossWallMs", 0);
        o.addProperty("sequentialAi", true);
        o.addProperty("aiTimeoutSec", 600);
        o.addProperty("out", labels.toString());
        final com.google.gson.JsonArray band = new com.google.gson.JsonArray();
        band.add(lo);
        band.add(hi);
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
        return cfg(labels, searchBlock, 0, 2);
    }

    static RlActorBench.Cfg cfg(final Path labels, final JsonObject searchBlock, final int lo, final int hi) {
        final JsonObject l = counterLabel(labels, lo, hi);
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
        base.watchdog.enabled = false; // lki-fix-port-1027 (review S3-4): no stall watchdog in these label replays
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
     * The seat/dec_idx swap fix (2026-10-25): with a step-0 reference (label index rows, the window [0, 2) here), a
     * replay that reproduces the reference's positions writes its rows unchanged; a game whose positions differ from
     * the reference (one row's seat flipped, as a replay that took a line in another priority would) writes none, and
     * its games row says so; the other games are untouched. A game the reference does not know is void too.
     */
    @Test(timeOut = 1800000)
    public void aStep0ReferenceKeepsOnlyTheSameTrajectory() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.leafValue = p -> -0.2;
            final Path a = Files.createTempDirectory(RlLabelReplayTest.out, "step-ref-a-");
            Assert.assertEquals(RlActorBench.replay(cfg(a, svc.port()), RlLabelReplayTest.index, "test"), 0);
            Path idx = null;
            try (java.util.stream.Stream<Path> ls = Files.list(a)) {
                for (Path q : (Iterable<Path>) ls::iterator) {
                    if (q.getFileName().toString().startsWith("index-")) {
                        idx = q;
                    }
                }
            }
            Assert.assertNotNull(idx);
            final Map<String, JsonObject> ra = rows(a);
            // the same positions: every game kept, rows identical
            final Path b = Files.createTempDirectory(RlLabelReplayTest.out, "step-ref-b-");
            final RlActorBench.Cfg cb = cfg(b, svc.port());
            cb.reference = java.util.List.of(idx.toString());
            cb.referenceTurns = 2;
            Assert.assertEquals(RlActorBench.replay(cb, RlLabelReplayTest.index, "test"), 0);
            for (JsonObject g : RlLabelReplayTest.jsonl(b, "games-")) {
                Assert.assertTrue(g.get("trajectory_equal").getAsBoolean(), g.toString());
            }
            Assert.assertEquals(rows(b), ra);
            // one row's seat flipped in the reference: its game writes nothing, the others are kept
            final List<String> lines = Files.readAllLines(idx);
            final JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
            final String gone = first.get("game_uid").getAsString();
            first.addProperty("seat", 1 - first.get("seat").getAsInt());
            lines.set(0, forge.bench.rl.RlWire.canonicalString(first));
            final Path bad = a.resolve("reference-tampered.jsonl");
            Files.write(bad, lines);
            final Path c = Files.createTempDirectory(RlLabelReplayTest.out, "step-ref-c-");
            final RlActorBench.Cfg cc = cfg(c, svc.port());
            cc.reference = java.util.List.of(bad.toString());
            cc.referenceTurns = 2;
            RlActorBench.replay(cc, RlLabelReplayTest.index, "test");
            int voided = 0;
            for (JsonObject g : RlLabelReplayTest.jsonl(c, "games-")) {
                final boolean te = g.get("trajectory_equal").getAsBoolean();
                Assert.assertEquals(te, !gone.equals(g.get("game_uid").getAsString()), g.toString());
                if (!te) {
                    voided++;
                    Assert.assertEquals(g.get("rows").getAsInt(), 0);
                    Assert.assertEquals(g.getAsJsonObject("trajectory").get("first_diff").getAsInt(),
                            first.get("dec_idx").getAsInt());
                }
            }
            Assert.assertEquals(voided, 1);
            final Map<String, JsonObject> rc = rows(c);
            final Map<String, JsonObject> keep = new TreeMap<>(ra);
            keep.keySet().removeIf(k -> k.startsWith(gone + "/"));
            Assert.assertEquals(rc, keep);
            Assert.assertTrue(rc.size() < ra.size());
        }
    }

    // ------------------------------------------------------------------------------------------------ review-align #5

    /** One labelled replay of the fixture games (band [0, 2), leaves at -0.2), shared by the reference tests below. */
    static Path baseline;
    static Map<String, JsonObject> baselineRows;

    static synchronized Path baseline() throws Exception {
        if (baseline == null) {
            try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
                svc.leafValue = p -> -0.2;
                final Path a = Files.createTempDirectory(RlLabelReplayTest.out, "step-ref-base-");
                Assert.assertEquals(RlActorBench.replay(cfg(a, svc.port()), RlLabelReplayTest.index, "test"), 0);
                baseline = a;
                baselineRows = rows(a);
            }
        }
        return baseline;
    }

    static Path indexFile(final Path dir) throws Exception {
        try (java.util.stream.Stream<Path> ls = Files.list(dir)) {
            for (Path q : (Iterable<Path>) ls::iterator) {
                if (q.getFileName().toString().startsWith("index-")) {
                    return q;
                }
            }
        }
        throw new AssertionError("no index file in " + dir);
    }

    static List<JsonObject> indexRows(final Path dir) throws Exception {
        final List<JsonObject> out = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(indexFile(dir))) {
            out.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return out;
    }

    static Path writeRows(final Path at, final List<JsonObject> rows) throws Exception {
        final List<String> lines = new java.util.ArrayList<>();
        for (JsonObject r : rows) {
            lines.add(forge.bench.rl.RlWire.canonicalString(r));
        }
        Files.write(at, lines);
        return at;
    }

    /** The games of the baseline's index, in order of first appearance. */
    static List<String> gamesOf(final List<JsonObject> rows) {
        final List<String> g = new java.util.ArrayList<>();
        for (JsonObject r : rows) {
            final String u = r.get("game_uid").getAsString();
            if (!g.contains(u)) {
                g.add(u);
            }
        }
        return g;
    }

    /** The first game (in order) other than {@code not} with a row at {@code tbe}. */
    static String gameWith(final List<JsonObject> rows, final int tbe, final String not) {
        for (String g : gamesOf(rows)) {
            if (g.equals(not)) {
                continue;
            }
            for (JsonObject r : rows) {
                if (r.get("game_uid").getAsString().equals(g) && r.get("turns_before_end").getAsInt() == tbe) {
                    return g;
                }
            }
        }
        throw new AssertionError("no game with a tbe-" + tbe + " row (but " + not + ")");
    }

    static JsonObject firstRow(final List<JsonObject> rows, final String game, final int tbe) {
        for (JsonObject r : rows) {
            if (r.get("game_uid").getAsString().equals(game) && r.get("turns_before_end").getAsInt() == tbe) {
                return r;
            }
        }
        throw new AssertionError("no tbe " + tbe + " row of game " + game);
    }

    static Map<String, JsonObject> gamesRows(final Path dir) throws Exception {
        final Map<String, JsonObject> out = new TreeMap<>();
        for (JsonObject g : RlLabelReplayTest.jsonl(dir, "games-")) {
            out.put(g.get("game_uid").getAsString(), g);
        }
        return out;
    }

    static RlActorBench.Cfg withReference(final RlActorBench.Cfg c, final Path ref, final int turns) {
        c.reference = List.of(ref.toString());
        c.referenceTurns = turns;
        return c;
    }

    /**
     * (a) The window's outer edge: the baseline's own rows as the reference at referenceTurns 1 (window [0, 1) of a
     * [0, 2) labelling): a tampered tbe-1 row is outside the window and voids nothing; a tampered tbe-0 row voids its
     * game, at that dec. The other games keep their rows exactly, and every games row records the reference's identity.
     */
    @Test(timeOut = 1800000)
    public void theReferenceWindowsOuterEdge() throws Exception {
        final List<JsonObject> base = indexRows(baseline());
        final List<String> games = gamesOf(base);
        Assert.assertTrue(games.size() >= 2, "fixture games " + games);
        final List<JsonObject> t = new java.util.ArrayList<>();
        for (JsonObject r : base) {
            t.add(r.deepCopy());
        }
        final String gOut = gameWith(t, 1, null), gIn = gameWith(t, 0, gOut);
        final JsonObject out = firstRow(t, gOut, 1);   // outside the window
        out.addProperty("seat", 1 - out.get("seat").getAsInt());
        final JsonObject in = firstRow(t, gIn, 0);     // inside
        in.addProperty("seat", 1 - in.get("seat").getAsInt());
        final Path ref = writeRows(baseline().resolve("reference-edge.jsonl"), t);
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.leafValue = p -> -0.2;
            final Path c = Files.createTempDirectory(RlLabelReplayTest.out, "step-ref-edge-");
            RlActorBench.replay(withReference(cfg(c, svc.port()), ref, 1), RlLabelReplayTest.index, "test");
            final Map<String, JsonObject> gr = gamesRows(c);
            Assert.assertEquals(gr.keySet().size(), games.size());
            for (Map.Entry<String, JsonObject> e : gr.entrySet()) {
                final JsonObject tr = e.getValue().getAsJsonObject("trajectory");
                Assert.assertEquals(tr.get("reference_turns").getAsInt(), 1);
                Assert.assertEquals(tr.getAsJsonArray("reference_sha256").get(0).getAsString(),
                        forge.bench.rl.BvwTrajectoryRefTest.sha256(ref));
                Assert.assertEquals(tr.get("games_known").getAsInt(), games.size());
                Assert.assertTrue(tr.get("stack_compared").getAsBoolean());
                final boolean te = e.getValue().get("trajectory_equal").getAsBoolean();
                Assert.assertEquals(te, !e.getKey().equals(gIn), e.getValue().toString());
                if (!te) {
                    Assert.assertEquals(tr.get("first_diff").getAsInt(), in.get("dec_idx").getAsInt());
                    Assert.assertEquals(e.getValue().get("rows").getAsInt(), 0);
                }
            }
            final Map<String, JsonObject> keep = new TreeMap<>(baselineRows);
            keep.keySet().removeIf(k -> k.startsWith(gIn + "/"));
            Assert.assertEquals(rows(c), keep);
        }
    }

    /**
     * (b) Production's geometry: the band [1, 2) is disjoint from the reference window [0, 1) (as band [3, 5) is from
     * step 0's [0, 3)): the recorder must see the window although no row is written there. Every game stays
     * trajectory-equal and its band rows are the baseline's tbe-1 rows (but the band and the search identity). The
     * rows' state_sha is the baseline's, and the hash is RlSimBench.fingerprint's text (+ stack) at every position.
     */
    @Test(timeOut = 1800000)
    public void aBandDisjointFromTheWindowIsCheckedOnTheWindow() throws Exception {
        final Path ref = indexFile(baseline());
        final int[] probes = {0, 0};
        forge.bench.rl.RlReplayLabeller.TEST_PROBE = g -> {
            synchronized (probes) {
                probes[0]++;
                if (!forge.bench.rl.TrajectoryRef.publicState(g).equals(RlSimBench.fingerprint(g))) {
                    probes[1]++;
                }
            }
        };
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.leafValue = p -> -0.2;
            final Path c = Files.createTempDirectory(RlLabelReplayTest.out, "step-ref-band-");
            final RlActorBench.Cfg cc = withReference(cfg(c, search(svc.port()), 1, 2), ref, 1);
            Assert.assertEquals(RlActorBench.replay(cc, RlLabelReplayTest.index, "test"), 0);
            for (JsonObject g : gamesRows(c).values()) {
                Assert.assertTrue(g.get("trajectory_equal").getAsBoolean(), g.toString());
                Assert.assertTrue(g.getAsJsonObject("trajectory").get("replay_rows").getAsInt() > 0, g.toString());
                Assert.assertEquals(g.get("band_lo").getAsInt(), 1);
            }
            final Map<String, JsonObject> rc = rows(c);
            final Map<String, JsonObject> want = new TreeMap<>();
            for (Map.Entry<String, JsonObject> e : baselineRows.entrySet()) {
                if (e.getValue().get("turns_before_end").getAsInt() == 1) {
                    want.put(e.getKey(), e.getValue());
                }
            }
            Assert.assertFalse(want.isEmpty());
            Assert.assertEquals(rc.keySet(), want.keySet());
            for (String k : rc.keySet()) {
                final JsonObject a = rc.get(k).deepCopy(), b = want.get(k).deepCopy();
                Assert.assertEquals(a.getAsJsonArray("band").get(0).getAsInt(), 1);
                Assert.assertTrue(a.has("state_sha") && a.get("state_sha").getAsString().length() == 16, a.toString());
                for (String x : new String[] {"band", "search_sha"}) {
                    a.remove(x);
                    b.remove(x);
                }
                Assert.assertEquals(a, b, k);
            }
        } finally {
            forge.bench.rl.RlReplayLabeller.TEST_PROBE = null;
        }
        Assert.assertTrue(probes[0] > 0, "no labelled position");
        Assert.assertEquals(probes[1], 0, "publicState != RlSimBench.fingerprint at " + probes[1] + " of " + probes[0]);
    }

    /**
     * (c) The masking cases: a reference row dropped, a reference row added, a row's turn and a row's phase changed,
     * one per game: each game is void, at that dec. A reference without one game's rows refuses the run before any game
     * (review-align #6).
     */
    @Test(timeOut = 1800000)
    public void droppedExtraAndMismatchedReferenceRowsVoidTheirGame() throws Exception {
        final List<JsonObject> base = indexRows(baseline());
        final List<String> games = gamesOf(base);
        Assert.assertTrue(games.size() >= 4, "fixture games " + games);
        final List<JsonObject> t = new java.util.ArrayList<>();
        for (JsonObject r : base) {
            t.add(r.deepCopy());
        }
        final Map<String, Integer> at = new TreeMap<>();
        final JsonObject drop = firstRow(t, games.get(0), 0);
        at.put(games.get(0), drop.get("dec_idx").getAsInt());
        t.remove(drop);
        final JsonObject extra = firstRow(t, games.get(1), 0).deepCopy();
        int maxDec = 0;
        for (JsonObject r : t) {
            if (r.get("game_uid").getAsString().equals(games.get(1))) {
                maxDec = Math.max(maxDec, r.get("dec_idx").getAsInt());
            }
        }
        extra.addProperty("dec_idx", maxDec + 1000);
        t.add(extra);
        at.put(games.get(1), maxDec + 1000);
        final JsonObject turn = firstRow(t, games.get(2), 0);
        turn.addProperty("turn", turn.get("turn").getAsInt() + 1);
        at.put(games.get(2), turn.get("dec_idx").getAsInt());
        final JsonObject phase = firstRow(t, games.get(3), 0);
        phase.addProperty("phase", "MAIN1".equals(phase.get("phase").getAsString()) ? "MAIN2" : "MAIN1");
        at.put(games.get(3), phase.get("dec_idx").getAsInt());
        final Path ref = writeRows(baseline().resolve("reference-masking.jsonl"), t);
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.leafValue = p -> -0.2;
            final Path c = Files.createTempDirectory(RlLabelReplayTest.out, "step-ref-mask-");
            RlActorBench.replay(withReference(cfg(c, svc.port()), ref, 2), RlLabelReplayTest.index, "test");
            final Map<String, JsonObject> gr = gamesRows(c);
            for (String u : games.subList(0, 4)) {
                final JsonObject g = gr.get(u);
                Assert.assertFalse(g.get("trajectory_equal").getAsBoolean(), g.toString());
                Assert.assertEquals(g.get("rows").getAsInt(), 0);
                Assert.assertEquals(g.getAsJsonObject("trajectory").get("first_diff").getAsInt(), (int) at.get(u),
                        g.toString());
            }
            Assert.assertEquals(g0(gr, games.get(0)).get("replay_at").getAsString().equals("none"), false);
            Assert.assertEquals(g0(gr, games.get(0)).get("reference_at").getAsString(), "none");
            Assert.assertEquals(g0(gr, games.get(1)).get("replay_at").getAsString(), "none");
            for (JsonObject rr : RlLabelReplayTest.jsonl(c, "replay")) {
                if (games.subList(0, 4).contains(rr.get("game_uid").getAsString())) {
                    Assert.assertFalse(rr.get("trajectory_equal").getAsBoolean(), rr.toString());
                }
            }
            // one game's rows missing from the reference: refused before any game (no games row written)
            final List<JsonObject> without = new java.util.ArrayList<>();
            for (JsonObject r : base) {
                if (!r.get("game_uid").getAsString().equals(games.get(0))) {
                    without.add(r);
                }
            }
            final Path ref2 = writeRows(baseline().resolve("reference-without-one.jsonl"), without);
            final Path d = Files.createTempDirectory(RlLabelReplayTest.out, "step-ref-missing-");
            Assert.assertEquals(RlActorBench.replay(withReference(cfg(d, svc.port()), ref2, 2), RlLabelReplayTest.index,
                    "test"), 2);
            Assert.assertTrue(RlLabelReplayTest.jsonl(d, "games-").isEmpty());
            Assert.assertTrue(RlLabelReplayTest.records(d).isEmpty());
        }
    }

    static JsonObject g0(final Map<String, JsonObject> gr, final String u) {
        return gr.get(u).getAsJsonObject("trajectory");
    }

    static RlActorBench.Cfg scanCfg(final Path dir) {
        final RlActorBench.Cfg c = RlLabelReplayTest.cfg("replay");
        c.replayDir = RlLabelReplayTest.out.resolve("tapes").toString();
        c.replayOut = dir.resolve("replay.jsonl").toString();
        c.exileScanTurns = 2;
        c.exileScanOut = dir.toString();
        return c;
    }

    static Map<String, JsonObject> scanRows(final Path dir) throws Exception {
        final Map<String, JsonObject> out = new TreeMap<>();
        for (JsonObject r : RlLabelReplayTest.jsonl(dir, "scan-")) {
            out.put(r.get("game_uid").getAsString() + "/" + r.get("dec_idx").getAsInt(), r);
        }
        return out;
    }

    /**
     * (e) The exile scan with a reference (the path that re-scans step 0's misaligned games): red / green on the
     * fixture. Without a reference the rows carry no stack (the plain scan's bytes) and the games row has no trajectory;
     * with the baseline's index as the reference every game is kept, its rows the plain rows plus stack; with one game's
     * row tampered that game writes no rows while its games row still says equal (the digest) and trajectory_equal false.
     */
    @Test(timeOut = 1800000)
    public void anExileScanWithAReferenceWritesOnlyTheSameTrajectory() throws Exception {
        final List<JsonObject> base = indexRows(baseline());
        final List<String> games = gamesOf(base);
        final Path plain = Files.createTempDirectory(RlLabelReplayTest.out, "scan-plain-");
        Assert.assertEquals(RlActorBench.replay(scanCfg(plain), RlLabelReplayTest.index, "test"), 0);
        final Map<String, JsonObject> rp = scanRows(plain);
        Assert.assertFalse(rp.isEmpty());
        for (JsonObject r : rp.values()) {
            Assert.assertFalse(r.has("stack"), r.toString());
        }
        for (JsonObject g : gamesRows(plain).values()) {
            Assert.assertTrue(g.get("equal").getAsBoolean());
            Assert.assertFalse(g.has("trajectory_equal") || g.has("trajectory"), g.toString());
        }
        // green: the same positions
        final Path green = Files.createTempDirectory(RlLabelReplayTest.out, "scan-green-");
        Assert.assertEquals(RlActorBench.replay(withReference(scanCfg(green), indexFile(baseline()), 2),
                RlLabelReplayTest.index, "test"), 0);
        final Map<String, JsonObject> rg = scanRows(green);
        Assert.assertEquals(rg.keySet(), rp.keySet());
        for (String k : rg.keySet()) {
            final JsonObject x = rg.get(k).deepCopy();
            Assert.assertTrue(x.has("stack"), x.toString());
            x.remove("stack");
            Assert.assertEquals(x, rp.get(k), k);
        }
        for (JsonObject g : gamesRows(green).values()) {
            Assert.assertTrue(g.get("equal").getAsBoolean() && g.get("trajectory_equal").getAsBoolean(), g.toString());
        }
        // red: one game's tbe-0 row on the other seat
        final List<JsonObject> t = new java.util.ArrayList<>();
        for (JsonObject r : base) {
            t.add(r.deepCopy());
        }
        final JsonObject bad = firstRow(t, games.get(0), 0);
        bad.addProperty("seat", 1 - bad.get("seat").getAsInt());
        final Path ref = writeRows(baseline().resolve("reference-scan-red.jsonl"), t);
        final Path red = Files.createTempDirectory(RlLabelReplayTest.out, "scan-red-");
        Assert.assertEquals(RlActorBench.replay(withReference(scanCfg(red), ref, 2), RlLabelReplayTest.index, "test"), 0);
        final Map<String, JsonObject> gr = gamesRows(red);
        final JsonObject g = gr.get(games.get(0));
        Assert.assertTrue(g.get("equal").getAsBoolean(), "equal is the digest: " + g);
        Assert.assertFalse(g.get("trajectory_equal").getAsBoolean());
        Assert.assertEquals(g.get("rows").getAsInt(), 0);
        Assert.assertEquals(g.getAsJsonObject("trajectory").get("first_diff").getAsInt(), bad.get("dec_idx").getAsInt());
        final Map<String, JsonObject> rr = scanRows(red);
        final Map<String, JsonObject> keep = new TreeMap<>(rg);
        keep.keySet().removeIf(k -> k.startsWith(games.get(0) + "/"));
        Assert.assertEquals(rr, keep);
        for (JsonObject x : RlLabelReplayTest.jsonl(red, "replay")) {
            Assert.assertEquals(x.get("trajectory_equal").getAsBoolean(), !games.get(0).equals(x.get("game_uid")
                    .getAsString()), x.toString());
        }
    }

    /**
     * Review-align #5: an exile scan whose reference has no stack (as the h6 scan rows) compares seat | turn | phase,
     * and says so in every games row.
     */
    @Test(timeOut = 1800000)
    public void aReferenceWithoutStackFallsBackLoudly() throws Exception {
        final List<JsonObject> t = new java.util.ArrayList<>();
        for (JsonObject r : indexRows(baseline())) {
            final JsonObject x = r.deepCopy();
            x.remove("stack");
            t.add(x);
        }
        final Path ref = writeRows(baseline().resolve("reference-nostack.jsonl"), t);
        final Path d = Files.createTempDirectory(RlLabelReplayTest.out, "scan-nostack-");
        Assert.assertEquals(RlActorBench.replay(withReference(scanCfg(d), ref, 2), RlLabelReplayTest.index, "test"), 0);
        for (JsonObject g : gamesRows(d).values()) {
            Assert.assertTrue(g.get("trajectory_equal").getAsBoolean(), g.toString());
            Assert.assertFalse(g.getAsJsonObject("trajectory").get("stack_compared").getAsBoolean(), g.toString());
        }
        Assert.assertEquals(forge.bench.rl.TrajectoryRef.NO_STACK_GAMES.get(), gamesRows(d).size());
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
