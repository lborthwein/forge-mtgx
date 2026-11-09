package forge.bench;

import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.CountedAiTime;
import forge.game.WorkClock;

/**
 * ai-timeout-1108: the counted allowance behind {@code -Dforge.countedTime} (Forge AI's evaluation budget in reference
 * work, not wall seconds). The key is read once per JVM; this suite runs with it off, so it checks (1) the allowance's
 * mechanics, which do not depend on the key, and (2) that with the key off every guarded limit is unchanged.
 */
public class CountedTimeTest {

    @Test
    public void keyOffChangesNothing() {
        Assert.assertFalse(WorkClock.COUNTED_TIME, "the suite runs with -Dforge.countedTime off");
        Assert.assertEquals(WorkClock.guardMs(16_000L), 16_000L);
        Assert.assertEquals(WorkClock.guardMs(0L), 0L);
        Assert.assertEquals(forge.ai.WallClock.readTimeoutMs(8000), 8000);
        Assert.assertNull(WorkClock.allowance());
        Assert.assertFalse(WorkClock.metering());
        final Runnable r = () -> { };
        Assert.assertSame(WorkClock.carried(r), r, "no clock and no allowance: the task itself");
        CountedAiTime.check("none"); // no allowance: a no-op
        Assert.assertFalse(CountedAiTime.spent());
    }

    @Test
    public void allowanceChargesWeightsNestsAndStaysSpent() {
        final long wPlay = WorkClock.weight(WorkClock.Site.AI_PLAY);
        final long wStep = WorkClock.weight(WorkClock.Site.STEP);
        final WorkClock.Allowance prev = WorkClock.openAllowance(3 * wPlay);
        try {
            final WorkClock.Allowance outer = WorkClock.allowance();
            Assert.assertTrue(WorkClock.metering());
            WorkClock.tick(WorkClock.Site.AI_PLAY, 2);
            Assert.assertEquals(outer.used(), 2 * wPlay, "a site is charged at its weight");
            final WorkClock.Allowance p2 = WorkClock.openAllowance(100 * wPlay);
            try {
                WorkClock.tick(WorkClock.Site.AI_PLAY); // outer at its limit: not yet spent
                CountedAiTime.check("at-limit");
                WorkClock.tick(WorkClock.Site.STEP); // engine site: charges, never throws
                Assert.assertTrue(outer.spent());
                Assert.assertFalse(WorkClock.allowance().ownSpent());
                Assert.assertTrue(CountedAiTime.spent(), "an inner allowance is spent when its outer one is");
                Assert.assertThrows(CountedAiTime.Spent.class, () -> CountedAiTime.check("over"));
            } finally {
                WorkClock.restoreAllowance(p2);
            }
            Assert.assertSame(WorkClock.allowance(), outer);
            Assert.assertEquals(outer.used(), 3 * wPlay + wStep);
            Assert.assertThrows(CountedAiTime.Spent.class, () -> CountedAiTime.check("sticky"));
        } finally {
            WorkClock.restoreAllowance(prev);
        }
        Assert.assertNull(WorkClock.allowance());
    }

    @Test
    public void carriedTakesTheAllowanceToTheEvalThread() throws Exception {
        final long w = WorkClock.weight(WorkClock.Site.AI_EVAL);
        final WorkClock.Allowance prev = WorkClock.openAllowance(10 * w);
        final WorkClock.Allowance mine = WorkClock.allowance();
        final AtomicReference<WorkClock.Allowance> seen = new AtomicReference<>();
        final Runnable task;
        try {
            task = WorkClock.carried(() -> {
                seen.set(WorkClock.allowance());
                WorkClock.tick(WorkClock.Site.AI_EVAL, 4);
            });
        } finally {
            WorkClock.restoreAllowance(prev);
        }
        Assert.assertNull(WorkClock.allowance(), "restored on the asking thread");
        final Thread t = new Thread(task, "Game AI Eval");
        t.start();
        t.join(10_000);
        Assert.assertSame(seen.get(), mine);
        Assert.assertEquals(mine.used(), 4 * w, "the eval thread's work is the asking thread's allowance's");
        Assert.assertFalse(mine.spent());
    }

    @Test
    public void allowanceIsAiTimeoutSecondsOfReferenceWork() {
        Assert.assertEquals(CountedAiTime.allowanceNs(5), 5_000_000_000L);
        Assert.assertEquals(CountedAiTime.allowanceNs(0), 1_000_000_000L, "at least one second");
    }
}
