package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** combo-assembly-1021 (B1): `plans.choose` is off by default, its config is strict, and the route pieces load. */
public class TutorChooserSpecTest {

    @Test
    public void offByDefault() {
        final PlanSearch.Spec s = PlanSearch.Spec.parse(new JsonObject());
        Assert.assertFalse(s.choose);
        Assert.assertEquals(s.chooseCfg.size(), 0);
    }

    @Test
    public void parsesItsKeysAndDefaults() {
        final PlanSearch.Spec s = PlanSearch.Spec.parse(JsonParser.parseString(
                "{\"choose\": true, \"chooseCfg\": {\"valueTop\": \"3\", \"wallMs\": \"5000\"}}").getAsJsonObject());
        Assert.assertTrue(s.choose);
        final TutorChooser.Cfg c = TutorChooser.Cfg.parse(s.chooseCfg);
        Assert.assertEquals(c.valueTop, 3);
        Assert.assertEquals(c.wallMs, 5000L);
        Assert.assertEquals(c.worlds, 2);
        Assert.assertEquals(c.fallback, "value");
        final TutorChooser.Cfg d = TutorChooser.Cfg.parse(new JsonObject());
        Assert.assertEquals(d.valueTop, 2);
        Assert.assertEquals(d.wallMs, 6000L);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void anUnknownKeyFailsAtParse() {
        PlanSearch.Spec.parse(JsonParser.parseString("{\"choose\": true, \"chooseCfg\": {\"budget\": \"1\"}}").getAsJsonObject());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void aWallOverTheBudgetFails() {
        TutorChooser.Cfg.parse(JsonParser.parseString("{\"wallMs\": \"25000\"}").getAsJsonObject());
    }

    @Test
    public void routePiecesLoad() {
        final java.util.Set<String> p = TutorChooser.loadPieces();
        Assert.assertTrue(p.contains("Doomsday"));
        Assert.assertTrue(p.contains("Underworld Breach"));
        Assert.assertTrue(p.contains("Splinter Twin"));
        Assert.assertTrue(p.size() > 50, String.valueOf(p.size()));
    }
}
