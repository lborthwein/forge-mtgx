package forge.bench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlPlayFrom;
import forge.bench.rl.RlReplaySearch;
import forge.bench.rl.RlSchema;
import forge.bench.rl.RlTape;
import forge.bench.rl.RlWire;

/**
 * Lane sdcc-item2-hooks-1115: the two offline replay hooks. (1) Parsing and refusals of the {@code replaySearch} /
 * {@code playFrom} blocks and their positions files (no game). (2) Whole games: two pilot games (both seats RL, a search
 * with plans against a stand-in search service whose SCORE value is a function of the observation, the decision log
 * recorded on both RL seats) are replayed forced with REPLAY-SEARCH on at positions from their own search rows: every
 * replay stays digest-equal, every position is found, K runs each write the seat's priority row with its own world draws,
 * and a mutant that moves the live game voids that game's rows (the digest rule). Then PLAY-FROM-POSITION forces a
 * candidate at one position and plays both seats on to the end. Plays real Forge games: run inside a broker test lease,
 * cwd = forge-gui with res/; fixtures as {@link RlActorBenchTest}.
 */
public class RlItem2HooksTest {
    static final String SPEC = "{\"worlds\":2,\"breadth\":3,\"horizon\":1,\"threads\":1,\"departZ\":0,\"leaf\":\"value\","
            + "\"playout\":\"policy\",\"deadEtb\":\"on\",\"zeroX\":\"on\",\"crewNoop\":\"on\",\"plans\":{\"samples\":2}}";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static Path tmp(final String name) throws Exception {
        return Files.createTempDirectory("rl-i2h-" + name + "-");
    }

    static Path lines(final Path dir, final String name, final List<JsonObject> rows) throws Exception {
        final StringBuilder sb = new StringBuilder();
        for (JsonObject r : rows) {
            sb.append(RlWire.canonicalString(r)).append('\n');
        }
        final Path f = dir.resolve(name);
        Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    static JsonObject pos(final String uid, final int seat, final int dec, final int turn, final int c, final double v) {
        final JsonObject o = new JsonObject();
        o.addProperty("game_uid", uid);
        o.addProperty("seat", seat);
        o.addProperty("dec_idx", dec);
        o.addProperty("turn", turn);
        o.addProperty("C", c);
        o.addProperty("v_root", v);
        return o;
    }

    static JsonObject block(final Path positions, final Path out, final String server) {
        final JsonObject b = new JsonObject();
        b.addProperty("positions", positions.toString());
        b.add("spec", JsonParser.parseString(SPEC));
        b.addProperty("server", server);
        b.addProperty("out", out.toString());
        return b;
    }

    // ------------------------------------------------------------------------------------------------ 1. parsing

    @Test
    public void positionsAndBlocksParseAndRefuse() throws Exception {
        final Path d = tmp("parse");
        final JsonObject a = pos("123", 0, 7, 3, 4, 0.25);
        a.addProperty("K", 4);
        a.addProperty("pos_id", "p1");
        final JsonObject b = pos("18446744073709551615", 1, 9, 4, 2, -0.5);
        final JsonArray salts = new JsonArray();
        salts.add(11L);
        salts.add(12L);
        b.add("salts", salts);
        final RlReplaySearch.Spec s = RlReplaySearch.Spec.parse(block(lines(d, "p.jsonl", List.of(a, b)), d.resolve("o"),
                "127.0.0.1:1"), false);
        Assert.assertEquals(s.positions.size(), 2);
        Assert.assertEquals(s.positions.get(0).posId, "p1");
        Assert.assertEquals(s.positions.get(0).K, 4);
        Assert.assertEquals(s.positions.get(1).posId, "18446744073709551615:1:9", "default pos_id = uid:seat:dec");
        Assert.assertEquals(s.positions.get(1).K, 2, "K from the salts");
        Assert.assertEquals(RlReplaySearch.salt(s, s.positions.get(1), 1), 12L);
        // derived salts: distinct, never the live 0x51
        final Set<Long> seen = new HashSet<>();
        for (int k = 0; k < 64; k++) {
            final long x = RlReplaySearch.salt(s, s.positions.get(0), k);
            Assert.assertNotEquals(x, 0x51L);
            Assert.assertTrue(seen.add(x), "salt " + k + " repeats");
        }
        Assert.assertEquals(s.of("123").size(), 1);
        // the run's config: the spec + the hook's service, salt and schema
        Assert.assertEquals(RlReplaySearch.config(s, 99L, 2).seedSalt, 99L);
        Assert.assertEquals(RlReplaySearch.config(s, 99L, 2).server, "127.0.0.1:1");
        // refusals: a missing field, K out of range, salts against K, a repeated position, unknown keys, harness keys
        final JsonObject noC = pos("1", 0, 1, 1, 2, 0);
        noC.remove("C");
        noC.addProperty("K", 1);
        final JsonObject k0 = pos("1", 0, 1, 1, 2, 0);
        final JsonObject bad = pos("1", 0, 1, 1, 2, 0);
        bad.addProperty("K", 3);
        bad.add("salts", salts);
        final JsonObject seat2 = pos("1", 2, 1, 1, 2, 0);
        seat2.addProperty("K", 1);
        for (JsonObject x : new JsonObject[] {noC, k0, bad, seat2}) {
            final Path f = lines(d, "bad.jsonl", List.of(x));
            Assert.assertThrows(IllegalArgumentException.class,
                    () -> RlReplaySearch.Spec.parse(block(f, d.resolve("o"), "127.0.0.1:1"), false));
        }
        final Path twice = lines(d, "twice.jsonl", List.of(a, a));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlReplaySearch.Spec.parse(block(twice, d.resolve("o"), "127.0.0.1:1"), false));
        final Path one = lines(d, "one.jsonl", List.of(a));
        final JsonObject unk = block(one, d.resolve("o"), "127.0.0.1:1");
        unk.addProperty("worlds", 3);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlReplaySearch.Spec.parse(unk, false));
        final JsonObject harness = block(one, d.resolve("o"), "127.0.0.1:1");
        harness.getAsJsonObject("spec").addProperty("decisionLog", "/tmp/x");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlReplaySearch.Spec.parse(harness, false));
        final JsonObject noServer = block(one, d.resolve("o"), "127.0.0.1:1");
        noServer.remove("server");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlReplaySearch.Spec.parse(noServer, false));
        // play-from: actions and seeds are required; jobs = positions x actions x seeds
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlReplaySearch.Spec.parse(block(one, d.resolve("o"), "127.0.0.1:1"), true));
        final JsonObject pf = pos("5", 1, 3, 2, 3, 0.1);
        final JsonArray acts = new JsonArray();
        acts.add(0);
        acts.add(2);
        final JsonArray seeds = new JsonArray();
        seeds.add(0);
        seeds.add(1);
        seeds.add(2);
        pf.add("actions", acts);
        pf.add("seeds", seeds);
        final RlReplaySearch.Spec ps = RlReplaySearch.Spec.parse(block(lines(d, "pf.jsonl", List.of(pf)), d.resolve("o"),
                "127.0.0.1:1"), true);
        final List<RlPlayFrom.Job> jobs = RlPlayFrom.jobs(ps);
        Assert.assertEquals(jobs.size(), 6);
        Assert.assertNotEquals(RlPlayFrom.searchSalt(ps, 0), RlPlayFrom.searchSalt(ps, 1));
        Assert.assertNotEquals(RlPlayFrom.searchSalt(ps, 0), 0x51L);
        // the actor config: replay mode only, alone
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("mode", "train");
        cfg.add("replaySearch", block(one, d.resolve("o"), "127.0.0.1:1"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(cfg));
        cfg.addProperty("mode", "replay");
        Assert.assertNotNull(RlActorBench.parse(cfg).replaySearch);
        Assert.assertNull(RlActorBench.parse(cfg).playFrom);
        final JsonObject both = cfg.deepCopy();
        both.add("playFrom", block(lines(d, "pf2.jsonl", List.of(pf)), d.resolve("o2"), "127.0.0.1:1"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(both));
        final JsonObject withSearch = cfg.deepCopy();
        withSearch.add("search", JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"worlds\":2}"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(withSearch));
        // off: a replay config without either key has neither
        final JsonObject off = new JsonObject();
        off.addProperty("mode", "replay");
        Assert.assertNull(RlActorBench.parse(off).replaySearch);
        Assert.assertNull(RlActorBench.parse(off).playFrom);
    }

    // ------------------------------------------------------------------------------------------------ 2. games

    /** v(o) of a frame from its observation alone (never its header), distinct across observations. */
    static double obsValue(final RlWire.Decide d) {
        long h = 1469598103934665603L;
        for (int x : d.tokCard) {
            h = (h ^ x) * 1099511628211L;
        }
        for (float x : d.scal) {
            h = (h ^ Float.floatToIntBits(x)) * 1099511628211L;
        }
        for (int x : d.candKind) {
            h = (h ^ x) * 1099511628211L;
        }
        h ^= h >>> 31;
        return ((h & 0xffffff) / (double) 0xffffff) * 1.8 - 0.9;
    }

    static List<JsonObject> readAll(final Path dir, final String prefix) throws Exception {
        final List<JsonObject> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path f : (Iterable<Path>) s.sorted()::iterator) {
                if (f.getFileName().toString().startsWith(prefix + "-")) {
                    for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                        if (!l.isBlank()) {
                            out.add(JsonParser.parseString(l).getAsJsonObject());
                        }
                    }
                }
            }
        }
        return out;
    }

    static RlActorBench.Cfg replayCfg() {
        final RlActorBench.Cfg c = RlActorBenchTest.cfg("replay");
        c.decisionLogRlSeats = true;
        c.aiTimeoutSec = 600;
        return c;
    }

    @Test
    public void replaySearchKeepsTheReplayAndPlayFromForcesTheAction() throws Exception {
        try (FakeSearchService svc = new FakeSearchService("0".repeat(64))) {
            svc.scoreValue = RlItem2HooksTest::obsValue;
            svc.leafValue = RlChoiceSearchTest::hashValue;
            final Path d = tmp("games");
            final Path dlog = d.resolve("dlog.jsonl");
            // the source games: both seats the pilot (policy + search with plans), decision log on both RL seats
            final RlActorBench.Cfg gen = RlActorBenchTest.cfg("train");
            gen.aiTimeoutSec = 600;
            gen.decisionLog = "record";
            gen.decisionLogRlSeats = true;
            gen.search = forge.bench.rl.RlSearch.Config.parse(JsonParser.parseString(SPEC.substring(0, SPEC.length() - 1)
                    + ",\"server\":\"" + svc.address() + "\",\"decisionLog\":\"" + dlog + "\"}").getAsJsonObject());
            final List<JsonObject> tapes = new ArrayList<>();
            final int n = Integer.getInteger("rl.i2hGames", 4);
            for (int i = 0; i < n; i++) {
                final RlActorBench.Played p = RlActorBench.play(gen, "train", RlActorBenchTest.game(i, "rl:M", "rl:M", false),
                        new RlFeaturizer(RlActorBenchTest.index), RlActorBenchOpponentTest.endpoint("train"), null, "test");
                Assert.assertNull(p.guardError, String.valueOf(p.guardError));
                Assert.assertTrue(p.tape.get("void").isJsonNull(), "source game " + i + " void " + p.tape.get("void"));
                Assert.assertTrue(p.tape.has("decision_log"), "the source tape carries its decision log");
                tapes.add(p.tape);
            }
            final Path tapeDir = d.resolve("tapes");
            try (RlTape t = new RlTape(tapeDir, 1000)) {
                for (JsonObject j : tapes) {
                    t.write(j);
                }
            }
            final Path tapeFile;
            try (java.util.stream.Stream<Path> s = Files.list(tapeDir)) {
                tapeFile = s.filter(f -> f.getFileName().toString().startsWith("tapes-")).findFirst().orElseThrow();
            }
            // the hook-off forced replay first: the games it reproduces are the ones the hooks are tested on
            final RlActorBench.Cfg bc = replayCfg();
            bc.replayDir = tapeDir.toString();
            bc.replayOut = d.resolve("base-replay.jsonl").toString();
            RlActorBench.replay(bc, RlActorBenchTest.index, "test");
            final Set<String> baseEqual = new HashSet<>();
            for (String l : Files.readAllLines(java.nio.file.Paths.get(bc.replayOut), StandardCharsets.UTF_8)) {
                final JsonObject r = JsonParser.parseString(l).getAsJsonObject();
                System.err.println("[i2h-test] hook-off replay " + r.get("game_uid") + " equal " + r.get("equal")
                        + (r.has("decision_log") && r.getAsJsonObject("decision_log").has("divergence")
                        ? " " + r.getAsJsonObject("decision_log").get("divergence") : ""));
                if (r.get("equal").getAsBoolean()) {
                    baseEqual.add(forge.bench.rl.TrajectoryRef.canonicalUid(r.get("game_uid").getAsString()));
                }
            }
            Assert.assertFalse(baseEqual.isEmpty(), "no source game replays forced");
            // positions: searched priority rows with >= 2 searched entries, the greedy answer from a not-overridden tape row
            final Map<String, JsonObject> byUid = new TreeMap<>();
            for (JsonObject t : tapes) {
                final String u = forge.bench.rl.TrajectoryRef.canonicalUid(t.get("game_uid").getAsString());
                if (baseEqual.contains(u)) {
                    byUid.put(u, t);
                }
            }
            final List<JsonObject> rsPos = new ArrayList<>();
            final Map<String, Integer> perGame = new TreeMap<>();
            for (String l : Files.readAllLines(dlog, StandardCharsets.UTF_8)) {
                final JsonObject r = JsonParser.parseString(l).getAsJsonObject();
                if (r.has("kind") || r.getAsJsonArray("entry").size() < 2) {
                    continue;
                }
                final String uid = forge.bench.rl.TrajectoryRef.canonicalUid(r.get("game_uid").getAsString());
                final JsonObject t = byUid.get(uid);
                if (t == null) {
                    continue;
                }
                final int dec = r.get("dec_idx").getAsInt();
                boolean over = false;
                for (JsonElement e : t.getAsJsonArray("overridden")) {
                    over |= e.getAsInt() == dec;
                }
                Integer seat = null, greedy = null;
                for (JsonElement e : t.getAsJsonArray("dec")) {
                    final JsonArray x = e.getAsJsonArray();
                    if (x.get(0).getAsInt() == dec && x.get(2).getAsInt() == RlSchema.F_PRIORITY) {
                        seat = x.get(1).getAsInt();
                        greedy = x.get(4).getAsJsonArray().get(0).getAsInt();
                    }
                }
                if (over || seat == null || perGame.getOrDefault(uid, 0) >= 3) {
                    continue;
                }
                perGame.merge(uid, 1, Integer::sum);
                final JsonObject q = pos(uid, seat, dec, r.get("turn").getAsInt(), r.get("C").getAsInt(),
                        r.get("v_root").getAsDouble());
                q.addProperty("phase", r.get("phase").getAsString());
                q.addProperty("tape", tapeFile.toString());
                q.addProperty("K", 3);
                q.addProperty("greedy", greedy);
                rsPos.add(q);
            }
            Assert.assertTrue(rsPos.size() >= 2, "too few searched positions in the source games: " + rsPos.size());
            final Path posFile = lines(d, "positions.jsonl", rsPos);

            // REPLAY-SEARCH, forced replay: digest-equal, every position found, K priority rows per position
            final RlActorBench.Cfg rc = replayCfg();
            final Path out = d.resolve("rs");
            rc.replaySearch = RlReplaySearch.Spec.parse(block(posFile, out, svc.address()), false);
            rc.replayOut = d.resolve("rs-replay.jsonl").toString();
            Assert.assertEquals(RlActorBench.replay(rc, RlActorBenchTest.index, "test"), 0, "every hooked replay equal");
            final List<JsonObject> games = readAll(out, "games");
            Assert.assertEquals(games.size(), perGame.size(), "a games row per replayed game");
            for (JsonObject g : games) {
                Assert.assertTrue(g.get("equal").getAsBoolean(), "replay digest with the hook on: " + g);
                Assert.assertEquals(g.get("found").getAsInt(), g.get("listed").getAsInt(), "every position found: " + g);
            }
            final List<JsonObject> status = readAll(out, "status");
            Assert.assertEquals(status.size(), rsPos.size());
            final List<JsonObject> frames = readAll(out, "frames");
            Assert.assertEquals(frames.size(), rsPos.size(), "one frame per position");
            final List<JsonObject> positions = readAll(out, "positions");
            int runs = 0;
            for (JsonObject st : status) {
                Assert.assertTrue(st.get("found").getAsBoolean(), String.valueOf(st));
                Assert.assertFalse(st.get("void").getAsBoolean());
                Assert.assertEquals(st.get("dv").getAsDouble(), 0.0, 1e-6, "the replayed observation is the original's");
                Assert.assertEquals(st.getAsJsonArray("answers").size(), 3);
                for (JsonElement e : st.getAsJsonArray("priority_rows")) {
                    runs += e.getAsInt();
                }
            }
            Assert.assertEquals(positions.size(), runs, "a positions row per run with a priority row");
            Assert.assertTrue(runs >= rsPos.size() * 2, "most runs logged their priority row: " + runs);
            final Map<String, Set<String>> worlds = new TreeMap<>();
            for (int k = 0; k < 3; k++) {
                for (JsonObject r : readAll(out.resolve("k" + k).resolve("logs"), "search-decisions")) {
                    if (!r.has("kind")) {
                        Assert.assertEquals(r.get("run").getAsInt(), k);
                        worlds.computeIfAbsent(r.get("pos_id").getAsString(), x -> new HashSet<>())
                                .add(String.valueOf(r.get("values")));
                    }
                }
            }
            for (Map.Entry<String, Set<String>> e : worlds.entrySet()) {
                Assert.assertTrue(e.getValue().size() >= 1, e.getKey());
            }
            // the replay rows of the hooked run equal the tape digests
            for (String l : Files.readAllLines(java.nio.file.Paths.get(rc.replayOut), StandardCharsets.UTF_8)) {
                final JsonObject r = JsonParser.parseString(l).getAsJsonObject();
                Assert.assertEquals(r.get("digest_replay"), r.get("digest_tape"));
            }

            // the digest rule: a mutant that moves the live game at a found position voids that game's rows
            final RlActorBench.Cfg mc = replayCfg();
            final Path mout = d.resolve("rs-mutant");
            mc.replaySearch = RlReplaySearch.Spec.parse(block(posFile, mout, svc.address()), false);
            mc.replayOut = d.resolve("rs-mutant-replay.jsonl").toString();
            RlReplaySearch.TEST_MUTANT = g -> g.getPlayers().get(0).setLife(g.getPlayers().get(0).getLife() + 7, null);
            final int mrc;
            try {
                mrc = RlActorBench.replay(mc, RlActorBenchTest.index, "test");
            } finally {
                RlReplaySearch.TEST_MUTANT = null;
            }
            Assert.assertEquals(mrc, 4, "an unequal replay exits 4");
            for (JsonObject g : readAll(mout, "games")) {
                Assert.assertFalse(g.get("equal").getAsBoolean(), "the mutant moved the game: " + g);
                Assert.assertEquals(g.get("written").getAsInt(), 0);
            }
            Assert.assertTrue(readAll(mout, "frames").isEmpty(), "no frame of a void game");
            Assert.assertTrue(readAll(mout, "positions").isEmpty(), "no positions row of a void game");
            Assert.assertFalse(Files.exists(mout.resolve("k0")), "no decision-log row of a void game");
            for (JsonObject st : readAll(mout, "status")) {
                Assert.assertTrue(st.get("void").getAsBoolean());
            }

            // PLAY-FROM-POSITION: the first position, its greedy answer and another legal candidate, one seed each
            final JsonObject q0 = rsPos.get(0).deepCopy();
            final JsonObject f0 = frames.stream().filter(f -> f.get("pos_id").getAsString().equals(
                    q0.get("game_uid").getAsString() + ":" + q0.get("seat").getAsInt() + ":" + q0.get("dec_idx").getAsInt()))
                    .findFirst().orElseThrow();
            final RlWire.Decide fr = RlWire.decodeDecide(java.util.Base64.getDecoder().decode(f0.get("payload").getAsString()),
                    f0.get("obs").getAsInt());
            final int g0 = q0.get("greedy").getAsInt();
            int alt = -1;
            for (int c = 0; c < fr.C && alt < 0; c++) {
                if (fr.candKind[c] > 0 && c != g0) {
                    alt = c;
                }
            }
            Assert.assertTrue(alt >= 0);
            q0.remove("K");
            final JsonArray acts = new JsonArray();
            acts.add(g0);
            acts.add(alt);
            final JsonArray seeds = new JsonArray();
            seeds.add(0);
            q0.add("actions", acts);
            q0.add("seeds", seeds);
            final RlActorBench.Cfg pc = replayCfg();
            final Path pout = d.resolve("pf");
            pc.playFrom = RlReplaySearch.Spec.parse(block(lines(d, "pf.jsonl", List.of(q0)), pout, svc.address()), true);
            pc.playFromJobs = RlPlayFrom.jobs(pc.playFrom);
            pc.replayOut = d.resolve("pf-replay.jsonl").toString();
            Assert.assertEquals(RlActorBench.replay(pc, RlActorBenchTest.index, "test"), 0, "every continuation forked");
            final List<JsonObject> res = readAll(pout, "results");
            Assert.assertEquals(res.size(), 2);
            for (JsonObject r : res) {
                Assert.assertTrue(r.get("forked").getAsBoolean(), String.valueOf(r));
                Assert.assertEquals(r.get("forced").getAsString(), "played", String.valueOf(r));
                Assert.assertTrue(r.get("void").isJsonNull(), String.valueOf(r));
                Assert.assertTrue(r.get("turns").getAsInt() >= q0.get("turn").getAsInt());
                Assert.assertEquals(r.getAsJsonObject("fork").get("tapes_detached").getAsInt(), 2);
                Assert.assertEquals(r.getAsJsonObject("fork").get("dv").getAsDouble(), 0.0, 1e-6);
                System.err.println("[i2h-test] play-from " + r);
            }
            Assert.assertEquals(res.get(0).get("action").getAsInt(), g0);
            Assert.assertEquals(res.get(1).get("action").getAsInt(), alt);
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
        }
    }
}
