package forge.bench;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlWire;

/**
 * Lane cm-choice-search-1009: S-t, the S1 search with its candidates' choice asks expanded into macro candidates (spec
 * key {@code choices} > 0; off by default). Spec parsing; whole games with S-t on against a stand-in search service
 * whose leaves differ (so the search departs), whose tapes replay WITHOUT the search to the same digest (the tape is the
 * game, as S1's G3); and an S1 spec whose JSON and per-game summary carry nothing of S-t. Plays real Forge games: run
 * inside a broker test lease, cwd = forge-gui with res/; fixtures as {@link RlActorBenchTest}.
 */
public class RlChoiceSearchTest {
    static final String SC = "{\"worlds\":2,\"breadth\":3,\"horizon\":1,\"threads\":1,\"departZ\":0,\"leaf\":\"value\","
            + "\"playout\":\"policy\",\"deadEtb\":\"on\",\"zeroX\":\"on\",\"crewNoop\":\"on\"";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static RlSearch.Config spec(final String server, final String extra) {
        return RlSearch.Config.parse(JsonParser.parseString(SC + ",\"server\":\"" + server + "\"" + extra + "}")
                .getAsJsonObject());
    }

    @Test
    public void specKeysParseAndAnS1SpecIsUnchanged() {
        final RlSearch.Config off = spec("127.0.0.1:1", "");
        Assert.assertEquals(off.choices, 0);
        Assert.assertEquals(off.choiceMaxProb, 1.0, "every ask with an alternative, however sure the policy is");
        Assert.assertEquals(off.choiceRank, "base");
        for (String k : new String[] {"choices", "choiceAlts", "choiceMaxProb", "choiceCap", "choiceFamilies", "choiceRank"}) {
            Assert.assertFalse(off.toJson().has(k), "an S1 spec's JSON carries no " + k);
        }
        final RlSearch.Config on = spec("127.0.0.1:1", ",\"choices\":2,\"choiceAlts\":3,\"choiceCap\":9,"
                + "\"choiceMaxProb\":0.8,\"choiceFamilies\":\"TARGETS,CARDS\",\"choiceRank\":\"joint\"");
        Assert.assertEquals(on.choices, 2);
        Assert.assertEquals(on.choiceAlts, 3);
        Assert.assertEquals(on.choiceCap, 9);
        Assert.assertEquals(on.choiceMaxProb, 0.8);
        Assert.assertEquals(on.toJson().get("choiceFamilies").getAsString(), "TARGETS,CARDS");
        Assert.assertEquals(on.toJson().get("choiceRank").getAsString(), "joint");
        Assert.assertThrows(IllegalArgumentException.class, () -> spec("127.0.0.1:1", ",\"choices\":1,\"choiceRank\":\"best\""));
        Assert.assertEquals(off.sameDepartures, 2, "the S1 read's loop guard");
        Assert.assertFalse(off.toJson().has("sameDepartures"));
        Assert.assertEquals(spec("127.0.0.1:1", ",\"sameDepartures\":8").toJson().get("sameDepartures").getAsInt(), 8);
        Assert.assertThrows(IllegalArgumentException.class, () -> spec("127.0.0.1:1", ",\"sameDepartures\":0"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(JsonParser.parseString(
                "{\"server\":\"127.0.0.1:1\",\"playout\":\"forge\",\"choices\":1}").getAsJsonObject()));
        Assert.assertThrows(IllegalArgumentException.class, () -> spec("127.0.0.1:1", ",\"choices\":1,"
                + "\"choiceFamilies\":\"PRIORITY\""));
        Assert.assertThrows(IllegalArgumentException.class, () -> spec("127.0.0.1:1", ",\"choices\":1,"
                + "\"choiceFamilies\":\"TARGET\""));
        Assert.assertThrows(IllegalArgumentException.class, () -> spec("127.0.0.1:1", ",\"choices\":-1"));
    }

    /** Leaf values that differ by observation (a hash of the payload), in [-1, 1]. */
    static double hashValue(final byte[] p) {
        long h = 1125899906842597L;
        for (byte b : p) {
            h = 31 * h + b;
        }
        h ^= h >>> 29;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 32;
        return ((h & 0xffff) / 32767.5) - 1.0;
    }

    static JsonObject replayGame(final JsonObject t) {
        final JsonObject g = new JsonObject();
        g.add("game_uid", t.get("game_uid"));
        g.add("seed", t.get("seed"));
        final JsonArray decks = new JsonArray(), shas = new JsonArray();
        for (JsonElement d : t.getAsJsonArray("decks")) {
            decks.add(d.getAsJsonObject().get("path"));
            shas.add(d.getAsJsonObject().get("sha"));
        }
        g.add("decks", decks);
        g.add("deck_sha", shas);
        g.add("controllers", t.get("controllers"));
        g.addProperty("priv", false);
        return g;
    }

    @Test
    public void stGamesExpandChoicesDepartAndReplayWithoutTheSearch() throws Exception {
        try (FakeSearchService svc = new FakeSearchService("0".repeat(64))) {
            svc.leafValue = RlChoiceSearchTest::hashValue;
            final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
            cfg.search = spec(svc.address(), ",\"choices\":2,\"choiceAlts\":2,\"choiceCap\":6");
            final int games = Integer.getInteger("rl.stGames", 3);
            int probeAsks = 0, macros = 0, macroDepartures = 0, liveHits = 0, playoutHits = 0;
            final List<JsonObject> tapes = new ArrayList<>();
            for (int i = 0; i < games; i++) {
                final RlActorBench.Played p = RlActorBench.play(cfg, "eval", RlActorBenchOpponentTest.evalGame(i,
                        i % 2 == 0 ? "rl:M" : "forge", i % 2 == 0 ? "forge" : "rl:M"),
                        new RlFeaturizer(RlActorBenchTest.index), RlActorBenchOpponentTest.endpoint("eval"), null, "test");
                Assert.assertNull(p.guardError);
                Assert.assertTrue(p.end.get("void").isJsonNull(), "game " + i + " void " + p.end.get("void"));
                final JsonObject st = p.end.getAsJsonObject("search").getAsJsonObject("st");
                Assert.assertNotNull(st, "an S-t game's summary carries its S-t counts");
                final JsonObject s1 = p.end.getAsJsonObject("search").getAsJsonObject("lookahead").getAsJsonObject("s1");
                Assert.assertEquals(s1.get("leafFallbacks").getAsInt(), 0);
                // every probe's SCORE is a frame the service takes (SINGLE): no connection is lost, no play-out fails
                Assert.assertEquals(s1.get("playoutSeatFailures").getAsInt(), 0, String.valueOf(s1.get("playoutLastFailure")));
                Assert.assertEquals(st.get("reconnects").getAsInt(), 0);
                probeAsks += st.get("probe_asks").getAsInt();
                macros += st.get("macros").getAsInt();
                macroDepartures += st.get("macro_departures").getAsInt();
                liveHits += st.getAsJsonObject("live").get("hit_exact").getAsInt()
                        + st.getAsJsonObject("live").get("hit_loose").getAsInt()
                        + st.getAsJsonObject("live").get("hit_ambiguous").getAsInt();
                playoutHits += st.getAsJsonObject("playout").get("hit_exact").getAsInt()
                        + st.getAsJsonObject("playout").get("hit_loose").getAsInt()
                        + st.getAsJsonObject("playout").get("hit_ambiguous").getAsInt();
                tapes.add(p.tape);
                System.err.println("[st-test] game " + i + " " + st);
            }
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
            Assert.assertTrue(probeAsks > 0, "the probes recorded no choice ask");
            Assert.assertTrue(macros > 0, "no macro candidate was searched");
            Assert.assertTrue(playoutHits > 0, "no macro play-out answered from its schedule");
            Assert.assertTrue(macroDepartures > 0, "no macro departure (departZ 0, leaves that differ)");
            System.err.println("[st-test] probe asks " + probeAsks + ", macros " + macros + ", macro departures "
                    + macroDepartures + ", live schedule hits " + liveHits + ", play-out schedule hits " + playoutHits);
            // the tape is the game: each replays to the same digest with no search and no service (S1's G3)
            for (JsonObject t : tapes) {
                final RlActorBench.Played r = RlActorBench.play(RlActorBenchTest.cfg("eval"), "eval", replayGame(t),
                        new RlFeaturizer(RlActorBenchTest.index),
                        RlActorBench.tapeEndpoint(t, RlWire.parseUid(t.get("game_uid").getAsString())), null, "test");
                Assert.assertNull(r.guardError);
                Assert.assertNull(r.seat.fatal, "replay error");
                Assert.assertEquals(r.end.get("digest"), t.get("digest"), "S-t replay digest of " + t.get("game_uid"));
            }
        }
    }

    @Test
    public void anS1SpecPlaysWithoutAnySTrace() throws Exception {
        try (FakeSearchService svc = new FakeSearchService("0".repeat(64))) {
            svc.leafValue = RlChoiceSearchTest::hashValue;
            final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
            cfg.search = spec(svc.address(), "");
            cfg.search.decisionLog = null;
            final RlActorBench.Played p = RlActorBench.play(cfg, "eval", RlActorBenchOpponentTest.evalGame(0, "rl:M",
                    "forge"), new RlFeaturizer(RlActorBenchTest.index), RlActorBenchOpponentTest.endpoint("eval"), null,
                    "test");
            Assert.assertTrue(p.end.get("void").isJsonNull(), String.valueOf(p.end.get("void")));
            final JsonObject s = p.end.getAsJsonObject("search");
            Assert.assertFalse(s.has("st"), "an S1 game's summary has no S-t block");
            Assert.assertFalse(s.getAsJsonObject("spec").has("choices"));
            Assert.assertFalse(s.getAsJsonObject("lookahead").getAsJsonObject("s1").has("macros"));
            Assert.assertTrue(s.get("searched").getAsInt() > 0, "the S1 search ran");
        }
    }
}
