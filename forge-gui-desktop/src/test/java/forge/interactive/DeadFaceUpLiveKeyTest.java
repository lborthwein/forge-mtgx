package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.bench.rl.RlLiveSeat;

/**
 * turn-face-up-timing-1118: the deadFaceUp key in both live strings. These are the K8 look-ahead spec
 * ({@link InteractiveMain#parseLookahead}) and the policy seat's search spec ({@link RlLiveSeat.Spec#parse}). Absent (today's
 * live strings) = off, with the config JSON unchanged; {@code deadFaceUp=on|shadow} arms it; any other value is refused.
 */
public class DeadFaceUpLiveKeyTest extends forge.ai.AITest {

    @Test
    public void theK8SpecKeyIsOffUnlessSet() {
        final InteractiveMain.ParsedLookahead off = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8);
        Assert.assertEquals(off.config.deadFaceUp, AiFixes.Mode.OFF);
        Assert.assertFalse(off.config.toJson().has("deadFaceUp"));
        final InteractiveMain.ParsedLookahead explicitOff = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadFaceUp=off");
        Assert.assertEquals(explicitOff.config.toJson().toString(), off.config.toJson().toString());
        final InteractiveMain.ParsedLookahead on = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadFaceUp=on");
        Assert.assertEquals(on.config.deadFaceUp, AiFixes.Mode.ON);
        Assert.assertEquals(on.config.toJson().get("deadFaceUp").getAsString(), "on");
        Assert.assertEquals(InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadFaceUp=shadow").config.deadFaceUp,
                AiFixes.Mode.SHADOW);
        Assert.expectThrows(IllegalArgumentException.class,
                () -> InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadFaceUp=yes"));
    }

    @Test
    public void thePolicySeatSpecKeyIsOffUnlessSet() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy(""));
        Assert.assertEquals(off.search.deadFaceUp, "off");
        Assert.assertFalse(off.search.toJson().has("deadFaceUp"), "policy-seat spec JSON unchanged while off");
        final RlLiveSeat.Spec explicitOff = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("deadFaceUp=off"));
        Assert.assertEquals(explicitOff.search.toJson().toString(), off.search.toJson().toString());
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("deadFaceUp=on"));
        Assert.assertEquals(on.search.deadFaceUp, "on");
        Assert.assertEquals(on.search.toJson().get("deadFaceUp").getAsString(), "on");
        final RlLiveSeat.Spec shadow = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("deadFaceUp=shadow"));
        Assert.assertEquals(shadow.search.deadFaceUp, "shadow");
        Assert.assertEquals(shadow.search.toJson().get("deadFaceUp").getAsString(), "shadow");
        Assert.expectThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("deadFaceUp=yes")));
    }
}
