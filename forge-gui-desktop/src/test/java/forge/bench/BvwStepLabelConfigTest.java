package forge.bench;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.HonestLabel;

/** Lane bvw-step1-build-1024: the replay label block's band and search keys. Parsing only; no game. */
public class BvwStepLabelConfigTest {
    static JsonObject good() {
        return forge.bench.rl.BvwStepSearchSpecTest.good();
    }

    /** The replay config refuses a search block over an ms proof spec, and a band with turns. */
    @Test
    public void theReplayConfigRefusesMixedBudgetsAndWindows() {
        final JsonObject label = JsonParser.parseString("{\"redraw\":\"belief\",\"out\":\"/x\",\"band\":[3,4],"
                + "\"solverCfg\":{\"hideOwnLibrary\":\"true\"},\"lossCfg\":{\"hideOwnLibrary\":\"true\"}}").getAsJsonObject();
        label.add("search", good());
        final JsonObject cfg = new JsonObject();
        cfg.add("label", label);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(cfg));
        for (String k : new String[] {"solverCfg", "lossCfg"}) {
            label.getAsJsonObject(k).addProperty("budget", "counter");
        }
        final RlActorBench.Cfg ok = RlActorBench.parse(cfg);
        Assert.assertNotNull(ok);
        final JsonObject both = cfg.deepCopy();
        both.getAsJsonObject("label").addProperty("turns", 3);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(both));
        Assert.assertTrue(HonestLabel.Spec.parse(label).counterBudgets());
    }

    @Test
    public void theBandIsParsed() {
        final JsonObject label = JsonParser.parseString("{\"redraw\":\"belief\",\"out\":\"/x\",\"band\":[3,4],"
                + "\"solverCfg\":{\"hideOwnLibrary\":\"true\"},\"lossCfg\":{\"hideOwnLibrary\":\"true\"}}").getAsJsonObject();
        final JsonObject cfg = new JsonObject();
        cfg.add("label", label);
        final RlActorBench.Cfg c = RlActorBench.parse(cfg);
        Assert.assertEquals(c.labelBandLo, 3);
        Assert.assertEquals(c.labelTurns, 4);
        Assert.assertNull(c.labelSearch);
        final JsonObject bad = cfg.deepCopy();
        bad.getAsJsonObject("label").add("band", JsonParser.parseString("[4,4]"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(bad));
    }
}
