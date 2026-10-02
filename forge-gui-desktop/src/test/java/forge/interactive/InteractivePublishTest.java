package forge.interactive;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.card.mana.ManaAtom;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.Mana;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.control.FControlGameEventHandler;
import forge.player.PlayerControllerHuman;
import forge.game.player.PlayerController.FullControlFlag;
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
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * What the bridge publishes, and when (lane bridge-race-1002, 2026-10-02).
 *
 * <ul>
 *   <li>A request is published only where the seat has a decision to make, once, and is never
 *       replaced before an input. Before the fix a mana-payment request sometimes went out with
 *       the previous prompt ("Pay Mana Cost: {4}{R}" after the {1} was paid) and was replaced
 *       1 ms later by the current one ({3}{R}). Replays of one seed and one input stream then
 *       differed in 2-3 of 20 games.</li>
 *   <li>Forge's state notifications (zones, cards, lives, phase, stack ...) do not publish or
 *       enumerate controls; only a presentation or a settled action does.</li>
 *   <li>A prompt keeps the bare name of the card it is about when the seat may see that card
 *       ("Lightning Bolt (4) - Lightning Bolt deals 3 damage", not "Face-down card deals"), and a
 *       face-down card stays hidden.</li>
 * </ul>
 *
 * <p>The harness runs Forge's real cast path for the seat (choose a spell, target it, pay for it)
 * on a thread named like Forge's game threads, so {@code GameAction.invoke} runs mana abilities
 * inline as it does in a match.</p>
 */
public class InteractivePublishTest extends AITest {

    private static final String SESSION = "publish-test";
    private static final Pattern COST = Pattern.compile("Pay Mana Cost: ((?:\\{[^}]+})+)");

    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final List<JsonObject> log = new ArrayList<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();
        Harness(final BiConsumer<Player, Player> board) throws IOException {
            this(board, null);
        }

        /** @param beforePriority run on the game thread before each priority decision, or null */
        Harness(final BiConsumer<Player, Player> board, final java.util.function.Consumer<Player> beforePriority)
                throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            board.accept(seat, foe);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat);
            game.getAction().checkStateEffects(true);

            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            // As InteractiveMain.configureHumanPayment: produced mana waits in the pool. (Its yield and
            // trigger settings need a human lobby player; the test seats have AI lobby players.)
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

            // Forge's priority loop for the seat, minus passing the turn: a pass offers priority again.
            final Thread forgeThread = new Thread(() -> {
                while (!closed.get() && !gui.hasFailed() && !game.isGameOver()) {
                    if (beforePriority != null) {
                        beforePriority.accept(seat);
                    }
                    final List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
                    if (chosen == null) {
                        continue;
                    }
                    for (SpellAbility ability : chosen) {
                        controller.playChosenSpellAbility(ability);
                    }
                }
            }, "Game thread (publish test)");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        /** The next message of {@code type}; every message is logged. */
        JsonObject next(final String type, final long timeoutMs) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return null;
                }
                log.add(message);
                if ("error".equals(message.get("type").getAsString())) {
                    fail("the bridge failed: " + message);
                }
                if (type.equals(message.get("type").getAsString())) {
                    return message;
                }
            }
        }

        /** Every request or ack that arrives within {@code ms}. */
        List<JsonObject> quiet(final long ms) throws InterruptedException {
            final List<JsonObject> seen = new ArrayList<>();
            final long deadline = System.currentTimeMillis() + ms;
            while (true) {
                final JsonObject message = wire.poll(Math.max(0, deadline - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS);
                if (message == null) {
                    return seen;
                }
                log.add(message);
                final String type = message.get("type").getAsString();
                if ("request".equals(type) || "error".equals(type)) {
                    seen.add(message);
                }
            }
        }

        /** Answer and return the accepted ack. */
        JsonObject answer(final JsonObject request, final String type, final String controlId)
                throws IOException, InterruptedException {
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
            final JsonObject ack = next("ack", 30000);
            assertNotNull(ack, "an ack for " + controlId);
            assertTrue(ack.get("accepted").getAsBoolean(), "accepted: " + ack + " for " + request);
            return ack;
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
        return controlIds(request).contains(controlId);
    }

    private static List<String> controlIds(final JsonObject request) {
        final List<String> ids = new ArrayList<>();
        for (JsonElement control : request.getAsJsonArray("controls")) {
            ids.add(control.getAsJsonObject().get("controlId").getAsString());
        }
        return ids;
    }

    private static String message(final JsonObject request) {
        return request.getAsJsonObject("prompt").get("message").getAsString();
    }

    /** Mana value of the "Pay Mana Cost: ..." part of a payment prompt. */
    private static int remainingCost(final JsonObject request) {
        final Matcher m = COST.matcher(message(request));
        assertTrue(m.find(), "a payment prompt: " + message(request));
        int total = 0;
        final Matcher shard = Pattern.compile("\\{([^}]+)}").matcher(m.group(1));
        while (shard.find()) {
            total += shard.group(1).matches("\\d+") ? Integer.parseInt(shard.group(1)) : 1;
        }
        return total;
    }

    /** The next request, which must be the next one after an input: nothing else in between. */
    private static JsonObject nextRequest(final Harness h) throws InterruptedException {
        final JsonObject request = h.next("request", 30000);
        assertNotNull(request, "a request");
        return request;
    }

    private static void addRedMana(final Player seat, final Card source, final int count) {
        for (int i = 0; i < count; i++) {
            seat.getManaPool().addMana(new Mana((byte) ManaAtom.RED, source, null, seat));
        }
    }

    /**
     * The reported race, as a stress loop: cast Shivan Dragon ({4}{R}{R}) from six floating red mana,
     * pay five of them one "Use R mana" at a time, cancel, repeat. Every payment answer is followed
     * by exactly one request, carrying the cost that is left, and by nothing else before the next
     * answer.
     */
    @Test(timeOut = 900000)
    public void eachPaymentStepPublishesOneCurrentRequest() throws Exception {
        final Card[] mountain = new Card[1];
        final Card[] dragon = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            mountain[0] = addCard("Mountain", seat);
            dragon[0] = addCardToZone("Shivan Dragon", seat, ZoneType.Hand);
            addCard("Plains", foe);
        }, seat -> {
            // Six floating red before each cast (a cancelled payment may or may not refund it).
            final int floating = seat.getManaPool().totalMana();
            if (floating < 6) {
                addRedMana(seat, mountain[0], 6 - floating);
            }
        })) {
            final String cast = "card:" + dragon[0].getId();
            int steps = 0;
            for (int cycle = 0; cycle < 25; cycle++) {
                JsonObject request = nextRequest(h);
                assertEquals(request.get("kind").getAsString(), "priority", "cycle " + cycle + ": " + request);
                assertTrue(offers(request, cast), "Shivan Dragon is offered: " + request);
                h.answer(request, "selectCard", cast);
                request = nextRequest(h);
                assertEquals(request.get("kind").getAsString(), "mana", "cycle " + cycle + ": " + request);
                int left = remainingCost(request);
                assertEquals(left, 6, "the whole cost is due: " + message(request));
                for (int paid = 1; paid <= 5; paid++) {
                    assertTrue(offers(request, "mana:" + ManaAtom.RED), "red mana is usable: " + request);
                    h.answer(request, "useMana", "mana:" + ManaAtom.RED);
                    request = nextRequest(h);
                    assertEquals(request.get("kind").getAsString(), "mana");
                    assertEquals(remainingCost(request), 6 - paid,
                            "cycle " + cycle + " step " + paid + ": the request after the answer shows the cost"
                                    + " that is left, not the previous one: " + message(request));
                    steps++;
                }
                // Nothing replaces the request before it is answered.
                assertEquals(h.quiet(150), List.of(), "cycle " + cycle + ": no request without an input");
                h.answer(request, "cancel", "button:cancel");
            }
            assertTrue(steps >= 125, "steps " + steps);
            assertFalse(h.gui.hasFailed());
            // Over the whole run: requests and accepted inputs alternate, starting with a request.
            int open = 0;
            for (JsonObject message : h.log) {
                final String type = message.get("type").getAsString();
                if ("request".equals(type)) {
                    assertEquals(open, 0, "a request replaced another before an input: " + message);
                    open = 1;
                } else if ("ack".equals(type) && message.get("accepted").getAsBoolean()) {
                    open = 0;
                }
            }
        }
    }

    /**
     * Forge's state notifications arrive while the game thread or an action runs, never as a
     * decision of their own. While a request waits for its answer they must not publish, and must
     * not enumerate controls either: enumeration is not a pure read of Forge's model, so a count
     * that varied with the event traffic would vary the game. A presentation callback enumerates
     * once and, with nothing changed, publishes nothing new.
     */
    @Test(timeOut = 900000)
    public void stateNotificationsNeitherPublishNorEnumerate() throws Exception {
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Mountain", seat);
            addCardToZone("Lightning Bolt", seat, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject request = nextRequest(h);
            assertEquals(request.get("kind").getAsString(), "priority");
            assertEquals(h.quiet(300), List.of());
            final long before = h.gui.controlEnumerations();
            for (int i = 0; i < 50; i++) {
                h.gui.updateLives(List.of(h.seat.getView()));
                h.gui.updateZones(List.of());
                h.gui.updateCards(List.of());
                h.gui.updateManaPool(List.of(h.seat.getView()));
                h.gui.updateShards(List.of());
                h.gui.updatePhase(false);
                h.gui.updateTurn(h.seat.getView());
                h.gui.updateStack();
                h.gui.showCombat();
                h.gui.updatePlayerControl();
                h.gui.setCard(null);
                h.gui.setPanelSelection(null);
                h.gui.flashIncorrectAction();
            }
            assertNull(h.quiet(500).stream().filter(m -> "request".equals(m.get("type").getAsString()))
                    .findFirst().orElse(null), "notifications publish nothing");
            assertEquals(h.gui.controlEnumerations(), before, "notifications enumerate nothing");

            h.gui.setHighlighted(List.of(), false);
            assertEquals(h.quiet(500), List.of(), "an unchanged presentation publishes nothing new");
            assertEquals(h.gui.controlEnumerations(), before + 1, "a presentation enumerates once");
        }
    }

    /**
     * The reported prompt: the seat casts its face-up Lightning Bolt with a second Lightning Bolt in
     * its library. The target prompt names the spell, it does not say "Face-down card".
     */
    @Test(timeOut = 900000)
    public void theSeatsOwnSpellIsNamedInItsTargetPrompt() throws Exception {
        final Card[] bolt = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Mountain", seat);
            bolt[0] = addCardToZone("Lightning Bolt", seat, ZoneType.Hand);
            addCardToZone("Lightning Bolt", seat, ZoneType.Library);
            addCardToZone("Lightning Bolt", foe, ZoneType.Hand);
            addCard("Plains", foe);
        })) {
            final JsonObject priority = nextRequest(h);
            h.answer(priority, "selectCard", "card:" + bolt[0].getId());
            final JsonObject target = nextRequest(h);
            assertEquals(target.get("kind").getAsString(), "target", target.toString());
            final String first = message(target).split("\n")[0];
            assertEquals(first, "Lightning Bolt (" + bolt[0].getId() + ") - Lightning Bolt deals 3 damage to any target.");
            assertFalse(message(target).contains("Face-down card"), message(target));

            h.answer(target, "selectPlayer", "player:0");
            final JsonObject payment = nextRequest(h);
            assertEquals(payment.get("kind").getAsString(), "mana", payment.toString());
            assertFalse(message(payment).contains("Face-down card"), message(payment));
        }
    }

    /**
     * The other reported prompt: a creature spell's payment prompt starts with its stack
     * description, "Squee, Goblin Nabob - Creature 1 / 1", the name bare. A second Squee in the
     * library made it "Face-down card - Creature 1 / 1".
     */
    @Test(timeOut = 900000)
    public void theSeatsOwnCreatureSpellIsNamedInItsPaymentPrompt() throws Exception {
        final Card[] squee = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCards("Mountain", 3, seat);
            squee[0] = addCardToZone("Squee, Goblin Nabob", seat, ZoneType.Hand);
            addCardToZone("Squee, Goblin Nabob", seat, ZoneType.Library);
            addCard("Plains", foe);
        })) {
            final JsonObject priority = nextRequest(h);
            h.answer(priority, "selectCard", "card:" + squee[0].getId());
            final JsonObject payment = nextRequest(h);
            assertEquals(payment.get("kind").getAsString(), "mana", payment.toString());
            assertTrue(message(payment).startsWith("Squee, Goblin Nabob - Creature 1 / 1"), message(payment));
            assertFalse(message(payment).contains("Face-down card"), message(payment));
        }
    }

    /**
     * Guard for the exemption: a prompt about a card the seat may not see keeps it hidden, and the
     * foe's face-down creature is never named, whether offered as a target or targeted.
     */
    @Test(timeOut = 900000)
    public void hiddenAndFaceDownCardsStayHidden() throws Exception {
        final Card[] bolt = new Card[1];
        final Card[] foeBolt = new Card[1];
        final Card[] morph = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Mountain", seat);
            bolt[0] = addCardToZone("Lightning Bolt", seat, ZoneType.Hand);
            foeBolt[0] = addCardToZone("Lightning Bolt", foe, ZoneType.Hand);
            morph[0] = createCard("Exalted Angel", foe);
            foe.getZone(ZoneType.Battlefield).add(morph[0]);
            assertTrue(morph[0].turnFaceDown(true), "a face-down creature");
        })) {
            final JsonObject priority = nextRequest(h);
            assertFalse(priority.toString().contains("Exalted Angel"), "the morph is hidden: " + priority);

            // A prompt whose subject is the foe's hidden Lightning Bolt: no exemption.
            final String about = "Lightning Bolt (" + foeBolt[0].getId() + ") - Lightning Bolt deals 3 damage";
            h.gui.showPromptMessage(h.seat.getView(), about, foeBolt[0].getView());
            final JsonObject hidden = nextRequest(h);
            assertEquals(message(hidden), "Face-down card (" + foeBolt[0].getId() + ") - Face-down card deals 3 damage");
            // The same prompt about the seat's own Lightning Bolt keeps the name.
            h.gui.showPromptMessage(h.seat.getView(), "Lightning Bolt (" + bolt[0].getId()
                    + ") - Lightning Bolt deals 3 damage", bolt[0].getView());
            final JsonObject own = nextRequest(h);
            assertEquals(message(own), "Lightning Bolt (" + bolt[0].getId() + ") - Lightning Bolt deals 3 damage");

            h.answer(own, "selectCard", "card:" + bolt[0].getId());
            final JsonObject target = nextRequest(h);
            assertEquals(target.get("kind").getAsString(), "target", target.toString());
            assertTrue(offers(target, "card:" + morph[0].getId()), "the face-down creature is a target: " + target);
            assertFalse(target.toString().contains("Exalted Angel"), "the morph is hidden: " + target);
            h.answer(target, "selectCard", "card:" + morph[0].getId());
            final JsonObject after = nextRequest(h);
            assertFalse(after.toString().contains("Exalted Angel"), "the morph is hidden once targeted: " + after);
            assertTrue(message(after).contains("Lightning Bolt"), message(after));
        }
    }
}
