package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.effects.CopySpellAbilityEffect;
import forge.game.card.Card;
import forge.game.card.CardFactory;
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
import java.util.ArrayList;
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
 * Prompt items, stack entries and ability labels name their source as the viewing seat sees it; "Face-down card" is
 * kept for what is actually face down or hidden from that seat (lane facedown-label-1021).
 *
 * <p>Owner report 2026-10-21T17-36-59: with the seat's own Brain Freeze storm trigger resolving, the "Resolve first"
 * order prompt listed every copy as "Ability of face-down card (87)" ... "(96)". A spell copy is made with no zone
 * (CardFactory.copySpellHost) and the order is asked before the copies go on the stack; the bridge's identity check
 * failed every zoneless card closed. Owner report 2026-10-21T21-17-01: a review row read "Cast Lórien Revealed ·
 * (Face-down cardcycling {1})". A hidden Island (a library card) made the bare-name redaction cut "Island" out of
 * "Islandcycling" and out of "Search your library for an Island card".</p>
 *
 * <p>Negatives: a face-down morph, a card in the opponent's hand and a library card stay hidden.</p>
 */
public class InteractiveSourceLabelTest extends AITest {

    private static final String SESSION = "source-label-test";

    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();
        /** Set during a play: the priority loop ends once that play is done instead of asking again. */
        volatile boolean stopLoop;

        /** {@code priorityLoop}: run the seat's priority loop (clicks at priority); otherwise the test drives Forge. */
        Harness(final BiConsumer<Player, Player> board, final boolean priorityLoop) throws IOException {
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
            if (priorityLoop) {
                onGameThread(() -> {
                    while (!closed.get() && !stopLoop && !gui.hasFailed() && !game.isGameOver()) {
                        final List<SpellAbility> chosen = controller.chooseSpellAbilityToPlay();
                        if (chosen != null) {
                            for (SpellAbility ability : chosen) {
                                controller.playChosenSpellAbility(ability);
                            }
                        }
                    }
                });
            }
        }

        /** The last thread {@link #onGameThread} started. */
        volatile Thread gameThread;

        /** Run {@code body} on a thread named like Forge's game threads; a closed session's abort ends it quietly. */
        void onGameThread(final Runnable body) {
            final Thread thread = new Thread(() -> {
                try {
                    body.run();
                } catch (RuntimeException e) {
                    if (!closed.get()) {
                        throw e;
                    }
                }
            }, "Game thread (source label test)");
            thread.setDaemon(true);
            gameThread = thread;
            thread.start();
        }

        JsonObject request() throws InterruptedException {
            return next("request");
        }

        /** The next request while the game thread runs, or null once it has ended. */
        JsonObject requestWhilePlaying() throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                final JsonObject m = wire.poll(200, TimeUnit.MILLISECONDS);
                if (m == null) {
                    if (!gameThread.isAlive()) return null;
                    continue;
                }
                if ("error".equals(m.get("type").getAsString())) fail("the bridge failed: " + m);
                if ("request".equals(m.get("type").getAsString())) return m;
            }
            fail("neither a request nor the end of the play");
            return null;
        }

        /** The next wire message of {@code type}, skipping the others. */
        JsonObject next(final String type) throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final JsonObject m = wire.poll(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                assertNotNull(m, "a " + type);
                if ("error".equals(m.get("type").getAsString())) {
                    fail("the bridge failed: " + m);
                }
                if (type.equals(m.get("type").getAsString())) {
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

        /** Click {@code card} at priority and return the next request. */
        JsonObject click(final Card card) throws IOException, InterruptedException {
            final JsonObject priority = request();
            assertEquals(priority.get("kind").getAsString(), "priority", priority.toString());
            final JsonObject action = new JsonObject();
            action.addProperty("type", "selectCard");
            action.addProperty("controlId", "card:" + card.getId());
            send(priority, action);
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

    /** {@code card}'s spell put on the stack by {@code caster}, targeting {@code target} (as GameCopierStackTest does). */
    private static SpellAbility onStack(final Game game, final Card card, final Player caster, final Player target) {
        final SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStackZone().add(card);
        game.getStack().add(sa);
        return sa;
    }

    /** Brain Freeze's own storm trigger body (CardFactoryUtil "Storm"), with the copy count fixed. */
    private static SpellAbility stormTrigger(final Card host, final SpellAbility spell, final Player you,
                                             final int copies) {
        final SpellAbility storm = AbilityFactory.getAbility("DB$ CopySpellAbility | Defined$ TriggeredSpellAbility"
                + " | Amount$ " + copies + " | MayChooseTarget$ True", host);
        storm.setActivatingPlayer(you);
        storm.setTriggeringObject(AbilityKey.SpellAbility, spell);
        return storm;
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

    /** The first control whose id starts with {@code prefix}, or null. */
    private static JsonObject control(final JsonObject request, final String prefix) {
        for (JsonElement control : request.getAsJsonArray("controls")) {
            if (control.getAsJsonObject().get("controlId").getAsString().startsWith(prefix)) {
                return control.getAsJsonObject();
            }
        }
        return null;
    }

    private static List<String> controlLabels(final JsonObject request, final String type) {
        final List<String> labels = new ArrayList<>();
        for (JsonElement control : request.getAsJsonArray("controls")) {
            final JsonObject object = control.getAsJsonObject();
            if (type.equals(object.get("type").getAsString())) {
                labels.add(object.get("label").getAsString());
            }
        }
        return labels;
    }

    /** The report: the seat orders its own Brain Freeze's storm copies. Each names Brain Freeze, never a face-down card. */
    @Test(timeOut = 900000)
    public void stormCopiesAreOrderedAsTheSeatsOwnBrainFreeze() throws Exception {
        final Card[] freeze = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            freeze[0] = addCardToZone("Brain Freeze", seat, ZoneType.Hand);
            // Hidden cards that must stay hidden: a second Brain Freeze in the seat's library, the foe's hand.
            addCardToZone("Brain Freeze", seat, ZoneType.Library);
            addCardToZone("Lightning Bolt", foe, ZoneType.Hand);
        }, false)) {
            final SpellAbility spell = onStack(h.game, freeze[0], h.seat, h.foe);
            final SpellAbility storm = stormTrigger(freeze[0], spell, h.seat, 3);
            h.onGameThread(() -> new CopySpellAbilityEffect().resolve(storm));

            final JsonObject order = h.request();
            assertEquals(order.get("kind").getAsString(), "order", order.toString());
            final List<String> labels = itemLabels(order);
            System.out.println("STORM ORDER ITEMS: " + labels);
            assertEquals(labels.size(), 3, order.toString());
            for (String label : labels) {
                assertFalse(label.toLowerCase().contains("face-down"), "a copy of the seat's own spell: " + labels);
                assertFalse(label.toLowerCase().contains("hidden"), "a copy of the seat's own spell: " + labels);
                assertTrue(label.startsWith("Brain Freeze (copy)"), "named by its spell, as a copy: " + labels);
            }
            assertFalse(order.toString().contains("Lightning Bolt"), "the foe's hand stays hidden: " + order);

            // Resolve them in the order offered; the copies go on the stack, where they are public too.
            final JsonObject action = new JsonObject();
            action.addProperty("type", "order");
            action.addProperty("controlId", "selection-order");
            final JsonArray ids = new JsonArray();
            for (int i = 0; i < labels.size(); i++) {
                ids.add("item:" + i);
            }
            action.add("order", ids);
            h.send(order, action);
            // Each copy, on its way to the stack, asks for its target ("You may choose new targets"): the foe again.
            // The prompt names the copy, by the id Forge gave it, as the seat's own Brain Freeze.
            for (int i = 0; i < labels.size(); i++) {
                final JsonObject ask = h.request();
                final String message = ask.getAsJsonObject("prompt").get("message").getAsString();
                System.out.println("COPY TARGET: " + ask.get("kind") + " " + message);
                assertEquals(ask.get("kind").getAsString(), "target", ask.toString());
                assertTrue(message.matches("(?s)Brain Freeze \\(\\d+\\) - Target player mills three cards\\..*"), message);
                assertFalse(ask.toString().contains("Face-down"), ask.toString());
                assertFalse(ask.toString().contains("Lightning Bolt"), "the foe's hand stays hidden: " + ask);
                final JsonObject foe = new JsonObject();
                foe.addProperty("type", "selectPlayer");
                foe.addProperty("controlId", "player:0");
                h.send(ask, foe);
            }
            h.gameThread.join(30000);
            assertFalse(h.gameThread.isAlive(), "the copies are on the stack");
            final JsonArray stack = InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack");
            System.out.println("STACK AFTER: " + stack);
            assertEquals(stack.size(), 4, stack.toString());
            int copies = 0;
            for (JsonElement entry : stack) {
                assertEquals(entry.getAsJsonObject().get("name").getAsString(), "Brain Freeze", stack.toString());
                if (entry.getAsJsonObject().get("isCopy").getAsBoolean()) copies++;
            }
            assertEquals(copies, 3, stack.toString());
            assertFalse(stack.toString().contains("Face-down"), stack.toString());
            assertFalse(stack.toString().contains("Hidden spell"), stack.toString());
            // The foe sees the same copies: they are public.
            final JsonArray foeStack = InteractiveState.seatView(h.game, h.foe, 0).getAsJsonArray("stack");
            assertEquals(foeStack.toString().split("\"name\":\"Brain Freeze\"", -1).length - 1, 4, foeStack.toString());
        }
    }

    /** Control: the seat orders two of its own dies triggers (sources that left the battlefield): each names its card. */
    @Test(timeOut = 900000)
    public void theSeatsOwnDiesTriggersNameTheirCards() throws Exception {
        final Card[] wrath = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Doomed Traveler", seat);
            addCard("Wurmcoil Engine", seat);
            wrath[0] = addCardToZone("Wrath of God", seat, ZoneType.Hand);
            addCardToZone("Lightning Bolt", foe, ZoneType.Hand);
        }, false)) {
            final SpellAbility destroy = AbilityFactory.getAbility("SP$ DestroyAll | ValidCards$ Creature", wrath[0]);
            destroy.setActivatingPlayer(h.seat);
            h.onGameThread(() -> {
                forge.game.ability.AbilityUtils.resolve(destroy);
                h.game.getAction().checkStateEffects(true);
                h.game.getStack().addAllTriggeredAbilitiesToStack();
            });
            final JsonObject order = h.request();
            final List<String> labels = itemLabels(order);
            System.out.println("DIES ORDER: " + order.get("kind") + " " + labels + " " + order.getAsJsonObject("prompt"));
            assertEquals(order.get("kind").getAsString(), "order", order.toString());
            assertEquals(labels.size(), 2, labels.toString());
            assertTrue(labels.stream().anyMatch(l -> l.startsWith("When Doomed Traveler dies")), labels.toString());
            assertTrue(labels.stream().anyMatch(l -> l.startsWith("When Wurmcoil Engine dies")), labels.toString());
            assertFalse(order.toString().contains("Face-down"), order.toString());
            assertFalse(order.toString().contains("Lightning Bolt"), "the foe's hand stays hidden: " + order);
        }
    }

    /** The foe cracks a Clue (sacrificed for its own cost): the stack entry is the Clue's to both seats, not face down. */
    @Test
    public void aCrackedCluesAbilityOnTheStackIsTheClues() {
        final Game game = initAndCreateGame();
        final Player seat = game.getPlayers().get(1);
        final Player foe = game.getPlayers().get(0);
        final Card clue = crackedToken(game, foe, false);
        assertEquals(game.findById(clue.getId()), null, "the token has ceased to exist");
        for (JsonArray stack : List.of(InteractiveState.seatView(game, seat, 1).getAsJsonArray("stack"),
                InteractiveState.seatView(game, foe, 0).getAsJsonArray("stack"))) {
            System.out.println("CLUE STACK: " + stack);
            assertEquals(stack.size(), 1, stack.toString());
            final JsonObject entry = stack.get(0).getAsJsonObject();
            assertEquals(entry.get("name").getAsString(), "Clue Token", stack.toString());
            assertTrue(entry.get("isToken").getAsBoolean(), stack.toString());
            assertFalse(stack.toString().contains("Face-down"), stack.toString());
            assertFalse(stack.toString().contains("Hidden spell"), stack.toString());
        }
    }

    /**
     * Negative: a token source that is face down stays a face-down source, for every seat. (Forge turns a face-down
     * permanent face up as it leaves the battlefield, CR 708.9, so the test turns the ceased token face down itself.)
     */
    @Test
    public void aCeasedFaceDownTokensAbilityStaysHidden() {
        final Game game = initAndCreateGame();
        final Player seat = game.getPlayers().get(1);
        final Player foe = game.getPlayers().get(0);
        crackedToken(game, foe, true);
        for (JsonArray stack : List.of(InteractiveState.seatView(game, seat, 1).getAsJsonArray("stack"),
                InteractiveState.seatView(game, foe, 0).getAsJsonArray("stack"))) {
            assertEquals(stack.get(0).getAsJsonObject().get("name").getAsString(), "Face-down spell or ability",
                    stack.toString());
            assertFalse(stack.toString().contains("Clue"), stack.toString());
        }
    }

    /**
     * Owner report 2026-10-22T00-12-29: the seat's own Map token, sacrificed for "{1}, {T}, Sacrifice this token: Target
     * creature you control explores" (target: a Phyrexian Golem token), read "Hidden casting or ability" on the stack,
     * the skin's words for the bridge's "Hidden spell or ability". Activated over the wire through Forge's own targeting
     * and payment; then state-based actions end the sacrificed token and the entry must still be the Map Token's.
     */
    @Test(timeOut = 900000)
    public void theSeatsSacrificedMapTokenIsTheStackEntrysSource() throws Exception {
        final Card[] map = new Card[1];
        final Card[] golem = new Card[1];
        final Card[] forest = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            forest[0] = addCard("Forest", seat);
            map[0] = addToken("c_a_map_sac_explore", seat);
            golem[0] = addToken("c_3_3_a_phyrexian_golem", seat);
            addCardToZone("Lightning Bolt", foe, ZoneType.Hand);
        }, true)) {
            JsonObject request = h.click(map[0]);
            // The harness's seat is a LobbyPlayerAi, which Forge's auto-yield check refuses once the stack is non-empty:
            // the priority loop ends after this play instead of asking again.
            h.stopLoop = true;
            // Forge's own questions until the ability is on the stack: the Golem is the target, the Forest pays {1},
            // yes to the sacrifice.
            while (request != null) {
                final String kind = request.get("kind").getAsString();
                System.out.println("MAP STEP: " + kind + " " + request.getAsJsonObject("prompt").get("message")
                        + " " + request.getAsJsonArray("controls"));
                final JsonObject action = new JsonObject();
                final JsonObject fromPool = control(request, "payment:");
                if ("mana".equals(kind) && fromPool != null) {
                    // The Forest's mana waits in the pool (NoPaymentFromManaAbility, as live): pay with it.
                    action.addProperty("type", fromPool.get("type").getAsString());
                    action.addProperty("controlId", fromPool.get("controlId").getAsString());
                } else if ("target".equals(kind) || "mana".equals(kind)) {
                    action.addProperty("type", "selectCard");
                    action.addProperty("controlId", "card:" + ("target".equals(kind) ? golem[0] : forest[0]).getId());
                } else if ("confirm".equals(kind)) {
                    action.addProperty("type", "confirm");
                    action.addProperty("controlId", "confirm:yes");
                    action.addProperty("confirm", true);
                } else {
                    fail("unexpected request: " + request);
                }
                h.send(request, action);
                request = h.requestWhilePlaying();
            }
            h.gameThread.join(30000);
            assertFalse(h.gameThread.isAlive(), "the play is done");
            assertEquals(h.game.getStack().size(), 1, "the explore ability is on the stack");
            // In the real game state-based actions run before the next priority: the token ceases to exist.
            h.game.getAction().checkStateEffects(true);
            assertEquals(h.game.findById(map[0].getId()), null, "the sacrificed token has ceased to exist");
            for (JsonArray stack : List.of(InteractiveState.seatView(h.game, h.seat, 1).getAsJsonArray("stack"),
                    InteractiveState.seatView(h.game, h.foe, 0).getAsJsonArray("stack"))) {
                System.out.println("MAP STACK: " + stack);
                assertEquals(stack.size(), 1, stack.toString());
                final JsonObject entry = stack.get(0).getAsJsonObject();
                assertEquals(entry.get("name").getAsString(), "Map Token", stack.toString());
                assertEquals(entry.get("fid").getAsInt(), map[0].getId(), stack.toString());
                assertTrue(entry.get("description").getAsString().contains("explores"), stack.toString());
                assertTrue(stack.toString().contains("Phyrexian Golem"), "its target: " + stack);
                assertFalse(stack.toString().contains("Face-down"), stack.toString());
                assertFalse(stack.toString().contains("Hidden spell"), stack.toString());
                assertFalse(stack.toString().contains("Lightning Bolt"), "the foe's hand stays hidden: " + stack);
            }
        }
    }

    /** {@code owner}'s Clue, moved to the graveyard as its cost, its ability on the stack, the token gone (SBA). */
    private Card crackedToken(final Game game, final Player owner, final boolean faceDown) {
        final Card clue = addToken("c_a_clue_draw", owner);
        game.getAction().checkStateEffects(true);
        final SpellAbility crack = clue.getSpellAbilities().stream().filter(sa -> !sa.isManaAbility()).findFirst().get();
        crack.setActivatingPlayer(owner);
        game.getAction().moveToGraveyard(clue, null);
        game.getStack().add(crack);
        game.getAction().checkStateEffects(true);
        assertEquals(game.getStack().peek().getSourceCard(), clue);
        if (faceDown) {
            clue.turnFaceDown(true);
            assertTrue(clue.getView().isFaceDown(), "the ceased token is face down");
        }
        return clue;
    }

    /** A copy of a spell in no zone yet is as public as the stack it is going onto, to both seats. */
    @Test
    public void aZonelessSpellCopyIsPublicToBothSeats() {
        final Game game = initAndCreateGame();
        final Player seat = game.getPlayers().get(1);
        final Player foe = game.getPlayers().get(0);
        final Card freeze = addCardToZone("Brain Freeze", seat, ZoneType.Hand);
        final SpellAbility spell = onStack(game, freeze, seat, foe);
        final SpellAbility storm = stormTrigger(freeze, spell, seat, 1);
        final SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(storm, spell, seat);
        final Card host = copy.getHostCard();
        assertTrue(host != freeze && host.getZone() == null && host.isCopiedSpell(), "a fresh zoneless copy");
        assertTrue(InteractiveState.mayReceiveIdentity(host.getView(), seat.getView()));
        assertTrue(InteractiveState.mayReceiveIdentity(host.getView(), foe.getView()));
        assertEquals(InteractiveState.safeCardLabel(host.getView(), foe.getView()), "Brain Freeze (" + host.getId() + ")");
    }

    /** Negative: a copy that is face down stays hidden even with no zone (fails closed, for its controller too). */
    @Test
    public void aFaceDownZonelessCopyStaysHidden() {
        final Game game = initAndCreateGame();
        final Player seat = game.getPlayers().get(1);
        final Player foe = game.getPlayers().get(0);
        final Card bears = addCardToZone("Grizzly Bears", foe, ZoneType.Hand);
        final SpellAbility spell = onStack(game, bears, foe, null);
        final SpellAbility copySource = AbilityFactory.getAbility("DB$ CopySpellAbility | Defined$ TriggeredSpellAbility", bears);
        copySource.setActivatingPlayer(foe);
        final SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(copySource, spell, foe);
        final Card host = copy.getHostCard();
        host.turnFaceDown(true);
        assertTrue(host.getZone() == null && host.isCopiedSpell() && host.getView().isFaceDown(), "a zoneless face-down copy");
        assertFalse(InteractiveState.mayReceiveIdentity(host.getView(), seat.getView()));
        assertFalse(InteractiveState.mayReceiveIdentity(host.getView(), foe.getView()));
        assertEquals(InteractiveState.safeCardLabel(host.getView(), seat.getView()), "Face-down card (" + host.getId() + ")");
    }

    /** Negative: any other zoneless view (here the foe's hand card behind a frozen tracker) still fails closed. */
    @Test
    public void aZonelessCardThatIsNoSpellCopyStillFailsClosed() {
        final Game game = initAndCreateGame();
        final Player seat = game.getPlayers().get(1);
        final Player foe = game.getPlayers().get(0);
        game.getAction().checkStateEffects(true);
        game.getTracker().freeze();
        try {
            final Card bolt = addCardToZone("Lightning Bolt", foe, ZoneType.Hand);
            assertEquals(bolt.getView().getZone(), null, "the frozen tracker left the view zoneless");
            assertFalse(InteractiveState.mayReceiveIdentity(bolt.getView(), seat.getView()));
            assertEquals(InteractiveState.safeCardLabel(bolt.getView(), seat.getView()), "Face-down card (" + bolt.getId() + ")");
        } finally {
            game.getTracker().unfreeze();
        }
        final Card bolt = foe.getCardsIn(ZoneType.Hand).get(0);
        assertFalse(InteractiveState.mayReceiveIdentity(bolt.getView(), seat.getView()), "the foe's hand, zoned");
        assertTrue(InteractiveState.mayReceiveIdentity(bolt.getView(), foe.getView()), "its owner's own hand");
    }

    /**
     * The review row's source: Lórien Revealed's "Choose an ability" lists its islandcycling in its own words while an
     * Island sits hidden in the seat's library and in the foe's hand. (The review reads this label.)
     */
    @Test(timeOut = 900000)
    public void cyclingKeepsItsOwnWordsBesideAHiddenIsland() throws Exception {
        final Card[] lorien = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Island", seat);
            lorien[0] = addCardToZone("Lórien Revealed", seat, ZoneType.Hand);
            addCardToZone("Island", seat, ZoneType.Library);
            addCardToZone("Island", foe, ZoneType.Hand);
            addCardToZone("Lightning Bolt", foe, ZoneType.Hand);
        }, true)) {
            final JsonObject choose = h.click(lorien[0]);
            final List<String> abilities = controlLabels(choose, "selectAbility");
            System.out.println("LORIEN ABILITIES: " + abilities + " / " + choose.getAsJsonObject("prompt"));
            final String cycling = abilities.stream().filter(l -> l.contains("cycling")).findFirst().orElse(null);
            assertNotNull(cycling, abilities.toString());
            assertTrue(cycling.startsWith("Islandcycling {1}"), cycling);
            assertTrue(cycling.contains("Search your library for an Island card"), cycling);
            assertFalse(choose.toString().contains("Face-down"), choose.toString());
            assertFalse(choose.toString().contains("Lightning Bolt"), "the foe's hand stays hidden: " + choose);
        }
    }

    /** Negative: the foe's face-down morph is a face-down card in the seat's target list, and its name never travels. */
    @Test(timeOut = 900000)
    public void theFoesFaceDownMorphStaysAFaceDownCard() throws Exception {
        final Card[] swords = new Card[1];
        final Card[] morph = new Card[1];
        try (Harness h = new Harness((seat, foe) -> {
            addCard("Plains", seat);
            swords[0] = addCardToZone("Swords to Plowshares", seat, ZoneType.Hand);
            morph[0] = addCard("Ainok Tracker", foe);
            morph[0].turnFaceDown(true);
        }, true)) {
            assertFalse(InteractiveState.mayReceiveIdentity(morph[0].getView(), h.seat.getView()));
            assertTrue(InteractiveState.mayReceiveIdentity(morph[0].getView(), h.foe.getView()), "its controller may look");
            final JsonObject target = h.click(swords[0]);
            final List<String> labels = controlLabels(target, "selectCard");
            System.out.println("SWORDS TARGETS: " + labels);
            assertTrue(labels.stream().anyMatch(l -> l.endsWith("Face-down card (" + morph[0].getId() + ")")), labels.toString());
            assertFalse(target.toString().contains("Ainok"), target.toString());
        }
    }
}
