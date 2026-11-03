package forge.interactive;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * order-memory-1102 live port: the K8 look-ahead spec's {@code infoState} key ({@link InteractiveMain#parseLookahead}).
 * Absent (today's live K8 spec) = off and the config JSON unchanged; {@code infoState=on} arms it; any other value is
 * refused.
 */
public class InfoStateLiveKeyTest extends forge.ai.AITest {

    static final String LIVE_K8 = "worlds=8,breadth=4,horizon=2,threads=8,budgetMs=8000,log=1,departZ=1.645,"
            + "aiFixes0928=on,deadEtb=on,zeroX=on,crewNoop=on,dudCast=on";

    @Test
    public void theK8SpecKeyIsOffUnlessOn() {
        final InteractiveMain.ParsedLookahead off = InteractiveMain.parseLookahead(LIVE_K8);
        Assert.assertFalse(off.config.infoState);
        Assert.assertFalse(off.config.toJson().has("infoState"));
        final InteractiveMain.ParsedLookahead explicitOff = InteractiveMain.parseLookahead(LIVE_K8 + ",infoState=off");
        Assert.assertEquals(explicitOff.config.toJson().toString(), off.config.toJson().toString());
        final InteractiveMain.ParsedLookahead on = InteractiveMain.parseLookahead(LIVE_K8 + ",infoState=on");
        Assert.assertTrue(on.config.infoState);
        Assert.assertEquals("uniform", on.config.belief, "still honest");
        Assert.expectThrows(IllegalArgumentException.class, () -> InteractiveMain.parseLookahead(LIVE_K8 + ",infoState=yes"));
    }
}
