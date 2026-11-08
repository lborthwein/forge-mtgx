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

    /**
     * unified-jar-1106 ({@code -Dforge.researchFixes=true}, read at class load; off = the live line's copies, byte for byte):
     * the research line's copier fixes, keyed. Live port (live-fixes-1107) sites: GameCopier's copy-token prototype and owned
     * trait states, effect source ability and the "gone" stack refusal; LookaheadSearch's belief-world static refresh and the
     * shown-on-stack filter. The split-LEAVES batches of the research line are not on the live line.
     */
    private static boolean researchFixes = Boolean.getBoolean("forge.researchFixes");

    public static boolean researchFixes() {
        return researchFixes;
    }

    /** For tests. */
    public static void setResearchFixesForTest(final boolean on) {
        researchFixes = on;
    }

    /** For tests. */
    public static void setCopyRobustForTest(final boolean on) {
        copyRobust = on;
    }
}
