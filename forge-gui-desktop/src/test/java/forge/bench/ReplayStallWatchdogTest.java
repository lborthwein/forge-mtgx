package forge.bench;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.RlReplayLabeller;
import forge.bench.rl.SinkTestAccess;

/**
 * Lane lki-fix-port-1027: the replay stall watchdog's trigger, fed injected samples (no game, no GC needed), and its
 * stall output. The thresholds are the defaults: gc >= 0.5, mutator <= 0.25, old gen after GC >= 0.85, 10 in a row.
 */
public class ReplayStallWatchdogTest {

    /** The stuck step-1 JVM's shape (m-00039: GC threads busy ~60 % of the wall, the heap full after GC). */
    static ReplayStallWatchdog.Sample starved() {
        return new ReplayStallWatchdog.Sample(60, 0.80, 0.08, 0.97, 4, 40);
    }

    static ReplayStallWatchdog.Sample healthy() {
        return new ReplayStallWatchdog.Sample(60, 0.03, 0.95, 0.20, 4, 12);
    }

    static ReplayStallWatchdog.Sample sample(final double gc, final double mutator, final double old) {
        return new ReplayStallWatchdog.Sample(60, gc, mutator, old, 4, 10);
    }

    @Test
    public void firesOnTheTenthConsecutiveStarvedSample() {
        final ReplayStallWatchdog.Judge j = new ReplayStallWatchdog.Judge(new ReplayStallWatchdog.Thresholds());
        for (int i = 1; i <= 9; i++) {
            Assert.assertFalse(j.offer(starved()), "sample " + i);
            Assert.assertEquals(j.run(), i);
        }
        Assert.assertTrue(j.offer(starved()));
    }

    @Test
    public void anyOtherSampleRestartsTheRun() {
        final ReplayStallWatchdog.Judge j = new ReplayStallWatchdog.Judge(new ReplayStallWatchdog.Thresholds());
        for (ReplayStallWatchdog.Sample breaker : new ReplayStallWatchdog.Sample[] {healthy(), sample(0.80, 0.08, Double.NaN),
                new ReplayStallWatchdog.Sample(60, 0.80, Double.NaN, 0.97, 0, 40), sample(0.49, 0.08, 0.97)}) {
            for (int i = 0; i < 9; i++) {
                Assert.assertFalse(j.offer(starved()));
            }
            Assert.assertFalse(j.offer(breaker));
            Assert.assertEquals(j.run(), 0);
        }
        for (int i = 0; i < 9; i++) {
            Assert.assertFalse(j.offer(starved()));
        }
        Assert.assertTrue(j.offer(starved()));
    }

    @Test
    public void noSignalAloneIsAStall() {
        final ReplayStallWatchdog.Judge j = new ReplayStallWatchdog.Judge(new ReplayStallWatchdog.Thresholds());
        final ReplayStallWatchdog.Sample[] alone = {
            sample(0.90, 0.60, 0.97), // collecting a lot, but the game threads still run
            sample(0.02, 0.03, 0.30), // game threads idle (a slow value service, a loaded box): no GC pressure
            sample(0.10, 0.10, 0.95), // a full heap that GC is not fighting
            sample(0.90, 0.05, 0.60), // GC busy and threads starved, but collecting frees the heap
            sample(0.90, 0.05, Double.NaN), // no GC notification in the interval
            new ReplayStallWatchdog.Sample(60, 0.90, Double.NaN, 0.97, 0, 40), // no live game thread
        };
        for (ReplayStallWatchdog.Sample s : alone) {
            Assert.assertFalse(j.starved(s), s.toJson().toString());
            for (int i = 0; i < 50; i++) {
                Assert.assertFalse(j.offer(s), s.toJson().toString());
            }
        }
    }

    @Test
    public void thresholdsAreInclusive() {
        final ReplayStallWatchdog.Judge j = new ReplayStallWatchdog.Judge(new ReplayStallWatchdog.Thresholds());
        Assert.assertTrue(j.starved(sample(0.50, 0.25, 0.85)));
        Assert.assertFalse(j.starved(sample(0.4999, 0.25, 0.85)));
        Assert.assertFalse(j.starved(sample(0.50, 0.2501, 0.85)));
        Assert.assertFalse(j.starved(sample(0.50, 0.25, 0.8499)));
    }

    @Test
    public void notAWallCap() {
        // a game that runs for days on a slow but healthy JVM is never voided: time alone is not a signal
        final ReplayStallWatchdog.Judge j = new ReplayStallWatchdog.Judge(new ReplayStallWatchdog.Thresholds());
        for (int i = 0; i < 100_000; i++) {
            Assert.assertFalse(j.offer(i % 2 == 0 ? healthy() : sample(0.05, 0.10, 0.50)));
        }
    }

    @Test
    public void configParsesAndRefuses() {
        Assert.assertTrue(ReplayStallWatchdog.Thresholds.parse(null).enabled);
        Assert.assertFalse(ReplayStallWatchdog.Thresholds.parse(JsonParser.parseString("false")).enabled);
        final ReplayStallWatchdog.Thresholds t = ReplayStallWatchdog.Thresholds.parse(JsonParser.parseString(
                "{\"intervalSec\": 30, \"consecutive\": 4, \"gcFractionMin\": 0.6, \"mutatorShareMax\": 0.2, "
                        + "\"oldAfterGcMin\": 0.9, \"dir\": \"/x\"}"));
        Assert.assertEquals(t.intervalSec, 30);
        Assert.assertEquals(t.consecutive, 4);
        Assert.assertEquals(t.gcFractionMin, 0.6, 1e-12);
        Assert.assertEquals(t.mutatorShareMax, 0.2, 1e-12);
        Assert.assertEquals(t.oldAfterGcMin, 0.9, 1e-12);
        Assert.assertEquals(t.dir, "/x");
        Assert.assertThrows(IllegalArgumentException.class,
                () -> ReplayStallWatchdog.Thresholds.parse(JsonParser.parseString("{\"wallCapSec\": 3600}")));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> ReplayStallWatchdog.Thresholds.parse(JsonParser.parseString("{\"consecutive\": 0}")));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> ReplayStallWatchdog.Thresholds.parse(JsonParser.parseString("{\"oldAfterGcMin\": 1.5}")));
        final RlActorBench.Cfg c = RlActorBench.parse(JsonParser.parseString("{\"mode\": \"replay\"}").getAsJsonObject());
        Assert.assertTrue(c.watchdog.enabled, "on by default in replay mode");
        Assert.assertFalse(RlActorBench.parse(JsonParser.parseString("{\"mode\": \"replay\", \"watchdog\": false}")
                .getAsJsonObject()).watchdog.enabled);
    }

    @Test
    public void oldOccupancyReadsTheOldGenerationPool() {
        final Map<String, MemoryUsage> m = new HashMap<>();
        m.put("G1 Eden Space", new MemoryUsage(0, 500, 500, 1000));
        Assert.assertTrue(Double.isNaN(ReplayStallWatchdog.JvmSampler.oldOccupancy(m)));
        m.put("G1 Old Gen", new MemoryUsage(0, 900, 950, 1000));
        Assert.assertEquals(ReplayStallWatchdog.JvmSampler.oldOccupancy(m), 0.9, 1e-9);
        m.put("G1 Old Gen", new MemoryUsage(0, 900, 950, -1));
        Assert.assertEquals(ReplayStallWatchdog.JvmSampler.oldOccupancy(m), 900.0 / Runtime.getRuntime().maxMemory(),
                1e-12);
    }

    @Test
    public void theJvmSamplerReadsThisJvm() throws Exception {
        final long[] sink = new long[1];
        final Thread busy = new Thread(() -> {
            final long end = System.nanoTime() + 400_000_000L;
            long x = 0;
            while (System.nanoTime() < end) {
                x += System.nanoTime() % 7;
            }
            sink[0] = x;
        }, "rlreplay-w0");
        busy.start();
        final ReplayStallWatchdog.JvmSampler s = new ReplayStallWatchdog.JvmSampler(Collections.singletonList(busy));
        Assert.assertNull(s.sample(), "the first call is the baseline");
        Thread.sleep(200);
        System.gc();
        final ReplayStallWatchdog.Sample a = s.sample();
        busy.join();
        Assert.assertNotNull(a);
        Assert.assertEquals(a.workers, 1);
        Assert.assertTrue(a.gcFraction >= 0 && a.gcFraction <= 1, a.toJson().toString());
        Assert.assertTrue(a.mutatorShare > 0 && a.mutatorShare <= 1, a.toJson().toString());
        final ReplayStallWatchdog.Sample b = s.sample();
        Assert.assertEquals(b.workers, 0, "a finished game thread is not counted");
        Assert.assertTrue(Double.isNaN(b.mutatorShare));
    }

    // ------------------------------------------------------------------------------------------- the stall itself

    static JsonObject tape(final String uid) {
        final JsonObject t = new JsonObject();
        t.addProperty("game_uid", uid);
        t.addProperty("seed", 7);
        t.addProperty("turns", 44);
        t.addProperty("digest", "d" + uid);
        t.addProperty("reason", "library");
        t.add("void", JsonNull.INSTANCE);
        final JsonArray r = new JsonArray();
        r.add(1);
        r.add(-1);
        t.add("result", r);
        t.add("decks", new JsonArray());
        t.add("controllers", new JsonArray());
        return t;
    }

    static List<JsonObject> lines(final Path f) throws IOException {
        final List<JsonObject> o = new ArrayList<>();
        if (Files.exists(f)) {
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                o.add(JsonParser.parseString(l).getAsJsonObject());
            }
        }
        return o;
    }

    /** A sampler that answers {@code script} in order, then healthy samples (null first: the baseline). */
    static ReplayStallWatchdog.Sampler scripted(final ReplayStallWatchdog.Sample... script) {
        final AtomicInteger k = new AtomicInteger(-1);
        return () -> {
            final int i = k.getAndIncrement();
            return i < 0 ? null : i < script.length ? script[i] : healthy();
        };
    }

    @Test
    public void aStallVoidsTheUnfinishedGamesAndExitsFive() throws Exception {
        final Path dir = Files.createTempDirectory("stall-wd");
        final Path labels = dir.resolve("labels");
        final RlReplayLabeller.Sink sink = new RlReplayLabeller.Sink(labels);
        final List<JsonObject> tapes = List.of(tape("11"), tape("22"), tape("33"), tape("44"));
        final List<int[]> ls = List.of(new int[] {0, 5}, new int[] {0, 6}, new int[] {1, 7}, new int[] {1, 8});
        final List<String> paths = List.of("a.gz", "a.gz", "b.gz", "b.gz");
        final AtomicReferenceArray<String> out = new AtomicReferenceArray<>(4);
        // game 11: finished (its labels and row are in); game 33: labels written, its row comes back during the grace
        final JsonObject g11 = new JsonObject();
        g11.addProperty("game_uid", "11");
        g11.addProperty("equal", true);
        SinkTestAccess.writeGame(sink, g11);
        out.set(0, "{\"equal\":true,\"game_uid\":\"11\",\"line\":5}");
        final JsonObject g33 = new JsonObject();
        g33.addProperty("game_uid", "33");
        g33.addProperty("equal", true);
        g33.addProperty("digest_replay", "d33");
        SinkTestAccess.writeGame(sink, g33);
        final AtomicInteger exit = new AtomicInteger(-1);
        final Path replay = dir.resolve("replay.jsonl");
        final ReplayStallWatchdog.Thresholds th = new ReplayStallWatchdog.Thresholds();
        th.consecutive = 3;
        try (PrintWriter w = new PrintWriter(new FileWriter(replay.toFile(), StandardCharsets.UTF_8, true), true)) {
            final ReplayStallWatchdog wd = new ReplayStallWatchdog(th,
                    scripted(starved(), healthy(), starved(), starved(), starved()), 20, dir, tapes, paths, ls, out, sink,
                    RlReplayLabeller.SCHEMA_STEP, w, "jar0", exit::set);
            wd.lateRowGraceMs = 5_000;
            final Thread late = new Thread(() -> {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    return;
                }
                out.set(2, "{\"equal\":true,\"game_uid\":\"33\",\"line\":7}");
            });
            late.start();
            wd.startThread();
            final long end = System.currentTimeMillis() + 20_000;
            while (exit.get() < 0 && System.currentTimeMillis() < end) {
                Thread.sleep(10);
            }
            late.join();
            Assert.assertEquals(exit.get(), ReplayStallWatchdog.EXIT_STALL);
            Assert.assertEquals(wd.samples(), 5, "the healthy second sample restarted the run");
            Assert.assertFalse(wd.close(), "the replay was stalled: the caller writes nothing");

            final List<JsonObject> rows = lines(replay);
            Assert.assertEquals(rows.size(), 4, rows.toString());
            Assert.assertEquals(rows.get(0).get("game_uid").getAsString(), "11");
            Assert.assertTrue(rows.get(0).get("equal").getAsBoolean());
            Assert.assertFalse(rows.get(0).has("void"));
            Assert.assertEquals(rows.get(2).get("game_uid").getAsString(), "33");
            Assert.assertTrue(rows.get(2).get("equal").getAsBoolean(), "a game labelled before the seal is finished");
            for (int i : new int[] {1, 3}) {
                final JsonObject r = rows.get(i);
                Assert.assertEquals(r.get("void").getAsString(), "stall");
                Assert.assertFalse(r.get("equal").getAsBoolean());
                Assert.assertEquals(r.get("line").getAsInt(), ls.get(i)[1]);
                Assert.assertEquals(r.get("tape").getAsString(), paths.get(i));
                Assert.assertEquals(r.get("digest_tape").getAsString(), "d" + tapes.get(i).get("game_uid").getAsString());
            }

            // a game finishing after the seal writes nothing
            final JsonObject g22 = new JsonObject();
            g22.addProperty("game_uid", "22");
            g22.addProperty("equal", true);
            SinkTestAccess.writeGame(sink, g22);
            final List<JsonObject> games = lines(sink.gamesFile());
            Assert.assertEquals(games.size(), 4, games.toString());
            Assert.assertEquals(games.get(0).get("game_uid").getAsString(), "11");
            Assert.assertEquals(games.get(1).get("game_uid").getAsString(), "33");
            for (int i : new int[] {2, 3}) {
                final JsonObject g = games.get(i);
                Assert.assertEquals(g.get("void").getAsString(), "stall");
                Assert.assertFalse(g.get("equal").getAsBoolean());
                Assert.assertEquals(g.get("rows").getAsInt(), 0);
                Assert.assertEquals(g.get("schema").getAsString(), RlReplayLabeller.SCHEMA_STEP);
                Assert.assertEquals(g.get("turns").getAsInt(), 44);
                Assert.assertEquals(g.get("jar_sha").getAsString(), "jar0");
            }
            Assert.assertEquals(games.get(2).get("game_uid").getAsString(), "22");
            Assert.assertEquals(games.get(3).get("game_uid").getAsString(), "44");

            final JsonObject st = JsonParser.parseString(Files.readString(wd.stallFile())).getAsJsonObject();
            Assert.assertEquals(st.get("type").getAsString(), "replay-stall");
            Assert.assertEquals(st.get("exit").getAsInt(), 5);
            Assert.assertEquals(st.get("games").getAsInt(), 4);
            Assert.assertEquals(st.get("games_done").getAsInt(), 2);
            Assert.assertEquals(st.getAsJsonArray("games_voided").toString(), "[\"22\",\"44\"]");
            Assert.assertEquals(st.getAsJsonArray("starved_samples").size(), 3);
            for (int i = 0; i < 3; i++) {
                Assert.assertTrue(st.getAsJsonArray("starved_samples").get(i).getAsJsonObject().get("starved").getAsBoolean());
            }
            final JsonObject pr = JsonParser.parseString(Files.readString(wd.progressFile())).getAsJsonObject();
            Assert.assertEquals(pr.get("type").getAsString(), "replay-progress");
            Assert.assertEquals(pr.get("starved_run").getAsInt(), 3);
            Assert.assertEquals(pr.get("samples").getAsInt(), 5);
        }
    }

    @Test
    public void aFinishedReplayClosesTheWatchdogWithoutAStall() throws Exception {
        final Path dir = Files.createTempDirectory("stall-wd-ok");
        final AtomicReferenceArray<String> out = new AtomicReferenceArray<>(1);
        final AtomicInteger exit = new AtomicInteger(-1);
        final ReplayStallWatchdog.Thresholds th = new ReplayStallWatchdog.Thresholds();
        th.consecutive = 3;
        final ReplayStallWatchdog wd = new ReplayStallWatchdog(th, scripted(starved(), starved()), 20, dir,
                List.of(tape("9")), List.of("a.gz"), List.<int[]>of(new int[] {0, 1}), out, null,
                RlReplayLabeller.SCHEMA, null, null, exit::set);
        wd.startThread();
        Thread.sleep(300);
        out.set(0, "{\"equal\":true}");
        Assert.assertTrue(wd.close(), "two starved samples then healthy ones: no stall");
        Assert.assertEquals(exit.get(), -1);
        Assert.assertFalse(Files.exists(wd.stallFile()));
        Assert.assertTrue(Files.exists(wd.progressFile()));
        // after close, a stall can no longer happen
        wd.fire();
        Assert.assertEquals(exit.get(), -1);
    }
}
