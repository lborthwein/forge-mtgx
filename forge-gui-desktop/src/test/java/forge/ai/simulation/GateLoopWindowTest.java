package forge.ai.simulation;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.bench.rl.RlSearch;
import forge.game.EngineFixes;
import forge.game.Game;
import forge.game.WorkClock;
import forge.game.player.Player;

/**
 * gate-loop-window-1113: a decision's reach gate reads a loop window it opened itself. By the time a decision starts, the
 * previous ask's window has run out on the real clock (the opponent's turn passed in between). With the switch on (spec
 * key {@code reach.gateWindow}, else {@code -Dforge.gateLoopWindow}) the gate opens the decision's window first, so the
 * Kiki loop probe runs and the gate opens on this decision. Off, the probe is cut, nothing is cached, and the gate stays
 * shut for the rest of the turn and library size (reachGateKey), even once the decision's own window is open. The gate
 * only borrows the game's window slot: afterwards it holds what it held before (the other seat, the decision's search
 * and every later read see the windows they saw before).
 */
public class GateLoopWindowTest extends forge.ai.AITest {

    /** The copier in play with its untapper (a loop), one more creature in the library; the planner screens on. */
    private static Game kikiGame() {
        final String frame = ForcedWinSolverTest.frame("", "Mountain;Mountain;Mountain;Mountain;Mountain;Kiki-Jiki, Mirror Breaker;Pestermite",
                9, "", "Forest;Forest", 18, "human").replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=Grizzly Bears");
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
    }

    /** A search seat with the reach gate on, the loop screens on, an 8 s budget and {@code reach.gateWindow} as given (null = absent). */
    private static RlSearch seat(final Boolean gateWindow) {
        final StringBuilder screens = new StringBuilder();
        for (String k : new String[] {"cycles", "engineLoopReach", "engineLongLoops", "engineLoopBisect", "engineLoopHoist",
                "engineMoves", "engineTriggerTargets", "turnLimits", "stackAbilityCopies", "stackHistory", "historyLki",
                "copyBackFace", "offBattlefieldLimits", "staleStatics", "stalePlayerStatics", "planExpandCap", "planLoops"}) {
            screens.append(screens.length() == 0 ? "" : ",").append('"').append(k).append("\":\"true\"");
        }
        final JsonObject spec = JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"leaf\":\"value\",\"playout\":\"policy\","
                + "\"budgetMs\":8000,\"reach\":{\"gate\":true" + (gateWindow == null ? "" : ",\"gateWindow\":" + gateWindow)
                + "},\"plans\":{\"solverCfg\":{" + screens + "}}}").getAsJsonObject();
        return new RlSearch(RlSearch.Config.parse(spec), 1L, 2L, null, null, "test", "t");
    }

    private static void gateStep(final RlSearch rs, final Game g, final Player me) throws ReflectiveOperationException {
        final Method m = RlSearch.class.getDeclaredMethod("gateStep", Game.class, Player.class);
        m.setAccessible(true);
        m.invoke(rs, g, me);
    }

    /** The game's loop-window slot (null = empty). */
    private static Long slot(final Game g) throws ReflectiveOperationException {
        final Field f = ForcedWinSolver.class.getDeclaredField("LOOP_DEADLINE");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<Object, Long> m = (Map<Object, Long>) f.get(null);
        return m.get(g.getRules());
    }

    private static boolean gateOpen(final RlSearch rs) throws ReflectiveOperationException {
        final Method m = RlSearch.class.getDeclaredMethod("reachGateOpen");
        m.setAccessible(true);
        return (Boolean) m.invoke(rs);
    }

    @Test
    public void theGateReadsItsOwnWindowOnlyWhenTheSwitchIsOn() throws ReflectiveOperationException {
        final boolean was = EngineFixes.gateLoopWindow();
        final WorkClock.Clock prev = WorkClock.install(null); // the real clock, as live
        try {
            // {spec key, -D default}: the key wins either way; absent, the -D value decides
            final Object[][] arms = {{Boolean.TRUE, false}, {null, true}, {Boolean.FALSE, true}, {null, false}};
            for (Object[] arm : arms) {
                final Boolean key = (Boolean) arm[0];
                EngineFixes.setGateLoopWindowForTest((Boolean) arm[1]);
                final boolean on = key != null ? key : (Boolean) arm[1];
                final String what = "reach.gateWindow=" + key + " -D=" + arm[1];
                final Game g = kikiGame();
                final Player me = g.getPlayers().get(0);
                final RlSearch rs = seat(key);
                try {
                    ForcedWinSolver.loopDeadline(g, System.nanoTime() - 1L); // the previous ask's window: run out
                    final Long before = slot(g);
                    final long[] t0 = ForcedWinSolver.loopTally();
                    gateStep(rs, g, me);
                    final long[] t1 = ForcedWinSolver.loopTally();
                    Assert.assertEquals(slot(g), before, what + ": the slot holds what it held before the gate");
                    final long probes = t1[0] - t0[0], cut = t1[1] - t0[1], found = t1[2] - t0[2];
                    System.out.println("GATELOOPWINDOW " + what + " open=" + gateOpen(rs) + " probes=" + probes + " cut=" + cut
                            + " found=" + found);
                    if (on) {
                        Assert.assertTrue(probes > 0, what + ": the gate's own window: the partner is probed");
                        Assert.assertEquals(cut, 0L, what + ": no read cut");
                        Assert.assertTrue(found > 0, what + ": the copier and its untapper win");
                        Assert.assertTrue(gateOpen(rs), what + ": the gate opens on this decision");
                    } else {
                        Assert.assertEquals(probes, 0L, what + ": the stale window: nothing probed");
                        Assert.assertTrue(cut > 0, what + ": the read is cut");
                        Assert.assertFalse(gateOpen(rs), what + ": the gate stays shut");
                        // the decision then opens its own window (decide0 after the gate), and the next ask of the same
                        // turn and library size does not check again: shut until a draw or the next turn
                        ForcedWinSolver.loopWindow(g, System.nanoTime(), 8_000_000_000L);
                        final long[] t2 = ForcedWinSolver.loopTally();
                        gateStep(rs, g, me);
                        Assert.assertEquals(ForcedWinSolver.loopTally()[0] - t2[0], 0L, what + ": no check, so no probe");
                        Assert.assertFalse(gateOpen(rs), what + ": still shut in the same turn");
                    }
                } finally {
                    ForcedWinSolver.loopDeadline(g, 0L);
                    rs.close();
                }
            }
        } finally {
            EngineFixes.setGateLoopWindowForTest(was);
            WorkClock.install(prev);
        }
    }

    /** With no window in the slot (the game's first read), the gate's own window is not left behind either. */
    @Test
    public void anEmptySlotStaysEmpty() throws ReflectiveOperationException {
        final WorkClock.Clock prev = WorkClock.install(null);
        final Game g = kikiGame();
        final RlSearch rs = seat(Boolean.TRUE);
        try {
            ForcedWinSolver.loopDeadline(g, 0L);
            Assert.assertNull(slot(g));
            gateStep(rs, g, g.getPlayers().get(0));
            Assert.assertTrue(gateOpen(rs), "the gate's own window: the loop is found");
            Assert.assertNull(slot(g), "the slot is empty again");
        } finally {
            ForcedWinSolver.loopDeadline(g, 0L);
            rs.close();
            WorkClock.install(prev);
        }
    }

    @Test
    public void theSpecKeyTakesTrueFalseOrOneZeroAndNothingElse() {
        Assert.assertNull(RlSearch.ReachCfg.parse(JsonParser.parseString("{\"gate\":true}").getAsJsonObject()).gateWindow);
        for (String v : new String[] {"true", "1", "1.0"}) {
            Assert.assertEquals(RlSearch.ReachCfg.parse(JsonParser.parseString("{\"gateWindow\":" + v + "}").getAsJsonObject()).gateWindow,
                    Boolean.TRUE, v);
        }
        for (String v : new String[] {"false", "0"}) {
            Assert.assertEquals(RlSearch.ReachCfg.parse(JsonParser.parseString("{\"gateWindow\":" + v + "}").getAsJsonObject()).gateWindow,
                    Boolean.FALSE, v);
        }
        for (String v : new String[] {"2", "0.5", "\"yes\"", "\"true\""}) {
            try {
                RlSearch.ReachCfg.parse(JsonParser.parseString("{\"gateWindow\":" + v + "}").getAsJsonObject());
                Assert.fail("accepted gateWindow=" + v);
            } catch (IllegalArgumentException expected) {
                // true|false or 1|0
            }
        }
    }
}
