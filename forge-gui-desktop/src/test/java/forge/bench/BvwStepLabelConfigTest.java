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
        final JsonObject label = JsonParser.parseString("{\"redraw\":\"belief\",\"out\":\"/x\",\"band\":[3,5],"
                + "\"worlds\":8,\"lossWallMs\":0,\"sequentialAi\":true,\"aiTimeoutSec\":600,"
                + "\"solverCfg\":{\"hideOwnLibrary\":\"true\"},\"lossCfg\":{\"hideOwnLibrary\":\"true\"}}").getAsJsonObject();
        label.add("search", good());
        final JsonObject cfg = new JsonObject();
        cfg.addProperty("aiTimeoutSec", 600);
        cfg.add("label", label);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(cfg)); // ms proof spec + search
        for (String k : new String[] {"solverCfg", "lossCfg"}) {
            label.getAsJsonObject(k).addProperty("budget", "counter");
        }
        final RlActorBench.Cfg ok = RlActorBench.parse(cfg);
        Assert.assertNotNull(ok);
        Assert.assertEquals(ok.labelSearch.bandLo, 3);
        Assert.assertEquals(ok.labelSearch.bandHi, 5);
        // review #3: the run's Forge AI timeout must be the pinned one
        final JsonObject slow = cfg.deepCopy();
        slow.addProperty("aiTimeoutSec", 5);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(slow));
        final JsonObject both = cfg.deepCopy();
        both.getAsJsonObject("label").addProperty("turns", 3);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(both));
        Assert.assertTrue(HonestLabel.Spec.parse(label).counterBudgets());
    }

    @Test
    public void aBandNeedsASearchBlock() {
        // review #14: a band without a search block would write step-0 rows outside step 0's window: refused
        final JsonObject label = JsonParser.parseString("{\"redraw\":\"belief\",\"out\":\"/x\",\"band\":[3,5],"
                + "\"solverCfg\":{\"hideOwnLibrary\":\"true\"},\"lossCfg\":{\"hideOwnLibrary\":\"true\"}}").getAsJsonObject();
        final JsonObject cfg = new JsonObject();
        cfg.add("label", label);
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(cfg));
        final JsonObject bad = cfg.deepCopy();
        bad.getAsJsonObject("label").add("band", JsonParser.parseString("[4,4]"));
        Assert.assertThrows(IllegalArgumentException.class, () -> RlActorBench.parse(bad));
        // the step-0 window is the turns key (unchanged)
        final JsonObject t = cfg.deepCopy();
        t.getAsJsonObject("label").remove("band");
        t.getAsJsonObject("label").addProperty("turns", 3);
        Assert.assertEquals(RlActorBench.parse(t).labelTurns, 3);
    }
}
