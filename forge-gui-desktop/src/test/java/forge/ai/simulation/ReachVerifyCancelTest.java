package forge.ai.simulation;

import java.util.HashMap;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * reach-verify-1115: the engine check of a planner reach line (reach.verify) respects the play-out's decision budget. A
 * plain check confirms a one-step kill; a check whose decision is out of time (past its deadline less the margin, or
 * stopped by the hard wall) runs no play-out and counts the claim as unconfirmed; a budget that runs out while checks are
 * running stops them at once; the wall of a line check never passes the decision's deadline. No thread is interrupted.
 */
public class ReachVerifyCancelTest extends AITest {

    private static Game boltForTheWin() {
        // our main phase, Lightning Bolt and a Mountain, the opponent at 3 life
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                ForcedWinSolverTest.frame("Lightning Bolt", "Mountain", 20, "", "Island", 3, "human"));
    }

    private static ForcedWinSolver.VerifiedReach verify(final Game g) {
        final Player me = g.getPlayers().get(0);
        final Map<String, String> props = new HashMap<>();
        final ForcedWinSolver s = ForcedWinSolver.withReader(me, ForcedWinSolver.Config.fromProps(props::get),
                ForcedWinSolver.newSharedReader());
        final ForcedWinSolver.ReachOpts o = new ForcedWinSolver.ReachOpts();
        o.ms = 5000; // node caps bind, not the clock
        return s.reachVerified(g, null, o, ForcedWinSolver.VERIFY_SAMPLED, ForcedWinSolver.VERIFY_SAMPLED, 3, true, false, 7L);
    }

    private static ForcedWinSolver.VerifiedReach verifyUnder(final Game g, final LookaheadSearch.Budget b) {
        final LookaheadSearch.Budget prev = ForcedWinSolver.VERIFY_BUDGET.get();
        ForcedWinSolver.VERIFY_BUDGET.set(b);
        try {
            return verify(g);
        } finally {
            ForcedWinSolver.VERIFY_BUDGET.set(prev);
        }
    }

    @Test(timeOut = 120000)
    public void aPlainCheckConfirmsTheLine() {
        final ForcedWinSolver.VerifiedReach vr = verify(boltForTheWin());
        Assert.assertTrue(vr.reach.direct, "the planner sees the Bolt kill");
        Assert.assertEquals(vr.reach.p, 1.0);
        Assert.assertEquals(vr.pv, 1.0, "the engine confirms it");
        Assert.assertEquals(vr.claims.get(0).sampled, "won");
        // with time to spare the decision budget changes nothing
        final ForcedWinSolver.VerifiedReach roomy = verifyUnder(boltForTheWin(),
                new LookaheadSearch.Budget(forge.game.WorkClock.nanoTime() + 60_000_000_000L));
        Assert.assertEquals(roomy.pv, 1.0);
        Assert.assertNull(ForcedWinSolver.VERIFY_BUDGET.get(), "the budget is cleared after the check");
    }

    @Test(timeOut = 120000)
    public void aDecisionOutOfTimeCountsTheClaimUnconfirmedAtOnce() {
        final Game g = boltForTheWin();
        // within the margin of its deadline
        final LookaheadSearch.Budget near = new LookaheadSearch.Budget(forge.game.WorkClock.nanoTime() + 100_000_000L);
        final ForcedWinSolver.VerifiedReach vr = verifyUnder(g, near);
        Assert.assertTrue(vr.reach.direct, "the claim itself is unchanged");
        Assert.assertEquals(vr.pv, 0.0, "an unchecked claim confirms nothing");
        Assert.assertEquals(vr.claims.get(0).sampled, "deadline");
        Assert.assertTrue(vr.checkNanos < 100_000_000L, "the skipped checks took " + vr.checkNanos / 1e6 + " ms");
        Assert.assertFalse(Thread.currentThread().isInterrupted(), "no interrupt is involved");
        // stopped by the hard wall, deadline far away
        final LookaheadSearch.Budget stopped = new LookaheadSearch.Budget(forge.game.WorkClock.nanoTime() + 60_000_000_000L);
        stopped.stop();
        final ForcedWinSolver.VerifiedReach vs = verifyUnder(g, stopped);
        Assert.assertEquals(vs.pv, 0.0);
        Assert.assertEquals(vs.claims.get(0).sampled, "deadline");
    }

    @Test(timeOut = 120000)
    public void theLineWallNeverPassesTheDecisionDeadline() {
        final long now = forge.game.WorkClock.nanoTime();
        ForcedWinSolver.VERIFY_BUDGET.set(new LookaheadSearch.Budget(now + 1_000_000_000L));
        try {
            final long w = ForcedWinSolver.verifyWall();
            Assert.assertTrue(w <= now + 1_000_000_000L - ForcedWinSolver.VERIFY_DEADLINE_MARGIN_NS + 50_000_000L,
                    "wall " + (w - now) / 1e6 + " ms");
            Assert.assertTrue(w - now < ForcedWinSolver.VERIFY_WALL_NS);
        } finally {
            ForcedWinSolver.VERIFY_BUDGET.remove();
        }
        Assert.assertTrue(ForcedWinSolver.verifyWall() - forge.game.WorkClock.nanoTime() > 4_000_000_000L,
                "without a budget the 5 s wall applies");
    }

    @Test(timeOut = 120000)
    public void aWallStopWhileChecksRunStopsThemWithin150Ms() throws Exception {
        final Game g = boltForTheWin();
        final LookaheadSearch.Budget b = new LookaheadSearch.Budget(forge.game.WorkClock.nanoTime() + 60_000_000_000L);
        final long[] done = new long[1];
        final int[] after = new int[1];
        final Thread t = new Thread(() -> {
            ForcedWinSolver.VERIFY_BUDGET.set(b);
            try {
                while (true) {
                    final ForcedWinSolver.VerifiedReach vr = verify(g); // repeated checks, as a busy decision would run them
                    if ("deadline".equals(vr.claims.get(0).sampled)) {
                        after[0]++;
                        break;
                    }
                }
                done[0] = System.nanoTime();
            } finally {
                ForcedWinSolver.VERIFY_BUDGET.remove();
            }
        }, "rv-wall");
        t.start();
        Thread.sleep(300);
        final long t0 = System.nanoTime();
        b.stop(); // the look-ahead's hard wall
        t.join(5000);
        Assert.assertFalse(t.isAlive(), "the checks did not stop");
        Assert.assertEquals(after[0], 1);
        Assert.assertTrue(done[0] - t0 < 150_000_000L, "stopped " + (done[0] - t0) / 1e6 + " ms after the wall");
    }
}
