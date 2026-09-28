package forge.interactive;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardStatsChanged;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPassPriority;
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
 * The bridge must not advertise a cast Forge will refuse (live report 2026-09-28, "can't play my
 * Brainstorm or Vampiric Tutor at end of turn": the foe had Teferi, Time Raveler).
 *
 * <p>{@code Card.getAllPossibleAbilities} runs {@code SpellAbility.canPlay}, which does not consult
 * CantBeCast / CantBeActivated statics; {@code PlaySpellAbility.playAbility} checks them only after
 * the card is on the stack and silently rolls the cast back. The bridge offered
 * {@code "cast spell: Brainstorm"} during the foe's end step, every click was accepted, and Forge
 * re-issued the same priority request. The priority controls now pass Forge's own
 * {@code checkRestrictions} first.</p>
 */
public class InteractiveCastRestrictionTest extends AITest {

    private static final String SESSION = "cast-restriction-test";

    /** The seat's board: two Islands, a Swamp, Brainstorm and Vampiric Tutor in hand. */
    private final class Harness implements AutoCloseable {
        final Game game;
        final Player seat;
        final Player foe;
        final Card brainstorm;
        final Card tutor;
        final Card sorcerer;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final AtomicBoolean closed = new AtomicBoolean();

        Harness(final PhaseType phase, final boolean seatsTurn, final BiConsumer<Game, Player> foeBoard)
                throws IOException {
            game = initAndCreateGame();
            seat = game.getPlayers().get(1);
            foe = game.getPlayers().get(0);
            addCard("Island", seat);
            addCard("Island", seat);
            addCard("Swamp", seat);
            sorcerer = addCard("Prodigal Sorcerer", seat);
            sorcerer.setSickness(false);
            brainstorm = addCardToZone("Brainstorm", seat, ZoneType.Hand);
            tutor = addCardToZone("Vampiric Tutor", seat, ZoneType.Hand);
            addCard("Plains", foe);
            foeBoard.accept(game, foe);
            game.getPhaseHandler().devModeSet(phase, seatsTurn ? seat : foe);
            game.getAction().checkStateEffects(true);

            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            final PipedReader fromClient = new PipedReader(client, 1 << 16);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(new BufferedReader(fromClient),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 1);
            gui.bind(game, seat, controller);
            controller.setGui(gui);
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui,
                    new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            gui.startReader();

            final Thread forgeThread = new Thread(() -> {
                while (!closed.get() && !gui.hasFailed()) {
                    new InputPassPriority(controller).showAndWait();
                }
            }, "test Forge game thread");
            forgeThread.setDaemon(true);
            forgeThread.start();
        }

        JsonObject priorityRequest() throws InterruptedException {
            final long deadline = System.currentTimeMillis() + 30000;
            while (true) {
                final long left = deadline - System.currentTimeMillis();
                final JsonObject message = wire.poll(Math.max(0, left), TimeUnit.MILLISECONDS);
                assertNotNull(message, "the priority input was published");
                if ("error".equals(message.get("type").getAsString())) {
                    fail("the bridge failed: " + message);
                }
                if ("request".equals(message.get("type").getAsString())) {
                    return message;
                }
            }
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

    private static boolean offers(final JsonObject request, final Card card) {
        final String controlId = "card:" + card.getId();
        return request.getAsJsonArray("controls").asList().stream()
                .anyMatch(c -> controlId.equals(c.getAsJsonObject().get("controlId").getAsString()));
    }

    /** Teferi on the foe's battlefield, with loyalty so state-based actions keep him there. */
    private Card addTeferi(final Player foe) {
        final Card teferi = addCard("Teferi, Time Raveler", foe);
        teferi.addCounterInternal(CounterEnumType.LOYALTY, 4, foe, false, null, null);
        return teferi;
    }

    /** The reported case: the foe's end step under Teferi. Neither instant is offered. */
    @Test(timeOut = 900000)
    public void teferiHidesInstantsDuringTheFoesEndStep() throws Exception {
        try (Harness harness = new Harness(PhaseType.END_OF_TURN, false,
                (game, foe) -> addTeferi(foe))) {
            final JsonObject request = harness.priorityRequest();
            assertFalse(offers(request, harness.brainstorm), "Brainstorm is not castable under Teferi: " + request);
            assertFalse(offers(request, harness.tutor), "Vampiric Tutor is not castable under Teferi: " + request);
            assertTrue(offers(request, harness.sorcerer),
                    "Teferi stops casting only; Prodigal Sorcerer's ability stays offered: " + request);
        }
    }

    /** Guard: the same board without Teferi offers both instants. */
    @Test(timeOut = 900000)
    public void withoutTeferiTheInstantsAreOfferedDuringTheFoesEndStep() throws Exception {
        try (Harness harness = new Harness(PhaseType.END_OF_TURN, false, (game, foe) -> { })) {
            final JsonObject request = harness.priorityRequest();
            assertTrue(offers(request, harness.brainstorm), "Brainstorm is offered: " + request);
            assertTrue(offers(request, harness.tutor), "Vampiric Tutor is offered: " + request);
        }
    }

    /** Guard: under Teferi the seat may still cast at sorcery speed in its own main phase. */
    @Test(timeOut = 900000)
    public void teferiStillAllowsTheSeatsOwnMainPhase() throws Exception {
        try (Harness harness = new Harness(PhaseType.MAIN1, true,
                (game, foe) -> addTeferi(foe))) {
            final JsonObject request = harness.priorityRequest();
            assertTrue(offers(request, harness.brainstorm), "Brainstorm is offered at sorcery speed: " + request);
            assertTrue(offers(request, harness.tutor), "Vampiric Tutor is offered at sorcery speed: " + request);
        }
    }

    /** The same gap for activated abilities: Linvala, Keeper of Silence (CantBeActivated). */
    @Test(timeOut = 900000)
    public void linvalaHidesCreatureAbilitiesButNotSpells() throws Exception {
        try (Harness harness = new Harness(PhaseType.END_OF_TURN, false,
                (game, foe) -> addCard("Linvala, Keeper of Silence", foe))) {
            final JsonObject request = harness.priorityRequest();
            assertFalse(offers(request, harness.sorcerer),
                    "Prodigal Sorcerer's ability cannot be activated under Linvala: " + request);
            assertTrue(offers(request, harness.brainstorm), "Brainstorm is still offered: " + request);
        }
    }

    /** Counts card-stats events; the legality query must fire none and move no id counter. */
    private static final class StatsEventCounter {
        private int count;

        @Subscribe
        public void receive(final GameEvent event) {
            if (event instanceof GameEventCardStatsChanged) {
                count++;
            }
        }
    }

    /** The legality query itself agrees with Forge's cast path and leaves the game untouched. */
    @Test(timeOut = 900000)
    public void theRestrictionQueryIsSilentAndMatchesTheCastPath() {
        final Game game = initAndCreateGame();
        final Player seat = game.getPlayers().get(1);
        final Player foe = game.getPlayers().get(0);
        addCard("Island", seat);
        final Card brainstorm = addCardToZone("Brainstorm", seat, ZoneType.Hand);
        addTeferi(foe);
        game.getPhaseHandler().devModeSet(PhaseType.END_OF_TURN, foe);
        game.getAction().checkStateEffects(true);
        final StatsEventCounter counter = new StatsEventCounter();
        game.subscribeToEvents(counter);

        final long timestamp = game.getNextTimestamp();
        final int zoneSize = seat.getZone(ZoneType.Hand).size();
        final var possible = brainstorm.getAllPossibleAbilities(seat, true);
        assertTrue(possible.stream().anyMatch(SpellAbility::isSpell),
                "precondition: canPlay alone still lists the spell (the gap): " + possible);
        for (SpellAbility ability : possible) {
            assertFalse(InteractiveGuiGame.passesCastRestrictions(seat, ability),
                    "Teferi refuses the cast: " + ability);
        }
        assertEquals(counter.count, 0, "the query fires no card-stats events");
        assertEquals(seat.getZone(ZoneType.Hand).size(), zoneSize, "the card never left the hand");
        assertEquals(game.getNextTimestamp(), timestamp + 1, "the query took no game timestamp");
    }
}
