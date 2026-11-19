package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.card.GamePieceType;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.TriggerType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * lane trigger-label-1119: the labels of abilities to order, and of trigger stack entries, name the card the ability
 * comes from as this seat may know it, with the trigger's own words and no engine context.
 *
 * <p>Owner report 2026-11-19T05-23-42 ("Why does it say Ability of Face Down Card?"), host game 44da2466, turn 15, the
 * owner's end step, request r274. The owner, the monarch, had cast Ragavan, Nimble Pilferer (card 100) for its dash
 * cost. Two of the owner's triggers were to be ordered, and the jar labeled them "At the beginning of your end step,
 * draw a card. [Phase: You]" and "Ability of face-down card (100)". The second is dash's "return it to its owner's hand
 * at the beginning of the next end step": SpellAbilityEffect.registerDelayedTrigger gives that delayed trigger a
 * last-known copy of Ragavan as its host, a copy in no zone, which the identity check failed closed. The first is the
 * monarch's draw with WrappedAbility's stack context ({@code [Phase: <player>]}) appended.</p>
 *
 * <p>Negatives: a face-down permanent's ability stays "face-down card"; a card in a hidden zone, a snapshot of one, and
 * an effect of a hidden card never name it.</p>
 */
public class InteractiveTriggerLabelTest extends AITest {

    private static final String SESSION = "trigger-label-test";
    /** A "[Key: value]" context tag, as Forge's stack context reads. */
    private static final Pattern ENGINE_TAG = Pattern.compile("\\[[^\\]\\[]*: [^\\]\\[]*\\]");

    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile Thread gameThread;

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
        }

        void onGameThread(final Runnable body) {
            final Thread thread = new Thread(() -> {
                try {
                    body.run();
                } catch (RuntimeException e) {
                    if (!closed.get()) {
                        throw e;
                    }
                }
            }, "Game thread (trigger label test)");
            thread.setDaemon(true);
            gameThread = thread;
            thread.start();
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

        void send(final JsonObject request, final JsonObject action) throws IOException {
            final JsonObject input = new JsonObject();
            input.addProperty("protocol", "mtgx-forge-interactive/1");
            input.addProperty("session", SESSION);
            input.addProperty("type", "input");
            input.addProperty("requestId", request.get("requestId").getAsString());
            input.addProperty("inputId", UUID.randomUUID().toString());
            input.addProperty("kind", request.get("kind").getAsString());
            input.add("action", action);
            client.write(input + "\n");
            client.flush();
        }

        /** The order prompt for {@code abilities}, asked as Forge asks for simultaneous abilities. */
        List<String> orderLabels(final List<SpellAbility> abilities) throws InterruptedException {
            onGameThread(() -> controller.orderSimultaneousSa(new ArrayList<>(abilities)));
            final JsonObject order = request();
            assertEquals(order.get("kind").getAsString(), "order", order.toString());
            return itemLabels(order);
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

    private static List<String> itemLabels(final JsonObject request) {
        final List<String> labels = new ArrayList<>();
        for (JsonElement control : request.getAsJsonArray("controls")) {
            final JsonObject object = control.getAsJsonObject();
            if (object.has("items")) {
                for (JsonElement item : object.getAsJsonArray("items")) {
                    labels.add(item.getAsJsonObject().get("label").getAsString());
                }
            }
        }
        return labels;
    }

    private static void noEngineTag(final String text) {
        assertFalse(ENGINE_TAG.matcher(text).find(), "no engine context tag: " + text);
    }

    /** An ability of {@code host}, as a script line ("DB$ Draw | Defined$ You | SpellDescription$ ..."). */
    private static SpellAbility ability(final Card host, final Player activator, final String script) {
        final SpellAbility sa = AbilityFactory.getAbility(script, host);
        sa.setActivatingPlayer(activator);
        return sa;
    }

    /** An effect in {@code owner}'s command zone with {@code source} as its source, named as Forge names one. */
    private static Card effect(final Game game, final Player owner, final Card source) {
        final Card effect = new Card(game.nextCardId(), null, game);
        effect.setOwner(owner);
        effect.setGamePieceType(GamePieceType.EFFECT);
        effect.setName(source.getName() + "'s Effect");
        effect.setEffectSource(source);
        game.getAction().moveTo(ZoneType.Command, effect, null, null);
        return effect;
    }

    // ------------------------------------------------------------------ the owner's prompt

    /**
     * The reported prompt, rebuilt: the seat is the monarch and has dashed Ragavan; at its end step the monarch's draw and
     * dash's return are to be ordered. The draw reads as the monarch's, without "[Phase: ...]"; the return names Ragavan.
     * The foe holds a Ragavan of its own and a Lightning Bolt, hidden: the seat's Ragavan is still named, and nothing of
     * the foe's hand shows.
     */
    @Test(timeOut = 900000)
    public void owner1119_theMonarchsDrawAndDashsReturnNameTheirCards() throws Exception {
        final Card[] ragavan = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            ragavan[0] = addCardToZone("Ragavan, Nimble Pilferer", seat, ZoneType.Hand);
            addCardToZone("Ragavan, Nimble Pilferer", foe, ZoneType.Hand);
            addCardToZone("Lightning Bolt", foe, ZoneType.Hand);
        })) {
            h.game.getAction().becomeMonarch(h.seat, null);
            SpellAbility dash = null;
            for (SpellAbility sa : ragavan[0].getSpellAbilities()) {
                if (sa.isDash()) dash = sa;
            }
            assertNotNull(dash, "Ragavan's dash");
            dash.setActivatingPlayer(h.seat);
            AbilityUtils.resolve(dash);
            assertTrue(ragavan[0].isInPlay() || h.game.findById(ragavan[0].getId()).isInPlay(), "Ragavan is on the battlefield");
            assertEquals(h.game.getTriggerHandler().getDelayedTriggersView().size(), 1, "dash's return waits for the end step");
            h.game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, h.seat);
            h.game.getAction().checkStateEffects(true);
            h.game.getTriggerHandler().resetActiveTriggers();
            h.onGameThread(() -> {
                h.game.getTriggerHandler().runTrigger(TriggerType.Phase, AbilityKey.mapFromPlayer(h.seat), false);
                System.out.println("OWNER TRIGGERED: " + h.game.getStack().hasSimultaneousStackEntries()
                        + " frozen=" + h.game.getStack().isFrozen());
                h.game.getStack().addAllTriggeredAbilitiesToStack();
            });

            final JsonObject order = h.request();
            final List<String> labels = itemLabels(order);
            System.out.println("OWNER ORDER: " + order.get("kind") + " " + order.getAsJsonObject("prompt") + " " + labels);
            assertEquals(order.get("kind").getAsString(), "order", order.toString());
            assertEquals(labels.size(), 2, labels.toString());
            for (String label : labels) {
                noEngineTag(label);
                assertFalse(label.toLowerCase().contains("face-down"), labels.toString());
                assertFalse(label.toLowerCase().contains("hidden"), labels.toString());
                assertFalse(label.contains("Lightning Bolt"), labels.toString());
            }
            assertTrue(labels.contains("The Monarch — At the beginning of your end step, draw a card."), labels.toString());
            assertTrue(labels.contains("Return Ragavan, Nimble Pilferer to your hand at the beginning of the next end step."),
                    labels.toString());

            // Ordered and on the stack, the entries read the same way: no engine context.
            final JsonObject action = new JsonObject();
            action.addProperty("type", "order");
            action.addProperty("controlId", "selection-order");
            final JsonArray ids = new JsonArray();
            ids.add("item:0");
            ids.add("item:1");
            action.add("order", ids);
            h.send(order, action);
            h.gameThread.join(30000);
            assertFalse(h.gameThread.isAlive(), "both triggers are on the stack");
            final JsonArray stack = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack");
            System.out.println("OWNER STACK: " + stack);
            assertEquals(stack.size(), 2, stack.toString());
            for (JsonElement entry : stack) {
                final String description = entry.getAsJsonObject().get("description").getAsString();
                noEngineTag(description);
                assertFalse(description.contains("Hidden"), stack.toString());
            }
            final List<String> descriptions = new ArrayList<>();
            for (JsonElement entry : stack) descriptions.add(entry.getAsJsonObject().get("description").getAsString());
            // The stack keeps its card references; only the engine's context and the repeated remembered list go.
            assertTrue(descriptions.contains("At the beginning of your end step, draw a card."), descriptions.toString());
            assertTrue(descriptions.contains("Return Ragavan, Nimble Pilferer (" + ragavan[0].getId()
                    + ") to your hand at the beginning of the next end step."), descriptions.toString());
            assertFalse(stack.toString().contains("Lightning Bolt"), stack.toString());
        }
    }

    // ------------------------------------------------------------------ effects, snapshots, hidden hosts

    /** The seat's own effect of a public card is named by that card; the monarch (no source) by its own name. */
    @Test(timeOut = 900000)
    public void anEffectIsNamedByItsPublicSourceOrItsOwnName() throws Exception {
        final Card[] tidebinder = new Card[1];
        try (Harness h = new Harness((seat, foe) -> tidebinder[0] = addCard("Tishana's Tidebinder", seat))) {
            final Card lasting = effect(h.game, h.seat, tidebinder[0]);
            h.game.getAction().becomeMonarch(h.seat, null);
            final Card monarch = h.game.getMonarch() == h.seat ? h.seat.getZone(ZoneType.Command).getCards().stream()
                    .filter(c -> "The Monarch".equals(c.getName())).findFirst().orElseThrow() : null;
            assertNotNull(monarch);
            final List<String> labels = h.orderLabels(List.of(
                    ability(lasting, h.seat, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card."),
                    ability(monarch, h.seat, "DB$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.")));
            System.out.println("EFFECT LABELS: " + labels);
            assertEquals(labels, List.of("Tishana's Tidebinder — Draw a card.", "The Monarch — Gain 1 life."));
        }
    }

    /**
     * Hidden information: an effect whose source is a card this seat may not identify (the foe's hand) never names it,
     * though the effect's own name carries it; a face-down permanent's ability stays "face-down card"; a card in a hidden
     * zone, and a snapshot of one, are hidden cards.
     */
    @Test(timeOut = 900000)
    public void hiddenHostsAndSourcesNeverNameTheCard() throws Exception {
        final Card[] cards = new Card[3];
        try (Harness h = new Harness((seat, foe) -> {
            cards[0] = addCardToZone("Thoughtseize", foe, ZoneType.Hand);
            cards[1] = addCard("Grizzly Bears", foe);
            cards[2] = addCardToZone("Llanowar Elves", foe, ZoneType.Hand);
        })) {
            final Card hiddenEffect = effect(h.game, h.foe, cards[0]);
            cards[1].turnFaceDown(true);
            final Card snapshot = CardCopyService.getLKICopy(cards[2]);
            final List<String> labels = h.orderLabels(List.of(
                    ability(hiddenEffect, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ CARDNAME: draw a card."),
                    ability(cards[1], h.foe, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card."),
                    ability(cards[2], h.foe, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card."),
                    ability(snapshot, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card.")));
            System.out.println("HIDDEN LABELS: " + labels);
            for (String label : labels) {
                assertFalse(label.contains("Thoughtseize"), labels.toString());
                assertFalse(label.contains("Grizzly Bears"), labels.toString());
                assertFalse(label.contains("Llanowar Elves"), labels.toString());
                noEngineTag(label);
            }
            assertEquals(labels.get(0), "A hidden card's effect — this effect: draw a card.");
            assertEquals(labels.get(1), "Ability of face-down card (" + cards[1].getId() + ")");
            assertEquals(labels.get(2), "Ability of a hidden card (" + cards[2].getId() + ")");
            assertEquals(labels.get(3), "Ability of a hidden card (" + cards[2].getId() + ")");
        }
    }

    /** A snapshot of a card in view (the foe's creature on the battlefield) is named by that card. */
    @Test(timeOut = 900000)
    public void aSnapshotOfACardInViewIsNamed() throws Exception {
        final Card[] bears = new Card[1];
        try (Harness h = new Harness((seat, foe) -> bears[0] = addCard("Grizzly Bears", foe))) {
            final Card snapshot = CardCopyService.getLKICopy(bears[0]);
            assertEquals(snapshot.getView().getZone(), null, "a last-known copy is in no zone");
            final List<String> labels = h.orderLabels(List.of(
                    ability(snapshot, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card."),
                    ability(bears[0], h.foe, "DB$ GainLife | Defined$ You | LifeAmount$ 1 | SpellDescription$ Gain 1 life.")));
            System.out.println("SNAPSHOT LABELS: " + labels);
            assertEquals(labels, List.of("Grizzly Bears — Draw a card.", "Gain 1 life."));
        }
    }
}
