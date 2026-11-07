package forge.game;

/**
 * Default-off engine and copier fixes. Each one changes play only where the old code threw (lane chaos-bugs-1106).
 * {@code -Dforge.copyRobust=true}, read at class load. The flag is part of a pilot's identity when on.
 */
public final class EngineFixes {
    private static boolean copyRobust = Boolean.getBoolean("forge.copyRobust");

    private EngineFixes() {
    }

    public static boolean copyRobust() {
        return copyRobust;
    }

    /**
     * loop-exec-1107 ({@code -Dforge.copyAltCost=true}, off by default): a game copy rebuilds a spell cast for an alternative
     * cost that a keyword grants only in another zone (escape from the graveyard, granted by a static) from the card's own
     * spell, keeping the alternative cost. Without it the copy threw ("stack copy: no ability"), and every play-out of that
     * position failed.
     */
    private static boolean copyAltCost = Boolean.getBoolean("forge.copyAltCost");

    public static boolean copyAltCost() {
        return copyAltCost;
    }

    /** For tests. */
    public static void setCopyAltCostForTest(final boolean on) {
        copyAltCost = on;
    }

    /** For tests. */
    public static void setCopyRobustForTest(final boolean on) {
        copyRobust = on;
    }
}
