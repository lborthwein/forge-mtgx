package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.HonestLabel;

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
                + "\"evalSteps\":\"20000\"}}},\"valueSha\":\"" + "b".repeat(64) + "\",\"maxCopyCards\":120000}")
                .getAsJsonObject();
    }

    /** sha256 of the identity of {@link #good()} at band [3, 5) with {@link #label()}'s runtime (both languages). */
    public static final String GOLDEN_SHA = "534b566c336262287cbc97e5e9ead4f2bcc4c85298988ecd54a39f3aa7518108";

    /** The proof check the search block is tied to: counter, K 8, the live belief pool, pinned Forge AI settings. */
    public static HonestLabel.Spec label() {
        return HonestLabel.Spec.parse(JsonParser.parseString("{\"redraw\":\"belief\",\"worlds\":8,\"lossWallMs\":0,"
                + "\"sequentialAi\":true,\"aiTimeoutSec\":600,\"solverCfg\":{\"hideOwnLibrary\":\"true\",\"budget\":"
                + "\"counter\"},\"lossCfg\":{\"hideOwnLibrary\":\"true\",\"budget\":\"counter\"}}").getAsJsonObject());
    }

    static StepSearch.Spec parse(final JsonObject b) {
        return StepSearch.Spec.parse(b, label(), 3, 5);
    }

    @Test
    public void anHonestCounterBlockParses() {
        final StepSearch.Spec s = parse(good());
        Assert.assertEquals(s.worlds, 8);
        Assert.assertEquals(s.valueSha, "b".repeat(64));
        Assert.assertEquals(s.sha(), parse(good()).sha());
        // the cross-language golden: mtgx bvw.search_identity of this block (tests/test_bvw_step1.py pins the same sha)
        Assert.assertEquals(s.sha(), GOLDEN_SHA);
        // review #15: no valueSha only with an explicit control flag (the policy's own v(o))
        final JsonObject control = good();
        control.remove("valueSha");
        Assert.assertThrows(IllegalArgumentException.class, () -> parse(control));
        control.addProperty("control", true);
        Assert.assertNull(parse(control).valueSha);
        Assert.assertNotEquals(parse(control).sha(), s.sha(), "control is in the identity");
        final JsonObject both = good();
        both.addProperty("control", true);
        Assert.assertThrows(IllegalArgumentException.class, () -> parse(both));
        // review #4: the transport and the per-job value pin are not in the identity; the band (#14) and runtime (#3) are
        final JsonObject port = good();
        port.getAsJsonObject("rlsearch").addProperty("server", "10.0.0.7:61234");
        port.getAsJsonObject("rlsearch").addProperty("readTimeoutMs", 9999);
        port.addProperty("valueSha", "e".repeat(64));
        Assert.assertEquals(parse(port).sha(), s.sha());
        Assert.assertFalse(s.identity().getAsJsonObject("rlsearch").has("server"));
        Assert.assertNotEquals(StepSearch.Spec.parse(good(), label(), 3, 4).sha(), s.sha(), "the band is in the identity");
        Assert.assertEquals(s.identity().getAsJsonObject("runtime").get("aiTimeoutSec").getAsInt(), 600);
        final JsonObject cards = good();
        cards.addProperty("maxCopyCards", 1);
        Assert.assertNotEquals(parse(cards).sha(), s.sha());
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
            // review #15: the belief pool must be the proof check's
            rs -> rs.addProperty("beliefSingleton", true),
            rs -> rs.addProperty("beliefBasics", 4),
            rs -> rs.addProperty("beliefCubeSha256", "0".repeat(64)),
        };
        for (int i = 0; i < muts.length; i++) {
            final JsonObject b = good();
            muts[i].apply(b.getAsJsonObject("rlsearch"));
            final int ii = i;
            Assert.expectThrows(IllegalArgumentException.class, () -> parse(b));
            Assert.assertTrue(ii >= 0);
        }
        final JsonObject extra = good();
        extra.addProperty("unknown", 1);
        Assert.assertThrows(IllegalArgumentException.class, () -> parse(extra));
        final JsonObject k6 = good();
        k6.getAsJsonObject("rlsearch").addProperty("worlds", 6);
        Assert.assertThrows(IllegalArgumentException.class, () -> parse(k6));
        final JsonObject noGuard = good();
        noGuard.remove("maxCopyCards");
        Assert.assertThrows(IllegalArgumentException.class, () -> parse(noGuard));
        Assert.assertThrows(IllegalArgumentException.class, () -> StepSearch.Spec.parse(good(), label(), 5, 5));
    }
}
