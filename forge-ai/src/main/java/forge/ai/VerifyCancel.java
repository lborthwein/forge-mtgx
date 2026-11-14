package forge.ai;

/**
 * reach-verify-1114: marks the thread of an engine check of a planner line (ForcedWinSolver's reach.verify play-outs; off
 * = never set, and nothing changes). While it is set, an AI eval wait that is interrupted ({@link AiController}) puts the
 * interrupt back on the thread, so that a check whose look-ahead world was cancelled stops at its next step instead of
 * running on to its wall. Only the verify path sets it.
 */
public final class VerifyCancel {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private VerifyCancel() {
    }

    /** This thread is inside an engine check of a planner line. */
    public static boolean active() {
        return ACTIVE.get();
    }

    /** Set or clear the mark; returns the previous value (to restore). */
    public static boolean set(final boolean on) {
        final boolean prev = ACTIVE.get();
        ACTIVE.set(on);
        return prev;
    }

    /** The check should stop: the mark is set and this thread was interrupted. */
    public static boolean cancelled() {
        return ACTIVE.get() && Thread.currentThread().isInterrupted();
    }
}
