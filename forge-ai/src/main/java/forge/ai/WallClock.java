package forge.ai;

/**
 * The AI's wall clocks as they bind now (lane forge-continue-1011, "Continue from here").
 *
 * <p>A continued game is rebuilt in a new JVM from its journal: same seed, decks and AI settings, the human's inputs fed
 * back one by one. The AI seat decides again and reproduces the original game unless a time limit cuts one of its
 * decisions differently. While {@link #rebuilding()} every configured wall budget and read timeout is at least
 * {@link #REBUILD_BUDGET_MS}, so none of them binds; at the handover the interactive host switches the AI back to its
 * live budgets ({@code setRebuilding(false)}).</p>
 *
 * <p>Off (the default, and every normal game): every value is returned unchanged. Only the interactive server's
 * {@code -Dforge.interactive.continuation=rebuild} turns it on.</p>
 */
public final class WallClock {
    /** A wall budget or read timeout while rebuilding: at least this long (ms). */
    public static final long REBUILD_BUDGET_MS = 600_000L;

    private static volatile boolean rebuilding;

    private WallClock() {
    }

    public static boolean rebuilding() {
        return rebuilding;
    }

    public static void setRebuilding(final boolean on) {
        rebuilding = on;
    }

    /** A configured wall budget as it binds now: unchanged live; at least REBUILD_BUDGET_MS while rebuilding. 0 stays 0. */
    public static long budgetMs(final long configured) {
        return configured > 0 && rebuilding ? Math.max(configured, REBUILD_BUDGET_MS) : configured;
    }

    /** A socket read timeout as it binds now (same rule; 0 = infinite stays 0). */
    public static int readTimeoutMs(final int configured) {
        return configured > 0 && rebuilding ? (int) Math.max(configured, REBUILD_BUDGET_MS) : configured;
    }
}
