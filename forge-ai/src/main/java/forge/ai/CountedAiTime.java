package forge.ai;

import forge.game.WorkClock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lane ai-timeout-1108 (ledger rows 77/79): Forge AI's evaluation budget as COUNTED work, under the one default-off key
 * {@code -Dforge.countedTime=true} ({@link WorkClock#COUNTED_TIME}).
 *
 * <p>Off (the default): {@link #check} returns at once (no allowance is ever opened) and Forge AI's priority evaluation
 * waits {@code Game.AI_TIMEOUT} wall seconds, as before.
 *
 * <p>On: the evaluation runs under a {@link WorkClock.Allowance} of {@code AI_TIMEOUT} seconds of reference work (times
 * {@link WorkClock#AI_SCALE}). {@link WorkClock#tick} charges every engine and AI site at its calibrated weight (the
 * {@code forge.workClock.weights} in effect, the pilot's work-unit weights), so the same position spends the same
 * allowance on any host and at any load. Forge AI's own sites ({@code canPlayAndPayFor}, {@code evaluateBoardPosition},
 * {@code getPossibleBlockers}) call {@link #check}: past the limit it throws {@link Spent}, and the evaluation returns no
 * play, which is what the wall timeout returned. A spent allowance stays spent; engine sites only charge, never throw.
 */
public final class CountedAiTime {
    private CountedAiTime() {
    }

    /** Evaluations stopped by a spent allowance in this JVM (receipts, {@code [counted-time]} lines). */
    public static final AtomicLong SPENT = new AtomicLong();
    /** Evaluations stopped by the last-resort real-time guard in this JVM (should stay 0). */
    public static final AtomicLong GUARD_HITS = new AtomicLong();

    /** Thrown at a Forge-AI site once the allowance is spent. No stack trace: it is control flow. */
    public static final class Spent extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Spent(final String why) {
            super(why, null, false, false);
        }
    }

    /** The allowance for one evaluation under an {@code aiTimeoutSec} game, reference ns. */
    public static long allowanceNs(final int aiTimeoutSec) {
        return (long) (Math.max(1, aiTimeoutSec) * 1_000_000_000.0 * WorkClock.AI_SCALE);
    }

    /** At a Forge-AI site: throw {@link Spent} when this thread's allowance (or an outer one) is spent. */
    public static void check(final String site) {
        final WorkClock.Allowance a = WorkClock.allowance();
        if (a != null && a.spent()) {
            throw new Spent("counted AI time spent at " + site + " (" + a.limit + " ref ns)");
        }
    }

    /** Between evaluation steps: this thread's allowance is open and spent. */
    public static boolean spent() {
        final WorkClock.Allowance a = WorkClock.allowance();
        return a != null && a.spent();
    }
}
