package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonParser;

/**
 * activate-miss-1116 (Fix B): search.mainBreadth is off unless set and then absent from the spec JSON (so a key-off
 * spec echoes byte-identically); on, the given breadth widens only at our own main phase with an empty stack, never
 * past the given entries and never below the base breadth (a plan stage's narrower breadth included).
 */
public class MainBreadthConfigTest {
    private static RlSearch.Config parse(final String s) {
        return RlSearch.Config.parse(JsonParser.parseString(s).getAsJsonObject());
    }

    @Test
    public void offByDefaultAndAbsentFromTheSpec() {
        final RlSearch.Config c = parse("{\"server\":\"h:1\"}");
        Assert.assertEquals(c.mainBreadth, 0);
        Assert.assertFalse(c.toJson().has("mainBreadth"));
    }

    @Test
    public void keptInTheSpecWhenOn() {
        final RlSearch.Config c = parse("{\"server\":\"h:1\",\"breadth\":4,\"mainBreadth\":8}");
        Assert.assertEquals(c.mainBreadth, 8);
        Assert.assertEquals(c.toJson().get("mainBreadth").getAsInt(), 8);
        final com.google.gson.JsonObject back = c.toJson();
        back.addProperty("server", "h:1"); // toJson carries no service address
        Assert.assertEquals(RlSearch.Config.parse(back).mainBreadth, 8);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void notAboveBreadthIsRefused() {
        parse("{\"server\":\"h:1\",\"breadth\":4,\"mainBreadth\":4}");
    }

    @Test
    public void widensOnlyAtOwnMainWithEmptyStack() {
        // off: the base breadth whatever the menu
        Assert.assertEquals(RlSearch.givenBreadth(4, 0, true, 9), 4);
        // on, not our main phase or the stack not empty: the base breadth
        Assert.assertEquals(RlSearch.givenBreadth(4, 8, false, 9), 4);
        // on: every entry up to the cap
        Assert.assertEquals(RlSearch.givenBreadth(4, 8, true, 6), 6);
        Assert.assertEquals(RlSearch.givenBreadth(4, 8, true, 12), 8);
        // a menu no larger than the base: unchanged
        Assert.assertEquals(RlSearch.givenBreadth(4, 8, true, 3), 4);
        // a plan stage's narrower base widens the same way
        Assert.assertEquals(RlSearch.givenBreadth(2, 8, true, 6), 6);
    }
}
