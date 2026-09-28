package forge.ai.simulation;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
import forge.util.MyRandom;

/**
 * Tutor ranking (look-ahead option {@code tutorRank}, lane tutor-ranking-0928): a library search asked during the live
 * game's resolution is searched over Forge AI's pick plus the ranker's top-n other names, each played out from a copy in
 * which the resolution is replayed with that card forced. {@code tutorRank: 0} is Forge AI (no call, config JSON
 * unchanged); shadow ranks and plays out but keeps Forge's pick; a failed ranker call or an unsupported position keeps
 * Forge's pick; the pin. The ranker is an in-process HTTP stub.
 */
public class LookaheadTutorRankTest extends SimulationTest {

    private static final String SHA = "7777777777777777777777777777777777777777777777777777777777777777";

    /** A stub of serve_tutor.py: /v1/health and /v1/tutor/rank. */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger health = new AtomicInteger(), rank = new AtomicInteger();
        final List<JsonObject> requests = new ArrayList<>();
        volatile String sha = SHA;
        volatile int status = 200;
        /** score of a candidate name. */
        volatile Function<String, Double> score = n -> 0.0;

        Stub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "tutor-stub");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/v1/health", ex -> {
                health.incrementAndGet();
                JsonObject o = new JsonObject();
                o.addProperty("ok", true);
                o.addProperty("schema", "tutor-rank/1");
                o.addProperty("checkpointSha256", sha);
                send(ex, 200, o.toString());
            });
            server.createContext("/v1/tutor/rank", ex -> {
                rank.incrementAndGet();
                JsonObject req = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                synchronized (requests) {
                    requests.add(req);
                }
                if (status != 200) {
                    send(ex, status, "{\"error\":\"stub\"}");
                    return;
                }
                JsonArray sc = new JsonArray();
                for (JsonElement ce : req.getAsJsonArray("candidates")) {
                    sc.add(score.apply(ce.getAsString()));
                }
                JsonObject o = new JsonObject();
                o.addProperty("schema", "tutor-rank/1");
                o.addProperty("checkpointSha256", sha);
                o.add("scores", sc);
                o.addProperty("unknownCards", 0);
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

    private static LookaheadSearch.Config config() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 719_250_400L;
        return c;
    }

    private static LookaheadSearch.Config tutor(Stub s, int n, boolean shadow) {
        LookaheadSearch.Config c = config();
        c.tutorRank = n;
        c.tutorShadow = shadow;
        c.tutorUrl = s.url();
        c.tutorCheckpointSha256 = SHA;
        return c;
    }

    /**
     * Seat a's main phase 1: Swamp, Swamp, Mountain untapped, Demonic Tutor in hand; the library holds Lightning Bolt,
     * creatures and lands. The opponent is at {@code oppLife} with blockers.
     */
    private Game board(int oppLife) {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (String n : new String[] {"Hill Giant", "Grizzly Bears", "Lightning Bolt", "Swamp", "Craw Wurm", "Mountain", "Giant Growth", "Swamp"}) {
            addCardToZone(n, a, ZoneType.Library);
        }
        for (int i = 0; i < 20; i++) {
            addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", b, ZoneType.Library);
        }
        addCard("Swamp", a);
        addCard("Swamp", a);
        addCard("Mountain", a);
        addCardToZone("Demonic Tutor", a, ZoneType.Hand);
        for (int i = 0; i < 3; i++) {
            addCard("Grizzly Bears", b).setSickness(false);
            addCard("Mountain", b);
        }
        b.setLife(oppLife, null);
        game.getAction().checkStateEffects(true);
        return game;
    }

    /** Seat a gets a look-ahead controller bound to this game and search. */
    private static PlayerControllerLookahead bind(Game game, LookaheadSearch s) {
        Player a = game.getPlayers().get(0);
        LobbyPlayerLookahead lobby = new LobbyPlayerLookahead("A");
        lobby.setAiProfile("Default");
        lobby.bind(game, s);
        PlayerControllerLookahead ctrl = new PlayerControllerLookahead(game, a, lobby);
        a.dangerouslySetController(ctrl);
        return ctrl;
    }

    /** Cast Demonic Tutor (Forge AI pays) and resolve it; returns the name of the card it put into a's hand. */
    private static String castAndResolve(Game game) {
        Player a = game.getPlayers().get(0);
        Card dt = null;
        for (Card c : a.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals("Demonic Tutor")) {
                dt = c;
            }
        }
        AssertJUnit.assertNotNull(dt);
        SpellAbility sa = dt.getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        AssertJUnit.assertTrue(((PlayerControllerAi) a.getController()).playChosenSpellAbility(sa));
        AssertJUnit.assertEquals(1, game.getStack().size());
        MyRandom.setRandom(new Random(42));
        game.getStack().resolveStack();
        AssertJUnit.assertTrue(game.getStack().isEmpty());
        String got = null;
        for (Card c : a.getCardsIn(ZoneType.Hand)) {
            got = got == null ? c.getName() : got + "," + c.getName();
        }
        return got;
    }

    @Test
    public void tutorRankZeroIsForgeAi() throws Exception {
        try (Stub stub = new Stub()) {
            Game g0 = board(3);
            String plain = castAndResolve(g0);
            String fp0 = LookaheadSearch.fingerprint(g0);

            Game g1 = board(3);
            LookaheadSearch.Config c0 = config();
            c0.tutorUrl = stub.url(); // configured but off: no call
            c0.tutorCheckpointSha256 = SHA;
            LookaheadSearch s = new LookaheadSearch(c0);
            bind(g1, s);
            String off = castAndResolve(g1);
            s.shutdown();
            AssertJUnit.assertEquals(plain, off);
            AssertJUnit.assertEquals(fp0, LookaheadSearch.fingerprint(g1));
            AssertJUnit.assertEquals(0, stub.health.get());
            AssertJUnit.assertEquals(0, stub.rank.get());
            AssertJUnit.assertEquals(config().toJson().toString(), c0.toJson().toString());
            AssertJUnit.assertFalse(c0.toJson().toString().contains("tutor"));
            AssertJUnit.assertFalse(s.getStats().toJson().toString().contains("tutor"));
        }
    }

    @Test
    public void shadowRanksPlaysOutAndKeepsForgesPick() throws Exception {
        try (Stub stub = new Stub()) {
            stub.score = n -> n.equals("Lightning Bolt") ? 3.0 : n.equals("Craw Wurm") ? 2.0 : 0.0;
            Game g0 = board(3);
            String plain = castAndResolve(g0);
            String fp0 = LookaheadSearch.fingerprint(g0);

            Game g1 = board(3);
            LookaheadSearch s = new LookaheadSearch(tutor(stub, 2, true));
            bind(g1, s);
            String sh = castAndResolve(g1);
            s.shutdown();
            AssertJUnit.assertEquals(plain, sh);
            AssertJUnit.assertEquals(fp0, LookaheadSearch.fingerprint(g1));
            AssertJUnit.assertEquals(1, stub.rank.get());
            LookaheadSearch.Stats st = s.getStats();
            AssertJUnit.assertEquals(1, st.tutorSeen);
            AssertJUnit.assertEquals(1, st.tutorSearched);
            AssertJUnit.assertEquals(0, st.tutorDeparted);
            // Forge's pick first, then the ranker's top two other names.
            AssertJUnit.assertEquals(3, s.lastTutorCandidates.size());
            AssertJUnit.assertTrue(String.valueOf(s.lastTutorCandidates), s.lastTutorCandidates.contains("Lightning Bolt"));
            AssertJUnit.assertEquals(plain, s.lastTutorCandidates.get(0));
            for (boolean ok : s.lastTutorOk) {
                AssertJUnit.assertTrue("every forced pick was made in every world", ok);
            }
            AssertJUnit.assertEquals(2 * 3, st.tutorRollouts);
            AssertJUnit.assertEquals(0, st.tutorForcedMissed);
            JsonObject req = stub.requests.get(0);
            AssertJUnit.assertEquals("tutor-rank/1", req.get("schema").getAsString());
            AssertJUnit.assertEquals("Demonic Tutor", req.get("tutor").getAsString());
            AssertJUnit.assertEquals("hand", req.get("dest").getAsString());
            // distinct names only (two Swamps in the library -> one name)
            AssertJUnit.assertEquals(7, req.getAsJsonArray("candidates").size());
            JsonObject state = req.getAsJsonObject("state");
            AssertJUnit.assertEquals(3, state.get("oppLife").getAsInt());
            AssertJUnit.assertEquals(2, state.getAsJsonObject("library").get("Swamp").getAsInt());
            AssertJUnit.assertTrue(st.toJson().toString().contains("\"tutorDecisions\""));
        }
    }

    @Test
    public void searchedPickTakesTheLethalBurnSpell() throws Exception {
        try (Stub stub = new Stub()) {
            // Opponent at 3: a Mountain stays untapped after Demonic Tutor, so Lightning Bolt to hand wins at once.
            stub.score = n -> n.equals("Lightning Bolt") ? 3.0 : 0.0;
            Game g0 = board(3);
            String plain = castAndResolve(g0);
            AssertJUnit.assertFalse("Forge AI's own pick must not be the Bolt for this test to mean anything: " + plain,
                    plain.contains("Lightning Bolt"));

            Game g1 = board(3);
            LookaheadSearch s = new LookaheadSearch(tutor(stub, 1, false));
            bind(g1, s);
            String got = castAndResolve(g1);
            s.shutdown();
            AssertJUnit.assertTrue(got, got.contains("Lightning Bolt"));
            AssertJUnit.assertEquals(1, s.getStats().tutorDeparted);
            AssertJUnit.assertEquals(LookaheadSearch.TERMINAL, s.lastTutorEv[1], 1e-9);
            // The live game only moved the chosen card: the opponent is untouched.
            AssertJUnit.assertEquals(3, g1.getPlayers().get(1).getLife());
            AssertJUnit.assertEquals(7, g1.getPlayers().get(0).getCardsIn(ZoneType.Library).size());
        }
    }

    @Test
    public void rankerFailureKeepsForgesPick() throws Exception {
        try (Stub stub = new Stub()) {
            Game g0 = board(3);
            String plain = castAndResolve(g0);
            Game g1 = board(3);
            LookaheadSearch s = new LookaheadSearch(tutor(stub, 2, false));
            stub.status = 500;
            bind(g1, s);
            String got = castAndResolve(g1);
            s.shutdown();
            AssertJUnit.assertEquals(plain, got);
            AssertJUnit.assertEquals(1, s.getStats().tutorFailures);
            AssertJUnit.assertEquals(0, s.getStats().tutorSearched);
        }
    }

    @Test
    public void triggeredSearchIsSearchedFromARebuiltTrigger() throws Exception {
        try (Stub stub = new Stub()) {
            Game game = initAndCreateGame();
            Player a = game.getPlayers().get(0);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
            addCardToZone("Batterskull", a, ZoneType.Library);
            addCardToZone("Sword of Fire and Ice", a, ZoneType.Library);
            addCardToZone("Plains", a, ZoneType.Library);
            addCard("Plains", a);
            addCard("Plains", a);
            addCardToZone("Stoneforge Mystic", a, ZoneType.Hand);
            for (int i = 0; i < 10; i++) {
                addCardToZone("Mountain", game.getPlayers().get(1), ZoneType.Library);
            }
            game.getAction().checkStateEffects(true);
            LookaheadSearch s = new LookaheadSearch(tutor(stub, 2, false));
            PlayerControllerLookahead ctrl = bind(game, s);
            Card sfm = a.getCardsIn(ZoneType.Hand).getFirst();
            SpellAbility sa = sfm.getFirstSpellAbility();
            sa.setActivatingPlayer(a);
            AssertJUnit.assertTrue(ctrl.playChosenSpellAbility(sa));
            game.getStack().resolveStack(); // the creature enters; its trigger goes on the stack
            game.getAction().checkStateEffects(true);
            game.getStack().addAllTriggeredAbilitiesToStack();
            AssertJUnit.assertFalse(game.getStack().isEmpty());
            game.getStack().resolveStack(); // the trigger's search
            s.shutdown();
            AssertJUnit.assertEquals(1, s.getStats().tutorSeen);
            AssertJUnit.assertEquals(String.valueOf(s.getStats().tutorWhy), 0, s.getStats().tutorUnsupported);
            AssertJUnit.assertEquals(1, s.getStats().tutorSearched);
            AssertJUnit.assertEquals(1, stub.rank.get());
            AssertJUnit.assertEquals("Stoneforge Mystic", stub.requests.get(0).get("tutor").getAsString());
            AssertJUnit.assertEquals(2, s.lastTutorCandidates.size());
            for (boolean ok : s.lastTutorOk) {
                AssertJUnit.assertTrue("the rebuilt trigger made the forced pick in every world", ok);
            }
            AssertJUnit.assertEquals(0, s.getStats().tutorForcedMissed);
            AssertJUnit.assertTrue(game.getStack().isEmpty());
            int equipmentInHand = 0;
            for (Card c : a.getCardsIn(ZoneType.Hand)) {
                equipmentInHand += c.isEquipment() ? 1 : 0;
            }
            AssertJUnit.assertEquals(1, equipmentInHand);
        }
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void pinMismatchRefuses() throws Exception {
        try (Stub stub = new Stub()) {
            stub.sha = "0000000000000000000000000000000000000000000000000000000000000000";
            LookaheadSearch.Config c = tutor(stub, 2, false);
            new LookaheadSearch(c);
        }
    }
}
