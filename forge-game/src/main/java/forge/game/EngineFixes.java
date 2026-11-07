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

    /** For tests. */
    public static void setCopyRobustForTest(final boolean on) {
        copyRobust = on;
    }
}
