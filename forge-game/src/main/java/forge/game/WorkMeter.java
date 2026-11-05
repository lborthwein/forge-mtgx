package forge.game;

/**
 * Lane void-causes-1104: the hang watchdog's meter. A game is HUNG when its thread spends {@code budget} units of
 * engine work with no progress in the live game. Units are counts, never time, so the same game trips at the same
 * point on any host and in its replays (the replay determinism class): one unit per priority-loop step of any game on
 * the thread (the live game and the search's copies), per state-based-action check, per game copy, per Forge AI work
 * tick and per object a copy's leak walk visits. Progress is a priority decision taken in the live game
 * ({@link #progress}, called by the host).
 *
 * <p>Off unless a host opens a meter on the game thread ({@link #open}); with none open every {@link #tick} returns at
 * once and nothing changes. Once tripped the meter stays tripped: every later tick throws {@link Hung} again, so a
 * catch that swallows it on the way (a search's own error guard) cannot keep the game running.
 *
 * <p>Measurement only: no decision reads it; it ends a hung game, which is then recorded as void "hang".
 */
public final class WorkMeter {
    /** Thrown on a tripped meter. An Error, so the engine's catch (Exception) guards do not swallow it. */
    public static final class Hung extends Error {
        private static final long serialVersionUID = 1L;
        public final long work;
        public final long budget;
        public final String site;

        Hung(final long work, final long budget, final String site) {
            super("hang: " + work + " engine work units with no live progress (budget " + budget + ") at " + site);
            this.work = work;
            this.budget = budget;
            this.site = site;
        }
    }

    private static final ThreadLocal<WorkMeter> CUR = new ThreadLocal<>();

    public final long budget;
    private long sinceProgress;
    private long total;
    private long maxSinceProgress;
    private long progressEvents;
    private String trippedAt;

    private WorkMeter(final long budget) {
        this.budget = budget;
    }

    /** Open a meter of {@code budget} units on this thread ({@code budget <= 0}: none, and null is returned). */
    public static WorkMeter open(final long budget) {
        if (budget <= 0) {
            CUR.remove();
            return null;
        }
        final WorkMeter m = new WorkMeter(budget);
        CUR.set(m);
        return m;
    }

    /** Close this thread's meter. */
    public static void close() {
        CUR.remove();
    }

    /** This thread's meter, or null. */
    public static WorkMeter current() {
        return CUR.get();
    }

    /** Make {@code m} this thread's meter (null: none); returns the previous one (a task carried to another thread). */
    public static WorkMeter install(final WorkMeter m) {
        final WorkMeter prev = CUR.get();
        if (m == null) {
            CUR.remove();
        } else {
            CUR.set(m);
        }
        return prev;
    }

    /** One unit of engine work at {@code site}; throws {@link Hung} once the meter trips. */
    public static void tick(final String site) {
        final WorkMeter m = CUR.get();
        if (m != null) {
            m.charge(site);
        }
    }

    private void charge(final String site) {
        final long u;
        synchronized (this) {
            total++;
            u = ++sinceProgress;
            if (u > maxSinceProgress) {
                maxSinceProgress = u;
            }
            if (trippedAt == null && u > budget) {
                trippedAt = site;
            }
            if (trippedAt == null) {
                return;
            }
        }
        throw new Hung(u, budget, trippedAt);
    }

    /** The live game made progress (a priority decision). A tripped meter stays tripped. */
    public synchronized void progress() {
        sinceProgress = 0;
        progressEvents++;
    }

    public synchronized boolean tripped() {
        return trippedAt != null;
    }

    /** {@code {budget, total, max_since_progress, progress, tripped_at}} for the game's record (insertion order). */
    public synchronized java.util.Map<String, Object> summary() {
        final java.util.Map<String, Object> o = new java.util.LinkedHashMap<>();
        o.put("budget", budget);
        o.put("total", total);
        o.put("max_since_progress", maxSinceProgress);
        o.put("progress", progressEvents);
        if (trippedAt != null) {
            o.put("tripped_at", trippedAt);
        }
        return o;
    }
}
