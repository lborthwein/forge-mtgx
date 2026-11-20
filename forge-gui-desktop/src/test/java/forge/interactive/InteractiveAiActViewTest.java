package forge.interactive;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.event.GameEventPlayerPriority;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.player.PlayerView;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

/**
 * AI ACT VIEWS (ai-beat-views-1120; owner ask 2026-11-19: the AI Player's actions one at a time). With the config's
 * {@code aiActViews} on, a one-seat game sends the human seat its own view ({@code state}, {@code "reason": "aiAct"})
 * each time the AI Player is about to decide at priority in the real game, when a game event reached the seat since its
 * last view and the view changed. Off (absent), nothing is added to the game.
 *
 * <p>The game runs Forge's real priority loop ({@code PhaseHandler.mainLoopStep}) on a thread named like a game
 * thread. Seat 0 is the browser's seat (a {@link PlayerControllerHuman} on the NDJSON bridge; a test client passes each
 * of its priority requests, as the host's auto-pass does); seat 1 is the classic Forge AI.</p>
 */
public class InteractiveAiActViewTest extends AITest {

    private static final String SESSION = "ai-act-view-test";
    /** What the AI Player holds that the seat must never be shown while it stays hidden. */
    private static final List<String> NEVER_SHOWN = List.of("Shivan Dragon", "Llanowar Elves", "Giant Growth");

    private static String config(final String extra) {
        return "{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":\"config\",\"session\":\"s\","
                + "\"humanSeat\":0,\"seed\":7,\"aiProfile\":\"Default\","
                + "\"decks\":[\"/tmp/a.dck\",\"/tmp/b.dck\"]" + extra + "}";
    }

    /** The seat's bridge over a real game; the Forge loop runs only when {@link #play} is called. */
    private final class OneSeat implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player ai;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        /** Every message taken off the wire, in order. */
        final List<JsonObject> seen = new CopyOnWriteArrayList<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();

        OneSeat(final boolean aiActViews) throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(0);
            ai = game.getPlayers().get(1);
            // The AI Player's main phase 2 (a ritual for a permanent is cast in main 2: ManaAi.doManaRitualLogic)
            addCard("Plains", seat);
            addCardToZone("Serra Angel", seat, ZoneType.Hand);
            addCard("Swamp", ai);
            addCardToZone("Swamp", ai, ZoneType.Hand);
            addCardToZone("Dark Ritual", ai, ZoneType.Hand);
            addCardToZone("Hypnotic Specter", ai, ZoneType.Hand);
            addCardToZone("Shivan Dragon", ai, ZoneType.Hand);
            addCardToZone("Llanowar Elves", ai, ZoneType.Library);
            addCardToZone("Giant Growth", ai, ZoneType.Library);
            game.getPhaseHandler().devModeSet(PhaseType.MAIN2, ai);
            game.getAction().checkStateEffects(true);

            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            controller.getFullControl().add(FullControlFlag.NoPaymentFromManaAbility);
            seat.dangerouslySetController(controller);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(
                    new BufferedReader(new PipedReader(client, 1 << 16)),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 0);
            gui.bind(game, seat, controller);
            controller.setGui(gui);
            gui.setGameView(game.getView());
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui,
                    new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            // exactly as InteractiveMain.runSession wires a one-seat game
            InteractiveMain.armAiActViews(aiActViews, game, gui);
            gui.startReader();
        }

        /**
         * Forge's priority loop until the AI Player's end step, on a game thread, while this test answers every
         * request of the seat with a pass (the host's auto-pass). Returns every message, in wire order.
         */
        List<JsonObject> play() throws Exception {
            final CompletableFuture<Void> done = new CompletableFuture<>();
            final Thread forge = new Thread(() -> {
                try {
                    playUntilPhase(game, PhaseType.END_OF_TURN);
                    done.complete(null);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            }, "Game thread (ai act view test)");
            forge.setDaemon(true);
            forge.start();
            final long deadline = System.currentTimeMillis() + 120_000;
            while (true) {
                final JsonObject message = wire.poll(50, TimeUnit.MILLISECONDS);
                if (message == null) {
                    if (done.isDone() && wire.isEmpty()) {
                        break;
                    }
                    if (System.currentTimeMillis() > deadline) {
                        fail("the AI Player's turn did not end: " + summary(seen));
                    }
                    continue;
                }
                seen.add(message);
                final String type = message.get("type").getAsString();
                if ("error".equals(type)) {
                    fail("the bridge failed: " + message);
                }
                if ("request".equals(type)) {
                    assertTrue(controlIds(message).contains("priority:pass"),
                            "the seat is only asked to pass here: " + message);
                    pass(message);
                }
            }
            done.join();
            return new ArrayList<>(seen);
        }

        void pass(final JsonObject request) throws IOException {
            final JsonObject input = new JsonObject();
            input.addProperty("protocol", "mtgx-forge-interactive/1");
            input.addProperty("session", SESSION);
            input.addProperty("type", "input");
            input.addProperty("requestId", request.get("requestId").getAsString());
            input.addProperty("inputId", UUID.randomUUID().toString());
            input.addProperty("kind", request.get("kind").getAsString());
            final JsonObject action = new JsonObject();
            action.addProperty("type", "passPriority");
            action.addProperty("controlId", "priority:pass");
            input.add("action", action);
            client.write(input + "\n");
            client.flush();
        }

        /** Every message on the wire now (none expected later: nothing is running). */
        List<JsonObject> drain() throws InterruptedException {
            final List<JsonObject> out = new ArrayList<>();
            JsonObject message;
            while ((message = wire.poll(200, TimeUnit.MILLISECONDS)) != null) {
                out.add(message);
            }
            return out;
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

    private static List<String> controlIds(final JsonObject request) {
        final List<String> ids = new ArrayList<>();
        for (JsonElement control : request.getAsJsonArray("controls")) {
            ids.add(control.getAsJsonObject().get("controlId").getAsString());
        }
        return ids;
    }

    private static boolean isAiActView(final JsonObject message) {
        return "state".equals(message.get("type").getAsString()) && message.has("reason")
                && InteractiveGuiGame.AI_ACT_VIEW_REASON.equals(message.get("reason").getAsString());
    }

    private static String eventKind(final JsonObject message) {
        if (!"event".equals(message.get("type").getAsString())) {
            return null;
        }
        final JsonObject event = message.getAsJsonObject("event");
        return event.has("kind") ? event.get("kind").getAsString() : null;
    }

    private static String eventCard(final JsonObject message) {
        final JsonObject event = message.getAsJsonObject("event");
        return event.has("card") && event.get("card").isJsonPrimitive() ? event.get("card").getAsString() : "";
    }

    private static JsonObject playerOf(final JsonObject view, final int index) {
        for (JsonElement p : view.getAsJsonArray("players")) {
            if (p.getAsJsonObject().get("index").getAsInt() == index) {
                return p.getAsJsonObject();
            }
        }
        throw new AssertionError("no player " + index + " in " + view);
    }

    private static int named(final JsonArray zone, final String name) {
        int n = 0;
        for (JsonElement card : zone) {
            if (card.isJsonObject() && card.getAsJsonObject().has("name")
                    && name.equals(card.getAsJsonObject().get("name").getAsString())) {
                n++;
            }
        }
        return n;
    }

    private static boolean onStack(final JsonObject view, final String name) {
        return named(view.getAsJsonArray("stack"), name) > 0;
    }

    private static String summary(final List<JsonObject> messages) {
        final StringBuilder sb = new StringBuilder();
        for (JsonObject m : messages) {
            final String type = m.get("type").getAsString();
            sb.append(type);
            if ("event".equals(type)) {
                sb.append('(').append(eventKind(m)).append(' ').append(eventCard(m)).append(')');
            } else if ("state".equals(type)) {
                sb.append('(').append(m.has("reason") ? m.get("reason").getAsString() : "?").append(')');
            } else if ("request".equals(type)) {
                sb.append('(').append(m.get("kind").getAsString()).append(')');
            }
            sb.append(' ');
        }
        return sb.toString();
    }

    private static int indexOf(final List<JsonObject> messages, final int from,
                               final java.util.function.Predicate<JsonObject> test) {
        for (int i = Math.max(0, from); i < messages.size(); i++) {
            if (test.test(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    public void aiActViewsIsABooleanConfigKeyThatDefaultsOff() throws Exception {
        assertFalse(InteractiveProtocol.readAiActViews(config("")), "absent: off");
        assertFalse(InteractiveProtocol.readAiActViews(config(",\"aiActViews\":null")), "null: off");
        assertFalse(InteractiveProtocol.readAiActViews(config(",\"aiActViews\":false")));
        assertTrue(InteractiveProtocol.readAiActViews(config(",\"aiActViews\":true")));
        for (String bad : List.of(",\"aiActViews\":\"true\"", ",\"aiActViews\":1", ",\"aiActViews\":{}")) {
            expectThrows(InteractiveProtocol.ProtocolException.class,
                    () -> InteractiveProtocol.readAiActViews(config(bad)));
        }
        // the config itself is unchanged by the key (an old jar ignores it)
        assertEquals(InteractiveProtocol.readConfig(config(",\"aiActViews\":true")).humanSeats(), List.of(0));
    }

    /**
     * The reported case: a land and a ritual in one frame of the AI Player (no request of the seat between them) get a
     * view each, in order: the land-only board after the land's event, then the board with the ritual on the stack
     * after the ritual's cast event, both before the seat's next request.
     */
    @Test(timeOut = 900000)
    public void aLandAndARitualInOneAiFrameEachGetTheirOwnView() throws Exception {
        try (OneSeat s = new OneSeat(true)) {
            final List<JsonObject> m = s.play();
            final String trace = summary(m);
            System.err.println("[ai-act-trace] " + trace);
            final int land = indexOf(m, 0, x -> "land_played".equals(eventKind(x)));
            assertTrue(land >= 0, "the AI Player played its land: " + trace);
            final int landView = indexOf(m, land, x -> isAiActView(x));
            assertTrue(landView > land, "a view after the land: " + trace);
            final JsonObject afterLand = m.get(landView).getAsJsonObject("view");
            assertEquals(named(playerOf(afterLand, 1).getAsJsonArray("battlefield"), "Swamp"), 2,
                    "the land is on the board: " + afterLand);
            assertEquals(afterLand.getAsJsonArray("stack").size(), 0, "nothing on the stack yet: " + afterLand);
            assertFalse(afterLand.toString().contains("Dark Ritual"), "the ritual is still a hidden card in hand");

            final int ritual = indexOf(m, landView, x -> "spell_cast".equals(eventKind(x))
                    && eventCard(x).startsWith("Dark Ritual"));
            assertTrue(ritual > landView, "the ritual is cast after the land's view: " + trace);
            final int ritualView = indexOf(m, ritual, x -> isAiActView(x));
            assertTrue(ritualView > ritual, "a view after the ritual: " + trace);
            final JsonObject afterRitual = m.get(ritualView).getAsJsonObject("view");
            assertTrue(onStack(afterRitual, "Dark Ritual"), "the ritual is on the stack: " + afterRitual);
            assertEquals(named(playerOf(afterRitual, 1).getAsJsonArray("battlefield"), "Swamp"), 2);

            // one frame: the seat was asked nothing between the land and the ritual's view
            for (int i = land; i <= ritualView; i++) {
                assertFalse("request".equals(m.get(i).get("type").getAsString()),
                        "a request between the land and the ritual's view: " + trace);
            }
            // the frame closes with the seat's request, whose own view still shows the ritual on the stack
            final int closer = indexOf(m, ritualView, x -> "request".equals(x.get("type").getAsString()));
            assertTrue(closer > ritualView, "the seat is asked after the ritual: " + trace);
            assertTrue(onStack(m.get(closer).getAsJsonObject("view"), "Dark Ritual"));
            // the next act (the Specter, cast with the ritual's mana) comes after the ritual's view, with a view of its own
            final int specter = indexOf(m, ritualView, x -> "spell_cast".equals(eventKind(x))
                    && eventCard(x).startsWith("Hypnotic Specter"));
            assertTrue(specter > closer, "the next act follows the ritual's view: " + trace);
            final int specterView = indexOf(m, specter, x -> isAiActView(x));
            assertTrue(specterView > specter && onStack(m.get(specterView).getAsJsonObject("view"), "Hypnotic Specter"),
                    "the Specter's own view: " + trace);

            // each view is the seat's own, and none is the board of the view before it (a request's or an AI act's)
            // with only who has priority, or the step, moved
            String last = null;
            for (JsonObject x : m) {
                if (isAiActView(x)) {
                    assertEquals(x.getAsJsonObject("view").get("seat").getAsInt(), 0);
                    final String board = InteractiveGuiGame.aiActBoard(x.getAsJsonObject("view"));
                    assertFalse(board.equals(last), "a view with no new board: " + trace);
                    last = board;
                } else if ("request".equals(x.get("type").getAsString())) {
                    last = InteractiveGuiGame.aiActBoard(x.getAsJsonObject("view"));
                }
            }
        }
    }

    /** Off (the config key absent or false): no view is sent and no hook is added to the game. */
    @Test(timeOut = 900000)
    public void noAiActViewWithTheFlagOff() throws Exception {
        try (OneSeat s = new OneSeat(false)) {
            assertFalse(s.game.hasPriorityDecisionListeners(), "nothing added to the game");
            final List<JsonObject> m = s.play();
            final String trace = summary(m);
            assertTrue(indexOf(m, 0, x -> "land_played".equals(eventKind(x))) >= 0, "the same turn was played: " + trace);
            assertTrue(indexOf(m, 0, x -> "spell_cast".equals(eventKind(x))) >= 0, "the same turn was played: " + trace);
            assertEquals(indexOf(m, 0, x -> "state".equals(x.get("type").getAsString())), -1,
                    "no state with the flag off: " + trace);
        }
    }

    /**
     * No hidden information: every AI act view is the seat's own information set. The AI Player's hand is a count; its
     * hand and library cards are never named while hidden (the land's view still holds the ritual and the Specter in
     * that hand).
     */
    @Test(timeOut = 900000)
    public void aiActViewsNeverShowTheAiPlayersHandOrLibrary() throws Exception {
        try (OneSeat s = new OneSeat(true)) {
            final List<JsonObject> m = s.play();
            int views = 0;
            for (JsonObject x : m) {
                if (!isAiActView(x)) {
                    continue;
                }
                views++;
                final JsonObject view = x.getAsJsonObject("view");
                final String text = view.toString();
                for (String hidden : NEVER_SHOWN) {
                    assertFalse(text.contains(hidden), hidden + " is hidden from the seat: " + text);
                }
                final JsonObject them = playerOf(view, 1);
                assertEquals(them.getAsJsonArray("hand").size(), 0, "the AI Player's hand is a count: " + them);
                assertTrue(them.get("handSize").getAsInt() >= 1, "its hand size is public: " + them);
                // the seat's own hand is its own to see
                assertEquals(named(playerOf(view, 0).getAsJsonArray("hand"), "Serra Angel"), 1);
            }
            assertTrue(views >= 2, "views were sent: " + summary(m));
            final int land = indexOf(m, 0, x -> "land_played".equals(eventKind(x)));
            final JsonObject afterLand = m.get(indexOf(m, land, x -> isAiActView(x))).getAsJsonObject("view");
            assertFalse(afterLand.toString().contains("Hypnotic Specter"), "the Specter is still hidden in hand");
            assertFalse(afterLand.toString().contains("Dark Ritual"), "the ritual is still hidden in hand");
        }
    }

    /**
     * Dedup: a view goes out only when a game event reached the seat since its last view AND the view differs from the
     * last one it got. The seat's own decisions never get one (their view comes with their requests).
     */
    @Test(timeOut = 900000)
    public void aViewIsSentOnlyAfterAnEventThatChangedIt() throws Exception {
        try (OneSeat s = new OneSeat(true)) {
            s.gui.offerAiActView(s.ai);
            List<JsonObject> out = s.drain();
            assertEquals(out.stream().filter(InteractiveAiActViewTest::isAiActView).count(), 1L,
                    "the first view: " + summary(out));

            s.gui.offerAiActView(s.ai);
            assertEquals(s.drain(), List.of(), "no event since the last view: nothing");

            // an event that changes nothing in the view
            s.game.fireEvent(new GameEventPlayerPriority(PlayerView.get(s.ai), PhaseType.MAIN2, PlayerView.get(s.ai)));
            s.gui.offerAiActView(s.ai);
            out = s.drain();
            assertEquals(out.stream().filter(x -> "state".equals(x.get("type").getAsString())).count(), 0L,
                    "an unchanged view is not sent again: " + summary(out));

            // a change: the AI Player's life
            s.ai.setLife(15, null);
            s.gui.offerAiActView(s.seat);
            out = s.drain();
            assertEquals(out.stream().filter(x -> "state".equals(x.get("type").getAsString())).count(), 0L,
                    "the seat's own decision gets no AI act view: " + summary(out));
            s.gui.offerAiActView(s.ai);
            out = s.drain();
            final List<JsonObject> views = out.stream().filter(InteractiveAiActViewTest::isAiActView).toList();
            assertEquals(views.size(), 1, "the changed board: " + summary(out));
            assertEquals(playerOf(views.get(0).getAsJsonObject("view"), 1).get("life").getAsInt(), 15);
        }
    }

    /**
     * The filter (architect-o, 2026-11-20): a view whose only differences from the seat's last one are
     * {@code priorityPlayer} and {@code phase} is no new board for a beat and is not sent; the next real board change
     * is, carrying the step it happened in.
     */
    @Test(timeOut = 900000)
    public void aPriorityOrStepOnlyChangeSendsNothingAndTheNextBoardChangeIsSent() throws Exception {
        try (OneSeat s = new OneSeat(true)) {
            s.gui.offerAiActView(s.ai);
            List<JsonObject> out = s.drain();
            assertEquals(out.stream().filter(InteractiveAiActViewTest::isAiActView).count(), 1L, "the first view: " + summary(out));
            final JsonObject first = out.stream().filter(InteractiveAiActViewTest::isAiActView).findFirst().orElseThrow()
                    .getAsJsonObject("view");

            // the step moves (main 2 -> end step), with its event: the view's `phase` changes and nothing else
            s.game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, s.ai);
            final JsonObject stepped = InteractiveState.seatView(s.game, s.seat, 0);
            assertFalse(stepped.toString().equals(first.toString()), "the view did change: " + stepped);
            assertEquals(InteractiveGuiGame.aiActBoard(stepped), InteractiveGuiGame.aiActBoard(first), "only the step");
            s.gui.offerAiActView(s.ai);
            out = s.drain();
            assertEquals(out.stream().filter(x -> "state".equals(x.get("type").getAsString())).count(), 0L,
                    "a step-only change sends nothing: " + summary(out));

            // priority moves to the seat and back, with its events: only `priorityPlayer` differs
            s.game.getPhaseHandler().setPriority(s.seat);
            s.game.fireEvent(new GameEventPlayerPriority(PlayerView.get(s.ai), PhaseType.END_OF_TURN, PlayerView.get(s.seat)));
            s.gui.offerAiActView(s.ai);
            s.game.getPhaseHandler().setPriority(s.ai);
            s.game.fireEvent(new GameEventPlayerPriority(PlayerView.get(s.ai), PhaseType.END_OF_TURN, PlayerView.get(s.ai)));
            s.gui.offerAiActView(s.ai);
            out = s.drain();
            assertEquals(out.stream().filter(x -> "state".equals(x.get("type").getAsString())).count(), 0L,
                    "a priority-only change sends nothing: " + summary(out));

            // the next real board change is sent, in the step it happened in
            s.ai.setLife(13, null);
            s.gui.offerAiActView(s.ai);
            out = s.drain();
            final List<JsonObject> views = out.stream().filter(InteractiveAiActViewTest::isAiActView).toList();
            assertEquals(views.size(), 1, "the board change: " + summary(out));
            assertEquals(playerOf(views.get(0).getAsJsonObject("view"), 1).get("life").getAsInt(), 13);
            assertEquals(views.get(0).getAsJsonObject("view").get("phase"), stepped.get("phase"));
            assertFalse(stepped.get("phase").equals(first.get("phase")), "the step had moved: " + stepped);
        }
    }

    /**
     * Real game only: a game copy (what the pilot's look-ahead, PlanSearch, RlSearch and Forge AI simulation play on)
     * has no priority-decision listener, so playing the AI Player's turn on the copy sends the seat nothing; and a
     * copy's player handed to the hook directly is refused.
     */
    @Test(timeOut = 900000)
    public void aSimulationCopyNeverSendsAView() throws Exception {
        try (OneSeat s = new OneSeat(true)) {
            assertTrue(s.game.hasPriorityDecisionListeners(), "the real game has the hook");
            s.drain();
            // the AI Player's own copy (its hand known to it), as its search makes one
            final Game copy = new GameCopier(s.game).makeCopy(null, s.ai);
            assertFalse(copy.hasPriorityDecisionListeners(), "a copy has no listener");
            final Player copyAi = copy.getPlayers().get(1);
            final CompletableFuture<Void> done = new CompletableFuture<>();
            final Thread forge = new Thread(() -> {
                try {
                    // an act on the copy (its AI Player's land), then the copy's priority loop to its end step
                    copyAi.playLand(copyAi.getCardsIn(ZoneType.Hand).stream()
                            .filter(c -> "Swamp".equals(c.getName())).findFirst().orElseThrow(), null);
                    playUntilPhase(copy, PhaseType.END_OF_TURN);
                    done.complete(null);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            }, "Game thread (ai act view copy)");
            forge.setDaemon(true);
            forge.start();
            done.get(120, TimeUnit.SECONDS);
            assertEquals(copyAi.getCardsIn(ZoneType.Battlefield).stream()
                    .filter(c -> "Swamp".equals(c.getName())).count(), 2L, "the copy's AI Player played its land");
            assertTrue(copy.getPhaseHandler().is(PhaseType.END_OF_TURN), "the copy's priority loop ran to its end step");
            assertEquals(s.ai.getCardsIn(ZoneType.Battlefield).size(), 1, "the real game is untouched");
            List<JsonObject> out = s.drain();
            assertEquals(out.stream().filter(x -> "state".equals(x.get("type").getAsString())).count(), 0L,
                    "nothing from the copy: " + summary(out));

            // the hook ignores another game's player even with a change pending for the real seat
            s.ai.setLife(14, null);
            s.drain();
            s.gui.offerAiActView(copyAi);
            assertEquals(s.drain().stream().filter(x -> "state".equals(x.get("type").getAsString())).count(), 0L,
                    "a copy's player is refused");
            s.gui.offerAiActView(s.ai);
            out = s.drain();
            assertEquals(out.stream().filter(InteractiveAiActViewTest::isAiActView).count(), 1L,
                    "the real AI Player's view still goes out: " + summary(out));
            assertNotNull(out.get(0));
        }
    }
}
