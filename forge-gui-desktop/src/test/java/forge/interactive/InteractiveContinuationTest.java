package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.WallClock;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.control.FControlGameEventHandler;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

/**
 * "Continue from here" (lane forge-continue-1011): the AI's wall clocks while a game is rebuilt, and the host's
 * {@code handover} at the human's parked request (reshuffle both libraries from a seed; switch the AI back to its live
 * budgets). Off unless {@code -Dforge.interactive.continuation=rebuild}: a normal game's hello and its refusal of any
 * non-input line are unchanged.
 */
public class InteractiveContinuationTest extends AITest {

    private static final String SESSION = "continuation-test";
    private static final long SEED = 123456789L;
    private static final List<String> LIBRARY_NAMES = List.of("Mountain", "Island", "Lightning Bolt", "Forest",
            "Grizzly Bears", "Plains", "Shivan Dragon", "Swamp", "Counterspell");

    /** A one-seat bridge on a piped client, Forge's priority loop for the seat on a game-named thread. */
    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();
        final Random rngBefore = MyRandom.getRandom();

        Harness(final Function<Game, InteractiveContinuation> continuation) throws IOException {
            this(continuation, null);
        }

        /**
         * @param continuation this game's continuation (it may set the game up first), or null for a normal game
         * @param extra more of the board (seat, foe), or null
         */
        Harness(final Function<Game, InteractiveContinuation> continuation,
                final java.util.function.BiConsumer<Player, Player> extra) throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            board(seat, 45);
            board(foe, 42);
            if (extra != null) {
                extra.accept(seat, foe);
            }
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat);
            game.getAction().checkStateEffects(true);

            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            controller.getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
            seat.dangerouslySetController(controller);
            final PipedReader fromClient = new PipedReader(client, 1 << 16);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(new BufferedReader(fromClient),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 1);
            gui.bind(game, seat, controller);
            if (continuation != null) {
                gui.setContinuation(continuation.apply(game));
            }
            controller.setGui(gui);
            gui.setGameView(game.getView());
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui,
                    new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            gui.startReader();

            final Thread forgeThread = new Thread(() -> {
                while (!closed.get() && !gui.hasFailed() && !game.isGameOver()) {
                    final List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
                    if (chosen == null) {
                        continue;
                    }
                    for (SpellAbility ability : chosen) {
                        controller.playChosenSpellAbility(ability);
                    }
                }
            }, "Game thread (continuation test)");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        private void board(final Player player, final int libraryCards) {
            for (int i = 0; i < libraryCards; i++) {
                addCardToZone(LIBRARY_NAMES.get(i % LIBRARY_NAMES.size()), player, ZoneType.Library);
            }
            addCardToZone("Lightning Bolt", player, ZoneType.Hand);
            addCardToZone("Grizzly Bears", player, ZoneType.Hand);
            addCard("Mountain", player);
            addCard("Forest", player);
            addCardToZone("Shock", player, ZoneType.Graveyard);
        }

        /** Acks read past while waiting for something else, by inputId. */
        final java.util.Map<String, JsonObject> acks = new java.util.HashMap<>();

        /** The next message of {@code type}, or the first error (a fatal ends the bridge); null on timeout. */
        JsonObject next(final String type, final long timeoutMs) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                final String got = message.get("type").getAsString();
                if ("ack".equals(got)) {
                    acks.put(message.get("inputId").getAsString(), message);
                }
                if (type.equals(got) || "error".equals(got)) {
                    return message;
                }
            }
        }

        /** The ack for {@code inputId}, read now or earlier; it must be accepted. */
        JsonObject acked(final String inputId) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (!acks.containsKey(inputId) && System.currentTimeMillis() < deadline) {
                final JsonObject message = next("ack", Math.max(1, deadline - System.currentTimeMillis()));
                if (message != null && "error".equals(message.get("type").getAsString())) {
                    fail("the bridge failed: " + message);
                }
            }
            final JsonObject ack = acks.get(inputId);
            assertNotNull(ack, "an ack for " + inputId);
            assertTrue(ack.get("accepted").getAsBoolean(), "accepted: " + ack);
            return ack;
        }

        JsonObject expect(final String type) throws InterruptedException {
            final JsonObject message = next(type, 30000);
            assertNotNull(message, "a " + type + " message");
            if (!type.equals(message.get("type").getAsString())) {
                fail("expected " + type + ", the bridge said: " + message);
            }
            return message;
        }

        /** Every request or error that arrives within {@code ms}. */
        List<JsonObject> quiet(final long ms) throws InterruptedException {
            final List<JsonObject> seen = new ArrayList<>();
            final long deadline = System.currentTimeMillis() + ms;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return seen;
                }
                final String type = message.get("type").getAsString();
                if ("request".equals(type) || "error".equals(type)) {
                    seen.add(message);
                }
            }
        }

        void send(final JsonObject line) throws IOException {
            client.write(line + "\n");
            client.flush();
        }

        void handover(final String requestId, final boolean reshuffle, final Long seed, final boolean live)
                throws IOException {
            final JsonObject line = new JsonObject();
            line.addProperty("protocol", InteractiveProtocol.VERSION);
            line.addProperty("session", SESSION);
            line.addProperty("type", "handover");
            line.addProperty("requestId", requestId);
            line.addProperty("reshuffle", reshuffle);
            if (seed != null) {
                line.addProperty("seed", seed);
            }
            line.addProperty("live", live);
            send(line);
        }

        /** Send an input without waiting for its ack; returns its inputId. */
        String input(final JsonObject request, final JsonObject action) throws IOException {
            final String inputId = UUID.randomUUID().toString();
            final JsonObject input = new JsonObject();
            input.addProperty("protocol", InteractiveProtocol.VERSION);
            input.addProperty("session", SESSION);
            input.addProperty("type", "input");
            input.addProperty("requestId", request.get("requestId").getAsString());
            input.addProperty("inputId", inputId);
            input.addProperty("kind", request.get("kind").getAsString());
            input.add("action", action);
            send(input);
            return inputId;
        }

        JsonObject answer(final JsonObject request, final String type, final String controlId)
                throws IOException, InterruptedException {
            final JsonObject action = new JsonObject();
            action.addProperty("type", type);
            action.addProperty("controlId", controlId);
            return acked(input(request, action));
        }

        @Override
        public void close() {
            closed.set(true);
            controller.getInputQueue().onGameOver(true);
            gui.close();
            WallClock.setRebuilding(false);
            MyRandom.setRandom(rngBefore);
        }
    }

    /** Splits the bridge's NDJSON output into messages. */
    private static final class LineSink extends OutputStream {
        private final BlockingQueue<JsonObject> wire;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LineSink(final BlockingQueue<JsonObject> wire) {
            this.wire = wire;
        }

        @Override
        public synchronized void write(final int b) {
            if (b == '\n') {
                wire.add(JsonParser.parseString(line.toString(StandardCharsets.UTF_8)).getAsJsonObject());
                line.reset();
            } else {
                line.write(b);
            }
        }
    }

    /** As the JVM boots a rebuild: wall clocks off, then the game's continuation (AI_TIMEOUT 600). */
    private static Function<Game, InteractiveContinuation> rebuild(final long safePointTimeoutMs) {
        return game -> {
            InteractiveContinuation.beginRebuild();
            return InteractiveContinuation.forGame(game, safePointTimeoutMs);
        };
    }

    private static List<String> zone(final Player player, final ZoneType type) {
        final List<String> lines = new ArrayList<>();
        for (Card card : player.getZone(type).getCards()) {
            lines.add(card.getId() + ":" + card.getName());
        }
        return lines;
    }

    /** sha256 hex of the lines, each ending in a newline (computed here, not by the class under test). */
    private static String digest(final List<String> lines) throws Exception {
        final MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (String line : lines) {
            sha.update((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static List<String> sortedNames(final List<String> idNameLines) {
        final List<String> names = new ArrayList<>();
        for (String line : idNameLines) {
            names.add(line.substring(line.indexOf(':') + 1));
        }
        Collections.sort(names);
        return names;
    }

    private static List<String> namesOnly(final List<String> idNameLines) {
        final List<String> names = new ArrayList<>();
        for (String line : idNameLines) {
            names.add(line.substring(line.indexOf(':') + 1));
        }
        return names;
    }

    private static JsonObject library(final JsonObject reply, final int seat) {
        for (JsonElement element : reply.getAsJsonArray("libraries")) {
            if (element.getAsJsonObject().get("seat").getAsInt() == seat) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("no library for seat " + seat + " in " + reply);
    }

    // ---- (a) the wall clocks

    @Test
    public void wallClocksBindAsConfiguredLiveAndNeverWhileRebuilding() {
        try {
            WallClock.setRebuilding(false);
            assertEquals(WallClock.budgetMs(8000), 8000);
            assertEquals(WallClock.budgetMs(0), 0);
            assertEquals(WallClock.budgetMs(900_000), 900_000);
            assertEquals(WallClock.readTimeoutMs(8000), 8000);
            assertEquals(WallClock.readTimeoutMs(0), 0);

            WallClock.setRebuilding(true);
            assertTrue(WallClock.rebuilding());
            assertEquals(WallClock.budgetMs(8000), WallClock.REBUILD_BUDGET_MS);
            assertTrue(WallClock.budgetMs(1) >= 600_000);
            assertEquals(WallClock.budgetMs(900_000), 900_000, "a longer budget is kept");
            assertEquals(WallClock.budgetMs(0), 0, "no budget stays no budget");
            assertEquals(WallClock.readTimeoutMs(5000), 600_000);
            assertEquals(WallClock.readTimeoutMs(0), 0, "an infinite read timeout stays infinite");
            assertEquals(WallClock.readTimeoutMs(Integer.MAX_VALUE), Integer.MAX_VALUE);
        } finally {
            WallClock.setRebuilding(false);
        }
        assertEquals(WallClock.budgetMs(8000), 8000, "off again: live budgets");
    }

    // ---- the wire: hello and the handover line

    @Test
    public void aNormalHelloIsUnchangedAndARebuildsSaysSo() throws Exception {
        final ByteArrayOutputStream normal = new ByteArrayOutputStream();
        new InteractiveProtocol.Channel(new BufferedReader(new StringReader("")),
                new PrintStream(normal, true, StandardCharsets.UTF_8), "s")
                .hello("c0ffee", "v1", "Default", 0, 42L);
        assertEquals(normal.toString(StandardCharsets.UTF_8).trim(),
                "{\"protocol\":\"mtgx-forge-interactive/1\",\"session\":\"s\",\"seq\":1,\"type\":\"hello\","
                        + "\"forgeCommit\":\"c0ffee\",\"forgeVersion\":\"v1\",\"aiProfile\":\"Default\","
                        + "\"humanSeat\":0,\"seed\":42}");

        final ByteArrayOutputStream rebuild = new ByteArrayOutputStream();
        new InteractiveProtocol.Channel(new BufferedReader(new StringReader("")),
                new PrintStream(rebuild, true, StandardCharsets.UTF_8), "s")
                .hello("c0ffee", "v1", "Default", 0, 42L, InteractiveContinuation.helloExtras());
        assertEquals(rebuild.toString(StandardCharsets.UTF_8).trim(),
                "{\"protocol\":\"mtgx-forge-interactive/1\",\"session\":\"s\",\"seq\":1,\"type\":\"hello\","
                        + "\"forgeCommit\":\"c0ffee\",\"forgeVersion\":\"v1\",\"aiProfile\":\"Default\","
                        + "\"humanSeat\":0,\"seed\":42,\"continuation\":\"rebuild\"}");
    }

    @Test
    public void handoverLinesAreParsedStrictly() throws Exception {
        final String head = "{\"protocol\":\"mtgx-forge-interactive/1\",\"session\":\"s\",\"type\":\"handover\","
                + "\"requestId\":\"r57\"";
        final InteractiveProtocol.Incoming ok = channel(head + ",\"reshuffle\":true,\"seed\":123456789,\"live\":true}")
                .readInputOrHandover();
        assertEquals(ok, new InteractiveProtocol.HandoverMessage("r57", true, 123456789L, true));
        assertEquals(channel(head + ",\"reshuffle\":false,\"live\":false}").readInputOrHandover(),
                new InteractiveProtocol.HandoverMessage("r57", false, null, false));
        for (String bad : List.of(
                head + ",\"reshuffle\":true,\"live\":true}",                       // a reshuffle needs a seed
                head + ",\"reshuffle\":true,\"seed\":-1,\"live\":true}",           // non-negative
                head + ",\"reshuffle\":true,\"seed\":1.5,\"live\":true}",          // an integer
                head + ",\"reshuffle\":\"yes\",\"seed\":1,\"live\":true}",         // booleans
                head + ",\"reshuffle\":false}",                                     // live is required
                head + ",\"reshuffle\":false,\"live\":false,\"seq\":3}",           // no seq, as an input
                head.replace("\"session\":\"s\"", "\"session\":\"other\"") + ",\"reshuffle\":false,\"live\":false}")) {
            final InteractiveProtocol.ProtocolException e = expectThrows(InteractiveProtocol.ProtocolException.class,
                    () -> channel(bad).readInputOrHandover());
            assertEquals(e.code(), "protocol_mismatch", bad);
        }
        // readInput (every normal game, and every table) refuses a handover as any non-input line
        final InteractiveProtocol.ProtocolException old = expectThrows(InteractiveProtocol.ProtocolException.class,
                () -> channel(head + ",\"reshuffle\":false,\"live\":false}").readInput());
        assertEquals(old.code(), "protocol_mismatch");
        assertEquals(old.getMessage(), "type must be exactly 'input'");
        // a table channel takes no handover
        final InteractiveProtocol.SeatDemux demux = new InteractiveProtocol.SeatDemux(new BufferedReader(
                new StringReader(head + ",\"seat\":0,\"reshuffle\":false,\"live\":false}\n")), List.of(0, 1));
        final InteractiveProtocol.Channel seat0 = InteractiveProtocol.Channel.table(demux,
                new PrintStream(OutputStream.nullOutputStream()), "s", List.of(0, 1)).get(0);
        final InteractiveProtocol.ProtocolException table = expectThrows(InteractiveProtocol.ProtocolException.class,
                seat0::readInputOrHandover);
        assertEquals(table.code(), "protocol_mismatch");
    }

    private static InteractiveProtocol.Channel channel(final String line) {
        return new InteractiveProtocol.Channel(new BufferedReader(new StringReader(line + "\n")),
                new PrintStream(OutputStream.nullOutputStream()), "s");
    }

    // ---- (b) the reshuffle

    @Test(timeOut = 600000)
    public void aReshuffleKeepsTheCardsAndEveryOtherZoneAndChangesBothLibrariesOrder() throws Exception {
        try (Harness h = new Harness(rebuild(InteractiveContinuation.SAFE_POINT_TIMEOUT_MS))) {
            final JsonObject request = h.expect("request");
            assertEquals(request.get("kind").getAsString(), "priority");
            final List<Player> seats = new ArrayList<>(h.game.getRegisteredPlayers());
            final List<List<String>> before = new ArrayList<>();
            final List<List<String>> others = new ArrayList<>();
            final List<List<String>> expected = new ArrayList<>();
            final Random stream = new Random(SEED);
            for (Player player : seats) {
                final List<String> order = zone(player, ZoneType.Library);
                assertTrue(order.size() >= 40, "a 40+ card library: " + order.size());
                before.add(order);
                final List<String> shuffled = new ArrayList<>(order);
                Collections.shuffle(shuffled, stream);   // seat order, one stream: the contract's recipe
                expected.add(shuffled);
                others.add(List.of(String.join(",", zone(player, ZoneType.Hand)),
                        String.join(",", zone(player, ZoneType.Battlefield)),
                        String.join(",", zone(player, ZoneType.Graveyard))));
            }

            h.handover(request.get("requestId").getAsString(), true, SEED, false);
            final JsonObject reply = h.expect("handover");
            assertEquals(reply.get("requestId").getAsString(), request.get("requestId").getAsString());
            assertTrue(reply.get("reshuffled").getAsBoolean());
            assertFalse(reply.get("live").getAsBoolean());
            assertTrue(reply.get("rebuilding").getAsBoolean(), "still rebuilding until the live handover");
            assertEquals(reply.get("aiTimeoutSec").getAsInt(), InteractiveContinuation.REBUILD_AI_TIMEOUT_SEC);
            assertTrue(WallClock.rebuilding());

            for (int seat = 0; seat < seats.size(); seat++) {
                final Player player = seats.get(seat);
                final List<String> after = zone(player, ZoneType.Library);
                assertNotEquals(after, before.get(seat), "seat " + seat + "'s library order changed");
                assertEquals(after, expected.get(seat), "seat " + seat + ": new Random(seed), then Zone.shuffle in seat order");
                assertEquals(sortedNames(after), sortedNames(before.get(seat)), "the same cards");
                assertEquals(List.of(String.join(",", zone(player, ZoneType.Hand)),
                        String.join(",", zone(player, ZoneType.Battlefield)),
                        String.join(",", zone(player, ZoneType.Graveyard))), others.get(seat),
                        "seat " + seat + "'s hand, battlefield and graveyard are untouched");
                final JsonObject library = library(reply, seat);
                assertEquals(library.get("count").getAsInt(), after.size());
                assertEquals(library.get("cards").getAsString(), digest(sortedNames(before.get(seat))));
                assertEquals(library.get("before").getAsString(), digest(before.get(seat)));
                assertEquals(library.get("after").getAsString(), digest(after));
                assertNotEquals(library.get("before").getAsString(), library.get("after").getAsString());
            }

            // The human's request stays active: nothing is republished, and it is answered as it was.
            assertEquals(h.quiet(300), List.of(), "no request or error after the handover");
            final JsonObject ack = h.answer(request, "passPriority", "priority:pass");
            assertTrue(ack.get("accepted").getAsBoolean(), "the parked request still takes its answer: " + ack);
            assertFalse(h.gui.hasFailed());
        }
    }

    /**
     * A nested request. A dual land's colour question is asked inside the tap's action, so the tap's input is still in
     * flight while Forge waits on the question (its ack follows the answer). Forge is parked there all the same, and
     * the handover runs; the question, and then the tap, are answered as before.
     */
    @Test(timeOut = 600000)
    public void aHandoverAtANestedRequestRunsWhileTheOuterActionWaitsOnIt() throws Exception {
        final Card[] sanctum = new Card[1];
        final Card[] oko = new Card[1];
        try (Harness h = new Harness(rebuild(InteractiveContinuation.SAFE_POINT_TIMEOUT_MS), (seat, foe) -> {
            sanctum[0] = addCard("Botanical Sanctum", seat);
            addCard("Island", seat);
            addCard("Island", seat);
            oko[0] = addCardToZone("Oko, Thief of Crowns", seat, ZoneType.Hand);
        })) {
            final JsonObject priority = h.expect("request");
            assertEquals(priority.get("kind").getAsString(), "priority", priority.toString());
            h.answer(priority, "selectCard", "card:" + oko[0].getId());
            final JsonObject payment = h.expect("request");
            assertEquals(payment.get("kind").getAsString(), "mana", payment.toString());
            final JsonObject tapAction = new JsonObject();
            tapAction.addProperty("type", "selectCard");
            tapAction.addProperty("controlId", "card:" + sanctum[0].getId());
            final String tap = h.input(payment, tapAction);
            final JsonObject question = h.expect("request");
            assertEquals(question.get("kind").getAsString(), "confirm", "the colour question: " + question);
            assertFalse(h.acks.containsKey(tap), "the tap is still in flight");

            final List<String> before = zone(h.seat, ZoneType.Library);
            h.handover(question.get("requestId").getAsString(), true, SEED, false);
            final JsonObject reply = h.expect("handover");
            assertTrue(reply.get("reshuffled").getAsBoolean());
            assertNotEquals(zone(h.seat, ZoneType.Library), before);

            JsonObject green = null;
            for (JsonElement c : question.getAsJsonArray("controls")) {
                if ("Green".equals(c.getAsJsonObject().get("label").getAsString())) {
                    green = c.getAsJsonObject();
                }
            }
            assertNotNull(green, "Green is offered: " + question);
            final JsonObject answer = new JsonObject();
            answer.addProperty("type", "confirm");
            answer.addProperty("controlId", green.get("controlId").getAsString());
            answer.addProperty("confirm", green.get("value").getAsBoolean());
            h.acked(h.input(question, answer));
            h.acked(tap);
            final JsonObject after = h.expect("request");
            assertEquals(after.get("kind").getAsString(), "mana", after.toString());
            assertFalse(h.gui.hasFailed());
        }
    }

    /** A modal request (X of Mind Spring, asked on the game thread): the handover runs there as at an input request. */
    @Test(timeOut = 600000)
    public void aHandoverAtAModalRequestRunsThere() throws Exception {
        final Card[] spell = new Card[1];
        try (Harness h = new Harness(rebuild(InteractiveContinuation.SAFE_POINT_TIMEOUT_MS), (seat, foe) -> {
            addCard("Island", seat);
            addCard("Island", seat);
            spell[0] = addCardToZone("Mind Spring", seat, ZoneType.Hand);
        })) {
            final JsonObject priority = h.expect("request");
            h.answer(priority, "selectCard", "card:" + spell[0].getId());
            final JsonObject modal = h.expect("request");
            assertTrue(modal.get("inputClass").getAsString().startsWith("modal:"), "a modal: " + modal);
            final List<String> before = zone(h.seat, ZoneType.Library);
            h.handover(modal.get("requestId").getAsString(), true, SEED, true);
            final JsonObject reply = h.expect("handover");
            assertTrue(reply.get("live").getAsBoolean());
            assertNotEquals(zone(h.seat, ZoneType.Library), before);
            assertEquals(h.quiet(300), List.of(), "no request or error after the handover");
            assertFalse(h.gui.hasFailed());
        }
    }

    /** The same journal replayed twice reshuffles to the same order: the seeded stream is the only input. */
    @Test(timeOut = 600000)
    public void theSameSeedGivesTheSameOrder() throws Exception {
        final List<List<String>> runs = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            try (Harness h = new Harness(rebuild(InteractiveContinuation.SAFE_POINT_TIMEOUT_MS))) {
                final JsonObject request = h.expect("request");
                h.handover(request.get("requestId").getAsString(), true, SEED, false);
                h.expect("handover");
                final List<String> order = new ArrayList<>();
                for (Player player : h.game.getRegisteredPlayers()) {
                    order.addAll(namesOnly(zone(player, ZoneType.Library)));
                    order.add("|");
                }
                runs.add(order);
            }
        }
        assertEquals(runs.get(1), runs.get(0));
    }

    // ---- (c) no reshuffle

    @Test(timeOut = 600000)
    public void withoutAReshuffleTheOrderAndTheRandomStreamStay() throws Exception {
        try (Harness h = new Harness(rebuild(InteractiveContinuation.SAFE_POINT_TIMEOUT_MS))) {
            final JsonObject request = h.expect("request");
            final List<List<String>> before = new ArrayList<>();
            for (Player player : h.game.getRegisteredPlayers()) {
                before.add(zone(player, ZoneType.Library));
            }
            final Random rng = MyRandom.getRandom();
            h.handover(request.get("requestId").getAsString(), false, null, false);
            final JsonObject reply = h.expect("handover");
            assertFalse(reply.get("reshuffled").getAsBoolean());
            assertSame(MyRandom.getRandom(), rng, "Forge's random stream is not replaced");
            int seat = 0;
            for (Player player : h.game.getRegisteredPlayers()) {
                assertEquals(zone(player, ZoneType.Library), before.get(seat));
                final JsonObject library = library(reply, seat);
                assertEquals(library.get("before").getAsString(), library.get("after").getAsString());
                assertEquals(library.get("after").getAsString(), digest(before.get(seat)));
                seat++;
            }
        }
    }

    // ---- (d) the live handover

    @Test(timeOut = 600000)
    public void theLiveHandoverRestoresTheLiveClocksAndIsTheLast() throws Exception {
        try (Harness h = new Harness(game -> {
            game.AI_TIMEOUT = 7;   // the live value is remembered, not assumed
            InteractiveContinuation.beginRebuild();
            return InteractiveContinuation.forGame(game);
        })) {
            assertEquals(h.game.AI_TIMEOUT, InteractiveContinuation.REBUILD_AI_TIMEOUT_SEC);
            assertTrue(WallClock.rebuilding());
            final JsonObject request = h.expect("request");
            h.handover(request.get("requestId").getAsString(), true, SEED, true);
            final JsonObject reply = h.expect("handover");
            assertTrue(reply.get("live").getAsBoolean());
            assertFalse(reply.get("rebuilding").getAsBoolean());
            assertEquals(reply.get("aiTimeoutSec").getAsInt(), 7);
            assertFalse(WallClock.rebuilding(), "live budgets again");
            assertEquals(h.game.AI_TIMEOUT, 7);
            assertEquals(WallClock.budgetMs(8000), 8000);

            // After the live handover the channel is an ordinary one: a second handover is a protocol mismatch.
            h.handover(request.get("requestId").getAsString(), false, null, true);
            final JsonObject error = h.expect("error");
            assertTrue(error.get("fatal").getAsBoolean());
            assertEquals(error.get("code").getAsString(), "protocol_mismatch", error.toString());
        }
    }

    // ---- (e) a normal game

    @Test(timeOut = 600000)
    public void aHandoverOnANormalGameIsAProtocolMismatch() throws Exception {
        try (Harness h = new Harness(null)) {
            final JsonObject request = h.expect("request");
            final List<String> before = zone(h.seat, ZoneType.Library);
            h.handover(request.get("requestId").getAsString(), true, SEED, true);
            final JsonObject error = h.expect("error");
            assertTrue(error.get("fatal").getAsBoolean());
            assertEquals(error.get("code").getAsString(), "protocol_mismatch", error.toString());
            assertEquals(error.get("message").getAsString(), "type must be exactly 'input'");
            assertEquals(zone(h.seat, ZoneType.Library), before, "nothing was shuffled");
        }
    }

    // ---- (f) a request Forge is not parked on

    @Test(timeOut = 600000)
    public void aHandoverAtAnotherRequestTimesOut() throws Exception {
        try (Harness h = new Harness(rebuild(300))) {
            final JsonObject request = h.expect("request");
            final List<String> before = zone(h.seat, ZoneType.Library);
            final long t0 = System.nanoTime();
            h.handover("r999", true, SEED, true);
            final JsonObject error = h.expect("error");
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue(error.get("fatal").getAsBoolean());
            assertEquals(error.get("code").getAsString(), "handover_timeout", error.toString());
            assertEquals(error.get("requestId").getAsString(), "r999");
            assertTrue(ms >= 250 && ms < 10_000, "timed out after the configured wait: " + ms + " ms");
            assertEquals(zone(h.seat, ZoneType.Library), before, "nothing was shuffled");
            assertTrue(WallClock.rebuilding(), "nothing was switched");
            assertNotEquals(request.get("requestId").getAsString(), "r999");
        }
    }
}
