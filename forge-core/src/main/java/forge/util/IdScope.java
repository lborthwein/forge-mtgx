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

    public static boolean isOpen() {
        return scope.get() != null;
    }

    /** The next scoped id of this kind, or {@link #NONE} when no scope is open. */
    public static int next(Kind k) {
        AtomicInteger[] a = scope.get();
        if (a == null) {
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
