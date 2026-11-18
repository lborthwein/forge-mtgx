package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonParser;

/**
 * forced-include-1117: search.forceInclude / forceProfile are off unless set and then absent from the spec JSON (a key-off
 * spec echoes byte-identically); only the tokens plan and wincon are accepted; a non-line plan has no first host.
 */
public class ForceIncludeConfigTest {
    private static RlSearch.Config parse(final String s) {
        return RlSearch.Config.parse(JsonParser.parseString(s).getAsJsonObject());
    }

    @Test
    public void offByDefaultAndAbsentFromTheSpec() {
        final RlSearch.Config c = parse("{\"server\":\"h:1\"}");
        Assert.assertEquals(c.forceInclude, "");
        Assert.assertEquals(c.forceProfile, "");
        Assert.assertFalse(c.toJson().has("forceInclude"));
        Assert.assertFalse(c.toJson().has("forceProfile"));
    }

    @Test
    public void keptInTheSpecWhenOn() {
        final RlSearch.Config c = parse("{\"server\":\"h:1\",\"forceInclude\":\"plan\"}");
        Assert.assertEquals(c.forceInclude, "plan");
        Assert.assertEquals(c.toJson().get("forceInclude").getAsString(), "plan");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void unknownTokenIsRefused() {
        parse("{\"server\":\"h:1\",\"forceInclude\":\"plan,cards\"}");
    }

    @Test
    public void nonLinePlanHasNoFirstHost() {
        Assert.assertEquals(RlSearch.lineFirstHost(null), -1);
        Assert.assertEquals(RlSearch.lineFirstHost("x"), -1);
        Assert.assertEquals(forge.ai.simulation.ForcedWinSolver.firstStepHostId(null), -1);
    }
}
