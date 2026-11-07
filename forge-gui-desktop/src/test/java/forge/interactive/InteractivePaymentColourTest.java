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
        /** Every event read so far. */
        final List<JsonObject> events = new java.util.ArrayList<>();

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
                if ("event".equals(got) && message.has("event")) {
                    events.add(message.getAsJsonObject("event"));
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

    /**
     * An Island floated the {U}: {1}{G} is left, and the Sanctum's {U} can pay the {1}, so the seat
     * is asked (owner, 2026-11-07: a dual land with a cost pending asks among the colours that can pay).
     */
    @Test(timeOut = 600000)
    public void aDualLandAsksWhenTheCostLeftHasAGenericPart() throws Exception {
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
            final String tap = h.sendSelect(afterIsland, sanctum[0]);
            final JsonObject question = h.request();
            assertEquals(kind(question), "confirm", "a colour question, not " + question);
            final JsonObject blue = control(question, "Blue");
            assertNotNull(blue, "Blue is offered: " + question);
            assertNotNull(control(question, "Green"), "Green is offered: " + question);
            final JsonObject action = new JsonObject();
            action.addProperty("type", "confirm");
            action.addProperty("controlId", blue.get("controlId").getAsString());
            action.addProperty("confirm", blue.get("value").getAsBoolean());
            h.answer(question, action);
            h.acked(tap);
            final JsonObject afterSanctum = h.request();
            assertEquals(kind(afterSanctum), "mana", afterSanctum.toString());
            assertEquals(floating(afterSanctum, "U"), 2, "the Sanctum made the U it was asked for: " + afterSanctum);
            assertEquals(floating(afterSanctum, "G"), 0, afterSanctum.toString());
            noForcedColour(h);
        }
    }

    /** Only the land's {U} can pay a {U}: Forge takes it without a question, and the log says which. */
    @Test(timeOut = 600000)
    public void aDualLandMakesTheOnlyColourThatCanPayAndTheLogSaysWhich() throws Exception {
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
            forcedColour(h, "Botanical Sanctum", "U");
        }
    }

    /** The color list Forge offers an any-color source, as its item labels. */
    private static List<String> colourOptions(final JsonObject request) {
        final List<String> labels = new java.util.ArrayList<>();
        for (JsonElement item : control(request, "Select Mana to Produce").getAsJsonArray("items")) {
            labels.add(item.getAsJsonObject().get("label").getAsString());
        }
        return labels;
    }

    private static void chooseColour(final Harness h, final JsonObject question, final String colour) throws Exception {
        final JsonObject chooser = control(question, "Select Mana to Produce");
        assertNotNull(chooser, "a color list: " + question);
        String id = null;
        for (JsonElement item : chooser.getAsJsonArray("items")) {
            if (colour.equals(item.getAsJsonObject().get("label").getAsString())) {
                id = item.getAsJsonObject().get("id").getAsString();
            }
        }
        assertNotNull(id, colour + " is offered: " + question);
        final JsonObject action = new JsonObject();
        action.addProperty("type", "choice");
        action.addProperty("controlId", "choices");
        final com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
        choices.add(id);
        action.add("choices", choices);
        h.answer(question, action);
    }

    /** Answer the source's own costs (tap {@code payer} for its mana, pay with the pool, yes to the sacrifice) until a request that is neither. */
    private static JsonObject throughSourceCosts(final Harness h, JsonObject request, final Card payer) throws Exception {
        while (true) {
            final String kind = kind(request);
            final JsonObject action = new JsonObject();
            if ("confirm".equals(kind) && control(request, "Yes") != null && message(request).contains("Sacrifice")) {
                action.addProperty("type", "confirm");
                action.addProperty("controlId", "confirm:yes");
                action.addProperty("confirm", true);
                h.answer(request, action);
            } else if ("mana".equals(kind) && payer != null && payer.isUntapped()) {
                h.select(request, payer);
            } else if ("mana".equals(kind) && payer != null && request.getAsJsonArray("controls").toString().contains("payment:0")) {
                action.addProperty("type", "choice");
                action.addProperty("controlId", "payment:0");
                h.answer(request, action);
            } else {
                return request;
            }
            request = h.request();
        }
    }

    private static void noForcedColour(final Harness h) {
        for (JsonObject event : h.events) {
            assertFalse(event.has("kind") && "mana_color_forced".equals(event.get("kind").getAsString()),
                    "no color was forced: " + event);
        }
    }

    private static void forcedColour(final Harness h, final String card, final String colour) {
        for (JsonObject event : h.events) {
            if (event.has("kind") && "mana_color_forced".equals(event.get("kind").getAsString())) {
                assertEquals(event.get("card").getAsString(), card, event.toString());
                assertEquals(event.get("color").getAsString(), colour, event.toString());
                assertEquals(event.get("playerSeat").getAsInt(), 1, event.toString());
                return;
            }
        }
        fail("the log is told which color: " + h.events);
    }

    /** An any-color source with nothing being paid: the seat says which color. */
    @Test(timeOut = 600000)
    public void anAnyColourSourceAsksWithAnEmptyStack() throws Exception {
        final Card[] star = new Card[1];
        final Card[] island = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            star[0] = addCard("Chromatic Star", seat);
            island[0] = addCard("Island", seat);
            addCard("Plains", foe);
        })) {
            final JsonObject priority = h.request();
            assertEquals(kind(priority), "priority", priority.toString());
            final String activate = h.sendSelect(priority, star[0]);
            final JsonObject question = throughSourceCosts(h, h.request(), island[0]);
            assertEquals(colourOptions(question).size(), 5, question.toString());
            chooseColour(h, question, "BLUE");
            h.acked(activate);
            final JsonObject after = h.request();
            assertEquals(floating(after, "U"), 1, after.toString());
            noForcedColour(h);
        }
    }

    /**
     * The report: the source was tapped for a {B}{B}{B}, its own cost was cancelled, and later, with
     * nothing being paid, it made {B} with no question. A cancelled activation leaves no color behind.
     */
    @Test(timeOut = 600000)
    public void aCancelledActivationLeavesNoColourBehind() throws Exception {
        final Card[] star = new Card[1];
        final Card[] island = new Card[1];
        final Card[] doomsday = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            star[0] = addCard("Chromatic Star", seat);
            island[0] = addCard("Island", seat);
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            doomsday[0] = addCardToZone("Doomsday", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, doomsday[0]);
            // No ack yet: the tap is still open while the source's own {1} is asked.
            h.sendSelect(payment, star[0]);
            JsonObject request = h.request();
            assertEquals(kind(request), "mana", "the source's own {1}: " + request);
            while ("mana".equals(kind(request))) {
                final JsonObject cancel = new JsonObject();
                cancel.addProperty("type", "cancel");
                cancel.addProperty("controlId", "button:cancel");
                h.send(request, cancel);
                request = h.request();
            }
            assertEquals(kind(request), "priority", request.toString());
            assertTrue(star[0].isInPlay(), "the source is still there");
            final String activate = h.sendSelect(request, star[0]);
            final JsonObject question = throughSourceCosts(h, h.request(), island[0]);
            assertEquals(colourOptions(question).size(), 5, "every color, not the cancelled payment's: " + question);
            chooseColour(h, question, "BLUE");
            h.acked(activate);
            final JsonObject after = h.request();
            assertEquals(floating(after, "U"), 1, after.toString());
            assertEquals(floating(after, "B"), 0, after.toString());
            noForcedColour(h);
        }
    }

    /** A cost pending with a generic part: every color can pay it, so the seat is asked. */
    @Test(timeOut = 600000)
    public void anAnyColourSourceAsksWithACostPending() throws Exception {
        final Card[] petal = new Card[1];
        final Card[] whisper = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            petal[0] = addCard("Lotus Petal", seat);
            addCard("Swamp", seat);
            whisper[0] = addCardToZone("Night's Whisper", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, whisper[0]);
            final String tap = h.sendSelect(payment, petal[0]);
            final JsonObject question = throughSourceCosts(h, h.request(), null);
            assertEquals(colourOptions(question).size(), 5, question.toString());
            chooseColour(h, question, "BLUE");
            h.acked(tap);
            final JsonObject after = h.request();
            assertEquals(kind(after), "mana", after.toString());
            assertEquals(floating(after, "U"), 1, after.toString());
            noForcedColour(h);
        }
    }

    /** Only one color can pay a {B}{B}{B}: Forge may take it, and the log is told which. */
    @Test(timeOut = 600000)
    public void onlyOneColourCanPayAndTheLogSaysWhich() throws Exception {
        final Card[] petal = new Card[1];
        final Card[] doomsday = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            petal[0] = addCard("Lotus Petal", seat);
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            doomsday[0] = addCardToZone("Doomsday", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, doomsday[0]);
            final String tap = h.sendSelect(payment, petal[0]);
            final JsonObject after = throughSourceCosts(h, h.request(), null);
            h.acked(tap);
            assertEquals(kind(after), "mana", "no color question: " + after);
            assertEquals(floating(after, "B"), 1, after.toString());
            forcedColour(h, "Lotus Petal", "B");
        }
    }

    /** Floating mana already covers the generic part of a {1}{B}: only {B} is left, and the log says so. */
    @Test(timeOut = 600000)
    public void floatingManaThatLeavesOneColourForcesIt() throws Exception {
        final Card[] petal = new Card[1];
        final Card[] island = new Card[1];
        final Card[] whisper = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            petal[0] = addCard("Lotus Petal", seat);
            island[0] = addCard("Island", seat);
            whisper[0] = addCardToZone("Night's Whisper", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, whisper[0]);
            h.select(payment, island[0]);
            final JsonObject afterIsland = h.request();
            final String tap = h.sendSelect(afterIsland, petal[0]);
            final JsonObject after = throughSourceCosts(h, h.request(), null);
            h.acked(tap);
            assertEquals(kind(after), "mana", "no color question: " + after);
            assertEquals(floating(after, "B"), 1, after.toString());
            forcedColour(h, "Lotus Petal", "B");
        }
    }

    /** The first control on {@code request} that lists items. */
    private static JsonObject itemsControl(final JsonObject request) {
        for (JsonElement c : request.getAsJsonArray("controls")) {
            if (c.getAsJsonObject().has("items")) {
                return c.getAsJsonObject();
            }
        }
        fail("no item list: " + request);
        return null;
    }

    private static List<String> itemLabels(final JsonObject control) {
        final List<String> labels = new java.util.ArrayList<>();
        for (JsonElement item : control.getAsJsonArray("items")) {
            labels.add(item.getAsJsonObject().get("label").getAsString());
        }
        return labels;
    }

    /**
     * A dual land with two abilities (its basic land types) paying {1}{G}: its {U} pays the {1}, so the
     * seat chooses between them. Before, Forge kept only the {G} ability and took it with no question.
     */
    @Test(timeOut = 600000)
    public void aTwoAbilityDualLandAsksWithAGenericPartPending() throws Exception {
        final Card[] island = new Card[1];
        final Card[] library = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            island[0] = addCard("Tropical Island", seat);
            library[0] = addCardToZone("Sylvan Library", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, library[0]);
            final String tap = h.sendSelect(payment, island[0]);
            final JsonObject question = h.request();
            assertFalse("mana".equals(kind(question)), "a question, not " + question);
            final JsonObject list = itemsControl(question);
            final List<String> labels = itemLabels(list);
            assertEquals(labels.size(), 2, "both abilities: " + question);
            String id = null;
            for (JsonElement item : list.getAsJsonArray("items")) {
                if (item.getAsJsonObject().get("label").getAsString().contains("{U}")) {
                    id = item.getAsJsonObject().get("id").getAsString();
                }
            }
            assertNotNull(id, "the {U} ability is offered: " + labels);
            final JsonObject action = new JsonObject();
            action.addProperty("type", "choice");
            action.addProperty("controlId", list.get("controlId").getAsString());
            final com.google.gson.JsonArray choices = new com.google.gson.JsonArray();
            choices.add(id);
            action.add("choices", choices);
            h.answer(question, action);
            h.acked(tap);
            final JsonObject after = h.request();
            assertEquals(kind(after), "mana", after.toString());
            assertEquals(floating(after, "U"), 1, after.toString());
            assertEquals(floating(after, "G"), 0, after.toString());
            noForcedColour(h);
        }
    }

    /** The same land paying a {U}: only its {U} can pay, so Forge takes it and the log says which. */
    @Test(timeOut = 600000)
    public void aTwoAbilityDualLandMakesTheOnlyColourThatCanPayAndTheLogSaysWhich() throws Exception {
        final Card[] island = new Card[1];
        final Card[] brainstorm = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            island[0] = addCard("Tropical Island", seat);
            brainstorm[0] = addCardToZone("Brainstorm", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, brainstorm[0]);
            h.select(payment, island[0]);
            final JsonObject after = h.request();
            assertEquals(kind(after), "mana", "no question: " + after);
            assertEquals(floating(after, "U"), 1, after.toString());
            forcedColour(h, "Tropical Island", "U");
        }
    }

    /** "Any combination", amount 2, answered as an allocation of {@code split} (colour label to count). */
    private static void allocate(final Harness h, final JsonObject question, final java.util.Map<String, Integer> split)
            throws Exception {
        final JsonObject allocation = control(question, "Assign mana");
        assertNotNull(allocation, "an allocation: " + question);
        final com.google.gson.JsonArray order = new com.google.gson.JsonArray();
        for (JsonElement item : allocation.getAsJsonArray("items")) {
            final String label = item.getAsJsonObject().get("label").getAsString().toUpperCase(java.util.Locale.ROOT);
            if (split.containsKey(label)) {
                final JsonObject entry = new JsonObject();
                entry.addProperty("id", item.getAsJsonObject().get("id").getAsString());
                entry.addProperty("amount", split.get(label));
                order.add(entry);
            }
        }
        assertEquals(order.size(), split.size(), "every colour is offered: " + question);
        final JsonObject action = new JsonObject();
        action.addProperty("type", "order");
        action.addProperty("controlId", "generic-allocation");
        action.add("order", order);
        h.answer(question, action);
    }

    private static List<String> allocationLabels(final JsonObject question) {
        final JsonObject allocation = control(question, "Assign mana");
        assertNotNull(allocation, "an allocation: " + question);
        final List<String> labels = new java.util.ArrayList<>();
        for (String label : itemLabels(allocation)) {
            labels.add(label.toUpperCase(java.util.Locale.ROOT));
        }
        return labels;
    }

    /**
     * "Add two mana in any combination of colors" paying {U}{B}, a cost pending: the seat assigns the
     * two mana among the colours that can pay, {U} and {B}. Before, Forge narrowed each mana by its
     * position in the cost with no question.
     */
    @Test(timeOut = 600000)
    public void anyCombinationAmountTwoAsksAmongTheColoursThatCanPay() throws Exception {
        final Card[] key = new Card[1];
        final Card[] strix = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            key[0] = addCard("Key to the Archive", seat);
            key[0].setTapped(false);
            strix[0] = addCardToZone("Baleful Strix", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, strix[0]);
            final String tap = h.sendSelect(payment, key[0]);
            final JsonObject question = h.request();
            assertEquals(new java.util.TreeSet<>(allocationLabels(question)),
                    new java.util.TreeSet<>(List.of("BLUE", "BLACK")), question.toString());
            allocate(h, question, java.util.Map.of("BLUE", 2));
            h.acked(tap);
            final JsonObject after = h.request();
            assertEquals(floating(after, "U"), 2, "the seat's combination, not the cost's order: " + after);
            assertEquals(floating(after, "B"), 0, after.toString());
            noForcedColour(h);
        }
    }

    /** The same source paying {1}{G}{U}: the {1} lets every colour pay, so all five are offered. */
    @Test(timeOut = 600000)
    public void anyCombinationAmountTwoWithAGenericPartOffersEveryColour() throws Exception {
        final Card[] key = new Card[1];
        final Card[] oko = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            key[0] = addCard("Key to the Archive", seat);
            key[0].setTapped(false);
            addCard("Island", seat);
            oko[0] = addCardToZone("Oko, Thief of Crowns", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, oko[0]);
            final String tap = h.sendSelect(payment, key[0]);
            final JsonObject question = h.request();
            assertEquals(allocationLabels(question).size(), 5, question.toString());
            allocate(h, question, java.util.Map.of("GREEN", 1, "RED", 1));
            h.acked(tap);
            final JsonObject after = h.request();
            assertEquals(floating(after, "G"), 1, after.toString());
            assertEquals(floating(after, "R"), 1, after.toString());
            noForcedColour(h);
        }
    }

    /** The same source paying {B}{B}: only {B} can pay, so both mana are {B} and the log says so. */
    @Test(timeOut = 600000)
    public void anyCombinationAmountTwoWithOneColourForcesItAndTheLogSaysWhich() throws Exception {
        final Card[] key = new Card[1];
        final Card[] hymn = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            key[0] = addCard("Key to the Archive", seat);
            key[0].setTapped(false);
            hymn[0] = addCardToZone("Hymn to Tourach", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject payment = cast(h, hymn[0]);
            h.select(payment, key[0]);
            final JsonObject after = h.request();
            assertEquals(kind(after), "mana", "no question: " + after);
            assertEquals(floating(after, "B"), 2, after.toString());
            forcedColour(h, "Key to the Archive", "B");
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
