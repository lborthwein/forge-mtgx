package forge.interactive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.ai.AiTape;
import forge.ai.AiTapeController;
import forge.ai.PlayerControllerAi;
import forge.ai.lesson.LessonScript;
import forge.ai.lesson.LessonScriptController;
import forge.ai.simulation.PlayoutKeys;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GlobalIds;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.control.FControlGameEventHandler;
import forge.player.PlayerControllerHuman;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Lane lessons-forge-jar-1027, J3: a lesson position installed in a running game ({@link InteractiveLessons}). What an
 * install resets (the stack, combat, the mana pool, the turn and phase with priority, per-turn history, the AI seat's
 * script and memory), that a taped lesson with an install replays forced exactly when the install is sent again at the
 * same point, and the wire: an install answers the browser's open priority request only, its refusals name no card,
 * and a game that is not a lesson refuses its kind as before. Plays real Forge games: run inside a broker test lease,
 * cwd = forge-gui with res/.
 */
public class LessonInstallTest extends AITest {
    private static final String SESSION = "lesson-install-test";
    private static final String HUMAN_LIBRARY = "humanlibrary=" + String.join(";", Collections.nCopies(12, "Plains"));
    private static final String AI_LIBRARY = "ailibrary=" + String.join(";", Collections.nCopies(12, "Forest"));

    /** P1: their main phase, their pump about to go on the stack; the player has a white mana floating. */
    static final String P1 = String.join("\n", "turn=4", "activeplayer=ai", "activephase=MAIN1", "humanlife=8", "ailife=7",
            "humanlandsplayed=1", "humanhand=Plains", HUMAN_LIBRARY, "humanbattlefield=Plains;Glory Seeker",
            "aihand=Monstrous Growth", AI_LIBRARY, "aibattlefield=Forest;Forest;Grizzly Bears");
    static final String P1_SCRIPT = "step=cast:Monstrous Growth|tap:Forest;Forest|target:ai/Grizzly Bears|when:MAIN1/ai\n"
            + "step=attack:Grizzly Bears|when:COMBAT_DECLARE_ATTACKERS/ai";
    /** P2: the next stage: the player's main phase three turns on, another board. */
    static final String P2 = String.join("\n", "turn=7", "activeplayer=human", "activephase=MAIN1", "humanlife=5", "ailife=6",
            "humanhand=Plains;Savannah Lions", HUMAN_LIBRARY, "humanbattlefield=Plains;Plains;Glory Seeker",
            "aihand=", AI_LIBRARY, "aibattlefield=Forest;Centaur Courser");
    static final String P2_SCRIPT = "attack=Centaur Courser";

    // ------------------------------------------------------------------------------------------------ harness

    private static int[] ids;

    private static void freshIds() {
        if (ids == null) {
            ids = GlobalIds.capture();
        }
        Game.dangerouslySetMaxId(ids[0]);
        SpellAbility.dangerouslySetMaxId(ids[1]);
        forge.game.spellability.SpellAbilityStackInstance.dangerouslySetMaxId(ids[2]);
        forge.game.trigger.Trigger.dangerouslySetMaxId(ids[3]);
        forge.game.replacement.ReplacementEffect.dangerouslySetMaxId(ids[4]);
        forge.game.staticability.StaticAbility.dangerouslySetMaxId(ids[5]);
        forge.game.combat.CombatView.dangerouslySetNextId(ids[6]);
    }

    private static JsonObject position(final String state, final String script) {
        final JsonObject p = new JsonObject();
        p.addProperty("state", state);
        p.addProperty("goal", "none");
        if (script != null) {
            p.addProperty("script", script);
        }
        return p;
    }

    private static JsonObject config(final JsonObject position) {
        final JsonObject c = JsonParser.parseString("{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":\"config\","
                + "\"session\":\"" + SESSION + "\",\"humanSeat\":0,\"seed\":7,\"aiProfile\":\"Default\"}").getAsJsonObject();
        c.add("puzzle", position);
        return c;
    }

    private static JsonObject installAction(final String state, final String script) {
        final JsonObject a = new JsonObject();
        a.addProperty("type", InteractiveLessons.KIND);
        a.add("position", position(state, script));
        return a;
    }

    /** A lesson game, J3 armed; seat 0 plays {@code playerLine} (a scripted stand-in for the browser) unless null. */
    private Object[] lesson(final String state, final String script, final String playerLine) throws Exception {
        final Game game = initAndCreateGame();
        if (playerLine != null) {
            LessonScriptController.seat(game, game.getPlayers().get(0), LessonScript.parse(playerLine));
        }
        final InteractivePuzzle.Spec spec = InteractivePuzzle.read(config(position(state, script)));
        final List<String> lines = InteractivePuzzle.lines(spec, 0);
        InteractivePuzzle.checkCards(lines);
        InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, 0), game).run();
        final LessonScriptController seat = InteractivePuzzle.seatScript(spec, game, 0);
        assertNotNull(seat);
        final InteractiveLessons lessons = InteractiveLessons.arm(spec, game, 0, seat, System.err);
        assertNotNull(lessons);
        game.getPhaseHandler().resetPriority();
        game.getPhaseHandler().onStackResolved();
        return new Object[] {game, lessons, seat};
    }

    /** Step to {@code until}; when {@code offerAt} first holds, the install is offered (taken at the player's next pass). */
    private static int play(final Game game, final InteractiveLessons lessons, final Predicate<Game> until,
                            final Predicate<Game> offerAt, final InteractiveLessons.Install install) {
        boolean offered = offerAt == null;
        int restarts = 0;
        int steps = 0;
        final String was = Thread.currentThread().getName();
        Thread.currentThread().setName("Game-lesson-test");
        try {
            while (!game.isGameOver() && !until.test(game)) {
                if (++steps > 20000) {
                    fail("stalled at turn " + game.getPhaseHandler().getTurn() + " " + game.getPhaseHandler().getPhase());
                }
                if (!offered && offerAt.test(game)) {
                    lessons.offer(install);
                    offered = true;
                }
                try {
                    game.getPhaseHandler().mainLoopStep();
                } catch (InteractiveLessons.Restart r) {
                    lessons.install(r.install);
                    restarts++;
                }
            }
        } finally {
            Thread.currentThread().setName(was);
        }
        assertTrue(until.test(game), "reached (the game ended first: " + game.isGameOver() + ")");
        return restarts;
    }

    private static Predicate<Game> at(final int turn, final PhaseType phase) {
        return g -> g.getPhaseHandler().getTurn() > turn
                || (g.getPhaseHandler().getTurn() == turn && g.getPhaseHandler().getPhase() == phase);
    }

    private static Predicate<Game> humanPriorityAt(final int turn, final PhaseType phase, final boolean stackNonEmpty) {
        return g -> g.getPhaseHandler().getTurn() == turn && g.getPhaseHandler().getPhase() == phase
                && g.getPhaseHandler().getPriorityPlayer() == g.getPlayers().get(0)
                && (!stackNonEmpty || !g.getStack().isEmpty());
    }

    private static String names(final Player p, final ZoneType zone) {
        final List<String> out = new ArrayList<>();
        for (Card c : p.getCardsIn(zone)) {
            out.add(c.getName() + (c.isTapped() ? "|T" : ""));
        }
        Collections.sort(out);
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------ what resets

    @Test
    public void anInstallResetsTheStackThePoolTheTurnAndTheScript() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(2027));
        // the player passes; the install comes while their pump is on the stack, with a white mana floating
        final Object[] l = lesson(P1 + "\nhumanmanapool=W", P1_SCRIPT, "rest=pass");
        final Game game = (Game) l[0];
        final InteractiveLessons lessons = (InteractiveLessons) l[1];
        final LessonScriptController seat = (LessonScriptController) l[2];
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);
        final InteractiveLessons.Install p2 = lessons.parse(installAction(P2, P2_SCRIPT));
        final int restarts = play(game, lessons, g -> lessons.installs() > 0, humanPriorityAt(4, PhaseType.MAIN1, true), p2);
        assertEquals(restarts, 1);
        assertTrue(game.getStack().isEmpty(), "the stack is empty");
        assertNull(game.getPhaseHandler().getCombat(), "no combat");
        assertEquals(game.getPhaseHandler().getTurn(), 7);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertEquals(game.getPhaseHandler().getPlayerTurn(), human);
        assertEquals(game.getPhaseHandler().getPriorityPlayer(), human, "priority to the position's active player");
        assertTrue(human.getManaPool().isEmpty(), "the floating mana is gone");
        assertEquals(human.getLife(), 5);
        assertEquals(ai.getLife(), 6);
        assertEquals(human.getLandsPlayedThisTurn(), 0, "the old turn's land drop is not this turn's");
        assertEquals(human.getSpellsCastThisTurn(), 0);
        assertEquals(names(human, ZoneType.Battlefield), "[Glory Seeker, Plains, Plains]");
        assertEquals(names(human, ZoneType.Hand), "[Plains, Savannah Lions]");
        assertEquals(names(ai, ZoneType.Battlefield), "[Centaur Courser, Forest]");
        assertEquals(names(ai, ZoneType.Graveyard), "[]", "the pump never resolved: it is in no zone");
        for (Player p : game.getPlayers()) {
            assertNull(named(p, ZoneType.Command, InteractivePuzzle.GOAL_CARD), "no goal card");
        }
        assertEquals(seat.script().attack(), List.of("Centaur Courser"), "the position's script, a fresh place in it");
        assertFalse(seat.handedToBot());
        // and the game goes on from the new position: their scripted attack on turn 8
        play(game, lessons, at(9, PhaseType.MAIN1), null, null);
        assertEquals(human.getLife(), 2, "the Courser attacked as the new script says");
    }

    @Test
    public void anInstallDuringCombatEndsTheCombat() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(2028));
        final Object[] l = lesson(P1, P1_SCRIPT, "rest=pass");
        final Game game = (Game) l[0];
        final InteractiveLessons lessons = (InteractiveLessons) l[1];
        final InteractiveLessons.Install p2 = lessons.parse(installAction(P2, null));
        final int restarts = play(game, lessons, g -> lessons.installs() > 0,
                humanPriorityAt(4, PhaseType.COMBAT_DECLARE_BLOCKERS, false), p2);
        assertEquals(restarts, 1);
        assertNull(game.getPhaseHandler().getCombat(), "the combat is over");
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            assertFalse(c.isTapped(), "every permanent as P2 has it: " + c);
        }
        assertEquals(((LessonScriptController) l[2]).script().attack(), null, "no script: rest=bot");
        assertEquals(((LessonScriptController) l[2]).script().rest(), LessonScript.Rest.BOT);
    }

    // ------------------------------------------------------------------------------------------------ the AI tape

    private record Taped(String log, List<String> tape) {
    }

    private Taped taped(final String mode, final List<String> record) throws Exception {
        freshIds();
        final PlayoutKeys.TrackedRandom rng = new PlayoutKeys.TrackedRandom(2029);
        MyRandom.setRandom(rng);
        final Object[] l = lesson(P1, P1_SCRIPT, "rest=pass");
        final Game game = (Game) l[0];
        final InteractiveLessons lessons = (InteractiveLessons) l[1];
        final Player ai = game.getPlayers().get(1);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        final PlayerControllerAi inner = (PlayerControllerAi) ai.getController();
        final AiTape tape = mode.equals("record") ? AiTape.record(game, ai, rng, ps) : AiTape.forced(game, ai, rng, ps, record);
        ai.dangerouslySetController(new AiTapeController(inner, tape));
        tape.install();
        tape.bindGameThread(Thread.currentThread());
        try {
            final InteractiveLessons.Install p2 = lessons.parse(installAction(P2, P2_SCRIPT + "\nbotfrom=turn/8"));
            play(game, lessons, at(9, PhaseType.MAIN1), humanPriorityAt(5, PhaseType.MAIN1, false), p2);
        } finally {
            tape.finish();
            tape.uninstall();
        }
        final List<String> lines = new ArrayList<>();
        for (String s : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (s.startsWith(AiTape.PREFIX)) {
                lines.add(s.substring(AiTape.PREFIX.length()));
            }
        }
        final List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));
        Collections.reverse(entries);
        final StringBuilder b = new StringBuilder();
        for (GameLogEntry e : entries) {
            if (e.message() != null && !e.message().startsWith("Match Duration")) {
                b.append(e.type()).append('\t').append(e.message()).append('\n');
            }
        }
        return new Taped(b.toString(), lines);
    }

    @Test
    public void aTapedLessonWithAnInstallReplaysForcedWhenTheInstallIsSentAgain() throws Exception {
        final Taped rec = taped("record", null);
        final List<String> entries = new ArrayList<>();
        for (String l : rec.tape()) {
            final String k = JsonParser.parseString(l).getAsJsonObject().get("k").getAsString();
            if (!List.of("boot", "end", "divergence", "live").contains(k)) {
                entries.add(l);
            }
        }
        assertFalse(entries.isEmpty());
        final Taped forced = taped("forced", entries);
        for (String l : forced.tape()) {
            assertFalse(l.contains("\"divergence\""), "no divergence: " + l);
        }
        assertEquals(forced.log(), rec.log(), "the forced replay with the same install is the recorded game");
    }

    // ------------------------------------------------------------------------------------------------ the wire

    /** A one-seat lesson game on a real {@link InteractiveGuiGame} wire, its game thread running. */
    private static final class Wire implements AutoCloseable {
        final Game game;
        final PlayerControllerHuman controller;
        final InteractiveGuiGame gui;
        final BlockingQueue<JsonObject> wire = new LinkedBlockingQueue<>();
        final PipedWriter client = new PipedWriter();
        final Thread thread;
        final InteractiveLessons lessons;
        volatile Throwable failure;
        private int inputs;

        Wire(final LessonInstallTest t, final String state, final String script, final boolean armed) throws Exception {
            game = t.newGame();
            final Player seat = game.getPlayers().get(0);
            controller = new PlayerControllerHuman(game, seat, seat.getLobbyPlayer());
            seat.dangerouslySetController(controller);
            gui = new InteractiveGuiGame(new InteractiveProtocol.Channel(
                    new BufferedReader(new PipedReader(client, 1 << 16)),
                    new PrintStream(new LineSink(wire), true, StandardCharsets.UTF_8), SESSION), 0);
            gui.bind(game, seat, controller);
            controller.setGui(gui);
            gui.setGameView(null);
            gui.setGameView(game.getView());
            gui.setOriginalGameController(seat.getView(), controller);
            gui.openView(new forge.trackable.TrackableCollection<>(seat.getView()));
            for (Player p : game.getPlayers()) {
                p.updateOpponentsForView();
            }
            game.subscribeToEvents(InteractiveGuiGame.uiEventsExceptEchoes(gui, new FControlGameEventHandler(controller)));
            game.subscribeToEvents(gui);
            final InteractivePuzzle.Spec spec = InteractivePuzzle.read(config(position(state, script)));
            final List<String> lines = InteractivePuzzle.lines(spec, 0);
            InteractivePuzzle.checkCards(lines);
            final Runnable hook = InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, 0), game);
            final LessonScriptController aiSeat = InteractivePuzzle.seatScript(spec, game, 0);
            lessons = armed ? InteractiveLessons.arm(spec, game, 0, aiSeat, System.err) : null;
            if (lessons != null) {
                gui.setLessons(lessons);
            }
            gui.startReader();
            thread = new Thread(() -> {
                try {
                    hook.run();
                    game.getPhaseHandler().resetPriority();
                    game.getPhaseHandler().onStackResolved();
                    if (lessons != null) {
                        lessons.loop(null);
                    } else {
                        game.getPhaseHandler().mainGameLoop();
                    }
                } catch (InteractiveGuiGame.InteractiveAbort ignored) {
                    // closed
                } catch (Throwable e) {
                    failure = e;
                }
            }, "Game-lesson-wire-test");
            thread.setDaemon(true);
            thread.start();
        }

        JsonObject next(final String type) throws Exception {
            final long deadline = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < deadline) {
                final JsonObject m = wire.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && type.equals(m.get("type").getAsString())) {
                    return m;
                }
                if (failure != null) {
                    throw new AssertionError("the game failed", failure);
                }
            }
            for (var e : Thread.getAllStackTraces().entrySet()) {
                if (e.getKey().getName().startsWith("Game")) {
                    System.err.println("[wire-dump] " + e.getKey().getName() + " " + e.getKey().getState());
                    for (StackTraceElement f : e.getValue()) {
                        System.err.println("[wire-dump]   " + f);
                    }
                }
            }
            throw new AssertionError("no " + type + " in 60 s");
        }

        /** The next request of {@code kind}, passing priority until it comes. */
        JsonObject request(final String kind) throws Exception {
            for (int i = 0; i < 40; i++) {
                final JsonObject r = next("request");
                if (kind.equals(r.get("kind").getAsString())) {
                    return r;
                }
                send(r, r.get("kind").getAsString(), pass(r));
                next("ack");
            }
            throw new AssertionError("no " + kind + " request");
        }

        private static JsonObject pass(final JsonObject request) {
            for (var c : request.getAsJsonArray("controls")) {
                final JsonObject control = c.getAsJsonObject();
                final String type = control.get("type").getAsString();
                if (type.equals("passPriority") || type.equals("ok")) {
                    final JsonObject a = new JsonObject();
                    a.addProperty("type", type);
                    a.addProperty("controlId", control.get("controlId").getAsString());
                    return a;
                }
            }
            throw new AssertionError("no pass control in " + request);
        }

        void send(final JsonObject request, final String kind, final JsonObject action) throws Exception {
            final JsonObject in = new JsonObject();
            in.addProperty("protocol", "mtgx-forge-interactive/1");
            in.addProperty("session", SESSION);
            in.addProperty("type", "input");
            in.addProperty("requestId", request.get("requestId").getAsString());
            in.addProperty("inputId", "in-" + (++inputs));
            in.addProperty("kind", kind);
            in.add("action", action);
            client.write(in + "\n");
            client.flush();
        }

        @Override
        public void close() {
            controller.getInputQueue().onGameOver(true);
            gui.close();
        }
    }

    /** As initAndCreateGame (for the wire's constructor, outside the test class). */
    Game newGame() {
        return initAndCreateGame();
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

    @Test(timeOut = 600000)
    public void overTheWireAnInstallAnswersTheOpenPriorityRequestOnly() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(2030));
        final String start = P2.replace("turn=7", "turn=3");
        try (Wire w = new Wire(this, start, "rest=pass", true)) {
            final JsonObject first = w.request("priority");
            assertEquals(first.getAsJsonObject("view").get("turn").getAsInt(), 3);
            // a position that is not a lesson position: refused, no card named, nothing changes
            w.send(first, InteractiveLessons.KIND, installAction(P1.replace("Grizzly Bears", "Grizzly Baers"), null));
            final JsonObject bad = w.next("ack");
            assertFalse(bad.get("accepted").getAsBoolean());
            assertTrue(bad.get("reason").getAsString().startsWith("not a lesson position"), bad.toString());
            assertFalse(bad.toString().contains("Baers"), "the refusal names no card: " + bad);
            // a lesson position: accepted, and the next priority request is the new position's
            w.send(first, InteractiveLessons.KIND, installAction(P1, P1_SCRIPT));
            final JsonObject ok = w.next("ack");
            assertTrue(ok.get("accepted").getAsBoolean(), ok.toString());
            final JsonObject after = w.next("request");
            final JsonObject view = after.getAsJsonObject("view");
            assertEquals(view.get("turn").getAsInt(), 4, after.toString());
            assertEquals(w.lessons.installs(), 1);
            // a request that is not priority (the player's blocks, after their scripted attack): refused
            // (`after` is open: the scripted pump on the stack; it is answered with a pass before the next request comes)
            final JsonObject combat;
            if (after.get("kind").getAsString().equals("combat")) {
                combat = after;
            } else {
                w.send(after, after.get("kind").getAsString(), Wire.pass(after));
                w.next("ack");
                combat = w.request("combat");
            }
            w.send(combat, InteractiveLessons.KIND, installAction(P2, null));
            final JsonObject refused = w.next("ack");
            assertFalse(refused.get("accepted").getAsBoolean());
            assertEquals(refused.get("reason").getAsString(), "an install answers the browser's open priority request");
            assertEquals(w.lessons.installs(), 1);
        }
    }

    @Test(timeOut = 600000)
    public void aGameThatIsNotALessonRefusesAnInstallAsBefore() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(2031));
        try (Wire w = new Wire(this, P2.replace("turn=7", "turn=3"), "rest=pass", false)) {
            final JsonObject first = w.request("priority");
            w.send(first, InteractiveLessons.KIND, installAction(P1, null));
            final JsonObject ack = w.next("ack");
            assertFalse(ack.get("accepted").getAsBoolean());
            assertEquals(ack.get("reason").getAsString(), "kind does not match active request");
        }
    }

    private static Card named(final Player p, final ZoneType zone, final String name) {
        for (Card c : p.getCardsIn(zone)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }
}
