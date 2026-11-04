package forge.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Scoped id counters for Forge's process-wide object ids (Game, SpellAbility, Trigger,
 * ReplacementEffect, StaticAbility, stack instances, combat views).
 *
 * <p>Those ids feed {@code hashCode()}, so they decide hash-set iteration order and with it some
 * AI choices. A look-ahead search creates thousands of such objects in copied games; drawing their
 * ids from the global counters would shift every id the live game creates afterwards and change
 * the live game. Inside a scope (per thread, inherited by child threads) ids come from the
 * scope's own counters, far above any live id; outside a scope nothing changes.
 */
public final class IdScope {
    public enum Kind { GAME, SPELL_ABILITY, STACK_INSTANCE, TRIGGER, REPLACEMENT, STATIC, COMBAT_VIEW }

    public static final int NONE = Integer.MIN_VALUE;
    private static final int BASE = 1_000_000_000;
    private static final InheritableThreadLocal<AtomicInteger[]> scope = new InheritableThreadLocal<>();

    private IdScope() {
    }

    public static void open() {
        AtomicInteger[] a = new AtomicInteger[Kind.values().length];
        for (int i = 0; i < a.length; i++) {
            a[i] = new AtomicInteger(BASE);
        }
        scope.set(a);
    }

    public static void close() {
        scope.remove();
    }

    /** This thread's scope (for {@link #install} on another thread), or null. */
    public static Object capture() {
        return scope.get();
    }

    /** Make {@code captured} (from {@link #capture}) this thread's scope; null removes it. */
    public static void install(Object captured) {
        if (captured == null) {
            scope.remove();
        } else {
            scope.set((AtomicInteger[]) captured);
        }
    }

    /** Lane shared-engine-1103: set a captured scope's counter of {@code k} to {@code value} (its last id handed out). */
    public static void set(Object captured, Kind k, int value) {
        ((AtomicInteger[]) captured)[k.ordinal()].set(k == Kind.COMBAT_VIEW ? -value - 1 : value);
    }

    /**
     * A captured scope's counters, the last id each handed out (COMBAT_VIEW as a positive count). Lane
     * shared-engine-1104: from research c7c6e7ed21a (bvw-decision-log-1027), for the multi-game host; unused otherwise.
     */
    public static int[] peek(Object captured) {
        AtomicInteger[] a = (AtomicInteger[]) captured;
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = i == Kind.COMBAT_VIEW.ordinal() ? -(a[i].get() + 1) : a[i].get();
        }
        return out;
    }

    /**
     * A new scope whose counters stand where {@code counters} ({@link #peek}'s form) says. Lane shared-engine-1104: from
     * research c7c6e7ed21a; the multi-game host starts each game's scope where a lone JVM's counters stand after init.
     */
    public static Object scopeAt(int[] counters) {
        AtomicInteger[] a = new AtomicInteger[Kind.values().length];
        for (int i = 0; i < a.length; i++) {
            int v = i < counters.length ? counters[i] : BASE;
            a[i] = new AtomicInteger(i == Kind.COMBAT_VIEW.ordinal() ? -v - 1 : v);
        }
        return a;
    }

    public static boolean isOpen() {
        return scope.get() != null;
    }

    /** The next scoped id of this kind, or {@link #NONE} when no scope is open. */
    public static int next(Kind k) {
        AtomicInteger[] a = scope.get();
        if (a == null) {
            if (GameContext.hosting()) {
                GameContext.noteUnscopedId(k.name()); // lane shared-engine-1103: an id drawn outside every hosted game
            }
            return NONE;
        }
        return k == Kind.COMBAT_VIEW ? -a[k.ordinal()].incrementAndGet() : a[k.ordinal()].incrementAndGet();
    }

    /** Run {@code body} in a fresh scope (restoring whatever scope the thread had). */
    public static <T> T detached(Supplier<T> body) {
        AtomicInteger[] prev = scope.get();
        open();
        try {
            return body.get();
        } finally {
            if (prev == null) {
                scope.remove();
            } else {
                scope.set(prev);
            }
        }
    }
}
