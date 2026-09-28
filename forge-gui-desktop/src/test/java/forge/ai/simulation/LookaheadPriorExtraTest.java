package forge.ai.simulation;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

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
 * Read HX (look-ahead option {@code priorExtra}): the candidate -> {kind, fid} mapping of the prior request; the
 * top-n selection (never a B member, never a null p, ties to Forge's order); {@code priorExtra: 0} is the base search
 * (no call, same candidates, config JSON unchanged); shadow calls but plays out B only; failures keep B; the pin.
 * The service is an in-process HTTP stub.
 */
public class LookaheadPriorExtraTest extends SimulationTest {

    private static final String SHA = "8d2f10ec28369bfa5b9ca5e5618074ff0cf08c91ed69bb2ac0567b16be21a0f9";

    /** A stub of serve.py's /v1/health and /v1/forge/score (prior part only). */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger health = new AtomicInteger(), score = new AtomicInteger();
        final List<JsonObject> requests = new ArrayList<>();
        volatile String sha = SHA;
        volatile int status = 200;
        volatile long delayMs = 0;
        /** p of candidate i (null = no head). */
        volatile IntFunction<Double> p = i -> 0.5;

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "prior-stub");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/v1/health", ex -> {
                health.incrementAndGet();
                JsonObject o = new JsonObject();
                o.addProperty("schema", "mtgx-foundation-response/1");
                o.addProperty("checkpointSha256", sha);
                send(ex, 200, o.toString());
            });
            server.createContext("/v1/forge/score", ex -> {
                score.incrementAndGet();
                JsonObject req = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                synchronized (requests) {
                    requests.add(req);
                }
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (status != 200) {
                    send(ex, status, "{\"error\":\"stub\"}");
                    return;
                }
                JsonArray pr = new JsonArray();
                for (JsonElement ce : req.getAsJsonArray("candidates")) {
                    JsonObject c = ce.getAsJsonObject();
                    JsonObject e = new JsonObject();
                    e.add("id", c.get("id"));
                    e.add("kind", c.get("kind"));
                    Double v = p.apply(c.get("id").getAsInt());
                    e.addProperty("p", v);
                    e.addProperty("logp", v == null ? null : Math.log(Math.max(1e-6, v)));
                    pr.add(e);
                }
                JsonObject o = new JsonObject();
                o.addProperty("schema", "mtgx-foundation-forge-response/1");
                o.add("value", new JsonArray());
                o.add("prior", pr);
                o.add("unknownCards", new JsonArray());
                JsonObject m = new JsonObject();
                m.addProperty("checkpointSha256", sha);
                o.add("model", m);
                o.addProperty("ms", 1.0);
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

    /** Seat a's main phase 1 with more legal plays than breadth 4 (so Forge's full list has a tail). */
    private Game board(boolean aActive) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, aActive ? a : b);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", p, ZoneType.Library);
            }
            for (int i = 0; i < 4; i++) {
                addCard("Mountain", p);
                addCard("Forest", p);
            }
            addCard("Grizzly Bears", p).setSickness(false);
            addCard("Hill Giant", p).setSickness(false);
        }
        addCard("Prodigal Pyromancer", a).setSickness(false);
        for (String n : new String[] {"Forest", "Lightning Bolt", "Hill Giant", "Grizzly Bears", "Shock", "Giant Growth"}) {
            addCardToZone(n, a, ZoneType.Hand);
        }
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch.Config config() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 719_250_400L;
        return c;
    }

    private static LookaheadSearch.Config prior(Stub s, int n, boolean shadow) {
        LookaheadSearch.Config c = config();
        c.priorExtra = n;
        c.priorShadow = shadow;
        c.priorUrl = s.url();
        c.priorCheckpointSha256 = SHA;
        return c;
    }

    private static String label(List<SpellAbility> answer) {
        return answer == null || answer.isEmpty() ? "pass" : answer.get(0).getHostCard().getName() + "::" + answer.get(0).getDescription();
    }

    private static List<String> keys(List<LookaheadSearch.Cand> cs) {
        List<String> l = new ArrayList<>();
        for (LookaheadSearch.Cand c : cs) {
            l.add(c.key());
        }
        return l;
    }

    /** One searched decision of seat a; returns the answer's label. */
    private static String run(LookaheadSearch s, PlayerControllerAi ctrl, List<SpellAbility> def) {
        try {
            return label(s.decide(ctrl, def));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void selectionSkipsBAndNullsAndBreaksTiesByForgeOrder() {
        Double[] p = {0.99, 0.98, 0.97, 0.96, null, 0.3, 0.3, 0.1, 0.5};
        AssertJUnit.assertEquals("[5, 8]", java.util.Arrays.toString(LookaheadSearch.selectExtras(p, 4, 2)));
        AssertJUnit.assertEquals("[8]", java.util.Arrays.toString(LookaheadSearch.selectExtras(p, 4, 1)));
        AssertJUnit.assertEquals("[5, 6, 7, 8]", java.util.Arrays.toString(LookaheadSearch.selectExtras(p, 4, 9)));
        Double[] tie = {0.9, 0.9, null, 0.2, 0.2, 0.2};
        AssertJUnit.assertEquals("[3, 4]", java.util.Arrays.toString(LookaheadSearch.selectExtras(tie, 2, 2)));
        Double[] nulls = {0.5, 0.5, null, null};
        AssertJUnit.assertEquals(0, LookaheadSearch.selectExtras(nulls, 2, 2).length);
        AssertJUnit.assertEquals(0, LookaheadSearch.selectExtras(p, 4, 0).length);
    }

    @Test
    public void candidateKindAndFidMapping() {
        Game game = board(true);
        Player a = game.getPlayers().get(0);
        Card pyro = findCardWithName(game, "Prodigal Pyromancer");
        Card bolt = null, forest = null;
        for (Card c : a.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals("Lightning Bolt")) {
                bolt = c;
            } else if (c.getName().equals("Forest")) {
                forest = c;
            }
        }
        LookaheadSearch.Cand cast = null, land = null, act = null;
        for (SpellAbility sa : new SpellAbilityPicker(a).getCandidateSpellsAndAbilities()) {
            if (sa.getHostCard() == bolt) {
                cast = new LookaheadSearch.Cand(sa, false);
            } else if (sa.getHostCard() == forest && sa.isLandAbility()) {
                land = new LookaheadSearch.Cand(sa, false);
            } else if (sa.getHostCard() == pyro) {
                act = new LookaheadSearch.Cand(sa, false);
            }
        }
        AssertJUnit.assertNotNull(cast);
        AssertJUnit.assertNotNull(land);
        AssertJUnit.assertNotNull(act);
        JsonObject j = cast.toPriorJson(3);
        AssertJUnit.assertEquals("{\"id\":3,\"kind\":\"cast\",\"fid\":" + bolt.getId() + "}", j.toString());
        AssertJUnit.assertEquals("{\"id\":4,\"kind\":\"land\",\"fid\":" + forest.getId() + "}", land.toPriorJson(4).toString());
        AssertJUnit.assertEquals("{\"id\":5,\"kind\":\"activate\",\"fid\":" + pyro.getId() + "}", act.toPriorJson(5).toString());
        AssertJUnit.assertEquals("{\"id\":0,\"kind\":\"pass\"}", new LookaheadSearch.Cand(null, true).toPriorJson(0).toString());
    }

    @Test
    public void priorExtraZeroIsTheBaseSearch() throws Exception {
        try (Stub stub = new Stub()) {
            Game game = board(true);
            Player a = game.getPlayers().get(0);
            PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
            List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
            String before = LookaheadSearch.fingerprint(game);

            LookaheadSearch base = new LookaheadSearch(config());
            String x = run(base, ctrl, def);
            LookaheadSearch.Config c0 = config();
            c0.priorUrl = stub.url(); // configured but off: still no call
            c0.priorCheckpointSha256 = SHA;
            LookaheadSearch zero = new LookaheadSearch(c0);
            String y = run(zero, ctrl, def);

            AssertJUnit.assertEquals(x, y);
            AssertJUnit.assertEquals(keys(base.lastCandidates), keys(zero.lastCandidates));
            AssertJUnit.assertEquals(4, base.lastCandidates.size());
            AssertJUnit.assertEquals(base.getStats().rollouts, zero.getStats().rollouts);
            AssertJUnit.assertEquals(base.getStats().steps, zero.getStats().steps);
            AssertJUnit.assertEquals(0, stub.health.get());
            AssertJUnit.assertEquals(0, stub.score.get());
            AssertJUnit.assertEquals(config().toJson().toString(), c0.toJson().toString());
            AssertJUnit.assertFalse(zero.getStats().toJson().toString().contains("prior"));
            AssertJUnit.assertFalse(c0.toJson().toString().contains("prior"));
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        }
    }

    @Test
    public void extrasAreTheTopNOfTheTailByP() throws Exception {
        try (Stub stub = new Stub()) {
            Game game = board(true);
            Player a = game.getPlayers().get(0);
            PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
            List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
            String before = LookaheadSearch.fingerprint(game);
            // B gets the highest p (must never be re-added), candidate 4 no head, 5 and 6 tie, the last one is the best.
            final int[] last = {-1};
            stub.p = i -> {
                if (i == 4) {
                    return null;
                }
                return Double.valueOf(i < 4 ? 0.99 : i == last[0] ? 0.5 : i <= 6 ? 0.3 : 0.1);
            };

            LookaheadSearch base = new LookaheadSearch(config());
            String baseAnswer = run(base, ctrl, def);
            // Learn L's size from a shadow call first (shadow adds nothing).
            LookaheadSearch sh = new LookaheadSearch(prior(stub, 2, true));
            String shAnswer = run(sh, ctrl, def);
            AssertJUnit.assertEquals(1, stub.score.get());
            int l = stub.requests.get(0).getAsJsonArray("candidates").size();
            AssertJUnit.assertTrue("full list must have a tail of >= 4, got " + l, l >= 8);
            last[0] = l - 1;

            LookaheadSearch two = new LookaheadSearch(prior(stub, 2, false));
            run(two, ctrl, def);
            LookaheadSearch one = new LookaheadSearch(prior(stub, 1, false));
            run(one, ctrl, def);
            AssertJUnit.assertEquals(1, stub.health.get()); // the pin is checked once per JVM and service
            AssertJUnit.assertEquals(3, stub.score.get());

            JsonObject req = stub.requests.get(1);
            AssertJUnit.assertEquals("mtgx-foundation-forge-request/1", req.get("schema").getAsString());
            AssertJUnit.assertEquals(0, req.get("seat").getAsInt());
            AssertJUnit.assertTrue(req.has("startingSeat") && req.has("mulligans") && req.has("deck"));
            AssertJUnit.assertEquals(0, req.getAsJsonArray("leaves").size());
            JsonObject root = req.getAsJsonObject("root");
            AssertJUnit.assertEquals(0, root.get("seat").getAsInt());
            AssertJUnit.assertEquals(0, root.get("activePlayer").getAsInt());
            AssertJUnit.assertEquals("MAIN1", root.get("phase").getAsString());
            // Every non-pass candidate's fid is a card of the root state (hand or battlefield of seat 0).
            List<Integer> fids = new ArrayList<>();
            for (JsonElement pl : root.getAsJsonArray("players")) {
                for (String z : new String[] {"hand", "battlefield"}) {
                    for (JsonElement ce : pl.getAsJsonObject().getAsJsonArray(z)) {
                        fids.add(ce.getAsJsonObject().get("fid").getAsInt());
                    }
                }
            }
            JsonArray cands = req.getAsJsonArray("candidates");
            AssertJUnit.assertEquals(l, cands.size());
            for (int i = 0; i < l; i++) {
                JsonObject c = cands.get(i).getAsJsonObject();
                AssertJUnit.assertEquals(i, c.get("id").getAsInt());
                String kind = c.get("kind").getAsString();
                if (kind.equals("pass")) {
                    AssertJUnit.assertFalse(c.has("fid"));
                } else {
                    AssertJUnit.assertTrue(c + " fid not in root", fids.contains(c.get("fid").getAsInt()));
                }
            }
            // B is unchanged and first, in every mode.
            List<String> bKeys = keys(base.lastCandidates);
            AssertJUnit.assertEquals(bKeys, keys(sh.lastCandidates));
            AssertJUnit.assertEquals(bKeys, keys(two.lastCandidates).subList(0, 4));
            AssertJUnit.assertEquals(bKeys, keys(one.lastCandidates).subList(0, 4));
            // n = 2: the best (the last) and, of the tie 5/6, 5; appended in Forge's order. n = 1: the last only.
            AssertJUnit.assertEquals(6, two.lastCandidates.size());
            AssertJUnit.assertEquals(fidKind(cands, 5), fidKind(two.lastCandidates.get(4)));
            AssertJUnit.assertEquals(fidKind(cands, l - 1), fidKind(two.lastCandidates.get(5)));
            AssertJUnit.assertEquals(5, one.lastCandidates.size());
            AssertJUnit.assertEquals(fidKind(cands, l - 1), fidKind(one.lastCandidates.get(4)));

            LookaheadSearch.Stats st = two.getStats();
            AssertJUnit.assertEquals(1, st.priorQualifying);
            AssertJUnit.assertEquals(1, st.priorCalls);
            AssertJUnit.assertEquals(0, st.priorFailures);
            AssertJUnit.assertEquals(1, st.decisionsWithExtras);
            AssertJUnit.assertEquals(2, st.extrasAdded);
            AssertJUnit.assertEquals(6L * 2, st.rollouts);
            AssertJUnit.assertEquals(SHA, st.priorCheckpoint);
            AssertJUnit.assertEquals(64, st.priorDigest.length());
            JsonObject js = st.toJson();
            for (String k : new String[] {"priorCalls", "priorFailures", "priorMs", "priorMaxMs", "decisionsWithExtras", "extrasAdded",
                    "departuresToExtra", "priorDigest", "priorCheckpointSha256", "priorDecisions"}) {
                AssertJUnit.assertTrue(k, js.has(k));
            }
            AssertJUnit.assertTrue(prior(stub, 2, false).toJson().has("priorExtra"));

            // Shadow: the call is made and logged, nothing is played out beyond B, the answer is the base answer.
            LookaheadSearch.Stats ss = sh.getStats();
            AssertJUnit.assertEquals(base.getStats().rollouts, ss.rollouts);
            AssertJUnit.assertEquals(baseAnswer, shAnswer);
            AssertJUnit.assertEquals(1, ss.shadowDecisionsWithExtras);
            AssertJUnit.assertEquals(2, ss.shadowExtras);
            AssertJUnit.assertEquals(0, ss.extrasAdded);
            AssertJUnit.assertEquals(2, ss.priorDecisions.get(0).getAsJsonObject().getAsJsonArray("wouldAdd").size());
            // The same responses give the same digest.
            AssertJUnit.assertEquals(st.priorDigest, one.getStats().priorDigest);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        }
    }

    private static String fidKind(JsonArray cands, int i) {
        JsonObject c = cands.get(i).getAsJsonObject();
        return c.get("kind").getAsString() + ":" + (c.has("fid") ? c.get("fid").getAsInt() : -1);
    }

    private static String fidKind(LookaheadSearch.Cand c) {
        return c.kind + ":" + (c.pass ? -1 : c.hostId);
    }

    @Test
    public void failureOrLateCallKeepsB() throws Exception {
        try (Stub stub = new Stub()) {
            Game game = board(true);
            Player a = game.getPlayers().get(0);
            PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
            List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
            LookaheadSearch base = new LookaheadSearch(config());
            String x = run(base, ctrl, def);

            stub.status = 500;
            LookaheadSearch err = new LookaheadSearch(prior(stub, 2, false));
            String y = run(err, ctrl, def);
            AssertJUnit.assertEquals(x, y);
            AssertJUnit.assertEquals(keys(base.lastCandidates), keys(err.lastCandidates));
            AssertJUnit.assertEquals(1, err.getStats().priorCalls);
            AssertJUnit.assertEquals(1, err.getStats().priorFailures);
            AssertJUnit.assertEquals("http 500", err.getStats().priorLastError);

            stub.status = 200;
            stub.delayMs = 1500;
            LookaheadSearch.Config late = prior(stub, 2, false);
            late.priorTimeoutMs = 300;
            LookaheadSearch slow = new LookaheadSearch(late);
            String z = run(slow, ctrl, def);
            AssertJUnit.assertEquals(x, z);
            AssertJUnit.assertEquals(keys(base.lastCandidates), keys(slow.lastCandidates));
            AssertJUnit.assertEquals(1, slow.getStats().priorFailures);
            AssertJUnit.assertEquals(0, slow.getStats().extrasAdded);
        }
    }

    @Test
    public void notActiveMakesNoCall() throws Exception {
        try (Stub stub = new Stub()) {
            Game game = board(false);
            Player a = game.getPlayers().get(0);
            PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
            List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
            LookaheadSearch s = new LookaheadSearch(prior(stub, 2, false));
            run(s, ctrl, def);
            AssertJUnit.assertEquals(0, stub.score.get());
            AssertJUnit.assertEquals(0, s.getStats().priorQualifying);
            AssertJUnit.assertEquals(s.getStats().searched, s.getStats().priorNotQualifying);
        }
    }

    @Test
    public void pinMismatchRefuses() throws Exception {
        try (Stub stub = new Stub()) {
            stub.sha = "0000";
            try {
                new LookaheadSearch(prior(stub, 2, false));
                AssertJUnit.fail("a checkpoint mismatch must refuse");
            } catch (IllegalStateException expected) {
                AssertJUnit.assertTrue(expected.getMessage(), expected.getMessage().contains("pinned"));
            }
            LookaheadSearch.Config noPin = prior(stub, 2, false);
            noPin.priorCheckpointSha256 = null;
            try {
                new LookaheadSearch(noPin);
                AssertJUnit.fail("a missing pin must refuse");
            } catch (IllegalStateException expected) {
                // refused
            }
        }
    }
}
