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
            this(board, false);
        }

        /** {@code humanLobby}: the seat's lobby player is a human one, as Forge's trigger-decision store requires. */
        Harness(final BiConsumer<Player, Player> board, final boolean humanLobby) throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            board.accept(seat, foe);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, seat);
            game.getAction().checkStateEffects(true);
            controller = new PlayerControllerHuman(game, seat,
                    humanLobby ? new forge.player.LobbyPlayerHuman(seat.getName()) : seat.getLobbyPlayer());
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
     * The foe holds a Lightning Bolt and a Counterspell, hidden: nothing of the foe's hand shows. (The owner's game was a
     * singleton cube, so no other Ragavan was hidden; since review fix 3 a hidden card of the same name would make the
     * label the fixed one, by design: {@link #r3_hiddenSafe}.)
     */
    @Test(timeOut = 900000)
    public void owner1119_theMonarchsDrawAndDashsReturnNameTheirCards() throws Exception {
        final Card[] ragavan = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            ragavan[0] = addCardToZone("Ragavan, Nimble Pilferer", seat, ZoneType.Hand);
            addCardToZone("Counterspell", foe, ZoneType.Hand);
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
     * though the effect's own name carries it; a face-down permanent's ability reads "A face-down card"; a card in a
     * hidden zone, and a snapshot of one, read "A hidden card". No free text and no id (review fix 2).
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
            // The card in the hand and its snapshot read alike; since review fix 4 they are told apart by position.
            assertEquals(labels, List.of("A hidden card's effect — ability", "A face-down card — ability",
                    "A hidden card — ability (1 of 2)", "A hidden card — ability (2 of 2)"));
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

    // ------------------------------------------------------------------ review fix 1: one visibility rule

    /**
     * The label of one ability, as the order prompt reads it (the reviewer's harness for #253: the ability is offered
     * as the one being ordered, then labeled).
     */
    private static String label(final Harness h, final SpellAbility sa) throws Exception {
        final java.lang.reflect.Field field = PlayerControllerHuman.class.getDeclaredField("orderingAbilities");
        field.setAccessible(true);
        field.set(h.controller, java.util.Map.of(sa.getView(), sa));
        try {
            final java.lang.reflect.Method method = InteractiveGuiGame.class.getDeclaredMethod("safeAbilityLabel",
                    forge.game.spellability.SpellAbilityView.class);
            method.setAccessible(true);
            return (String) method.invoke(h.gui, sa.getView());
        } finally {
            field.set(h.controller, null);
        }
    }

    /** A phase trigger of {@code host} remembering {@code remembered}, wrapped as Forge puts it on the stack. */
    private static SpellAbility wrapped(final Card host, final Player player, final Card remembered, final String text) {
        final forge.game.trigger.Trigger trigger = forge.game.trigger.TriggerHandler.parseTrigger(
                "Mode$ Phase | Phase$ End Of Turn | TriggerDescription$ " + text, host, false);
        if (remembered != null) trigger.addRemembered(remembered);
        final SpellAbility sa = ability(host, player, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card.");
        final SpellAbility wrapper = new forge.game.trigger.WrappedAbility(trigger, sa, player);
        wrapper.setActivatingPlayer(player);
        wrapper.setTriggeringObject(AbilityKey.Player, player);
        return wrapper;
    }

    /**
     * Review of #253, S1 1 (the reviewer's repro): Sneak Attack's end-step sacrifice remembers a creature that is then
     * bounced, shuffled into the library and put onto the battlefield face down. The label and the stack entry show no
     * free text (review fix 2), only the host and the kind; the head 303f98933b0 named "Grizzly Bears (2)".
     */
    @Test(timeOut = 900000)
    public void review253_aRememberedCreatureHiddenAgainIsNeverNamed() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card sneak = addCard("Sneak Attack", h.foe);
            Card bears = addCard("Grizzly Bears", h.foe);
            sneak.addRemembered(bears);
            AbilityUtils.resolve(ability(sneak, h.foe,
                    "DB$ Animate | Keywords$ Haste | Defined$ Remembered | Duration$ Permanent | AtEOT$ Sacrifice"));
            final forge.game.trigger.Trigger delayed = h.game.getTriggerHandler().getDelayedTriggersView().get(0);
            bears = h.game.getAction().moveTo(ZoneType.Hand, bears, null, null);
            bears = h.game.getAction().moveTo(ZoneType.Library, bears, null, null);
            h.foe.shuffle(null);
            bears = h.game.getAction().moveTo(ZoneType.Battlefield, bears, null, null);
            bears.turnFaceDown(true);
            assertFalse(InteractiveState.mayReceiveIdentity(bears.getView(), h.seat.getView()));
            final SpellAbility sacrifice = new forge.game.trigger.WrappedAbility(delayed,
                    delayed.getOverridingAbility().copy(delayed.getHostCard(), h.foe, false), h.foe);
            sacrifice.setActivatingPlayer(h.foe);
            sacrifice.setTriggeringObject(AbilityKey.Player, h.foe);
            final String result = label(h, sacrifice);
            System.out.println("SNEAK label=" + result + " raw=" + sacrifice);
            assertEquals(result, "Sneak Attack — triggered ability");
            h.game.getStack().add(sacrifice);
            final JsonArray stack = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack");
            System.out.println("SNEAK stack=" + stack);
            assertEquals(stack.get(0).getAsJsonObject().get("name").getAsString(), "Sneak Attack");
            assertEquals(stack.get(0).getAsJsonObject().get("description").getAsString(), "Sneak Attack — triggered ability");
            assertFalse(stack.toString().contains("Grizzly Bears"), stack.toString());
        }
    }

    /**
     * Review of #253, S1 1 (the reviewer's repro): a remembered card of the foe's hand, the same card moved to exile face
     * down (remembered as itself, or as a last-known copy), and a library card are never named, by name or by id: the
     * label is the minimal one (review fix 2).
     */
    @Test(timeOut = 900000)
    public void review253_rememberedHiddenCardsAreNotListed() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card snapshot = CardCopyService.getLKICopy(addCard("Sneak Attack", h.seat));
            final List<String> secrets = List.of("Lightning Bolt", "Swords to Plowshares", "Counterspell", "Dark Ritual");
            for (int mode = 0; mode < secrets.size(); mode++) {
                Card card = addCardToZone(secrets.get(mode), h.foe, ZoneType.Hand);
                final Card remembered = mode == 2 ? CardCopyService.getLKICopy(card) : card;
                if (mode == 1 || mode == 2) {
                    card = h.game.getAction().moveTo(ZoneType.Exile, card, null, null);
                    card.turnFaceDown(true);
                }
                if (mode == 3) card = h.game.getAction().moveTo(ZoneType.Library, card, null, null);
                final String result = label(h, wrapped(snapshot, h.seat, remembered, "Return the remembered card."));
                System.out.println("REMEMBER mode=" + mode + " label=" + result);
                assertEquals(result, "Sneak Attack — triggered ability", "mode " + mode);
            }
        }
    }

    /**
     * Review of #253, S1 2 and 3 (the reviewer's repros): an effect whose source has since gone out of view (to the
     * hand, the library, face down, through the graveyard to the hand, as a last-known copy, from the hand to face-down
     * exile), and an effect whose source is an effect of such a card, read "A hidden card's effect"; the head named the
     * source from its old object, or the source effect by its generated name.
     */
    @Test(timeOut = 900000)
    public void review253_staleAndNestedEffectSourcesAreHidden() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            for (int mode = 0; mode < 7; mode++) {
                Card card = mode == 5 ? addCardToZone("Grizzly Bears", h.foe, ZoneType.Hand) : addCard("Grizzly Bears", h.foe);
                Card effect = effect(h.game, h.foe, mode == 4 ? CardCopyService.getLKICopy(card) : card);
                if (mode == 0 || mode == 4) card = h.game.getAction().moveTo(ZoneType.Hand, card, null, null);
                if (mode == 1) card = h.game.getAction().moveTo(ZoneType.Library, card, null, null);
                if (mode == 2) card.turnFaceDown(true);
                if (mode == 3) {
                    card = h.game.getAction().moveTo(ZoneType.Graveyard, card, null, null);
                    card = h.game.getAction().moveTo(ZoneType.Hand, card, null, null);
                }
                if (mode == 5) {
                    card = h.game.getAction().moveTo(ZoneType.Exile, card, null, null);
                    card.turnFaceDown(true);
                }
                if (mode == 6) {
                    card = h.game.getAction().moveTo(ZoneType.Hand, card, null, null);
                    effect = effect(h.game, h.foe, effect);
                }
                assertFalse(InteractiveState.mayReceiveIdentity(card.getView(), h.seat.getView()), "mode " + mode);
                final String result = label(h, ability(effect, h.foe,
                        "DB$ Draw | Defined$ You | SpellDescription$ Draw a card."));
                System.out.println("EFFECT mode=" + mode + " label=" + result);
                assertEquals(result, "A hidden card's effect — ability", "mode " + mode);
            }
        }
    }

    /**
     * The real Necropotence: its delayed "put the exiled card into your hand" remembers a card exiled face down, which
     * the other seat may not identify. Its label and stack entry are the minimal ones (review fix 2), with nothing of
     * that card, not even Forge's hidden reference "Face-down card (H1)".
     */
    @Test(timeOut = 900000)
    public void necropotencesFaceDownExileGivesTheMinimalLabel() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card necro = addCard("Necropotence", h.foe);
            addCardToZone("Lightning Bolt", h.foe, ZoneType.Library);
            final SpellAbility activation = necro.getSpellAbilities().stream()
                    .filter(a -> !a.isSpell() && a.getApi() == forge.game.ability.ApiType.ChangeZone).findFirst().orElseThrow();
            activation.setActivatingPlayer(h.foe);
            AbilityUtils.resolve(activation);
            final forge.game.trigger.Trigger delayed = h.game.getTriggerHandler().getDelayedTriggersView().get(0);
            final SpellAbility put = new forge.game.trigger.WrappedAbility(delayed, delayed.getOverridingAbility(), h.foe);
            put.setActivatingPlayer(h.foe);
            put.setTriggeringObject(AbilityKey.Player, h.foe);
            final String result = label(h, put);
            System.out.println("NECRO label=" + result);
            assertEquals(result, "Necropotence — triggered ability");
            h.game.getStack().add(put);
            final JsonArray stack = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack");
            System.out.println("NECRO stack=" + stack);
            assertEquals(stack.get(0).getAsJsonObject().get("name").getAsString(), "Necropotence");
            assertEquals(stack.get(0).getAsJsonObject().get("description").getAsString(), "Necropotence — triggered ability");
            assertFalse(stack.toString().contains("Face-down card"), stack.toString());
        }
    }

    /**
     * The moves (review fix 1): a card is a snapshot host, an effect's source, and a trigger's remembered card; then it
     * moves. Its text shows exactly when this seat may identify the card with its id now: the minimal label after hand
     * to face-down exile, battlefield to graveyard to hand, or a shuffle into the library; the full text after a change
     * of control, and after a token has ceased to exist (a face-up token is public wherever it was).
     */
    @Test(timeOut = 900000)
    public void review253_eachReferenceFollowsTheCardAsItIsNow() throws Exception {
        for (String move : List.of("hand-to-face-down-exile", "battlefield-graveyard-hand", "shuffle-into-library",
                "control-change", "token-gone")) {
            try (Harness h = new Harness((seat, foe) -> { })) {
                final Card sneak = CardCopyService.getLKICopy(addCard("Sneak Attack", h.seat));
                final List<Card> cards = new ArrayList<>();
                for (int role = 0; role < 3; role++) {
                    cards.add(switch (move) {
                        case "hand-to-face-down-exile" -> addCardToZone("Grizzly Bears", h.foe, ZoneType.Hand);
                        case "token-gone" -> addToken("c_1_1_a_thopter_flying", h.foe);
                        default -> addCard("Grizzly Bears", h.foe);
                    });
                }
                final String name = cards.get(0).getName();
                final SpellAbility asHost = ability(CardCopyService.getLKICopy(cards.get(0)), h.foe,
                        "DB$ Draw | Defined$ You | SpellDescription$ Draw a card.");
                final SpellAbility asSource = ability(effect(h.game, h.foe, cards.get(1)), h.foe,
                        "DB$ Draw | Defined$ You | SpellDescription$ Draw a card.");
                final SpellAbility asRemembered = wrapped(sneak, h.seat, cards.get(2), "Return the remembered card.");
                final List<Card> now = new ArrayList<>();
                for (Card card : cards) {
                    Card c = card;
                    switch (move) {
                        case "hand-to-face-down-exile" -> {
                            c = h.game.getAction().moveTo(ZoneType.Exile, c, null, null);
                            c.turnFaceDown(true);
                        }
                        case "battlefield-graveyard-hand" -> {
                            c = h.game.getAction().moveTo(ZoneType.Graveyard, c, null, null);
                            c = h.game.getAction().moveTo(ZoneType.Hand, c, null, null);
                        }
                        case "shuffle-into-library" -> {
                            c = h.game.getAction().moveTo(ZoneType.Library, c, null, null);
                            h.foe.shuffle(null);
                        }
                        case "control-change" -> c.setController(h.seat, h.game.getNextTimestamp());
                        case "token-gone" -> c = h.game.getAction().moveTo(ZoneType.Graveyard, c, null, null);
                        default -> fail(move);
                    }
                    now.add(c);
                }
                h.game.getAction().checkStateEffects(true);
                final boolean named = move.equals("control-change") || move.equals("token-gone");
                if (move.equals("token-gone")) {
                    for (Card card : cards) assertEquals(h.game.findById(card.getId()), null, "the token has ceased to exist");
                } else {
                    for (Card card : now) {
                        assertEquals(InteractiveState.mayReceiveIdentity(card.getView(), h.seat.getView()), named, move);
                    }
                }
                final List<String> labels = List.of(label(h, asHost), label(h, asSource), label(h, asRemembered));
                System.out.println("MOVE " + move + " " + labels);
                if (named) {
                    assertEquals(labels, List.of(name + " — Draw a card.", name + " — Draw a card.",
                            "Sneak Attack — Return the remembered card. (" + name + ")"), move);
                } else {
                    final String host = move.equals("hand-to-face-down-exile") ? "A face-down card" : "A hidden card";
                    assertEquals(labels, List.of(host + " — ability", "A hidden card's effect — ability",
                            "Sneak Attack — triggered ability"), move);
                    for (String label : labels) assertFalse(label.contains(name), move + ": " + label);
                }
            }
        }
    }

    // ------------------------------------------------------------------ review fix 2: all or nothing

    /**
     * A fully visible trigger of this seat's own keeps its full text, in its label and on the stack: its host, its
     * remembered card and its triggering card are all cards this seat may identify (as in the owner's prompt).
     */
    @Test(timeOut = 900000)
    public void r2_aFullyVisibleOwnTriggerKeepsItsFullText() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card sneak = addCard("Sneak Attack", h.seat);
            final Card bears = addCard("Grizzly Bears", h.seat);
            final Card elves = addCard("Llanowar Elves", h.seat);
            final SpellAbility trigger = wrapped(sneak, h.seat, bears, "Return the remembered card.");
            trigger.setTriggeringObject(AbilityKey.Card, elves);
            final String result = label(h, trigger);
            System.out.println("R2 FULL label=" + result);
            assertEquals(result, "Return the remembered card. (Grizzly Bears)");
            h.game.getStack().add(trigger);
            final JsonObject entry = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack").get(0).getAsJsonObject();
            System.out.println("R2 FULL stack=" + entry);
            assertEquals(entry.get("name").getAsString(), "Sneak Attack");
            assertEquals(entry.get("description").getAsString(), "Return the remembered card. (Grizzly Bears (" + bears.getId() + "))");
        }
    }

    /** Astra's re-review of #253, probe 1: a hidden host carries no id ("Ability of a hidden card (1)" at 99a05755e59). */
    @Test(timeOut = 900000)
    public void r2_aHiddenHostCarriesNoId() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            Card bears = addCard("Grizzly Bears", h.foe);
            final Card snapshot = CardCopyService.getLKICopy(bears);
            bears = h.game.getAction().moveTo(ZoneType.Library, bears, null, null);
            h.foe.shuffle(null);
            final String result = label(h, ability(snapshot, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card."));
            System.out.println("R2 HIDDEN HOST: " + result);
            assertEquals(result, "A hidden card — ability");
            assertFalse(result.contains("(" + bears.getId() + ")"), result);
        }
    }

    /**
     * Probe 2: a trigger pushed while its remembered Grizzly Bears was face up, the same object then turned face down.
     * The stored text ("Return the remembered card. ([Grizzly Bears (2)])") is not shown.
     */
    @Test(timeOut = 900000)
    public void r2_aRememberedCardConcealedAfterThePushHidesTheStoredText() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card sneak = addCard("Sneak Attack", h.foe);
            final Card bears = addCard("Grizzly Bears", h.foe);
            h.game.getStack().add(wrapped(sneak, h.foe, bears, "Return the remembered card."));
            bears.turnFaceDown(true);
            assertFalse(InteractiveState.mayReceiveIdentity(bears.getView(), h.seat.getView()));
            final JsonArray stack = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack");
            System.out.println("R2 STORED AFTER: " + stack);
            assertEquals(stack.get(0).getAsJsonObject().get("name").getAsString(), "Sneak Attack");
            assertEquals(stack.get(0).getAsJsonObject().get("description").getAsString(), "Sneak Attack — triggered ability");
            assertFalse(stack.toString().contains("Grizzly Bears"), stack.toString());
        }
    }

    /** Probe 3: a triggering card turned face down after the ability's view was made ("Return Grizzly Bears (2)."). */
    @Test(timeOut = 900000)
    public void r2_aTriggeringCardConcealedLaterHidesTheText() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card sneak = addCard("Sneak Attack", h.foe);
            final Card bears = addCard("Grizzly Bears", h.foe);
            final SpellAbility trigger = wrapped(sneak, h.foe, null, "Return " + bears + ".");
            trigger.setTriggeringObject(AbilityKey.Card, bears);
            trigger.getView();
            bears.turnFaceDown(true);
            final String result = label(h, trigger);
            System.out.println("R2 TRIGGERING: " + result);
            assertEquals(result, "Sneak Attack — triggered ability");
        }
    }

    /**
     * Probe 4: an effect named "Grizzly Bears's Effect" with no source, while the Bears is face down. A generated name is
     * never shown: the effect has no engine provenance as a public game effect, so it reads "An effect".
     */
    @Test(timeOut = 900000)
    public void r2_aSourcelessEffectsGeneratedNameIsNeverShown() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card bears = addCard("Grizzly Bears", h.foe);
            final Card effect = effect(h.game, h.foe, bears);
            effect.setEffectSource((Card) null);
            bears.turnFaceDown(true);
            assertFalse(InteractiveState.mayReceiveIdentity(bears.getView(), h.seat.getView()));
            final String result = label(h, ability(effect, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card."));
            System.out.println("R2 SOURCELESS: " + result);
            assertEquals(result, "An effect — Draw a card.");
            final String named = label(h, ability(effect, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ CARDNAME: draw a card."));
            System.out.println("R2 SOURCELESS CARDNAME: " + named);
            assertFalse(named.contains("Grizzly Bears"), named);
        }
    }

    /**
     * Probe 5: an effect of the foe's hidden Thoughtseize has an ability on the stack targeting a face-down creature.
     * The entry's description is the base's "Hidden spell or ability", and its name is not the effect's generated name.
     */
    @Test(timeOut = 900000)
    public void r2_aHiddenTargetsEntryNamesNoEffect() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card thoughtseize = addCardToZone("Thoughtseize", h.foe, ZoneType.Hand);
            final Card effect = effect(h.game, h.foe, thoughtseize);
            final Card target = addCard("Grizzly Bears", h.foe);
            target.turnFaceDown(true);
            final SpellAbility pump = ability(effect, h.foe,
                    "DB$ Pump | ValidTgts$ Creature | NumAtt$ 1 | NumDef$ 1 | SpellDescription$ Pump target creature.");
            pump.getTargets().add(target);
            h.game.getStack().add(pump);
            final JsonArray stack = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack");
            System.out.println("R2 HIDDEN TARGET EFFECT: " + stack);
            assertEquals(stack.get(0).getAsJsonObject().get("name").getAsString(), "A hidden card's effect");
            assertEquals(stack.get(0).getAsJsonObject().get("description").getAsString(), "Hidden spell or ability");
            assertFalse(stack.toString().contains("Thoughtseize"), stack.toString());
        }
    }

    /** Probe 6: a public host's trigger remembers an effect of the foe's hidden Thoughtseize. */
    @Test(timeOut = 900000)
    public void r2_aRememberedEffectFollowsItsSourceChain() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card thoughtseize = addCardToZone("Thoughtseize", h.foe, ZoneType.Hand);
            final Card effect = effect(h.game, h.foe, thoughtseize);
            final Card sneak = addCard("Sneak Attack", h.foe);
            final SpellAbility trigger = wrapped(sneak, h.foe, effect, "Return the remembered card.");
            final String result = label(h, trigger);
            h.game.getStack().add(trigger);
            final JsonArray stack = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack");
            System.out.println("R2 REMEMBER EFFECT LABEL: " + result);
            System.out.println("R2 REMEMBER EFFECT STACK: " + stack);
            assertEquals(result, "Sneak Attack — triggered ability");
            assertEquals(stack.get(0).getAsJsonObject().get("description").getAsString(), "Sneak Attack — triggered ability");
            assertFalse(stack.toString().contains("Thoughtseize"), stack.toString());
        }
    }

    /**
     * The reviewer's cycle and depth probes: an effect chain that loops, or is longer than the cap, cannot be resolved:
     * the minimal label, naming nothing.
     */
    @Test(timeOut = 900000)
    public void r2_effectChainLoopsAndOverlongChainsFailClosed() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card bears = addCard("Grizzly Bears", h.foe);
            final Card a = effect(h.game, h.foe, bears);
            final Card b = effect(h.game, h.foe, a);
            a.setEffectSource(b);
            final String cycle = label(h, ability(b, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ CARDNAME: draw a card."));
            Card deep = effect(h.game, h.foe, bears);
            for (int i = 0; i < 9; i++) deep = effect(h.game, h.foe, deep);
            final String overlong = label(h, ability(deep, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ CARDNAME: draw a card."));
            System.out.println("R2 CYCLE: " + cycle + " / DEPTH: " + overlong);
            assertEquals(cycle, "An effect — ability");
            assertEquals(overlong, "An effect — ability");
        }
    }

    // ------------------------------------------------------------------ review fix 3: the final sink check

    /** The seat's view of the stack's top entry. */
    private static JsonObject topEntry(final Harness h) {
        return InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack").get(0).getAsJsonObject();
    }

    /** Neither the hidden card's name nor its id anywhere in {@code text}. */
    private static void namesNothingOf(final String text, final Card secret) {
        assertFalse(text.contains("Grizzly Bears") || text.contains("(" + secret.getId() + ")"), text);
    }

    private static void entryIs(final JsonObject entry, final String name, final String description, final Card secret) {
        namesNothingOf(entry.toString(), secret);
        assertEquals(entry.get("name").getAsString(), name, entry.toString());
        assertEquals(entry.get("description").getAsString(), description, entry.toString());
    }

    /** r3 finding 1: an ability rendering the host's ExiledWith list ("Return {c:ExiledWith}."), the card hidden since. */
    @Test(timeOut = 900000)
    public void r3_anExiledWithListConcealedLaterIsNotShown() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card bears = addCardToZone("Grizzly Bears", h.foe, ZoneType.Exile);
            host.addExiledCard(bears);
            h.game.getStack().add(ability(host, h.foe, "DB$ ChangeZone | Origin$ Exile | Destination$ Hand | Defined$ ExiledWith"
                    + " | StackDescription$ Return {c:ExiledWith}. | SpellDescription$ Return exiled cards."));
            bears.turnFaceDown(true);
            entryIs(topEntry(h), "Sneak Attack", "Sneak Attack — ability", bears);
        }
    }

    /** r3 finding 1: Dollhouse of Horrors' paid "Exiled" list, the exiled card turned face down since. */
    @Test(timeOut = 900000)
    public void r3_aPaidExiledListConcealedLaterIsNotShown() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Dollhouse of Horrors", h.foe);
            final Card bears = addCardToZone("Grizzly Bears", h.foe, ZoneType.Exile);
            final SpellAbility sa = host.getSpellAbilities().stream()
                    .filter(a -> a.getApi() == forge.game.ability.ApiType.CopyPermanent).findFirst().orElseThrow();
            sa.setActivatingPlayer(h.foe);
            sa.addCostToHashList(bears, "Exiled", true);
            h.game.getStack().add(sa);
            bears.turnFaceDown(true);
            entryIs(topEntry(h), "Dollhouse of Horrors", "Dollhouse of Horrors — ability", bears);
        }
    }

    /** r3 finding 1: a snapshot host's Remembered falls back to the current host's list. */
    @Test(timeOut = 900000)
    public void r3_aSnapshotsRememberedFallbackIsNotShown() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card snapshot = CardCopyService.getLKICopy(host);
            final Card bears = addCard("Grizzly Bears", h.foe);
            host.addRemembered(bears);
            h.game.getStack().add(ability(snapshot, h.foe, "DB$ ChangeZone | Origin$ Battlefield | Destination$ Hand"
                    + " | Defined$ Remembered | StackDescription$ Return {c:Remembered}. | SpellDescription$ Return remembered cards."));
            bears.turnFaceDown(true);
            entryIs(topEntry(h), "Sneak Attack", "Sneak Attack — ability", bears);
        }
    }

    /** r3 finding 2: text rendered from a remembered list that has been cleared since; the card hidden after. */
    @Test(timeOut = 900000)
    public void r3_storedTextFromAListChangedSinceIsNotShown() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card bears = addCard("Grizzly Bears", h.foe);
            host.addRemembered(bears);
            h.game.getStack().add(ability(host, h.foe, "DB$ ChangeZone | Origin$ Battlefield | Destination$ Hand"
                    + " | Defined$ Remembered | StackDescription$ Return {c:Remembered}. | SpellDescription$ Return remembered cards."));
            host.clearRemembered();
            bears.turnFaceDown(true);
            entryIs(topEntry(h), "Sneak Attack", "Sneak Attack — ability", bears);
        }
    }

    /** r3 finding 1: a sub-ability with its own host and remembered list. */
    @Test(timeOut = 900000)
    public void r3_aSubAbilitysOwnReferencesAreNotShown() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card snapshot = CardCopyService.getLKICopy(host);
            final Card bears = addCard("Grizzly Bears", h.foe);
            host.addRemembered(bears);
            final SpellAbility sa = ability(snapshot, h.foe, "AB$ Draw | Cost$ 0 | Defined$ You | SpellDescription$ Draw a card.");
            sa.setSubAbility((forge.game.spellability.AbilitySub) ability(host, h.foe, "DB$ ChangeZone | Origin$ Battlefield"
                    + " | Destination$ Hand | Defined$ Remembered | StackDescription$ Return {c:Remembered}."));
            h.game.getStack().add(sa);
            bears.turnFaceDown(true);
            entryIs(topEntry(h), "Sneak Attack", "Sneak Attack — ability", bears);
        }
    }

    /** r3 finding 1: a trigger's ORIGINALHOST, rendered into the stored stack text, the card hidden since. */
    @Test(timeOut = 900000)
    public void r3_anOriginalHostInStoredTextIsNotShown() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card bears = addCard("Grizzly Bears", h.foe);
            final SpellAbility sa = wrapped(host, h.foe, null, "Return ORIGINALHOST.");
            sa.getTrigger().setCardState(bears.getCurrentState());
            h.game.getStack().add(sa);
            bears.turnFaceDown(true);
            entryIs(topEntry(h), "Sneak Attack", "Sneak Attack — triggered ability", bears);
        }
    }

    /**
     * r3 finding 3: an effect the game no longer has (removed from the command zone) is not named by its generated name,
     * and its source chain is still followed: its source is hidden.
     */
    @Test(timeOut = 900000)
    public void r3_aRemovedEffectNeverShowsItsGeneratedName() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card bears = addCard("Grizzly Bears", h.foe);
            final Card effect = effect(h.game, h.foe, bears);
            final SpellAbility sa = ability(effect, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card.");
            h.foe.getZone(ZoneType.Command).remove(effect);
            assertEquals(h.game.findById(effect.getId()), null);
            bears.turnFaceDown(true);
            final String result = label(h, sa);
            namesNothingOf(result, bears);
            assertEquals(result, "A hidden card's effect — ability");
            // With its source in view, it is named by that source, still never by its generated name.
            bears.turnFaceUp(false, null);
            assertEquals(label(h, sa), "Grizzly Bears — Draw a card.");
        }
    }

    /** r3 finding 4: a Stifle-style target's name follows the targeted effect's provenance. */
    @Test(timeOut = 900000)
    public void r3_aTargetedEffectsNameFollowsItsProvenance() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card bears = addCard("Grizzly Bears", h.foe);
            final Card effect = effect(h.game, h.foe, bears);
            final SpellAbility target = ability(effect, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ Draw a card.");
            h.game.getStack().add(target);
            final Card stifle = addCard("Stifle", h.seat);
            final SpellAbility sa = ability(stifle, h.seat, "DB$ Counter | ValidTgts$ Ability | SpellDescription$ Counter target ability.");
            sa.getTargets().add(target);
            h.game.getStack().add(sa);
            bears.turnFaceDown(true);
            final JsonObject entry = topEntry(h);
            entryIs(entry, "Stifle", "Stifle — ability", bears);
            assertEquals(entry.getAsJsonArray("targetSpells").get(0).getAsJsonObject().get("name").getAsString(),
                    "A hidden card's effect", entry.toString());
        }
    }

    /**
     * The sink check itself ({@link InteractiveState.HiddenNames}): a hidden name, word-bounded and with its exact
     * capitalization (review fix 3b: a hidden Life // Death's "Life" fails "Life" but not "gain 3 life"), except the
     * first letter of a name of two or more words (review fix 4: "grizzly Bears"); a hidden id (N), while Forge's (HN)
     * handles pass (review fix 4); both faces of a hidden double-faced card; a visible card sharing a hidden card's name fails too
     * (closed, by design), and passes once no card of that name is hidden.
     */
    @Test(timeOut = 900000)
    public void r3_hiddenSafe() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card bolt = addCardToZone("Lightning Bolt", h.foe, ZoneType.Hand);
            final Card delver = addCardToZone("Delver of Secrets", h.foe, ZoneType.Hand);
            addCardToZone("Life // Death", h.foe, ZoneType.Library);
            addCardToZone("Island", h.seat, ZoneType.Library);
            final Card mine = addCard("Grizzly Bears", h.seat);
            final InteractiveState.HiddenNames before = InteractiveState.HiddenNames.of(h.game, h.seat.getView());
            assertTrue(before.safe("Grizzly Bears attacks."), "no hidden Grizzly Bears yet");
            assertTrue(before.safe("Grizzly Bears (" + mine.getId() + ") attacks."), "a visible card's id");
            final Card theirs = addCardToZone("Grizzly Bears", h.foe, ZoneType.Hand);
            final InteractiveState.HiddenNames hidden = InteractiveState.HiddenNames.of(h.game, h.seat.getView());
            // A hidden name.
            assertFalse(hidden.safe("Lightning Bolt deals 3 damage to any target."));
            assertTrue(hidden.safe("copy target lightning bolt"), "exact capitalization: Forge renders names verbatim");
            // Review fix 4: a name of two or more words matches with its first letter in either case.
            assertFalse(hidden.safe("lightning Bolt deals 3 damage."), "Forge lowercases a wrapped ability's first letter");
            assertFalse(hidden.safe("grizzly Bears draws a card."), "the first letter in either case");
            assertTrue(hidden.safe("grizzly bears draw cards."), "the rest stays exact");
            // Case pair: a hidden Life // Death.
            assertTrue(hidden.safe("You gain 3 life."), "a hidden \"Life\" leaves \"gain 3 life\" alone");
            assertTrue(hidden.safe("Pay 1 life, Sacrifice a land: draw a card."), "a hidden \"Life\" leaves \"pay 1 life\"");
            assertFalse(hidden.safe("Life"), "the hidden half's own name");
            assertFalse(hidden.safe("Cast Life"), "the hidden half's own name");
            assertFalse(hidden.safe("Death returns a creature."), "the other half");
            assertFalse(hidden.safe("Life // Death"), "the whole card's name");
            assertTrue(hidden.safe("Lightning Bolts are not a card."), "word-bounded");
            assertTrue(hidden.safe("Islandcycling {2}"), "word-bounded: the seat's own library Island");
            assertFalse(hidden.safe("Search your library for an Island card."), "the seat's own library is hidden");
            // A hidden id.
            assertFalse(hidden.safe("Return a card (" + bolt.getId() + ")."));
            // Forge's (HN) handles are its public masks for face-down cards, not ids (review fix 4).
            assertTrue(hidden.safe("Face-down card (H" + bolt.getId() + ")"), "an (HN) handle is a public mask");
            assertTrue(hidden.safe("Return a card (" + mine.getId() + ")."), "a visible card's id");
            // Both faces of a hidden double-faced card.
            assertFalse(hidden.safe("Delver of Secrets transforms."));
            assertFalse(hidden.safe("Insectile Aberration attacks."), "the back face");
            assertFalse(hidden.safe("(" + delver.getId() + ")"));
            // A visible card sharing a hidden card's name fails too.
            assertFalse(hidden.safe("Grizzly Bears attacks."), "closed: the foe's Grizzly Bears is hidden");
            assertFalse(InteractiveState.hiddenSafe("Grizzly Bears attacks.", h.game, h.seat.getView()));
            // The foe sees its own hand: those names pass for the foe; the seat's library is hidden from it.
            final InteractiveState.HiddenNames foes = InteractiveState.HiddenNames.of(h.game, h.foe.getView());
            assertTrue(foes.safe("Lightning Bolt deals 3 damage to any target."));
            assertTrue(foes.safe("Return a card (" + theirs.getId() + ")."));
            assertFalse(foes.safe("Search your library for an Island card."));
        }
    }

    /** The fixed label's last resort: when even the host's name is hidden, "A hidden card — ...". */
    @Test(timeOut = 900000)
    public void r3_aHostNamedLikeAHiddenCardFallsToAHiddenCard() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            addCardToZone("Sneak Attack", h.foe, ZoneType.Hand);
            final Card bears = addCard("Grizzly Bears", h.foe);
            host.addRemembered(bears);
            h.game.getStack().add(ability(host, h.foe, "DB$ ChangeZone | Origin$ Battlefield | Destination$ Hand"
                    + " | Defined$ Remembered | StackDescription$ Return {c:Remembered}. | SpellDescription$ Return remembered cards."));
            bears.turnFaceDown(true);
            // The check fails (the remembered card is hidden): "Sneak Attack — ability"; a Sneak Attack is hidden too.
            final JsonObject entry = topEntry(h);
            namesNothingOf(entry.toString(), bears);
            assertEquals(entry.get("name").getAsString(), "A hidden card", entry.toString());
            assertEquals(entry.get("description").getAsString(), "A hidden card — ability", entry.toString());
            final String order = label(h, wrapped(host, h.foe, bears, "Return remembered cards."));
            assertEquals(order, "A hidden card — triggered ability");
        }
    }

    /** Forge's optional-trigger question carries the trigger's words: it passes the sink check too. */
    @Test(timeOut = 900000)
    public void r3_theOptionalTriggerQuestionPassesTheSink() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card bears = addCard("Grizzly Bears", h.foe);
            final java.lang.reflect.Field card = InteractiveGuiGame.class.getDeclaredField("promptCard");
            card.setAccessible(true);
            card.set(h.gui, host.getView());
            final java.lang.reflect.Method sink = InteractiveGuiGame.class.getDeclaredMethod("sinkTriggerQuestion", String.class);
            sink.setAccessible(true);
            final String question = "Use triggered ability of Sneak Attack (" + host.getId() + ")?\n(Return Grizzly Bears ("
                    + bears.getId() + ").)";
            assertEquals(sink.invoke(h.gui, question), question, "nothing hidden: unchanged");
            bears.turnFaceDown(true);
            assertEquals(sink.invoke(h.gui, question), "Use triggered ability of Sneak Attack?");
            assertEquals(sink.invoke(h.gui, "Sacrifice Grizzly Bears?"), "Sacrifice Grizzly Bears?", "not a trigger question");
        }
    }
    // ------------------------------------------------------------------ review fix 4

    /** A trigger of {@code host}'s, by the start of its description, wrapped as Forge puts it on the stack. */
    private static SpellAbility etb(final Harness h, final Card host, final Player player) {
        final forge.game.trigger.Trigger trigger = host.getTriggers().stream()
                .filter(t -> t.getParamOrDefault("TriggerDescription", "").startsWith("When CARDNAME enters"))
                .findFirst().orElseThrow();
        final SpellAbility sa = new forge.game.trigger.WrappedAbility(trigger, trigger.ensureAbility(), player);
        sa.setActivatingPlayer(player);
        sa.setTriggeringObject(AbilityKey.Card, host);
        return sa;
    }

    /** "Return ORIGINALHOST." with {@code original} as the original host, as Forge stores it. */
    private static SpellAbility returnOriginal(final Harness h, final Card host, final Card original) {
        final SpellAbility sa = wrapped(host, h.foe, null, "Return ORIGINALHOST.");
        sa.getTrigger().setCardState(original.getCurrentState());
        return sa;
    }

    /**
     * r4 finding 1 (ruled not a leak): Windbrisk Heights' hideaway text keeps Forge's "Face-down card (H1)", its public
     * mask for the face-down card, the same handle the zone views show; the card's real id still fails the check.
     */
    @Test(timeOut = 900000)
    public void r4_aHideawaysFaceDownHandlePassesAndARealIdFails() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Windbrisk Heights", h.foe);
            final Card secret = addCardToZone("Grizzly Bears", h.foe, ZoneType.Exile);
            secret.turnFaceDown(true);
            host.addExiledCard(secret);
            final SpellAbility sa = host.getSpellAbilities().stream()
                    .filter(a -> a.getApi() == forge.game.ability.ApiType.Play).findFirst().orElseThrow();
            sa.setActivatingPlayer(h.foe);
            h.game.getStack().add(sa);
            assertEquals(InteractiveState.knownNow(h.game, secret.getView(), h.seat.getView()), null);
            assertTrue(secret.getId() != secret.getHiddenId());
            final String description = topEntry(h).get("description").getAsString();
            System.out.println("R4 HIDEAWAY: " + description);
            assertTrue(description.contains("may play Face-down card (H" + secret.getHiddenId() + ")"), description);
            namesNothingOf(description, secret);
            assertFalse(InteractiveState.hiddenSafe("may play a card (" + secret.getId() + ")", h.game, h.seat.getView()));
        }
    }

    /** r4 finding 1 (ruled not a leak): the optional-trigger question keeps the attacker's face-down handle. */
    @Test(timeOut = 900000)
    public void r4_theTriggerQuestionKeepsTheFaceDownHandle() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { }, true)) {
            final Card host = addCard("Sneak Attack", h.seat);
            final Card secret = addCard("Grizzly Bears", h.foe);
            secret.turnFaceDown(true);
            final SpellAbility sa = wrapped(host, h.seat, null, "You may draw a card.");
            sa.setTriggeringObject(AbilityKey.Attacker, secret);
            h.onGameThread(() -> h.controller.confirmTrigger((forge.game.trigger.WrappedAbility) sa));
            final JsonObject request = h.request();
            final String message = request.getAsJsonObject("prompt").get("message").getAsString();
            System.out.println("R4 QUESTION: " + message);
            assertTrue(message.startsWith("Use triggered ability of Sneak Attack (" + host.getId() + ")"), message);
            assertTrue(message.contains("Face-down card (H" + secret.getHiddenId() + ")"), message);
            namesNothingOf(message, secret);
        }
    }

    /** r4 finding 2: a card copying another keeps its printed name in the hidden set. */
    @Test(timeOut = 900000)
    public void r4_aCopiedCardKeepsItsPrintedNameHidden() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card secret = addCard("Grizzly Bears", h.foe);
            final Card model = addCard("Merfolk Looter", h.foe);
            h.game.getStack().add(returnOriginal(h, host, secret));
            final SpellAbility clone = ability(secret, h.foe, "DB$ Clone | Defined$ Self | CloneTarget$ Targeted");
            secret.addCloneState(forge.game.card.CardFactory.getCloneStates(model, secret, clone), h.game.getNextTimestamp());
            assertEquals(secret.getState(forge.card.CardStateName.Original).getName(), "Merfolk Looter");
            secret.turnFaceDown(true);
            entryIs(topEntry(h), "Sneak Attack", "Sneak Attack — triggered ability", secret);
        }
    }

    /** r4 finding 3: a mutated permanent's component, in no zone, is in the hidden set. */
    @Test(timeOut = 900000)
    public void r4_aMergedComponentIsHidden() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card base = addCard("Grizzly Bears", h.foe);
            final Card secret = addCard("Gemrazer", h.foe);
            h.game.getStack().add(returnOriginal(h, host, secret));
            final SpellAbility mutate = ability(secret, h.foe, "DB$ Mutate | ValidTgts$ Creature.YouCtrl");
            mutate.getTargets().add(base);
            AbilityUtils.resolve(mutate);
            base.turnFaceDown(true);
            assertTrue(secret.isFaceDown());
            assertEquals(InteractiveState.knownNow(h.game, secret.getView(), h.seat.getView()), null);
            final JsonObject entry = topEntry(h);
            assertFalse(entry.toString().contains("Gemrazer"), entry.toString());
            assertEquals(entry.get("description").getAsString(), "Sneak Attack — triggered ability", entry.toString());
        }
    }

    /** r4 finding 4: Forge lowercases the first letter of a wrapped ability's words ("grizzly Bears draws a card."). */
    @Test(timeOut = 900000)
    public void r4_forgesLowercasedNameIsHidden() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card secret = addCard("Grizzly Bears", h.foe);
            final SpellAbility inner = ability(host, h.foe, "DB$ Draw | Defined$ You | SpellDescription$ ORIGINALHOST draws a card.");
            inner.setCardState(secret.getCurrentState());
            final forge.game.trigger.Trigger trigger = forge.game.trigger.TriggerHandler.parseTrigger(
                    "Mode$ Phase | Phase$ End Of Turn | TriggerDescription$ At the beginning of your end step, ABILITY", host, false);
            final SpellAbility sa = new forge.game.trigger.WrappedAbility(trigger, inner, h.foe);
            sa.setActivatingPlayer(h.foe);
            sa.setTriggeringObject(AbilityKey.Player, h.foe);
            final String raw = sa.getStackDescription();
            assertTrue(raw.contains("grizzly Bears"), raw);
            h.game.getStack().add(sa);
            secret.turnFaceDown(true);
            entryIs(topEntry(h), "Sneak Attack", "Sneak Attack — triggered ability", secret);
            assertFalse(topEntry(h).toString().contains("grizzly Bears"));
        }
    }

    /**
     * r4 finding 5: two different triggers to order whose labels both fall to the fixed one (a copy of each host is
     * hidden in the library) are told apart by position, and each id still maps to its own trigger.
     */
    @Test(timeOut = 900000)
    public void r4_differentTriggersToOrderStayDistinguishable() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final List<SpellAbility> abilities = new ArrayList<>();
            for (String name : List.of("Mulldrifter", "Fleshbag Marauder")) {
                final Card host = addCard(name, h.seat);
                addCardToZone(name, h.seat, ZoneType.Library);
                abilities.add(etb(h, host, h.seat));
            }
            final java.util.concurrent.atomic.AtomicReference<List<SpellAbility>> picked =
                    new java.util.concurrent.atomic.AtomicReference<>();
            h.onGameThread(() -> picked.set(h.controller.orderSimultaneousSa(abilities)));
            final JsonObject order = h.request();
            final List<String> labels = itemLabels(order);
            System.out.println("R4 ORDER: " + labels);
            assertEquals(labels, List.of("A hidden card — triggered ability (1 of 2)",
                    "A hidden card — triggered ability (2 of 2)"));
            final JsonObject action = new JsonObject();
            action.addProperty("type", "order");
            action.addProperty("controlId", "selection-order");
            final JsonArray ids = new JsonArray();
            ids.add("item:1");
            ids.add("item:0");
            action.add("order", ids);
            h.send(order, action);
            h.gameThread.join(30000);
            assertEquals(picked.get(), List.of(abilities.get(1), abilities.get(0)), "each id maps to its own trigger");
        }
    }

    /** Review fix 4: identical fixed labels on the stack are told apart by position too; names are left as they are. */
    @Test(timeOut = 900000)
    public void r4_identicalFixedStackLabelsAreToldApart() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            for (String name : List.of("Mulldrifter", "Fleshbag Marauder")) {
                final Card host = addCard(name, h.foe);
                addCardToZone(name, h.foe, ZoneType.Library);
                h.game.getStack().add(etb(h, host, h.foe));
            }
            final JsonArray stack = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack");
            System.out.println("R4 STACK: " + stack);
            assertEquals(stack.size(), 2, stack.toString());
            for (int i = 0; i < 2; i++) {
                final JsonObject entry = stack.get(i).getAsJsonObject();
                assertEquals(entry.get("name").getAsString(), "A hidden card", stack.toString());
                assertEquals(entry.get("description").getAsString(),
                        "A hidden card — triggered ability (" + (i + 1) + " of 2)", stack.toString());
            }
        }
    }

    /** The discriminator itself: only identical labels, by position; others and nulls unchanged. */
    @Test(timeOut = 900000)
    public void r4_discriminated() {
        final List<String> labels = new ArrayList<>(List.of("A", "B", "A"));
        labels.add(null);
        labels.add("A");
        labels.add(null);
        final List<String> expected = new ArrayList<>(List.of("A (1 of 3)", "B", "A (2 of 3)"));
        expected.add(null);
        expected.add("A (3 of 3)");
        expected.add(null);
        assertEquals(InteractiveState.discriminated(labels), expected);
        assertEquals(InteractiveState.discriminated(List.of("X", "Y")), List.of("X", "Y"));
    }
    // ------------------------------------------------------------------ review fix 5

    /**
     * r5 finding: Forge renders a card's display name, so the Cecily, Haunted Mage printing (stored as Eleven, the Mage)
     * read "Return Cecily, Haunted Mage." after it was turned face down. Both names are hidden now.
     */
    @Test(timeOut = 900000)
    public void r5_aPrintedFlavorNameIsHidden() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card secret = addCard("Cecily, Haunted Mage", h.foe);
            assertEquals(secret.getName(), "Eleven, the Mage");
            assertEquals(secret.getDisplayName(), "Cecily, Haunted Mage");
            h.game.getStack().add(returnOriginal(h, host, secret));
            secret.turnFaceDown(true);
            assertEquals(InteractiveState.knownNow(h.game, secret.getView(), h.seat.getView()), null);
            final JsonObject entry = topEntry(h);
            System.out.println("R5 FLAVOR: " + entry);
            assertFalse(entry.toString().contains("Cecily") || entry.toString().contains("Eleven"), entry.toString());
            assertEquals(entry.get("description").getAsString(), "Sneak Attack — triggered ability", entry.toString());
            final InteractiveState.HiddenNames hidden = InteractiveState.HiddenNames.of(h.game, h.seat.getView());
            assertFalse(hidden.safe("Return Cecily, Haunted Mage."));
            assertFalse(hidden.safe("Return Eleven, the Mage."));
        }
    }
    // ------------------------------------------------------------------ review fix 6

    /** Astra's r6 repro: a sub-ability hosted by {@code printed} renders "Return NICKNAME." as its nickname. */
    private void nicknameIsHidden(final String printed, final String nickname) throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card host = addCard("Sneak Attack", h.foe);
            final Card secret = addCard(printed, h.foe);
            final SpellAbility wrapper = wrapped(host, h.foe, null, "ABILITY");
            final SpellAbility inner = ((forge.game.trigger.WrappedAbility) wrapper).getWrappedAbility();
            inner.setSubAbility((forge.game.spellability.AbilitySub) ability(secret, h.foe,
                    "DB$ Draw | Defined$ You | SpellDescription$ Return NICKNAME."));
            final String rendered = wrapper.getStackDescription();
            assertTrue(rendered.contains("Return " + nickname + "."), rendered);
            h.game.getStack().add(wrapper);
            secret.turnFaceDown(true);
            assertEquals(InteractiveState.knownNow(h.game, secret.getView(), h.seat.getView()), null);
            final JsonObject entry = topEntry(h);
            System.out.println("R6 NICKNAME " + printed + ": " + entry);
            assertFalse(entry.toString().contains(nickname), entry.toString());
            assertEquals(entry.get("description").getAsString(), "Sneak Attack — triggered ability", entry.toString());
        }
    }

    /** r6 finding: Forge's NICKNAME renders "Nicol Bolas" for Nicol Bolas, the Ravager. */
    @Test(timeOut = 900000)
    public void r6_aRawNicknameIsHidden() throws Exception {
        nicknameIsHidden("Nicol Bolas, the Ravager", "Nicol Bolas");
    }

    /** r6 finding: the Cecily, Haunted Mage printing's NICKNAME renders "Cecily", its flavor name's nickname. */
    @Test(timeOut = 900000)
    public void r6_aFlavorNicknameIsHidden() throws Exception {
        nicknameIsHidden("Cecily, Haunted Mage", "Cecily");
    }

    /**
     * The other rendered name forms (#253 "Rendered name forms covered"): a nickname wherever Forge can render one (a
     * legendary face without a comma, a script that uses NICKNAME), none where it cannot (a sorcery's first word) and
     * none that is only an article; an effect's generated name and a card's "Name (id)" form.
     */
    @Test(timeOut = 900000)
    public void r6_renderedNameForms() throws Exception {
        try (Harness h = new Harness((seat, foe) -> { })) {
            final Card cruise = addCardToZone("Treasure Cruise", h.foe, ZoneType.Hand);
            addCardToZone("Phage the Untouchable", h.foe, ZoneType.Hand);
            addCardToZone("Serum Sovereign", h.foe, ZoneType.Hand);
            addCardToZone("The One Ring", h.foe, ZoneType.Hand);
            final InteractiveState.HiddenNames hidden = InteractiveState.HiddenNames.of(h.game, h.seat.getView());
            // Nicknames Forge can render: a legendary face, and a card whose script uses NICKNAME.
            assertFalse(hidden.safe("Phage deals damage."), "a legendary face's nickname");
            assertFalse(hidden.safe("Remove an oil counter from Serum."), "Serum Sovereign's script uses NICKNAME");
            // None where Forge renders none, and none that is only an article.
            assertTrue(hidden.safe("Create a Treasure token."), "a sorcery has no nickname");
            assertFalse(hidden.safe("Treasure Cruise"));
            assertTrue(hidden.safe("The Monarch — draw a card."), "\"The\" names no card");
            assertFalse(hidden.safe("The One Ring"));
            // The effect name and "Name (id)" forms carry the name or the id.
            assertFalse(hidden.safe("Phage the Untouchable's Effect"));
            assertFalse(hidden.safe("A card (" + cruise.getId() + ")'s Effect"));
        }
    }
}
