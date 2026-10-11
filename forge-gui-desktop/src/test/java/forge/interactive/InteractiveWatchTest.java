package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlLiveSeat;
import forge.bench.rl.RlReviewObserver;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * WATCH tables (lane forge-bot-tables-1011): an AI-vs-AI game recorded per seat ({@code watchSeats}, {@code seatAi}), the
 * priority-decision hook it is framed by, and the review observer on an AI seat. Plays real Forge games: run inside a broker
 * test lease, cwd = forge-gui with res/, with {@code -Dforge.bench.sequentialAi=true}.
 *
 * <p>Each game starts with Forge's process-wide id counters where the first one found them ({@link Ids}): a watch JVM plays
 * one game, so two runs of one config in this JVM are compared as two processes would be.
 */
public class InteractiveWatchTest extends AITest {
    private static final String SHA = "7f7d160785e58bd1da37435f8ed71e145d761fb34f9d45b40809880982388465";
    private static final String CHEAP_LOOKAHEAD = "worlds=2,breadth=2,horizon=1,threads=1,budgetMs=0,aiFixes0928=on";
    private static Path deck0;
    private static Path deck1;
    private static Path indexFile;

    // ------------------------------------------------------------------------------------------------ fixtures

    private static synchronized void fixtures() throws Exception {
        if (deck0 != null) {
            return;
        }
        final Path decks = forge.bench.rl.RlWireSchemaTest.res().resolve("decks").toAbsolutePath().normalize();
        deck0 = decks.resolve("extC64b-pod00-p0.dck");
        deck1 = decks.resolve("extC64b-pod00-p1.dck");
        final TreeSet<String> names = new TreeSet<>();
        for (Path f : List.of(deck0, deck1)) {
            final Deck d = DeckSerializer.fromFile(f.toFile());
            for (Map.Entry<PaperCard, Integer> e : d.getMain()) {
                names.add(e.getKey().getName());
            }
        }
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : names) {
            sb.append(n).append('\t').append(i++).append('\n');
        }
        indexFile = Files.createTempFile("watch-index-", ".tsv");
        Files.write(indexFile, sb.toString().getBytes(StandardCharsets.UTF_8));
        Ids.take();
    }

    private static String config(final long seed, final String ai0, final String ai1, final String extra) {
        return "{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":\"config\",\"session\":\"watch-test\","
                + "\"humanSeat\":0,\"seed\":" + seed + ",\"aiProfile\":\"Default\","
                + "\"decks\":[\"" + (deck0 == null ? "/tmp/a.dck" : deck0) + "\",\""
                + (deck1 == null ? "/tmp/b.dck" : deck1) + "\"],"
                + "\"startingChooser\":0,\"gameNumber\":1,\"names\":[\"Bot 3\",\"Bot 5\"],"
                + "\"watchSeats\":[0,1],\"seatAi\":[\"" + ai0 + "\",\"" + ai1 + "\"]" + extra + "}";
    }

    /**
     * Forge's process-wide object-id counters (SpellAbility, Trigger, ... ids feed hashCode and so some AI choices): taken
     * once, put back before every game, so that each game here starts as a fresh watch JVM's would.
     */
    static final class Ids {
        private static final String[][] FIELDS = {
                {"forge.game.Game", "maxId"},
                {"forge.game.spellability.SpellAbility", "maxId"},
                {"forge.game.spellability.SpellAbilityStackInstance", "maxId"},
                {"forge.game.trigger.Trigger", "maxId"},
                {"forge.game.replacement.ReplacementEffect", "maxId"},
                {"forge.game.staticability.StaticAbility", "maxId"},
                {"forge.game.combat.CombatView", "nextId"},
        };
        private static int[] snap;

        static void take() throws Exception {
            snap = new int[FIELDS.length];
            for (int i = 0; i < FIELDS.length; i++) {
                snap[i] = field(i).getInt(null);
            }
        }

        static void restore() throws Exception {
            for (int i = 0; i < FIELDS.length; i++) {
                field(i).setInt(null, snap[i]);
            }
        }

        private static java.lang.reflect.Field field(final int i) throws Exception {
            final java.lang.reflect.Field f = Class.forName(FIELDS[i][0]).getDeclaredField(FIELDS[i][1]);
            f.setAccessible(true);
            return f;
        }
    }

    /** One watch game's stdout, split per channel. */
    private static final class Run {
        final boolean ok;
        final String text;
        final Map<Integer, List<JsonObject>> byChannel = new LinkedHashMap<>();

        Run(final boolean ok, final String text) {
            this.ok = ok;
            this.text = text;
            for (String line : text.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                final JsonObject m = JsonParser.parseString(line).getAsJsonObject();
                byChannel.computeIfAbsent(m.get("to").getAsInt(), k -> new ArrayList<>()).add(m);
            }
        }

        List<JsonObject> channel(final int seat) {
            return byChannel.get(seat);
        }

        List<JsonObject> of(final int seat, final String type) {
            final List<JsonObject> out = new ArrayList<>();
            for (JsonObject m : channel(seat)) {
                if (type.equals(m.get("type").getAsString())) {
                    out.add(m);
                }
            }
            return out;
        }

        /** The stream without its event messages (states, hello, terminal). */
        String withoutEvents() {
            final StringBuilder sb = new StringBuilder();
            for (String line : text.split("\n")) {
                if (!line.contains("\"type\":\"event\"")) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static Run watch(final String configJson) throws Exception {
        final InteractiveProtocol.Config config = InteractiveProtocol.readConfig(configJson);
        Ids.restore();
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        final boolean ok = InteractiveWatch.run(config, out, false);
        out.flush();
        return new Run(ok, buf.toString(StandardCharsets.UTF_8));
    }

    private static void withProperty(final String key, final String value, final ThrowingRunnable body) throws Exception {
        final String was = System.getProperty(key);
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
        try {
            body.run();
        } finally {
            if (was == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, was);
            }
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** The wire contract of one finished watch game, on both channels. */
    private static void assertWatchStream(final Run r) {
        assertTrue(r.ok, "the game completed normally:\n" + tail(r.text));
        assertEquals(r.byChannel.keySet(), java.util.Set.of(0, 1), "one channel per seat");
        final List<Integer> n0 = new ArrayList<>();
        final List<Integer> n1 = new ArrayList<>();
        for (int seat = 0; seat < 2; seat++) {
            final List<JsonObject> ch = r.channel(seat);
            final JsonObject hello = ch.get(0);
            assertEquals(hello.get("type").getAsString(), "hello");
            assertEquals(hello.get("humanSeat").getAsInt(), seat, "the hello names the channel's seat");
            for (int i = 0; i < ch.size(); i++) {
                assertEquals(ch.get(i).get("seq").getAsLong(), i + 1L, "each channel has its own sequence");
                assertEquals(ch.get(i).get("session").getAsString(), "watch-test");
            }
            final JsonObject last = ch.get(ch.size() - 1);
            final JsonObject finalState = ch.get(ch.size() - 2);
            assertEquals(last.get("type").getAsString(), "terminal");
            assertEquals(finalState.get("type").getAsString(), "state");
            assertEquals(finalState.get("reason").getAsString(), "game-completed");
            assertEquals(finalState.getAsJsonObject("view").get("seat").getAsInt(), seat);
            assertFalse(r.of(seat, "event").isEmpty(), "events");
            for (JsonObject e : r.of(seat, "event")) {
                assertTrue(e.getAsJsonObject("event").has("class"));
            }
            for (JsonObject st : r.of(seat, "state")) {
                if (!"decision".equals(st.get("reason").getAsString())) {
                    continue;
                }
                assertEquals(st.getAsJsonObject("view").get("seat").getAsInt(), seat, "each channel sees its own seat");
                final JsonObject d = st.getAsJsonObject("decision");
                (seat == 0 ? n0 : n1).add(d.get("n").getAsInt());
                assertTrue(d.get("seat").getAsInt() == 0 || d.get("seat").getAsInt() == 1);
                assertTrue(d.get("turn").getAsInt() >= 1);
                assertNotNull(d.get("phase").getAsString());
            }
        }
        assertFalse(n0.isEmpty(), "decision states");
        assertEquals(n0, n1, "both channels get every decision of either seat");
        for (int i = 0; i < n0.size(); i++) {
            assertEquals(n0.get(i).intValue(), i, "decisions count from 0 for the whole game");
        }
        final JsonObject t0 = r.of(0, "terminal").get(0);
        final JsonObject t1 = r.of(1, "terminal").get(0);
        assertEquals(t0.get("winner"), t1.get("winner"), "one game, one winner");
        assertEquals(t0.get("turns"), t1.get("turns"));
        // a seat sees its own hand, never the other's
        final JsonObject v0 = r.of(0, "state").get(0).getAsJsonObject("view");
        final JsonArray players = v0.getAsJsonArray("players");
        assertTrue(players.get(0).getAsJsonObject().getAsJsonArray("hand").size() > 0, "seat 0 sees its hand");
        assertEquals(players.get(1).getAsJsonObject().getAsJsonArray("hand").size(), 0, "seat 0 never sees seat 1's hand");
    }

    private static String tail(final String text) {
        return text.length() <= 4000 ? text : text.substring(text.length() - 4000);
    }

    private static List<JsonObject> rows(final Path file) throws Exception {
        final List<JsonObject> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(JsonParser.parseString(line).getAsJsonObject());
            }
        }
        return out;
    }

    private static List<JsonObject> rowsOf(final List<JsonObject> rows, final String type) {
        final List<JsonObject> out = new ArrayList<>();
        for (JsonObject r : rows) {
            if (type.equals(r.get("type").getAsString())) {
                out.add(r);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ config

    @Test
    public void aWatchConfigIsParsedAndEveryOtherShapeIsRefused() throws Exception {
        final InteractiveProtocol.Config c = InteractiveProtocol.readConfig(config(7, "classic", "classic", ""));
        assertTrue(c.isWatch());
        assertFalse(c.isTable());
        assertEquals(c.watchSeats(), List.of(0, 1));
        assertEquals(c.seatAi(), List.of("classic", "classic"));
        assertEquals(c.names(), List.of("Bot 3", "Bot 5"));
        // every other mode is not a watch table
        final String oneSeat = "{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":\"config\",\"session\":\"s\","
                + "\"humanSeat\":0,\"seed\":7,\"aiProfile\":\"Default\",\"decks\":[\"/tmp/a.dck\",\"/tmp/b.dck\"]}";
        assertFalse(InteractiveProtocol.readConfig(oneSeat).isWatch());
        assertNull(InteractiveProtocol.readConfig(oneSeat).seatAi());
        assertFalse(InteractiveProtocol.readConfig(oneSeat.replace("}", ",\"humanSeats\":[0,1]}")).isWatch());
        final String classic = config(7, "classic", "classic", "");
        for (String bad : List.of(
                classic.replace("\"watchSeats\":[0,1]", "\"watchSeats\":[0]"),
                classic.replace("\"watchSeats\":[0,1]", "\"watchSeats\":[1,0]"),
                classic.replace("\"watchSeats\":[0,1]", "\"watchSeats\":[0,0]"),
                classic.replace("\"watchSeats\":[0,1]", "\"watchSeats\":[0,1,1]"),
                classic.replace("\"watchSeats\":[0,1]", "\"watchSeats\":[\"0\",\"1\"]"),
                classic.replace("\"watchSeats\":[0,1]", "\"watchSeats\":{}"),
                classic.replace("}", ",\"humanSeats\":[0,1]}"),
                classic.replace(",\"seatAi\":[\"classic\",\"classic\"]", ""),
                classic.replace("\"seatAi\":[\"classic\",\"classic\"]", "\"seatAi\":null"),
                classic.replace("\"seatAi\":[\"classic\",\"classic\"]", "\"seatAi\":[\"classic\"]"),
                classic.replace("\"seatAi\":[\"classic\",\"classic\"]", "\"seatAi\":[\"classic\",\"human\"]"),
                classic.replace("\"seatAi\":[\"classic\",\"classic\"]", "\"seatAi\":[\"classic\",1]"),
                oneSeat.replace("}", ",\"seatAi\":[\"classic\",\"classic\"]}"),
                config(7, "lookahead", "classic", ""),
                config(7, "classic", "policy", ""))) {
            expectThrows(InteractiveProtocol.ProtocolException.class, () -> InteractiveProtocol.readConfig(bad));
        }
        withProperty("forge.interactive.lookahead", CHEAP_LOOKAHEAD, () ->
                assertEquals(InteractiveProtocol.readConfig(config(7, "lookahead", "classic", "")).seatAi(),
                        List.of("lookahead", "classic")));
        withProperty("forge.interactive.policySearch", "server=127.0.0.1:1", () ->
                assertEquals(InteractiveProtocol.readConfig(config(7, "classic", "policy", "")).seatAi(),
                        List.of("classic", "policy")));
        // a frame is a human table's test option, never a watch table's
        withProperty("forge.interactive.allowFrame", "true", () -> expectThrows(InteractiveProtocol.ProtocolException.class,
                () -> InteractiveProtocol.readConfig(classic.replace("}", ",\"frame\":\"turn=3\"}"))));
    }

    @Test
    public void watchChannelsAreTaggedPerSeatAndReadNoInput() throws Exception {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        final List<InteractiveProtocol.Channel> channels = InteractiveProtocol.Channel.watch(out, "s", List.of(0, 1));
        channels.get(0).hello("c", "v", "Default", 0, 7);
        channels.get(1).hello("c", "v", "Default", 1, 7);
        channels.get(1).send("state", new JsonObject());
        expectThrows(InteractiveProtocol.ProtocolException.class, channels.get(0)::readInput);
        channels.get(1).fatal("engine", "boom", null, "m", null);
        assertTrue(channels.get(0).isEnded() && channels.get(1).isEnded(), "a fatal reaches every seat");
        final String[] lines = buf.toString(StandardCharsets.UTF_8).split("\n");
        assertEquals(lines.length, 5);
        final JsonObject h1 = JsonParser.parseString(lines[1]).getAsJsonObject();
        assertEquals(h1.get("to").getAsInt(), 1);
        assertEquals(h1.get("seq").getAsInt(), 1);
        assertEquals(JsonParser.parseString(lines[2]).getAsJsonObject().get("seq").getAsInt(), 2);
    }

    // ------------------------------------------------------------------------------------------------ the hooks

    @Test
    public void theDecisionHookAndTheAiKnowledgeHookAreInertUntilSet() throws Exception {
        final Game game = initAndCreateGame();
        assertFalse(game.hasPriorityDecisionListeners(), "a game has no decision listener unless a host adds one");
        final Player ai = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card shown = addCardToZone("Shivan Dragon", opp, ZoneType.Hand);
        final Card top = addCardToZone("Island", ai, ZoneType.Library);
        final PlayerControllerAi pca = (PlayerControllerAi) ai.getController();
        pca.reveal(new CardCollection(shown), ZoneType.Hand, opp, "", false);   // no hook: nothing to report
        final List<String> seen = new ArrayList<>();
        pca.setKnowledgeHook(new PlayerControllerAi.KnowledgeHook() {
            @Override
            public void onReveal(final List<Card> cards, final ZoneType zone, final Player owner) {
                seen.add("reveal " + cards.size() + " " + zone + " " + (owner == opp));
            }

            @Override
            public void onLook(final List<Card> cards, final ZoneType destination) {
                seen.add("look " + cards.size() + " " + destination);
            }
        });
        pca.reveal(new CardCollection(shown), ZoneType.Hand, opp, "", false);
        pca.reveal(List.of(shown.getView()), ZoneType.Hand, opp.getView(), "", false);
        pca.arrangeForScry(new CardCollection(top));
        pca.arrangeForSurveil(new CardCollection(top));
        pca.orderMoveToZoneList(new CardCollection(top), ZoneType.Library, null);
        assertEquals(seen, List.of("reveal 1 Hand true", "reveal 1 Hand true", "look 1 Library", "look 1 Library",
                "look 1 Library"));
    }

    @Test
    public void theReviewSeatKeyBelongsToAWatchTableOnly() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(0);
        expectThrows(IllegalArgumentException.class, () -> RlReviewObserver.attach(game, p, 0, 1L,
                "out=/nonexistent/x,cardIndex=/nonexistent/y,seat=0", null));
        expectThrows(IllegalArgumentException.class, () -> RlReviewObserver.attachWatch(game, 1L,
                "out=/nonexistent/x,cardIndex=/nonexistent/y", null));
        expectThrows(IllegalArgumentException.class, () -> RlReviewObserver.attachWatch(game, 1L,
                "out=/nonexistent/x,cardIndex=/nonexistent/y,seat=2", null));
    }

    // ------------------------------------------------------------------------------------------------ games

    /**
     * Classic vs classic: both channels carry hello, every event, a state at every decision of either seat with their own
     * seat's view, the final state and the same terminal; a second run of the config is the same stream byte for byte;
     * and the game is the game Forge plays with no watcher at all (the same final view).
     */
    @Test
    public void aClassicWatchGameIsRecordedPerSeatIsRepeatableAndIsThePlainGame() throws Exception {
        fixtures();
        withProperty("forge.interactive.aiTimeoutSec", "600", () -> {
            final String cfg = config(722160001L, "classic", "classic", "");
            final Run a = watch(cfg);
            assertWatchStream(a);
            final Run b = watch(cfg);
            assertEquals(b.text, a.text, "the same config gives the same stream");
            System.err.println("[watch-test] classic: " + a.of(0, "state").size() + " states/channel, "
                    + a.of(0, "event").size() + " events/channel, " + a.text.length() + " bytes, terminal "
                    + a.of(0, "terminal").get(0));

            // the plain game: the same seats, seed and decks, no watcher, no decision listener
            final InteractiveProtocol.Config config = InteractiveProtocol.readConfig(cfg);
            Ids.restore();
            final InteractiveWatch.Prepared p = InteractiveWatch.prepare(config, new RlLiveSeat[2]);
            assertFalse(p.game().hasPriorityDecisionListeners());
            final CompletableFuture<Void> done = new CompletableFuture<>();
            p.game().getAction().invoke(() -> {
                try {
                    p.match().startGame(p.game());
                    done.complete(null);
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            done.join();
            final JsonObject plainFinal = InteractiveState.seatView(p.game(),
                    InteractiveMain.playerAtSeat(p.game(), 0), 0);
            final List<JsonObject> states = a.of(0, "state");
            assertEquals(states.get(states.size() - 1).getAsJsonObject("view"), plainFinal,
                    "watching does not change the game");
        });
    }

    @Test
    public void aLookaheadWatchGameReachesTerminalAndIsRepeatable() throws Exception {
        fixtures();
        withProperty("forge.interactive.aiTimeoutSec", "600", () ->
                withProperty("forge.interactive.lookahead", CHEAP_LOOKAHEAD, () -> {
                    final String cfg = config(722160002L, "lookahead", "lookahead", "");
                    final Run a = watch(cfg);
                    assertWatchStream(a);
                    final Run b = watch(cfg);
                    assertEquals(b.text, a.text, "the same config gives the same stream");
                    System.err.println("[watch-test] lookahead: " + a.of(0, "state").size() + " states/channel, "
                            + a.text.length() + " bytes, terminal " + a.of(0, "terminal").get(0));
                }));
    }

    /** Two policy seats: one service connection each, both bound, the game ends normally (no degrade, no void). */
    @Test
    public void aPolicyWatchGameBindsOneSeatPerPolicySeat() throws Exception {
        fixtures();
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            final String spec = "server=" + svc.address() + ",policySha=" + SHA + ",cardIndex=" + indexFile
                    + ",worlds=2,threads=1,budgetMs=0";
            final PrintStream was = System.err;
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            withProperty("forge.interactive.aiTimeoutSec", "600", () ->
                    withProperty("forge.interactive.policySearch", spec, () -> {
                        System.setErr(new PrintStream(new java.io.OutputStream() {
                            @Override
                            public void write(final int c) {
                                errBuf.write(c);
                                was.write(c);
                            }

                            @Override
                            public void write(final byte[] b, final int off, final int len) {
                                errBuf.write(b, off, len);
                                was.write(b, off, len);
                            }
                        }, true, StandardCharsets.UTF_8));
                        try {
                            final Run a = watch(config(722160003L, "policy", "policy", ""));
                            assertWatchStream(a);
                        } finally {
                            System.setErr(was);
                        }
                    }));
            final String err = errBuf.toString(StandardCharsets.UTF_8);
            assertEquals(err.lines().filter(l -> l.startsWith("[policy-seat] {\"bound\":true")).count(), 2L,
                    "one bound policy seat per policy seat");
            assertEquals(err.lines().filter(l -> l.startsWith("[policy-seat] {\"end\":true,\"degraded\":false")).count(),
                    2L, "each seat finishes once, undegraded");
            assertTrue(svc.decides.get() > 0, "the seats decide through the service");
        }
    }

    // ------------------------------------------------------------------------------------------------ review

    /**
     * The review observer on an AI seat: a frame at each of the seat's decisions (d0, d1, ...) and one move row after each,
     * the other seat's priority frames as for a human seat; a plan's d<j> entries are searched (here against a fake
     * service) or dumped before the AI decides, and the search does not move the game: the same frames, the same moves,
     * the same watch stream.
     */
    @Test
    public void theReviewObservesAnAiSeatAndItsPlanDoesNotMoveTheGame() throws Exception {
        reviewCase("classic", 722160001L);
    }

    /** The same on a look-ahead seat (its own search, its caches): observing and searching from it move nothing. */
    @Test
    public void theReviewOnALookaheadSeatDoesNotMoveTheGame() throws Exception {
        withProperty("forge.interactive.lookahead", CHEAP_LOOKAHEAD, () -> reviewCase("lookahead", 722160002L));
    }

    private void reviewCase(final String ai, final long seed) throws Exception {
        fixtures();
        final String cfg = config(seed, ai, ai, "");
        final Path dir = Files.createTempDirectory("watch-review-");
        final Path pass1 = dir.resolve("pass1.jsonl");
        final Path pass2 = dir.resolve("pass2.jsonl");
        final Run[] runs = new Run[3];
        withProperty("forge.interactive.aiTimeoutSec", "600", () -> {
            runs[0] = watch(cfg);
            withProperty("forge.interactive.review", "out=" + pass1 + ",cardIndex=" + indexFile + ",seat=1",
                    () -> runs[1] = watch(cfg));
        });
        assertWatchStream(runs[1]);
        final List<JsonObject> r1 = rows(pass1);
        final JsonObject header = r1.get(0);
        assertEquals(header.get("type").getAsString(), "header");
        assertTrue(header.get("aiSeat").getAsBoolean());
        assertEquals(header.get("humanSeat").getAsInt(), 1);
        final List<JsonObject> decisionFrames = new ArrayList<>();
        int otherPriority = 0;
        for (JsonObject f : rowsOf(r1, "frame")) {
            if ("decision".equals(f.get("at").getAsString())) {
                assertEquals(f.get("seat").getAsInt(), 1);
                assertEquals(f.get("kind").getAsString(), "priority");
                assertEquals(f.get("requestId").getAsString(), "d" + decisionFrames.size());
                assertFalse(f.has("error"), String.valueOf(f.get("error")));
                assertTrue(f.has("payload"));
                decisionFrames.add(f);
            } else {
                assertEquals(f.get("at").getAsString(), "priority");
                assertEquals(f.get("seat").getAsInt(), 0, "the other seat's priority frames");
                otherPriority++;
            }
        }
        final List<JsonObject> moves1 = rowsOf(r1, "move");
        assertFalse(decisionFrames.isEmpty());
        assertTrue(otherPriority > 0);
        assertEquals(moves1.size(), decisionFrames.size(), "one move row per decision frame");
        int cast = -1;
        int passed = -1;
        for (int j = 0; j < moves1.size(); j++) {
            final JsonObject m = moves1.get(j);
            assertEquals(m.get("requestId").getAsString(), "d" + j);
            assertEquals(m.get("n").getAsInt(), decisionFrames.get(j).get("n").getAsInt());
            if (m.has("pass")) {
                passed = passed < 0 ? j : passed;
            } else {
                assertTrue(m.get("card").getAsInt() > 0);
                assertTrue(m.has("name") && m.has("ability") && m.has("sa") && m.has("land") && m.has("spell"));
                if (cast < 0 && !m.get("land").getAsBoolean()) {
                    cast = j;
                }
            }
        }
        assertTrue(cast >= 0 && passed >= 0, "the AI both passed and cast");
        assertEquals(rowsOf(r1, "end").size(), 1);
        // the watch stream is the unobserved one, events aside (an observer's isolated reads may fire LKI events)
        assertEquals(runs[1].withoutEvents(), runs[0].withoutEvents(), "observing a seat does not change the game");

        // pass 2: the AI's own moves as the plan, searched against a fake service; d0 also dumps the position
        final JsonObject requests = new JsonObject();
        final JsonObject castEntry = new JsonObject();
        castEntry.add("card", moves1.get(cast).get("card"));
        castEntry.add("ability", moves1.get(cast).get("ability"));
        requests.add("d" + cast, castEntry);
        final JsonObject passEntry = new JsonObject();
        passEntry.addProperty("pass", true);
        requests.add("d" + passed, passEntry);
        final JsonObject stateEntry = new JsonObject();
        stateEntry.addProperty("state", true);
        if (!requests.has("d0")) {
            requests.add("d0", stateEntry);
        } else {
            requests.getAsJsonObject("d0").addProperty("state", true);
        }
        final JsonObject plan = new JsonObject();
        plan.add("requests", requests);
        final Path planFile = dir.resolve("plan.json");
        Files.writeString(planFile, plan.toString());
        try (FakeSearchService svc = new FakeSearchService(SHA)) {
            final String search = "server=" + svc.address() + ",policySha=" + SHA + ",cardIndex=" + indexFile
                    + ",worlds=2,threads=0,budgetMs=0";
            withProperty("forge.interactive.aiTimeoutSec", "600", () ->
                    withProperty("forge.interactive.reviewSearch", search, () ->
                            withProperty("forge.interactive.review", "out=" + pass2 + ",cardIndex=" + indexFile
                                    + ",seat=1,plan=" + planFile, () -> runs[2] = watch(cfg))));
            assertTrue(svc.scores.get() > 0, "the planned search asked the service");
        }
        final List<JsonObject> r2 = rows(pass2);
        assertEquals(rowsOf(r2, "move"), moves1, "the AI chose the same at every decision");
        assertEquals(rowsOf(r2, "frame"), rowsOf(r1, "frame"), "the same frames");
        final List<JsonObject> searches = rowsOf(r2, "search");
        assertEquals(searches.size(), 2, "one search row per planned priority entry");
        for (JsonObject s : searches) {
            assertTrue(s.has("search") || s.has("skipped"), String.valueOf(s));
            assertFalse(s.has("skipped") && s.get("skipped").getAsString().startsWith("error"), String.valueOf(s));
        }
        final String passId = "d" + passed;
        final String castId = "d" + cast;
        assertTrue(searches.stream().anyMatch(s -> s.get("requestId").getAsString().equals(passId)
                && (!s.has("playedEntry") || s.get("playedEntry").getAsInt() == 0)), "the pass entry is the menu's pass");
        assertTrue(searches.stream().anyMatch(s -> s.get("requestId").getAsString().equals(castId)
                && s.get("playedEntry").getAsInt() > 0), "the AI's cast is found in the seat's menu: " + searches);
        final List<JsonObject> dumps = rowsOf(r2, "state");
        assertEquals(dumps.size(), 1);
        assertEquals(dumps.get(0).get("requestId").getAsString(), "d0");
        assertTrue(dumps.get(0).has("text"), String.valueOf(dumps.get(0)));
        assertEquals(runs[2].withoutEvents(), runs[0].withoutEvents(), "the planned search does not change the game");
        System.err.println("[watch-test] review " + ai + ": " + decisionFrames.size() + " decision frames, " + otherPriority
                + " other-seat frames, searches " + searches + ", events equal "
                + runs[2].text.equals(runs[0].text) + "/" + runs[1].text.equals(runs[0].text));
    }
}
