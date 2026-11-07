package forge.bench;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.RlSearch;
import forge.game.WorkClock;

/**
 * Lane work-units-1107 (ARCH-APPENDIX-1104 A3): the work clock. Off (no clock installed) every read is the host's clock
 * and a tick changes nothing; on, virtual time is the weighted work over the scale, lanes start at the decision's
 * virtual now and the join moves it to the latest lane's end, and past the real-time safety wall every deadline has
 * passed (counted as a hit). The search spec's keys parse, are refused alone, and are unrecorded when off.
 */
public class WorkUnitsTest {

    @AfterMethod
    public void clear() {
        WorkClock.install(null);
        WorkClock.setWeights(null);
    }

    @Test
    public void offIsTheHostClockAndTicksAreNoOps() {
        WorkClock.install(null);
        Assert.assertFalse(WorkClock.active());
        final long a = System.nanoTime();
        WorkClock.tick(WorkClock.Site.COPY, 1000);
        final long v = WorkClock.nanoTime();
        final long b = System.nanoTime();
        Assert.assertTrue(v >= a && v <= b, "off: nanoTime is System.nanoTime");
        Assert.assertNull(WorkClock.begin(8000), "off: no span");
        WorkClock.end(null);
        final Runnable r = () -> { };
        Assert.assertSame(WorkClock.carried(r), r, "off: carried is the task itself");
    }

    @Test
    public void virtualTimeIsWeightedWorkOverScale() throws Exception {
        final long[] w = WorkClock.weights();
        final WorkClock.Clock c = WorkClock.newClock(2.0);
        WorkClock.install(c);
        final long t0 = WorkClock.nanoTime();
        Thread.sleep(20); // real time passes, virtual time does not
        Assert.assertEquals(WorkClock.nanoTime(), t0);
        WorkClock.tick(WorkClock.Site.STEP, 10);
        WorkClock.tick(WorkClock.Site.COPY_CARD, 3);
        final long work = 10 * w[WorkClock.Site.STEP.ordinal()] + 3 * w[WorkClock.Site.COPY_CARD.ordinal()];
        Assert.assertEquals(WorkClock.nanoTime() - t0, (long) (work / 2.0));
        Assert.assertEquals(c.totalWork(), work);
        Assert.assertEquals(c.counts()[WorkClock.Site.STEP.ordinal()], 10);
    }

    @Test
    public void lanesStartAtNowAndJoinAtTheLatestEnd() throws Exception {
        final long[] w = WorkClock.weights();
        final WorkClock.Clock c = WorkClock.newClock(1.0);
        WorkClock.install(c);
        WorkClock.tick(WorkClock.Site.STEP, 5);
        final long now = WorkClock.nanoTime();
        final WorkClock.Clock[] lanes = {c.fork(), c.fork(), c.fork()};
        final int[] steps = {7, 30, 2};
        final Thread[] th = new Thread[3];
        for (int i = 0; i < 3; i++) {
            final int ii = i;
            th[i] = new Thread(() -> {
                WorkClock.install(lanes[ii]);
                Assert.assertEquals(WorkClock.nanoTime(), now, "a lane starts at the decision's virtual now");
                WorkClock.tick(WorkClock.Site.STEP, steps[ii]);
            });
            th[i].start();
        }
        for (Thread t : th) {
            t.join();
        }
        c.join(lanes);
        final long step = w[WorkClock.Site.STEP.ordinal()];
        Assert.assertEquals(WorkClock.nanoTime() - now, 30 * step, "the span is the longest lane");
        Assert.assertEquals(c.counts()[WorkClock.Site.STEP.ordinal()], 5 + 7 + 30 + 2, "every lane's work is metered");
    }

    @Test
    public void safetyWallPassesEveryDeadlineAndCounts() throws Exception {
        final WorkClock.Clock c = WorkClock.newClock(1.0);
        WorkClock.install(c);
        final WorkClock.Span s = WorkClock.begin(30);
        final long deadline = WorkClock.nanoTime() + 1_000_000_000_000L;
        Assert.assertTrue(WorkClock.nanoTime() < deadline);
        Thread.sleep(60);
        Assert.assertTrue(WorkClock.nanoTime() > deadline, "past the safety wall, every deadline has passed");
        Assert.assertTrue(s.wallHit());
        WorkClock.end(s);
        Assert.assertEquals(c.wallHits.get(), 1L);
        Assert.assertTrue(WorkClock.nanoTime() < deadline, "the wall is disarmed at the end");
        final WorkClock.Span inner = WorkClock.begin(5000);
        final WorkClock.Span nested = WorkClock.begin(1);
        Thread.sleep(10);
        Assert.assertFalse(nested.wallHit() || WorkClock.nanoTime() > deadline, "a nested begin keeps the outer wall");
        WorkClock.end(nested);
        WorkClock.end(inner);
    }

    @Test
    public void carriedRunsUnderTheCallersClock() throws Exception {
        final WorkClock.Clock c = WorkClock.newClock(1.0);
        WorkClock.install(c);
        final Thread t = new Thread(WorkClock.carried(() -> WorkClock.tick(WorkClock.Site.AI_PLAY, 4)));
        t.start();
        t.join();
        Assert.assertEquals(c.counts()[WorkClock.Site.AI_PLAY.ordinal()], 4);
    }

    @Test
    public void weightsParse() {
        WorkClock.setWeights(null);
        final long[] d = WorkClock.weights();
        final long[] w = WorkClock.parseWeights("mainLoopStep:7,copyCard:9");
        Assert.assertEquals(w[WorkClock.Site.STEP.ordinal()], 7);
        Assert.assertEquals(w[WorkClock.Site.COPY_CARD.ordinal()], 9);
        Assert.assertEquals(w[WorkClock.Site.COPY.ordinal()], d[WorkClock.Site.COPY.ordinal()]);
        Assert.assertThrows(IllegalArgumentException.class, () -> WorkClock.parseWeights("nope:1"));
    }

    private static JsonObject spec(final String extra) {
        return JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"budgetMs\":8000" + extra + "}").getAsJsonObject();
    }

    @Test
    public void specKeys() {
        final RlSearch.Config off = RlSearch.Config.parse(spec(""));
        Assert.assertFalse(off.workUnits);
        Assert.assertFalse(off.toJson().has("workUnits"), "off: unrecorded");
        Assert.assertFalse(off.toJson().has("workScale"));
        final RlSearch.Config on = RlSearch.Config.parse(spec(",\"workUnits\":true,\"workScale\":0.8,\"workWallMs\":7500"));
        Assert.assertTrue(on.workUnits);
        Assert.assertEquals(on.workScale, 0.8);
        Assert.assertEquals(on.workWallMs, 7500L);
        Assert.assertTrue(on.toJson().get("workUnits").getAsBoolean());
        final JsonObject rec = on.toJson();
        rec.addProperty("server", "127.0.0.1:1"); // the recorded spec leaves the host out
        Assert.assertEquals(RlSearch.Config.parse(rec).workScale, 0.8, "the recorded spec parses back");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlSearch.Config.parse(spec(",\"workScale\":0.8")));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlSearch.Config.parse(spec(",\"workUnits\":true,\"workScale\":0")));
    }
}
