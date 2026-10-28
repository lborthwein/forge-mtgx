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
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

/**
 * Lane lessons-forge-jar-1027: J1 (a lesson position, goal 'none') and J2 (the scripted AI seat). Each stage here is a
 * synthetic Chapter 1 stage covering one feature of design section 3 (the real Chapter 1 lines come from
 * ch1-script-1028): a land and a cast paid with named sources, a creature named as a payment source, the opponent's
 * forced pre-combat pump, named attacks and blocks, a pass otherwise, botfrom and rest=bot handing the seat to the
 * wrapped Forge AI, and drift (the player removes the scripted attacker, taps or destroys a named source): the seat goes
 * on as the wrapped Forge AI and the game never stalls. The browser seat is played by a second scripted controller (the
 * player's line). Plays real Forge games: run inside a broker test lease, cwd = forge-gui with res/.
 */
public class LessonScriptTest extends AITest {

    // ------------------------------------------------------------------------------------------------ harness

    private static int[] ids;

    /** Every game starts with the process-wide ids where the first found them (a fresh JVM's start). */
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

    private static String state(final String... lines) {
        return String.join("\n", lines);
    }

    private static final String HUMAN_LIBRARY = "humanlibrary=" + String.join(";", Collections.nCopies(12, "Plains"));
    private static final String AI_LIBRARY = "ailibrary=" + String.join(";", Collections.nCopies(12, "Forest"));

    /** A lesson game: the position installed as a goal-'none' puzzle, the AI seat scripted, the player's line scripted. */
    private Game lesson(final String position, final String aiScript, final String playerLine) throws Exception {
        final Game game = initAndCreateGame();
        final InteractivePuzzle.Spec spec = InteractivePuzzle.read(config(position, aiScript));
        final List<String> lines = InteractivePuzzle.lines(spec, 0);
        InteractivePuzzle.checkCards(lines);
        InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, 0), game).run();
        assertNotNull(InteractivePuzzle.seatScript(spec, game, 0));
        if (playerLine != null) {
            LessonScriptController.seat(game, game.getPlayers().get(0), LessonScript.parse(playerLine));
        }
        game.getPhaseHandler().resetPriority();
        game.getPhaseHandler().onStackResolved();
        return game;
    }

    private static JsonObject config(final String position, final String script) {
        final JsonObject p = new JsonObject();
        p.addProperty("state", position);
        p.addProperty("goal", "none");
        if (script != null) {
            p.addProperty("script", script);
        }
        final JsonObject c = JsonParser.parseString("{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":\"config\","
                + "\"session\":\"s\",\"humanSeat\":0,\"seed\":7,\"aiProfile\":\"Default\"}").getAsJsonObject();
        c.add("puzzle", p);
        return c;
    }

    private static Player human(final Game g) {
        return g.getPlayers().get(0);
    }

    private static Player ai(final Game g) {
        return g.getPlayers().get(1);
    }

    private static LessonScriptController seat(final Player p) {
        Object c = p.getController();
        if (c instanceof AiTapeController t) {
            c = t.inner();
        }
        return (LessonScriptController) c;
    }

    /** Step the game until {@code until} holds; a game that does not get there within the bound has stalled. */
    private static void playUntil(final Game game, final Predicate<Game> until, final String what) {
        int steps = 0;
        while (!game.isGameOver() && !until.test(game)) {
            if (++steps > 20000) {
                fail("stalled before " + what + " (turn " + game.getPhaseHandler().getTurn() + " "
                        + game.getPhaseHandler().getPhase() + ")");
            }
            game.getPhaseHandler().mainLoopStep();
        }
        assertTrue(until.test(game), what + " (the game ended first: " + game.isGameOver() + ")");
    }

    private static Predicate<Game> at(final int turn, final PhaseType phase) {
        return g -> g.getPhaseHandler().getTurn() > turn
                || (g.getPhaseHandler().getTurn() == turn && g.getPhaseHandler().getPhase() == phase);
    }

    /** One seat's board, as the next stage's position would name it: battlefield (with |T), hand, graveyard, life. */
    private static String board(final Player p) {
        final List<String> bf = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            bf.add(c.getName() + (c.isTapped() ? "|T" : ""));
        }
        Collections.sort(bf);
        final List<String> hand = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Hand)) {
            hand.add(c.getName());
        }
        Collections.sort(hand);
        final List<String> gy = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
            gy.add(c.getName());
        }
        Collections.sort(gy);
        return "life=" + p.getLife() + " bf=" + bf + " hand=" + hand + " gy=" + gy;
    }

    private static Card named(final Player p, final ZoneType zone, final String name) {
        for (Card c : p.getCardsIn(zone)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ J1

    @Test
    public void aLessonPositionHasNoGoalCardAndNoTurnLimit() throws Exception {
        final String position = state("turn=1", "activeplayer=human", "activephase=MAIN1", "humanlife=8", "ailife=7",
                "humanhand=", "humanlibrary=" + String.join(";", Collections.nCopies(40, "Plains")), "humanbattlefield=Plains",
                "aihand=", "ailibrary=" + String.join(";", Collections.nCopies(40, "Forest")), "aibattlefield=Forest");
        final Game game = lesson(position, "rest=pass", "rest=pass");
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Command)) {
                assertFalse(InteractivePuzzle.GOAL_CARD.equals(c.getName()), "no goal card");
            }
        }
        // far past any goal's 20-turn limit: nobody has lost (no turn-limit trigger)
        playUntil(game, at(26, PhaseType.MAIN1), "turn 26");
        assertFalse(game.isGameOver());
        assertEquals(seat(ai(game)).actsTaken(), 0, "an empty script only passes");
    }

    @Test
    public void goalNoneTakesNoTurnsAndAScriptIsChecked() {
        final String position = state("turn=1", "activeplayer=human", "activephase=MAIN1", "humanbattlefield=Plains",
                "aibattlefield=Forest");
        final JsonObject withTurns = config(position, null);
        withTurns.getAsJsonObject("puzzle").addProperty("turns", 5);
        assertRefused(withTurns, "invalid_puzzle: a puzzle with goal 'none' has no turns");
        assertRefused(config(position, "step=cast:Grizzly Bears|tap:Forest"), "invalid_puzzle: puzzle.script is malformed");
        assertRefused(config(position, "rest=sometimes"), "invalid_puzzle: puzzle.script is malformed");
        assertRefused(config(position, "step=attack:Grizzly Bears|when:MAIN1/bob"), "invalid_puzzle: puzzle.script is malformed");
        assertRefused(config(position, "[script]\nrest=pass"), "invalid_puzzle: puzzle.script is malformed");
        final JsonObject notText = config(position, null);
        notText.getAsJsonObject("puzzle").addProperty("script", 3);
        assertRefused(notText, "invalid_puzzle: puzzle.script must be the [script] section's text");
        final JsonObject other = config(position, null);
        other.getAsJsonObject("puzzle").addProperty("goal", "teach");
        assertRefused(other, "invalid_puzzle: puzzle.goal must be 'win', 'survive' or 'none'");
    }

    @Test
    public void aScriptNamingAnUnknownCardOrPhaseIsRefusedAtTheStart() throws Exception {
        initAndCreateGame();
        for (String bad : List.of("plays=Forrest", "step=pass|when:LUNCH/ai")) {
            try {
                InteractivePuzzle.checkedScript(bad);
                fail("accepted " + bad);
            } catch (InteractiveProtocol.ProtocolException e) {
                assertEquals(e.code(), InteractivePuzzle.REFUSED);
                assertFalse(e.getMessage().contains("Forrest"), "the name stays off the wire: " + e.getMessage());
            }
        }
        assertEquals(InteractivePuzzle.checkedScript("plays=Forest\nstep=pass|when:MAIN2/ai").steps().size(), 1);
    }

    @Test
    public void aWinPuzzleIsUnchanged() throws Exception {
        final JsonObject c = config(state("turn=7", "activeplayer=human", "activephase=MAIN1", "humanbattlefield=Plains",
                "aibattlefield=Forest", "ailife=5"), null);
        c.getAsJsonObject("puzzle").addProperty("goal", "win");
        c.getAsJsonObject("puzzle").addProperty("turns", 7);
        final InteractivePuzzle.Spec spec = InteractivePuzzle.read(c);
        assertTrue(spec.hasGoal());
        assertNull(spec.script());
        final Game game = initAndCreateGame();
        final List<String> lines = InteractivePuzzle.lines(spec, 0);
        InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, 0), game).run();
        assertNotNull(named(human(game), ZoneType.Command, InteractivePuzzle.GOAL_CARD), "a win puzzle keeps its goal card");
        assertNull(InteractivePuzzle.seatScript(spec, game, 0), "no script: the AI seat is untouched");
        assertFalse(ai(game).getController() instanceof LessonScriptController);
    }

    private static void assertRefused(final JsonObject config, final String want) {
        try {
            InteractivePuzzle.read(config);
            fail("accepted: " + config);
        } catch (InteractiveProtocol.ProtocolException e) {
            assertEquals(e.code() + ": " + e.getMessage(), want);
        }
    }

    // ------------------------------------------------------------------------------------------------ J2: the stages

    /** Stage A (e3's mechanism): its land, then a creature paid with two named Forests; a pass otherwise. */
    @Test
    public void stageALandThenACastPaidWithNamedLandsAndAPassOtherwise() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1027));
        final Game game = lesson(state("turn=2", "activeplayer=ai", "activephase=UPKEEP", "humanlife=8", "ailife=7",
                        "humanhand=", HUMAN_LIBRARY, "humanbattlefield=Plains;Glory Seeker",
                        "aihand=Forest;Grizzly Bears;Llanowar Elves", AI_LIBRARY, "aibattlefield=Forest;Forest"),
                "plays=Forest\nstep=cast:Grizzly Bears|tap:Forest;Forest|when:MAIN1/ai", "rest=pass");
        playUntil(game, at(3, PhaseType.MAIN1), "the player's next main phase");
        // two of the three Forests paid; the third stays up, and the castable Elves stay in hand (rest=pass)
        assertEquals(board(ai(game)), "life=7 bf=[Forest, Forest|T, Forest|T, Grizzly Bears] hand=[Forest, Llanowar Elves] gy=[]");
        assertEquals(board(human(game)), "life=8 bf=[Glory Seeker, Plains] hand=[Plains] gy=[]");
        assertEquals(seat(ai(game)).actsTaken(), 2);
        assertFalse(seat(ai(game)).handedToBot());
    }

    /** Stage B (e5b's): a named block, then its land and a creature paid with two Forests and the Elves. */
    @Test
    public void stageBANamedBlockThenACastPaidWithACreatureToo() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1028));
        final Game game = lesson(state("turn=3", "activeplayer=human", "activephase=MAIN1", "humanlife=8", "ailife=7",
                        "humanhand=", HUMAN_LIBRARY, "humanbattlefield=Plains;Plains;Glory Seeker;Savannah Lions",
                        "aihand=Forest;Centaur Courser", AI_LIBRARY, "aibattlefield=Forest;Forest;Llanowar Elves;Grizzly Bears"),
                "plays=Forest\nblock=Llanowar Elves>Glory Seeker;Grizzly Bears>Savannah Lions\n"
                        + "step=cast:Centaur Courser|tap:Forest;Forest;Llanowar Elves|when:MAIN1/ai",
                // the player's line: attack with the Lions only
                "step=attack:Savannah Lions|when:COMBAT_DECLARE_ATTACKERS/ai");
        playUntil(game, at(5, PhaseType.MAIN1), "the player's turn after theirs");
        // the Elves' pair is skipped (the Glory Seeker did not attack); the Bears traded with the Lions; then the Courser
        // was paid with the Elves and two Forests, leaving the new Forest up
        assertEquals(board(ai(game)), "life=7 bf=[Centaur Courser, Forest, Forest|T, Forest|T, Llanowar Elves|T] "
                + "hand=[Forest] gy=[Grizzly Bears]");
        assertEquals(board(human(game)), "life=8 bf=[Glory Seeker, Plains, Plains] hand=[Plains] gy=[Savannah Lions]");
        assertEquals(seat(ai(game)).drifts(), 0);
    }

    /** Stage C (the new Chapter 1's pump): a sorcery pump on its own creature before combat, then the attack. */
    @Test
    public void stageCTheOpponentsForcedPreCombatPump() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1029));
        final Game game = lesson(state("turn=6", "activeplayer=ai", "activephase=MAIN1", "humanlife=8", "ailife=7",
                        "humanhand=", HUMAN_LIBRARY, "humanbattlefield=Plains;Glory Seeker",
                        "aihand=Monstrous Growth", AI_LIBRARY, "aibattlefield=Forest;Forest;Grizzly Bears"),
                "step=cast:Monstrous Growth|tap:Forest;Forest|target:ai/Grizzly Bears|when:MAIN1/ai\n"
                        + "step=attack:Grizzly Bears|when:COMBAT_DECLARE_ATTACKERS/ai",
                // the player's line: the chump block
                "block=Glory Seeker>Grizzly Bears");
        playUntil(game, at(6, PhaseType.COMBAT_BEGIN), "their beginning of combat");
        final Card bears = named(ai(game), ZoneType.Battlefield, "Grizzly Bears");
        assertEquals(bears.getNetPower(), 6, "pumped before combat");
        assertEquals(bears.getNetToughness(), 6);
        assertNotNull(named(ai(game), ZoneType.Graveyard, "Monstrous Growth"));
        playUntil(game, at(7, PhaseType.MAIN1), "the player's next main phase");
        assertEquals(board(ai(game)), "life=7 bf=[Forest|T, Forest|T, Grizzly Bears|T] hand=[] gy=[Monstrous Growth]");
        assertEquals(board(human(game)), "life=8 bf=[Plains] hand=[Plains] gy=[Glory Seeker]");
        assertEquals(named(ai(game), ZoneType.Battlefield, "Grizzly Bears").getNetPower(), 2, "the pump wore off");
    }

    /** Stage D (e5's): it attacks with exactly the named creatures; another able attacker stays home. */
    @Test
    public void stageDItAttacksWithExactlyTheNamedCreatures() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1030));
        final Game game = lesson(state("turn=4", "activeplayer=ai", "activephase=MAIN1", "humanlife=8", "ailife=7",
                        "humanhand=", HUMAN_LIBRARY, "humanbattlefield=Plains",
                        "aihand=", AI_LIBRARY, "aibattlefield=Forest;Grizzly Bears;Llanowar Elves;Centaur Courser"),
                "attack=Grizzly Bears;Llanowar Elves", "rest=pass");
        playUntil(game, at(5, PhaseType.MAIN1), "the player's next main phase");
        assertEquals(board(human(game)), "life=5 bf=[Plains] hand=[Plains] gy=[]");
        assertEquals(board(ai(game)), "life=7 bf=[Centaur Courser, Forest, Grizzly Bears|T, Llanowar Elves|T] hand=[] gy=[]");
    }

    /** A named attacker that is there but tapped stays home (as the client's partner); the rest attack; no drift. */
    @Test
    public void aTappedNamedAttackerStaysHomeAndTheRestAttack() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1040));
        final Game game = lesson(state("turn=4", "activeplayer=ai", "activephase=MAIN1", "humanlife=8", "ailife=7",
                        "humanhand=", HUMAN_LIBRARY, "humanbattlefield=Plains",
                        "aihand=", AI_LIBRARY, "aibattlefield=Forest;Grizzly Bears|Tapped;Llanowar Elves"),
                "attack=Grizzly Bears;Llanowar Elves", "rest=pass");
        playUntil(game, at(5, PhaseType.MAIN1), "the player's next main phase");
        assertEquals(board(human(game)), "life=7 bf=[Plains] hand=[Plains] gy=[]");
        assertEquals(seat(ai(game)).drifts(), 0);
        assertFalse(seat(ai(game)).handedToBot());
    }

    /** Stage E (e6's block pairs): the first pair whose attacker attacks takes the blocker; a used blocker's pair is skipped. */
    @Test
    public void stageEBlockPairsInOrder() throws Exception {
        final String position = state("turn=5", "activeplayer=human", "activephase=MAIN1", "humanlife=8", "ailife=7",
                "humanhand=", HUMAN_LIBRARY, "humanbattlefield=Plains;Glory Seeker;Savannah Lions",
                "aihand=", AI_LIBRARY, "aibattlefield=Forest;Centaur Courser");
        final String blocks = "block=Centaur Courser>Glory Seeker;Centaur Courser>Savannah Lions";
        freshIds();
        MyRandom.setRandom(new Random(1031));
        final Game both = lesson(position, blocks, "attack=Glory Seeker;Savannah Lions");
        playUntil(both, at(5, PhaseType.MAIN2), "the player's second main phase");
        assertEquals(board(ai(both)), "life=5 bf=[Centaur Courser, Forest] hand=[] gy=[]");
        assertEquals(board(human(both)), "life=8 bf=[Plains, Savannah Lions|T] hand=[] gy=[Glory Seeker]");
        freshIds();
        MyRandom.setRandom(new Random(1031));
        final Game lions = lesson(position, blocks, "attack=Savannah Lions");
        playUntil(lions, at(5, PhaseType.MAIN2), "the player's second main phase");
        assertEquals(board(ai(lions)), "life=7 bf=[Centaur Courser, Forest] hand=[] gy=[]");
        assertEquals(board(human(lions)), "life=8 bf=[Glory Seeker, Plains] hand=[] gy=[Savannah Lions]");
        assertEquals(seat(ai(lions)).drifts(), 0);
    }

    /** botfrom: from its turn on, the seat is the wrapped Forge AI, in the same game (1.7's hand-over). */
    @Test
    public void botFromHandsTheSeatToTheWrappedForgeAi() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1032));
        final Game game = lesson(state("turn=7", "activeplayer=human", "activephase=MAIN1", "humanlife=8", "ailife=7",
                        "humanhand=", HUMAN_LIBRARY, "humanbattlefield=Plains",
                        "aihand=Grizzly Bears", AI_LIBRARY, "aibattlefield=Forest;Forest"),
                "botfrom=turn/8", "rest=pass");
        playUntil(game, at(7, PhaseType.END_OF_TURN), "the player's end step");
        assertFalse(seat(ai(game)).handedToBot());
        assertNull(named(ai(game), ZoneType.Battlefield, "Grizzly Bears"), "nothing cast on the player's turn");
        playUntil(game, at(9, PhaseType.MAIN1), "the player's next main phase");
        assertTrue(seat(ai(game)).handedToBot());
        assertNotNull(named(ai(game), ZoneType.Battlefield, "Grizzly Bears"), "the wrapped Forge AI cast it");
    }

    /** rest=bot with nothing named is the wrapped Forge AI, decision for decision: the same game as a plain Forge AI seat. */
    @Test
    public void restBotIsTheWrappedForgeAi() throws Exception {
        final String position = state("turn=3", "activeplayer=ai", "activephase=UPKEEP", "humanlife=12", "ailife=12",
                "humanhand=Savannah Lions;Glory Seeker", HUMAN_LIBRARY, "humanbattlefield=Plains;Plains;Glory Seeker",
                "aihand=Grizzly Bears;Centaur Courser;Monstrous Growth;Forest", AI_LIBRARY,
                "aibattlefield=Forest;Forest;Llanowar Elves");
        final List<String> logs = new ArrayList<>();
        for (int run = 0; run < 2; run++) {
            freshIds();
            MyRandom.setRandom(new Random(1033));
            final Game game = initAndCreateGame();
            final InteractivePuzzle.Spec spec = InteractivePuzzle.read(config(position, run == 0 ? null : "rest=bot"));
            final List<String> lines = InteractivePuzzle.lines(spec, 0);
            InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, 0), game).run();
            InteractivePuzzle.seatScript(spec, game, 0);
            game.getPhaseHandler().resetPriority();
            game.getPhaseHandler().onStackResolved();
            assertEquals(ai(game).getController() instanceof LessonScriptController, run == 1);
            playUntil(game, at(8, PhaseType.MAIN1), "turn 8");
            logs.add(log(game));
        }
        assertTrue(logs.get(0).contains("Grizzly Bears") || logs.get(0).contains("Centaur Courser"), logs.get(0));
        assertEquals(logs.get(1), logs.get(0), "rest=bot plays the plain Forge AI's game");
    }

    // ------------------------------------------------------------------------------------------------ J2: drift

    /** The player kills the scripted attacker: the seat goes on as the wrapped Forge AI; the game never stalls. */
    @Test
    public void driftThePlayerRemovesTheScriptedAttacker() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1034));
        final Game game = lesson(state("turn=9", "activeplayer=human", "activephase=MAIN1", "humanlife=8", "ailife=7",
                        "humanhand=Lightning Bolt", HUMAN_LIBRARY, "humanbattlefield=Mountain;Plains",
                        "aihand=", AI_LIBRARY, "aibattlefield=Forest;Grizzly Bears;Centaur Courser"),
                "step=attack:Grizzly Bears|when:COMBAT_DECLARE_ATTACKERS/ai",
                // the player's other line: Bolt their attacker first ("human" is the other seat here)
                "step=cast:Lightning Bolt|tap:Mountain|target:human/Grizzly Bears|when:MAIN1/ai");
        playUntil(game, at(11, PhaseType.MAIN1), "the player's turn after theirs");
        assertNotNull(named(ai(game), ZoneType.Graveyard, "Grizzly Bears"));
        assertEquals(seat(ai(game)).drifts(), 1, "the attack step drifted");
        assertTrue(seat(ai(game)).handedToBot(), "and the wrapped Forge AI took the seat");
    }

    /** The player taps the creature named as a payment source: no forced cast; the wrapped Forge AI plays on. */
    @Test
    public void driftThePlayerTapsANamedSource() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1035));
        final Game game = lesson(state("turn=10", "activeplayer=ai", "activephase=UPKEEP", "humanlife=8", "ailife=7",
                        "humanhand=Pressure Point", HUMAN_LIBRARY, "humanbattlefield=Plains;Plains",
                        "aihand=Centaur Courser", AI_LIBRARY, "aibattlefield=Forest;Forest;Llanowar Elves"),
                "step=cast:Centaur Courser|tap:Forest;Forest;Llanowar Elves|when:MAIN1/ai",
                // the player's other line: tap their Elves in their upkeep
                "step=cast:Pressure Point|tap:Plains;Plains|target:human/Llanowar Elves|when:UPKEEP/human");
        playUntil(game, at(11, PhaseType.MAIN1), "the player's next main phase");
        assertNotNull(named(human(game), ZoneType.Graveyard, "Pressure Point"));
        assertEquals(seat(ai(game)).drifts(), 1, "the cast drifted (its named Elves were tapped)");
        assertTrue(seat(ai(game)).handedToBot());
    }

    /** The player destroys a named land: no forced cast; the game goes on. */
    @Test
    public void driftThePlayerDestroysANamedSource() throws Exception {
        freshIds();
        MyRandom.setRandom(new Random(1036));
        final Game game = lesson(state("turn=11", "activeplayer=human", "activephase=MAIN1", "humanlife=8", "ailife=7",
                        "humanhand=Stone Rain", HUMAN_LIBRARY, "humanbattlefield=Mountain;Mountain;Mountain",
                        "aihand=Grizzly Bears", AI_LIBRARY, "aibattlefield=Forest;Forest"),
                "step=cast:Grizzly Bears|tap:Forest;Forest|when:MAIN1/ai",
                "step=cast:Stone Rain|tap:Mountain;Mountain;Mountain|target:human/Forest|when:MAIN1/ai");
        playUntil(game, at(13, PhaseType.MAIN1), "the player's turn after theirs");
        assertEquals(seat(ai(game)).drifts(), 1);
        assertTrue(seat(ai(game)).handedToBot());
        assertFalse(game.isGameOver());
    }

    // ------------------------------------------------------------------------------------------------ J2: the AI tape

    /** One taped run: the game log, the tape lines and the script's lines. */
    private record Taped(String log, List<String> tape, List<String> script, int acts) {
    }

    private Taped taped(final String position, final String aiScript, final String playerLine, final String mode,
                        final List<String> record, final int untilTurn) throws Exception {
        freshIds();
        final PlayoutKeys.TrackedRandom rng = new PlayoutKeys.TrackedRandom(1037);
        MyRandom.setRandom(rng);
        final Game game = lesson(position, aiScript, playerLine);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintStream ps = new PrintStream(out, true, StandardCharsets.UTF_8);
        final Player ai = ai(game);
        final PlayerControllerAi inner = (PlayerControllerAi) ai.getController();
        final AiTape tape = mode.equals("record") ? AiTape.record(game, ai, rng, ps)
                : mode.equals("resume") ? AiTape.resume(game, ai, rng, ps, record)
                : AiTape.forced(game, ai, rng, ps, record);
        ai.dangerouslySetController(new AiTapeController(inner, tape));
        tape.install();
        tape.bindGameThread(Thread.currentThread());
        try {
            playUntil(game, at(untilTurn, PhaseType.MAIN1), "turn " + untilTurn);
        } finally {
            tape.finish();
            tape.uninstall();
        }
        final List<String> lines = new ArrayList<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (l.startsWith(AiTape.PREFIX)) {
                lines.add(l.substring(AiTape.PREFIX.length()));
            }
        }
        return new Taped(log(game), lines, List.of(), seat(ai).actsTaken());
    }

    private static String log(final Game game) {
        final List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));
        Collections.reverse(entries);
        final StringBuilder b = new StringBuilder();
        for (GameLogEntry e : entries) {
            if (e.message() != null && !e.message().startsWith("Match Duration")) {
                b.append(e.type()).append('\t').append(e.message()).append('\n');
            }
        }
        return b.toString();
    }

    private static List<String> entries(final List<String> tape) {
        final List<String> out = new ArrayList<>();
        for (String l : tape) {
            final String k = JsonParser.parseString(l).getAsJsonObject().get("k").getAsString();
            if (!List.of("boot", "end", "divergence", "live").contains(k)) {
                out.add(l);
            }
        }
        return out;
    }

    private static JsonObject status(final List<String> tape, final String k) {
        for (String l : tape) {
            final JsonObject o = JsonParser.parseString(l).getAsJsonObject();
            if (o.get("k").getAsString().equals(k)) {
                return o;
            }
        }
        return null;
    }

    @Test
    public void aRecordedLessonReplaysForcedAndResumesWhereTheScriptWas() throws Exception {
        final String position = state("turn=2", "activeplayer=ai", "activephase=UPKEEP", "humanlife=20", "ailife=20",
                "humanhand=", HUMAN_LIBRARY, "humanbattlefield=Plains;Glory Seeker;Savannah Lions",
                "aihand=Forest;Grizzly Bears;Monstrous Growth;Forest", AI_LIBRARY, "aibattlefield=Forest;Forest;Llanowar Elves");
        final String script = "plays=Forest;Forest\n"
                + "step=cast:Grizzly Bears|tap:Forest;Llanowar Elves|when:MAIN1/ai\n"
                + "block=Grizzly Bears>Savannah Lions\n"
                + "step=cast:Monstrous Growth|tap:Forest;Forest|target:ai/Grizzly Bears|when:turn/4\n"
                + "step=attack:Grizzly Bears|when:turn/4\n"
                + "botfrom=turn/6";
        final String player = "step=attack:Glory Seeker|when:COMBAT_DECLARE_ATTACKERS/ai";
        final Taped rec = taped(position, script, player, "record", null, 8);
        final List<String> entries = entries(rec.tape());
        assertFalse(entries.isEmpty());
        // forced: the same game, every entry used, no divergence
        final Taped forced = taped(position, script, player, "forced", entries, 8);
        assertNull(status(forced.tape(), "divergence"), "no divergence: " + forced.tape());
        final JsonObject end = status(forced.tape(), "end");
        assertNotNull(end);
        assertEquals(end.get("used").getAsInt(), end.get("entries").getAsInt(), "every recorded decision used: " + end);
        assertEquals(forced.log(), rec.log(), "the forced replay is the recorded game");
        assertEquals(forced.acts(), rec.acts(), "the script's place followed the record (ForcedChoiceObserver)");
        // resume from the first half of the record: then live, from where the script was; the same game
        final Taped resumed = taped(position, script, player, "resume", entries.subList(0, entries.size() / 2), 8);
        assertNull(status(resumed.tape(), "divergence"), "no divergence: " + resumed.tape());
        assertEquals(resumed.log(), rec.log(), "resumed halfway, the scripted seat plays on exactly as it did live");
    }
}
