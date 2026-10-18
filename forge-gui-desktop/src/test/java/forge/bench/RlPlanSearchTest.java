package forge.bench;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

    // ------------------------------------------------------------------ r5-prereq-1018: S-b with the label sink

    /** Every record header of a sink file (mtgx-rl-labelsink/1: concatenated gzip members of MXL1 records). */
    static List<JsonObject> sinkHeaders(final Path f) throws Exception {
        final List<JsonObject> out = new ArrayList<>();
        if (!Files.exists(f)) {
            return out;
        }
        final byte[] all;
        try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(f))) {
            all = in.readAllBytes();
        }
        final ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            final byte[] magic = new byte[4];
            b.get(magic);
            Assert.assertEquals(new String(magic, StandardCharsets.US_ASCII), "MXL1");
            final byte[] h = new byte[b.getInt()];
            b.get(h);
            out.add(JsonParser.parseString(new String(h, StandardCharsets.UTF_8)).getAsJsonObject());
            final int pl = b.getInt();
            b.position(b.position() + pl);
        }
        return out;
    }

    static Map<String, Integer> kinds(final List<JsonObject> hs) {
        final Map<String, Integer> k = new TreeMap<>();
        for (JsonObject h : hs) {
            k.merge(h.get("kind").getAsString(), 1, Integer::sum);
        }
        return k;
    }

    private static RlActorBench.Played playFull(final RlActorBench.Cfg cfg, final int i) throws Exception {
        final RlActorBench.Played p = RlActorBench.play(cfg, "eval", RlActorBenchOpponentTest.evalGame(i,
                i % 2 == 0 ? "rl:M" : "forge", i % 2 == 0 ? "forge" : "rl:M"),
                new RlFeaturizer(RlActorBenchTest.index), RlActorBenchOpponentTest.endpoint("eval"), null, "test");
        Assert.assertNull(p.guardError);
        Assert.assertTrue(p.end.get("void").isJsonNull(), "game " + i + " void " + p.end.get("void"));
        return p;
    }

    /**
     * S-b's decisions recorded by the label sink (root rows with the plan candidates' first-step entries and the proof
     * flags), and the sink only records: the same games with it off are digest-identical.
     */
    @Test
    public void sbGamesWithTheSinkRecordAndPlayTheSame() throws Exception {
        try (FakeSearchService svc = new FakeSearchService("0".repeat(64))) {
            svc.leafValue = RlChoiceSearchTest::hashValue;
            final Path dir = Files.createTempDirectory("sb-sink-");
            final String extra = ",\"plans\":{\"samples\":3}";
            int roots = 0, planCands = 0;
            for (int i = 0; i < Integer.getInteger("rl.sbGames", 2); i++) {
                final RlActorBench.Cfg off = RlActorBenchTest.cfg("eval");
                off.search = RlChoiceSearchTest.spec(svc.address(), extra);
                final String dOff = playFull(off, i).end.get("digest").getAsString();
                final Path f = dir.resolve("g" + i + ".mxl.gz");
                final RlActorBench.Cfg on = RlActorBenchTest.cfg("eval");
                on.search = RlChoiceSearchTest.spec(svc.address(), extra + ",\"labelSink\":\"" + f + "\"");
                final RlActorBench.Played p = playFull(on, i);
                Assert.assertEquals(p.end.get("digest").getAsString(), dOff, "game " + i + ": the sink changed the game");
                final List<JsonObject> hs = sinkHeaders(f);
                System.err.println("[sb-sink] game " + i + " " + kinds(hs) + " " + p.end.getAsJsonObject("search").get("labels"));
                for (JsonObject h : hs) {
                    if (!"root".equals(h.get("kind").getAsString())) {
                        continue;
                    }
                    roots++;
                    Assert.assertTrue(h.get("sb").getAsBoolean());
                    Assert.assertTrue(h.has("target_cand") && h.has("proven") && h.has("chosen_ev"), h.toString());
                    for (com.google.gson.JsonElement e : h.getAsJsonArray("searched")) {
                        final JsonObject x = e.getAsJsonObject();
                        Assert.assertTrue(x.has("proven_win") && x.has("proven_loss") && x.has("plan"), x.toString());
                        if (x.get("plan").getAsInt() >= 0) {
                            planCands++;
                            Assert.assertTrue(x.get("entry").getAsInt() >= -1);
                        }
                    }
                }
            }
            Assert.assertTrue(roots > 0, "no root row");
            System.err.println("[sb-sink] roots " + roots + ", plan candidates " + planCands);
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
        }
    }

    /** GameState applied on the game's own thread (as the ComboPuzzle harness installs a puzzle frame). */
    static final class NowState extends forge.game.GameState {
        void applyNow(final forge.game.Game g) {
            applyGameOnThread(g);
        }
    }

    static final class HookedAction extends forge.game.GameAction {
        private final Runnable hook;

        HookedAction(final forge.game.Game g, final Runnable hook) {
            super(g);
            this.hook = hook;
        }

        @Override
        public void startGame(final forge.game.GameOutcome last, final Runnable h) {
            super.startGame(last, h != null ? h : hook);
        }
    }

    static void install(final forge.game.Game game, final String frameText) {
        try {
            final java.lang.reflect.Field f = forge.game.Game.class.getField("action");
            f.setAccessible(true);
            f.set(game, new HookedAction(game, () -> {
                final NowState gs = new NowState();
                gs.parse(java.util.Arrays.asList(frameText.split("\\R")));
                gs.applyNow(game);
            }));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A proven Doomsday line (puzzle P05: Doomsday and Thassa's Oracle in hand) taken by S-b's short-circuit: its root
     * row's hard target is the line's first step (Doomsday), the line's later steps are plan-step rows on the frames the
     * seat would have been sent, and its forced pile picks are line-choice rows. The sink only records.
     */
    @Test
    public void aProvenLineIsLabelledStepByStep() throws Exception {
        final String frame = "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\n"
                + "humanlandsplayed=0\nailandsplayed=0\naihand=\nailibrary=" + String.join(";", java.util.Collections.nCopies(20, "Forest"))
                + "\naigraveyard=\naiexile=\naibattlefield=Forest;Forest;Forest;Forest;Grizzly Bears\n"
                + "humanhand=Doomsday;Thassa's Oracle\nhumanbattlefield=Jace, the Mind Sculptor|Counters:LOYALTY=3;"
                + "Brazen Borrower;Underground Sea;Underground Sea;Underground Sea;Island;Island\nhumangraveyard=\n"
                + "humanexile=\nhumanlibrary=Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;"
                + "Island\n";
        try (FakeSearchService svc = new FakeSearchService("0".repeat(64))) {
            svc.leafValue = RlChoiceSearchTest::hashValue;
            RlActorBench.KNOWLEDGE_TAP = g -> {
                install(g, frame);
                return new BenchSession.KnowledgeObserver() {
                    @Override
                    public void onReveal(final forge.game.Game gg, final forge.game.player.Player v,
                            final List<forge.game.card.Card> cards, final forge.game.zone.ZoneType z,
                            final forge.game.player.Player owner) {
                    }

                    @Override
                    public void onLook(final forge.game.Game gg, final forge.game.player.Player v,
                            final List<forge.game.card.Card> cards, final forge.game.zone.ZoneType dest) {
                    }
                };
            };
            try {
                final Path dir = Files.createTempDirectory("sb-line-");
                final Path f = dir.resolve("line.mxl.gz");
                final String extra = ",\"plans\":{\"samples\":0}";
                final RlActorBench.Cfg off = RlActorBenchTest.cfg("eval");
                off.search = RlChoiceSearchTest.spec(svc.address(), extra);
                final String dOff = playFull(off, 0).end.get("digest").getAsString();
                final RlActorBench.Cfg on = RlActorBenchTest.cfg("eval");
                on.search = RlChoiceSearchTest.spec(svc.address(), extra + ",\"labelSink\":\"" + f + "\"");
                final RlActorBench.Played p = playFull(on, 0);
                Assert.assertEquals(p.end.get("digest").getAsString(), dOff, "the sink changed the game");
                final List<JsonObject> hs = sinkHeaders(f);
                final JsonObject s = p.end.getAsJsonObject("search");
                System.err.println("[sb-line] " + kinds(hs) + " labels " + s.get("labels") + " result " + p.end.get("result"));
                JsonObject sc = null;
                int steps = 0, choices = 0;
                for (JsonObject h : hs) {
                    final String k = h.get("kind").getAsString();
                    if ("root".equals(k) && h.has("short_circuit")) {
                        sc = h;
                    } else if ("plan_step".equals(k)) {
                        steps++;
                        Assert.assertEquals(h.get("plan_kind").getAsString(), "line");
                        Assert.assertTrue(h.get("proven").getAsBoolean());
                        Assert.assertTrue(h.get("target_cand").getAsInt() >= 0);
                        System.err.println("[sb-line] step " + h);
                    } else if ("line_choice".equals(k)) {
                        choices++;
                        System.err.println("[sb-line] choice " + h);
                    }
                }
                Assert.assertNotNull(sc, "the short-circuit root row");
                Assert.assertTrue(sc.get("proven").getAsBoolean());
                Assert.assertTrue(sc.get("target_cand").getAsInt() >= 0, sc.toString());
                Assert.assertTrue(steps > 0, "no plan-step row");
                Assert.assertTrue(choices > 0, "no line-choice row");
            } finally {
                RlActorBench.KNOWLEDGE_TAP = null;
            }
            Assert.assertEquals(svc.errors.get(), 0L, "the service refused a request");
        }
    }
}
