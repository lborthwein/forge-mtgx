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
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Two owner reports from 2026-10-06 (lane client-bugs-1006).
 *
 * <ul>
 *   <li>00-39-37: "tapping Botanical Sanctum to pay for Oko, it always just gave me [U] instead of
 *       giving me the option for [U] or G". The browser seat's produced mana floats
 *       ({@link FullControlFlag#NoPaymentFromManaAbility}), so the cost never shrank and Forge's
 *       express choice took the first needed colour every time. Now the floating mana is counted
 *       first, and when more than one colour is still needed the seat is asked.</li>
 *   <li>01-51-13: a Phyrexian Revoker's named card was on no surface of the table. The wire now
 *       carries {@code namedCards} on the object.</li>
 * </ul>
 *
 * <p>The harness is {@link InteractivePublishTest}'s: Forge's real cast path for the seat on a
 * thread named like Forge's game threads.</p>
 */
public class InteractivePaymentColourTest extends AITest {

    private static final String SESSION = "payment-colour-test";

    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();

        Harness(final BiConsumer<Player, Player> board) throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            board.accept(seat, foe);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat);
            game.getAction().checkStateEffects(true);

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
            }, "Game thread (payment colour test)");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        /** Acks read past while waiting for something else, by inputId. */
        final java.util.Map<String, JsonObject> acks = new java.util.HashMap<>();

        JsonObject next(final String type) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                final String got = message.get("type").getAsString();
                if ("error".equals(got)) {
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

        /** The ack for {@code inputId}, read now or earlier; it must be accepted. */
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

        /** Send an input without waiting for its ack; returns its inputId. */
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

        void answer(final JsonObject request, final JsonObject action) throws IOException, InterruptedException {
            acked(send(request, action));
        }

        String sendSelect(final JsonObject request, final Card card) throws IOException {
            final JsonObject action = new JsonObject();
            action.addProperty("type", "selectCard");
            action.addProperty("controlId", "card:" + card.getId());
            return send(request, action);
        }

        void select(final JsonObject request, final Card card) throws IOException, InterruptedException {
            acked(sendSelect(request, card));
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

    /** The seat's floating mana of one colour ("W", "U", "B", "R", "G", "C") on the request's view. */
    private static int floating(final JsonObject request, final String colour) {
        for (JsonElement p : request.getAsJsonObject("view").getAsJsonArray("players")) {
            final JsonObject player = p.getAsJsonObject();
            if (player.get("index").getAsInt() == 1) {
                return player.getAsJsonObject("manaPool").get(colour).getAsInt();
            }
        }
        fail("no seat 1 in " + request);
        return -1;
    }

    /** The control labelled {@code label}, or null. */
    private static JsonObject control(final JsonObject request, final String label) {
        for (JsonElement c : request.getAsJsonArray("controls")) {
            if (label.equals(c.getAsJsonObject().get("label").getAsString())) {
                return c.getAsJsonObject();
            }
        }
        return null;
    }

    /** Cast {@code spell} and return its payment request. */
    private static JsonObject cast(final Harness h, final Card spell) throws Exception {
        final JsonObject priority = h.request();
        assertEquals(kind(priority), "priority", priority.toString());
        h.select(priority, spell);
        final JsonObject payment = h.request();
        assertEquals(kind(payment), "mana", payment.toString());
        return payment;
    }

    /** The reported game: Oko ({1}{G}{U}), Botanical Sanctum tapped first. The seat says which. */
    @Test(timeOut = 600000)
    public void aDualLandAsksWhichColourWhenTheCostStillNeedsBoth() throws Exception {
        final Card[] sanctum = new Card[1];
        final Card[] oko = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            sanctum[0] = addCard("Botanical Sanctum", seat);
            addCard("Island", seat);
            addCard("Island", seat);
            oko[0] = addCardToZone("Oko, Thief of Crowns", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, oko[0]);
            // The question is asked inside the tap, so it arrives before the tap's ack (as Lotus
            // Petal's colour question does during a payment).
            final String tap = h.sendSelect(payment, sanctum[0]);
            final JsonObject question = h.request();
            assertEquals(kind(question), "confirm", "a colour question, not " + question);
            // The question Forge asks when the land is tapped at priority, naming the land (the
            // browser opens it over that card).
            assertEquals(message(question), "Botanical Sanctum (" + sanctum[0].getId() + ")\n\nSelect Mana to Produce");
            final JsonObject green = control(question, "Green");
            assertNotNull(green, "Green is offered: " + question);
            assertNotNull(control(question, "Blue"), "Blue is offered: " + question);
            final JsonObject action = new JsonObject();
            action.addProperty("type", "confirm");
            action.addProperty("controlId", green.get("controlId").getAsString());
            action.addProperty("confirm", green.get("value").getAsBoolean());
            h.answer(question, action);
            h.acked(tap);
            final JsonObject after = h.request();
            assertEquals(kind(after), "mana", after.toString());
            assertEquals(floating(after, "G"), 1, "the Sanctum made G: " + after);
            assertEquals(floating(after, "U"), 0, "and not U: " + after);
        }
    }

    /** An Island floated the {U}: the Sanctum makes the G that is left, without a question. */
    @Test(timeOut = 600000)
    public void aDualLandMakesTheOneColourTheFloatingManaLeavesNeeded() throws Exception {
        final Card[] sanctum = new Card[1];
        final Card[] island = new Card[1];
        final Card[] oko = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            sanctum[0] = addCard("Botanical Sanctum", seat);
            island[0] = addCard("Island", seat);
            addCard("Island", seat);
            oko[0] = addCardToZone("Oko, Thief of Crowns", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, oko[0]);
            h.select(payment, island[0]);
            final JsonObject afterIsland = h.request();
            assertEquals(kind(afterIsland), "mana", afterIsland.toString());
            assertEquals(floating(afterIsland, "U"), 1, afterIsland.toString());
            h.select(afterIsland, sanctum[0]);
            final JsonObject afterSanctum = h.request();
            assertEquals(kind(afterSanctum), "mana", "no question: " + afterSanctum);
            assertEquals(floating(afterSanctum, "G"), 1, "the Sanctum made the G: " + afterSanctum);
            assertEquals(floating(afterSanctum, "U"), 1, afterSanctum.toString());
        }
    }

    /** Unchanged: a cost that needs one of the land's colours gets it without a question. */
    @Test(timeOut = 600000)
    public void aDualLandMakesTheOnlyNeededColourAsBefore() throws Exception {
        final Card[] sanctum = new Card[1];
        final Card[] brainstorm = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            sanctum[0] = addCard("Botanical Sanctum", seat);
            brainstorm[0] = addCardToZone("Brainstorm", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, brainstorm[0]);
            h.select(payment, sanctum[0]);
            final JsonObject after = h.request();
            assertEquals(kind(after), "mana", "no question: " + after);
            assertEquals(floating(after, "U"), 1, after.toString());
            assertEquals(floating(after, "G"), 0, after.toString());
        }
    }

    /** Phyrexian Revoker's choice is on the wire, for both seats' views. */
    @Test(timeOut = 600000)
    public void aNamedCardIsOnTheWire() throws Exception {
        final Card[] revoker = new Card[1];
        final Card[] plain = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Island", seat);
            revoker[0] = addCard("Phyrexian Revoker", foe);
            revoker[0].addNamedCard("Oko, Thief of Crowns");
            plain[0] = addCard("Phyrexian Revoker", foe);
        })) {
            final JsonObject priority = h.request();
            JsonObject named = null;
            JsonObject unnamed = null;
            for (JsonElement p : priority.getAsJsonObject("view").getAsJsonArray("players")) {
                for (JsonElement c : p.getAsJsonObject().getAsJsonArray("battlefield")) {
                    final int fid = c.getAsJsonObject().get("fid").getAsInt();
                    if (fid == revoker[0].getId()) {
                        named = c.getAsJsonObject();
                    } else if (fid == plain[0].getId()) {
                        unnamed = c.getAsJsonObject();
                    }
                }
            }
            assertNotNull(named, "the Revoker is in view: " + priority);
            assertNotNull(unnamed, "the other Revoker is in view: " + priority);
            assertEquals(named.getAsJsonArray("namedCards").size(), 1, named.toString());
            assertEquals(named.getAsJsonArray("namedCards").get(0).getAsString(), "Oko, Thief of Crowns");
            assertFalse(unnamed.has("namedCards"), "nothing named, no field: " + unnamed);
        }
    }
}
