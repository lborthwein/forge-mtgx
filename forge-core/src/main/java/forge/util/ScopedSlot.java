package forge.util;

/**
 * Lane shared-engine-1103: a process-wide value that is per game in a multi-game host. Outside a hosted game (every
 * process today) it is a plain static field: {@link #get()} and {@link #set} read and write the one global value. On a
 * thread of a hosted game ({@link GameContext#current()} non-null) they read and write that game's own value, which
 * starts unset (null) and is never another game's.
 */
public final class ScopedSlot<T> {
    private volatile T global;

    public T get() {
        final GameContext context = GameContext.current();
        return context != null ? context.get(this) : global;
    }

    public void set(final T value) {
        final GameContext context = GameContext.current();
        if (context != null) {
            context.set(this, value);
        } else {
            global = value;
        }
    }
}
