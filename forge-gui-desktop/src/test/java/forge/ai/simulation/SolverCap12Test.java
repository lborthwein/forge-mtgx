package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * solver-cap12-1030 (CAP-2, PREDECLARATION-solver-cap12 §2): the counted generator's engine node cap. A seat's solver
 * lives the whole game and its engine Stats accumulate; off, the cap compares that lifetime count, so once earlier
 * searches spent the cap a later same-turn pass gets no node (a lifetime stop) and the seat misses a kill a label (a
 * fresh solver) proves. engineNodesPerSearch gives each search its own cap.
 */
public class SolverCap12Test extends AITest {

    static final String KIKI_BF = ForcedWinSolverTest.KIKI_BF;

    /** Spend at least 60 engine nodes of {@code st} on a position with no kill (the seat's earlier turns). */
    static void spend(final ForcedWinEngine.Stats st, final boolean perSearch) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("Grizzly Bears;Mountain",
                "Mountain;Mountain;Forest;Forest", 20, "", "Forest", 20, "human"));
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        lim.nodes = 60;
        lim.passTurn = false;
        lim.perSearchNodes = perSearch;
        // review #149 S2-2: the same Limits across the searches (search restores it, error or not)
        for (int i = 0; i < 30 && st.nodes < 60; i++) {
            Assert.assertNull(ForcedWinEngine.search(g, g.getPlayers().get(0), lim, st, System.nanoTime() + 20_000_000_000L,
                    3L + i), "no kill in the spending position");
        }
    }

    static ForcedWinPlanner.Line kiki(final ForcedWinEngine.Stats st, final boolean perSearch) {
        return kiki(st, perSearch, false);
    }

    static ForcedWinPlanner.Line kiki(final ForcedWinEngine.Stats st, final boolean perSearch, final boolean passTurn) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame("Pestermite", KIKI_BF, 20, "",
                "Forest", 6, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinEngine.Limits lim = new ForcedWinEngine.Limits();
        lim.nodes = 60;
        lim.cycles = true;
        lim.passTurn = passTurn;
        lim.perSearchNodes = perSearch;
        return ForcedWinEngine.search(g, me, lim, st, System.nanoTime() + 20_000_000_000L, 7L);
    }

    /** The profile spec through CubeComboControl's own split. */
    static ForcedWinSolver.Config parse(final String spec) {
        final java.util.Map<String, String> kv = new java.util.HashMap<>();
        for (String part : spec.split(",")) {
            final int eq = part.indexOf('=');
            if (eq > 0) kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    @Test
    public void aFreshSolverFindsTheKikiKill() {
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        Assert.assertNotNull(kiki(st, false), st.summary());
        Assert.assertEquals(st.lifetimeStops, 0);
    }

    @Test
    public void offTheLifetimeCapStarvesALaterSearch() {
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        spend(st, false);
        Assert.assertTrue(st.nodes >= 60, st.summary());
        final int lt0 = st.lifetimeStops, n0 = st.nodes;
        Assert.assertNull(kiki(st, false), "off: the earlier search's nodes count against this one: " + st.summary());
        Assert.assertEquals(st.lifetimeStops, lt0 + 1, "the Kiki search itself stopped on the lifetime cap: " + st.summary());
        Assert.assertEquals(st.nodes, n0, "starved at the root: no node spent: " + st.summary());
    }

    @Test
    public void perSearchNodesFindTheKillAfterEarlierSearches() {
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        spend(st, true);
        Assert.assertTrue(st.nodes >= 60, st.summary());
        final int n0 = st.nodes;
        Assert.assertNotNull(kiki(st, true), "on: this search has its own 60 nodes: " + st.summary());
        Assert.assertEquals(st.lifetimeStops, 0, st.summary());
        Assert.assertTrue(st.nodes - n0 <= 60, "within its own cap: " + st.summary());
    }

    @Test
    public void perSearchNodesHoldForTheTwoPassSearch() {
        // review #149 S3-4: with passTurn the same-turn pass gets half the cap, counted from the search's start
        final ForcedWinEngine.Stats st = new ForcedWinEngine.Stats();
        spend(st, true);
        final int n0 = st.nodes;
        Assert.assertNotNull(kiki(st, true, true), st.summary());
        Assert.assertEquals(st.lifetimeStops, 0, st.summary());
        Assert.assertTrue(st.nodes - n0 <= 60, st.summary());
    }

    @Test
    public void theCounted2ProfileIsTheCountedSpecPlusTheKey() {
        Assert.assertEquals(AiProfileUtil.HONEST_FORCED_WIN_COUNTED2_SPEC,
                AiProfileUtil.HONEST_FORCED_WIN_COUNTED_SPEC + ",engineNodesPerSearch=true");
        Assert.assertFalse(AiProfileUtil.HONEST_FORCED_WIN_COUNTED_SPEC.contains("engineNodesPerSearch"),
                "HonestForcedWinCounted is unchanged");
        Assert.assertTrue(AiProfileUtil.isKnownProfile(AiProfileUtil.HONEST_FORCED_WIN_COUNTED2_PROFILE));
        final ForcedWinSolver.Config c1 = parse(AiProfileUtil.HONEST_FORCED_WIN_COUNTED_SPEC);
        final ForcedWinSolver.Config c2 = parse(AiProfileUtil.HONEST_FORCED_WIN_COUNTED2_SPEC);
        Assert.assertTrue(c2.engineNodesPerSearch && c2.counter);
        Assert.assertFalse(c1.engineNodesPerSearch);
        Assert.assertFalse(HonestGenerator.solverCfgJson(c1).has("engineNodesPerSearch"), "the Counted spec's record unchanged");
        final ForcedWinSolver.Config off = ForcedWinSolver.Config.fromProps(k -> null);
        Assert.assertFalse(off.engineNodesPerSearch);
        Assert.assertFalse(off.toString().contains("engineNodesPerSearch"));
        Assert.assertFalse(HonestGenerator.solverCfgJson(off).has("engineNodesPerSearch"),
                "absent from a recorded config at its default (old tapes' specs byte for byte)");
        final ForcedWinSolver.Config on = ForcedWinSolver.Config.fromProps(k -> "engineNodesPerSearch".equals(k) ? "true" : null);
        Assert.assertTrue(on.engineNodesPerSearch);
        Assert.assertTrue(HonestGenerator.solverCfgJson(on).get("engineNodesPerSearch").getAsBoolean());
    }
}
