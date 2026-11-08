package forge.game;

/**
 * Engine and copier fixes. Each one changes play only where the old code threw or copied the game wrongly (lane
 * chaos-bugs-1106 and later). Every flag is read at class load and is part of a pilot's identity when on.
 *
 * <p><b>Live line (live-fixes-1107):</b> {@code -Dforge.liveFixes} (default {@code true} on this line only) is the one
 * switch that sets the DEFAULT of every selected bug fix for every seat: the JVM flags {@code forge.copyRobust},
 * {@code forge.copyAltCost}, {@code forge.researchFixes}, {@code forge.probeEmptyDraw} (ForcedWinProbe), and the solver keys {@code staleStatics}, {@code turnLimits},
 * {@code lineUncapped} ({@code ForcedWinSolver.Config.fromProps}). An explicit flag or key still wins.
 * {@code -Dforge.liveFixes=false} is the live jar before these fixes, byte for byte.
 */
public final class EngineFixes {
    private static boolean liveFixes = Boolean.parseBoolean(System.getProperty("forge.liveFixes", "true"));

    /** A fix flag: its own property when set, else {@link #liveFixes()}. */
    private static boolean flag(final String property) {
        final String v = System.getProperty(property);
        return v == null ? liveFixes : Boolean.parseBoolean(v);
    }

    /** A fix flag of another module: its own property when set, else {@link #liveFixes()} (read once, at the caller's class load). */
    public static boolean liveFlag(final String property) {
        return flag(property);
    }

    private static boolean copyRobust = flag("forge.copyRobust");

    private EngineFixes() {
    }

    /** live-fixes-1107: the default of every selected fix (see the class comment). */
    public static boolean liveFixes() {
        return liveFixes;
    }

    /** The startup line: the switch and what each fix resolved to. */
    public static String describe() {
        return "[live-fixes] forge.liveFixes=" + liveFixes + " copyRobust=" + copyRobust + " copyAltCost=" + copyAltCost
                + " researchFixes=" + researchFixes + " probeEmptyDraw=" + flag("forge.probeEmptyDraw") + " solver(staleStatics,turnLimits,lineUncapped)=" + liveFixes
                + " (solver keys: default; an explicit key wins)";
    }

    /** For tests. */
    public static void setLiveFixesForTest(final boolean on) {
        liveFixes = on;
    }

    public static boolean copyRobust() {
        return copyRobust;
    }

    /**
     * loop-exec-1107 ({@code -Dforge.copyAltCost=true}): a game copy rebuilds a spell cast for an alternative cost that a
     * keyword grants only in another zone (escape from the graveyard, granted by a static) from the card's own spell,
     * keeping the alternative cost. Without it the copy threw ("stack copy: no ability"), and every play-out of that
     * position failed.
     */
    private static boolean copyAltCost = flag("forge.copyAltCost");

    public static boolean copyAltCost() {
        return copyAltCost;
    }

    /** For tests. */
    public static void setCopyAltCostForTest(final boolean on) {
        copyAltCost = on;
    }

    /**
     * unified-jar-1106 ({@code -Dforge.researchFixes=true}): the research line's copier fixes, keyed. Live port
     * (live-fixes-1107) sites: GameCopier's copy-token prototype and owned trait states, effect source ability and the
     * "gone" stack refusal; LookaheadSearch's belief-world static refresh and the shown-on-stack filter. The split-LEAVES
     * batches of the research line are not on the live line.
     */
    private static boolean researchFixes = flag("forge.researchFixes");

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
