package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.BeliefSampler;

/**
 * Lane bvw-step1-build-1024: the step-1 search block is refused unless it is honest (belief, hidden own library, belief
 * probes) and host-independent (counter budgets, no wall or CPU clock), with the label's K everywhere. Parsing only.
 */
public class BvwStepSearchSpecTest {

    public static JsonObject good() {
        return JsonParser.parseString("{\"rlsearch\":{\"server\":\"127.0.0.1:1\",\"policySha\":\"" + "a".repeat(64)
                + "\",\"worlds\":8,\"breadth\":4,\"horizon\":2,\"leaf\":\"value\",\"playout\":\"policy\",\"threads\":1,"
                + "\"belief\":\"uniform\",\"beliefCube\":\"" + BeliefSampler.LIVE_CUBE + "\",\"beliefCubeSha256\":\""
                + BeliefSampler.LIVE_CUBE_SHA256 + "\",\"beliefSingleton\":false,\"deadEtb\":\"on\",\"zeroX\":\"on\","
                + "\"crewNoop\":\"on\",\"plans\":{\"samples\":4,\"minProvenWorlds\":8,\"solverCfg\":{\"budget\":"
                + "\"counter\",\"hideOwnLibrary\":\"true\",\"cycles\":\"true\",\"worlds\":\"8\",\"engineSteps\":\"6000\","
                + "\"evalSteps\":\"20000\"}}},\"valueSha\":\"" + "b".repeat(64) + "\"}").getAsJsonObject();
    }

    @Test
    public void anHonestCounterBlockParses() {
        final StepSearch.Spec s = StepSearch.Spec.parse(good(), 8);
        Assert.assertEquals(s.worlds, 8);
        Assert.assertEquals(s.valueSha, "b".repeat(64));
        Assert.assertEquals(s.sha(), StepSearch.Spec.parse(good(), 8).sha());
        final JsonObject control = good();
        control.remove("valueSha");
        Assert.assertNull(StepSearch.Spec.parse(control, 8).valueSha, "no valueSha = the policy's own v(o)");
    }

    interface Mut {
        void apply(JsonObject rs);
    }

    @Test
    public void everyDishonestOrWallClockBlockIsRefused() {
        final Mut[] muts = {
            rs -> rs.addProperty("belief", "off"),
            rs -> rs.remove("plans"),
            rs -> rs.getAsJsonObject("plans").getAsJsonObject("solverCfg").addProperty("hideOwnLibrary", "false"),
            rs -> rs.getAsJsonObject("plans").getAsJsonObject("solverCfg").remove("budget"),
            rs -> rs.getAsJsonObject("plans").addProperty("defend", true),
            rs -> rs.getAsJsonObject("plans").addProperty("opp", true),
            rs -> rs.addProperty("budgetMs", 600000),
            rs -> rs.addProperty("cpuCapMs", 1000),
            rs -> rs.addProperty("leaf", "static"),
            rs -> rs.addProperty("playout", "forge"),
            rs -> rs.addProperty("worlds", 6),
            rs -> rs.getAsJsonObject("plans").getAsJsonObject("solverCfg").addProperty("worlds", "6"),
            rs -> rs.getAsJsonObject("plans").addProperty("minProvenWorlds", 3),
            rs -> rs.remove("policySha"),
            rs -> rs.addProperty("stack", true),
            rs -> rs.addProperty("decisionLog", "/tmp/x"),
        };
        for (int i = 0; i < muts.length; i++) {
            final JsonObject b = good();
            muts[i].apply(b.getAsJsonObject("rlsearch"));
            final int ii = i;
            Assert.expectThrows(IllegalArgumentException.class, () -> StepSearch.Spec.parse(b, 8));
            Assert.assertTrue(ii >= 0);
        }
        final JsonObject extra = good();
        extra.addProperty("unknown", 1);
        Assert.assertThrows(IllegalArgumentException.class, () -> StepSearch.Spec.parse(extra, 8));
        Assert.assertThrows(IllegalArgumentException.class, () -> StepSearch.Spec.parse(good(), 6));
    }
}
