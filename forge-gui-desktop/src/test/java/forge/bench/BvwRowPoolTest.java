package forge.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.HonestLabel;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlReplayLabeller;
import forge.bench.rl.RlWire;
import forge.bench.rl.StepSearch;

/**
 * Lane bvw-tail-fix-1027 (FLAG-3H): a game's band searches on a row pool ({@code label.search.rowThreads}) write the
 * inline labeller's rows bit for bit, in decision order, on RlLabelReplayTest's real replayed games against the test
 * service: the S2T labeller (T-sym with OPR and the smoke pairs, so the per-seat OPR row is chosen across row threads)
 * and the step-1 labeller (every S2T key off); {@code rowThreads} is a runtime key outside the search identity; and the
 * stall watchdog counts the row threads as the game's mutators. Run inside a broker test lease, cwd = forge-gui with
 * res/.
 */
public class BvwRowPoolTest {

    @BeforeClass
    public static void setUp() throws Exception {
        if (RlLabelReplayTest.tapes.isEmpty()) {
            RlLabelReplayTest.setUp();
        }
    }

    static JsonObject withRows(final JsonObject block, final int rowThreads) {
        final JsonObject b = block.deepCopy();
        if (rowThreads > 0) {
            b.addProperty("rowThreads", rowThreads);
        }
        return b;
    }

    /** Without wall times: a header's or index row's {@code ms}, {@code search.ms}, {@code meter.engine_ms}. */
    static JsonObject noClock(final JsonObject r) {
        final JsonObject x = r.deepCopy();
        x.remove("ms");
        if (x.has("search") && x.get("search").isJsonObject()) {
            x.getAsJsonObject("search").remove("ms");
        }
        if (x.has("meter") && x.get("meter").isJsonObject()) {
            x.getAsJsonObject("meter").remove("engine_ms");
        }
        for (String k : new String[] {"solve_ms", "frame_ms", "search_ms"}) {
            x.remove(k);
        }
        return x;
    }

    /**
     * What a labelled replay wrote, per game (by uid): its label records in file order (header without wall times |
     * payload bytes), its index rows in file order, its games row; and the replay rows.
     */
    static Map<String, List<String>> written(final Path dir) throws Exception {
        final Map<String, List<String>> out = new TreeMap<>();
        for (Object[] r : RlLabelReplayTest.records(dir)) {
            final JsonObject h = (JsonObject) r[0];
            out.computeIfAbsent("rec/" + h.get("game_uid").getAsString(), k -> new ArrayList<>()).add(
                    RlWire.canonicalString(noClock(h)) + "|" + Base64.getEncoder().encodeToString((byte[]) r[1]));
        }
        for (JsonObject r : RlLabelReplayTest.jsonl(dir, "index-")) {
            out.computeIfAbsent("idx/" + r.get("game_uid").getAsString(), k -> new ArrayList<>())
                    .add(RlWire.canonicalString(noClock(r)));
        }
        for (JsonObject g : RlLabelReplayTest.jsonl(dir, "games-")) {
            out.computeIfAbsent("game/" + g.get("game_uid").getAsString(), k -> new ArrayList<>())
                    .add(RlWire.canonicalString(noClock(g)));
        }
        for (JsonObject g : RlLabelReplayTest.jsonl(dir, "replay")) {
            final JsonObject x = g.deepCopy();
            for (String k : new String[] {"wall_ms", "cpu_ms", "cpu_other_ms", "label_solve_ms", "ms"}) {
                x.remove(k);
            }
            if (x.has("game_uid")) {
                out.computeIfAbsent("replay/" + x.get("game_uid").getAsString(), k -> new ArrayList<>())
                        .add(RlWire.canonicalString(x));
            }
        }
        return out;
    }

    /** Runs the labelled replay of {@code block} into a fresh directory; returns it and the row threads seen. */
    static Path replay(final String tag, final JsonObject block, final Set<String> rowThreadsSeen) throws Exception {
        final Path d = Files.createTempDirectory(RlLabelReplayTest.out, tag + "-");
        final RlActorBench.Cfg c = BvwStepLabelReplayTest.cfg(d, block);
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        final Thread watch = new Thread(() -> {
            while (!stop.get()) {
                for (Thread t : RlReplayLabeller.liveRowThreads()) {
                    synchronized (rowThreadsSeen) {
                        rowThreadsSeen.add(t.getName());
                    }
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "row-watch");
        watch.setDaemon(true);
        watch.start();
        try {
            Assert.assertEquals(RlActorBench.replay(c, RlLabelReplayTest.index, "test"), 0, tag);
        } finally {
            stop.set(true);
            watch.join();
        }
        for (JsonObject g : RlLabelReplayTest.jsonl(d, "games-")) {
            Assert.assertTrue(g.get("equal").getAsBoolean(), tag + ": digest of " + g.get("game_uid"));
            Assert.assertFalse(g.has("row_crash"), tag + ": " + g);
        }
        return d;
    }

    static void assertSame(final Map<String, List<String>> inline, final Map<String, List<String>> pool, final String tag) {
        Assert.assertEquals(pool.keySet(), inline.keySet(), tag + ": games / files");
        for (Map.Entry<String, List<String>> e : inline.entrySet()) {
            final List<String> p = pool.get(e.getKey());
            Assert.assertEquals(p.size(), e.getValue().size(), tag + ": " + e.getKey() + " row count");
            for (int i = 0; i < p.size(); i++) {
                Assert.assertEquals(p.get(i), e.getValue().get(i), tag + ": " + e.getKey() + " row " + i);
            }
        }
    }

    @Test(timeOut = 3600000)
    public void s2tRowsOnARowPoolAreTheInlineRowsBitForBit() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(BvwStepLabelReplayTest.SHA, 2)) {
            svc.leafValue = p -> (java.util.Arrays.hashCode(p) % 1000) / 1000.0;
            final JsonObject block = BvwS2tLabelReplayTest.block(svc.port(), true);
            final Set<String> none = new TreeSet<>(), four = new TreeSet<>(), three = new TreeSet<>();
            final Map<String, List<String>> inline = written(replay("s2t-inline", block, none));
            final Map<String, List<String>> pool4 = written(replay("s2t-pool4", withRows(block, 4), four));
            final Map<String, List<String>> pool3 = written(replay("s2t-pool3", withRows(block, 3), three));
            Assert.assertTrue(none.isEmpty(), "row threads without rowThreads: " + none);
            Assert.assertFalse(four.isEmpty(), "rowThreads 4 ran no row thread");
            for (String n : four) {
                Assert.assertTrue(n.matches("rlreplay-w0-row[1-4]"), n);
            }
            assertSame(inline, pool4, "rowThreads 4");
            assertSame(inline, pool3, "rowThreads 3");
            // the fixture exercises what the pool must keep: search targets and one OPR row per seat at most
            int rows = 0, targets = 0, opr = 0;
            for (Map.Entry<String, List<String>> e : inline.entrySet()) {
                if (!e.getKey().startsWith("idx/")) {
                    continue;
                }
                final Map<Integer, Integer> oprBySeat = new LinkedHashMap<>();
                for (String s : e.getValue()) {
                    final JsonObject r = JsonParser.parseString(s).getAsJsonObject();
                    rows++;
                    if ("search".equals(r.get("target_source").getAsString())) {
                        targets++;
                    }
                    if (r.getAsJsonObject("s2t").get("opr").isJsonObject()) {
                        opr++;
                        oprBySeat.merge(r.get("seat").getAsInt(), 1, Integer::sum);
                    }
                }
                for (int n : oprBySeat.values()) {
                    Assert.assertEquals(n, 1, e.getKey());
                }
            }
            System.err.println("[row-pool] s2t rows " + rows + " search targets " + targets + " OPR rows " + opr
                    + " row threads " + four + " / " + three);
            Assert.assertTrue(rows > 0 && targets > 0 && opr > 0, rows + " " + targets + " " + opr);
        }
    }

    @Test(timeOut = 3600000)
    public void stepOneRowsOnARowPoolAreTheInlineRowsBitForBit() throws Exception {
        try (FakeSearchService svc = new FakeSearchService(BvwStepLabelReplayTest.SHA, 2)) {
            svc.leafValue = p -> (java.util.Arrays.hashCode(p) % 1000) / 1000.0;
            final JsonObject block = BvwStepLabelReplayTest.search(svc.port());
            final Set<String> seen = new TreeSet<>();
            final Map<String, List<String>> inline = written(replay("step-inline", block, new TreeSet<>()));
            final Map<String, List<String>> pool = written(replay("step-pool4", withRows(block, 4), seen));
            Assert.assertFalse(seen.isEmpty(), "rowThreads 4 ran no row thread");
            assertSame(inline, pool, "step-1 block, rowThreads 4");
            int rows = 0;
            for (Map.Entry<String, List<String>> e : inline.entrySet()) {
                if (e.getKey().startsWith("idx/")) {
                    rows += e.getValue().size();
                }
            }
            Assert.assertTrue(rows > 0, "no rows");
        }
    }

    @Test
    public void rowThreadsIsARuntimeKeyOutsideTheIdentity() throws Exception {
        final HonestLabel.Spec label = HonestLabel.Spec.parse(BvwStepLabelReplayTest.counterLabel(
                RlLabelReplayTest.out));
        final JsonObject s2t = BvwS2tLabelReplayTest.block(1, false);
        final JsonObject step = BvwStepLabelReplayTest.search(1);
        for (JsonObject b : new JsonObject[] {s2t, step}) {
            final StepSearch.Spec a = StepSearch.Spec.parse(b, label, 0, 2);
            Assert.assertEquals(a.rowThreads(), 1, "absent = inline");
            for (int k : new int[] {1, 2, 8, StepSearch.Spec.MAX_ROW_THREADS}) {
                final StepSearch.Spec p = StepSearch.Spec.parse(withRows(b, k), label, 0, 2);
                Assert.assertEquals(p.rowThreads(), k);
                Assert.assertEquals(p.sha(), a.sha(), "rowThreads moved the search identity");
                Assert.assertEquals(RlWire.canonicalString(p.identity()), RlWire.canonicalString(a.identity()));
            }
            final JsonObject nul = b.deepCopy();
            nul.add("rowThreads", com.google.gson.JsonNull.INSTANCE);
            Assert.assertEquals(StepSearch.Spec.parse(nul, label, 0, 2).rowThreads(), 1);
            for (String bad : new String[] {"0", "-1", "65", "1.5", "\"4\"", "true", "[4]"}) {
                final JsonObject x = b.deepCopy();
                x.add("rowThreads", JsonParser.parseString(bad));
                try {
                    StepSearch.Spec.parse(x, label, 0, 2);
                    Assert.fail("rowThreads " + bad + " accepted");
                } catch (IllegalArgumentException e) {
                    Assert.assertTrue(e.getMessage().contains("rowThreads"), e.getMessage());
                }
            }
        }
        // the step-1 golden (BvwStepSearchSpecTest) and the S2T goldens are unchanged by construction: same identity
    }

    @Test
    public void theWatchdogCountsRowThreadsAsTheGamesMutators() throws Exception {
        final java.util.concurrent.CountDownLatch idle = new java.util.concurrent.CountDownLatch(1);
        final Thread game = new Thread(() -> {
            try {
                idle.await(); // a game thread waiting for its row threads
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "rlreplay-w0");
        final Thread row = ReplayStallWatchdogTest.spinner();
        game.start();
        row.start();
        try {
            final List<Thread> rows = Collections.singletonList(row);
            final ReplayStallWatchdog.JvmSampler with = new ReplayStallWatchdog.JvmSampler(List.of(game), () -> rows, 1);
            final ReplayStallWatchdog.JvmSampler without = new ReplayStallWatchdog.JvmSampler(List.of(game),
                    Collections::emptyList, 1);
            final ReplayStallWatchdog.JvmSampler twoCpus = new ReplayStallWatchdog.JvmSampler(List.of(game), () -> rows, 8);
            Assert.assertNull(with.sample());
            Assert.assertNull(without.sample());
            Assert.assertNull(twoCpus.sample());
            Thread.sleep(600);
            final ReplayStallWatchdog.Sample a = with.sample(), b = without.sample(), c = twoCpus.sample();
            Assert.assertEquals(a.workers, 2, a.toJson().toString());
            Assert.assertEquals(b.workers, 1, b.toJson().toString());
            Assert.assertEquals(c.workers, 2, c.toJson().toString());
            // the busy row thread is the game's progress: one CPU's worth of it is a share near 1, two threads' a half
            Assert.assertTrue(a.mutatorShare > 0.3, a.toJson().toString());
            Assert.assertTrue(b.mutatorShare < 0.1, "without row threads the waiting game thread alone: " + b.toJson());
            Assert.assertTrue(c.mutatorShare > 0.15 && c.mutatorShare <= 0.5 + 1e-9, c.toJson().toString());
            with.close();
            without.close();
            twoCpus.close();
        } finally {
            ReplayStallWatchdogTest.stop(row);
            idle.countDown();
            game.join();
        }
    }
}
