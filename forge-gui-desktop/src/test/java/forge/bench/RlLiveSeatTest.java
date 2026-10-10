package forge.bench;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.LookaheadSearch;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.LobbyPlayerPolicy;
import forge.bench.rl.RlLiveSeat;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import forge.util.MyRandom;

/**
 * Lane live-sc-1009: the interactive server's policy seat ({@link RlLiveSeat}, off unless
 * {@code -Dforge.interactive.policySearch} is set). Its spec defaults are the S1 read's S-c arm; it binds only after a
 * pinned HELLO and otherwise falls back at boot; a whole game against Forge AI completes with it; a service that dies
 * mid-game degrades the seat to Forge AI plus the K8 look-ahead and the game still ends normally (never as the seat's
 * void, never stuck). Lane sc-wallguard-1009: play-out seats whose service answers late keep searched decisions past
 * their budget; each one is capped within the wall's bound, plays the policy's own choice, and the game ends normally.
 * Plays real Forge games: run inside a broker test lease, cwd = forge-gui with res/.
 */
public class RlLiveSeatTest {
    static final String SHA = "7f7d160785e58bd1da37435f8ed71e145d761fb34f9d45b40809880982388465";
    static Path indexFile;
    static List<Path> decks;

    @BeforeClass
    public static void setUp() throws Exception {
        RlActorBench.ensureBooted();
        decks = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(forge.bench.rl.RlWireSchemaTest.res().resolve("decks"))) {
            s.filter(p -> p.toString().endsWith(".dck")).sorted().forEach(decks::add);
        }
        final TreeSet<String> names = new TreeSet<>();
        for (Path f : decks) {
            final Deck d = DeckSerializer.fromFile(f.toFile());
            for (java.util.Map.Entry<PaperCard, Integer> e : d.getMain()) {
                names.add(e.getKey().getName());
            }
        }
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : names) {
            sb.append(n).append('\t').append(i++).append('\n');
        }
        indexFile = Files.createTempFile("live-sc-index-", ".tsv");
        Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String spec(final String server, final String extra) {
        return "server=" + server + ",policySha=" + SHA + ",cardIndex=" + indexFile + (extra.isEmpty() ? "" : "," + extra);
    }

    // ------------------------------------------------------------------------------------------------ spec

    @Test
    public void specDefaultsAreTheReadsScArmUnderTheLiveBudget() {
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", ""));
        Assert.assertEquals(s.search.worlds, 8);
        Assert.assertEquals(s.search.breadth, 4);
        Assert.assertEquals(s.search.horizon, 2);
        Assert.assertEquals(s.search.departZ, 1.645);
        Assert.assertEquals(s.search.margin, 0.0);
        Assert.assertEquals(s.search.leaf, "value");
        Assert.assertEquals(s.search.playout, "policy");
        Assert.assertFalse(s.search.playoutSample);
        Assert.assertEquals(s.search.deadEtb, "on");
        Assert.assertEquals(s.search.zeroX, "on");
        Assert.assertEquals(s.search.crewNoop, "on");
        Assert.assertEquals(s.search.cpuCapMs, 0L, "a live game is never voided by a CPU cap");
        Assert.assertEquals(s.search.budgetMs, 8000L);
        Assert.assertEquals(s.search.policySha, SHA);
        Assert.assertEquals(s.aiFixes0928, AiFixes.Mode.OFF, "the seat's own Forge AI as in the read");
        Assert.assertEquals(s.capActions, 40);
        Assert.assertEquals(s.maxDecisions, 3000);
        final RlLiveSeat.Spec t = RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "budgetMs=6000,threads=8,log=0"));
        Assert.assertEquals(t.search.budgetMs, 6000L);
        Assert.assertEquals(t.search.threads, 8);
        Assert.assertFalse(t.log);
    }

    @Test
    public void specRefusesAMissingPinAndUnknownKeys() {
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse("server=127.0.0.1:1,cardIndex=" + indexFile));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse("server=127.0.0.1:1,cardIndex=" + indexFile + ",policySha=abc"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "wolrds=8")));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(spec("127.0.0.1:1", "leaf=magic")));
    }

    // ------------------------------------------------------------------------------------------------ boot

    @Test
    public void bootFallsBackWithoutTheServiceOrOnAPinMismatch() throws Exception {
        final int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        Assert.assertNull(RlLiveSeat.connect(spec("127.0.0.1:" + closedPort, ""), "t"), "no service");
        try (FakeSearchService wrong = new FakeSearchService("0".repeat(64))) {
            Assert.assertNull(RlLiveSeat.connect(spec(wrong.address(), ""), "t"), "another checkpoint");
        }
        Assert.assertNull(RlLiveSeat.connect("server=127.0.0.1:1", "t"), "a bad spec");
        try (FakeSearchService ok = new FakeSearchService(SHA)) {
            final RlLiveSeat s = RlLiveSeat.connect(spec(ok.address(), ""), "t");
            Assert.assertNotNull(s);
            s.finish();
        }
    }

    // ------------------------------------------------------------------------------------------------ games

    /** One game, Forge AI Default (seat 0) vs the policy seat (seat 1), on a "Game" thread as the interactive server's. */
    static Game play(final RlLiveSeat live, final long seed) throws Exception {
        final LobbyPlayerAi forge = new LobbyPlayerAi("Seat0", null);
        forge.setAiProfile("Default");
        final LobbyPlayerPolicy pol = live.lobby("Default Forge", 1, "Default");
        final RegisteredPlayer r0 = new RegisteredPlayer(RlActorBench.deck(decks.get(0).toString()));
        r0.setPlayer(forge);
        final RegisteredPlayer r1 = new RegisteredPlayer(RlActorBench.deck(decks.get(1).toString()));
        r1.setPlayer(pol);
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setGamesPerMatch(1);
        final Match match = new Match(rules, Arrays.asList(r0, r1), "live-sc test");
        final AtomicReference<Game> out = new AtomicReference<>();
        final AtomicReference<Throwable> err = new AtomicReference<>();
        final Thread t = new Thread(() -> {
            try {
                MyRandom.setRandom(new Random(seed));
                final Game game = match.createGame();
                game.AI_TIMEOUT = 60;
                final LookaheadSearch.Config k8 = new LookaheadSearch.Config();
                k8.worlds = 2;
                k8.breadth = 4;
                k8.horizonTurns = 2;
                k8.threads = 2;
                k8.budgetMs = 4000;
                k8.departZ = 1.645;
                k8.decisionLog = true;
                k8.seed = seed * 31 + 1;
                live.bind(game, 1, seed, k8, AiFixes.Mode.ON);
                out.set(game);
                match.startGame(game, null);
            } catch (Throwable e) {
                err.set(e);
            } finally {
                live.finish();
            }
        }, "Game-live-sc-test");
        t.start();
        t.join(20 * 60_000L);
        Assert.assertFalse(t.isAlive(), "the game must end");
        if (err.get() != null) {
            throw new AssertionError("the game thread failed", err.get());
        }
        return out.get();
    }

    static String captureErr(final ThrowingRunnable r) throws Exception {
        final PrintStream was = System.err;
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final PrintStream tee = new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(final int b) {
                buf.write(b);
                was.write(b);
            }

            @Override
            public void write(final byte[] b, final int off, final int len) {
                buf.write(b, off, len);
                was.write(b, off, len);
            }
        }, true, StandardCharsets.UTF_8);
        System.setErr(tee);
        try {
            r.run();
        } finally {
            System.setErr(was);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    public void aWholeGameCompletesWithThePolicySeatAndItsSearch() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            final RlLiveSeat live = RlLiveSeat.connect(spec(svc.address(), "worlds=2,threads=2,budgetMs=4000"), "t");
            Assert.assertNotNull(live);
            final Game[] g = new Game[1];
            final String err = captureErr(() -> g[0] = play(live, 722159001L));
            Assert.assertTrue(g[0].isGameOver());
            Assert.assertNotNull(g[0].getOutcome());
            Assert.assertFalse(live.degraded(), "a healthy service never degrades the seat");
            Assert.assertTrue(svc.decides.get() > 0, "the seat decides through the service");
            Assert.assertTrue(svc.scores.get() > 0, "the search asks for the prior");
            Assert.assertTrue(err.contains("[lookahead-decision] {\"seat\":\"policy\""), "decision lines for the host");
            Assert.assertTrue(err.contains("[policy-seat] {\"end\":true,\"degraded\":false"), "an end summary");
        }
    }

    @Test
    public void aServiceThatDiesMidGameDegradesTheSeatToK8AndTheGameStillEnds() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            svc.failAfterDecides = 150;
            final RlLiveSeat live = RlLiveSeat.connect(spec(svc.address(), "worlds=2,threads=2,budgetMs=4000"), "t");
            Assert.assertNotNull(live);
            final Game[] g = new Game[1];
            final String err = captureErr(() -> g[0] = play(live, 722159002L));
            Assert.assertTrue(g[0].isGameOver());
            Assert.assertNotNull(g[0].getOutcome());
            Assert.assertTrue(live.degraded(), "the seat degrades when its service goes away");
            Assert.assertTrue(err.contains("[policy-seat] {\"degraded\":true"), "the degrade is logged");
            Assert.assertTrue(err.contains("\"fallback\":\"k8\""), "the degraded seat plays the K8 look-ahead");
            // a degraded seat's priority decisions go through K8 (its decision lines carry no policy seat marker)
            final long k8Lines = err.lines().filter(l -> l.startsWith("[lookahead-decision] {\"decision\"")).count();
            Assert.assertTrue(k8Lines > 0 || g[0].getPhaseHandler().getTurn() <= 2,
                    "K8 decision lines after the degrade (" + k8Lines + ")");
            // the game was not ended by the seat (a bench seat ends a faulted game as a draw)
            Assert.assertNotEquals(g[0].getOutcome().getWinCondition(), forge.game.GameEndReason.Draw);
        }
    }

    /**
     * Lane sc-wallguard-1009 (the S-c path end to end): every DECIDE of a play-out seat takes 150 ms, so the play-outs
     * run past a 600 ms budget; each such decision stops at its play-outs' next step or request (the endpoint's refusal
     * itself: LookaheadBudgetTest.policyPlayoutEndpointRefusesRequestsPastTheBudget), is capped within the wall's bound,
     * and plays the policy's own choice; the seat never degrades and the game ends normally.
     */
    @Test
    public void playOutsPastTheBudgetAreStoppedAtTheirNextRequestAndThePolicyPlays() throws Exception {
        final long budgetMs = 600;
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            svc.playoutDecideDelayMs = 150;
            final RlLiveSeat live = RlLiveSeat.connect(spec(svc.address(), "worlds=2,threads=2,budgetMs=" + budgetMs), "t");
            Assert.assertNotNull(live);
            final Game[] g = new Game[1];
            final String err = captureErr(() -> g[0] = play(live, 722159003L));
            Assert.assertTrue(g[0].isGameOver());
            Assert.assertNotNull(g[0].getOutcome());
            Assert.assertFalse(live.degraded(), "slow play-outs never degrade the seat");
            Assert.assertNotEquals(g[0].getOutcome().getWinCondition(), forge.game.GameEndReason.Draw);
            int capped = 0, cooperative = 0;
            for (String l : err.lines().filter(x -> x.startsWith("[lookahead-decision] {\"seat\":\"policy\""))
                    .collect(java.util.stream.Collectors.toList())) {
                final com.google.gson.JsonObject d = com.google.gson.JsonParser.parseString(
                        l.substring("[lookahead-decision] ".length())).getAsJsonObject();
                if (d.get("capped").getAsBoolean()) {
                    capped++;
                    final double ms = d.get("searchMs").getAsDouble();
                    Assert.assertFalse(d.get("departed").getAsBoolean(), "a capped decision plays the policy's choice");
                    // the wall's bound (plus scheduling slack on a loaded host)
                    Assert.assertTrue(ms < budgetMs + LookaheadSearch.WALL_GRACE_MS + 1500, "bounded: " + l);
                    if (!d.has("wall") && ms < budgetMs + LookaheadSearch.WALL_GRACE_MS) {
                        cooperative++;
                    }
                }
            }
            Assert.assertTrue(capped > 0, "slow play-outs keep searches past the budget");
            Assert.assertTrue(cooperative > 0, "capped decisions stopped by the play-outs themselves, before the wall");
        }
    }
}
