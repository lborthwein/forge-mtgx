package forge.game.mana;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/** Exact-object removal used only when undoing a mana activation. */
final class ManaUndoSelection {
    private ManaUndoSelection() { }

    /** Validate the complete multiset before removing anything. Mana.equals is
     * deliberately not used: it can equate mana from different (including snow)
     * sources. Callers serialize game mutations, as for other mana-pool writes. */
    static <T> boolean removeExact(final Collection<T> pool, final Collection<T> produced) {
        if (produced.isEmpty()) { return false; }
        final Map<T, Integer> needed = new IdentityHashMap<>();
        for (T unit : produced) { needed.merge(unit, 1, Integer::sum); }
        final Map<T, Integer> available = new IdentityHashMap<>();
        for (T unit : pool) { available.merge(unit, 1, Integer::sum); }
        for (Map.Entry<T, Integer> entry : needed.entrySet()) {
            if (available.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        for (Iterator<T> it = pool.iterator(); it.hasNext() && !needed.isEmpty();) {
            final T unit = it.next();
            final Integer count = needed.get(unit);
            if (count == null) { continue; }
            it.remove();
            if (count == 1) { needed.remove(unit); }
            else { needed.put(unit, count - 1); }
        }
        return true;
    }
}
