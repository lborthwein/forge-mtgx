package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.control.FControlGameEventHandler;
import forge.player.PlayerControllerHuman;
import forge.trackable.Tracker;
import forge.util.ThreadUtil;
import org.testng.annotations.Test;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Lane host-cme-petal-1122: a live game ended with "human action failed: ConcurrentModificationException" when the
 * browser seat paid the last pip of a creature spell with a "{T}, Sacrifice: add one mana of any color" source and
 * confirmed the sacrifice.
 *
 * <p>The cause: the source's activation runs on the seat's action lane and asks "Sacrifice ...?" in a nested input.
 * When that input stops, the InputProxy shows the payment input again on the EDT, while the lane is still paying the
 * cost. Showing it wrote the game's tracker (the prompt's {@code SpellAbility.getView()}), and the tracker belongs to
 * the game thread: with the tracker frozen the write went into its delayed changes, which the lane was replaying. The
 * payment input now leaves the game alone until its activation ends.</p>
 *
 * <p>A race needs an interleaving, so these tests force the one that failed live, with the game tracker's delayed-change
 * queue instrumented ({@link RaceQueue}). Each step is a thread paused between two of its own instructions, which any
 * scheduler may do. Without the fix the lane's replay throws the live exception; with it no other thread writes the
 * queue while the activation runs.</p>
 *
 * <p>The harness is {@link InteractivePaymentColourTest}'s: Forge's real cast path for the seat.</p>
 */
public class InteractiveSacrificeManaSourceTest extends AITest {

    private static final String SESSION = "sacrifice-mana-source-test";
    /** The browser seat's sacrifice source pays the last pip (the live report's order). */
    @Test(timeOut = 120000)
    public void aSacrificeSourcePaysTheLastPip() throws Exception {
        pay(4);
    }

    @Test(timeOut = 120000)
    public void aSacrificeSourcePaysTheFirstPip() throws Exception {
        pay(0);
    }

    @Test(timeOut = 120000)
    public void aSacrificeSourcePaysAMiddlePip() throws Exception {
        pay(2);
    }

    /** Cast with the four lands and the sacrifice source at {@code sourceAt}, confirming its sacrifice. */
    private void pay(final int sourceAt) throws Exception {
        final Card[] source = new Card[1];
        final Card[] spell = new Card[1];
        final List<Card> lands = new ArrayList<>();
        final RaceQueue queue = new RaceQueue();
        try (Harness h = new Harness((seat, foe) -> {
            lands.add(addCard("Island", seat));
            lands.add(addCard("Swamp", seat));
            lands.add(addCard("Swamp", seat));
            lands.add(addCard("Swamp", seat));
            source[0] = addCard("Lotus Petal", seat);
            // a static ability on the battlefield, so every static check replays tracker changes
            addCard("Glorious Anthem", seat);
            addCard("Grizzly Bears", seat);
            spell[0] = addCardToZone("The Scarab God", seat, ZoneType.Hand);
            addCardToZone("Glen Elendra Archmage", seat, ZoneType.Hand);
            addCard("Forest", foe);
        }, queue)) {
            JsonObject request = h.request();
            assertEquals(kind(request), "priority", request.toString());
            h.acked(h.sendSelect(request, spell[0]));
            request = h.request();
            final List<Card> order = new ArrayList<>(lands);
            order.add(sourceAt, source[0]);
            for (Card card : order) {
                assertEquals(kind(request), "mana", request.toString());
                final String tap = h.sendSelect(request, card);
                request = h.request();
                while (true) {
                    if ("confirm".equals(kind(request)) && message(request).contains("Sacrifice")) {
                        final JsonObject action = new JsonObject();
                        action.addProperty("type", "confirm");
                        action.addProperty("controlId", "confirm:yes");
                        action.addProperty("confirm", true);
                        queue.arm();
                        h.acked(h.send(request, action));
                        request = h.request();
                    } else if (control(request, "Select Mana to Produce") != null) {
                        final JsonObject chooser = control(request, "Select Mana to Produce");
                        final JsonObject action = new JsonObject();
                        action.addProperty("type", "choice");
                        action.addProperty("controlId", "choices");
                        final com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
                        choices.add(chooser.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString());
                        action.add("choices", choices);
                        h.acked(h.send(request, action));
                        request = h.request();
                    } else {
                        break;
                    }
                }
                h.acked(tap); // the source's activation has returned
                if (card == source[0]) {
                    queue.disarm();
                }
            }
            assertEquals(queue.armings, 1, "the sacrifice's confirm was answered once");
            assertTrue(queue.laneQueued, "the lane queued tracker changes after the confirm");
            assertEquals(queue.foreign, Collections.emptyList(),
                    "no other thread wrote the game's tracker queue while the activation ran");

            assertTrue(source[0].isInZone(ZoneType.Graveyard), "the source was sacrificed: " + source[0].getZone());
            // the seat's produced mana waits in the pool: pay the spell with it
            while ("mana".equals(kind(request)) && request.getAsJsonArray("controls").toString().contains("payment:0")) {
                final JsonObject action = new JsonObject();
                action.addProperty("type", "choice");
                action.addProperty("controlId", "payment:0");
                h.acked(h.send(request, action));
                request = h.request();
            }
            assertEquals(kind(request), "priority", "the spell is cast: " + request);
            assertEquals(h.game.getStack().size(), 1, "the spell is on the stack");
            assertTrue(spell[0].isInZone(ZoneType.Stack), "the spell was cast: " + spell[0].getZone());
            assertFalse(h.gui.hasFailed(), "the game goes on");

            // the harness runs no priority loop: resolve the spell on a game thread, as Forge would after both pass
            final AtomicReference<Throwable> resolveFailure = new AtomicReference<>();
            final Thread resolve = new Thread(() -> {
                try {
                    h.game.getStack().resolveStack();
                } catch (Throwable t) {
                    resolveFailure.set(t);
                }
            }, "Game resolve (sacrifice mana source test)");
            resolve.start();
            resolve.join(TimeUnit.SECONDS.toMillis(30));
            if (resolveFailure.get() != null) {
                throw new AssertionError("resolving the spell", resolveFailure.get());
            }
            assertTrue(h.game.getStack().isEmpty(), "the stack resolved");
            assertTrue(h.seat.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c -> c.getId() == spell[0].getId()),
                    "the spell resolved onto the battlefield: " + h.game.getCardState(spell[0]).getZone());
            assertTrue(source[0].isInZone(ZoneType.Graveyard), "the source stays in the graveyard");
            assertFalse(h.gui.hasFailed(), "the game goes on");
        }
    }

    /**
     * The game tracker's delayed-change queue ({@code Tracker.delayedPropChanges}), instrumented. A write into it by a
     * thread that is not a game thread (Forge's rule: the name starts with "Game"), that is the EDT, is recorded while
     * armed. Armed, it also forces the interleaving that turns such a write into the live failure:
     * <ol>
     *   <li>{@link #arm()} parks the EDT before the confirm is sent, so whatever the confirm's end queues there waits;</li>
     *   <li>the lane, holding the tracker frozen, queues its first change: it lets the EDT go and waits (bounded) until
     *       another thread is inside its own add, that is past its {@code isFrozen()} check;</li>
     *   <li>that add waits (bounded) until the lane has unfrozen the tracker and taken its iterator over the queue;</li>
     *   <li>the lane waits (bounded) until that add is done, then replays the queue.</li>
     * </ol>
     * With no such write (the fix) the lane's one wait times out and nothing else changes.
     */
    private static final class RaceQueue extends ArrayList<Object> {
        private static final long WAIT_MS = 1500;
        final List<String> foreign = Collections.synchronizedList(new ArrayList<>());
        private final AtomicReference<Arming> armed = new AtomicReference<>();
        int armings;
        volatile boolean laneQueued;

        /** One armed confirm: the parked EDT and the handshake's steps. */
        private static final class Arming {
            final CountDownLatch edt = new CountDownLatch(1);
            final CountDownLatch foreignInside = new CountDownLatch(1);
            final CountDownLatch laneIterating = new CountDownLatch(1);
            final CountDownLatch foreignAdded = new CountDownLatch(1);
            final AtomicBoolean laneWaited = new AtomicBoolean();
            final AtomicBoolean foreignForced = new AtomicBoolean();

            void release() {
                edt.countDown();
                foreignInside.countDown();
                laneIterating.countDown();
                foreignAdded.countDown();
            }
        }

        void arm() throws InterruptedException {
            final Arming arming = new Arming();
            final CountDownLatch parked = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                parked.countDown();
                await(arming.edt, 4 * WAIT_MS);
            });
            assertTrue(parked.await(10, TimeUnit.SECONDS), "the EDT parked");
            armings++;
            armed.set(arming);
        }

        void disarm() {
            final Arming arming = armed.getAndSet(null);
            if (arming != null) {
                arming.release();
            }
        }

        private static void await(final CountDownLatch latch, final long ms) {
            try {
                latch.await(ms, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void record(final String what) {
            final StringBuilder sb = new StringBuilder(what).append(" on ").append(Thread.currentThread().getName());
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 3; i < Math.min(st.length, 14); i++) {
                sb.append("\n    at ").append(st[i]);
            }
            foreign.add(sb.toString());
        }

        @Override
        public boolean add(final Object change) {
            final Arming arming = armed.get();
            if (arming == null) {
                return super.add(change);
            }
            if (!ThreadUtil.isGameThread()) {
                record("add");
                if (arming.foreignForced.compareAndSet(false, true)) {
                    arming.foreignInside.countDown();
                    await(arming.laneIterating, WAIT_MS);
                    final boolean added = super.add(change);
                    arming.foreignAdded.countDown();
                    return added;
                }
                return super.add(change);
            }
            laneQueued = true;
            if (arming.laneWaited.compareAndSet(false, true)) {
                arming.edt.countDown();
                await(arming.foreignInside, WAIT_MS);
            }
            return super.add(change);
        }

        @Override
        public void clear() {
            if (armed.get() != null && !ThreadUtil.isGameThread()) {
                record("clear");
            }
            super.clear();
        }

        @Override
        public Iterator<Object> iterator() {
            final Iterator<Object> it = super.iterator();
            final Arming arming = armed.get();
            if (arming != null && ThreadUtil.isGameThread() && arming.foreignForced.get()
                    && arming.laneIterating.getCount() == 1) {
                arming.laneIterating.countDown();
                await(arming.foreignAdded, WAIT_MS);
            }
            return it;
        }
    }

    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();
        final Map<String, JsonObject> acks = new HashMap<>();

        Harness(final BiConsumer<Player, Player> board, final RaceQueue queue) throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            board.accept(seat, foe);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN2, seat);
            game.getAction().checkStateEffects(true);
            if (queue != null) {
                try {
                    final java.lang.reflect.Field f = Tracker.class.getDeclaredField("delayedPropChanges");
                    f.setAccessible(true);
                    assertTrue(((List<?>) f.get(game.getTracker())).isEmpty(), "no pending tracker changes");
                    f.set(game.getTracker(), queue);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }

            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            // As InteractiveMain.configureHumanPayment: produced mana waits in the pool.
            controller.getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
            seat.dangerouslySetController(controller);
            final PipedReader fromClient = new PipedReader(client, 1 << 16);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(new BufferedReader(fromClient),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 1);
            gui.bind(game, seat, controller);
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
            }, "Game thread (sacrifice mana source test)");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        JsonObject next(final String type) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                final String got = message.get("type").getAsString();
                if ("error".equals(got) || "fatal".equals(got)) {
                    fail("the bridge failed: " + message);
                }
                if ("ack".equals(got)) {
                    acks.put(message.get("inputId").getAsString(), message);
                }
                if (type.equals(got)) {
                    return message;
                }
            }
        }

        void acked(final String inputId) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (!acks.containsKey(inputId) && System.currentTimeMillis() < deadline) {
                if (next("ack") == null) {
                    break;
                }
            }
            final JsonObject ack = acks.get(inputId);
            assertNotNull(ack, "an ack for " + inputId);
            assertTrue(ack.get("accepted").getAsBoolean(), "accepted: " + ack);
        }

        JsonObject request() throws InterruptedException {
            final JsonObject request = next("request");
            assertNotNull(request, "a request");
            return request;
        }

        String send(final JsonObject request, final JsonObject action) throws IOException {
            final String inputId = UUID.randomUUID().toString();
            final JsonObject input = new JsonObject();
            input.addProperty("protocol", "mtgx-forge-interactive/1");
            input.addProperty("session", SESSION);
            input.addProperty("type", "input");
            input.addProperty("requestId", request.get("requestId").getAsString());
            input.addProperty("inputId", inputId);
            input.addProperty("kind", request.get("kind").getAsString());
            input.add("action", action);
            client.write(input + "\n");
            client.flush();
            return inputId;
        }

        String sendSelect(final JsonObject request, final Card card) throws IOException {
            final JsonObject action = new JsonObject();
            action.addProperty("type", "selectCard");
            action.addProperty("controlId", "card:" + card.getId());
            return send(request, action);
        }

        @Override
        public void close() {
            closed.set(true);
            controller.getInputQueue().onGameOver(true);
            gui.close();
        }
    }

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

    private static String kind(final JsonObject request) {
        return request.get("kind").getAsString();
    }

    private static String message(final JsonObject request) {
        return request.getAsJsonObject("prompt").get("message").getAsString();
    }

    private static JsonObject control(final JsonObject request, final String label) {
        for (JsonElement c : request.getAsJsonArray("controls")) {
            if (label.equals(c.getAsJsonObject().get("label").getAsString())) {
                return c.getAsJsonObject();
            }
        }
        return null;
    }
}
