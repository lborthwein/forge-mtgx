package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.bench.rl.RlLiveSeat;

/**
 * temp-pump-wrong-turn-1117: the deadPump key in both live strings. These are the K8 look-ahead spec
 * ({@link InteractiveMain#parseLookahead}) and the policy seat's search spec ({@link RlLiveSeat.Spec#parse}). Absent (today's
 * live strings) = off, with the config JSON unchanged; {@code deadPump=on|shadow} arms it; any other value is refused.
 */
public class DeadPumpLiveKeyTest extends forge.ai.AITest {

    @Test
    public void theK8SpecKeyIsOffUnlessSet() {
        final InteractiveMain.ParsedLookahead off = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8);
        Assert.assertEquals(off.config.deadPump, AiFixes.Mode.OFF);
        Assert.assertFalse(off.config.toJson().has("deadPump"));
        final InteractiveMain.ParsedLookahead explicitOff = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadPump=off");
        Assert.assertEquals(explicitOff.config.toJson().toString(), off.config.toJson().toString());
        Assert.assertEquals(InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadPump=on").config.deadPump, AiFixes.Mode.ON);
        Assert.assertEquals(InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadPump=shadow").config.deadPump,
                AiFixes.Mode.SHADOW);
        Assert.assertEquals(InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadPump=on").config.toJson()
                .get("deadPump").getAsString(), "on");
        Assert.expectThrows(IllegalArgumentException.class,
                () -> InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadPump=yes"));
    }

    @Test
    public void thePolicySeatSpecKeyIsOffUnlessSet() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy(""));
        Assert.assertEquals(off.search.deadPump, "off");
        Assert.assertFalse(off.search.toJson().has("deadPump"), "policy-seat spec JSON unchanged while off");
        final RlLiveSeat.Spec explicitOff = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("deadPump=off"));
        Assert.assertEquals(explicitOff.search.toJson().toString(), off.search.toJson().toString());
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("deadPump=on"));
        Assert.assertEquals(on.search.deadPump, "on");
        Assert.assertEquals(on.search.toJson().get("deadPump").getAsString(), "on");
        Assert.assertEquals(RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("deadPump=shadow")).search.deadPump, "shadow");
        Assert.expectThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(DeadAltCostLiveKeyTest.policy("deadPump=yes")));
    }
}
