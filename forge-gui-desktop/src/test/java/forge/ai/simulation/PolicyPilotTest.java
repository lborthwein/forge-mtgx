package forge.ai.simulation;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import forge.LobbyPlayer;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * L2 policy P1 pilot (lane l2-fork-1001) with a mock {@link PolicyClient}: a forced cast, a veto (Forge AI re-asked),
 * a react counter at the opponent's spell, Forge AI's own counter vetoed, the land swap, never an illegal force, the
 * fallback on a service failure (no retry that turn), the pin, and option-off identity (classes off, an unbound policy
 * seat and shadow all play exactly as Forge AI over several turns).
 */
public class PolicyPilotTest extends AITest {

    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** A mock P1 service: p per (kind, card name); "fail" makes every call throw. */
    static final class Mock extends PolicyClient {
        final List<JsonObject> requests = new ArrayList<>();
        final List<String> paths = new ArrayList<>();
        BiFunction<String, String, Double> p = (kind, name) -> null;
        boolean fail = false;
        String body = null;
        Game game;

        Mock() {
            super(null, 2000);
        }

        @Override
        protected String post(String path, String reqBody) throws Exception {
            final JsonObject req = JsonParser.parseString(reqBody).getAsJsonObject();
            requests.add(req);
            paths.add(path);
            if (fail) {
                throw new IOException("mock: service down");
            }
            if (body != null) {
                return body;
            }
            final String kind = path.substring(path.lastIndexOf('/') + 1);
            final JsonArray out = new JsonArray();
            for (JsonElement ce : req.getAsJsonArray("cards")) {
                final JsonObject c = ce.getAsJsonObject();
                final Card card = game.findById(c.get("fid").getAsInt());
                final JsonObject o = new JsonObject();
                o.add("id", c.get("id"));
                o.add("fid", c.get("fid"));
                if ("plan".equals(kind)) {
                    o.addProperty("cast", p.apply("cast", card.getName()));
                    o.addProperty("land", p.apply("land", card.getName()));
                } else {
                    o.addProperty("react", p.apply("react", card.getName()));
                }
                out.add(o);
            }
            final JsonObject r = new JsonObject();
            r.addProperty("schema", PolicyClient.RESPONSE_SCHEMA);
            r.addProperty("kind", kind);
            r.add("cards", out);
            r.add("unknownCards", new JsonArray());
            r.addProperty("truncated", 0);
            final JsonObject m = new JsonObject();
            m.addProperty("checkpointSha256", SHA);
            r.add("model", m);
            return r.toString();
        }
    }

    private static PolicyPilot.Config config() {
        final PolicyPilot.Config c = new PolicyPilot.Config();
        c.url = "mock";
        c.checkpointSha256 = SHA;
        c.seed = 719_268_000L;
        c.log = true;
        return c;
    }

    /** Two plain Forge AI seats (no simulation option), seat 0 = {@code seat0}. */
    private Game game(LobbyPlayer seat0) {
        final List<RegisteredPlayer> players = Lists.newArrayList();
        final Deck d = new Deck();
        players.add(new RegisteredPlayer(d).setPlayer(seat0));
        players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("p1", null)));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Match match = new Match(rules, players, "Test");
        final Game game = new Game(players, rules, match);
        game.setAge(GameStage.Play);
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        game.AI_CAN_USE_TIMEOUT = false;
        game.AI_TIMEOUT = 600;
        return game;
    }

    private Game game() {
        return game(new LobbyPlayerAi("p0", null));
    }

    private static SpellAbility putOnStack(Game game, Card card, Player caster) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        game.getStackZone().add(card);
        game.getStack().add(sa);
        return sa;
    }

    private static Card inHand(Player p, String name) {
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static List<SpellAbility> one(SpellAbility sa) {
        final List<SpellAbility> l = new ArrayList<>();
        l.add(sa);
        return l;
    }

    @Test
    public void ownTurnForcesTheBestCastablePlanCardOncePerTurnAndNeverAnInstant() {
        final Game game = game();
        final Player a = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        for (int i = 0; i < 4; i++) {
            addCard("Forest", a);
        }
        final Card bears = addCardToZone("Grizzly Bears", a, ZoneType.Hand);
        final Card growth = addCardToZone("Giant Growth", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final Mock mock = new Mock();
        mock.game = game;
        mock.p = (k, n) -> "cast".equals(k) ? (n.equals("Giant Growth") ? 0.95 : n.equals("Grizzly Bears") ? 0.9 : 0.1) : null;
        final PolicyPilot pilot = new PolicyPilot(config(), mock);
        pilot.bind(game, a);
        final PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        final String before = LookaheadSearch.fingerprint(game);

        // Forge AI passes (null): the best castable sorcery-speed plan card is forced; the instant is never forced.
        final List<SpellAbility> ans = pilot.decide(ctrl, null);
        AssertJUnit.assertNotNull(ans);
        AssertJUnit.assertSame(bears, ans.get(0).getHostCard());
        AssertJUnit.assertTrue(ans.get(0).isSpell());
        AssertJUnit.assertTrue(PolicyPilot.legal(ans.get(0), a, false));
        AssertJUnit.assertEquals(1, pilot.getStats().force);
        AssertJUnit.assertEquals(1, pilot.getStats().planCalls);
        AssertJUnit.assertEquals("/v1/policy/plan", mock.paths.get(0));
        final JsonObject req = mock.requests.get(0);
        AssertJUnit.assertEquals(PolicyClient.REQUEST_SCHEMA, req.get("schema").getAsString());
        AssertJUnit.assertEquals(2, req.getAsJsonArray("cards").size());
        AssertJUnit.assertTrue(req.has("root") && req.has("deck") && req.has("mulligans"));
        AssertJUnit.assertEquals("deciding changes nothing on the live game", before, LookaheadSearch.fingerprint(game));

        // Same turn: the plan is cached (no new call), Bears was forced once, Giant Growth is an instant -> pass.
        AssertJUnit.assertNull(pilot.decide(ctrl, null));
        AssertJUnit.assertEquals(1, pilot.getStats().planCalls);
        AssertJUnit.assertEquals(1, pilot.getStats().force);
        AssertJUnit.assertNotNull(growth);
        AssertJUnit.assertFalse(pilot.getStats().toJson().get("policyDigest").getAsString().isEmpty());
    }

    @Test
    public void ownTurnNeverForcesAnIllegalCast() {
        final Game game = game();
        final Player a = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        addCardToZone("Grizzly Bears", a, ZoneType.Hand); // no lands: cannot pay
        final Mock mock = new Mock();
        mock.game = game;
        mock.p = (k, n) -> "cast".equals(k) ? 0.99 : null;
        final PolicyPilot pilot = new PolicyPilot(config(), mock);
        pilot.bind(game, a);
        AssertJUnit.assertNull(pilot.decide((PlayerControllerAi) a.getController(), null));
        AssertJUnit.assertEquals(0, pilot.getStats().force);
        AssertJUnit.assertEquals(1, pilot.getStats().notCastable);
    }

    @Test
    public void ownTurnVetoReasksForgeWithoutTheVetoedCard() {
        final Game game = game();
        final Player a = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        for (int i = 0; i < 6; i++) {
            addCard("Forest", a);
        }
        addCardToZone("Grizzly Bears", a, ZoneType.Hand);
        addCardToZone("Centaur Courser", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        final List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        AssertJUnit.assertNotNull("Forge AI casts a creature in main 2", def);
        final String vetoed = def.get(0).getHostCard().getName();
        final String other = vetoed.equals("Grizzly Bears") ? "Centaur Courser" : "Grizzly Bears";
        final Mock mock = new Mock();
        mock.game = game;
        mock.p = (k, n) -> "cast".equals(k) ? (n.equals(vetoed) ? 0.1 : 0.9) : null;
        final PolicyPilot pilot = new PolicyPilot(config(), mock);
        pilot.bind(game, a);

        final List<SpellAbility> ans = pilot.decide(ctrl, def);
        AssertJUnit.assertNotNull(ans);
        AssertJUnit.assertEquals(other, ans.get(0).getHostCard().getName());
        AssertJUnit.assertEquals(1, pilot.getStats().veto + pilot.getStats().force);
        AssertJUnit.assertEquals(1, pilot.getStats().vetoConsults);

        // The veto predicate is cleared after the re-ask: Forge AI alone proposes the vetoed card again.
        AssertJUnit.assertEquals(vetoed, ctrl.chooseSpellAbilityToPlay().get(0).getHostCard().getName());
    }

    @Test
    public void landSwapPlaysThePolicysLand() {
        final Game game = game();
        final Player a = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        addCardToZone("Forest", a, ZoneType.Hand);
        addCardToZone("Island", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        final List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        AssertJUnit.assertTrue("Forge AI plays a land", def != null && def.get(0).isLandAbility());
        final String forgeLand = def.get(0).getHostCard().getName();
        final String other = forgeLand.equals("Forest") ? "Island" : "Forest";
        final Mock mock = new Mock();
        mock.game = game;
        mock.p = (k, n) -> "land".equals(k) ? (n.equals(other) ? 0.8 : 0.2) : null;
        final PolicyPilot pilot = new PolicyPilot(config(), mock);
        pilot.bind(game, a);
        final List<SpellAbility> ans = pilot.decide(ctrl, def);
        AssertJUnit.assertTrue(ans.get(0).isLandAbility());
        AssertJUnit.assertEquals(other, ans.get(0).getHostCard().getName());
        AssertJUnit.assertEquals(1, pilot.getStats().landSwap);
    }

    @Test
    public void reactCountersTheOpponentsSpell() {
        final Game game = game();
        final Player a = game.getPlayers().get(0);
        final Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, b);
        addCard("Island", a);
        addCard("Island", a);
        final Card counter = addCardToZone("Counterspell", a, ZoneType.Hand);
        addCardToZone("Grizzly Bears", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final Card theirs = addCardToZone("Grizzly Bears", b, ZoneType.Hand);
        final SpellAbility theirSa = putOnStack(game, theirs, b);
        final Mock mock = new Mock();
        mock.game = game;
        mock.p = (k, n) -> "react".equals(k) ? (n.equals("Counterspell") ? 0.9 : 0.05) : null;
        final PolicyPilot pilot = new PolicyPilot(config(), mock);
        pilot.bind(game, a);
        final String before = LookaheadSearch.fingerprint(game);

        final List<SpellAbility> ans = pilot.decide((PlayerControllerAi) a.getController(), null);
        AssertJUnit.assertNotNull(ans);
        AssertJUnit.assertSame(counter, ans.get(0).getHostCard());
        AssertJUnit.assertSame("the counter targets the top stack object", theirSa, ans.get(0).getTargets().getFirstTargetedSpell());
        AssertJUnit.assertEquals(1, pilot.getStats().reactCast);
        AssertJUnit.assertEquals(1, pilot.getStats().reactCalls);
        AssertJUnit.assertEquals("/v1/policy/react", mock.paths.get(0));
        final JsonObject ctx = mock.requests.get(0).getAsJsonObject("context");
        AssertJUnit.assertEquals("stack", ctx.get("trigger").getAsString());
        AssertJUnit.assertTrue(ctx.get("oppCast").isJsonArray());
        AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
        AssertJUnit.assertEquals(1, pilot.getStats().reactRoots);
    }

    @Test
    public void reactVetoesForgesOwnCounterBelowThreshold() {
        final Game game = game();
        final Player a = game.getPlayers().get(0);
        final Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, b);
        addCard("Island", a);
        addCard("Island", a);
        final Card counter = addCardToZone("Counterspell", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final SpellAbility theirSa = putOnStack(game, addCardToZone("Grizzly Bears", b, ZoneType.Hand), b);
        // Forge AI's proposal (as the counter AI would make it): Counterspell at their spell.
        final SpellAbility cs = counter.getFirstSpellAbility();
        cs.setActivatingPlayer(a);
        cs.getTargets().add(theirSa);
        final Mock mock = new Mock();
        mock.game = game;
        mock.p = (k, n) -> "react".equals(k) ? 0.1 : null;
        final PolicyPilot pilot = new PolicyPilot(config(), mock);
        pilot.bind(game, a);
        final List<SpellAbility> ans = pilot.decide((PlayerControllerAi) a.getController(), one(cs));
        AssertJUnit.assertTrue("vetoed: Forge AI re-asked without Counterspell",
                ans == null || ans.isEmpty() || ans.get(0).getHostCard() != counter);
        AssertJUnit.assertEquals(1, pilot.getStats().reactVeto);
        AssertJUnit.assertEquals(0, pilot.getStats().reactCast);
    }

    @Test
    public void serviceFailureFallsBackToForgeAndIsCountedOncePerTurn() {
        final Game game = game();
        final Player a = game.getPlayers().get(0);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, a);
        for (int i = 0; i < 4; i++) {
            addCard("Forest", a);
        }
        addCardToZone("Grizzly Bears", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        final List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        final Mock mock = new Mock();
        mock.game = game;
        mock.fail = true;
        final PolicyPilot pilot = new PolicyPilot(config(), mock);
        pilot.bind(game, a);
        AssertJUnit.assertSame(def, pilot.decide(ctrl, def));
        AssertJUnit.assertNull(pilot.decide(ctrl, null)); // Forge passes: no plan this turn, nothing forced
        AssertJUnit.assertEquals(1, pilot.getStats().failures);
        AssertJUnit.assertEquals(1, pilot.getStats().planCalls);
        AssertJUnit.assertEquals(0, pilot.getStats().changed());
        AssertJUnit.assertTrue(pilot.getStats().lastError.contains("service down"));

        // A malformed answer (wrong length) is a failure too.
        final Mock bad = new Mock();
        bad.game = game;
        bad.body = "{\"schema\":\"" + PolicyClient.RESPONSE_SCHEMA + "\",\"kind\":\"plan\",\"cards\":[]}";
        final PolicyPilot p2 = new PolicyPilot(config(), bad);
        p2.bind(game, a);
        AssertJUnit.assertSame(def, p2.decide(ctrl, def));
        AssertJUnit.assertEquals(1, p2.getStats().failures);
        AssertJUnit.assertTrue(p2.getStats().lastError.contains("cards length"));
    }

    @Test
    public void pinRefusesAnotherCheckpointOrNoService() throws Exception {
        final HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/v1/health", ex -> {
            final byte[] b = ("{\"checkpointSha256\":\"" + SHA + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        s.start();
        try {
            final String url = "http://127.0.0.1:" + s.getAddress().getPort();
            AssertJUnit.assertEquals(SHA, PolicyClient.checkHealth(url, SHA, 2000));
            try {
                PolicyClient.checkHealth(url, "ffff", 2000);
                AssertJUnit.fail("another pin must be refused");
            } catch (IllegalStateException expected) {
                AssertJUnit.assertTrue(expected.getMessage().contains("pinned ffff"));
            }
            try {
                PolicyClient.checkHealth(url, null, 2000);
                AssertJUnit.fail("a missing pin must be refused");
            } catch (IllegalStateException expected) {
                // ok
            }
        } finally {
            s.stop(0);
        }
        try {
            PolicyClient.checkHealth("http://127.0.0.1:9", SHA, 500);
            AssertJUnit.fail("an unreachable service must be refused");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    // ------------------------------------------------------------------ option-off identity

    private static final String[] LIB = {"Forest", "Grizzly Bears", "Island", "Counterspell", "Forest", "Centaur Courser",
            "Island", "Giant Growth", "Forest", "Hill Giant", "Mountain", "Shock"};

    /**
     * Several turns of Forge AI vs Forge AI from a fixed position and seed; seat 0 is {@code seat0}. Returns the
     * fingerprint at every turn start plus the final one.
     */
    private String play(LobbyPlayer seat0, java.util.function.Consumer<Game> bind) {
        forge.util.IdScope.open();
        try {
            MyRandom.setRandom(new Random(719_268_001L));
            final Game game = game(seat0);
            final Player a = game.getPlayers().get(0);
            final Player b = game.getPlayers().get(1);
            game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, a);
            for (Player p : new Player[] {a, b}) {
                for (int i = 0; i < 24; i++) {
                    addCardToZone(LIB[i % LIB.length], p, ZoneType.Library);
                }
                for (String n : new String[] {"Forest", "Forest", "Island", "Island", "Mountain"}) {
                    addCard(n, p);
                }
                for (String n : new String[] {"Grizzly Bears", "Counterspell", "Forest", "Giant Growth", "Shock", "Centaur Courser"}) {
                    addCardToZone(n, p, ZoneType.Hand);
                }
            }
            game.getAction().checkStateEffects(true);
            if (bind != null) {
                bind.accept(game);
            }
            final StringBuilder sb = new StringBuilder();
            for (int t = 0; t < 6 && !game.isGameOver(); t++) {
                sb.append(LookaheadSearch.fingerprint(game));
                playUntilNextTurn(game);
            }
            sb.append(LookaheadSearch.fingerprint(game));
            return sb.toString();
        } finally {
            forge.util.IdScope.close();
        }
    }

    @Test
    public void optionOffIdentity() {
        final String forge = play(new LobbyPlayerAi("p0", null), null);
        // A policy seat with no pilot bound is Forge AI.
        AssertJUnit.assertEquals(forge, play(new LobbyPlayerPolicy("p0"), null));

        // Every class off: the pilot is consulted at every priority and never calls or changes anything.
        final Mock off = new Mock();
        final PolicyPilot.Config oc = config();
        oc.cast = oc.land = oc.react = false;
        final PolicyPilot pOff = new PolicyPilot(oc, off);
        final LobbyPlayerPolicy lOff = new LobbyPlayerPolicy("p0");
        AssertJUnit.assertEquals(forge, play(lOff, g -> {
            off.game = g;
            lOff.bind(g, g.getPlayers().get(0), pOff);
        }));
        AssertJUnit.assertTrue(pOff.getStats().decisions > 0);
        AssertJUnit.assertEquals(0, off.requests.size());

        // Shadow with opinions that would change play: every call made, nothing changed on the board.
        final Mock sh = new Mock();
        sh.p = (k, n) -> "land".equals(k) ? (n.equals("Island") ? 0.9 : 0.1) : (n.equals("Counterspell") ? 0.95 : 0.05);
        final PolicyPilot.Config sc = config();
        sc.shadow = true;
        final PolicyPilot pSh = new PolicyPilot(sc, sh);
        final LobbyPlayerPolicy lSh = new LobbyPlayerPolicy("p0");
        AssertJUnit.assertEquals(forge, play(lSh, g -> {
            sh.game = g;
            lSh.bind(g, g.getPlayers().get(0), pSh);
        }));
        final PolicyPilot.Stats st = pSh.getStats();
        System.err.println("[policy-test] shadow stats " + st.toJson());
        AssertJUnit.assertEquals(0, st.changed());
        AssertJUnit.assertTrue("shadow made calls", st.planCalls + st.reactCalls > 0);
        AssertJUnit.assertTrue("shadow saw changes it would make: " + st.toJson(),
                st.wouldVeto + st.wouldForce + st.wouldLandSwap + st.wouldReactCast + st.wouldReactVeto > 0);

        // On: the same opinions change play, legally and reproducibly (two runs, same turn-start fingerprints).
        final String[] on = new String[2];
        final PolicyPilot.Stats[] ons = new PolicyPilot.Stats[2];
        for (int r = 0; r < 2; r++) {
            final Mock m = new Mock();
            m.p = sh.p;
            final PolicyPilot pOn = new PolicyPilot(config(), m);
            final LobbyPlayerPolicy lOn = new LobbyPlayerPolicy("p0");
            on[r] = play(lOn, g -> {
                m.game = g;
                lOn.bind(g, g.getPlayers().get(0), pOn);
            });
            ons[r] = pOn.getStats();
        }
        System.err.println("[policy-test] on stats " + ons[0].toJson());
        AssertJUnit.assertEquals(on[0], on[1]);
        AssertJUnit.assertEquals(ons[0].digest, ons[1].digest);
        AssertJUnit.assertEquals(ons[0].toJson().get("changed").toString(), ons[1].toJson().get("changed").toString());
        AssertJUnit.assertEquals(ons[0].events, ons[1].events);
        AssertJUnit.assertTrue("on changes decisions", ons[0].changed() > 0);
        AssertJUnit.assertFalse("and so the game", forge.equals(on[0]));

        // The step-2 hook is wired but refused.
        final LookaheadSearch.Config lc = new LookaheadSearch.Config();
        AssertJUnit.assertFalse(lc.toJson().toString().contains("policy"));
        lc.policyRollouts = true;
        try {
            new LookaheadSearch(lc);
            AssertJUnit.fail("policyRollouts must be refused until step 2 is built");
        } catch (IllegalStateException expected) {
            // ok
        }
    }
}
