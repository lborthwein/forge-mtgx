package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.player.Player;

/**
 * solver-cap12-1030 round 3 (PREDECLARATION-solver-cap12-APPENDIX-round3): the budget ladder, the engine's own eval-step
 * budget in label mode, value-ordered engine moves, X inside engine moves and engine lines on a non-empty stack. All off
 * by default and absent from a recorded config at their defaults.
 */
public class SolverCap12R3Test extends forge.ai.AITest {

    static final String[] BOOL_KEYS = {"engineLadder", "engineX", "engineCostChoice", "engineStack", "loopMacro3"};

    @Test
    public void theRoundThreeKeysAreOffAndUnrecordedByDefault() {
        final ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        Assert.assertFalse(off.engineLadder || off.engineX || off.engineCostChoice || off.engineStack || off.loopMacro3);
        Assert.assertEquals(off.engineOrder, "off");
        Assert.assertEquals(off.engineEvalSteps, 0);
        for (String k : ForcedWinSolver.Config.R3_DEFAULTS.keySet()) {
            Assert.assertFalse(HonestGenerator.solverCfgJson(off).has(k), k + " absent at its default");
            Assert.assertFalse(off.toString().contains(k), k);
        }
        for (String k : BOOL_KEYS) {
            final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(x -> k.equals(x) ? "true" : null);
            Assert.assertTrue(HonestGenerator.solverCfgJson(on).get(k).getAsBoolean(), k + " recorded when on");
        }
        final ForcedWinSolver.Config ord = ForcedWinSolver.Config.fromProps(x -> "engineOrder".equals(x) ? "static" : null);
        Assert.assertEquals(HonestGenerator.solverCfgJson(ord).get("engineOrder").getAsString(), "static");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> ForcedWinSolver.Config.fromProps(x -> "engineOrder".equals(x) ? "cards" : null));
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        Assert.assertNull(lim.order);
        Assert.assertFalse(lim.xChoice);
    }

    @Test
    public void xValuesCoverSmallRangesAndSampleLargeOnes() {
        Assert.assertEquals(ForcedWinEngine.xValues(3, 8), List.of(3, 2, 1, 0));
        Assert.assertEquals(ForcedWinEngine.xValues(7, 8), List.of(7, 6, 5, 4, 3, 2, 1, 0));
        Assert.assertEquals(ForcedWinEngine.xValues(40, 8), List.of(40, 39, 30, 20, 10, 2, 1, 0));
        Assert.assertEquals(ForcedWinEngine.xValues(0, 8), List.of(0));
        Assert.assertTrue(ForcedWinEngine.xValues(-1, 8).isEmpty());
    }

    /** A coverage puzzle by name, as a game (seat 0 is the combo seat). */
    static Game puzzle(final String name) {
        for (WinConditionCoverageTest.P p : WinConditionCoverageTest.puzzles()) {
            if (p.name.equals(name)) {
                return ForcedWinSolverTest.game("Default", "Default", p.frame());
            }
        }
        throw new IllegalArgumentException(name);
    }

    static ForcedWinEngine.Limits limits(final int nodes, final boolean order, final boolean x) {
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        lim.nodes = nodes;
        lim.cycles = true;
        lim.passTurn = true;
        lim.loopMacro = true;
        lim.loopMacro3 = true; // review #154 S2: round 3's loop extensions (the mana-sink ceiling)
        lim.order = order ? StaticValuer.INSTANCE : null;
        lim.xChoice = x;
        return lim;
    }

    static ForcedWinPlanner.Line engine(final Game g, final Player me, final ForcedWinEngine.Limits lim,
            final ForcedWinEngine.Stats st) {
        return ForcedWinEngine.search(g, me, lim, st, System.nanoTime() + 600_000_000_000L, 7L);
    }

    @Test(timeOut = 600000)
    public void ballistaXIsTheOutletOfTheBasaltKinnanLoop() {
        // R3-4 (a): the loop makes unbounded colourless mana; X inside the engine's Ballista move spends it (Forge AI's
        // X alone never kills)
        final Game g = puzzle("basalt-kinnan-ballista");
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        final ForcedWinPlanner.Line l = engine(g, g.getPlayers().get(0), limits(250, Boolean.parseBoolean(
                System.getProperty("sc3.order", "true")), true), st);
        System.out.println("SC3 ballista-x " + st.summary() + " line=" + l);
        Assert.assertNotNull(l, st.summary());
        Assert.assertTrue(l.toString().contains("X="), l.toString());
    }

    @Test(timeOut = 600000)
    public void orderingKeepsTheSameProofsOnTheLoopPuzzles() {
        // R3-3: value-ordered moves still prove the loop kills (the order changes which line is found first, not
        // whether a line exists)
        for (String name : new String[] {"kiki-pestermite", "twin-exarch", "tendrils-storm3"}) {
            final Game g = puzzle(name);
            final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
            final ForcedWinPlanner.Line l = engine(g, g.getPlayers().get(0), limits(250, true, false), st);
            System.out.println("SC3 ordered " + name + " " + st.summary() + " line=" + l);
            Assert.assertNotNull(l, name + " " + st.summary());
        }
    }

    @Test(timeOut = 600000)
    public void wideTargetsDoNotCrowdOutTheOtherMoves() {
        // the kiki flip of the ladder-frequency run (2990164782-s1, turn 12): engineX's widened single targets (D8) were
        // distinct moves, so Strip Mine's many land targets filled the branching cap (8) and the line through Reanimate
        // was never tried. Here Rod of Ruin has ten targets and spends the mana the Bolt needs: the Bolt must still be
        // among the root's moves, keys off and on
        final Game g = ForcedWinSolverTest.game("Default", "Default", new WinConditionCoverageTest.P("rod-crowd",
                WinConditionCoverageTest.V.PROVEN, "Bolt for 3 into 3; Rod of Ruin faces two players and eight creatures")
                .k("humanhand", "Lightning Bolt")
                .k("humanbattlefield", WinConditionCoverageTest.j("Rod of Ruin", WinConditionCoverageTest.n("Mountain", 3)))
                .k("aibattlefield", WinConditionCoverageTest.n("Grizzly Bears", 8)).k("ailife", "3").frame());
        for (boolean x : new boolean[] {false, true}) {
            final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
            final ForcedWinEngine.Limits lim = limits(250, false, x);
            lim.passTurn = false; // this turn only: no next-turn Bolt after the Rod
            final ForcedWinPlanner.Line l = engine(g, g.getPlayers().get(0), lim, st);
            System.out.println("SC3 rod-crowd x=" + x + " " + st.summary() + " line=" + l);
            Assert.assertNotNull(l, "x=" + x + " " + st.summary());
            Assert.assertTrue(l.toString().contains("Lightning Bolt"), l.toString());
        }
    }

    /**
     * R3-3 measurement (not an assertion): nodes-to-proof of the full-information engine on -Dsc3.puzzles (coverage
     * names; "breach" = the CAP-1 frame, seat 1), Forge's order vs value order, at -Dsc3.nodes (default 2000).
     */
    @Test(timeOut = 3600000)
    public void nodesToProof() {
        final String names = System.getProperty("sc3.puzzles");
        if (names == null) {
            return;
        }
        final int nodes = Integer.getInteger("sc3.nodes", 2000);
        for (String name : names.split(",")) {
            for (boolean order : "static".equals(System.getProperty("sc3.only")) ? new boolean[] {true}
                    : "forge".equals(System.getProperty("sc3.only")) ? new boolean[] {false} : new boolean[] {false, true}) {
                final Game g = name.equals("breach") ? SolverCap1BreachTest.breach() : puzzle(name);
                final Player me = g.getPlayers().get(name.equals("breach") ? 1 : 0);
                final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
                final long t0 = System.nanoTime();
                final ForcedWinEngine.Limits lim = limits(nodes, order, true);
                lim.stack = !g.getStack().isEmpty(); // R3-5: a response puzzle
                lim.anyWindow = true;
                lim.costChoice = Boolean.getBoolean("sc3.cost"); // R3-4 (b)
                final ForcedWinPlanner.Line l = engine(g, me, lim, st);
                System.out.println("SC3 N2P " + name + " order=" + (order ? "static" : "forge") + " found=" + (l != null)
                        + " nodes=" + st.nodes + " moves=" + st.moves + " copies=" + st.copies + " ms="
                        + (System.nanoTime() - t0) / 1_000_000 + " " + st.summary() + " line=" + l);
            }
        }
    }
}
