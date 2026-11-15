package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * reach-verify-1114: the default-off key {@code reach.verify} (off|sampled|unopposed) and its settings
 * {@code reach.verifyLines} and {@code reach.verifyFull}. Absent = off, the seat as before; the key needs a belief, so the
 * checks never play lines against the opponent's true hand. Configs only: no game, no card database.
 */
public class ReachVerifySpecTest {

    static final String SHIP = ShipASpecParseTest.HOST + ShipASpecParseTest.TODAY + "," + ShipASpecParseTest.SHIPA_ADDS;

    @Test
    public void absentIsOff() {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(SHIP).search;
        final RlSearch.ReachCfg rc = RlSearch.ReachCfg.parse(c.reach == null ? new JsonObject() : c.reach);
        Assert.assertFalse(rc.verifyOn());
        Assert.assertEquals(rc.verify, "off");
        Assert.assertEquals(rc.verifyLines, 3);
        Assert.assertTrue(rc.verifyFull);
    }

    @Test
    public void liveKeysParse() {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(SHIP + ",reach.verify=sampled,reach.verifyLines=2,reach.verifyFull=false")
                .search;
        Assert.assertEquals(c.belief, "uniform");
        final RlSearch.ReachCfg rc = RlSearch.ReachCfg.parse(c.reach);
        Assert.assertTrue(rc.verifyOn());
        Assert.assertEquals(rc.verifyMode(), forge.ai.simulation.ForcedWinSolver.VERIFY_SAMPLED);
        Assert.assertEquals(rc.verifyLines, 2);
        Assert.assertFalse(rc.verifyFull);
        Assert.assertEquals(RlSearch.ReachCfg.parse(JsonParser.parseString("{\"verify\":\"unopposed\"}").getAsJsonObject())
                .verifyMode(), forge.ai.simulation.ForcedWinSolver.VERIFY_UNOPPOSED);
    }

    @Test
    public void badValueIsRefused() {
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(SHIP + ",reach.verify=true"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(SHIP + ",reach.verify=on"));
    }

    @Test
    public void verifyFullIsStrictAndVerifyLinesIsBounded() {
        // reach-verify-1115 (review S2): 1 reads as true and 0 as false (never a silent false), anything else is refused
        Assert.assertTrue(RlSearch.ReachCfg.parse(JsonParser.parseString("{\"verifyFull\":1}").getAsJsonObject()).verifyFull);
        Assert.assertFalse(RlSearch.ReachCfg.parse(JsonParser.parseString("{\"verifyFull\":0}").getAsJsonObject()).verifyFull);
        Assert.assertFalse(RlSearch.ReachCfg.parse(JsonParser.parseString("{\"verifyFull\":false}").getAsJsonObject()).verifyFull);
        for (String bad : new String[] {"\"yes\"", "\"true\"", "2", "0.5"}) {
            Assert.assertThrows(IllegalArgumentException.class,
                    () -> RlSearch.ReachCfg.parse(JsonParser.parseString("{\"verifyFull\":" + bad + "}").getAsJsonObject()));
        }
        Assert.assertTrue(RlSearch.ReachCfg.parse(RlLiveSeat.Spec.parse(SHIP + ",reach.verify=sampled,reach.verifyFull=1").search.reach)
                .verifyFull);
        Assert.assertFalse(RlSearch.ReachCfg.parse(RlLiveSeat.Spec.parse(SHIP + ",reach.verify=sampled,reach.verifyFull=0").search.reach)
                .verifyFull);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(SHIP + ",reach.verifyFull=yes"));
        for (String n : new String[] {"0", "9", "-1"}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(SHIP + ",reach.verifyLines=" + n));
        }
        Assert.assertEquals(RlSearch.ReachCfg.parse(RlLiveSeat.Spec.parse(SHIP + ",reach.verifyLines=8").search.reach).verifyLines, 8);
    }

    @Test
    public void benchSpecNeedsABelief() {
        final String base = "{\"server\":\"127.0.0.1:1\",\"worlds\":2,\"breadth\":2,\"horizon\":2,\"reach\":{\"verify\":\"sampled\"}";
        final IllegalArgumentException e = Assert.expectThrows(IllegalArgumentException.class,
                () -> RlSearch.Config.parse(JsonParser.parseString(base + ",\"belief\":\"off\"}").getAsJsonObject()));
        Assert.assertTrue(e.getMessage().contains("reach.verify needs a belief"), e.getMessage());
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlSearch.Config.parse(JsonParser.parseString(base + "}").getAsJsonObject()));
        // verify off with belief off stays legal (the default)
        RlSearch.Config.parse(JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"worlds\":2,\"breadth\":2,\"horizon\":2,\"reach\":{\"verify\":\"off\"}}")
                .getAsJsonObject());
    }
}
