package forge.interactive;

import java.util.LinkedHashMap;
import java.util.Map;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/** Pure coverage of the modern-combat allocation guard; it starts no game. */
public final class InteractiveCombatAllocationTest {

    private static Map<String, Integer> map(final Object... values) {
        final Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put((String) values[i], (Integer) values[i + 1]);
        }
        return result;
    }

    @Test
    public void modernCombatAllowsArbitraryBlockerSplitsButRetainsTrampleLimit() {
        final Map<String, Integer> lethal = map("blocker:0", 4, "blocker:1", 5);

        // Current rules allow a nontrampling attacker to distribute a partial
        // assignment across more than one blocker; no former order is imposed.
        assertEquals(InteractiveGuiGame.validateModernCombatAllocation(
                map("blocker:0", 1, "blocker:1", 2), lethal, "defender"), null,
                "modern multi-sublethal split");

        // Trample still cannot assign excess to the defender before every
        // blocker has lethal damage assigned.
        assertEquals(InteractiveGuiGame.validateModernCombatAllocation(
                map("blocker:0", 1, "blocker:1", 2, "defender", 4), lethal, "defender"),
                "the defender cannot receive damage before every blocker has lethal",
                "trample requires aggregate lethal");

        assertEquals(InteractiveGuiGame.validateModernCombatAllocation(
                map("blocker:0", 4, "blocker:1", 5, "defender", 3), lethal, "defender"), null,
                "trample excess after lethal");
    }
}
