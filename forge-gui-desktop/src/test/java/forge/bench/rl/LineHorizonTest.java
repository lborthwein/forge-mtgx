package forge.bench.rl;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/** Lane line-adoption-1107: plans.lineHorizon is off by default, and a line's win turn maps to the right absolute turn. */
public class LineHorizonTest {
    @Test
    public void offByDefaultAndParsed() {
        AssertJUnit.assertFalse(PlanSearch.Spec.parse(new JsonObject()).lineHorizon);
        final JsonObject o = new JsonObject();
        o.addProperty("lineHorizon", true);
        AssertJUnit.assertTrue(PlanSearch.Spec.parse(o).lineHorizon);
    }

    @Test
    public void winTurnToAbsoluteTurn() {
        // from our turn 5: next-turn win on turn 7; the play-outs end at turn 8's start, past horizon 2 (turn 7)
        AssertJUnit.assertEquals(7, PlanSearch.winAbs(5, 1, true));
        // from their end step on turn 6: a win on our next turn is turn 7, which horizon 2 (turn 8) already covers
        AssertJUnit.assertEquals(7, PlanSearch.winAbs(6, 1, false));
        // two of our turns ahead
        AssertJUnit.assertEquals(9, PlanSearch.winAbs(5, 2, true));
    }
}
