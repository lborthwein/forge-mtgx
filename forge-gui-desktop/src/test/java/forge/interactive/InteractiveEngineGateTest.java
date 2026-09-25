package forge.interactive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.event.GameEventPlayerStatsChanged;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * The browser bridge and Forge's single-threaded model (2026-09-24, seed 719211528: "human action
 * failed: ConcurrentModificationException" when the seat cast Steel Seraph while the foe had
 * Oracle of Mul Daya in play).
 *
 * <p>Enumerating a Prototype spell's abilities is not a read: {@code GameActionUtil
 * .getAlternativeCosts} re-applies every static ability in the game around an LKI copy, freezing
 * and unfreezing the view tracker; with Oracle of Mul Daya in play each pass fires four
 * {@link GameEventPlayerStatsChanged}. The bridge (1) republished on each of those events, so the
 * EDT enumerated again and again while the input was live, and (2) let a claimed action run on its
 * own thread while the EDT was enumerating. Two threads re-applying statics at once throw.</p>
 */
public class InteractiveEngineGateTest extends AITest {

    private static final String SESSION = "engine-gate-test";

    /** A live bridge over a real game with the reported board. */
    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Card seraph;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final Thread forgeThread;
        final AtomicBoolean closed = new AtomicBoolean();

        Harness() throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            final Player foe = game.getPlayers().get(0);
            for (int i = 0; i < 4; i++) {
                addCard("Plains", seat);
            }
            addCard("Swamp", seat);
            addCard("Monastery Mentor", seat);
            seraph = addCardToZone("Steel Seraph", seat, ZoneType.Hand);
            addCardToZone("Cathar Commando", seat, ZoneType.Hand);
            // More LKI-checked spells and statics lengthen each publish pass, which widens the
            // window in which a claim can land mid-pass (the reported game hit it once in 384).
            for (int i = 0; i < 5; i++) {
                addCardToZone("Steel Seraph", seat, ZoneType.Hand);
            }
            addCard("Glorious Anthem", seat);
            addCard("Intangible Virtue", seat);
            addCard("Forest", foe);
            addCard("Tundra", foe);
            addCard("Mountain", foe);
            addCard("Oracle of Mul Daya", foe);
            game.getAction().checkStateEffects(true);

            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            final PipedReader fromClient = new PipedReader(client, 1 << 16);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(new BufferedReader(fromClient),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 1);
            gui.bind(game, seat, controller);
            controller.setGui(gui);
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui,
                    new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            gui.startReader();

            // Forge's game thread: parked on the seat's priority input, re-offered after each answer.
            forgeThread = new Thread(() -> {
                while (!closed.get() && !gui.hasFailed()) {
                    new InputPassPriority(controller).showAndWait();
                }
            }, "test Forge game thread");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        JsonObject nextOfType(final String type, final long timeoutMs) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                final long left = deadline - System.currentTimeMillis();
                final JsonObject message = wire.poll(Math.max(0, left), TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                if ("error".equals(message.get("type").getAsString())) {
                    fail("the bridge failed: " + message);
                }
                if (type.equals(message.get("type").getAsString())) {
                    return message;
                }
            }
        }

        void answer(final JsonObject request, final String type, final String controlId) throws IOException {
            final JsonObject input = new JsonObject();
            input.addProperty("protocol", "mtgx-forge-interactive/1");
            input.addProperty("session", SESSION);
            input.addProperty("type", "input");
            input.addProperty("requestId", request.get("requestId").getAsString());
            input.addProperty("inputId", UUID.randomUUID().toString());
            input.addProperty("kind", request.get("kind").getAsString());
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
            controller.getInputQueue().onGameOver(true);
            gui.close();
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

    private static boolean offers(final JsonObject request, final String controlId) {
        return request.getAsJsonArray("controls").asList().stream()
                .anyMatch(c -> controlId.equals(c.getAsJsonObject().get("controlId").getAsString()));
    }

    /** Precondition of both tests: the reported board makes the enumeration fire events. */
    @Test(timeOut = 900000)
    public void enumeratingSteelSeraphReappliesStaticsAndFiresEvents() {
        final Game game = initAndCreateGame();
        final Player seat = game.getPlayers().get(1);
        addCard("Oracle of Mul Daya", game.getPlayers().get(0));
        final Card seraph = addCardToZone("Steel Seraph", seat, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        final int[] fired = {0};
        game.subscribeToEvents(new Object() {
            @com.google.common.eventbus.Subscribe
            public void on(final GameEvent event) {
                if (event instanceof GameEventPlayerStatsChanged) {
                    fired[0]++;
                }
            }
        });
        seraph.getAllPossibleAbilities(seat, true);
        assertEquals(fired[0], 4, "Prototype's LKI check removes and re-adds Oracle's extra land play, twice");
    }

    /**
     * A live priority input with nothing happening must go quiet. Before the fix the bridge's own
     * enumeration fired four PlayerStatsChanged per pass and each one scheduled another pass: an
     * endless stream of events on the wire (and in every transcript) for as long as the seat
     * thought.
     */
    @Test(timeOut = 900000)
    public void anIdleLiveInputPublishesOnceAndGoesQuiet() throws Exception {
        try (Harness harness = new Harness()) {
            final JsonObject request = harness.nextOfType("request", 30000);
            assertNotNull(request, "the priority input was published");
            assertTrue(offers(request, "card:" + harness.seraph.getId()), "Steel Seraph is offered: " + request);
            Thread.sleep(500);
            harness.wire.clear();
            Thread.sleep(2000);
            final List<JsonObject> idle = new ArrayList<>(harness.wire);
            assertEquals(idle.size(), 0, "an idle live input publishes nothing more; got " + idle.size()
                    + " messages, first " + (idle.isEmpty() ? "" : idle.get(0)));
        }
    }

    /**
     * Casting Steel Seraph while the EDT keeps republishing the same input. Each cast is offered the
     * Prototype choice and cancelled, so the input stays live and the cycle repeats. The republish
     * hammer stands in for the event traffic that schedules publishes in a real game. Before the fix
     * the action thread and the EDT enumerated at once and one of them threw.
     */
    @Test(timeOut = 900000)
    public void castingSteelSeraphWhileTheInputIsRepublishedNeverOverlaps() throws Exception {
        try (Harness harness = new Harness()) {
            final AtomicBoolean stop = new AtomicBoolean();
            final Thread hammer = new Thread(() -> {
                while (!stop.get() && !harness.gui.hasFailed()) {
                    harness.gui.updateLives(List.of());
                    try {
                        Thread.sleep(1); // about the event rate of the reported game
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "test republish hammer");
            hammer.setDaemon(true);
            hammer.start();
            try {
                final String seraph = "card:" + harness.seraph.getId();
                int casts = 0;
                final long deadline = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < deadline) {
                    final JsonObject request = harness.nextOfType("request", 30000);
                    assertNotNull(request, "a request after " + casts + " casts");
                    if ("choice".equals(request.get("kind").getAsString())) {
                        // The Prototype choice (Steel Seraph 5/4 or 3/3): back out, keep priority.
                        harness.answer(request, "cancel", "ability:cancel");
                        continue;
                    }
                    if (!offers(request, seraph)) {
                        continue; // a stale republish; the next one names the card again
                    }
                    harness.answer(request, "selectCard", seraph);
                    casts++;
                }
                System.out.println("[gate-test] " + casts + " Steel Seraph casts under republish, no failure");
                assertTrue(casts >= 20, "the loop exercised the cast path: " + casts);
                assertTrue(!harness.gui.hasFailed(), "the bridge never failed");
            } finally {
                stop.set(true);
                hammer.join();
            }
        }
    }
}
