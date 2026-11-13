package forge.ai.simulation;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * searchspeed-1113 (measurement only): where one live decision's wall time goes. On with {@code -Dsdcc.searchProf=true}
 * (a JVM property, never a spec key: the spec JSON, its sha and every decision are the same on or off). It reads clocks
 * and writes one {@code [searchprof]} stderr line per decision of the searching seat; nothing it records is read by any
 * decision.
 *
 * <p>Per thread, a stack of categories: time is charged to the innermost one (exclusive time). The deciding thread
 * ("main") and every other thread ("pool") are reported apart; pool idle = threads x wall - the pool's charged time.
 * Per play-out: candidate, world, start and end (ms from the decision's start), main-loop steps, service requests and
 * their wait, the thread's CPU, aborted.
 */
public final class SearchProf {
    public static final boolean ON = Boolean.getBoolean("sdcc.searchProf");

    public static final int COPY = 0, SVC_DECIDE = 1, SVC_SCORE = 2, SVC_LEAVES = 3, PLAY = 4, PREP = 5, PROPOSE = 6,
            SCREEN = 7, LEARN = 8, WAIT = 9, DEFEND = 10, ASK = 11, PLANREC = 12, LEAFREACH = 13, N = 14;
    static final String[] NAMES = {"copy", "svc_decide", "svc_score", "svc_leaves", "play_engine", "prep_other",
            "solver_propose", "solver_screen", "card_learn", "wait_pool", "solver_defend", "ask_other", "plan_record",
            "leaf_reach_other"};

    private SearchProf() {
    }

    static final class Frame {
        final long[] acc = new long[N];
        final long[] cnt = new long[N];
        final int[] stack = new int[128];
        int depth = -1;
        long last;
        final Thread thread = Thread.currentThread();
        /** The play-out in progress on this thread. */
        int cand = -1, world = -1;
        long playT0, playCpu0, playSvcN0, playSvcNs0, playCopyNs0, playScreenNs0, playLearnN0;
        long leafItems;
        long[] lat = new long[256];
        int nLat;
    }

    private static final ThreadLocal<Frame> TL = ThreadLocal.withInitial(() -> {
        final Frame f = new Frame();
        FRAMES.add(f);
        return f;
    });
    private static final CopyOnWriteArrayList<Frame> FRAMES = new CopyOnWriteArrayList<>();
    private static final ConcurrentLinkedQueue<long[]> PLAYS = new ConcurrentLinkedQueue<>();
    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();

    private static volatile Thread decider;
    private static int nesting;
    private static long t0, gcMs0, gcN0, cpu0;
    private static String kind;
    private static JsonObject notes;
    private static long seq;

    public static void enter(final int cat) {
        if (!ON) return;
        final Frame f = TL.get();
        final long now = System.nanoTime();
        if (f.depth >= 0) f.acc[f.stack[f.depth]] += now - f.last;
        if (f.depth + 1 < f.stack.length) f.stack[++f.depth] = cat;
        f.cnt[cat]++;
        f.last = now;
    }

    public static void exit() {
        if (!ON) return;
        final Frame f = TL.get();
        final long now = System.nanoTime();
        if (f.depth >= 0) {
            f.acc[f.stack[f.depth]] += now - f.last;
            f.depth--;
        }
        f.last = now;
    }

    /** A service request's wait, for the latency percentiles (the category time is charged by enter / exit). */
    public static void latency(final long nanos) {
        if (!ON) return;
        final Frame f = TL.get();
        if (f.nLat == f.lat.length) f.lat = Arrays.copyOf(f.lat, f.nLat * 2);
        f.lat[f.nLat++] = nanos;
    }

    public static void leafItems(final int n) {
        if (!ON) return;
        TL.get().leafItems += n;
    }

    /** Which candidate and world the next play-out on this thread is. */
    public static void ctx(final int cand, final int world) {
        if (!ON) return;
        final Frame f = TL.get();
        f.cand = cand;
        f.world = world;
    }

    public static void playBegin() {
        if (!ON) return;
        enter(PLAY);
        final Frame f = TL.get();
        f.playT0 = System.nanoTime();
        f.playCpu0 = TMX.getCurrentThreadCpuTime();
        f.playSvcN0 = f.cnt[SVC_DECIDE];
        f.playSvcNs0 = f.acc[SVC_DECIDE];
        f.playCopyNs0 = f.acc[COPY];
        f.playScreenNs0 = f.acc[SCREEN] + f.acc[LEARN] + f.acc[LEAFREACH];
        f.playLearnN0 = f.cnt[LEARN];
    }

    public static void playEnd(final int steps, final boolean aborted, final boolean ok, final int turn) {
        if (!ON) return;
        exit();
        final Frame f = TL.get();
        final long now = System.nanoTime();
        PLAYS.add(new long[] {f.cand, f.world, f.playT0, now, steps, f.cnt[SVC_DECIDE] - f.playSvcN0,
                f.acc[SVC_DECIDE] - f.playSvcNs0, TMX.getCurrentThreadCpuTime() - f.playCpu0, aborted ? 1 : 0, ok ? 1 : 0,
                f.acc[SCREEN] + f.acc[LEARN] + f.acc[LEAFREACH] - f.playScreenNs0, f.cnt[LEARN] - f.playLearnN0, turn,
                f.acc[COPY] - f.playCopyNs0});
        f.cand = -1;
        f.world = -1;
    }

    private static long[] gc() {
        long ms = 0, n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            ms += Math.max(0, b.getCollectionTime());
            n += Math.max(0, b.getCollectionCount());
        }
        return new long[] {ms, n};
    }

    /** A decision of the searching seat begins on this thread (nested begins are counted, not reported). */
    public static synchronized void begin(final String k, final int turn, final String phase, final int stack, final int seat) {
        if (!ON) return;
        if (nesting++ > 0) return;
        decider = Thread.currentThread();
        for (Frame f : FRAMES) {
            Arrays.fill(f.acc, 0L);
            Arrays.fill(f.cnt, 0L);
            f.nLat = 0;
            f.leafItems = 0;
        }
        PLAYS.clear();
        kind = k;
        notes = new JsonObject();
        notes.addProperty("turn", turn);
        notes.addProperty("phase", phase);
        notes.addProperty("stack", stack);
        notes.addProperty("seat", seat);
        final long[] g = gc();
        gcMs0 = g[0];
        gcN0 = g[1];
        cpu0 = TMX.getCurrentThreadCpuTime();
        final Frame f = TL.get();
        f.depth = -1;
        t0 = System.nanoTime();
        f.last = t0;
    }

    public static synchronized void note(final String key, final Number v) {
        if (!ON || notes == null) return;
        notes.addProperty(key, v);
    }

    public static synchronized void note(final String key, final String v) {
        if (!ON || notes == null) return;
        notes.addProperty(key, v);
    }

    private static double ms(final long ns) {
        return Math.round(ns / 1e4) / 100.0;
    }

    public static synchronized void end(final int threads) {
        if (!ON) return;
        if (--nesting > 0) return;
        nesting = 0;
        final long now = System.nanoTime();
        final long wall = now - t0;
        final JsonObject o = notes == null ? new JsonObject() : notes;
        notes = null;
        o.addProperty("n", seq++);
        o.addProperty("kind", kind);
        o.addProperty("wallMs", ms(wall));
        o.addProperty("threads", threads);
        o.addProperty("mainCpuMs", ms(TMX.getCurrentThreadCpuTime() - cpu0));
        final long[] main = new long[N], pool = new long[N], cm = new long[N], cp = new long[N];
        long leafItems = 0;
        int nl = 0;
        for (Frame f : FRAMES) {
            final boolean isMain = f.thread == decider;
            for (int i = 0; i < N; i++) {
                (isMain ? main : pool)[i] += f.acc[i];
                (isMain ? cm : cp)[i] += f.cnt[i];
            }
            leafItems += f.leafItems;
            nl += f.nLat;
        }
        final long[] lat = new long[nl];
        int j = 0;
        for (Frame f : FRAMES) {
            final int m = Math.min(f.nLat, nl - j);
            System.arraycopy(f.lat, 0, lat, j, m);
            j += m;
        }
        Arrays.sort(lat);
        final JsonObject jm = new JsonObject(), jp = new JsonObject(), jc = new JsonObject();
        long mainSum = 0, poolSum = 0;
        for (int i = 0; i < N; i++) {
            mainSum += main[i];
            poolSum += pool[i];
            if (main[i] != 0) jm.addProperty(NAMES[i], ms(main[i]));
            if (pool[i] != 0) jp.addProperty(NAMES[i], ms(pool[i]));
            if (cm[i] + cp[i] != 0) jc.addProperty(NAMES[i], cm[i] + cp[i]);
        }
        jm.addProperty("uncharged", ms(wall - mainSum));
        o.add("main", jm);
        o.add("pool", jp);
        o.add("count", jc);
        o.addProperty("poolBusyMs", ms(poolSum));
        o.addProperty("poolIdleMs", ms((long) threads * wall - poolSum));
        final long[] g = gc();
        o.addProperty("gcMs", g[0] - gcMs0);
        o.addProperty("gcCount", g[1] - gcN0);
        final Runtime rt = Runtime.getRuntime();
        o.addProperty("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        o.addProperty("heapTotalMb", rt.totalMemory() >> 20);
        if (nl > 0) {
            final JsonObject l = new JsonObject();
            l.addProperty("n", nl);
            l.addProperty("p50", ms(lat[nl / 2]));
            l.addProperty("p95", ms(lat[Math.min(nl - 1, (int) (nl * 0.95))]));
            l.addProperty("max", ms(lat[nl - 1]));
            o.add("svcLatencyMs", l);
        }
        o.addProperty("leafItems", leafItems);
        final JsonArray pa = new JsonArray();
        for (long[] p : PLAYS) {
            final JsonArray a = new JsonArray();
            a.add(p[0]);
            a.add(p[1]);
            a.add(ms(p[2] - t0));
            a.add(ms(p[3] - t0));
            a.add(p[4]);
            a.add(p[5]);
            a.add(ms(p[6]));
            a.add(ms(p[7]));
            a.add(p[8]);
            a.add(p[9]);
            a.add(ms(p[10]));
            a.add(p[11]);
            a.add(p[12]);
            a.add(ms(p[13]));
            pa.add(a);
        }
        // per play-out: cand, world, startMs, endMs, steps, svcRequests, svcWaitMs, cpuMs, aborted, ok, screenMs, learns, endTurn, copyMs
        o.add("plays", pa);
        PLAYS.clear();
        System.err.println("[searchprof] " + o);
    }
}
