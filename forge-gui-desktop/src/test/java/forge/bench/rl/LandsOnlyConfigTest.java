package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * sdcc-landsonly-1117 (diagnostic): search.landsOnlyTurns is off unless set and then absent from the spec JSON (a key-off
 * spec echoes byte-identically); the cut applies only while the seat is active and its own turn (ceil(turn / 2)) is at
 * most the key; it keeps pass and the accepted entries, keeps their answers, and a menu cut to pass alone is trivial.
 */
public class LandsOnlyConfigTest {
    private static RlSearch.Config parse(final String s) {
        return RlSearch.Config.parse(JsonParser.parseString(s).getAsJsonObject());
    }

    @Test
    public void offByDefaultAndAbsentFromTheSpec() {
        final RlSearch.Config c = parse("{\"server\":\"h:1\"}");
        Assert.assertEquals(c.landsOnlyTurns, 0);
        Assert.assertFalse(c.toJson().has("landsOnlyTurns"));
    }

    @Test
    public void keptInTheSpecWhenOn() {
        final RlSearch.Config c = parse("{\"server\":\"h:1\",\"landsOnlyTurns\":3}");
        Assert.assertEquals(c.landsOnlyTurns, 3);
        final JsonObject back = c.toJson();
        Assert.assertEquals(back.get("landsOnlyTurns").getAsInt(), 3);
        back.addProperty("server", "h:1");
        Assert.assertEquals(RlSearch.Config.parse(back).landsOnlyTurns, 3);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void negativeIsRefused() {
        parse("{\"server\":\"h:1\",\"landsOnlyTurns\":-1}");
    }

    @Test
    public void ownTurnRule() {
        // on the play: game turns 1, 3, 5 are own turns 1, 2, 3; on the draw: 2, 4, 6 are 1, 2, 3
        Assert.assertEquals(RlCandidates.ownTurn(1), 1);
        Assert.assertEquals(RlCandidates.ownTurn(2), 1);
        Assert.assertEquals(RlCandidates.ownTurn(5), 3);
        Assert.assertEquals(RlCandidates.ownTurn(6), 3);
        Assert.assertTrue(RlCandidates.landsOnlyApplies(3, true, 5));
        Assert.assertTrue(RlCandidates.landsOnlyApplies(3, true, 6));
        Assert.assertFalse(RlCandidates.landsOnlyApplies(3, true, 7));
        Assert.assertFalse(RlCandidates.landsOnlyApplies(3, false, 2)); // the opponent's turn: unchanged
        Assert.assertFalse(RlCandidates.landsOnlyApplies(0, true, 1)); // off
        Assert.assertTrue(RlCandidates.landsOnlyApplies(4, true, 7)); // an installed mid-game start at turn 7
    }

    private static RlCandidates.Menu menu(final int entries) {
        final RlCandidates.Menu m = new RlCandidates.Menu();
        m.family = RlSchema.F_PRIORITY;
        m.mode = RlSchema.M_SINGLE;
        m.shape = RlCandidates.SHAPE_SINGLE;
        m.minPick = 1;
        m.maxPick = 1;
        for (int i = 0; i <= entries; i++) {
            final RlCandidates.Cand c = new RlCandidates.Cand();
            c.kind = i == 0 ? RlSchema.K_PASS : i % 2 == 1 ? RlSchema.K_LAND : RlSchema.K_CAST;
            final JsonObject f = new JsonObject();
            f.addProperty("choice", i);
            c.frag = f;
            m.cands.add(c);
        }
        return m;
    }

    @Test
    public void cutKeepsPassAndAcceptedEntriesWithTheirAnswers() {
        final RlCandidates.Menu m = menu(4); // pass, entries 1..4
        final int[] kn = RlCandidates.landsOnlyCut(m, ch -> ch == 1 || ch == 3);
        Assert.assertEquals(kn, new int[] {2, 4});
        Assert.assertEquals(m.C(), 3);
        Assert.assertFalse(m.trivial);
        Assert.assertNull(m.unposable);
        Assert.assertEquals(m.answer(new short[] {0}).get("choice").getAsInt(), 0);
        Assert.assertEquals(m.answer(new short[] {1}).get("choice").getAsInt(), 1);
        Assert.assertEquals(m.answer(new short[] {2}).get("choice").getAsInt(), 3);
    }

    @Test
    public void cutToPassAloneIsTrivialPass() {
        final RlCandidates.Menu m = menu(2);
        final int[] kn = RlCandidates.landsOnlyCut(m, ch -> false);
        Assert.assertEquals(kn, new int[] {0, 2});
        Assert.assertTrue(m.trivial);
        Assert.assertEquals(m.answer(m.trivialSteps).get("choice").getAsInt(), 0);
    }

    @Test
    public void nothingRemovedLeavesTheMenuAlone() {
        final RlCandidates.Menu m = menu(3);
        final int[] kn = RlCandidates.landsOnlyCut(m, ch -> true);
        Assert.assertEquals(kn, new int[] {3, 3});
        Assert.assertEquals(m.C(), 4);
    }
}
