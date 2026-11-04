package forge.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lane shared-engine-1103: ONE game's mutable process-wide state, for a host that runs several games in one JVM over one
 * shared card database ({@code forge.interactive.InteractiveHost}). Off unless that host calls {@link #enableHosting()}:
 * until then {@link #current()} is null, {@link #wrap} returns its argument and nothing in Forge behaves differently.
 *
 * <p>A game's state is carried in thread-locals the engine already scopes: the game's random stream
 * ({@link MyRandom#setThreadRandom}), its id counters ({@link IdScope}), the AI cache scope ({@code forge.ai.AiCache}),
 * plus this object, which holds the game's {@link ScopedSlot} values (its GUI binding, its stderr, its config-derived
 * statics). Child threads inherit them ({@link InheritableThreadLocal}); work handed to a POOLED thread (Forge's game
 * thread pool, its delayed pool, the EDT) does not, so {@link ThreadUtil} and the host's GUI {@link #wrap} each task with
 * the submitting thread's state and put the worker's own back afterwards.</p>
 */
public final class GameContext {
    /** One piece of thread-carried game state: what {@link #wrap} snapshots on the caller and installs on the worker. */
    public interface Carrier {
        Object capture();

        void install(Object captured);
    }

    private static volatile boolean hosting;
    private static final InheritableThreadLocal<GameContext> CURRENT = new InheritableThreadLocal<>();
    private static final List<Carrier> CARRIERS = new CopyOnWriteArrayList<>();

    static {
        CARRIERS.add(new Carrier() {
            @Override
            public Object capture() {
                return CURRENT.get();
            }

            @Override
            public void install(final Object captured) {
                if (captured == null) {
                    CURRENT.remove();
                } else {
                    CURRENT.set((GameContext) captured);
                }
            }
        });
        CARRIERS.add(new Carrier() {
            @Override
            public Object capture() {
                return MyRandom.getThreadRandom();
            }

            @Override
            public void install(final Object captured) {
                MyRandom.setThreadRandom((java.util.Random) captured);
            }
        });
        CARRIERS.add(new Carrier() {
            @Override
            public Object capture() {
                return IdScope.capture();
            }

            @Override
            public void install(final Object captured) {
                IdScope.install(captured);
            }
        });
    }

    private static final Map<String, java.util.concurrent.atomic.AtomicLong> UNSCOPED_IDS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> UNSCOPED_REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Hosting only: an object id drawn from the process-wide counters (a thread that carried no game). Counted per kind;
     * the first such draw per (kind, thread name) is reported with its stack on the host's stderr.
     */
    static void noteUnscopedId(final String kind) {
        UNSCOPED_IDS.computeIfAbsent(kind, x -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        final String where = kind + "@" + Thread.currentThread().getName().replaceAll("[0-9]+", "#");
        if (UNSCOPED_REPORTED.add(where)) {
            final Throwable t = new Throwable("[forge.host] unscoped " + where);
            t.printStackTrace(System.err);
        }
    }

    /** Hosting only: the unscoped id draws so far, per kind. */
    public static Map<String, Long> unscopedIds() {
        final Map<String, Long> out = new java.util.TreeMap<>();
        UNSCOPED_IDS.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    private final String name;
    private final Map<ScopedSlot<?>, Object> slots = new ConcurrentHashMap<>();

    private GameContext(final String name) {
        this.name = name;
    }

    /** The multi-game host only, once, before its first game: from then on games are scoped and pooled work carried. */
    public static void enableHosting() {
        hosting = true;
    }

    public static boolean hosting() {
        return hosting;
    }

    /** Tests only: back to a one-game process (the extra carriers a test registered are dropped too). */
    public static void stopHostingForTests() {
        hosting = false;
        CURRENT.remove();
        while (CARRIERS.size() > 3) {
            CARRIERS.remove(CARRIERS.size() - 1);
        }
    }

    /** Another module's carrier (the AI cache scope); the host registers it before its first game. */
    public static void register(final Carrier carrier) {
        CARRIERS.add(carrier);
    }

    /** This thread's game, or null (always null unless hosting). */
    public static GameContext current() {
        return hosting ? CURRENT.get() : null;
    }

    /** Make a new game context this thread's (and its future children's). Hosting only. */
    public static GameContext open(final String name) {
        if (!hosting) {
            throw new IllegalStateException("GameContext.open outside a multi-game host");
        }
        final GameContext context = new GameContext(name);
        CURRENT.set(context);
        return context;
    }

    /** Clear every carried value on this thread (a host connection thread when its game is over). */
    public static void clearThread() {
        for (Carrier carrier : CARRIERS) {
            carrier.install(null);
        }
    }

    public String name() {
        return name;
    }

    /** Lane shared-engine-1104: what this game holds until it ends (a lone JVM's exit frees it; a host must). */
    private final List<Runnable> endHooks = new CopyOnWriteArrayList<>();

    /**
     * Hosting: run {@code hook} when the calling thread's game ends (e.g. stop a look-ahead search's worker pool, whose
     * threads a lone JVM's exit stops). Off, or on a thread of no game: nothing (the process exit is the game's end).
     */
    public static void onGameEnd(final Runnable hook) {
        final GameContext context = current();
        if (context != null && hook != null) {
            context.endHooks.add(hook);
        }
    }

    /** The host, once its game is over: every {@link #onGameEnd} hook, in order; one failing never skips the rest. */
    public void end() {
        for (Runnable hook : endHooks) {
            try {
                hook.run();
            } catch (Throwable t) {
                System.err.println("[forge.host] game " + name + " end hook failed: " + t);
            }
        }
        endHooks.clear();
        slots.clear();
    }

    /** This game's own id counters ({@link IdScope#capture} form), the ones a lone JVM's process-wide counters are. */
    private volatile Object gameIds;

    public void setGameIds(final Object captured) {
        gameIds = captured;
    }

    /**
     * Hosting: the calling game's own id counters (what a write to the process-wide counters means for a game that
     * has a JVM of its own, e.g. a match resetting its trigger ids); null outside a hosted game: use the process-wide
     * counters, as always.
     */
    public static Object gameIds() {
        final GameContext context = current();
        return context != null ? context.gameIds : null;
    }

    @SuppressWarnings("unchecked")
    <T> T get(final ScopedSlot<T> slot) {
        return (T) slots.get(slot);
    }

    <T> void set(final ScopedSlot<T> slot, final T value) {
        if (value == null) {
            slots.remove(slot);
        } else {
            slots.put(slot, value);
        }
    }

    private static Object[] captureAll() {
        final Object[] out = new Object[CARRIERS.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = CARRIERS.get(i).capture();
        }
        return out;
    }

    private static void installAll(final Object[] captured) {
        for (int i = 0; i < captured.length && i < CARRIERS.size(); i++) {
            CARRIERS.get(i).install(captured[i]);
        }
    }

    /** {@code task} run with the calling thread's game state, the worker's own restored after; off: {@code task}. */
    public static Runnable wrap(final Runnable task) {
        if (!hosting || task == null) {
            return task;
        }
        final Object[] caller = captureAll();
        return () -> {
            final Object[] worker = captureAll();
            installAll(caller);
            try {
                task.run();
            } finally {
                installAll(worker);
            }
        };
    }

    /** {@link #wrap(Runnable)} for a {@link Callable}. */
    public static <T> Callable<T> wrap(final Callable<T> task) {
        if (!hosting || task == null) {
            return task;
        }
        final Object[] caller = captureAll();
        return () -> {
            final Object[] worker = captureAll();
            installAll(caller);
            try {
                return task.call();
            } finally {
                installAll(worker);
            }
        };
    }
}
