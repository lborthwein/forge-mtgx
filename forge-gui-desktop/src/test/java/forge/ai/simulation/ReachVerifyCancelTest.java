package forge.ai.simulation;

import java.util.HashMap;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.VerifyCancel;
import forge.game.Game;
import forge.game.player.Player;

/**
 * reach-verify-1114: the engine check of a planner reach line (reach.verify). A plain check confirms a one-step kill; a
 * check whose look-ahead world was cancelled (its thread interrupted) stops at once, counts nothing and keeps the
 * interrupt; the cancel mark is set only inside a check.
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

    @Test(timeOut = 120000)
    public void aPlainCheckConfirmsTheLine() {
        Assert.assertFalse(VerifyCancel.active());
        final ForcedWinSolver.VerifiedReach vr = verify(boltForTheWin());
        Assert.assertTrue(vr.reach.direct, "the planner sees the Bolt kill: " + vr.claims.size());
        Assert.assertEquals(vr.reach.p, 1.0);
        Assert.assertEquals(vr.pv, 1.0, "the engine confirms it");
        Assert.assertEquals(vr.claims.get(0).sampled, "won");
        Assert.assertFalse(VerifyCancel.active(), "the mark is cleared after the check");
    }

    @Test(timeOut = 120000)
    public void aCancelledCheckStopsAtOnceAndKeepsTheInterrupt() throws Exception {
        final Game g = boltForTheWin();
        final ForcedWinSolver.VerifiedReach[] out = new ForcedWinSolver.VerifiedReach[1];
        final boolean[] stillInterrupted = new boolean[1];
        final long[] wallNs = new long[1];
        final Thread t = new Thread(() -> {
            Thread.currentThread().interrupt(); // the look-ahead cancelled this world
            final long t0 = System.nanoTime();
            out[0] = verify(g);
            wallNs[0] = System.nanoTime() - t0;
            stillInterrupted[0] = Thread.currentThread().isInterrupted();
        }, "rv-cancel");
        t.start();
        t.join(60000);
        Assert.assertFalse(t.isAlive());
        final ForcedWinSolver.VerifiedReach vr = out[0];
        Assert.assertNotNull(vr);
        Assert.assertTrue(vr.reach.direct, "the claim itself is unchanged");
        Assert.assertEquals(vr.pv, 0.0, "a cancelled check confirms nothing");
        Assert.assertEquals(vr.claims.get(0).sampled, "cancelled");
        Assert.assertTrue(vr.checkNanos < 100_000_000L, "the cancelled checks took " + vr.checkNanos / 1e6 + " ms");
        Assert.assertTrue(stillInterrupted[0], "the interrupt is kept for the look-ahead");
        Assert.assertTrue(wallNs[0] > 0);
    }

    @Test(timeOut = 120000)
    public void anInterruptMidCheckStopsWithinAbout100Ms() throws Exception {
        // the play-out's own step loop and the AI eval wait both honour the interrupt while the mark is set
        final Game g = boltForTheWin();
        final long[] done = new long[1];
        final Thread t = new Thread(() -> {
            final boolean prev = VerifyCancel.set(true);
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    verify(g); // repeated checks, as a busy world would run them
                }
                done[0] = System.nanoTime();
            } finally {
                VerifyCancel.set(prev);
            }
        }, "rv-mid");
        t.start();
        Thread.sleep(300);
        final long t0 = System.nanoTime();
        t.interrupt();
        t.join(5000);
        Assert.assertFalse(t.isAlive(), "the check did not stop");
        Assert.assertTrue(done[0] - t0 < 150_000_000L, "stopped " + (done[0] - t0) / 1e6 + " ms after the interrupt");
    }
}
