package forge.interactive;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.AITest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Lane puzzle-host-1021: a one-seat game from a puzzle position. The three catalog fixtures (mtgx-52's intro-01,
 * intro-05, intro-08 .pzl [state] blocks) parse, install through Forge's own Puzzle, and report their goal; bad
 * positions are refused with {@code invalid_puzzle}. The full games over the wire are the lane's e2e runs.
 */
public class InteractivePuzzleTest extends AITest {

    static final String INTRO_01 = String.join("\n",
            "turn=7", "activeplayer=human", "activephase=MAIN1", "humanlife=9", "ailife=5", "humanlandsplayed=1",
            "humanhand=Lightning Bolt;Young Pyromancer",
            "humanlibrary=Mountain;Forest;Mountain;Forest;Mountain;Forest;Mountain;Forest", "humangraveyard=",
            "humanbattlefield=Mountain;Mountain;Monastery Swiftspear", "humanexile=", "aihand=",
            "ailibrary=Mountain;Forest;Mountain;Forest;Mountain;Forest;Mountain;Forest", "aigraveyard=",
            "aibattlefield=Forest|Tapped;Forest|Tapped", "aiexile=");
    static final String INTRO_05 = String.join("\n",
            "turn=7", "activeplayer=human", "activephase=MAIN1", "humanlife=10", "ailife=4", "humanlandsplayed=1",
            "humanhand=", "humanlibrary=Plains;Forest;Plains;Forest;Plains;Forest;Plains;Forest", "humangraveyard=",
            "humanbattlefield=Plains;Plains;Plains|Tapped;Sword of the Meek;Elite Spellbinder;Young Pyromancer",
            "humanexile=", "aihand=", "ailibrary=Plains;Forest;Plains;Forest;Plains;Forest;Plains;Forest",
            "aigraveyard=", "aibattlefield=Forest|Tapped;Wall of Omens", "aiexile=");
    static final String INTRO_08 = String.join("\n",
            "turn=7", "activeplayer=ai", "activephase=COMBAT_BEGIN", "humanlife=3", "ailife=12",
            "humanhand=Lightning Bolt", "humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain",
            "humangraveyard=", "humanbattlefield=Mountain;Island|Tapped;Baleful Strix", "humanexile=", "aihand=",
            "ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain", "aigraveyard=",
            "aibattlefield=Mountain|Tapped;Mountain|Tapped;Mountain|Tapped;Rotting Regisaur;Flametongue Kavu;Monastery Swiftspear",
            "aiexile=");

    private static JsonObject config(final String puzzle, final String extra) {
        return JsonParser.parseString("{\"protocol\":\"mtgx-forge-interactive/1\",\"type\":\"config\",\"session\":\"s\","
                + "\"humanSeat\":0,\"seed\":7,\"aiProfile\":\"Default\"" + (puzzle == null ? "" : ",\"puzzle\":" + puzzle)
                + extra + "}").getAsJsonObject();
    }

    private static String puzzleJson(final String state, final String goal, final Object turns) {
        final JsonObject p = new JsonObject();
        p.addProperty("state", state);
        p.addProperty("goal", goal);
        if (turns instanceof Number n) {
            p.addProperty("turns", n);
        } else if (turns != null) {
            p.addProperty("turns", turns.toString());
        }
        return p.toString();
    }

    private static InteractivePuzzle.Spec spec(final String state, final String goal) throws Exception {
        return InteractivePuzzle.read(config(puzzleJson(state, goal, 7), ""));
    }

    private static String code(final ThrowingRunnable r) {
        try {
            r.run();
        } catch (InteractiveProtocol.ProtocolException e) {
            return e.code() + ": " + e.getMessage();
        } catch (Exception e) {
            return "unexpected " + e;
        }
        return null;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // --- the config line ------------------------------------------------------------------------------------------

    @Test
    public void aPuzzleConfigHasNoDecksAndIsOneSeat() throws Exception {
        final String p = puzzleJson(INTRO_01, "win", 7);
        final InteractiveProtocol.Config c = InteractiveProtocol.readConfig(config(p, "").toString());
        assertTrue(c.decks().isEmpty());
        assertFalse(c.isTable());
        assertEquals(InteractivePuzzle.read(config(p, "")).turns(), 7);
        assertNull(InteractivePuzzle.read(config(null, "")), "no puzzle: a deck game");
        for (String extra : List.of(",\"decks\":[\"/tmp/a.dck\",\"/tmp/b.dck\"]", ",\"humanSeats\":[0,1]",
                ",\"startingChooser\":0")) {
            assertNotNull(code(() -> InteractiveProtocol.readConfig(config(p, extra).toString())), extra);
        }
        // a deck game without decks is still refused
        assertNotNull(code(() -> InteractiveProtocol.readConfig(config(null, "").toString())));
    }

    @Test
    public void badPositionsAreRefusedAsInvalidPuzzle() throws Exception {
        final List<String[]> bad = List.of(
                new String[] {INTRO_01, "kill", "7"},
                new String[] {INTRO_01, "win", "6"},              // the limit before the position's turn
                new String[] {INTRO_01, "win", "28"},             // more than 20 turns ahead
                new String[] {INTRO_01, "win", "seven"},
                new String[] {"", "win", "7"},
                new String[] {INTRO_01.replace("turn=7\n", ""), "win", "7"},
                new String[] {INTRO_01.replace("activeplayer=human", "activeplayer=bob"), "win", "7"},
                new String[] {INTRO_01.replace("humanlife=9", "humanlife=lots"), "win", "7"},
                new String[] {INTRO_01 + "\nhumanfavourite=Lightning Bolt", "win", "7"},
                new String[] {"[metadata]\nGoal:Win\n" + INTRO_01, "win", "7"},
                new String[] {INTRO_01 + "\njust some words", "win", "7"},
                new String[] {"x".repeat(InteractivePuzzle.MAX_STATE + 1), "win", "7"});
        for (String[] b : bad) {
            final String why = code(() -> InteractivePuzzle.read(config(puzzleJson(b[0], b[1],
                    b[2].matches("\\d+") ? Integer.valueOf(b[2]) : b[2]), "")));
            assertNotNull(why, "accepted: " + b[1] + "/" + b[2] + " " + b[0].substring(0, Math.min(60, b[0].length())));
            assertTrue(why.startsWith(InteractivePuzzle.REFUSED + ":"), why);
        }
        // a phase Forge does not know: refused once Forge's localizer is up (the game start), not by the config reader
        initAndCreateGame();
        final String lunch = INTRO_01.replace("activephase=MAIN1", "activephase=LUNCH");
        final String why = code(() -> InteractivePuzzle.lines(InteractivePuzzle.read(config(puzzleJson(lunch, "win", 7), "")), 0));
        assertNotNull(why);
        assertTrue(why.startsWith("invalid_puzzle: activephase is not a phase"), why);
        // a combat damage step has no Combat to resume: refused
        for (String step : List.of("COMBAT_DAMAGE", "COMBAT_FIRST_STRIKE_DAMAGE")) {
            final String dmg = INTRO_01.replace("activephase=MAIN1", "activephase=" + step);
            final String w = code(() -> InteractivePuzzle.lines(InteractivePuzzle.read(config(puzzleJson(dmg, "win", 7), "")), 0));
            assertEquals(w, "invalid_puzzle: activephase cannot be a combat damage step", step);
        }
        // more than 300 cards
        final String many = INTRO_01.replace("aiexile=", "aiexile=" + String.join(";", java.util.Collections.nCopies(301, "Island")));
        assertNotNull(code(() -> InteractivePuzzle.read(config(puzzleJson(many, "win", 7), ""))));
        // spaces around a number are taken (and passed on trimmed: GameState parses them untrimmed)
        final String spaced = INTRO_01.replace("turn=7", "turn= 7 ").replace("humanlife=9", "humanlife= 9");
        final List<String> lines = InteractivePuzzle.lines(InteractivePuzzle.read(config(puzzleJson(spaced, "win", 7), "")), 0);
        assertTrue(lines.contains("turn=7"), lines.toString());
        assertTrue(lines.contains("p0life=9"), lines.toString());
        // an unknown field of the puzzle object, and a puzzle that is not an object
        assertNotNull(code(() -> InteractivePuzzle.read(config("{\"state\":\"turn=7\",\"goal\":\"win\",\"turns\":7,\"x\":1}", ""))));
        assertNotNull(code(() -> InteractivePuzzle.read(config("\"turn=7\"", ""))));
    }

    @Test
    public void anUnknownCardIsRefusedNotSkipped() throws Exception {
        initAndCreateGame(); // the card database
        for (String state : List.of(INTRO_01, INTRO_05, INTRO_08)) {
            InteractivePuzzle.checkCards(InteractivePuzzle.lines(spec(state, "win"), 0));
        }
        final String typo = INTRO_01.replace("Monastery Swiftspear", "Monastery Swiftspeer");
        final String why = code(() -> InteractivePuzzle.checkCards(InteractivePuzzle.lines(spec(typo, "win"), 0)));
        assertNotNull(why);
        assertEquals(why, "invalid_puzzle: unknown or unsupported card in p0battlefield", "the name stays off the wire");
    }

    // --- the position, installed by Forge's own Puzzle -------------------------------------------------------------

    private static List<RegisteredPlayer> registered(final Game game) {
        final List<RegisteredPlayer> out = new ArrayList<>();
        for (Player player : game.getPlayers()) {
            out.add(player.getRegisteredPlayer());
        }
        return out;
    }

    private Game installed(final String state, final String goal, final int humanSeat) throws Exception {
        final Game game = initAndCreateGame();
        final InteractivePuzzle.Spec spec = spec(state, goal);
        final List<String> lines = InteractivePuzzle.lines(spec, humanSeat);
        InteractivePuzzle.checkCards(lines);
        InteractivePuzzle.hook(InteractivePuzzle.position(spec, lines, humanSeat), game).run();
        return game;
    }

    private static Card named(final Player p, final ZoneType zone, final String name) {
        for (Card c : p.getCardsIn(zone)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void intro01InstallsAsWritten() throws Exception {
        final Game game = installed(INTRO_01, "win", 0);
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);
        assertEquals(game.getPhaseHandler().getTurn(), 7);
        assertEquals(game.getPhaseHandler().getPhase(), PhaseType.MAIN1);
        assertEquals(game.getPhaseHandler().getPlayerTurn(), human);
        assertEquals(human.getLife(), 9);
        assertEquals(ai.getLife(), 5);
        assertEquals(human.getLandsPlayedThisTurn(), 1);
        assertEquals(human.getCardsIn(ZoneType.Hand).size(), 2);
        assertEquals(human.getCardsIn(ZoneType.Library).size(), 8);
        final Card spear = named(human, ZoneType.Battlefield, "Monastery Swiftspear");
        assertNotNull(spear);
        assertFalse(spear.hasSickness(), "a battlefield creature is not summoning sick unless |SummonSick");
        for (Card forest : ai.getCardsIn(ZoneType.Battlefield)) {
            assertTrue(forest.isTapped(), forest + " is |Tapped");
        }
        assertNotNull(named(human, ZoneType.Command, InteractivePuzzle.GOAL_CARD), "the goal is the browser seat's");
        assertNull(named(ai, ZoneType.Command, InteractivePuzzle.GOAL_CARD));
    }

    @Test
    public void intro05AndIntro08InstallAsWritten() throws Exception {
        final Game g5 = installed(INTRO_05, "win", 0);
        final Player h5 = g5.getPlayers().get(0);
        assertEquals(g5.getPlayers().get(1).getLife(), 4);
        assertFalse(named(h5, ZoneType.Battlefield, "Elite Spellbinder").hasSickness());
        assertTrue(g5.getPlayers().get(1).getCardsIn(ZoneType.Exile).isEmpty(), "no ETB fired while installing");
        assertEquals(h5.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isTapped).count(), 1L);

        final Game g8 = installed(INTRO_08, "survive", 0);
        final Player h8 = g8.getPlayers().get(0);
        final Player a8 = g8.getPlayers().get(1);
        assertEquals(g8.getPhaseHandler().getPlayerTurn(), a8);
        assertEquals(g8.getPhaseHandler().getPhase(), PhaseType.COMBAT_BEGIN);
        assertEquals(h8.getLife(), 3);
        assertFalse(named(h8, ZoneType.Battlefield, "Baleful Strix").hasSickness());
        assertFalse(named(a8, ZoneType.Battlefield, "Rotting Regisaur").hasSickness());
        assertNotNull(named(h8, ZoneType.Command, InteractivePuzzle.GOAL_CARD));
        assertNotNull(g8.getPhaseHandler().getCombat(), "a beginning-of-combat position has its combat");
        assertEquals(g8.getPhaseHandler().getCombat().getAttackingPlayer(), a8);
        assertNull(g5.getPhaseHandler().getCombat(), "a main-phase position has none");
    }

    @Test
    public void humanSeatOneGetsTheHumanSide() throws Exception {
        final Game game = installed(INTRO_01, "win", 1);
        final Player browser = game.getPlayers().get(1);
        final Player ai = game.getPlayers().get(0);
        assertEquals(browser.getLife(), 9);
        assertEquals(ai.getLife(), 5);
        assertEquals(game.getPhaseHandler().getPlayerTurn(), browser);
        assertNotNull(named(browser, ZoneType.Battlefield, "Monastery Swiftspear"));
        assertNotNull(named(browser, ZoneType.Command, InteractivePuzzle.GOAL_CARD));
        // a card attribute naming a player cannot be rewritten safely: refused for seat 1, kept for seat 0
        final String ref = INTRO_01.replace("aibattlefield=Forest|Tapped;Forest|Tapped",
                "aibattlefield=Forest|Tapped;Forest|Tapped|Owner:HUMAN");
        assertNotNull(code(() -> InteractivePuzzle.lines(spec(ref, "win"), 1)));
        InteractivePuzzle.lines(spec(ref, "win"), 0);
    }

    @Test
    public void theSeatDecksAreTheirOwnPuzzleCards() throws Exception {
        initAndCreateGame();
        final List<String> lines = InteractivePuzzle.lines(spec(INTRO_01, "win"), 0);
        assertEquals(InteractivePuzzle.deckOf(lines, 0).getMain().countAll(), 13);
        assertEquals(InteractivePuzzle.deckOf(lines, 0).getMain().count(
                forge.StaticData.instance().getCommonCards().getCard("Lightning Bolt")), 1);
        assertEquals(InteractivePuzzle.deckOf(lines, 1).getMain().countAll(), 10);
    }

    // --- the goal's outcome --------------------------------------------------------------------------------------

    private static JsonObject outcome(final Game game, final String goal, final int humanSeat) throws Exception {
        game.getAction().checkGameOverCondition();
        assertTrue(game.isGameOver());
        return InteractivePuzzle.outcome(spec(INTRO_01, goal), game.getOutcome(), registered(game), humanSeat);
    }

    @Test
    public void winningAWinPuzzleIsSolved() throws Exception {
        final Game game = installed(INTRO_01, "win", 0);
        final List<RegisteredPlayer> seats = registered(game);
        game.getPlayers().get(1).setLife(0, null);
        game.getAction().checkGameOverCondition();
        final JsonObject o = InteractivePuzzle.outcome(spec(INTRO_01, "win"), game.getOutcome(), seats, 0);
        assertEquals(o.toString(), "{\"goal\":\"win\",\"goalMet\":true,\"reason\":\"solved\"}");
    }

    @Test
    public void theGoalsOwnLossIsTheTurnLimit() throws Exception {
        final Game game = installed(INTRO_01, "win", 0);
        final List<RegisteredPlayer> seats = registered(game);
        game.getPlayers().get(0).loseConditionMet(GameLossReason.SpellEffect, InteractivePuzzle.GOAL_CARD);
        game.getAction().checkGameOverCondition();
        final JsonObject o = InteractivePuzzle.outcome(spec(INTRO_01, "win"), game.getOutcome(), seats, 0);
        assertEquals(o.toString(), "{\"goal\":\"win\",\"goalMet\":false,\"reason\":\"turn_limit\"}");
    }

    @Test
    public void survivingIsTheGoalsWinAndDyingIsLost() throws Exception {
        Game game = installed(INTRO_08, "survive", 0);
        List<RegisteredPlayer> seats = registered(game);
        game.getPlayers().get(0).altWinBySpellEffect(InteractivePuzzle.GOAL_CARD);
        game.getAction().checkGameOverCondition();
        assertEquals(InteractivePuzzle.outcome(spec(INTRO_08, "survive"), game.getOutcome(), seats, 0).toString(),
                "{\"goal\":\"survive\",\"goalMet\":true,\"reason\":\"survived\"}");

        game = installed(INTRO_08, "survive", 0);
        seats = registered(game);
        game.getPlayers().get(0).setLife(0, null);
        game.getAction().checkGameOverCondition();
        assertEquals(InteractivePuzzle.outcome(spec(INTRO_08, "survive"), game.getOutcome(), seats, 0).toString(),
                "{\"goal\":\"survive\",\"goalMet\":false,\"reason\":\"lost\"}");

        game = installed(INTRO_08, "survive", 0);
        seats = registered(game);
        game.getPlayers().get(0).concede();
        game.getAction().checkGameOverCondition();
        assertEquals(InteractivePuzzle.outcome(spec(INTRO_08, "survive"), game.getOutcome(), seats, 0).toString(),
                "{\"goal\":\"survive\",\"goalMet\":false,\"reason\":\"conceded\"}");
    }

    @Test
    public void theTerminalCarriesThePuzzleOutcome() throws Exception {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream out = new java.io.PrintStream(bytes, true, "UTF-8");
        final InteractiveProtocol.Channel channel = new InteractiveProtocol.Channel(
                new java.io.BufferedReader(new java.io.StringReader("")), out, "session-1");
        final JsonObject puzzle = new JsonObject();
        puzzle.addProperty("goal", "win");
        puzzle.addProperty("goalMet", true);
        puzzle.addProperty("reason", "solved");
        channel.terminal("g1", 0, "AllOpponentsLost", 7, null, puzzle);
        final String[] lines = bytes.toString("UTF-8").trim().split("\n");
        final JsonObject t = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
        assertEquals(t.getAsJsonObject("puzzle"), puzzle);
        assertEquals(t.get("winner").getAsInt(), 0);
    }
}
