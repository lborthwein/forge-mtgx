package forge.interactive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gui.control.FControlGameEventHandler;
import forge.player.PlayerControllerHuman;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

/**
 * A TABLE (mtgx, 2026-09-27): two browser seats in one Forge game over one stdin/stdout.
 * Each seat's messages carry {@code "to": seat} and their own sequence; each seat's inputs
 * carry {@code "seat"}; each seat sees only its own information set; either seat can concede
 * while Forge waits on the other; a config without {@code humanSeats} is the one-seat wire.
 */
public class InteractiveTableTest extends AITest {

    private static final String SESSION = "table-test";

    private static String config(final String extra) {
        return "{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":\"config\",\"session\":\"s\","
                + "\"humanSeat\":0,\"seed\":7,\"aiProfile\":\"Default\","
                + "\"decks\":[\"/tmp/a.dck\",\"/tmp/b.dck\"]" + extra + "}";
    }

    @Test
    public void aConfigWithoutHumanSeatsIsTheOneSeatGame() throws Exception {
        final InteractiveProtocol.Config config = InteractiveProtocol.readConfig(config(""));
        assertEquals(config.humanSeats(), List.of(0));
        assertFalse(config.isTable());
        assertEquals(config.names(), List.of());
    }

    @Test
    public void humanSeatsZeroOneIsATable() throws Exception {
        final InteractiveProtocol.Config config = InteractiveProtocol.readConfig(
                config(",\"humanSeats\":[0,1],\"names\":[\"Ann\",\"Bo Li\"]"));
        assertEquals(config.humanSeats(), List.of(0, 1));
        assertTrue(config.isTable());
        assertEquals(config.names(), List.of("Ann", "Bo Li"));
    }

    @Test
    public void malformedTableConfigsAreRefused() {
        for (String extra : List.of(",\"humanSeats\":[1,0]", ",\"humanSeats\":[0,0]", ",\"humanSeats\":[0,2]",
                ",\"humanSeats\":[]", ",\"humanSeats\":0", ",\"names\":[\"a\"]", ",\"names\":[\"a\",\"[x]\"]",
                ",\"names\":[\"a\",\"\"]")) {
            expectThrows(InteractiveProtocol.ProtocolException.class, () -> InteractiveProtocol.readConfig(config(extra)));
        }
    }

    @Test
    public void theDemuxRoutesEachLineToItsSeatAndEndsBoth() throws Exception {
        final String zero = "{\"seat\":0,\"n\":1}";
        final String one = "{\"seat\":1,\"n\":2}";
        final String stray = "{\"seat\":7}";
        final InteractiveProtocol.SeatDemux demux = new InteractiveProtocol.SeatDemux(
                new BufferedReader(new StringReader(one + "\n" + zero + "\n" + stray + "\n")), List.of(0, 1));
        final InteractiveProtocol.LineSource s0 = demux.source(0);
        final InteractiveProtocol.LineSource s1 = demux.source(1);
        assertEquals(s1.readLine(), one);
        assertNull(s1.readLine());
        assertNull(s1.readLine(), "end of input stays ended");
        assertEquals(s0.readLine(), zero);
        assertEquals(s0.readLine(), stray, "an unroutable line goes to the first seat, which refuses it");
        assertNull(s0.readLine());
    }

    @Test
    public void tableChannelsTagAndSequenceEachSeatAndCheckTheInputSeat() throws Exception {
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PrintStream out = new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8);
        final String input0 = "{\"protocol\":\"mtgx-forge-interactive/1\",\"session\":\"s\",\"type\":\"input\","
                + "\"seat\":0,\"requestId\":\"r1\",\"inputId\":\"i1\",\"kind\":\"priority\","
                + "\"action\":{\"type\":\"passPriority\",\"controlId\":\"pass\"}}";
        final String wrongSeat = input0.replace("\"seat\":0", "\"seat\":1");
        final InteractiveProtocol.SeatDemux demux = new InteractiveProtocol.SeatDemux(
                new BufferedReader(new StringReader(input0 + "\n")), List.of(0, 1));
        final List<InteractiveProtocol.Channel> channels =
                InteractiveProtocol.Channel.table(demux, out, "s", List.of(0, 1));
        channels.get(0).hello("c", "v", "Default", 0, 7);
        channels.get(1).hello("c", "v", "Default", 1, 7);
        channels.get(1).send("state", new JsonObject());
        final JsonObject h0 = wire.take();
        final JsonObject h1 = wire.take();
        final JsonObject st = wire.take();
        assertEquals(h0.get("to").getAsInt(), 0);
        assertEquals(h0.get("seq").getAsInt(), 1);
        assertEquals(h1.get("to").getAsInt(), 1);
        assertEquals(h1.get("seq").getAsInt(), 1, "each seat has its own sequence");
        assertEquals(st.get("seq").getAsInt(), 2);
        assertEquals(channels.get(0).readInput().inputId(), "i1");

        // The one-seat channel never tags, and refuses a seat field.
        final InteractiveProtocol.Channel single = new InteractiveProtocol.Channel(
                new BufferedReader(new StringReader(input0 + "\n")), out, "s");
        single.hello("c", "v", "Default", 0, 7);
        assertFalse(wire.take().has("to"));
        expectThrows(InteractiveProtocol.ProtocolException.class, single::readInput);

        // A seat's channel refuses another seat's input.
        final InteractiveProtocol.SeatDemux stray = new InteractiveProtocol.SeatDemux(
                new BufferedReader(new StringReader(wrongSeat + "\n")), List.of(0));
        final List<InteractiveProtocol.Channel> lone =
                InteractiveProtocol.Channel.table(stray, out, "s", List.of(0));
        expectThrows(InteractiveProtocol.ProtocolException.class, lone.get(0)::readInput);
    }

    @Test
    public void aFatalOnOneSeatReachesEverySeat() throws Exception {
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PrintStream out = new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8);
        final InteractiveProtocol.SeatDemux demux = new InteractiveProtocol.SeatDemux(
                new BufferedReader(new StringReader("")), List.of(0, 1));
        final List<InteractiveProtocol.Channel> channels =
                InteractiveProtocol.Channel.table(demux, out, "s", List.of(0, 1));
        channels.get(1).fatal("engine", "boom", "r3", "m", "k");
        final JsonObject a = wire.take();
        final JsonObject b = wire.take();
        assertEquals(a.get("to").getAsInt(), 1);
        assertEquals(a.get("requestId").getAsString(), "r3");
        assertEquals(b.get("to").getAsInt(), 0);
        assertEquals(b.get("code").getAsString(), "engine");
        assertTrue(channels.get(0).isEnded() && channels.get(1).isEnded());
    }

    /** Two live seats over one real game: the Forge game thread alternates their priority inputs. */
    private final class Table implements AutoCloseable {
        final Game game;
        final Player[] players = new Player[2];
        final Card[] secrets = new Card[2];
        final PlayerControllerHuman[] controllers = new PlayerControllerHuman[2];
        final InteractiveGuiGame[] guis = new InteractiveGuiGame[2];
        final InteractiveGuiGame.Table table = new InteractiveGuiGame.Table();
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final Thread forgeThread;
        final AtomicBoolean closed = new AtomicBoolean();

        Table() throws IOException {
            game = initAndCreateGame();
            for (int seat = 0; seat < 2; seat++) {
                players[seat] = game.getPlayers().get(seat);
                addCard("Plains", players[seat]);
            }
            secrets[0] = addCardToZone("Serra Angel", players[0], ZoneType.Hand);
            secrets[1] = addCardToZone("Shivan Dragon", players[1], ZoneType.Hand);
            game.getAction().checkStateEffects(true);

            final PrintStream out = new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8);
            final InteractiveProtocol.SeatDemux demux = new InteractiveProtocol.SeatDemux(
                    new BufferedReader(new PipedReader(client, 1 << 16)), List.of(0, 1));
            final List<InteractiveProtocol.Channel> channels =
                    InteractiveProtocol.Channel.table(demux, out, SESSION, List.of(0, 1));
            for (int seat = 0; seat < 2; seat++) {
                controllers[seat] = new PlayerControllerHuman(game, players[seat], players[seat].getLobbyPlayer());
                guis[seat] = new InteractiveGuiGame(channels.get(seat), seat, table);
                guis[seat].bind(game, players[seat], controllers[seat]);
                controllers[seat].setGui(guis[seat]);
                guis[seat].setGameView(game.getView());
                game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(guis[seat],
                        new FControlGameEventHandler(controllers[seat])));
                game.subscribeToEvents(guis[seat]);
            }
            for (InteractiveGuiGame gui : guis) {
                gui.startReader();
            }
            forgeThread = new Thread(() -> {
                int seat = 0;
                while (!closed.get() && !game.isGameOver() && !guis[0].hasFailed()) {
                    new InputPassPriority(controllers[seat]).showAndWait();
                    seat = 1 - seat;
                }
            }, "test Forge table thread");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        JsonObject next(final String type, final int to, final long timeoutMs) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                if ("error".equals(message.get("type").getAsString())) {
                    fail("the table failed: " + message);
                }
                if (type.equals(message.get("type").getAsString()) && message.get("to").getAsInt() == to) {
                    return message;
                }
            }
        }

        void send(final int seat, final String requestId, final String kind, final String type,
                  final String controlId) throws IOException {
            final JsonObject input = new JsonObject();
            input.addProperty("protocol", "mtgx-forge-interactive/1");
            input.addProperty("session", SESSION);
            input.addProperty("type", "input");
            input.addProperty("seat", seat);
            input.addProperty("requestId", requestId);
            input.addProperty("inputId", UUID.randomUUID().toString());
            input.addProperty("kind", kind);
            final JsonObject action = new JsonObject();
            action.addProperty("type", type);
            action.addProperty("controlId", controlId);
            input.add("action", action);
            client.write(input + "\n");
            client.flush();
        }

        @Override
        public void close() {
            closed.set(true);
            for (PlayerControllerHuman controller : controllers) {
                controller.getInputQueue().onGameOver(true);
            }
            table.close();
            for (InteractiveGuiGame gui : guis) {
                gui.close();
            }
        }
    }

    /** Every card name a seat was shown, anywhere in its request view. */
    private static String viewText(final JsonObject request) {
        return request.getAsJsonObject("view").toString();
    }

    @Test(timeOut = 900000)
    public void eachSeatGetsItsOwnPromptsAndOnlyItsOwnHiddenCards() throws Exception {
        try (Table t = new Table()) {
            final JsonObject first = t.next("request", 0, 30000);
            assertNotNull(first, "seat 0 is asked first");
            assertEquals(first.get("seat").getAsInt(), 0);
            assertTrue(viewText(first).contains("Serra Angel"), "seat 0 sees its own hand");
            assertFalse(viewText(first).contains("Shivan Dragon"), "seat 0 never sees seat 1's hand: " + first);

            t.send(0, first.get("requestId").getAsString(), first.get("kind").getAsString(),
                    "passPriority", "priority:pass");
            final JsonObject ack = t.next("ack", 0, 30000);
            assertNotNull(ack);
            assertTrue(ack.get("accepted").getAsBoolean(), ack.toString());

            final JsonObject second = t.next("request", 1, 30000);
            assertNotNull(second, "then seat 1, on its own channel");
            assertEquals(second.get("seat").getAsInt(), 1);
            assertTrue(viewText(second).contains("Shivan Dragon"), "seat 1 sees its own hand");
            assertFalse(viewText(second).contains("Serra Angel"), "seat 1 never sees seat 0's hand: " + second);

            // Seat 0 answering seat 1's request is refused: requests are per seat.
            t.send(0, second.get("requestId").getAsString(), second.get("kind").getAsString(),
                    "passPriority", "priority:pass");
            final JsonObject refused = t.next("ack", 0, 30000);
            assertNotNull(refused);
            assertFalse(refused.get("accepted").getAsBoolean(), refused.toString());
            assertFalse(t.game.isGameOver());
        }
    }

    @Test(timeOut = 900000)
    public void aSeatConcedesWhileForgeWaitsOnTheOtherSeat() throws Exception {
        try (Table t = new Table()) {
            final JsonObject first = t.next("request", 0, 30000);
            assertNotNull(first, "Forge waits on seat 0");
            t.send(1, InteractiveProtocol.SEAT_CONCEDE_REQUEST, "concede", "concede", "game:concede");
            final JsonObject ack = t.next("ack", 1, 30000);
            assertNotNull(ack);
            assertTrue(ack.get("accepted").getAsBoolean(), ack.toString());
            final long deadline = System.currentTimeMillis() + 30000;
            while (!t.game.isGameOver() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(t.game.isGameOver(), "the concession ended the game");
            assertEquals(t.players[1].getOutcome().lossState, GameLossReason.Conceded);
            assertTrue(t.game.getOutcome().isWinner(t.players[0].getRegisteredPlayer()));
            t.forgeThread.join(30000);
            assertFalse(t.forgeThread.isAlive(), "seat 0's input was released");
        }
    }

    @Test(timeOut = 900000)
    public void aSeatConcessionIsOnlyTheConcedeControl() throws Exception {
        try (Table t = new Table()) {
            assertNotNull(t.next("request", 0, 30000));
            t.send(1, InteractiveProtocol.SEAT_CONCEDE_REQUEST, "concede", "passPriority", "priority:pass");
            final JsonObject ack = t.next("ack", 1, 30000);
            assertNotNull(ack);
            assertFalse(ack.get("accepted").getAsBoolean());
            assertFalse(t.game.isGameOver());
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
}
