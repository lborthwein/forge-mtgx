package forge.game;

/**
 * Marks the current thread as building an independent copy of a game (GameCopier).
 *
 * <p>Forge copies traits and costs with {@code Object.clone()}, which leaves the copy sharing some mutable
 * collections with the original: a cost part's paid-card lists, an ability's paid lists, convoke and rollback
 * lists, a trigger's remembered objects, a static ability's ignore lists. Inside one game that sharing is
 * harmless or intended. A copied game that runs on another thread than its original (look-ahead play-outs)
 * must not share them: two play-outs paying the same granted ability's cost raced on
 * {@code CostPartWithList.cardList} (ConcurrentModificationException in {@code reportPaidCardsTo}).
 *
 * <p>While active, the copy methods give the clone its own collections. Outside GameCopier nothing changes.
 */
public final class IndependentCopies {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private IndependentCopies() {
    }

    public static boolean active() {
        return DEPTH.get()[0] > 0;
    }

    public static void enter() {
        DEPTH.get()[0]++;
    }

    public static void exit() {
        DEPTH.get()[0]--;
    }
}
