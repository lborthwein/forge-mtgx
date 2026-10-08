package forge.ai;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU accounting across the threads one game uses (lane s1-search-1007; measurement only, never a decision input).
 *
 * <p>A thread may carry accounts ({@link #set}); work it hands to a short-lived thread is charged to the same accounts
 * when the hand-off goes through {@link #charged} (Forge AI's "Game AI Eval" thread, whose CPU no per-thread clock of
 * the asking thread sees). With no accounts set, which is every path but an RL search seat's game, {@link #charged}
 * returns the task itself: nothing changes.
 */
public final class CpuAccount {
    private CpuAccount() {
    }

    private static final ThreadMXBean TMX = ManagementFactory.getThreadMXBean();
    private static final ThreadLocal<AtomicLong[]> ACC = new ThreadLocal<>();

    /** This thread's accounts (null = none). */
    public static AtomicLong[] get() {
        return ACC.get();
    }

    /** Set (null clears) this thread's accounts. */
    public static void set(final AtomicLong[] accounts) {
        if (accounts == null || accounts.length == 0) {
            ACC.remove();
        } else {
            ACC.set(accounts);
        }
    }

    /** {@code accounts} plus one more (a new array; null and empty mean none). */
    public static AtomicLong[] plus(final AtomicLong[] accounts, final AtomicLong more) {
        if (accounts == null || accounts.length == 0) {
            return new AtomicLong[] {more};
        }
        final AtomicLong[] out = Arrays.copyOf(accounts, accounts.length + 1);
        out[accounts.length] = more;
        return out;
    }

    /** The current thread's CPU time in ns. */
    public static long now() {
        return TMX.getCurrentThreadCpuTime();
    }

    public static void charge(final AtomicLong[] accounts, final long nanos) {
        if (accounts == null || nanos <= 0) {
            return;
        }
        for (AtomicLong a : accounts) {
            a.addAndGet(nanos);
        }
    }

    /**
     * {@code task}, charging the CPU of whichever thread runs it to the accounts the CALLING thread holds now; the task
     * itself when the calling thread holds none.
     */
    public static Runnable charged(final Runnable task) {
        final AtomicLong[] accounts = ACC.get();
        if (accounts == null) {
            return task;
        }
        return () -> {
            final long c0 = now();
            try {
                task.run();
            } finally {
                charge(accounts, now() - c0);
            }
        };
    }
}
