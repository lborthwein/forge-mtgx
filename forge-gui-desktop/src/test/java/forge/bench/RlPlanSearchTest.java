package forge.bench;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSearch;

/**
 * Lane search-bones-1015: the S-c + ForcedWin baseline (spec key {@code forcedWin}) and S-b, turn-plans over S-c (spec
 * key {@code plans}); both off by default. Spec parsing (an S-c spec's JSON carries neither key), and whole games with
 * each on against a stand-in search service whose leaves differ, so the searches depart. Plays real Forge games: run
 * inside a broker test lease, cwd = forge-gui with res/; fixtures as {@link RlActorBenchTest}.
 */
public class RlPlanSearchTest {

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    @Test
    public void specKeysParseAndAnScSpecIsUnchanged() {
        final RlSearch.Config off = RlChoiceSearchTest.spec("127.0.0.1:1", "");
        Assert.assertNull(off.forcedWin);
        Assert.assertNull(off.plans);
        Assert.assertFalse(off.toJson().has("forcedWin"), "an S-c spec's JSON carries no forcedWin");
        Assert.assertFalse(off.toJson().has("plans"), "an S-c spec's JSON carries no plans");
        final RlSearch.Config fw = RlChoiceSearchTest.spec("127.0.0.1:1", ",\"forcedWin\":{\"worlds\":\"4\"}");
        Assert.assertEquals(fw.forcedWin.get("worlds").getAsString(), "4");
        Assert.assertTrue(fw.toJson().has("forcedWin"));
        final RlSearch.Config sb = RlChoiceSearchTest.spec("127.0.0.1:1", ",\"plans\":{\"samples\":3,\"maxLines\":1}");
        Assert.assertEquals(sb.plans.get("samples").getAsInt(), 3);
        Assert.assertTrue(sb.toJson().has("plans"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlChoiceSearchTest.spec("127.0.0.1:1",
                ",\"plans\":{\"sample\":3}"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlChoiceSearchTest.spec("127.0.0.1:1",
                ",\"plans\":{},\"forcedWin\":{}"));
        // (d): S-t's choice expansion of the given candidates is allowed with S-b (discard choices priced by the check)
        Assert.assertEquals(RlChoiceSearchTest.spec("127.0.0.1:1", ",\"plans\":{\"opp\":true},\"choices\":2").choices, 2);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(JsonParser.parseString(
                "{\"server\":\"127.0.0.1:1\",\"playout\":\"forge\",\"plans\":{}}").getAsJsonObject()));
    }

    private static JsonObject play(final RlActorBench.Cfg cfg, final int i) throws Exception {
        final RlActorBench.Played p = RlActorBench.play(cfg, "eval", RlActorBenchOpponentTest.evalGame(i,
                i % 2 == 0 ? "rl:M" : "forge", i % 2 == 0 ? "forge" : "rl:M"),
                new RlFeaturizer(RlActorBenchTest.index), RlActorBenchOpponentTest.endpoint("eval"), null, "test");
        Assert.assertNull(p.guardError);
        Assert.assertTrue(p.end.get("void").isJsonNull(), "game " + i + " void " + p.end.get("void"));
        Assert.assertNull(p.seat.fatal, "game " + i + " fatal");
        return p.end.getAsJsonObject("search");
    }

    @Test
    public void forcedWinGamesPlayWithTheSolverInFront() throws Exception {
        try (FakeSearchService svc = new FakeSearchService("0".repeat(64))) {
            svc.leafValue = RlChoiceSearchTest::hashValue;
            final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
            cfg.search = RlChoiceSearchTest.spec(svc.address(), ",\"forcedWin\":{}");
            int calls = 0;
            for (int i = 0; i < Integer.getInteger("rl.sbGames", 2); i++) {
                final JsonObject s = play(cfg, i);
                Assert.assertTrue(s.has("fw"), "a forcedWin game's summary has the solver's block");
                for (String k : s.getAsJsonObject("fw").keySet()) {
                    calls += s.getAsJsonObject("fw").getAsJsonObject(k).get("calls").getAsInt();
                }
                Assert.assertTrue(s.get("searched").getAsInt() > 0, "S-c still searched");
                System.err.println("[sb-test] fw game " + i + " " + s.get("fw"));
            }
            Assert.assertTrue(calls > 0, "the solver was asked at the seat's priorities");
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
        }
    }

    @Test
    public void sbGamesProposeSamplePlansAndPlayThem() throws Exception {
        try (FakeSearchService svc = new FakeSearchService("0".repeat(64))) {
            svc.leafValue = RlChoiceSearchTest::hashValue;
            final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
            cfg.search = RlChoiceSearchTest.spec(svc.address(), ",\"plans\":{\"samples\":3}");
            int points = 0, sampled = 0, departures = 0;
            for (int i = 0; i < Integer.getInteger("rl.sbGames", 2); i++) {
                final JsonObject s = play(cfg, i);
                Assert.assertTrue(s.has("plans"), "an S-b game's summary has the plan block");
                final JsonObject s1 = s.getAsJsonObject("lookahead").getAsJsonObject("s1");
                Assert.assertEquals(s1.get("leafFallbacks").getAsInt(), 0);
                Assert.assertEquals(s1.get("playoutSeatFailures").getAsInt(), 0, String.valueOf(s1.get("playoutLastFailure")));
                for (String k : s.getAsJsonObject("plans").keySet()) {
                    final JsonObject pl = s.getAsJsonObject("plans").getAsJsonObject(k);
                    points += pl.get("planPoints").getAsInt();
                    sampled += pl.get("sampledPlans").getAsInt();
                    departures += pl.get("planDepartures").getAsInt();
                    System.err.println("[sb-test] S-b game " + i + " " + pl);
                }
            }
            Assert.assertTrue(points > 0, "no plan point");
            Assert.assertTrue(sampled > 0, "no sampled plan was searched");
            System.err.println("[sb-test] plan points " + points + ", sampled plans " + sampled + ", plan departures "
                    + departures);
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
        }
    }

    @Test
    public void sbOppGamesExportTheOpponentCheckCounters() throws Exception {
        // R5 (search-bones-1015 / r5-prereq-1018): with plans.opp the game summary's lookahead block carries the
        // opponent check's counters (oppChecks, oppLosses); without plans.opp the block has neither key
        try (FakeSearchService svc = new FakeSearchService("0".repeat(64))) {
            svc.leafValue = RlChoiceSearchTest::hashValue;
            final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
            // horizon 2 (the live spec's): a play-out from our turn reaches their main phase, where the check runs
            cfg.search = RlChoiceSearchTest.spec(svc.address(), ",\"horizon\":2,\"plans\":{\"samples\":3,\"opp\":true}");
            long checks = 0;
            for (int i = 0; i < Integer.getInteger("rl.sbGames", 2); i++) {
                final JsonObject la = play(cfg, i).getAsJsonObject("lookahead");
                if (la.has("oppChecks")) {
                    final long c = la.get("oppChecks").getAsLong(), l = la.get("oppLosses").getAsLong();
                    Assert.assertTrue(l >= 0 && l <= c, "oppLosses " + l + " > oppChecks " + c);
                    checks += c;
                }
            }
            Assert.assertTrue(checks > 0, "no opponent check counted in an S-b3 game");
            cfg.search = RlChoiceSearchTest.spec(svc.address(), ",\"plans\":{\"samples\":3}");
            Assert.assertFalse(play(cfg, 0).getAsJsonObject("lookahead").has("oppChecks"), "S-b2 game has oppChecks");
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
        }
    }
}
