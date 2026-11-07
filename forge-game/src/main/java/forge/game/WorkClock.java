package forge.game;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lane work-units-1107 (ARCH-APPENDIX-1104 A3, owner ruling 11-07; GOAL-PROMPT N4): the pilot's search budgets in engine
 * work units. A clock installed on a thread counts engine work at fixed sites (each a count times a fixed weight in
 * reference nanoseconds), and {@link #nanoTime()} returns that work as virtual time. Every pilot budget that reads
 * {@link #nanoTime()} instead of {@link System#nanoTime()} then binds after the same work on any host and at any load.
 *
 * <p>Off (the default, and every path but a pilot seat with spec key {@code workUnits}): no clock is installed,
 * {@link #nanoTime()} is {@link System#nanoTime()} and every {@link #tick} returns at once, so nothing changes.
 *
 * <p>A real-time <b>safety wall</b> per decision ({@link #begin}) is the only nondeterministic path: past it,
 * {@link #nanoTime()} returns a time past every deadline, so every budget stops as on a wall-clock cut; the hit is
 * counted ({@link Clock#wallHits}).
 */
public final class WorkClock {
    private WorkClock() {
    }

    /** The counted sites (their order is the {@code work_counts} order in rows). */
    public enum Site {
        STEP("mainLoopStep"), SBA("checkStateEffects"), COPY("makeCopy"), COPY_CARD("copyCard"), LEAK("copyLeakWalk"),
        AI_PLAY("canPlayAndPayFor"), AI_EVAL("evaluateBoardPosition"), AI_BLOCK("getPossibleBlockers"),
        SVC("serviceRequest"), SVC_ITEM("serviceItem"), PLAN_NODE("plannerNode"), ENGINE_NODE("engineNode"),
        OPP_NODE("oppMinNode");

        public final String key;

        Site(final String key) {
            this.key = key;
        }
    }

    private static final Site[] SITES = Site.values();

    /**
     * Default weights, reference nanoseconds per unit (fitted per decision against CPU time on the reference host;
     * work-units-1107 calibration). Override: {@code -Dforge.workClock.weights=mainLoopStep:300,makeCopy:...}.
     * CONTRACT: changing a weight changes where budgets bind; record the weights with every read.
     */
    static final long[] DEFAULT_WEIGHTS = {
        // STEP, SBA, COPY, COPY_CARD, LEAK, AI_PLAY, AI_EVAL, AI_BLOCK, SVC, SVC_ITEM, PLAN_NODE, ENGINE_NODE, OPP_NODE
        20_000L, 10_000L, 300_000L, 20_000L, 200L, 20_000L, 20_000L, 2_000L, 1_000_000L, 200_000L, 20_000L, 100_000L,
        100_000L};

    private static volatile long[] weights = parseWeights(System.getProperty("forge.workClock.weights"));

    /** The weights in effect, by site (a copy). */
    public static long[] weights() {
        return weights.clone();
    }

    /** Tests and calibration: set the weights ({@code null} = the defaults and the JVM property). */
    public static void setWeights(final long[] w) {
        weights = w == null ? parseWeights(System.getProperty("forge.workClock.weights")) : w.clone();
    }

    public static long[] parseWeights(final String spec) {
        final long[] w = DEFAULT_WEIGHTS.clone();
        if (spec == null || spec.isEmpty()) {
            return w;
        }
        for (String kv : spec.split(",")) {
            final int i = kv.indexOf(':');
            if (i <= 0) {
                throw new IllegalArgumentException("forge.workClock.weights: site:ns pairs, not " + kv);
            }
            final String k = kv.substring(0, i).trim();
            boolean found = false;
            for (Site s : SITES) {
                if (s.key.equals(k)) {
                    w[s.ordinal()] = Long.parseLong(kv.substring(i + 1).trim());
                    found = true;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("forge.workClock.weights: unknown site " + k);
            }
        }
        return w;
    }

    /** A virtual time past every deadline (and far from overflow when deadlines are added to it). */
    static final long FAR = Long.MAX_VALUE / 4;

    /** One thread's (or one lane's) work clock. */
    public static final class Clock {
        /** Virtual nanoseconds at zero work. */
        final long origin;
        /** Budgets bind at {@code scale} x their reference work. */
        public final double scale;
        /** Weighted work in reference nanoseconds (this clock's own sites). */
        private final AtomicLong work = new AtomicLong();
        /** Virtual nanoseconds added by joins (the parallel lanes' span). */
        private final AtomicLong joined = new AtomicLong();
        /** Counts per site: this clock's own plus its joined lanes' (metering only). */
        private final AtomicLongArray counts = new AtomicLongArray(SITES.length);
        /** Weighted work of joined lanes (metering only: total work, not span). */
        private final AtomicLong laneWork = new AtomicLong();
        /** The armed safety wall (System.nanoTime), 0 = none. */
        volatile long wall;
        /** Safety-wall hits seen by this clock (decisions cut by real time). */
        public final AtomicLong wallHits = new AtomicLong();
        /** Set when the armed wall was passed (cleared by {@link #begin}). */
        volatile boolean wallHit;

        Clock(final long origin, final double scale) {
            if (!(scale > 0)) {
                throw new IllegalArgumentException("workScale must be > 0, not " + scale);
            }
            this.origin = origin;
            this.scale = scale;
        }

        /** Virtual now ignoring the safety wall (metering: the span a cut decision had reached). */
        public long rawNow() {
            return origin + (long) (work.get() / scale) + joined.get();
        }

        /** Virtual now: origin + work / scale + joined span; past every deadline once the safety wall passed. */
        public long now() {
            final long w = wall;
            if (w != 0 && System.nanoTime() - w > 0) {
                if (!wallHit) {
                    wallHit = true;
                }
                return FAR;
            }
            return origin + (long) (work.get() / scale) + joined.get();
        }

        /** The armed safety wall (System.nanoTime), 0 = none. */
        public long safetyWall() {
            return wall;
        }

        /** A real-time cut outside {@link #now} (a hard wall's wait) counts as a safety-wall hit. */
        public void markWallHit() {
            wallHit = true;
        }

        void charge(final Site s, final long n) {
            counts.addAndGet(s.ordinal(), n);
            work.addAndGet(n * weights[s.ordinal()]);
        }

        /** Total weighted work so far (own + joined lanes), reference ns (metering; not virtual time). */
        public long totalWork() {
            return work.get() + laneWork.get();
        }

        /** Counts per site so far (own + joined lanes). */
        public long[] counts() {
            final long[] c = new long[SITES.length];
            for (int i = 0; i < c.length; i++) {
                c[i] = counts.get(i);
            }
            return c;
        }

        /** A lane of this clock: starts at this clock's virtual now, same scale and safety wall. */
        public Clock fork() {
            final Clock c = new Clock(rawNow(), scale);
            c.wall = wall;
            c.wallHit = wallHit;
            return c;
        }

        /** After the lanes ran: this clock's now moves to the latest lane's end; their work goes into the meter. */
        public void join(final Clock[] lanes) {
            long end = Long.MIN_VALUE;
            boolean hit = false;
            for (Clock l : lanes) {
                if (l == null) {
                    continue;
                }
                hit |= l.wallHit;
                end = Math.max(end, l.wallHit ? l.rawNow() : l.now());
                for (int i = 0; i < SITES.length; i++) {
                    counts.addAndGet(i, l.counts.get(i));
                }
                laneWork.addAndGet(l.totalWork());
            }
            if (hit) {
                wallHit = true;
            }
            if (end != Long.MIN_VALUE) {
                final long cur = origin + (long) (work.get() / scale) + joined.get();
                if (end > cur) {
                    joined.addAndGet(end - cur);
                }
            }
        }
    }

    private static final ThreadLocal<Clock> CUR = new ThreadLocal<>();

    /** This thread's clock (null = none, the default). */
    public static Clock current() {
        return CUR.get();
    }

    /** True when this thread has a clock. */
    public static boolean active() {
        return CUR.get() != null;
    }

    /**
     * The time every pilot budget reads: this thread's virtual time with a clock, else {@link System#nanoTime()}.
     */
    public static long nanoTime() {
        final Clock c = CUR.get();
        return c == null ? System.nanoTime() : c.now();
    }

    /** A new clock (not installed) whose virtual time starts at the real now; budgets bind at {@code scale} x work. */
    public static Clock newClock(final double scale) {
        return new Clock(System.nanoTime(), scale);
    }

    /** Install a clock on this thread unless it has one (scale = workScale); returns this thread's clock. */
    public static Clock ensure(final double scale) {
        Clock c = CUR.get();
        if (c == null) {
            c = new Clock(System.nanoTime(), scale);
            CUR.set(c);
        }
        return c;
    }

    /** Make {@code c} this thread's clock (null: none); returns the previous one (a lane or a carried task). */
    public static Clock install(final Clock c) {
        final Clock prev = CUR.get();
        if (c == null) {
            CUR.remove();
        } else {
            CUR.set(c);
        }
        return prev;
    }

    /** {@code n} units at {@code s} on this thread's clock (none = a no-op). */
    public static void tick(final Site s, final long n) {
        final Clock c = CUR.get();
        if (c != null && n > 0) {
            c.charge(s, n);
        }
    }

    public static void tick(final Site s) {
        tick(s, 1L);
    }

    /** One decision's metering and safety wall, from {@link #begin} to {@link #end}. */
    public static final class Span {
        final Clock clock;
        final boolean armed;
        final long work0;
        final long[] counts0;
        final long v0;

        Span(final Clock clock, final boolean armed) {
            this.clock = clock;
            this.armed = armed;
            this.work0 = clock.totalWork();
            this.counts0 = clock.counts();
            this.v0 = clock.rawNow();
        }

        /** Weighted work since begin, reference ms. */
        public double workMs() {
            return (clock.totalWork() - work0) / 1e6;
        }

        /** Counts per site since begin. */
        public long[] counts() {
            final long[] c = clock.counts();
            for (int i = 0; i < c.length; i++) {
                c[i] -= counts0[i];
            }
            return c;
        }

        /** Virtual ms since begin (the work the budgets saw; past a safety-wall cut, the work done until the cut). */
        public double spanMs() {
            return (clock.rawNow() - v0) / 1e6;
        }

        /** The decision passed its safety wall. */
        public boolean wallHit() {
            return clock.wallHit;
        }
    }

    /**
     * Begin a pilot decision on this thread's clock (null without one): arms the safety wall {@code wallMs} from now
     * (real time) unless an outer decision armed one already (the outer wall stays).
     */
    public static Span begin(final long wallMs) {
        final Clock c = CUR.get();
        if (c == null) {
            return null;
        }
        final boolean arm = c.wall == 0 && wallMs > 0;
        if (arm) {
            c.wallHit = false;
            c.wall = System.nanoTime() + wallMs * 1_000_000L;
        }
        return new Span(c, arm);
    }

    /** End the decision begun with {@code s} (null = none): the wall it armed is disarmed; a hit is counted. */
    public static void end(final Span s) {
        if (s == null || !s.armed) {
            return;
        }
        if (s.clock.wallHit) {
            s.clock.wallHits.incrementAndGet();
        }
        s.clock.wall = 0;
        s.clock.wallHit = false;
    }

    /** {@code task} run under the CALLING thread's clock on whichever thread runs it (the task itself without one). */
    public static Runnable carried(final Runnable task) {
        final Clock c = CUR.get();
        if (c == null) {
            return task;
        }
        return () -> {
            final Clock prev = install(c);
            try {
                task.run();
            } finally {
                install(prev);
            }
        };
    }

    /** The site keys in {@code work_counts} order. */
    public static String[] siteKeys() {
        final String[] k = new String[SITES.length];
        for (int i = 0; i < k.length; i++) {
            k[i] = SITES[i].key;
        }
        return k;
    }
}
