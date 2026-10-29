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
        return detached(BASE, body);
    }

    /**
     * Lane bvw-decision-log-1027: {@link #detached(Supplier)} with the fresh scope's counters starting at {@code base}
     * (the AI tape's decode scope: a range no game scope, look-ahead scope or cube combo probe scope hands out).
     */
    public static <T> T detached(int base, Supplier<T> body) {
        AtomicInteger[] prev = scope.get();
        AtomicInteger[] a = new AtomicInteger[Kind.values().length];
        for (int i = 0; i < a.length; i++) {
            a[i] = new AtomicInteger(base);
        }
        scope.set(a);
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

    /**
     * Lane bvw-decision-log-1027: the counters of a captured scope ({@link #capture}) in {@code GlobalIds}' form: the
     * last id handed out per kind, except the combat views', which count down: the next id it will hand out.
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
     * Lane bvw-decision-log-1027: a new scope ({@link #install}'s form) whose counters stand where {@code counters}
     * ({@link #peek}'s form) says: the next ids it hands out are the ones a scope in that state handed out.
     */
    public static Object scopeAt(int[] counters) {
        AtomicInteger[] a = new AtomicInteger[Kind.values().length];
        for (int i = 0; i < a.length; i++) {
            int v = i < counters.length ? counters[i] : BASE;
            a[i] = new AtomicInteger(i == Kind.COMBAT_VIEW.ordinal() ? -v - 1 : v);
        }
        return a;
    }

    /**
     * Lane bvw-decision-log-1027: move a captured scope's counters forward to {@code to} ({@link #peek}'s form); a
     * counter already past it is left alone. Returns how many were already past.
     */
    public static int restoreForward(Object captured, int[] to) {
        AtomicInteger[] a = (AtomicInteger[]) captured;
        int ahead = 0;
        for (int i = 0; i < a.length && i < to.length; i++) {
            int want = i == Kind.COMBAT_VIEW.ordinal() ? -to[i] - 1 : to[i];
            int now = a[i].get();
            if (now < want) {
                a[i].set(want);
            } else if (now > want) {
                ahead++;
            }
        }
        return ahead;
    }
}
