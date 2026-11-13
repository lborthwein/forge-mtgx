package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiFixes;
import forge.bench.rl.RlLiveSeat;

/**
 * evoke-empty-board-1111: the deadAltCost key in both live strings. These are the K8 look-ahead spec
 * ({@link InteractiveMain#parseLookahead}) and the policy seat's search spec ({@link RlLiveSeat.Spec#parse}). Absent (today's
 * live strings) = off, with the config JSON unchanged; {@code deadAltCost=on|shadow} arms it; any other value is refused.
 */
public class DeadAltCostLiveKeyTest extends forge.ai.AITest {

    static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    static String policy(String extra) throws Exception {
        final java.nio.file.Path index = java.nio.file.Files.createTempFile("dac-index-", ".tsv");
        return "server=127.0.0.1:1,policySha=" + SHA + ",cardIndex=" + index + ",plans.samples=4,plans.solverCfg.hideOwnLibrary=true"
                + (extra.isEmpty() ? "" : "," + extra);
    }

    @Test
    public void theK8SpecKeyIsOffUnlessSet() {
        final InteractiveMain.ParsedLookahead off = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8);
        Assert.assertEquals(off.config.deadAltCost, AiFixes.Mode.OFF);
        Assert.assertFalse(off.config.toJson().has("deadAltCost"));
        final InteractiveMain.ParsedLookahead explicitOff = InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadAltCost=off");
        Assert.assertEquals(explicitOff.config.toJson().toString(), off.config.toJson().toString());
        Assert.assertEquals(InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadAltCost=on").config.deadAltCost, AiFixes.Mode.ON);
        Assert.assertEquals(InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadAltCost=shadow").config.deadAltCost,
                AiFixes.Mode.SHADOW);
        Assert.expectThrows(IllegalArgumentException.class,
                () -> InteractiveMain.parseLookahead(InfoStateLiveKeyTest.LIVE_K8 + ",deadAltCost=yes"));
    }

    @Test
    public void thePolicySeatSpecKeyIsOffUnlessSet() throws Exception {
        final RlLiveSeat.Spec off = RlLiveSeat.Spec.parse(policy(""));
        Assert.assertEquals(off.search.deadAltCost, "off");
        Assert.assertFalse(off.search.toJson().has("deadAltCost"), "policy-seat spec JSON unchanged while off");
        final RlLiveSeat.Spec on = RlLiveSeat.Spec.parse(policy("deadAltCost=on"));
        Assert.assertEquals(on.search.deadAltCost, "on");
        Assert.assertEquals(on.search.toJson().get("deadAltCost").getAsString(), "on");
        Assert.expectThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(policy("deadAltCost=yes")));
    }
}
