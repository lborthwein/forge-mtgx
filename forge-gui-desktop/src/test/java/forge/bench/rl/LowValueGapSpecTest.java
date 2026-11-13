package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

/** low-value-gap-1113 / noop-target-1113: the look-ahead keys lowFloor, lowGap and noopTarget in the seat's specs (all off by default). */
public class LowValueGapSpecTest {

    @Test
    public void theKeysParseInBothSpecFormsKeepTheirBoundsAndAreWrittenOnlyWhenOn() {
        final String ship = OwnerMisplaysSpecTest.SHIP;
        final RlSearch.Config off = RlLiveSeat.Spec.parse(ship).search;
        Assert.assertEquals(off.lowGap, 0.0);
        Assert.assertFalse(off.noopTarget);
        for (String k : new String[] {"lowFloor", "lowGap", "noopTarget"}) {
            Assert.assertFalse(off.toJson().has(k), k);
        }
        final RlSearch.Config on = RlLiveSeat.Spec.parse(ship + ",lowFloor=0.1,lowGap=0.02,noopTarget=true").search;
        Assert.assertEquals(on.lowFloor, 0.1);
        Assert.assertEquals(on.lowGap, 0.02);
        Assert.assertTrue(on.noopTarget);
        Assert.assertEquals(on.toJson().get("lowGap").getAsDouble(), 0.02);
        Assert.assertTrue(on.toJson().get("noopTarget").getAsBoolean());
        final com.google.gson.JsonObject j = on.toJson();
        j.addProperty("server", "127.0.0.1:1"); // the bench form names its service
        final RlSearch.Config back = RlSearch.Config.parse(j);
        Assert.assertEquals(back.lowFloor, 0.1);
        Assert.assertTrue(back.noopTarget);
        for (String bad : new String[] {"lowGap=0.02", "lowFloor=0.1", "lowFloor=1.5,lowGap=0.02", "lowFloor=0.1,lowGap=-0.1"}) {
            try {
                RlLiveSeat.Spec.parse(ship + "," + bad);
                Assert.fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // both in 0..1 and set together
            }
        }
    }
}
