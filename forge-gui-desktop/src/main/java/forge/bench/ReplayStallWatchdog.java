package forge.bench;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntConsumer;

import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.management.GarbageCollectionNotificationInfo;

import forge.bench.rl.RlReplayLabeller;
import forge.bench.rl.RlWire;
import forge.bench.rl.TrajectoryRef;

/**
 * Lane lki-fix-port-1027 (design: lane lki-leak-1027; BVW step 2): the stall watchdog of {@link RlActorBench}'s replay
 * mode, JVM side.
 *
 * <p><b>Why.</b> A labelling replay whose heap fills does not crash: it spends its time in GC while its game threads
 * crawl (step-1 chunk m-00039: 0 of 6 games after 23 h; the cause, LKI snapshots kept alive, is fixed in
 * {@code Game#copyLastState}). A wall cap per game is ruled out (FLAG-26: the long games are the ones to label), so this
 * watches for no PROGRESS, never for time.
 *
 * <p><b>Samples.</b> Every {@code intervalSec} (60 s) a daemon thread reads, over the interval just ended:
 * <ul>
 * <li>{@code gc}, the G1 GC fraction: the JVM collectors' summed collection (pause) time / wall time;</li>
 * <li>{@code mutator}, the mutator share of the live {@code rlreplay-w*} game threads: their summed CPU time / (wall time
 * x their number); bvw-tail-fix-1027: a labeller's row threads ({@code rlreplay-wK-rowN}, the band searches of a game
 * with {@code rowThreads} > 1, whose game thread waits for them) count as game threads, and while any is live the
 * share is of min(their number, the JVM's available processors);</li>
 * <li>{@code old_after_gc}, the old generation's occupancy (used / max) after a GC: the lowest after any GC of the
 * interval (GC notifications).</li>
 * </ul>
 * A sample is STARVED when all three agree: {@code gc >= gcFractionMin} (0.5), {@code mutator <= mutatorShareMax} (0.25)
 * and {@code old_after_gc >= oldAfterGcMin} (0.85). That is: the JVM collects most of the time, its game threads barely
 * run, and collecting frees nothing. A slow game alone (a long search, a loaded box, a slow value service: low mutator
 * share, but no GC pressure) is never starved, and an interval with no GC or no live game thread is not starved.
 *
 * <p><b>Outputs.</b> Each sample rewrites {@code progress-<pid>.json} (the sample, the starved run, games done). After
 * {@code consecutive} (10) starved samples in a row, the replay STALLS:
 * <ul>
 * <li>the label sink is sealed (a game that finishes later writes nothing);</li>
 * <li>replay.out gets every row, in line order: the finished games' rows as they are, and a {@code void:"stall"} row
 * ({@code equal:false}) for each unfinished game;</li>
 * <li>the label games file gets a {@code void:"stall"} games row (0 rows) for each unfinished game;</li>
 * <li>{@code STALL-<pid>.json} records the thresholds, the starved samples and the voided games;</li>
 * <li>the JVM exits 5 ({@link Runtime#halt}: the game threads are starving, so no join).</li>
 * </ul>
 * Config ({@code RlActorBench} key {@code watchdog}; on by default in replay mode; {@code false} or
 * {@code {"enabled": false}} turns it off): {@code enabled, intervalSec, consecutive, gcFractionMin, mutatorShareMax,
 * oldAfterGcMin, dir} (default dir: replay.out's directory, else label.out, else the working directory). It reads
 * MXBeans only: no game, random stream or decision is touched.
 *
 * <p>Review of #121 (lane lki-fix-port-1027): it arms only under the G1, Parallel or Serial collector (their pause
 * times and old-generation pools are what the samples read; the collector names are in every progress file); it starts
 * before the game threads; a sample that throws is logged and skipped; the stall decides which games finished from one
 * snapshot of the rows taken before the label sink is sealed, and it exits 5 even if writing the rows fails; closing it
 * removes its GC listeners.
 */
public final class ReplayStallWatchdog {
    /** The replay's exit code after a stall. */
    public static final int EXIT_STALL = 5;
    /** How long a stall waits for the replay row of a game whose labels were written just before the seal. */
    static final long LATE_ROW_GRACE_MS = 30_000L;

    /** The thresholds and switches ({@code watchdog} in the actor config). */
    public static final class Thresholds {
        public boolean enabled = true;
        public int intervalSec = 60;
        public int consecutive = 10;
        public double gcFractionMin = 0.5;
        public double mutatorShareMax = 0.25;
        public double oldAfterGcMin = 0.85;
        /** progress / STALL files' directory; null = replay.out's directory, else label.out, else the working dir. */
        public String dir = null;

        public static Thresholds parse(final JsonElement e) {
            final Thresholds t = new Thresholds();
            if (e == null || e.isJsonNull()) {
                return t;
            }
            if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
                t.enabled = e.getAsBoolean();
                return t;
            }
            final JsonObject o = e.getAsJsonObject();
            for (String k : o.keySet()) {
                switch (k) {
                    case "enabled": t.enabled = o.get(k).getAsBoolean(); break;
                    case "intervalSec": t.intervalSec = o.get(k).getAsInt(); break;
                    case "consecutive": t.consecutive = o.get(k).getAsInt(); break;
                    case "gcFractionMin": t.gcFractionMin = o.get(k).getAsDouble(); break;
                    case "mutatorShareMax": t.mutatorShareMax = o.get(k).getAsDouble(); break;
                    case "oldAfterGcMin": t.oldAfterGcMin = o.get(k).getAsDouble(); break;
                    case "dir": t.dir = o.get(k).isJsonNull() ? null : o.get(k).getAsString(); break;
                    default:
                        throw new IllegalArgumentException("watchdog: unknown key " + k + " (enabled, intervalSec, "
                                + "consecutive, gcFractionMin, mutatorShareMax, oldAfterGcMin, dir)");
                }
            }
            if (t.intervalSec < 1 || t.consecutive < 1 || !(t.gcFractionMin > 0 && t.gcFractionMin <= 1)
                    || !(t.mutatorShareMax >= 0 && t.mutatorShareMax < 1) || !(t.oldAfterGcMin > 0 && t.oldAfterGcMin <= 1)) {
                throw new IllegalArgumentException("watchdog: intervalSec >= 1, consecutive >= 1, gcFractionMin in (0, 1], "
                        + "mutatorShareMax in [0, 1), oldAfterGcMin in (0, 1]");
            }
            return t;
        }

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("enabled", enabled);
            o.addProperty("intervalSec", intervalSec);
            o.addProperty("consecutive", consecutive);
            o.addProperty("gcFractionMin", gcFractionMin);
            o.addProperty("mutatorShareMax", mutatorShareMax);
            o.addProperty("oldAfterGcMin", oldAfterGcMin);
            return o;
        }
    }

    /** One interval's reading. NaN = not measurable in this interval (no live game thread; no GC). */
    public static final class Sample {
        public final double wallSec, gcFraction, mutatorShare, oldAfterGc;
        public final int workers;
        public final long gcs;

        public Sample(final double wallSec, final double gcFraction, final double mutatorShare, final double oldAfterGc,
                final int workers, final long gcs) {
            this.wallSec = wallSec;
            this.gcFraction = gcFraction;
            this.mutatorShare = mutatorShare;
            this.oldAfterGc = oldAfterGc;
            this.workers = workers;
            this.gcs = gcs;
        }

        JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("wall_s", round(wallSec));
            num(o, "gc", gcFraction);
            num(o, "mutator", mutatorShare);
            num(o, "old_after_gc", oldAfterGc);
            o.addProperty("workers", workers);
            o.addProperty("gcs", gcs);
            return o;
        }

        private static void num(final JsonObject o, final String k, final double v) {
            if (Double.isNaN(v)) {
                o.add(k, JsonNull.INSTANCE);
            } else {
                o.addProperty(k, round(v));
            }
        }

        private static double round(final double v) {
            return Math.round(v * 10000.0) / 10000.0;
        }
    }

    /** The interval since the previous call; null on the first call (the baseline). */
    public interface Sampler {
        Sample sample();

        /** The JVM's collector names (recorded in every progress file). */
        default List<String> collectors() {
            return Collections.emptyList();
        }

        /** Stops listening (review S3-4: the GC listeners are removed when the replay closes the watchdog). */
        default void close() {
        }
    }

    /** Review S3-8: the collectors whose pause times and old-generation pools the samples are defined on. */
    static final java.util.Set<String> KNOWN_COLLECTORS = java.util.Set.of(
            "G1 Young Generation", "G1 Old Generation", "G1 Concurrent GC", // G1 (the concurrent bean: JDK 20+)
            "PS Scavenge", "PS MarkSweep", // Parallel
            "Copy", "MarkSweepCompact"); // Serial

    /** Null when the watchdog may arm under these collectors (G1, Parallel or Serial only), else why it may not. */
    static String armRefusal(final List<String> collectors) {
        if (collectors.isEmpty()) {
            return "no garbage collector MXBeans";
        }
        for (String c : collectors) {
            if (!KNOWN_COLLECTORS.contains(c)) {
                return "collector " + c + " (of " + collectors + ") is not G1, Parallel or Serial";
            }
        }
        return null;
    }

    /** The trigger, pure: {@code consecutive} starved samples in a row. Any other sample restarts the count. */
    public static final class Judge {
        private final Thresholds th;
        private int run = 0;

        public Judge(final Thresholds th) {
            this.th = th;
        }

        public boolean starved(final Sample s) {
            return s != null && s.workers > 0
                    && !Double.isNaN(s.gcFraction) && s.gcFraction >= th.gcFractionMin
                    && !Double.isNaN(s.mutatorShare) && s.mutatorShare <= th.mutatorShareMax
                    && !Double.isNaN(s.oldAfterGc) && s.oldAfterGc >= th.oldAfterGcMin;
        }

        /** Counts the sample; true when it completes a run of {@code consecutive} starved samples. */
        public boolean offer(final Sample s) {
            run = starved(s) ? run + 1 : 0;
            return run >= th.consecutive;
        }

        public int run() {
            return run;
        }
    }

    /** The JVM's own readings: GC MXBeans (times and after-GC notifications) and the game threads' CPU times. */
    static final class JvmSampler implements Sampler {
        private final List<Thread> workers;
        private final ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        private final Map<Long, Long> prevCpu = new HashMap<>();
        private long prevNanos = -1, prevGcMs = 0, prevGcCount = 0;
        private final Object oldLock = new Object();
        /** The lowest old-generation occupancy after a GC since the last sample (NaN: no GC since). */
        private double minOld = Double.NaN;
        /** The GC listeners this sampler added, by emitter (removed by {@link #close}). */
        private final Map<NotificationEmitter, javax.management.NotificationListener> listeners = new java.util.LinkedHashMap<>();

        /**
         * bvw-tail-fix-1027: the labeller's live row threads (counted with the game threads) and the CPUs the share is
         * of when any is live.
         */
        private final java.util.function.Supplier<List<Thread>> rowThreads;
        private final int cpus;

        JvmSampler(final List<Thread> workers) {
            this(workers, RlReplayLabeller::liveRowThreads, Runtime.getRuntime().availableProcessors());
        }

        JvmSampler(final List<Thread> workers, final java.util.function.Supplier<List<Thread>> rowThreads, final int cpus) {
            this.rowThreads = rowThreads;
            this.cpus = cpus;
            this.workers = workers;
            if (tmx.isThreadCpuTimeSupported() && !tmx.isThreadCpuTimeEnabled()) {
                tmx.setThreadCpuTimeEnabled(true);
            }
            for (GarbageCollectorMXBean b : gcBeans) {
                if (!(b instanceof NotificationEmitter)) {
                    continue;
                }
                final javax.management.NotificationListener l = (n, hb) -> {
                    if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) {
                        return;
                    }
                    final GarbageCollectionNotificationInfo info =
                            GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
                    final double occ = oldOccupancy(info.getGcInfo().getMemoryUsageAfterGc());
                    if (!Double.isNaN(occ)) {
                        synchronized (oldLock) {
                            minOld = Double.isNaN(minOld) ? occ : Math.min(minOld, occ);
                        }
                    }
                };
                ((NotificationEmitter) b).addNotificationListener(l, null, null);
                listeners.put((NotificationEmitter) b, l);
            }
        }

        @Override
        public List<String> collectors() {
            final List<String> names = new ArrayList<>();
            for (GarbageCollectorMXBean b : gcBeans) {
                names.add(b.getName());
            }
            return names;
        }

        /** The GC listeners still registered (tests). */
        synchronized int listenerCount() {
            return listeners.size();
        }

        @Override
        public synchronized void close() {
            for (Map.Entry<NotificationEmitter, javax.management.NotificationListener> e : listeners.entrySet()) {
                try {
                    e.getKey().removeNotificationListener(e.getValue());
                } catch (javax.management.ListenerNotFoundException ignored) {
                    // already gone
                }
            }
            listeners.clear();
        }

        /** Old-generation pools ("G1 Old Gen", or any "Old Gen" / "Tenured" pool): used / max; NaN when there is none. */
        static double oldOccupancy(final Map<String, MemoryUsage> after) {
            long used = 0, max = 0;
            boolean any = false;
            for (Map.Entry<String, MemoryUsage> e : after.entrySet()) {
                if (e.getKey().contains("Old Gen") || e.getKey().contains("Tenured")) {
                    any = true;
                    used += e.getValue().getUsed();
                    max += Math.max(0, e.getValue().getMax());
                }
            }
            if (!any) {
                return Double.NaN;
            }
            if (max <= 0) {
                max = Runtime.getRuntime().maxMemory();
            }
            return (double) used / max;
        }

        @Override
        public Sample sample() {
            final long now = System.nanoTime();
            long gcMs = 0, gcCount = 0;
            for (GarbageCollectorMXBean b : gcBeans) {
                gcMs += Math.max(0, b.getCollectionTime());
                gcCount += Math.max(0, b.getCollectionCount());
            }
            final double old;
            synchronized (oldLock) {
                old = minOld;
                minOld = Double.NaN;
            }
            final Map<Long, Long> cpu = new HashMap<>();
            for (Thread t : workers) {
                if (t.isAlive()) {
                    final long c = tmx.getThreadCpuTime(t.getId());
                    if (c >= 0) {
                        cpu.put(t.getId(), c);
                    }
                }
            }
            // bvw-tail-fix-1027: a game whose band searches run on row threads waits for them; they are its mutators
            final java.util.Set<Long> rows = new java.util.HashSet<>();
            for (Thread t : rowThreads.get()) {
                if (t.isAlive()) {
                    final long c = tmx.getThreadCpuTime(t.getId());
                    if (c >= 0) {
                        cpu.put(t.getId(), c);
                        rows.add(t.getId());
                    }
                }
            }
            Sample s = null;
            if (prevNanos >= 0) {
                final double wallNs = Math.max(1, now - prevNanos);
                long dCpu = 0;
                int live = 0, rowLive = 0;
                for (Map.Entry<Long, Long> e : cpu.entrySet()) {
                    final Long p = prevCpu.get(e.getKey());
                    if (p != null) {
                        dCpu += Math.max(0, e.getValue() - p);
                        live++;
                        if (rows.contains(e.getKey())) {
                            rowLive++;
                        }
                    }
                }
                // with row threads: the share of the CPUs they can run on (more threads than CPUs never all run at
                // once); without any, exactly as before
                final int slots = rowLive == 0 ? live : Math.min(live, Math.max(1, cpus));
                s = new Sample(wallNs / 1e9, Math.min(1.0, (gcMs - prevGcMs) * 1e6 / wallNs),
                        live == 0 ? Double.NaN : Math.min(1.0, dCpu / (wallNs * slots)), old, live, gcCount - prevGcCount);
            }
            prevNanos = now;
            prevGcMs = gcMs;
            prevGcCount = gcCount;
            prevCpu.clear();
            prevCpu.putAll(cpu);
            return s;
        }
    }

    private final Thresholds th;
    private final Sampler sampler;
    private final Judge judge;
    private final long intervalMs;
    private final Path dir;
    private final long pid = ProcessHandle.current().pid();
    private final List<JsonObject> tapes;
    private final List<String> tapePaths;
    private final List<int[]> lines;
    private final AtomicReferenceArray<String> out;
    private final RlReplayLabeller.Sink sink;
    private final String gamesSchema;
    private final PrintWriter replayOut;
    private final String jarSha;
    private final IntConsumer exit;
    private final Deque<JsonObject> recent = new ArrayDeque<>();
    private int samples = 0;
    private boolean closed = false, fired = false;
    private Thread thread;
    /** {@link #LATE_ROW_GRACE_MS}; tests shorten it. */
    long lateRowGraceMs = LATE_ROW_GRACE_MS;

    ReplayStallWatchdog(final Thresholds th, final Sampler sampler, final long intervalMs, final Path dir,
            final List<JsonObject> tapes, final List<String> tapePaths, final List<int[]> lines,
            final AtomicReferenceArray<String> out, final RlReplayLabeller.Sink sink, final String gamesSchema,
            final PrintWriter replayOut, final String jarSha, final IntConsumer exit) {
        this.th = th;
        this.sampler = sampler;
        this.judge = new Judge(th);
        this.intervalMs = intervalMs;
        this.dir = dir;
        this.tapes = tapes;
        this.tapePaths = tapePaths;
        this.lines = lines;
        this.out = out;
        this.sink = sink;
        this.gamesSchema = gamesSchema;
        this.replayOut = replayOut;
        this.jarSha = jarSha;
        this.exit = exit;
    }

    /**
     * Starts the watchdog of one replay, BEFORE its game threads start (review S3-5): {@code workers} are its
     * {@code rlreplay-w*} threads (started or not); game {@code i} is {@code tapes.get(i)} (from
     * {@code tapePaths.get(i)}, line {@code lines.get(i)[1]}) and its row lands in {@code out[i]}. A stall exits the
     * JVM with {@link #EXIT_STALL}. Returns null, and says why on stderr, when it may not arm (review S3-8: a
     * collector other than G1, Parallel or Serial).
     */
    public static ReplayStallWatchdog start(final Thresholds th, final List<Thread> workers, final Path dir,
            final List<JsonObject> tapes, final List<String> tapePaths, final List<int[]> lines,
            final AtomicReferenceArray<String> out, final RlReplayLabeller.Sink sink, final String gamesSchema,
            final PrintWriter replayOut, final String jarSha) throws IOException {
        final List<String> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            collectors.add(b.getName());
        }
        final String refusal = armRefusal(collectors);
        if (refusal != null) {
            System.err.println("[rlactor] stall watchdog NOT armed: " + refusal);
            return null;
        }
        Files.createDirectories(dir);
        final ReplayStallWatchdog w = new ReplayStallWatchdog(th, new JvmSampler(workers), th.intervalSec * 1000L, dir,
                tapes, tapePaths, lines, out, sink, gamesSchema, replayOut, jarSha, code -> {
                    System.err.flush();
                    System.out.flush();
                    Runtime.getRuntime().halt(code);
                });
        w.startThread();
        return w;
    }

    void startThread() {
        thread = new Thread(this::loop, "replay-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The replay finished (every game thread joined): stop watching. False when the watchdog stalled the replay first
     * (its rows are written; the caller returns {@link #EXIT_STALL} and writes nothing).
     */
    public synchronized boolean close() {
        closed = true;
        if (thread != null) {
            thread.interrupt();
        }
        try {
            sampler.close(); // review S3-4: the GC listeners go with the replay
        } catch (RuntimeException e) {
            System.err.println("[rlactor] watchdog: closing the sampler: " + e);
        }
        return !fired;
    }

    public synchronized int samples() {
        return samples;
    }

    public Path progressFile() {
        return dir.resolve("progress-" + pid + ".json");
    }

    public Path stallFile() {
        return dir.resolve("STALL-" + pid + ".json");
    }

    private void loop() {
        try {
            sampler.sample(); // the baseline
        } catch (Throwable t) {
            log("the baseline sample failed", t);
        }
        while (true) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            boolean stall = false;
            try {
                final Sample s = sampler.sample();
                synchronized (this) {
                    if (closed) {
                        return;
                    }
                    if (s != null) {
                        final boolean starved = judge.starved(s);
                        stall = judge.offer(s);
                        samples++;
                        final JsonObject j = s.toJson();
                        j.addProperty("t", Instant.now().toString());
                        j.addProperty("starved", starved);
                        recent.addLast(j);
                        while (recent.size() > th.consecutive) {
                            recent.removeFirst();
                        }
                        writeProgress(j);
                    }
                }
            } catch (Throwable t) {
                // review S3-6: a failed sample (an OOM under the very pressure it watches, an MXBean error) is logged and
                // skipped; it counts as no sample, so it neither extends nor restarts the starved run
                log("a sample failed (skipped)", t);
                continue;
            }
            if (stall) {
                fire();
                return;
            }
        }
    }

    private static void log(final String what, final Throwable t) {
        try {
            System.err.println("[rlactor] watchdog: " + what + ": " + t);
        } catch (Throwable ignored) {
            // nothing more to do
        }
    }

    private int gamesDone() {
        int d = 0;
        for (int i = 0; i < out.length(); i++) {
            d += out.get(i) != null ? 1 : 0;
        }
        return d;
    }

    private void writeProgress(final JsonObject sample) {
        final JsonObject p = new JsonObject();
        p.addProperty("type", "replay-progress");
        p.addProperty("pid", pid);
        p.addProperty("t", sample.get("t").getAsString());
        p.addProperty("samples", samples);
        p.addProperty("starved_run", judge.run());
        p.add("sample", sample);
        p.addProperty("games", out.length());
        p.addProperty("games_done", gamesDone());
        p.add("watchdog", th.toJson());
        final JsonArray gcs = new JsonArray();
        for (String c : sampler.collectors()) {
            gcs.add(c);
        }
        p.add("collectors", gcs); // review S3-8
        if (jarSha != null) {
            p.addProperty("jar_sha", jarSha);
        }
        try {
            atomicWrite(progressFile(), RlWire.canonicalString(p) + "\n");
        } catch (IOException e) {
            System.err.println("[rlactor] watchdog: progress file: " + e);
        }
    }

    private static void atomicWrite(final Path f, final String s) throws IOException {
        final Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.write(tmp, s.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String uid(final JsonObject tape) {
        return TrajectoryRef.canonicalUid(tape.get("game_uid").getAsString());
    }

    /**
     * The stall: void the unfinished games, write every row and STALL-<pid>.json, exit 5.
     *
     * <p>Review S2-1: which games finished is decided from ONE snapshot of the rows, taken before the label sink is
     * sealed. A game finished iff its row is in that snapshot, or its labels and games row were written before the seal
     * (then its replay row is waited for, bounded). A row that comes back later for any other game is ignored, and that
     * game is voided "stall" (its labels, if any, were refused by the seal). Review S2-2: whatever fails here, the JVM
     * exits 5; {@code fired} is set only once the rows are written.
     */
    synchronized void fire() {
        if (closed) {
            return;
        }
        try {
            final String at = Instant.now().toString();
            final int n = out.length();
            final String[] snapshot = new String[n];
            for (int i = 0; i < n; i++) {
                snapshot[i] = out.get(i);
            }
            final Map<String, JsonObject> labelled = sink == null ? Collections.emptyMap() : sink.seal();
            // a game labelled before the seal but not in the snapshot is finished: wait (bounded) for its replay row
            final long until = System.nanoTime() + lateRowGraceMs * 1_000_000L;
            for (int i = 0; i < n; i++) {
                if (snapshot[i] != null || !labelled.containsKey(uid(tapes.get(i)))) {
                    continue;
                }
                while (out.get(i) == null && System.nanoTime() < until) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            final JsonObject stall = new JsonObject();
            stall.addProperty("pid", pid);
            stall.addProperty("t", at);
            stall.addProperty("samples", samples);
            final List<String> rows = new ArrayList<>(n);
            final List<JsonObject> stallGames = new ArrayList<>();
            final JsonArray voided = new JsonArray();
            int done = 0;
            for (int i = 0; i < n; i++) {
                if (snapshot[i] != null) {
                    rows.add(snapshot[i]);
                    done++;
                    continue;
                }
                final JsonObject t = tapes.get(i);
                final String u = uid(t);
                final JsonObject g = labelled.get(u);
                if (g != null) {
                    final String late = out.get(i);
                    if (late != null) {
                        rows.add(late); // labelled before the seal: finished, its row came back during the wait
                    } else {
                        // finished (its labels and games row are written) but its replay row did not come back in time
                        final JsonObject row = rowHead(i, t);
                        row.add("digest_replay", g.get("digest_replay"));
                        row.add("equal", g.get("equal"));
                        if (g.has("trajectory_equal")) {
                            row.add("trajectory_equal", g.get("trajectory_equal"));
                        }
                        row.addProperty("error", "stall: the replay row was not back before the exit (the game's labels "
                                + "are written)");
                        rows.add(RlWire.canonicalString(row));
                    }
                    done++;
                    continue;
                }
                // unfinished at the snapshot: voided, whatever comes back later
                final JsonObject row = rowHead(i, t);
                row.addProperty("equal", false);
                row.addProperty("void", "stall");
                row.addProperty("error", "stall: the watchdog voided this unfinished game");
                rows.add(RlWire.canonicalString(row));
                voided.add(u);
                stallGames.add(stallGameRow(t, u, stall));
            }
            if (sink != null) {
                for (JsonObject gr : stallGames) {
                    sink.writeStallGame(gr);
                }
            }
            if (replayOut != null) {
                for (String r : rows) {
                    replayOut.println(r);
                }
                replayOut.flush();
            }
            fired = true; // review S2-2: only once every row is written
            final JsonObject f = new JsonObject();
            f.addProperty("type", "replay-stall");
            f.addProperty("pid", pid);
            f.addProperty("t", at);
            f.addProperty("exit", EXIT_STALL);
            f.add("watchdog", th.toJson());
            final JsonArray rs = new JsonArray();
            for (JsonObject s : recent) {
                rs.add(s);
            }
            f.add("starved_samples", rs);
            f.addProperty("games", n);
            f.addProperty("games_done", done);
            f.add("games_voided", voided);
            if (jarSha != null) {
                f.addProperty("jar_sha", jarSha);
            }
            atomicWrite(stallFile(), RlWire.canonicalString(f) + "\n");
            System.err.println("[rlactor] STALL: " + th.consecutive + " starved samples in a row (gc >= "
                    + th.gcFractionMin + ", mutator <= " + th.mutatorShareMax + ", old after GC >= " + th.oldAfterGcMin
                    + "); " + done + " of " + n + " games finished, " + voided.size() + " voided \"stall\"; exit "
                    + EXIT_STALL + " -> " + stallFile());
        } catch (Throwable t) {
            log("the stall's outputs failed (exiting " + EXIT_STALL + " anyway; rows written: " + fired + ")", t);
        } finally {
            exit.accept(EXIT_STALL);
        }
    }

    /** A replay row's identity fields for game {@code i}. */
    private JsonObject rowHead(final int i, final JsonObject t) {
        final JsonObject row = new JsonObject();
        row.addProperty("tape", tapePaths.get(i));
        row.addProperty("line", lines.get(i)[1]);
        row.add("game_uid", t.get("game_uid"));
        row.add("digest_tape", t.get("digest"));
        return row;
    }

    /** The label games row of an unfinished game: the tape's identity fields, {@code void:"stall"}, no rows. */
    private JsonObject stallGameRow(final JsonObject t, final String u, final JsonObject stall) {
        final JsonObject gr = new JsonObject();
        gr.addProperty("schema", gamesSchema);
        gr.addProperty("split", "TRAIN");
        gr.addProperty("game_uid", u);
        gr.add("seed", t.get("seed"));
        gr.add("controllers", t.get("controllers"));
        gr.add("decks", t.get("decks"));
        gr.add("result", t.get("result"));
        gr.addProperty("void", "stall");
        gr.add("reason", t.get("reason"));
        gr.add("turns", t.get("turns"));
        gr.add("digest_tape", t.get("digest"));
        gr.add("digest_replay", JsonNull.INSTANCE);
        gr.addProperty("equal", false);
        gr.addProperty("rows", 0);
        gr.addProperty("rows_void", 0);
        if (jarSha != null) {
            gr.addProperty("jar_sha", jarSha);
        }
        gr.add("stall", stall);
        return gr;
    }
}
