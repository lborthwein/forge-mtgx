package forge.ai;

import java.util.concurrent.atomic.AtomicLong;

/**
 * rollout-budget-1101 (DESIGN-R3 N4/N9 blocker): a COUNTED budget for Forge AI's own evaluation work while a counted
 * solver decision runs. With no budget open on the thread, which is every path but a budget=counter solver's decision,
 * {@link #tick} returns at once and nothing changes.
 *
 * <p>Work units are counts, never time, so the same position spends the same units on any host: one unit for each
 * spell ability Forge AI weighs for play ({@code AiController.canPlayAndPayFor}), each board evaluation
 * ({@code ComputerUtil.evaluateBoardPosition}, counted before its cache), and each (attacker, blocker) pair the block
 * predictor checks ({@code AiBlockController.getPossibleBlockers}), which is where the B8 hang (job
 * 2988764574:1:150:surveil|0, ETB target choice -> board evaluation -> block prediction, ~90 s CPU per priority call)
 * spent its time.
 *
 * <p>Past the limit the budget is SPENT, and stays spent: every later tick throws {@link Exhausted}, Forge AI's
 * "Game AI Eval" thread hands it back to the asking thread, and the solver's own checks stop new nodes, worlds and
 * lines. The decision is then a cap hit ({@link #CAP}), never a proof.
 */
public final class AiWork {
    private AiWork() {
    }

    /** The cap's name in receipts, meters and label rows. */
    public static final String CAP = "cap:rollout-ai";

    /** Diagnostics only (budget calibration): each counted decision's units to stderr, -Dforge.ai.aiWork.trace=true. */
    public static final boolean TRACE = Boolean.getBoolean("forge.ai.aiWork.trace");

    /** Thrown by {@link #tick} on a spent budget. No stack trace: it is control flow. */
    public static final class Exhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Exhausted(final String why) {
            super(why, null, false, false);
        }
    }

    /** One decision's budget; a budget opened inside another also charges the outer one. */
    public static final class Budget {
        public final long limit;
        final Budget parent;
        private final AtomicLong used = new AtomicLong();
        private volatile String spentAt;

        Budget(final long limit, final Budget parent) {
            this.limit = limit;
            this.parent = parent;
        }

        public long used() {
            return used.get();
        }

        /** Where the budget (or an outer one) was spent; null while it is not. */
        public String spentAt() {
            for (Budget b = this; b != null; b = b.parent) {
                if (b.spentAt != null) {
                    return b.spentAt;
                }
            }
            return null;
        }

        public boolean spent() {
            return spentAt() != null;
        }

        void charge(final String site, final long n) {
            for (Budget b = this; b != null; b = b.parent) {
                if (b.used.addAndGet(n) > b.limit && b.spentAt == null) {
                    b.spentAt = site;
                }
            }
        }
    }

    private static final ThreadLocal<Budget> CUR = new ThreadLocal<>();

    /** This thread's budget (null = none). */
    public static Budget current() {
        return CUR.get();
    }

    /**
     * Open a budget of {@code limit} units on this thread (inside the current one, if any); {@code limit <= 0} opens
     * none. Returns the budget that was current, for {@link #restore}.
     */
    public static Budget open(final long limit) {
        final Budget prev = CUR.get();
        if (limit > 0) {
            CUR.set(new Budget(limit, prev));
        }
        return prev;
    }

    /** Make {@code prev} (from {@link #open}) current again. */
    public static void restore(final Budget prev) {
        if (prev == null) {
            CUR.remove();
        } else {
            CUR.set(prev);
        }
    }

    /** One unit of Forge AI work at {@code site}. */
    public static void tick(final String site) {
        tick(site, 1);
    }

    /** {@code n} units of Forge AI work at {@code site}; throws {@link Exhausted} once the budget is spent. */
    public static void tick(final String site, final long n) {
        final Budget b = CUR.get();
        if (b == null) {
            return;
        }
        b.charge(site, n);
        final String at = b.spentAt();
        if (at != null) {
            throw new Exhausted(CAP + " at " + at + " (" + b.limit + ")");
        }
    }

    /** This thread's budget is open and spent. */
    public static boolean spent() {
        final Budget b = CUR.get();
        return b != null && b.spent();
    }

    /** Throw {@link Exhausted} when this thread's budget is spent (a check between Forge AI calls). */
    public static void check(final String where) {
        final Budget b = CUR.get();
        if (b != null && b.spent()) {
            throw new Exhausted(CAP + " at " + b.spentAt() + " (" + where + ")");
        }
    }

    /**
     * {@code task}, run under the CALLING thread's budget on whichever thread runs it (Forge AI's "Game AI Eval"
     * thread); the task itself when the calling thread has none.
     */
    public static Runnable carried(final Runnable task) {
        final Budget b = CUR.get();
        if (b == null) {
            return task;
        }
        return () -> {
            final Budget prev = CUR.get();
            CUR.set(b);
            try {
                task.run();
            } finally {
                restore(prev);
            }
        };
    }
}
