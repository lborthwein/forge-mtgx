package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.GlobalIds;
import forge.game.card.Card;
import forge.game.cost.CostPart;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.control.FControlGameEventHandler;
import forge.player.PlayerControllerHuman;
import forge.trackable.Tracker;
import forge.util.GameContext;
import forge.util.IdScope;
import forge.util.MyRandom;
import forge.util.ScopedSlot;
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
import java.util.regex.Pattern;

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
    /** The host's JAVA_STACK_HEADER (mtgx tools/forge/interactive-session.ts), the exception-class branch. */
    private static final Pattern JAVA_STACK_HEADER =
            Pattern.compile("^[a-z][\\w$]*(?:\\.[\\w$]+)+(?:Exception|Error)(?::|$)");

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

    /**
     * Part 2b: a failed action's stack goes to its game's own stderr frames on a shared host, where the host's
     * {@code lastJavaStack} looks for it: first the throwable's line (the host's stack header), then at most
     * {@link InteractiveGuiGame#FAIL_STACK_FRAMES} frames; nothing of it on the host's own stderr.
     */
    @Test(timeOut = 120000)
    public void aFailedActionsStackGoesToItsGamesStderrFrames() throws Exception {
        final ByteArrayOutputStream frames = new ByteArrayOutputStream();
        final ByteArrayOutputStream hostErr = new ByteArrayOutputStream();
        final AtomicReference<Throwable> problem = new AtomicReference<>();
        final AtomicReference<JsonObject> fatal = new AtomicReference<>();
        final PrintStream savedErr = System.err;
        GameContext.enableHosting();
        try {
            System.setErr(new PrintStream(routedErr(hostErr), true, StandardCharsets.UTF_8));
            // as InteractiveHost.serve: the game's context, ids and stderr frames, on the game's own thread
            final Thread game = new Thread(null, () -> {
                final GameContext context = GameContext.open("fail-stack-test");
                final Object ids = IdScope.scopeAt(GlobalIds.capture());
                IdScope.install(ids);
                context.setGameIds(ids);
                gameErr().set(new InteractiveHost.LineFramer(new InteractiveHost.FrameSink(frames), 'E', 'e', false));
                try {
                    fatal.set(failInTheSacrificeConfirm());
                } catch (Throwable t) {
                    problem.set(t);
                }
            }, "test-host-game", 0, false);
            game.start();
            game.join(TimeUnit.SECONDS.toMillis(90));
        } finally {
            System.setErr(savedErr);
            GameContext.stopHostingForTests();
            MyRandom.setThreadRandom(null);
            IdScope.close();
        }
        if (problem.get() != null) {
            throw new AssertionError("the hosted game", problem.get());
        }
        assertNotNull(fatal.get(), "the forced failure ends the game");
        assertTrue(fatal.get().toString().contains("human action failed: IllegalStateException: forced"),
                fatal.get().toString());

        final List<String> err = new ArrayList<>();
        for (String frame : frames.toString(StandardCharsets.UTF_8).split("\n")) {
            if (frame.startsWith("E")) {
                err.add(frame.substring(1));
            }
        }
        int header = -1;
        for (int i = 0; i < err.size(); i++) {
            if (JAVA_STACK_HEADER.matcher(err.get(i)).find() && err.get(i).contains("forced")) {
                header = i;
            }
        }
        assertTrue(header > 0, "the game's stderr frames hold the exception's line: " + err);
        assertEquals(err.get(header), "java.lang.IllegalStateException: forced failure in the sacrifice confirm");
        assertTrue(err.get(header - 1).startsWith("[forge.interactive] dispatch:selectCard: IllegalStateException"),
                err.get(header - 1));
        int frameCount = 0;
        boolean throwingFrame = false;
        for (int i = header + 1; i < err.size() && err.get(i).startsWith("\tat "); i++) {
            frameCount++;
            throwingFrame |= err.get(i).contains("confirmPayment");
        }
        assertTrue(throwingFrame, "the frames start where it was thrown: " + err.subList(header, err.size()));
        assertTrue(frameCount > 0 && frameCount <= InteractiveGuiGame.FAIL_STACK_FRAMES,
                frameCount + " frames: " + err.subList(header, err.size()));
        assertFalse(hostErr.toString(StandardCharsets.UTF_8).contains("forced failure"),
                "none of it on the host's own stderr");
    }

    /** Part 2b: a deep failure prints its line first, then 40 frames, then a short cause; nothing past the caps. */
    @Test
    public void aFailuresStackIsCappedWithItsLineFirst() {
        final RuntimeException cause = new RuntimeException("inner");
        cause.setStackTrace(frames("inner", 30));
        final IllegalStateException failure = new IllegalStateException("outer", cause);
        failure.setStackTrace(frames("outer", 100));
        final String[] lines = InteractiveGuiGame.cappedStack(failure).split("\n");
        assertEquals(lines[0], "java.lang.IllegalStateException: outer");
        assertTrue(JAVA_STACK_HEADER.matcher(lines[0]).find(), "the host's stack header");
        for (int i = 1; i <= InteractiveGuiGame.FAIL_STACK_FRAMES; i++) {
            assertEquals(lines[i], "\tat x.outer.m" + (i - 1) + "(X.java:" + (i - 1) + ")");
        }
        int at = InteractiveGuiGame.FAIL_STACK_FRAMES + 1;
        assertEquals(lines[at++], "\t... 60 more");
        assertEquals(lines[at++], "Caused by: java.lang.RuntimeException: inner");
        for (int i = 0; i < InteractiveGuiGame.FAIL_CAUSE_FRAMES; i++) {
            assertEquals(lines[at++], "\tat x.inner.m" + i + "(X.java:" + i + ")");
        }
        assertEquals(lines[at++], "\t... 20 more");
        assertEquals(lines.length, at, "nothing past the caps");
    }

    private static StackTraceElement[] frames(final String name, final int n) {
        final StackTraceElement[] trace = new StackTraceElement[n];
        for (int i = 0; i < n; i++) {
            trace[i] = new StackTraceElement("x." + name, "m" + i, "X.java", i);
        }
        return trace;
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
        }, queue, null)) {
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

    /** Part 2b's game: the seat's controller throws in the source's sacrifice confirm, on the action lane. */
    private JsonObject failInTheSacrificeConfirm() throws Exception {
        final Card[] source = new Card[1];
        final Card[] spell = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Island", seat);
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            source[0] = addCard("Lotus Petal", seat);
            spell[0] = addCardToZone("The Scarab God", seat, ZoneType.Hand);
            addCard("Forest", foe);
        }, null, (game, seat) -> new PlayerControllerHuman(game, seat, seat.getLobbyPlayer()) {
            @Override
            public boolean confirmPayment(final CostPart costPart, final String question, final SpellAbility sa) {
                throw new IllegalStateException("forced failure in the sacrifice confirm");
            }
        })) {
            JsonObject request = h.request();
            h.acked(h.sendSelect(request, spell[0]));
            request = h.request();
            assertEquals(kind(request), "mana", request.toString());
            h.sendSelect(request, source[0]);
            return h.awaitFatal();
        }
    }

    /** The host's routed System.err ({@code InteractiveHost.RoutedErr}): a hosted game's frames, else {@code host}. */
    private static OutputStream routedErr(final OutputStream host) throws ReflectiveOperationException {
        final java.lang.reflect.Constructor<?> c = Class.forName("forge.interactive.InteractiveHost$RoutedErr")
                .getDeclaredConstructor(OutputStream.class);
        c.setAccessible(true);
        return (OutputStream) c.newInstance(host);
    }

    /** {@code InteractiveHost.GAME_ERR}: a hosted game's stderr frames. */
    @SuppressWarnings("unchecked")
    private static ScopedSlot<OutputStream> gameErr() {
        try {
            final java.lang.reflect.Field f = InteractiveHost.class.getDeclaredField("GAME_ERR");
            f.setAccessible(true);
            return (ScopedSlot<OutputStream>) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
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

    private interface ControllerFactory {
        PlayerControllerHuman create(Game game, Player seat);
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

        Harness(final BiConsumer<Player, Player> board, final RaceQueue queue, final ControllerFactory controllers)
                throws IOException {
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

            controller = controllers == null ? new PlayerControllerHuman(game, seat, seat.getLobbyPlayer())
                    : controllers.create(game, seat);
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

        JsonObject awaitFatal() throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                final String got = message.get("type").getAsString();
                if ("error".equals(got) || "fatal".equals(got)) {
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
