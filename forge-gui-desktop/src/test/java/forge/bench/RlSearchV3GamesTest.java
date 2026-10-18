package forge.bench;

import java.util.ArrayList;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.bench.rl.FakeRlServer;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSchemaV3;
import forge.bench.rl.RlSearch;
import forge.bench.rl.RlWire;

/**
 * Lane r4-payment-1015 (R5): the look-ahead (S-c) on an obs-v3 seat, with and without the MANA_SOURCE ask, in whole
 * games against the stand-in search service (obs-v3: v2's frames, v3's sha). Plays real Forge games: run inside a broker
 * test lease, cwd = forge-gui with res/; fixtures as {@link RlActorBenchTest}.
 * <ul>
 *   <li>Option off: S-c on obs-v3 plays S-c's obs-v2 games byte for byte (equal digests), with no payment ask.</li>
 *   <li>Option on: no guard error, no void, no play-out failure; the searched seat is asked payments (never inside a
 *       play-out: the service sees no MANA_SOURCE frame on a play-out connection), each logged as a {@code mana_source}
 *       row with the policy's distribution; the seat knowledge keeps the v2 extras (seen ids); the tapes replay
 *       without the search to their digests.</li>
 * </ul>
 */
public class RlSearchV3GamesTest {

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static FakeSearchService service(final int obs) throws Exception {
        final FakeSearchService svc = new FakeSearchService("0".repeat(64), obs);
        svc.leafValue = RlChoiceSearchTest::hashValue;
        svc.checker = new FakeRlServer(0, "eval", 7, null, java.util.Collections.emptyList());
        return svc;
    }

    static List<RlActorBench.Played> play(final FakeSearchService svc, final int obs, final boolean mana, final int games)
            throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
        cfg.obsSchema = obs;
        cfg.manaSource = mana;
        cfg.search = RlSearchV2GamesTest.spec(svc.address(), "");
        // the decision log on (its rows, incl. the mana_source rows, are kept in memory too)
        final java.io.File log = java.io.File.createTempFile("search-v3-decisions-", ".jsonl");
        log.deleteOnExit();
        cfg.search.decisionLog = log.getPath();
        final List<RlActorBench.Played> out = new ArrayList<>();
        for (int i = 0; i < games; i++) {
            final RlActorBenchTest.LocalEndpoint ep = RlSearchV2GamesTest.endpoint(2);   // obs-v3 frames are layout 2
            final RlActorBench.Played p = RlActorBench.play(cfg, "eval", RlActorBenchOpponentTest.evalGame(i,
                    i % 2 == 0 ? "rl:M" : "forge", i % 2 == 0 ? "forge" : "rl:M"),
                    new RlFeaturizer(RlActorBenchTest.index), ep, null, "test");
            Assert.assertNull(p.guardError, "guard (obs " + obs + ", mana " + mana + ")");
            Assert.assertNull(p.seat.fatal, "fatal");
            Assert.assertTrue(p.end.get("void").isJsonNull(), "game " + i + " void " + p.end.get("void"));
            Assert.assertTrue(p.knowledge.v2, "the seat knowledge keeps the v2 extras under obs-v" + obs);
            final JsonObject s = p.end.getAsJsonObject("search");
            Assert.assertEquals(s.get("obs").getAsInt(), obs, "the search ran obs-v" + obs);
            Assert.assertEquals(s.get("score_failures").getAsInt(), 0, "every SCORE answered");
            final JsonObject s1 = s.getAsJsonObject("lookahead").getAsJsonObject("s1");
            Assert.assertEquals(s1.get("leafFallbacks").getAsInt(), 0, "every LEAVES answered");
            Assert.assertEquals(s1.get("playoutSeatFailures").getAsInt(), 0, String.valueOf(s1.get("playoutLastFailure")));
            out.add(p);
        }
        return out;
    }

    static int manaSent(final RlActorBench.Played p) {
        final JsonObject c = p.tape.getAsJsonObject("census");
        return c.has("MANA_SOURCE") ? c.getAsJsonObject("MANA_SOURCE").get("sent").getAsInt() : 0;
    }

    @Test
    public void optionOffIsTheObsV2SearchGame() throws Exception {
        final int games = Integer.getInteger("rl.v3SearchGames", 3);
        final List<RlActorBench.Played> v2, v3;
        try (FakeSearchService svc = service(2)) {
            v2 = play(svc, 2, false, games);
        }
        try (FakeSearchService svc = service(3)) {
            v3 = play(svc, 3, false, games);
            Assert.assertEquals(svc.refusedHellos.get(), 0L, "a HELLO of another schema");
            Assert.assertEquals(svc.badFrames.get(), 0L);
        }
        int searched = 0;
        for (int i = 0; i < games; i++) {
            Assert.assertEquals(v3.get(i).end.get("digest"), v2.get(i).end.get("digest"), "game " + i + ": v3 off = v2");
            Assert.assertEquals(manaSent(v3.get(i)), 0);
            Assert.assertEquals(v3.get(i).tape.get("schema_sha").getAsString(), RlSchemaV3.SCHEMA_SHA);
            Assert.assertFalse(v3.get(i).end.getAsJsonObject("search").has("mana_source_asks"));
            searched += v3.get(i).end.getAsJsonObject("search").get("searched").getAsInt();
        }
        Assert.assertTrue(searched > 0, "no searched decision");
    }

    @Test
    public void optionOnSearchesAndAsksTheRootOnly() throws Exception {
        final int games = Integer.getInteger("rl.v3SearchGames", 3);
        final List<RlActorBench.Played> on;
        final long playoutMana;
        try (FakeSearchService svc = service(3)) {
            final java.util.concurrent.atomic.AtomicLong pm = new java.util.concurrent.atomic.AtomicLong();
            svc.script = d -> {
                if (d.family == RlSchemaV3.F_MANA_SOURCE) {
                    pm.incrementAndGet();   // the search's own connections (play-outs) are never asked a payment
                }
                return null;
            };
            on = play(svc, 3, true, games);
            playoutMana = pm.get();
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
            Assert.assertEquals(svc.checker.badFrames.get(), 0L, String.valueOf(svc.checker.problems));
        }
        Assert.assertEquals(playoutMana, 0L, "a play-out seat was asked MANA_SOURCE");
        int asks = 0, logged = 0, searched = 0;
        for (RlActorBench.Played p : on) {
            final JsonObject s = p.end.getAsJsonObject("search");
            searched += s.get("searched").getAsInt();
            final int sent = manaSent(p);
            asks += sent;
            int rows = 0;
            for (JsonObject r : p.search.rows()) {
                if (!"mana_source".equals(r.has("kind") ? r.get("kind").getAsString() : "")) {
                    continue;
                }
                rows++;
                Assert.assertFalse(r.has("score_failed"), String.valueOf(r));
                final JsonArray pr = r.getAsJsonArray("probs");
                Assert.assertEquals(pr.size(), r.get("C").getAsInt(), "the label covers every candidate");
                double sum = 0;
                for (int i = 0; i < pr.size(); i++) {
                    sum += pr.get(i).getAsDouble();
                }
                Assert.assertEquals(sum, 1.0, 1e-3, "the label is a distribution");
            }
            // every ask of the searched seat (the RL seat: one per game) is the search's row; the summary counts them
            Assert.assertEquals(rows, sent, "one mana_source row per MANA_SOURCE ask");
            Assert.assertEquals(s.has("mana_source_asks") ? s.get("mana_source_asks").getAsInt() : 0, sent);
            logged += rows;
        }
        System.err.println("[search-v3] S-c obs-v3 + MANA_SOURCE: searched " + searched + ", payment asks " + asks
                + ", logged " + logged);
        Assert.assertTrue(searched > 0, "no searched decision");
        Assert.assertTrue(asks > 0, "no MANA_SOURCE ask in " + games + " games");
        // the tapes replay without the search (the payments are on the tape)
        for (RlActorBench.Played p : on) {
            final JsonObject g = RlChoiceSearchTest.replayGame(p.tape);
            g.addProperty("obs_schema", 3);
            g.addProperty("mana_source", true);
            final RlActorBench.Played r = RlActorBench.play(RlActorBenchTest.cfg("eval"), "eval", g,
                    new RlFeaturizer(RlActorBenchTest.index),
                    RlActorBench.tapeEndpoint(p.tape, RlWire.parseUid(p.tape.get("game_uid").getAsString())), null, "test");
            Assert.assertNull(r.guardError);
            Assert.assertNull(r.seat.fatal, "replay error");
            Assert.assertEquals(r.end.get("digest"), p.tape.get("digest"), "obs-v3 replay digest of " + p.tape.get("game_uid"));
        }
    }

    /** R5: S-b games (obs-v3, MANA_SOURCE on) with or without payment variants ({@code plans.pay}). */
    static List<RlActorBench.Played> playSb(final FakeSearchService svc, final boolean mana, final String plans,
            final int games) throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
        cfg.obsSchema = 3;
        cfg.manaSource = mana;
        cfg.search = RlSearchV2GamesTest.spec(svc.address(), ",\"plans\":" + plans);
        final java.io.File log = java.io.File.createTempFile("search-v3-pay-", ".jsonl");
        log.deleteOnExit();
        cfg.search.decisionLog = log.getPath();
        final List<RlActorBench.Played> out = new ArrayList<>();
        for (int i = 0; i < games; i++) {
            final RlActorBench.Played p = RlActorBench.play(cfg, "eval", RlActorBenchOpponentTest.evalGame(i,
                    i % 2 == 0 ? "rl:M" : "forge", i % 2 == 0 ? "forge" : "rl:M"),
                    new RlFeaturizer(RlActorBenchTest.index), RlSearchV2GamesTest.endpoint(2), null, "test");
            Assert.assertNull(p.guardError, "guard");
            Assert.assertNull(p.seat.fatal, "fatal");
            Assert.assertTrue(p.end.get("void").isJsonNull(), "game " + i + " void " + p.end.get("void"));
            final JsonObject s1 = p.end.getAsJsonObject("search").getAsJsonObject("lookahead").getAsJsonObject("s1");
            Assert.assertEquals(s1.get("playoutSeatFailures").getAsInt(), 0, String.valueOf(s1.get("playoutLastFailure")));
            out.add(p);
        }
        return out;
    }

    /**
     * R5: payment variants. With MANA_SOURCE off the pay key changes nothing (the S-b games are the games without it);
     * on, variants are searched, an adopted one answers its ask (a {@code teacher: plan} row whose answer is its target),
     * and the tapes replay without the search.
     */
    @Test
    public void payVariantsAreSearchedAndAdoptedOnesReplay() throws Exception {
        final int games = Integer.getInteger("rl.v3PayGames", 6);
        final String sb = "{\"samples\":1,\"solver\":false}";
        final String pay = "{\"samples\":1,\"solver\":false,\"pay\":{\"variants\":4}}";
        final List<RlActorBench.Played> offA, offB, on;
        try (FakeSearchService svc = service(3)) {
            svc.leafValue = RlChoiceSearchTest::hashValue;
            offA = playSb(svc, false, sb, games);
        }
        try (FakeSearchService svc = service(3)) {
            offB = playSb(svc, false, pay, games);
        }
        for (int i = 0; i < games; i++) {
            Assert.assertEquals(offB.get(i).end.get("digest"), offA.get(i).end.get("digest"),
                    "game " + i + ": the pay key without MANA_SOURCE asks changes nothing");
        }
        try (FakeSearchService svc = service(3)) {
            on = playSb(svc, true, pay, games);
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
        }
        int variants = 0, departures = 0, teacher = 0, overrides = 0;
        for (RlActorBench.Played p : on) {
            final JsonObject pl = p.end.getAsJsonObject("search").getAsJsonObject("plans");
            for (String k : pl.keySet()) {
                final JsonObject py = pl.getAsJsonObject(k).getAsJsonObject("pay");
                Assert.assertNotNull(py, "the pay block");
                variants += py.get("variants").getAsInt();
                System.err.println("[search-v3-pay] seat " + k + " " + py);
                departures += py.get("departures").getAsInt();
            }
            overrides += p.seat.payOverrides;
            for (JsonObject r : p.search.rows()) {
                if ("mana_source".equals(r.has("kind") ? r.get("kind").getAsString() : "") && r.has("teacher")) {
                    teacher++;
                    Assert.assertEquals(r.get("teacher").getAsString(), "plan");
                    Assert.assertEquals(r.get("answer").getAsInt(), r.get("target").getAsInt(), String.valueOf(r));
                    Assert.assertTrue(r.get("target").getAsInt() > 0, "a variant's target is a source, not AUTO");
                    Assert.assertTrue(r.has("proven") && r.has("variant"), String.valueOf(r));
                }
            }
        }
        System.err.println("[search-v3-pay] variants " + variants + ", adopted " + departures + ", teacher rows " + teacher
                + ", live overrides " + overrides);
        Assert.assertTrue(variants > 0, "no payment variant was searched");
        Assert.assertTrue(teacher <= departures, "a teacher row without an adopted variant");
        for (RlActorBench.Played p : on) {
            final JsonObject g = RlChoiceSearchTest.replayGame(p.tape);
            g.addProperty("obs_schema", 3);
            g.addProperty("mana_source", true);
            final RlActorBench.Played r = RlActorBench.play(RlActorBenchTest.cfg("eval"), "eval", g,
                    new RlFeaturizer(RlActorBenchTest.index),
                    RlActorBench.tapeEndpoint(p.tape, RlWire.parseUid(p.tape.get("game_uid").getAsString())), null, "test");
            Assert.assertNull(r.seat.fatal, "replay error");
            Assert.assertEquals(r.end.get("digest"), p.tape.get("digest"), "replay of an S-b pay game");
        }
    }

    @Test
    public void payKeyParses() {
        Assert.assertNull(forge.bench.rl.PlanSearch.Spec.parse(com.google.gson.JsonParser.parseString("{}")
                .getAsJsonObject()).pay);
        final forge.bench.rl.PlanSearch.Spec s = forge.bench.rl.PlanSearch.Spec.parse(com.google.gson.JsonParser
                .parseString("{\"pay\":{\"variants\":2,\"when\":\"lines\"}}").getAsJsonObject());
        Assert.assertEquals(s.pay.variants, 2);
        Assert.assertEquals(s.pay.when, "lines");
        Assert.assertFalse(s.pay.lineProbe, "the line probe (measurement) is off by default");
        Assert.assertTrue(forge.bench.rl.PlanSearch.Spec.parse(com.google.gson.JsonParser.parseString(
                "{\"pay\":{\"lineProbe\":true}}").getAsJsonObject()).pay.lineProbe);
        Assert.assertThrows(IllegalArgumentException.class, () -> forge.bench.rl.PlanSearch.Spec.parse(
                com.google.gson.JsonParser.parseString("{\"pay\":{\"variants\":0}}").getAsJsonObject()));
        Assert.assertThrows(IllegalArgumentException.class, () -> forge.bench.rl.PlanSearch.Spec.parse(
                com.google.gson.JsonParser.parseString("{\"pay\":{\"when\":\"always\"}}").getAsJsonObject()));
    }

    @Test
    public void obsV3WithTheSearchIsNoLongerRefusedAtConfig() {
        final JsonObject c = com.google.gson.JsonParser.parseString("{\"mode\":\"eval\",\"obsSchema\":3,"
                + "\"manaSource\":true,\"search\":" + RlSearchV2GamesTest.SC + ",\"server\":\"127.0.0.1:1\",\"obs\":3}}")
                .getAsJsonObject();
        final RlActorBench.Cfg p = RlActorBench.parse(c);
        Assert.assertEquals(p.search.obs, 3);
        Assert.assertTrue(p.manaSource);
        c.getAsJsonObject("search").addProperty("obs", 2);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(c));
    }
}
