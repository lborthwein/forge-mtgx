package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.simulation.ForcedWinSolver;

/**
 * choice-search-1109 (choiceScorer.lineOnly): the departure rule. The option with the highest verified p_win is played,
 * ties broken by the leaf value; with no verified line anywhere the policy's answer stands; the value alone never moves
 * the answer. And the key is off unless set, keeps old configs printing as before, and accepts DISCARD_FROM.
 */
public class ChoiceLineRuleTest {

    private static ChoiceScorer scorer() {
        return new ChoiceScorer(ChoiceScorer.Config.parse(JsonParser.parseString(
                "{\"families\":\"CARDS,DISCARD_FROM\",\"screenNodes\":2000,\"lineOnly\":true}").getAsJsonObject()), null, 2, 1L);
    }

    private static ChoiceScorer.Option opt(final String label, final int step, final boolean policy, final double v,
            final double pWin) {
        final ChoiceScorer.Option o = new ChoiceScorer.Option();
        o.label = label;
        o.steps = new short[] {(short) step};
        o.policy = policy;
        o.value = v;
        o.screen = new ForcedWinSolver.Screen();
        if (!Double.isNaN(pWin)) {
            o.screen.lines = 1;
            o.screen.pWin = pWin;
            o.screen.check = pWin >= 1 ? "win" : "unresolved";
        }
        return o;
    }

    private static short[] run(final ChoiceScorer.Scored s) {
        ChoiceScorer.Option own = null;
        for (ChoiceScorer.Option o : s.options) {
            if (o.policy) {
                own = o;
            }
        }
        return scorer().chooseLine(s, own);
    }

    @Test
    public void noVerifiedLineKeepsThePolicyEvenWhenTheValueIsHigher() {
        final ChoiceScorer.Scored s = new ChoiceScorer.Scored();
        s.options.add(opt("Troll", 0, true, 0.30, Double.NaN));
        s.options.add(opt("Doomsday", 1, false, 0.90, Double.NaN));
        final ChoiceScorer.Option screenOnly = opt("Timetwister", 2, false, 0.95, Double.NaN);
        screenOnly.screen.lines = 3; // a planner line the check never verified is not a line
        s.options.add(screenOnly);
        Assert.assertNull(run(s));
        Assert.assertEquals(s.chosen.label, "Troll");
    }

    @Test
    public void theHighestVerifiedPWinIsPlayed() {
        final ChoiceScorer.Scored s = new ChoiceScorer.Scored();
        s.options.add(opt("Troll", 0, true, 0.60, Double.NaN));
        s.options.add(opt("Doomsday", 1, false, 0.10, 0.625));
        s.options.add(opt("Jace", 2, false, 0.70, 0.25));
        Assert.assertEquals(run(s), new short[] {1});
        Assert.assertEquals(s.chosen.label, "Doomsday");
    }

    @Test
    public void tiesAreBrokenByTheValueAndThePolicyKeepsATie() {
        final ChoiceScorer.Scored s = new ChoiceScorer.Scored();
        s.options.add(opt("A", 0, false, 0.40, 1.0));
        s.options.add(opt("B", 1, false, 0.55, 1.0));
        s.options.add(opt("P", 2, true, 0.20, Double.NaN));
        Assert.assertEquals(run(s), new short[] {1});
        final ChoiceScorer.Scored t = new ChoiceScorer.Scored();
        t.options.add(opt("P", 0, true, 0.50, 1.0));
        t.options.add(opt("B", 1, false, 0.50, 1.0));
        Assert.assertNull(run(t), "an equal value keeps the policy's answer");
        final ChoiceScorer.Scored u = new ChoiceScorer.Scored();
        u.options.add(opt("P", 0, true, Double.NaN, 0.5));
        u.options.add(opt("B", 1, false, 0.10, 0.5));
        Assert.assertEquals(run(u), new short[] {1}, "a failed value ranks below any value on a tie");
    }

    @Test
    public void aFailedOrTerminalOptionIsPricedHonestly() {
        final ChoiceScorer.Scored s = new ChoiceScorer.Scored();
        final ChoiceScorer.Option failed = opt("X", 1, false, 0.9, 1.0);
        failed.fail = "pickMissed";
        s.options.add(opt("P", 0, true, 0.2, Double.NaN));
        s.options.add(failed);
        Assert.assertNull(run(s));
        final ChoiceScorer.Option won = opt("W", 2, false, Double.NaN, Double.NaN);
        won.terminal = 1.0;
        s.options.add(won);
        Assert.assertEquals(run(s), new short[] {2});
        Assert.assertEquals(ChoiceScorer.pLine(won), 1.0);
        final ChoiceScorer.Option lost = opt("L", 3, false, Double.NaN, Double.NaN);
        lost.terminal = 0.0;
        Assert.assertEquals(ChoiceScorer.pLine(lost), 0.0);
    }

    @Test
    public void theKeyIsOffByDefaultAndOldConfigsPrintAsBefore() {
        final ChoiceScorer.Config old = ChoiceScorer.Config.parse(JsonParser.parseString(
                "{\"families\":\"CARDS\",\"screenNodes\":2000}").getAsJsonObject());
        Assert.assertFalse(old.lineOnly);
        Assert.assertNull(old.check);
        Assert.assertFalse(old.toJson().has("lineOnly"));
        Assert.assertFalse(old.toJson().has("checkTop"));
        final JsonObject on = scorer().config().toJson();
        Assert.assertTrue(on.get("lineOnly").getAsBoolean());
        Assert.assertEquals(on.get("families").getAsString(), "CARDS,DISCARD_FROM");
        final ChoiceScorer.Config back = ChoiceScorer.Config.parse(on);
        Assert.assertTrue(back.lineOnly);
        Assert.assertTrue(back.families.contains(RlSchema.F_DISCARD_FROM));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void lineOnlyNeedsThePlannerScreen() {
        ChoiceScorer.Config.parse(JsonParser.parseString("{\"lineOnly\":true}").getAsJsonObject());
    }

    @Test
    public void costCardsIsAFamilyNow() {
        final ChoiceScorer.Config c = ChoiceScorer.Config.parse(JsonParser.parseString(
                "{\"families\":\"CARDS,DISCARD_FROM,COST_CARDS\",\"screenNodes\":2000,\"lineOnly\":true}").getAsJsonObject());
        Assert.assertTrue(c.families.contains(RlSchema.F_COST_CARDS));
    }

    @Test
    public void theCheckSpecIsKeptAndHonestyIsEnforcedAtInstall() {
        final JsonObject o = JsonParser.parseString("{\"screenNodes\":2000,\"lineOnly\":true,\"checkTop\":2,"
                + "\"check\":{\"worlds\":8,\"redraw\":\"belief\",\"loss\":false,\"solverCfg\":{\"hideOwnLibrary\":\"true\"},"
                + "\"lossCfg\":{\"hideOwnLibrary\":\"true\"}}}").getAsJsonObject();
        final ChoiceScorer.Config c = ChoiceScorer.Config.parse(o);
        Assert.assertEquals(c.checkTop, 2);
        Assert.assertEquals(c.check.get("worlds").getAsInt(), 8);
        final JsonObject dishonest = c.check.deepCopy();
        dishonest.addProperty("redraw", "none");
        try {
            forge.ai.simulation.HonestLabel.Spec.parse(dishonest);
            Assert.fail("a check without the belief re-draw must be refused");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("refused"), e.getMessage());
        }
    }
}
