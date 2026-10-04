package forge.bench;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.AiProps;
import forge.ai.LobbyPlayerAi;

/**
 * LookaheadBench's per-seat "aiProfiles" game key (lane pf1-1003): absent means Forge's "Default" on both seats (the
 * behaviour before the option); a named profile must be one of the shipped res/ai profiles, because Forge silently
 * falls back to the built-in property defaults for a profile it did not load.
 */
public class LookaheadBenchAiProfileTest extends AITest {

    private static JsonObject spec(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static List<String> shipped() {
        return AiProfileUtil.getAvailableProfiles();
    }

    @Test
    public void theShippedProfilesAreTheFourOf2015() {
        final List<String> p = shipped();
        for (String name : new String[] {"Default", "Reckless", "Cautious", "Experimental"}) {
            AssertJUnit.assertTrue(name + " should be shipped in res/ai: " + p, p.contains(name));
        }
    }

    @Test
    public void absentKeyIsDefaultOnBothSeats() {
        final String[] p = LookaheadBench.seatProfiles(spec("{\"id\":\"g\",\"seats\":[\"default\",\"default\"]}"), shipped());
        AssertJUnit.assertEquals("Default", p[0]);
        AssertJUnit.assertEquals("Default", p[1]);
        AssertJUnit.assertEquals("Default", LookaheadBench.DEFAULT_AI_PROFILE);
    }

    @Test
    public void namedProfilesArePerSeat() {
        final String[] p = LookaheadBench.seatProfiles(spec("{\"id\":\"g\",\"aiProfiles\":[\"Default\",\"Reckless\"]}"), shipped());
        AssertJUnit.assertEquals("Default", p[0]);
        AssertJUnit.assertEquals("Reckless", p[1]);
        final String[] q = LookaheadBench.seatProfiles(spec("{\"id\":\"g\",\"aiProfiles\":[\"Cautious\",\"Experimental\"]}"), shipped());
        AssertJUnit.assertEquals("Cautious", q[0]);
        AssertJUnit.assertEquals("Experimental", q[1]);
    }

    @Test
    public void anUnshippedProfileIsRefused() {
        for (String bad : new String[] {"{\"id\":\"g\",\"aiProfiles\":[\"Default\",\"Reckles\"]}",
                "{\"id\":\"g\",\"aiProfiles\":[\"default\",\"Default\"]}",
                "{\"id\":\"g\",\"aiProfiles\":[\"Default\"]}",
                "{\"id\":\"g\",\"aiProfiles\":[\"Default\",\"Default\",\"Default\"]}",
                "{\"id\":\"g\",\"aiProfiles\":\"Reckless\"}",
                "{\"id\":\"g\",\"aiProfiles\":[\"Default\",null]}",
                "{\"id\":\"g\",\"aiProfiles\":[\"Default\",[\"Reckless\"]]}"}) {
            try {
                LookaheadBench.seatProfiles(spec(bad), shipped());
                AssertJUnit.fail("should refuse " + bad);
            } catch (IllegalStateException expected) {
                AssertJUnit.assertTrue(expected.getMessage(), expected.getMessage().startsWith("game g:"));
            }
        }
    }

    @Test
    public void aSeatsProfileChangesItsPropertiesAsShipped() {
        // The values the PF1 screen relies on, read the way the AI reads them (by the lobby player's profile).
        final LobbyPlayerAi d = new LobbyPlayerAi("d", null);
        d.setAiProfile("Default");
        final LobbyPlayerAi r = new LobbyPlayerAi("r", null);
        r.setAiProfile("Reckless");
        final LobbyPlayerAi c = new LobbyPlayerAi("c", null);
        c.setAiProfile("Cautious");
        final LobbyPlayerAi unknown = new LobbyPlayerAi("u", null);
        unknown.setAiProfile("NotShipped");
        AssertJUnit.assertEquals("30", AiProfileUtil.getAIProp(d, AiProps.CHANCE_TO_COUNTER_CMC_1));
        AssertJUnit.assertEquals("80", AiProfileUtil.getAIProp(r, AiProps.CHANCE_TO_COUNTER_CMC_1));
        AssertJUnit.assertEquals("0", AiProfileUtil.getAIProp(c, AiProps.CHANCE_TO_COUNTER_CMC_1));
        AssertJUnit.assertEquals("true", AiProfileUtil.getAIProp(r, AiProps.PLAY_AGGRO));
        AssertJUnit.assertEquals("2", AiProfileUtil.getAIProp(c, AiProps.MIN_SPELL_CMC_TO_COUNTER));
        // Why the bench refuses an unknown name: Forge falls back to the built-in default (50), which is no profile.
        AssertJUnit.assertEquals(AiProps.CHANCE_TO_COUNTER_CMC_1.getDefault(),
                AiProfileUtil.getAIProp(unknown, AiProps.CHANCE_TO_COUNTER_CMC_1));
        AssertJUnit.assertFalse("30".equals(AiProps.CHANCE_TO_COUNTER_CMC_1.getDefault()));
    }
}
