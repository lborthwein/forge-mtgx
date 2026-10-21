package forge.game.card;

/**
 * The traits Forge builds lazily, on a card's first query: the land, permanent and aura spells, a land's mana abilities,
 * the loyalty, defense, lore, adventure and omen replacements, and the shield, stun and finality counter replacements.
 * When each is built depends on who asked first (often the AI, deliberating), and building one takes ids from the
 * process-wide counters. A listener may watch each build (an AI tape records where the AI built one, and its forced
 * replay builds it at the same place). With none installed, nothing changes.
 */
public final class LazyTraits {
    public interface Listener {
        /** {@code state}'s trait {@code kind} is about to be built; returns a token for {@link #after}. */
        Object before(CardState state, String kind);

        void after(Object token);
    }

    private static volatile Listener listener;

    private LazyTraits() {
    }

    public static void install(final Listener l) {
        listener = l;
    }

    static Object before(final CardState state, final String kind) {
        final Listener l = listener;
        return l == null ? null : new Object[] {l, l.before(state, kind)};
    }

    static void after(final Object token) {
        if (token != null) {
            final Object[] t = (Object[]) token;
            ((Listener) t[0]).after(t[1]);
        }
    }

    /** Build {@code state}'s trait {@code kind} now, if it is not built yet. */
    public static void build(final CardState state, final String kind) {
        state.buildLazy(kind);
    }
}
