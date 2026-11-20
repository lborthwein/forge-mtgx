package forge.ai.simulation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.RlSearch;
import forge.game.WorkClock;

/**
 * wb-seqlanes-1120 ({@link LookaheadSearch.Config#seqLanes}): with a work clock, the look-ahead's play-outs keep the pooled
 * path's fixed virtual lanes but run them one after another on the deciding thread. (a) Repeats are bit-identical: the
 * same work per task, per lane, on the critical path and in all. (b) Budgets bind on per-lane clocks: every task's cut,
 * the span and the counts equal the pooled path's (tasks here share nothing, so the pooled path is exact too), and the
 * span is the longest lane's, not the sum that threads 1 (no lanes) charges to one clock. Off is unchanged: no key, no
 * record, a pool with threads > 1 and today's threads-1 path.
 */
public class SeqLanesTest {

    private static final int TASKS = 12, LANES = 4;
    /** Each task's own work cap, in main-loop steps; lane i gets tasks i, i+4, i+8 (task i on lane i mod 4). */
    private static final int[] CAP = {40, 5, 90, 10, 70, 15, 20, 60, 30, 25, 80, 35};
    /** The decision's budget, in main-loop steps of work on a lane clock. */
    private static final int BUDGET_STEPS = 120;

    @AfterMethod
    public void clear() {
        WorkClock.install(null);
        WorkClock.setWeights(null);
    }

    /** One searched decision's play-outs: what each task got done, which thread ran it, and the deciding clock after. */
    private static final class Run {
        final int[] done = new int[TASKS];
        final String[] thread = new String[TASKS];
        long span;
        /** The play-outs' weighted work in all (every lane, or the one clock), reference ns = virtual ns at scale 1. */
        long playWork;
        long[] counts, path;
    }

    private static Run run(final boolean seqLanes, final int threads) throws Exception {
        final LookaheadSearch.Config cfg = new LookaheadSearch.Config();
        cfg.threads = threads;
        cfg.seqLanes = seqLanes;
        final LookaheadSearch ls = new LookaheadSearch(cfg);
        try {
            final WorkClock.Clock c = WorkClock.newClock(1.0);
            WorkClock.install(c);
            WorkClock.tick(WorkClock.Site.STEP, 3); // the decision's own work before the play-outs
            final long now = WorkClock.nanoTime();
            final long step = WorkClock.weights()[WorkClock.Site.STEP.ordinal()];
            final LookaheadSearch.Budget b = new LookaheadSearch.Budget(now + BUDGET_STEPS * step);
            final Run out = new Run();
            final List<Runnable> tasks = new ArrayList<>();
            for (int k = 0; k < TASKS; k++) {
                final int kk = k;
                tasks.add(() -> {
                    out.thread[kk] = Thread.currentThread().getName();
                    int n = 0;
                    while (n < CAP[kk] && !b.over()) {
                        WorkClock.tick(WorkClock.Site.STEP);
                        if (n % 7 == 0) {
                            WorkClock.tick(WorkClock.Site.SBA);
                        }
                        n++;
                    }
                    out.done[kk] = n;
                });
            }
            final Method m = LookaheadSearch.class.getDeclaredMethod("runAll", List.class, LookaheadSearch.Budget.class);
            m.setAccessible(true);
            Assert.assertEquals(m.invoke(ls, tasks, null), Boolean.TRUE);
            out.span = WorkClock.nanoTime() - now;
            out.playWork = c.totalWork() - 3 * step;
            out.counts = c.counts();
            out.path = c.pathCounts();
            return out;
        } finally {
            ls.shutdown();
            WorkClock.install(null);
        }
    }

    /** The cuts the tasks' loop gets on {@code lanes} lane clocks (task i on lane i mod lanes), worked out by hand. */
    private static int[] expected(final int lanes) {
        final long[] w = WorkClock.weights();
        final long step = w[WorkClock.Site.STEP.ordinal()], sba = w[WorkClock.Site.SBA.ordinal()];
        final long deadline = BUDGET_STEPS * step;
        final long[] t = new long[lanes];
        final int[] done = new int[TASKS];
        for (int k = 0; k < TASKS; k++) {
            final int l = k % lanes;
            int n = 0;
            while (n < CAP[k] && !(t[l] - deadline > 0)) {
                t[l] += step;
                if (n % 7 == 0) {
                    t[l] += sba;
                }
                n++;
            }
            done[k] = n;
        }
        return done;
    }

    @Test
    public void repeatsAreBitIdentical() throws Exception {
        final Run first = run(true, LANES);
        for (int r = 0; r < 5; r++) {
            final Run again = run(true, LANES);
            Assert.assertEquals(again.done, first.done, "every task's work, run " + r);
            Assert.assertEquals(again.span, first.span, "the span, run " + r);
            Assert.assertEquals(again.counts, first.counts, "the counts, run " + r);
            Assert.assertEquals(again.path, first.path, "the critical-path counts, run " + r);
        }
        final String me = Thread.currentThread().getName();
        for (String t : first.thread) {
            Assert.assertEquals(t, me, "seqLanes runs every play-out on the deciding thread (no pool)");
        }
    }

    @Test
    public void taskIndexOrderEachOnItsLaneClock() throws Exception {
        // SL': the tasks run in index order (0, 1, 2, ...), task k under lane (k mod L)'s clock, which starts at the
        // decision's virtual now and has advanced only by that lane's earlier tasks (k - L, k - 2L, ...)
        final LookaheadSearch.Config cfg = new LookaheadSearch.Config();
        cfg.threads = LANES;
        cfg.seqLanes = true;
        final LookaheadSearch ls = new LookaheadSearch(cfg);
        try {
            final WorkClock.Clock c = WorkClock.newClock(1.0);
            WorkClock.install(c);
            WorkClock.tick(WorkClock.Site.STEP, 3);
            final long now = WorkClock.nanoTime();
            final long step = WorkClock.weights()[WorkClock.Site.STEP.ordinal()];
            final List<Integer> order = new ArrayList<>();
            final long[] start = new long[TASKS];
            final List<Runnable> tasks = new ArrayList<>();
            for (int k = 0; k < TASKS; k++) {
                final int kk = k;
                tasks.add(() -> {
                    order.add(kk);
                    start[kk] = WorkClock.nanoTime() - now;
                    WorkClock.tick(WorkClock.Site.STEP, CAP[kk]);
                });
            }
            final Method m = LookaheadSearch.class.getDeclaredMethod("runAll", List.class, LookaheadSearch.Budget.class);
            m.setAccessible(true);
            Assert.assertEquals(m.invoke(ls, tasks, null), Boolean.TRUE);
            final List<Integer> want = new ArrayList<>();
            final long[] lane = new long[LANES];
            long longest = 0;
            for (int k = 0; k < TASKS; k++) {
                want.add(k);
                Assert.assertEquals(start[k], lane[k % LANES], "task " + k + " starts at its lane's own time");
                lane[k % LANES] += CAP[k] * step;
                longest = Math.max(longest, lane[k % LANES]);
            }
            Assert.assertEquals(order, want, "task-index order");
            Assert.assertEquals(WorkClock.nanoTime() - now, longest, "the join ends at the longest lane");
        } finally {
            ls.shutdown();
            WorkClock.install(null);
        }
    }

    @Test
    public void budgetsBindOnLaneClocksAsPooled() throws Exception {
        final Run seq = run(true, LANES);
        final Run pooled = run(false, LANES);
        final Run one = run(false, 1);
        Assert.assertTrue(pooled.thread[0].startsWith("Game-lookahead-"), "the pooled path runs on the pool");
        Assert.assertEquals(seq.done, pooled.done, "each play-out is cut where the pooled path cuts it");
        Assert.assertEquals(seq.span, pooled.span, "the span is the pooled span");
        Assert.assertEquals(seq.counts, pooled.counts);
        Assert.assertEquals(seq.path, pooled.path);
        // the budget binds per lane: each lane's tasks, in order, on that lane's own clock (task i on lane i mod 4)
        Assert.assertEquals(seq.done, expected(LANES), "every cut is where a per-lane clock puts it");
        // threads 1 has no lanes: one clock, so the budget binds on the sum, far earlier, and the span is that sum
        Assert.assertEquals(one.done, expected(1), "threads 1: every cut is where one clock puts it");
        Assert.assertNotEquals(one.done, seq.done, "threads 1 cuts on the total, not per lane");
        Assert.assertTrue(Arrays.stream(seq.done).sum() > Arrays.stream(one.done).sum(),
                "per-lane budgets let the lanes do more work in all than one clock does");
        // the span: one clock's span is all its play-out work; the lanes' span is the longest lane, far below their sum
        Assert.assertEquals(one.span, one.playWork, "threads 1: the span is the sum of the play-outs' work");
        Assert.assertTrue(seq.span * 2 < seq.playWork, "seqLanes: the span " + seq.span + " is one lane's, not the sum "
                + seq.playWork);
        Assert.assertEquals(seq.playWork, pooled.playWork);
    }

    @Test
    public void offIsTodaysPath() throws Exception {
        final LookaheadSearch.Config cfg = new LookaheadSearch.Config();
        Assert.assertFalse(cfg.seqLanes, "off by default");
        Assert.assertFalse(cfg.toJson().has("seqLanes"), "off: unrecorded");
        cfg.seqLanes = true;
        Assert.assertTrue(cfg.toJson().get("seqLanes").getAsBoolean());
        // threads 1 without the key: today's path (in order on the deciding clock, no lanes)
        final Run one = run(false, 1);
        final Run oneAgain = run(false, 1);
        Assert.assertEquals(one.done, oneAgain.done);
        Assert.assertEquals(one.span, oneAgain.span);
        // seqLanes without a work clock: the tasks run in order on the deciding thread, as threads 1
        final LookaheadSearch.Config c2 = new LookaheadSearch.Config();
        c2.threads = LANES;
        c2.seqLanes = true;
        final LookaheadSearch ls = new LookaheadSearch(c2);
        try {
            final List<String> order = new ArrayList<>();
            final List<Runnable> tasks = new ArrayList<>();
            for (int k = 0; k < 6; k++) {
                final int kk = k;
                tasks.add(() -> order.add(kk + "@" + Thread.currentThread().getName()));
            }
            final Method m = LookaheadSearch.class.getDeclaredMethod("runAll", List.class, LookaheadSearch.Budget.class);
            m.setAccessible(true);
            m.invoke(ls, tasks, null);
            final String me = Thread.currentThread().getName();
            Assert.assertEquals(order, Arrays.asList("0@" + me, "1@" + me, "2@" + me, "3@" + me, "4@" + me, "5@" + me));
        } finally {
            ls.shutdown();
        }
    }

    private static JsonObject spec(final String extra) {
        return JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"budgetMs\":8000" + extra + "}").getAsJsonObject();
    }

    @Test
    public void specKey() {
        final RlSearch.Config off = RlSearch.Config.parse(spec(",\"workUnits\":true"));
        Assert.assertFalse(off.seqLanes, "off by default");
        Assert.assertFalse(off.toJson().has("seqLanes"), "off: unrecorded, so every other spec's JSON is unchanged");
        final RlSearch.Config on = RlSearch.Config.parse(spec(",\"workUnits\":true,\"seqLanes\":true"));
        Assert.assertTrue(on.seqLanes);
        Assert.assertTrue(on.toJson().get("seqLanes").getAsBoolean());
        final JsonObject rec = on.toJson();
        rec.addProperty("server", "127.0.0.1:1");
        Assert.assertTrue(RlSearch.Config.parse(rec).seqLanes, "the recorded spec parses back");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(spec(",\"seqLanes\":true")));
    }
}
