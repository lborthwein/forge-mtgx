package forge.ai.simulation;

/** Test-scope access to LookaheadSearch's package-private J1 test hook (lane block-search-1019). */
public final class LookaheadTestHooks {
    private LookaheadTestHooks() {
    }

    /** At the decision whose line index it accepts, the candidate to play instead of the search's (null = none). */
    public static void setOverride(final java.util.function.IntUnaryOperator o) {
        LookaheadSearch.testOverride = o;
    }

    /** Before the decision whose line index it accepts, the search's own counter advances once more (null = none). */
    public static void setBump(final java.util.function.IntPredicate b) {
        LookaheadSearch.testBump = b;
    }
}
