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
     * Lane ai-timeout-1108 (ledger rows 77/79): ONE default-off JVM key, {@code -Dforge.countedTime=true}, that takes real
     * time out of every limit that can change a Forge-AI or pilot choice. With it on:
     * <ul>
     * <li>Forge AI's priority evaluation ({@code AiController.chooseSpellAbilityToPlayFromList}) stops on a counted
     * {@link Allowance} of {@code Game.AI_TIMEOUT} seconds of reference work (the {@link #weights()} of the work it does),
     * not on {@code AI_TIMEOUT} wall seconds;</li>
     * <li>Forge AI's forced-attacker tasks run on the calling thread in attacker order (the {@code sequentialAi} path), not
     * in parallel under an {@code AI_TIMEOUT} wall;</li>
     * <li>the real-time limits that remain (this clock's per-decision safety wall, a search service read timeout, the
     * evaluation's own last-resort wait) are hang guards only: {@link #guardMs} widens them by {@link #GUARD_FACTOR}, so on
     * a slow or loaded host the counted budgets bind first. A guard that still fires is logged ({@code [counted-time]}).</li>
     * </ul>
     * Off (the default): nothing changes, byte for byte. Turning it on changes Forge AI's play wherever a 5 s wall cut an
     * evaluation (and the counted budget cuts where the wall did not), so it is a play change, not an identity change.
     */
    public static final boolean COUNTED_TIME = Boolean.getBoolean("forge.countedTime");
    /** The hang guards' widening under {@link #COUNTED_TIME} ({@code -Dforge.countedTime.guard}, default 8). */
    public static final long GUARD_FACTOR = Math.max(1L, Long.getLong("forge.countedTime.guard", 8L));
    /** Forge AI's counted allowance per evaluation, x AI_TIMEOUT seconds of reference work ({@code -Dforge.countedTime.aiScale}). */
    public static final double AI_SCALE = parseScale(System.getProperty("forge.countedTime.aiScale"));

    private static double parseScale(final String v) {
        final double d = v == null || v.isEmpty() ? 1.0 : Double.parseDouble(v);
        if (!(d > 0)) {
            throw new IllegalArgumentException("forge.countedTime.aiScale must be > 0, not " + v);
        }
        return d;
    }

    /** A real-time limit as it binds now: x {@link #GUARD_FACTOR} under {@link #COUNTED_TIME}, else unchanged (0 stays 0). */
    public static long guardMs(final long ms) {
        return COUNTED_TIME && ms > 0 ? ms * GUARD_FACTOR : ms;
    }

    /** The weight in effect for {@code s}, reference ns per unit. */
    public static long weight(final Site s) {
        return weights[s.ordinal()];
    }

    /**
     * ai-timeout-1108: one Forge-AI evaluation's counted allowance, in reference ns of work ({@link #tick} charges every
     * site at its weight while one is open on the thread). Opened inside another, it charges the outer one too. Spent
     * when its own or an outer allowance's work passes its limit; Forge AI's sites then stop the evaluation.
     */
    public static final class Allowance {
        public final long limit;
        final Allowance parent;
        private final AtomicLong used = new AtomicLong();

        Allowance(final long limit, final Allowance parent) {
            this.limit = limit;
            this.parent = parent;
        }

        void charge(final long w) {
            for (Allowance a = this; a != null; a = a.parent) {
                a.used.addAndGet(w);
            }
        }

        public long used() {
            return used.get();
        }

        /** This allowance's own work is past its limit. */
        public boolean ownSpent() {
            return used.get() > limit;
        }

        /** This one or an outer one is spent. */
        public boolean spent() {
            for (Allowance a = this; a != null; a = a.parent) {
                if (a.ownSpent()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final ThreadLocal<Allowance> ALLOW = new ThreadLocal<>();

    /** This thread's open allowance (null = none, always with the key off). */
    public static Allowance allowance() {
        return ALLOW.get();
    }

    /** Open an allowance of {@code limitRefNs} on this thread (inside the current one); returns the previous, for restore. */
    public static Allowance openAllowance(final long limitRefNs) {
        final Allowance prev = ALLOW.get();
        ALLOW.set(new Allowance(Math.max(1L, limitRefNs), prev));
        return prev;
    }

    public static void restoreAllowance(final Allowance prev) {
        if (prev == null) {
            ALLOW.remove();
        } else {
            ALLOW.set(prev);
        }
    }

    /** True when this thread meters work: a clock or an allowance (callers that skip computing a tick's count). */
    public static boolean metering() {
        return CUR.get() != null || ALLOW.get() != null;
    }

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
    /** Past the wall, each read is this much later (~18 min: more than any budget a deadline adds). */
    static final long POST_WALL_STEP = 1L << 40;
    /** Reads past the wall before the time stops growing (keeps FAR + steps far below overflow). */
    static final long POST_WALL_MAX = 2_000_000L;

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
        /** Counts per site on the critical path: this clock's own plus, at each join, the latest lane's (calibration). */
        private final AtomicLongArray pathCounts = new AtomicLongArray(SITES.length);
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

        /** Reads past the safety wall: each moves virtual time on by {@link #POST_WALL_STEP}. */
        private final AtomicLong postWall = new AtomicLong();

        /**
         * Virtual now: origin + work / scale + joined span. Past the safety wall every read is FAR plus one more
         * {@link #POST_WALL_STEP} than the read before, so a deadline set after the wall (now + budget) has passed by the
         * next read too, and every budget stops.
         */
        public long now() {
            final long w = wall;
            if (w != 0 && System.nanoTime() - w > 0) {
                if (!wallHit) {
                    wallHit = true;
                }
                final long k = postWall.incrementAndGet();
                return k < POST_WALL_MAX ? FAR + k * POST_WALL_STEP : FAR + POST_WALL_MAX * POST_WALL_STEP;
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
            pathCounts.addAndGet(s.ordinal(), n);
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

        /** Critical-path counts per site so far (the work whose weighted sum is this clock's virtual span). */
        public long[] pathCounts() {
            final long[] c = new long[SITES.length];
            for (int i = 0; i < c.length; i++) {
                c[i] = pathCounts.get(i);
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
            Clock latest = null;
            for (Clock l : lanes) {
                if (l == null) {
                    continue;
                }
                hit |= l.wallHit;
                final long e = l.rawNow(); // raw: a lane read past the wall would join FAR
                if (e > end) {
                    end = e;
                    latest = l;
                }
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
                    for (int i = 0; i < SITES.length; i++) {
                        pathCounts.addAndGet(i, latest.pathCounts.get(i));
                    }
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
        final Allowance a = ALLOW.get(); // ai-timeout-1108: none unless forge.countedTime opened one
        if (a != null && n > 0) {
            a.charge(n * weights[s.ordinal()]);
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
        final long[] path0;
        final long v0;

        Span(final Clock clock, final boolean armed) {
            this.clock = clock;
            this.armed = armed;
            this.work0 = clock.totalWork();
            this.counts0 = clock.counts();
            this.path0 = clock.pathCounts();
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

        /** Critical-path counts per site since begin (calibration: their weighted sum is the span). */
        public long[] pathCounts() {
            final long[] c = clock.pathCounts();
            for (int i = 0; i < c.length; i++) {
                c[i] -= path0[i];
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
            c.wall = System.nanoTime() + guardMs(wallMs) * 1_000_000L; // ai-timeout-1108: a hang guard under the key
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
        final Allowance a = ALLOW.get(); // ai-timeout-1108: an open allowance goes too (none with the key off)
        if (c == null && a == null) {
            return task;
        }
        return () -> {
            final Clock prev = c == null ? null : install(c);
            final Allowance prevA = a == null ? null : ALLOW.get();
            if (a != null) {
                ALLOW.set(a);
            }
            try {
                task.run();
            } finally {
                if (a != null) {
                    restoreAllowance(prevA);
                }
                if (c != null) {
                    install(prev);
                }
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
