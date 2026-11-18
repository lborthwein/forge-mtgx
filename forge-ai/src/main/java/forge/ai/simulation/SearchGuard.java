package forge.ai.simulation;

import java.util.IdentityHashMap;
import java.util.Map;

import forge.game.Game;

/**
 * nestfix-1118 (a rule of the search, not of a card): while a seat's search, planner or solver runs over a REAL game, no
 * decision about that real game may reach a real seat's controller. Searches work on copies, whose controllers answer
 * themselves; an engine call made on the real game during a search (the bench crash: ForcedWinSolver.spellTaxes ran
 * CostAdjustment on the real game, and Emerge asked the real seat which artifact to sacrifice, a second DECIDE at the
 * same dec_idx) is refused with {@link RealSeatAsk}: the branch that made it ends (the search's own failure path), and
 * the refusal is counted per game ({@link #takeBlocked}). Asks about a copy are never refused (copies are other Game
 * objects). Never throws outside an active scope.
 */
public final class SearchGuard {
    private SearchGuard() {
    }

    /** The refusal: a search asked a real seat to decide. Unchecked; cheap (no stack). */
    public static final class RealSeatAsk extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RealSeatAsk(final String what) {
            super("search asked a real seat: " + what, null, false, false);
        }
    }

    private static final Map<Game, int[]> ACTIVE = new IdentityHashMap<>();
    private static final Map<Game, long[]> BLOCKED = new IdentityHashMap<>();
    private static final Map<Game, String> LAST = new IdentityHashMap<>();
    private static volatile int activeCount;

    /** Opens a search scope over the real game {@code g} (nests; null: no-op). Pair with {@link #exit}. */
    public static void enter(final Game g) {
        if (g == null) {
            return;
        }
        synchronized (ACTIVE) {
            ACTIVE.computeIfAbsent(g, k -> new int[1])[0]++;
            activeCount = ACTIVE.size();
        }
    }

    public static void exit(final Game g) {
        if (g == null) {
            return;
        }
        synchronized (ACTIVE) {
            final int[] d = ACTIVE.get(g);
            if (d != null && --d[0] <= 0) {
                ACTIVE.remove(g);
            }
            activeCount = ACTIVE.size();
        }
    }

    public static boolean active(final Game g) {
        if (activeCount == 0 || g == null) {
            return false;
        }
        synchronized (ACTIVE) {
            return ACTIVE.containsKey(g);
        }
    }

    /** Called where a real seat would be asked: refuses (and counts) the ask when a search scope is open on {@code g}. */
    public static void check(final Game g, final String what) {
        if (!active(g)) {
            return;
        }
        synchronized (ACTIVE) {
            BLOCKED.computeIfAbsent(g, k -> new long[1])[0]++;
            LAST.put(g, what);
        }
        throw new RealSeatAsk(what);
    }

    private static final Map<Game, long[]> SEEN = new IdentityHashMap<>();

    /**
     * Counted, never refused: a controller call about the real game inside a search that does not ask a seat (the tape
     * controller's inner answerer decides it). Reported with the refusals ({@link #takeSeen}).
     */
    public static void note(final Game g, final String what) {
        if (!active(g)) {
            return;
        }
        synchronized (ACTIVE) {
            SEEN.computeIfAbsent(g, k -> new long[1])[0]++;
        }
    }

    /** The counted (not refused) real-game calls on {@code g} since the last take, and resets them. */
    public static long takeSeen(final Game g) {
        if (g == null) {
            return 0;
        }
        synchronized (ACTIVE) {
            final long[] b = SEEN.remove(g);
            return b == null ? 0 : b[0];
        }
    }

    /** The refusals on {@code g} since the last take, and resets them (0 = none). */
    public static long takeBlocked(final Game g) {
        if (g == null) {
            return 0;
        }
        synchronized (ACTIVE) {
            final long[] b = BLOCKED.remove(g);
            return b == null ? 0 : b[0];
        }
    }

    /** The last refused ask's name on {@code g} (null = none), and forgets it. */
    public static String takeLast(final Game g) {
        if (g == null) {
            return null;
        }
        synchronized (ACTIVE) {
            return LAST.remove(g);
        }
    }

    /** Test hook: no scope open anywhere. */
    static boolean idle() {
        synchronized (ACTIVE) {
            return ACTIVE.isEmpty();
        }
    }
}
