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
        }
    }
}
