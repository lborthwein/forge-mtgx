package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.bench.rl.RlLiveSeat;

/**
 * block-vs-open-pump-1118: the lethalBlock key in the policy seat's search spec ({@link RlLiveSeat.Spec#parse}); the block
 * search runs only at the policy seat, so the K8 look-ahead spec has no such key. Absent (today's live string) = off, with
 * the config JSON unchanged; {@code lethalBlock=on|shadow} arms it; any other value is refused.
 */
public class LethalBlockLiveKeyTest extends forge.ai.AITest {

    @Test
    public void thePolicySeatSpecKeyIsOffUnlessSet() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy(""));
        Assert.assertEquals(off.search.lethalBlock, "off");
        Assert.assertFalse(off.search.toJson().has("lethalBlock"), "policy-seat spec JSON unchanged while off");
        final RlLiveSeat.Spec explicitOff = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("lethalBlock=off"));
        Assert.assertEquals(explicitOff.search.toJson().toString(), off.search.toJson().toString());
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("lethalBlock=on"));
        Assert.assertEquals(on.search.lethalBlock, "on");
        Assert.assertEquals(on.search.toJson().get("lethalBlock").getAsString(), "on");
        final RlLiveSeat.Spec shadow = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("lethalBlock=shadow"));
        Assert.assertEquals(shadow.search.lethalBlock, "shadow");
        Assert.assertEquals(shadow.search.toJson().get("lethalBlock").getAsString(), "shadow");
        for (String bad : new String[] {"yes", "true", "1", "On", ""}) {
            Assert.expectThrows(IllegalArgumentException.class,
                    () -> RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("lethalBlock=" + bad)));
        }
    }

    /** The K8 parser ignores keys it does not know: lethalBlock there arms nothing and leaves its JSON unchanged. */
    @Test
    public void theK8SpecHasNoSuchKey() {
        final InteractiveMain.ParsedLookahead k8 = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8);
        final InteractiveMain.ParsedLookahead with = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",lethalBlock=on");
        Assert.assertEquals(with.config.lethalBlock, forge.ai.AiFixes.Mode.OFF);
        Assert.assertEquals(with.config.toJson().toString(), k8.config.toJson().toString());
    }
}
