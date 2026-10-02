package forge.interactive;

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
 * A target choice says what it targets and carries the facts a client needs to answer it
 * (owner report 2026-10-02T16-19-27: casting Reprieve at the foe's Time Walk asked "Select target
 * Card.inZoneStack", with one legal target).
 *
 * <p>{@code request.context}: {@code target}, {@code targetRequired}, {@code targetMin},
 * {@code targetMax}, {@code targetChosen}, {@code targetSourceCardId}, and per option
 * {@code targetOption} = {@code controllerSeat} / {@code playerSeat} / {@code finishTargeting} /
 * {@code heading}. Seat numbers are registered order: here the foe is 0 and the seat is 1.</p>
 */
public class InteractiveTargetPromptTest extends AITest {

    private static final String SESSION = "target-prompt-test";

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
            controller.getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
            seat.dangerouslySetController(controller);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(
                    new BufferedReader(new PipedReader(client, 1 << 16)),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 1);
            gui.bind(game, seat, controller);
            controller.setGui(gui);
            gui.setGameView(game.getView());
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui, new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            gui.startReader();
            // The seat's priority loop on a thread named like Forge's game threads.
            final Thread forgeThread = new Thread(() -> {
                while (!closed.get() && !gui.hasFailed() && !game.isGameOver()) {
                    final List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
                    if (chosen != null) {
                        for (SpellAbility ability : chosen) {
                            controller.playChosenSpellAbility(ability);
                        }
                    }
                }
            }, "Game thread (target prompt test)");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        JsonObject request() throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final JsonObject m = wire.poll(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                assertNotNull(m, "a request");
                if ("error".equals(m.get("type").getAsString())) {
                    fail("the bridge failed: " + m);
                }
                if ("request".equals(m.get("type").getAsString())) {
                    return m;
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

        /** Cast {@code card} from the priority request and return the next request. */
        JsonObject cast(final Card card) throws IOException, InterruptedException {
            final JsonObject priority = request();
            assertEquals(priority.get("kind").getAsString(), "priority", priority.toString());
            answer(priority, "selectCard", "card:" + card.getId());
            return request();
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

    /** A spell put on the stack by {@code caster}, as GameCopierStackTest does. */
    private static Card onStack(final Game game, final Card card, final Player caster) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        game.getStackZone().add(card);
        game.getStack().add(sa);
        return card;
    }

    private static JsonObject context(final JsonObject request) {
        assertTrue(request.has("context"), "a target request carries its facts: " + request);
        return request.getAsJsonObject("context");
    }

    /** The report: Reprieve with the foe's Time Walk the only spell on the stack. */
    @Test(timeOut = 900000)
    public void reprieveAtTheFoesSpellNamesItAndSaysItIsTheFoes() throws Exception {
        final Card[] reprieve = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Plains", seat);
            addCard("Plains", seat);
            reprieve[0] = addCardToZone("Reprieve", seat, ZoneType.Hand);
            onStack(seat.getGame(), addCardToZone("Time Walk", foe, ZoneType.Hand), foe);
        })) {
            final JsonObject target = h.cast(reprieve[0]);
            assertEquals(target.get("inputClass").getAsString(), "modal:getChoices", target.toString());
            final String message = target.getAsJsonObject("prompt").get("message").getAsString();
            assertEquals(message, "Select target spell");
            assertFalse(target.toString().contains("inZoneStack"), target.toString());
            final JsonObject context = context(target);
            assertTrue(context.get("target").getAsBoolean());
            assertTrue(context.get("targetRequired").getAsBoolean(), context.toString());
            assertEquals(context.get("targetMin").getAsInt(), 1);
            assertEquals(context.get("targetChosen").getAsInt(), 0);
            assertEquals(context.get("targetSourceCardId").getAsInt(), reprieve[0].getId());
            assertEquals(context.get("targetSourceName").getAsString(), "Reprieve");
            final JsonObject options = context.getAsJsonObject("targetOption");
            assertEquals(options.size(), 1, options.toString());
            assertEquals(options.getAsJsonObject("choice:0").get("controllerSeat").getAsInt(), 0, "the foe's spell");
        }
    }

    /** The seat's own spell as the only legal target: the facts say it is the seat's. */
    @Test(timeOut = 900000)
    public void reprieveAtTheSeatsOwnSpellSaysItIsTheSeats() throws Exception {
        final Card[] reprieve = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Plains", seat);
            addCard("Plains", seat);
            reprieve[0] = addCardToZone("Reprieve", seat, ZoneType.Hand);
            onStack(seat.getGame(), addCardToZone("Divination", seat, ZoneType.Hand), seat);
        })) {
            final JsonObject context = context(h.cast(reprieve[0]));
            assertTrue(context.get("targetRequired").getAsBoolean());
            assertEquals(context.getAsJsonObject("targetOption").getAsJsonObject("choice:0")
                    .get("controllerSeat").getAsInt(), 1, "the seat's own spell");
        }
    }

    /** Removal on the battlefield (InputSelectTargets): per-card controller facts. */
    @Test(timeOut = 900000)
    public void swordsToPlowsharesSaysWhoControlsEachCreature() throws Exception {
        final Card[] swords = new Card[1];
        final Card[] bears = new Card[1];
        final Card[] own = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Plains", seat);
            swords[0] = addCardToZone("Swords to Plowshares", seat, ZoneType.Hand);
            bears[0] = addCard("Grizzly Bears", foe);
            own[0] = addCard("Llanowar Elves", seat);
        })) {
            final JsonObject target = h.cast(swords[0]);
            assertEquals(target.get("kind").getAsString(), "target", target.toString());
            final JsonObject context = context(target);
            assertTrue(context.get("targetRequired").getAsBoolean());
            final JsonObject options = context.getAsJsonObject("targetOption");
            assertEquals(options.getAsJsonObject("card:" + bears[0].getId()).get("controllerSeat").getAsInt(), 0);
            assertEquals(options.getAsJsonObject("card:" + own[0].getId()).get("controllerSeat").getAsInt(), 1);
        }
    }

    /** A player target: playerSeat per player control. */
    @Test(timeOut = 900000)
    public void mindRotSaysWhichSeatEachPlayerIs() throws Exception {
        final Card[] rot = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            addCard("Swamp", seat);
            rot[0] = addCardToZone("Mind Rot", seat, ZoneType.Hand);
        })) {
            final JsonObject target = h.cast(rot[0]);
            final JsonObject options = context(target).getAsJsonObject("targetOption");
            assertEquals(options.getAsJsonObject("player:0").get("playerSeat").getAsInt(), 0);
            assertEquals(options.getAsJsonObject("player:1").get("playerSeat").getAsInt(), 1);
        }
    }

    /** "Up to one target": not required, so a client must still ask. */
    @Test(timeOut = 900000)
    public void anUpToOneTargetIsNotRequired() throws Exception {
        final Card[] ratOut = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Swamp", seat);
            ratOut[0] = addCardToZone("Rat Out", seat, ZoneType.Hand);
            addCard("Grizzly Bears", foe);
        })) {
            final JsonObject context = context(h.cast(ratOut[0]));
            assertFalse(context.get("targetRequired").getAsBoolean(), context.toString());
            assertEquals(context.get("targetMin").getAsInt(), 0);
        }
    }

    /** Not a target choice: no target facts. */
    @Test(timeOut = 900000)
    public void aPriorityRequestHasNoTargetFacts() throws Exception {
        try (Harness h = new Harness((seat, foe) -> addCard("Plains", seat))) {
            final JsonObject priority = h.request();
            assertFalse(priority.has("context"), priority.toString());
        }
    }
}
