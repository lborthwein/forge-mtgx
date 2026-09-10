package forge.game.mana;

import com.google.common.collect.ArrayListMultimap;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

public class ManaUndoSelectionTest {
    /** Match the problematic equality contract: snow/source provenance differs,
     * yet equals treats the same-colored units as interchangeable. */
    private record Unit(String color, boolean snow) {
        @Override public boolean equals(Object other) {
            return other instanceof Unit unit && color.equals(unit.color);
        }
        @Override public int hashCode() { return color.hashCode(); }
    }

    @Test
    public void undoSnowWhiteLeavesEarlierPlainWhiteInLivePool() {
        final Unit plain = new Unit("W", false);
        final Unit snow = new Unit("W", true);
        AssertJUnit.assertEquals(plain, snow);
        final ArrayListMultimap<String, Unit> pool = ArrayListMultimap.create();
        pool.put("W", plain);
        pool.put("W", snow);
        AssertJUnit.assertTrue(ManaUndoSelection.removeExact(pool.values(), List.of(snow)));
        AssertJUnit.assertEquals(1, pool.size());
        AssertJUnit.assertSame(plain, pool.get("W").get(0));
    }

    @Test
    public void removesRequestedMultiplicityWithoutTouchingEqualUnits() {
        final Unit unit = new Unit("W", false);
        final Unit other = new Unit("W", false);
        final List<Unit> pool = new ArrayList<>(List.of(other, unit, unit, unit));
        AssertJUnit.assertTrue(ManaUndoSelection.removeExact(pool, List.of(unit, unit)));
        AssertJUnit.assertEquals(2, pool.size());
        AssertJUnit.assertSame(other, pool.get(0));
        AssertJUnit.assertSame(unit, pool.get(1));
    }

    @Test
    public void missingOneProducedObjectDoesNotRemoveAnAvailablePrefix() {
        final Unit present = new Unit("W", false);
        final Unit missing = new Unit("W", true);
        final List<Unit> pool = new ArrayList<>(List.of(present));
        AssertJUnit.assertFalse(ManaUndoSelection.removeExact(pool, List.of(present, missing)));
        AssertJUnit.assertEquals(1, pool.size());
        AssertJUnit.assertSame(present, pool.get(0));
    }

    @Test
    public void insufficientMultiplicityDoesNotMutate() {
        final Unit unit = new Unit("W", false);
        final List<Unit> pool = new ArrayList<>(List.of(unit));
        AssertJUnit.assertFalse(ManaUndoSelection.removeExact(pool, List.of(unit, unit)));
        AssertJUnit.assertEquals(1, pool.size());
        AssertJUnit.assertSame(unit, pool.get(0));
        AssertJUnit.assertFalse(ManaUndoSelection.removeExact(pool, List.of()));
        AssertJUnit.assertEquals(1, pool.size());
    }
}
