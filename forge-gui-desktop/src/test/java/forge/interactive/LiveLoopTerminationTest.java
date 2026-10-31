package forge.interactive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.simulation.LobbyPlayerLookahead;
import forge.ai.simulation.LookaheadSearch;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameState;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Lane live-ai-quality-1030 (owner 2026-10-30): open-ended loops with no gain ceiling must end in bounded steps, and the live
 * Hard AI must not deck itself. Each scripted position (a {@link GameState} record, as puzzles and lessons install them) gives
 * the live look-ahead seat, bound with the live spec (property {@code loop.spec}, default the live cut-3 spec), a loop it can
 * repeat for free: Fastbond + Zuran Orb + Crucible of Worlds (infinite life), Displacer Kitten + Teferi, Time Raveler + Mox
 * Amber (infinite draw), Kinnan + Basalt Monolith (infinite mana with no outlet). The game is stepped through the seat's turn,
 * the opponent's turn and the seat's next draw. Pass: the run stops within the step cap and the seat has not lost.
 * {@code [loop-test]} lines record the steps, the seat's casts and activations per turn, and its library, life and decisions.
 */
public class LiveLoopTerminationTest extends AITest {

    static final String LIVE_SPEC = "worlds=8,breadth=4,horizon=2,threads=8,budgetMs=8000,log=1,reuse=1,departZ=1.645,"
            + "aiFixes0928=on,deadEtb=on,zeroX=on,crewNoop=on";

    private static String lib(String... names) {
        return libN(30, names);
    }

    private static String libN(int n, String... names) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(names[i % names.length]);
        }
        return String.join(";", l);
    }

    private static String position(String aiBattlefield, String aiHand, String aiGraveyard, String aiLibrary) {
        return position("ai", "MAIN1", aiBattlefield, aiHand, aiGraveyard, aiLibrary);
    }

    private static String position(String active, String phase, String aiBattlefield, String aiHand, String aiGraveyard,
            String aiLibrary) {
        return String.join("\n", "turn=5", "activeplayer=" + active, "activephase=" + phase, "humanlife=20", "ailife=20",
                "humanhand=Island;Island", "humanlibrary=" + lib("Island", "Grizzly Bears"), "humangraveyard=", "humanexile=",
                "humanbattlefield=Island;Island;Island",
                "aihand=" + aiHand, "ailibrary=" + aiLibrary, "aigraveyard=" + aiGraveyard, "aiexile=",
                "aibattlefield=" + aiBattlefield);
    }

    static final String LIFE = position("Fastbond;Zuran Orb;Crucible of Worlds;Forest;Forest;Forest", "Forest",
            "Forest;Forest;Mountain", lib("Forest", "Grizzly Bears"));
    static final String DRAW = position("Displacer Kitten;Teferi, Time Raveler|Counters:LOYALTY=4;Mox Amber;"
            + "Island;Island;Island;Island", "", "", lib("Island", "Grizzly Bears"));
    static final String MANA = position("Kinnan, Bonder Prodigy;Basalt Monolith;Forest;Island;Forest", "", "",
            lib("Forest", "Grizzly Bears"));

    /** Mid-loop: Mox Amber in hand (a free noncreature spell: Kitten resets Teferi), six cards left in the library. */
    static final String DRAW_MID = position("Displacer Kitten;Teferi, Time Raveler|Counters:LOYALTY=4;"
            + "Island;Island;Island;Island", "Mox Amber", "", libN(6, "Island", "Grizzly Bears"));
    /** Mid-loop: the opponent's end step, Basalt Monolith tapped (its untap's AILogic is AtOppEOT), Kinnan in play. */
    static final String MANA_MID = position("human", "END_OF_TURN", "Kinnan, Bonder Prodigy;Basalt Monolith|Tapped;"
            + "Forest;Island;Forest;Island", "", "", lib("Forest", "Grizzly Bears"));
    /** The loop's own steps in the game log, per scenario (what "started the loop" means). */
    static final String[] LOOP_STEPS = {"cast Mox Amber", "triggered Displacer Kitten targeting [Teferi", "activated Teferi",
        "activated Zuran Orb", "activated Basalt Monolith"};

    /** One scripted run's outcome. */
    static final class Run {
        int steps;
        boolean capped;
        boolean seatLost;
        String reason = "-";
        int seatLibrary;
        int seatLife;
        long decisions;
        final List<Integer> actionsPerTurn = new ArrayList<>();
        final java.util.Map<String, Integer> loopSteps = new java.util.TreeMap<>();
        int logged;
        String stats;

        @Override
        public String toString() {
            return "steps " + steps + " capped " + capped + " seatLost " + seatLost + " (" + reason + ") library " + seatLibrary
                    + " life " + seatLife + " decisions " + decisions + " seat actions per turn " + actionsPerTurn
                    + " loop steps " + loopSteps + " stats " + stats;
        }
    }

    private Run run(String name, String position, String spec) {
        initAndCreateGame();
        MyRandom.setRandom(new java.util.Random(1030));
        final List<RegisteredPlayer> players = new ArrayList<>();
        final Deck d = new Deck();
        final LobbyPlayerLookahead lp = new LobbyPlayerLookahead("k8");
        players.add(new RegisteredPlayer(d).setPlayer(new LobbyPlayerAi("opp", null)));
        players.add(new RegisteredPlayer(d).setPlayer(lp));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Game game = new Game(players, rules, new Match(rules, players, "Test"));
        game.setAge(GameStage.Play);
        final GameState state = new GameState();
        state.parse(Arrays.asList(position.split("\\R")));
        // as InteractivePuzzle's hook: named as a game thread, so GameState installs inline
        final String was = Thread.currentThread().getName();
        Thread.currentThread().setName("Game-loop-install");
        try {
            state.applyToGame(game);
        } finally {
            Thread.currentThread().setName(was);
        }
        final InteractiveMain.ParsedLookahead pl = InteractiveMain.parseLookahead(spec);
        lp.setAiFixes0928(pl.aiFixes);
        pl.config.seed = 1030L * 31;
        pl.config.threads = Integer.getInteger("loop.threads", 4); // decisions do not depend on it
        final LookaheadSearch search = new LookaheadSearch(pl.config);
        lp.bind(game, search);
        final Player seat = game.getPlayers().get(1);
        final int startTurn = game.getPhaseHandler().getTurn();
        final int maxSteps = Integer.getInteger("loop.maxSteps", 4000);
        final Run r = new Run();
        final long deadline = System.currentTimeMillis() + Long.getLong("loop.maxMs", 1_200_000L);
        while (!game.isGameOver() && r.steps < maxSteps && System.currentTimeMillis() < deadline) {
            final int t = game.getPhaseHandler().getTurn();
            if (t > startTurn + 2 || (t == startTurn + 2 && game.getPhaseHandler().getPhase() != null
                    && game.getPhaseHandler().getPhase().isAfter(forge.game.phase.PhaseType.DRAW))) {
                break;
            }
            game.getPhaseHandler().mainLoopStep();
            r.steps++;
        }
        r.capped = !game.isGameOver() && (r.steps >= maxSteps || System.currentTimeMillis() >= deadline);
        r.seatLost = seat.hasLost();
        if (r.seatLost) {
            r.reason = String.valueOf(seat.getOutcome());
        }
        r.seatLibrary = seat.getCardsIn(ZoneType.Library).size();
        r.seatLife = seat.getLife();
        r.decisions = search.getStats().decisions;
        r.stats = String.valueOf(search.getStats().toJson().get("dudCast"));
        final List<GameLogEntry> entries = new ArrayList<>(game.getGameLog().getLogEntries(null));
        Collections.reverse(entries);
        int turnActions = 0;
        for (GameLogEntry e : entries) {
            final String m = e.message();
            if (m == null) {
                continue;
            }
            if (m.startsWith(seat.getName() + " ") && r.logged++ < 80) {
                System.err.println("[loop-log] " + name + " | " + m);
            }
            for (String step : LOOP_STEPS) {
                if (m.startsWith(seat.getName() + " " + step)) {
                    r.loopSteps.merge(step, 1, Integer::sum);
                }
            }
            if (m.startsWith("Turn ")) {
                r.actionsPerTurn.add(turnActions);
                turnActions = 0;
            } else if (m.startsWith(seat.getName() + " cast") || m.startsWith(seat.getName() + " activate")
                    || m.startsWith(seat.getName() + " play")) {
                turnActions++;
            }
        }
        r.actionsPerTurn.add(turnActions);
        search.shutdown();
        System.err.println("[loop-test] " + name + " spec=" + (spec.equals(LIVE_SPEC) ? "live" : spec) + " " + r);
        return r;
    }

    private Run check(String name, String position) {
        final String spec = System.getProperty("loop.spec", LIVE_SPEC);
        final Run r = run(name, position, spec);
        AssertJUnit.assertFalse(name + ": the run must end within the step cap: " + r, r.capped);
        AssertJUnit.assertFalse(name + ": the seat must not lose (deck itself): " + r, r.seatLost);
        AssertJUnit.assertTrue(name + ": library left: " + r, r.seatLibrary > 0);
        return r;
    }

    private static int steps(Run r, String step) {
        return r.loopSteps.getOrDefault(step, 0);
    }

    @Test(timeOut = 3_600_000)
    public void infiniteLife() {
        // the loop runs: lands replayed from the graveyard and sacrificed to the Orb, then the seat stops
        final Run r = check("life (Fastbond + Zuran Orb + Crucible)", LIFE);
        AssertJUnit.assertTrue("the seat starts the life loop: " + r, steps(r, "activated Zuran Orb") >= 3);
    }

    @Test(timeOut = 3_600_000)
    public void infiniteDraw() {
        check("draw (Displacer Kitten + Teferi + Mox Amber)", DRAW);
    }

    @Test(timeOut = 3_600_000)
    public void infiniteDrawMidLoop() {
        // the loop's first step (the free Mox Amber, the Kitten trigger) is taken; the seat then does not reset Teferi
        final Run r = check("draw mid-loop (Mox Amber in hand, 6 cards left)", DRAW_MID);
        AssertJUnit.assertTrue("the seat takes the loop's first step: " + r, steps(r, "cast Mox Amber") >= 1);
    }

    @Test(timeOut = 3_600_000)
    public void infiniteManaMidLoop() {
        check("mana mid-loop (opponent's end step, Monolith tapped)", MANA_MID);
    }

    @Test(timeOut = 3_600_000)
    public void infiniteMana() {
        check("mana (Kinnan + Basalt Monolith)", MANA);
    }
}
