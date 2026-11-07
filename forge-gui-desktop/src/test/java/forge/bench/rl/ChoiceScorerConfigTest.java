package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** choice-scorer-1106: the key is off unless set, and a bad config fails at parse. */
public class ChoiceScorerConfigTest {
    @Test
    public void offByDefaultAndAbsentFromTheSpec() {
        final RlSearch.Config c = RlSearch.Config.parse(JsonParser.parseString("{\"server\":\"h:1\"}").getAsJsonObject());
        Assert.assertNull(c.choiceScorer);
        Assert.assertFalse(c.toJson().has("choiceScorer"));
    }

    @Test
    public void parsesItsKeysAndKeepsThemInTheSpec() {
        final JsonObject o = JsonParser.parseString("{\"server\":\"h:1\",\"choiceScorer\":{\"families\":\"CARDS,MULLIGAN\",\"pile\":true,"
                + "\"screenNodes\":2000}}").getAsJsonObject();
        final RlSearch.Config c = RlSearch.Config.parse(o);
        Assert.assertTrue(c.toJson().has("choiceScorer"));
        final ChoiceScorer.Config s = ChoiceScorer.Config.parse(c.choiceScorer);
        Assert.assertTrue(s.pile);
        Assert.assertEquals(s.screenNodes, 2000);
        Assert.assertTrue(s.families.contains(RlSchema.F_MULLIGAN));
        Assert.assertTrue(s.guard);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void unknownKeyFails() {
        RlSearch.Config.parse(JsonParser.parseString("{\"server\":\"h:1\",\"choiceScorer\":{\"nope\":1}}").getAsJsonObject());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void unsupportedFamilyFails() {
        ChoiceScorer.Config.parse(JsonParser.parseString("{\"families\":\"TARGETS\"}").getAsJsonObject());
    }
}
