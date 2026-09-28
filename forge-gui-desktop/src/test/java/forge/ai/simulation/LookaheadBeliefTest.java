package forge.ai.simulation;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Belief (lane belief-sampling-0928, look-ahead option {@code belief}): the conditional-Bernoulli draw and its
 * marginals; {@code belief=off} is the base search; the observation-only pool (our deck and every opponent card we can
 * see removed); belief worlds hold new cards of pool names and never touch the live game; the service request and its
 * pin; a failed call samples uniformly (counted); shadow builds belief worlds but plays the default ones.
 */
public class LookaheadBeliefTest extends SimulationTest {

    private static final String SHA = "b31efb31efb31efb31efb31efb31efb31efb31efb31efb31efb31efb31efb31e";

    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger health = new AtomicInteger(), calls = new AtomicInteger();
        final List<JsonObject> requests = new ArrayList<>();
        volatile int status = 200;
        final Map<String, Double> lw = new HashMap<>();

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "belief-stub");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/v1/health", ex -> {
                health.incrementAndGet();
                JsonObject o = new JsonObject();
                o.addProperty("checkpointSha256", SHA);
                send(ex, 200, o.toString());
            });
            server.createContext("/v1/forge/belief", ex -> {
                calls.incrementAndGet();
                JsonObject req = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                synchronized (requests) {
                    requests.add(req);
                }
                if (status != 200) {
                    send(ex, status, "{}");
                    return;
                }
                JsonArray out = new JsonArray();
                for (JsonElement e : req.getAsJsonArray("pool")) {
                    out.add(lw.getOrDefault(e.getAsString(), 0.0));
                }
                JsonObject o = new JsonObject();
                o.addProperty("schema", "mtgx-belief-response/1");
                o.add("logw", out);
                o.add("unknownCards", new JsonArray());
                JsonObject m = new JsonObject();
                m.addProperty("checkpointSha256", SHA);
                o.add("model", m);
                send(ex, 200, o.toString());
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private static void send(HttpExchange ex, int code, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final String[] CUBE = {"Counterspell", "Lightning Bolt", "Hill Giant", "Shock", "Giant Growth", "Grizzly Bears",
        "Llanowar Elves", "Serra Angel", "Dark Ritual", "Wrath of God", "Mana Leak", "Prodigal Pyromancer", "Island"};

    private static String cubeFile() throws IOException {
        JsonArray cards = new JsonArray();
        for (String n : CUBE) {
            JsonObject c = new JsonObject();
            c.addProperty("name", n);
            JsonArray t = new JsonArray();
            if (n.equals("Counterspell") || n.equals("Mana Leak")) {
                t.add("counter");
            }
            if (n.equals("Lightning Bolt") || n.equals("Shock") || n.equals("Wrath of God")) {
                t.add("removal");
            }
            c.add("tags", t);
            cards.add(c);
        }
        JsonObject o = new JsonObject();
        o.addProperty("schema", "mtgx-belief-cube/1");
        o.add("cards", cards);
        Path p = Files.createTempFile("belief-cube", ".json");
        Files.writeString(p, o.toString());
        p.toFile().deleteOnExit();
        return p.toString();
    }

    /** Seat a's main phase 1; seat b holds 3 hidden cards and a 10-card library, with a Hill Giant and a Shock seen. */
    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 10; i++) {
                addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", p, ZoneType.Library);
            }
            for (int i = 0; i < 3; i++) {
                addCard("Mountain", p);
                addCard("Forest", p);
            }
        }
        addCard("Hill Giant", b).setSickness(false);
        addCardToZone("Shock", b, ZoneType.Graveyard);
        addCard("Prodigal Pyromancer", a).setSickness(false);
        for (String n : new String[] {"Forest", "Lightning Bolt", "Grizzly Bears", "Giant Growth"}) {
            addCardToZone(n, a, ZoneType.Hand);
        }
        for (String n : new String[] {"Llanowar Elves", "Dark Ritual", "Serra Angel"}) {
            addCardToZone(n, b, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch.Config config() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 3;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 719_250_900L;
        return c;
    }

    private static LookaheadSearch.Config belief(String mode, String cube, Stub s) {
        LookaheadSearch.Config c = config();
        c.belief = mode;
        c.beliefCube = cube;
        c.beliefBasics = 2;
        if (s != null) {
            c.beliefUrl = s.url();
            c.beliefCheckpointSha256 = SHA;
        }
        return c;
    }

    private static String label(List<SpellAbility> answer) {
        return answer == null || answer.isEmpty() ? "pass" : answer.get(0).getHostCard().getName() + "::" + answer.get(0).getDescription();
    }

    private static String run(LookaheadSearch s, PlayerControllerAi ctrl, List<SpellAbility> def) {
        try {
            return label(s.decide(ctrl, def));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void conditionalBernoulliDrawMatchesTheExactDistribution() {
        double[] w = {0.2, 1.0, 0.5, 0.05, 0.8, 0.3};
        int n = 2;
        List<String> pool = Arrays.asList("a", "b", "c", "d", "e", "f");
        BeliefSampler.Dist d = new BeliefSampler.Dist(pool, w, 1, n, 0);
        double en = 0;
        Map<String, Double> exact = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                en += w[i] * w[j];
                exact.put(i + "," + j, w[i] * w[j]);
            }
        }
        Random rng = new Random(7);
        Map<String, Integer> seen = new HashMap<>();
        int N = 200_000;
        for (int t = 0; t < N; t++) {
            int[] h = d.sampleHand(rng);
            seen.merge(h[0] + "," + h[1], 1, Integer::sum);
        }
        for (Map.Entry<String, Double> e : exact.entrySet()) {
            double p = e.getValue() / en;
            double f = seen.getOrDefault(e.getKey(), 0) / (double) N;
            AssertJUnit.assertEquals(e.getKey(), p, f, 4 * Math.sqrt(p * (1 - p) / N) + 1e-4);
        }
        double[] pi = d.marginals();
        double sum = 0;
        for (int i = 0; i < 6; i++) {
            double inc = 0;
            for (int j = 0; j < 6; j++) {
                if (j != i) {
                    inc += w[i] * w[j];
                }
            }
            AssertJUnit.assertEquals(inc / en, pi[i], 1e-12);
            sum += pi[i];
        }
        AssertJUnit.assertEquals(2.0, sum, 1e-12);
        // Basics are M slots of one weight; the library is the other slots.
        BeliefSampler.Dist b = new BeliefSampler.Dist(Arrays.asList("x", "Island"), new double[] {1.0, 0.5}, 3, 2, 2);
        AssertJUnit.assertEquals(4, b.slots());
        int[] h = b.sampleHand(new Random(1));
        List<Integer> lib = b.library(h, new Random(2));
        AssertJUnit.assertEquals(2, lib.size());
        for (int i : h) {
            AssertJUnit.assertFalse(lib.contains(i));
        }
        // Log-weights: max -> 1, floored at exp(-30).
        double[] ws = BeliefSampler.weights(new double[] {2.0, 0.0, -100.0});
        AssertJUnit.assertEquals(1.0, ws[0], 0);
        AssertJUnit.assertEquals(Math.exp(-2.0), ws[1], 1e-15);
        AssertJUnit.assertEquals(Math.exp(-30.0), ws[2], 1e-25);
    }

    @Test
    public void beliefOffIsTheBaseSearch() throws Exception {
        String cube = cubeFile();
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch base = new LookaheadSearch(config());
        String x = run(base, ctrl, def);
        LookaheadSearch.Config c0 = config();
        c0.beliefCube = cube; // configured but off
        LookaheadSearch off = new LookaheadSearch(c0);
        String y = run(off, ctrl, def);
        AssertJUnit.assertEquals(x, y);
        AssertJUnit.assertEquals(base.getStats().rollouts, off.getStats().rollouts);
        AssertJUnit.assertEquals(base.getStats().steps, off.getStats().steps);
        AssertJUnit.assertNull(off.lastBelief);
        AssertJUnit.assertEquals(config().toJson().toString(), c0.toJson().toString());
        AssertJUnit.assertFalse(c0.toJson().toString().contains("belief"));
        AssertJUnit.assertFalse(off.getStats().toJson().toString().contains("belief"));
        AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
    }

    @Test
    public void uniformBeliefUsesTheObservationOnlyPool() throws Exception {
        String cube = cubeFile();
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch u = new LookaheadSearch(belief("uniform", cube, null));
        run(u, ctrl, def);
        BeliefSampler.Dist d = u.lastBelief;
        AssertJUnit.assertNotNull(d);
        AssertJUnit.assertTrue(d.uniform);
        // Seen opponent cards (Hill Giant on the battlefield, Shock in the graveyard) are out; the opponent's hidden
        // hand (Llanowar Elves, Dark Ritual, Serra Angel) stays possible; basics are in with M = 2 slots each.
        AssertJUnit.assertFalse(d.pool.contains("Hill Giant"));
        AssertJUnit.assertFalse(d.pool.contains("Shock"));
        AssertJUnit.assertTrue(d.pool.containsAll(Arrays.asList("Llanowar Elves", "Dark Ritual", "Serra Angel", "Counterspell")));
        AssertJUnit.assertTrue(d.pool.contains("Island"));
        AssertJUnit.assertEquals(3, d.n);
        AssertJUnit.assertEquals(10, d.libHidden);
        int basics = 0;
        for (String s : d.slotName) {
            if (BeliefSampler.isBasic(s)) {
                basics++;
            }
        }
        AssertJUnit.assertEquals(10, basics);
        LookaheadSearch.Stats st = u.getStats();
        AssertJUnit.assertEquals(1, st.beliefDecisions);
        AssertJUnit.assertEquals(0, st.beliefCalls);
        AssertJUnit.assertEquals(st.rollouts, st.beliefWorlds);
        AssertJUnit.assertEquals(st.beliefWorlds * 13, st.beliefCards);
        AssertJUnit.assertEquals(0, st.beliefMismatch);
        JsonObject cov = st.beliefCoverage.get(0).getAsJsonObject();
        AssertJUnit.assertEquals(0.0, cov.get("tvd").getAsDouble(), 1e-9);
        AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        // Our deck is out of the pool: register seat a's deck with Counterspell in it.
        forge.deck.Deck deck = new forge.deck.Deck("t");
        deck.getOrCreate(forge.deck.DeckSection.Main).add(BeliefSampler.resolve("Counterspell"), 1);
        java.lang.reflect.Field f = forge.game.player.RegisteredPlayer.class.getDeclaredField("currentDeck");
        f.setAccessible(true);
        f.set(a.getRegisteredPlayer(), deck);
        LookaheadSearch u2 = new LookaheadSearch(belief("uniform", cube, null));
        run(u2, ctrl, def);
        AssertJUnit.assertFalse(u2.lastBelief.pool.contains("Counterspell"));
    }

    @Test
    public void humanBeliefCallsTheServiceAndFallsBackUniformly() throws Exception {
        String cube = cubeFile();
        try (Stub stub = new Stub()) {
            stub.lw.put("Counterspell", 5.0);
            stub.lw.put("Mana Leak", 4.0);
            Game game = board();
            Player a = game.getPlayers().get(0);
            PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
            List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
            String before = LookaheadSearch.fingerprint(game);
            LookaheadSearch h = new LookaheadSearch(belief("human", cube, stub));
            run(h, ctrl, def);
            AssertJUnit.assertEquals(1, stub.health.get());
            AssertJUnit.assertEquals(1, stub.calls.get());
            JsonObject req = stub.requests.get(0);
            AssertJUnit.assertEquals("mtgx-belief-request/1", req.get("schema").getAsString());
            AssertJUnit.assertEquals(0, req.get("seat").getAsInt());
            AssertJUnit.assertEquals(1, req.get("target").getAsInt());
            AssertJUnit.assertEquals(3, req.get("n").getAsInt());
            AssertJUnit.assertEquals(6, req.get("openMana").getAsInt());
            AssertJUnit.assertTrue(req.has("root") && req.has("deck") && req.has("mulligans"));
            AssertJUnit.assertEquals(h.lastBelief.pool.size(), req.getAsJsonArray("pool").size());
            LookaheadSearch.Stats st = h.getStats();
            AssertJUnit.assertEquals(0, st.beliefFailures);
            AssertJUnit.assertEquals(SHA, st.beliefCheckpoint);
            AssertJUnit.assertEquals(64, st.beliefDigest.length());
            JsonObject cov = st.beliefCoverage.get(0).getAsJsonObject();
            // Both counters carry most of the mass: expected counters near 2 vs uniform 3 * 2 / slots.
            JsonArray ec = cov.getAsJsonArray("counter");
            AssertJUnit.assertTrue(cov.toString(), ec.get(0).getAsDouble() > 1.5);
            AssertJUnit.assertTrue(cov.toString(), ec.get(1).getAsDouble() < 0.5);
            AssertJUnit.assertTrue(cov.get("tvd").getAsDouble() > 0.3);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertTrue(belief("human", cube, stub).toJson().has("beliefCheckpointSha256"));
            // Same state, same responses: same digest.
            LookaheadSearch h2 = new LookaheadSearch(belief("human", cube, stub));
            String y2 = run(h2, ctrl, def);
            AssertJUnit.assertEquals(st.beliefDigest, h2.getStats().beliefDigest);
            AssertJUnit.assertEquals(st.steps, h2.getStats().steps);

            stub.status = 500;
            LookaheadSearch f = new LookaheadSearch(belief("human", cube, stub));
            run(f, ctrl, def);
            AssertJUnit.assertEquals(1, f.getStats().beliefFailures);
            AssertJUnit.assertTrue(f.lastBelief.fallback);
            AssertJUnit.assertTrue(f.lastBelief.uniform);
            AssertJUnit.assertEquals("http 500", f.getStats().beliefLastError);
            AssertJUnit.assertEquals(f.getStats().rollouts, f.getStats().beliefWorlds);
            // Fallback = uniform over the same pool: the same search as belief=uniform.
            LookaheadSearch u = new LookaheadSearch(belief("uniform", cube, null));
            run(u, ctrl, def);
            AssertJUnit.assertEquals(u.getStats().steps, f.getStats().steps);
            AssertJUnit.assertNotNull(y2);
        }
    }

    @Test
    public void shadowBuildsBeliefWorldsButPlaysTheDefaultOnes() throws Exception {
        String cube = cubeFile();
        try (Stub stub = new Stub()) {
            Game game = board();
            Player a = game.getPlayers().get(0);
            PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
            List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
            String before = LookaheadSearch.fingerprint(game);
            LookaheadSearch base = new LookaheadSearch(config());
            String x = run(base, ctrl, def);
            LookaheadSearch.Config sc = belief("human", cube, stub);
            sc.beliefShadow = true;
            LookaheadSearch sh = new LookaheadSearch(sc);
            String y = run(sh, ctrl, def);
            AssertJUnit.assertEquals(x, y);
            AssertJUnit.assertEquals(base.getStats().rollouts, sh.getStats().rollouts);
            AssertJUnit.assertEquals(base.getStats().steps, sh.getStats().steps);
            AssertJUnit.assertEquals(1, sh.getStats().beliefCalls);
            AssertJUnit.assertEquals(2, sh.getStats().beliefWorlds); // K = 2 throwaway worlds
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        }
    }

    @Test
    public void refusals() throws Exception {
        String cube = cubeFile();
        LookaheadSearch.Config r = belief("uniform", cube, null);
        r.reuse = true;
        try {
            new LookaheadSearch(r);
            AssertJUnit.fail("reuse + belief must be refused");
        } catch (IllegalStateException expected) {
            AssertJUnit.assertTrue(expected.getMessage().contains("reuse"));
        }
        LookaheadSearch.Config pin = belief("uniform", cube, null);
        pin.beliefCubeSha256 = "00";
        try {
            new LookaheadSearch(pin);
            AssertJUnit.fail("a cube pin mismatch must be refused");
        } catch (IllegalStateException expected) {
            AssertJUnit.assertTrue(expected.getMessage().contains("pinned"));
        }
        LookaheadSearch.Config noUrl = belief("human", cube, null);
        try {
            new LookaheadSearch(noUrl);
            AssertJUnit.fail("belief=human without a service must be refused");
        } catch (IllegalStateException expected) {
            AssertJUnit.assertTrue(expected.getMessage().contains("beliefUrl"));
        }
        LookaheadSearch.Config bad = belief("human2", cube, null);
        try {
            new LookaheadSearch(bad);
            AssertJUnit.fail("an unknown belief must be refused");
        } catch (IllegalStateException expected) {
            AssertJUnit.assertTrue(expected.getMessage().contains("off, human or uniform"));
        }
        Card unused = null;
        AssertJUnit.assertNull(unused);
    }
}
