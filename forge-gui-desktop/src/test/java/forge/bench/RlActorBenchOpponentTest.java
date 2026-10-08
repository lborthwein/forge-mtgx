package forge.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import forge.ai.simulation.LobbyPlayerLookahead;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.rl.CardIndex;
import forge.bench.rl.FakeRlServer;
import forge.bench.rl.RlFeaturizer;
import forge.bench.rl.RlSeat;
import forge.bench.rl.RlWire;

/**
 * Lane gen-check-1007: RlActorBench's opponent seats -- Forge AI under a shipped profile ({@code forge:<Profile>}) and
 * the K8 look-ahead seat ({@code lookahead:K8}, ICR B7-k8-opponent-seat). Plays real Forge games (run inside a broker
 * test lease, cwd = forge-gui with res/); fixtures as {@link RlActorBenchTest}.
 */
public class RlActorBenchOpponentTest {

    /** The live K8 spec's search keys and seat options, with no wall budget (a bench decision never depends on time). */
    static final String LIVE = "{\"worlds\":8,\"breadth\":4,\"horizonTurns\":2,\"threads\":1,\"reuse\":true,"
            + "\"departZ\":1.645,\"deadEtb\":\"on\",\"zeroX\":\"on\",\"crewNoop\":\"on\",\"aiFixes0928\":\"on\","
            + "\"fairNaming\":\"on\"}";
    /** A cheap spec for game tests. */
    static final String CHEAP = "{\"worlds\":1,\"breadth\":2,\"horizonTurns\":1,\"threads\":1,\"departZ\":1.645,"
            + "\"aiFixes0928\":\"on\",\"fairNaming\":\"on\"}";

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlActorBenchTest.root == null) {
            RlActorBenchTest.setUp();
        }
    }

    static RlActorBenchTest.LocalEndpoint endpoint(final String mode) throws Exception {
        return new RlActorBenchTest.LocalEndpoint(new FakeRlServer(0, mode, 7, null, Collections.emptyList()));
    }

    /** An eval-mode GAME on the fixture's two EVAL decks. */
    static JsonObject evalGame(final int i, final String c0, final String c1) throws Exception {
        final JsonObject g = RlActorBenchTest.game(i, c0, c1, false);
        for (int s = 0; s < 2; s++) {
            final Path ev = RlActorBenchTest.evalBank.resolve("decks/ev" + s + ".dck");
            g.getAsJsonArray("decks").set(s, new JsonPrimitive(ev.toString()));
            g.getAsJsonArray("deck_sha").set(s, new JsonPrimitive(CardIndex.sha256(Files.readAllBytes(ev))));
        }
        return g;
    }

    @Test
    public void controllersParse() {
        Assert.assertEquals(RlSeat.roleOf("forge:Reckless"), RlSeat.Role.FORGE);
        Assert.assertEquals(RlSeat.roleOf("lookahead:K8"), RlSeat.Role.FORGE);
        Assert.assertEquals(RlSeat.roleOf("forge"), RlSeat.Role.FORGE);
        for (String bad : new String[] {"forge:", "lookahead:K1", "lookahead", "Forge:Reckless"}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> RlSeat.roleOf(bad));
        }
        Assert.assertEquals(RlActorBench.aiProfileOf("forge:Cautious"), "Cautious");
        Assert.assertEquals(RlActorBench.aiProfileOf("forge"), "Default");
        Assert.assertEquals(RlActorBench.aiProfileOf("rl:eval"), "Default");
        Assert.assertEquals(RlActorBench.aiProfileOf("lookahead:K8"), "Default");
    }

    @Test
    public void k8SpecParses() {
        final JsonObject live = JsonParser.parseString(LIVE).getAsJsonObject();
        final LookaheadSearch.Config c = RlActorBench.k8Config(live, 42L);
        Assert.assertEquals(c.worlds, 8);
        Assert.assertEquals(c.breadth, 4);
        Assert.assertEquals(c.horizonTurns, 2);
        Assert.assertEquals(c.threads, 1);
        Assert.assertTrue(c.reuse);
        Assert.assertEquals(c.departZ, 1.645);
        Assert.assertEquals(c.deadEtb, forge.ai.AiFixes.Mode.ON);
        Assert.assertEquals(c.zeroX, forge.ai.AiFixes.Mode.ON);
        Assert.assertEquals(c.crewNoop, forge.ai.AiFixes.Mode.ON);
        Assert.assertEquals(c.copyEot, forge.ai.AiFixes.Mode.OFF);
        Assert.assertEquals(c.departMedian, forge.ai.AiFixes.Mode.OFF);
        Assert.assertEquals(c.budgetMs, 0L);
        Assert.assertEquals(c.margin, 0.0);
        Assert.assertEquals(c.seed, 42L);
        Assert.assertEquals(RlActorBench.k8Mode(live, "aiFixes0928"), forge.ai.AiFixes.Mode.ON);
        // LookaheadBench's defaults when a key is absent
        final LookaheadSearch.Config d = RlActorBench.k8Config(new JsonObject(), 0L);
        Assert.assertEquals(d.worlds, 1);
        Assert.assertEquals(d.breadth, 4);
        Assert.assertEquals(d.horizonTurns, 2);
        Assert.assertEquals(d.departZ, 0.0);
        Assert.assertFalse(d.reuse);
        // an unknown key or a bad option value refuses the spec (and the actor config at parse time)
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlActorBench.k8Config(JsonParser.parseString("{\"worldz\":8}").getAsJsonObject(), 0L));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlActorBench.k8Config(JsonParser.parseString("{\"deadEtb\":\"maybe\"}").getAsJsonObject(), 0L));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlActorBench.k8Config(JsonParser.parseString("{\"fairNaming\":\"x\"}").getAsJsonObject(), 0L));
        final JsonObject cfg = JsonParser.parseString("{\"mode\":\"eval\",\"k8\":" + LIVE + "}").getAsJsonObject();
        Assert.assertEquals(RlActorBench.parse(cfg).k8, live);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(
                JsonParser.parseString("{\"mode\":\"eval\",\"k8\":{\"probe\":true}}").getAsJsonObject()));
    }

    @Test
    public void profileSeatPlaysTheNamedProfileAndForgeDefaultIsForge() throws Exception {
        for (int i = 0; i < 2; i++) {
            final RlActorBench.Played plain = RlActorBench.play(RlActorBenchTest.cfg("train"), "train",
                    RlActorBenchTest.game(i, "rl:M", "forge", false), new RlFeaturizer(RlActorBenchTest.index),
                    endpoint("train"), null, "test");
            final RlActorBench.Played named = RlActorBench.play(RlActorBenchTest.cfg("train"), "train",
                    RlActorBenchTest.game(i, "rl:M", "forge:Default", false), new RlFeaturizer(RlActorBenchTest.index),
                    endpoint("train"), null, "test");
            Assert.assertNull(plain.guardError);
            Assert.assertNull(named.guardError);
            // do-no-harm: the explicit Default name plays exactly as "forge"
            Assert.assertEquals(named.end.get("digest"), plain.end.get("digest"), "forge:Default vs forge, game " + i);
            Assert.assertEquals(named.lobbies[1].getAiProfile(), "Default");
        }
        final RlActorBench.Played r = RlActorBench.play(RlActorBenchTest.cfg("train"), "train",
                RlActorBenchTest.game(0, "forge:Reckless", "rl:M", false), new RlFeaturizer(RlActorBenchTest.index),
                endpoint("train"), null, "test");
        Assert.assertNull(r.guardError);
        Assert.assertEquals(r.lobbies[0].getAiProfile(), "Reckless");
        Assert.assertEquals(r.lobbies[1].getAiProfile(), "Default");
        Assert.assertTrue(r.end.get("void").isJsonNull(), String.valueOf(r.end.get("void")));
        Assert.assertEquals(r.tape.getAsJsonArray("controllers").get(0).getAsString(), "forge:Reckless");
        Assert.assertFalse(r.tape.has("k8"));
    }

    @Test
    public void misuseIsRefusedBeforeAnyGame() throws Exception {
        final RlActorBench.Played p = RlActorBench.play(RlActorBenchTest.cfg("train"), "train",
                RlActorBenchTest.game(0, "rl:M", "forge:Nope", false), new RlFeaturizer(RlActorBenchTest.index),
                endpoint("train"), null, "test");
        Assert.assertNotNull(p.guardError);
        Assert.assertTrue(p.guardError.contains("not shipped"), p.guardError);
        Assert.assertNull(p.end);
        // the K8 seat: eval mode only, and only with a spec
        final RlActorBench.Played t = RlActorBench.play(RlActorBenchTest.cfg("train"), "train",
                RlActorBenchTest.game(0, "rl:M", "lookahead:K8", false), new RlFeaturizer(RlActorBenchTest.index),
                endpoint("train"), null, "test");
        Assert.assertNotNull(t.guardError);
        Assert.assertNull(t.end);
        final RlActorBench.Played e = RlActorBench.play(RlActorBenchTest.cfg("eval"), "eval",
                evalGame(0, "rl:M", "lookahead:K8"), new RlFeaturizer(RlActorBenchTest.index), endpoint("eval"), null,
                "test");
        Assert.assertNotNull(e.guardError);
        Assert.assertTrue(e.guardError.contains("k8 spec"), e.guardError);
        Assert.assertNull(e.end);
        final RlActorBench.Played rec = RlActorBench.play(RlActorBenchTest.cfg("record"), "record",
                RlActorBenchTest.game(0, "record", "forge:Reckless", false), new RlFeaturizer(RlActorBenchTest.index),
                endpoint("record"), null, "test");
        Assert.assertNotNull(rec.guardError);
    }

    @Test
    public void k8SeatPlaysRecordsItsSpecAndReplays() throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
        cfg.k8 = JsonParser.parseString(CHEAP).getAsJsonObject();
        for (int k = 0; k < 2; k++) {
            final String[] ctl = k == 0 ? new String[] {"rl:M", "lookahead:K8"} : new String[] {"lookahead:K8", "rl:M"};
            final RlActorBench.Played p = RlActorBench.play(cfg, "eval", evalGame(k, ctl[0], ctl[1]),
                    new RlFeaturizer(RlActorBenchTest.index), endpoint("eval"), null, "test");
            Assert.assertNull(p.guardError);
            Assert.assertTrue(p.end.get("void").isJsonNull(), String.valueOf(p.end.get("void")));
            final int ks = k == 0 ? 1 : 0;
            Assert.assertTrue(p.lobbies[ks] instanceof LobbyPlayerLookahead);
            Assert.assertEquals(p.lobbies[ks].getAiFixes0928(), forge.ai.AiFixes.Mode.ON);
            Assert.assertEquals(p.lobbies[ks].getFairNaming(), forge.ai.AiFixes.Mode.ON);
            final JsonObject os = p.end.getAsJsonObject("opp_search");
            Assert.assertEquals(os.get("seat").getAsInt(), ks);
            Assert.assertEquals(os.getAsJsonObject("config").get("worlds").getAsInt(), 1);
            Assert.assertTrue(os.get("decisions").getAsInt() > 0, os.toString());
            Assert.assertEquals(p.tape.getAsJsonObject("k8"), cfg.k8);
            // a decided game names its winner (the K8 seat is not a bridge)
            final JsonArray res = p.end.getAsJsonArray("result");
            if (!"draw".equals(p.end.get("reason").getAsString())) {
                Assert.assertEquals(Math.abs(res.get(0).getAsInt()) + Math.abs(res.get(1).getAsInt()), 2, res.toString());
            }
            // replay from the tape with no k8 in the actor config: the tape's spec, equal digest
            final JsonObject t = p.tape;
            final JsonObject g = new JsonObject();
            g.add("game_uid", t.get("game_uid"));
            g.add("seed", t.get("seed"));
            final JsonArray decks = new JsonArray(), shas = new JsonArray();
            for (com.google.gson.JsonElement d : t.getAsJsonArray("decks")) {
                decks.add(d.getAsJsonObject().get("path"));
                shas.add(d.getAsJsonObject().get("sha"));
            }
            g.add("decks", decks);
            g.add("deck_sha", shas);
            g.add("controllers", t.get("controllers"));
            g.add("k8", t.get("k8"));
            g.addProperty("priv", false);
            final RlActorBench.Played r = RlActorBench.play(RlActorBenchTest.cfg("eval"), "eval", g,
                    new RlFeaturizer(RlActorBenchTest.index),
                    RlActorBench.tapeEndpoint(t, RlWire.parseUid(t.get("game_uid").getAsString())), null, "test");
            Assert.assertNull(r.guardError);
            Assert.assertEquals(r.end.get("digest"), p.end.get("digest"), "K8 replay digest, game " + k);
            // k8-determinism-1008: the LookaheadBench digest is recorded and replays too; no scope was lost
            Assert.assertTrue(p.end.has("digest_lb") && p.tape.has("digest_lb"), p.end.toString());
            Assert.assertEquals(r.end.get("digest_lb"), p.end.get("digest_lb"), "K8 replay LookaheadBench digest, game " + k);
            Assert.assertFalse(p.end.has("scope_lost"), "game " + k + " lost " + p.end.get("scope_lost"));
            Assert.assertFalse(r.end.has("scope_lost"), "replay " + k + " lost " + r.end.get("scope_lost"));
        }
    }

    /**
     * Lane k8-determinism-1008: RlActorBench (and RlSimBench) share one parsed Deck per path between game threads. A
     * Deck loads its sections lazily and without a lock, so games that copied a not-yet-loaded shared Deck at the same
     * moment could get a partial deck (gen-check-1007 G2/G3: two Default-vs-Default games "lost to their library on turn
     * 1"). The cache now loads the deck before publishing it: every concurrent first use sees the whole deck.
     */
    @Test
    public void sharedDeckIsWholeForConcurrentFirstUse() throws Exception {
        for (String name : new String[] {"ev0.dck", "ev1.dck"}) {
            final String path = RlActorBenchTest.evalBank.resolve("decks/" + name).toString();
            final int expected = new forge.game.player.RegisteredPlayer(
                    forge.deck.io.DeckSerializer.fromFile(new java.io.File(path))).getDeck().getMain().countAll();
            Assert.assertTrue(expected >= 40, name + " has " + expected);
            for (int round = 0; round < 25; round++) {
                for (java.util.Map<String, forge.deck.Deck> cache : java.util.Arrays.asList(RlActorBench.DECKS,
                        RlSimBench.DECKS)) {
                    cache.remove(path);
                    final int n = 8;
                    final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                    final int[] got = new int[n];
                    final java.util.List<Thread> ts = new java.util.ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        final int ii = i;
                        final Thread t = new Thread(() -> {
                            try {
                                go.await();
                                final forge.deck.Deck d = cache == RlActorBench.DECKS ? RlActorBench.deck(path)
                                        : RlSimBench.deck(path);
                                got[ii] = new forge.game.player.RegisteredPlayer(d).getDeck().getMain().countAll();
                            } catch (Throwable e) {
                                got[ii] = -1;
                            }
                        });
                        ts.add(t);
                        t.start();
                    }
                    go.countDown();
                    for (Thread t : ts) {
                        t.join();
                    }
                    for (int i = 0; i < n; i++) {
                        Assert.assertEquals(got[i], expected, name + " round " + round + " thread " + i);
                    }
                }
            }
        }
    }

    /**
     * Lane k8-determinism-1008: a game with a K8 seat is a pure function of its row. The look-ahead's candidate
     * enumeration used to remove the game thread's AI-cache scope (AiCache.closeScope) instead of restoring it, so from
     * the first searched decision on, the game's Forge AI shared Forge's one process-wide cache with every other game in
     * the JVM, and a game played beside others differed from its replay alone (gen-check-1007 G4b/G4c). Each game must
     * keep its own random stream, id scope and AI-cache scope to the end, and give the same digests whether it plays alone
     * or at the same time as other K8 games.
     */
    @Test
    public void k8GamesKeepTheirScopesAndPlayTogetherAsAlone() throws Exception {
        final RlActorBench.Cfg cfg = RlActorBenchTest.cfg("eval");
        cfg.k8 = JsonParser.parseString(CHEAP).getAsJsonObject();
        final int n = 3;
        final JsonObject[] games = new JsonObject[n];
        final String[] alone = new String[n];
        for (int k = 0; k < n; k++) {
            games[k] = evalGame(k, k % 2 == 0 ? "forge" : "lookahead:K8", k % 2 == 0 ? "lookahead:K8" : "forge");
            final RlActorBench.Played p = RlActorBench.play(cfg, "eval", games[k], new RlFeaturizer(RlActorBenchTest.index),
                    endpoint("eval"), null, "test");
            Assert.assertNull(p.guardError);
            Assert.assertFalse(p.end.has("scope_lost"), "alone " + k + " lost " + p.end.get("scope_lost"));
            Assert.assertTrue(p.end.getAsJsonObject("opp_search").get("decisions").getAsInt() > 0);
            alone[k] = p.end.get("digest").getAsString() + "/" + p.end.get("digest_lb").getAsString();
        }
        final String[] together = new String[n];
        final Throwable[] failed = new Throwable[n];
        final java.util.List<Thread> ts = new java.util.ArrayList<>();
        for (int k = 0; k < n; k++) {
            final int kk = k;
            final Thread t = new Thread(() -> {
                try {
                    final RlActorBench.Played p = RlActorBench.play(cfg, "eval", games[kk],
                            new RlFeaturizer(RlActorBenchTest.index), endpoint("eval"), null, "test");
                    Assert.assertFalse(p.end.has("scope_lost"), "together " + kk + " lost " + p.end.get("scope_lost"));
                    together[kk] = p.end.get("digest").getAsString() + "/" + p.end.get("digest_lb").getAsString();
                } catch (Throwable e) {
                    failed[kk] = e;
                }
            }, "rlactor-test-" + k);
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        for (int k = 0; k < n; k++) {
            if (failed[k] != null) {
                throw new AssertionError("game " + k + " played together failed", failed[k]);
            }
            Assert.assertEquals(together[k], alone[k], "game " + k + ": together vs alone (digest/digest_lb)");
        }
    }
}
