package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;

/**
 * sdcc-exact-known-1117 (research; empty unless an exact start names known cards): the own-library cards a seat KNOWS at
 * an exact start (bench exact_start {@code known_top} / {@code known_bottom}): the order it placed them in before the
 * recorded position (a pile, a put-back, a scry), which an installed state otherwise forgets. Read by the live game's
 * solver when it starts watching the library ({@link ForcedWinSolver#watch}), the same moment it would have seen those
 * cards placed. Only the named seat's own entry exists; the other seat learns nothing. A shuffle of that library drops
 * the entry. Keyed by the live game (a copy is another game: never read there).
 */
public final class ExactStartKnown {
    private ExactStartKnown() {
    }

    /** Per seat: the known top ids (top first) and the known bottom ids (bottom last). */
    public static final class Entry {
        public final List<Integer> top, bottom;

        Entry(final List<Integer> top, final List<Integer> bottom) {
            this.top = Collections.unmodifiableList(new ArrayList<>(top));
            this.bottom = Collections.unmodifiableList(new ArrayList<>(bottom));
        }
    }

    private static final Map<Game, Map<Integer, Entry>> BY_GAME = new WeakHashMap<>();

    /** Drops a seat's entry when its library is shuffled. */
    public static final class ShuffleWatch {
        final Game game;

        ShuffleWatch(final Game game) {
            this.game = game;
        }

        @Subscribe
        public void on(final forge.game.event.GameEventShuffle e) {
            if (e.player() != null) {
                synchronized (BY_GAME) {
                    final Map<Integer, Entry> m = BY_GAME.get(game);
                    if (m != null) {
                        m.remove(e.player().getId());
                    }
                }
            }
        }
    }

    /** Record that {@code p} knows these cards of its own library (the game thread, at the install). */
    public static void put(final Game g, final Player p, final List<Card> top, final List<Card> bottom) {
        final List<Integer> t = new ArrayList<>(), b = new ArrayList<>();
        for (Card c : top) {
            t.add(c.getId());
        }
        for (Card c : bottom) {
            b.add(c.getId());
        }
        synchronized (BY_GAME) {
            Map<Integer, Entry> m = BY_GAME.get(g);
            if (m == null) {
                m = new HashMap<>();
                BY_GAME.put(g, m);
                g.subscribeToEvents(new ShuffleWatch(g));
            }
            m.put(p.getId(), new Entry(t, b));
        }
    }

    /** {@code p}'s entry in game {@code g}, or null (none: nothing named, shuffled since, or another game). */
    public static Entry get(final Game g, final Player p) {
        if (g == null || p == null) {
            return null;
        }
        synchronized (BY_GAME) {
            final Map<Integer, Entry> m = BY_GAME.get(g);
            return m == null ? null : m.get(p.getId());
        }
    }
}
