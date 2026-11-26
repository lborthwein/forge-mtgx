package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.player.Player;
import forge.gamemodes.match.input.InputConfirmMulligan;
import forge.gamemodes.match.input.InputSyncronizedBase;
import forge.gui.GuiBase;
import forge.gui.control.FControlGameEventHandler;
import forge.gui.interfaces.IGuiBase;
import forge.player.PlayerControllerHuman;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * The opening mulligan with no controls (lane mulligan-no-controls-1126; Sacred Geometry soak, game db3a4cd3, 1 of 29
 * games in which the AI took a mulligan first).
 *
 * <p>The seat that does not choose who plays first is told to wait ({@code GameAction.determineFirstTurnPlayer} calls
 * its {@code awaitNextInput}). 250 ms later {@code AbstractGuiGame}'s timer posts "Waiting for &lt;opponent&gt;..." to
 * the EDT: a status prompt ({@code showPromptMessage(view, text)}) and disabled buttons. The next input's
 * {@code showMessageInitial} cancels that timer, but the {@code InputProxy} names the new input before it queues
 * {@code showMessageInitial}. When the status update runs in that window, the bridge took the status prompt for the
 * presentation of the input the proxy named, and its publish (queued ahead of the input's own presentation) enumerated
 * the mulligan with the "waiting" buttons: no human control, so the game ended fatal on turn 0.</p>
 *
 * <p>The test holds Forge's game thread in that window (at the proxy's queuing of {@code showMessageInitial}) until
 * the status update has run on the EDT, so the order is the reported one on every run.</p>
 */
public class InteractiveMulliganPresentationTest extends AITest {

    private static final String SESSION = "mulligan-presentation-test";

    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final List<JsonObject> log = new ArrayList<>();
        final PipedWriter client = new PipedWriter();

        Harness() throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
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
        }

        /** The next request or error (both logged), or null after {@code timeoutMs}. */
        JsonObject nextRequestOrError(final long timeoutMs) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                log.add(message);
                final String type = message.get("type").getAsString();
                if ("request".equals(type) || "error".equals(type)) {
                    return message;
                }
            }
        }

        /** Every request or error that arrives within {@code ms}. */
        List<JsonObject> quiet(final long ms) throws InterruptedException {
            final List<JsonObject> seen = new ArrayList<>();
            while (true) {
                final JsonObject message = nextRequestOrError(ms);
                if (message == null) {
                    return seen;
                }
                seen.add(message);
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

    private static List<String> controlIds(final JsonObject request) {
        final List<String> ids = new ArrayList<>();
        for (JsonElement control : request.getAsJsonArray("controls")) {
            ids.add(control.getAsJsonObject().get("controlId").getAsString());
        }
        return ids;
    }

    private static String label(final JsonObject request, final String controlId) {
        for (JsonElement control : request.getAsJsonArray("controls")) {
            if (controlId.equals(control.getAsJsonObject().get("controlId").getAsString())) {
                return control.getAsJsonObject().get("label").getAsString();
            }
        }
        return null;
    }

    /**
     * Forge's EDT entry point with one hold: when {@code gameThread} queues EDT work while the seat's InputProxy names
     * an {@link InputConfirmMulligan} (that is InputProxy.update queuing the input's showMessageInitial, right after
     * naming it), the queuing waits until this game's awaitNextInput timer task has run on the EDT.
     */
    private static IGuiBase holdShowUntilWaitingPromptRan(final IGuiBase forge, final Harness h,
                                                           final AtomicReference<Thread> gameThread,
                                                           final CountDownLatch waitingRan,
                                                           final AtomicBoolean held) {
        final String timerThread = "awaitNextInputTimer Game:" + h.game.getView().getId() + " ";
        return (IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                (proxy, method, args) -> {
                    Object[] call = args;
                    if ("invokeInEdtLater".equals(method.getName()) && args != null && args.length == 1
                            && args[0] instanceof Runnable task) {
                        final Thread caller = Thread.currentThread();
                        if (caller.getName().startsWith(timerThread)) {
                            call = new Object[]{(Runnable) () -> {
                                try {
                                    task.run();
                                } finally {
                                    waitingRan.countDown();
                                }
                            }};
                        } else if (caller == gameThread.get()
                                && h.controller.getInputProxy().getInput() instanceof InputConfirmMulligan
                                && held.compareAndSet(false, true)) {
                            if (!waitingRan.await(30, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("the awaitNextInput timer task never ran");
                            }
                        }
                    }
                    try {
                        return method.invoke(forge, call);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * The reported order, forced: "Waiting for ..." (status prompt, both buttons off) runs on the EDT after the
     * InputProxy has named the seat's mulligan input and before it queues that input's showMessageInitial. The seat
     * still gets exactly one mulligan request, with Keep and Mulligan, and Keep is answered.
     */
    @Test(timeOut = 120000)
    public void aWaitingPromptBeforeTheMulligansOwnPresentationIsNotItsPresentation() throws Exception {
        final IGuiBase forge = GuiBase.getInterface();
        final Harness h = new Harness();
        final AtomicReference<Thread> gameThread = new AtomicReference<>();
        final CountDownLatch waitingRan = new CountDownLatch(1);
        final AtomicBoolean held = new AtomicBoolean();
        final AtomicReference<Boolean> kept = new AtomicReference<>();
        final CountDownLatch decided = new CountDownLatch(1);
        GuiBase.setInterface(holdShowUntilWaitingPromptRan(forge, h, gameThread, waitingRan, held));
        try {
            final Thread forgeThread = new Thread(() -> {
                try {
                    // GameAction.determineFirstTurnPlayer: the seat that does not choose who plays first waits.
                    h.controller.awaitNextInput();
                    // MulliganService.runPlayerMulligans: the opponent (on the play) has decided; now this seat.
                    kept.set(h.controller.mulliganKeepHand(h.foe, 0));
                } finally {
                    decided.countDown();
                }
            }, "Game thread (mulligan presentation test)");
            forgeThread.setDaemon(true);
            gameThread.set(forgeThread);
            forgeThread.start();

            final JsonObject first = h.nextRequestOrError(60000);
            assertNotNull(first, "a request or an error; wire: " + h.log);
            assertTrue(held.get(), "the game thread was held in the InputProxy window");
            assertEquals(first.get("type").getAsString(), "request",
                    "the mulligan was published before Forge presented it: " + first);
            assertEquals(first.get("kind").getAsString(), "mulligan", first.toString());
            assertEquals(first.get("inputClass").getAsString(), "InputConfirmMulligan", first.toString());
            final List<String> ids = controlIds(first);
            assertTrue(ids.contains("button:ok") && ids.contains("button:cancel"), "Keep and Mulligan: " + ids);
            assertEquals(label(first, "button:ok"), "Keep", first.toString());
            assertEquals(label(first, "button:cancel"), "Mulligan", first.toString());
            assertTrue(first.getAsJsonObject("prompt").get("message").getAsString().contains("keep your hand"),
                    "the mulligan's own prompt: " + first.getAsJsonObject("prompt"));
            assertTrue(h.quiet(500).isEmpty(), "one request, not replaced before an answer: " + h.log);

            h.answer(first, "ok", "button:ok");
            assertTrue(decided.await(30, TimeUnit.SECONDS), "Forge's mulligan question returned");
            assertEquals(kept.get(), Boolean.TRUE, "Keep was answered");
        } finally {
            GuiBase.setInterface(forge);
            h.close();
        }
    }

    /** An input that presents itself with both buttons off and nothing selectable: the bridge offers no human control. */
    private static final class NothingToPress extends InputSyncronizedBase {
        private static final long serialVersionUID = 1L;

        NothingToPress(final PlayerControllerHuman controller) {
            super(controller);
        }

        @Override
        protected void showMessage() {
            getController().getGui().updateButtons(getOwner(), false, false, false);
            showMessage("Nothing here can be answered");
        }

        @Override
        public String getActivateAction(final forge.game.card.Card card) {
            return null;
        }
    }

    /** An input whose own presentation has no human control still ends the game as unsupported. */
    @Test(timeOut = 120000)
    public void aPresentedInputWithNoHumanControlStillFails() throws Exception {
        final Harness h = new Harness();
        try {
            final Thread forgeThread = new Thread(() -> new NothingToPress(h.controller).showAndWait(),
                    "Game thread (mulligan presentation test, unsupported)");
            forgeThread.setDaemon(true);
            forgeThread.start();

            final JsonObject first = h.nextRequestOrError(60000);
            assertNotNull(first, "a request or an error; wire: " + h.log);
            if (!"error".equals(first.get("type").getAsString())) {
                fail("an input with no human control was published: " + first);
            }
            assertEquals(first.get("code").getAsString(), "unsupported", first.toString());
            assertEquals(first.get("method").getAsString(), "publishCurrentInput", first.toString());
            assertTrue(first.get("message").getAsString().contains("exposed no human controls"), first.toString());
            assertTrue(h.gui.hasFailed(), "the seat failed");
        } finally {
            h.close();
        }
    }
}
