package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonParser;

import forge.ai.simulation.LookaheadSearch;

/**
 * sdcc-c1-priogf-1117 (C1): search.prioGf is off unless set and then absent from the spec JSON (a key-off spec echoes
 * byte-identically and the look-ahead's config is unchanged); on, it needs the choiceScorer and plans it reuses; and the
 * selection rule (a pure helper) changes the gated choice only for a goldfish line within the margin.
 */
public class PrioGfConfigTest {
    private static final double NAN = Double.NaN;

    private static RlSearch.Config parse(final String s) {
        return RlSearch.Config.parse(JsonParser.parseString(s).getAsJsonObject());
    }

    @Test
    public void offByDefaultAndAbsentFromTheSpec() {
        final RlSearch.Config c = parse("{\"server\":\"h:1\"}");
        Assert.assertEquals(c.prioGf, 0);
        Assert.assertEquals(c.prioGfMargin, 0.01);
        Assert.assertEquals(c.prioGfTurns, 4);
        Assert.assertFalse(c.toJson().has("prioGf"));
        Assert.assertFalse(c.toJson().has("prioGfMargin"));
        Assert.assertFalse(c.toJson().has("prioGfTurns"));
        // the look-ahead's config too: off and absent from its JSON
        final LookaheadSearch.Config lc = new LookaheadSearch.Config();
        Assert.assertEquals(lc.prioGf, 0);
        Assert.assertFalse(lc.toJson().has("prioGf"));
    }

    @Test
    public void keptInTheSpecWhenOn() {
        final RlSearch.Config c = parse("{\"server\":\"h:1\",\"playout\":\"policy\",\"plans\":{},\"choiceScorer\":{},"
                + "\"prioGf\":3,\"prioGfMargin\":0.02,\"prioGfTurns\":0}");
        Assert.assertEquals(c.prioGf, 3);
        Assert.assertEquals(c.prioGfMargin, 0.02);
        Assert.assertEquals(c.prioGfTurns, 0);
        final com.google.gson.JsonObject back = c.toJson();
        Assert.assertEquals(back.get("prioGf").getAsInt(), 3);
        Assert.assertEquals(back.get("prioGfMargin").getAsDouble(), 0.02);
        Assert.assertEquals(back.get("prioGfTurns").getAsInt(), 0);
        back.addProperty("server", "h:1"); // toJson carries no service address
        final RlSearch.Config again = RlSearch.Config.parse(back);
        Assert.assertEquals(again.prioGf, 3);
        Assert.assertEquals(again.prioGfMargin, 0.02);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void onWithoutChoiceScorerIsRefused() {
        parse("{\"server\":\"h:1\",\"playout\":\"policy\",\"plans\":{},\"prioGf\":3}");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void negativeMarginIsRefused() {
        parse("{\"server\":\"h:1\",\"prioGfMargin\":-0.1}");
    }

    @Test
    public void liveKeysParse() {
        final String base = "server=h:1,cardIndex=x,policySha=" + "a".repeat(64);
        Assert.assertEquals(RlLiveSeat.Spec.parse(base).search.prioGf, 0);
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(base + ",prioGfMargin=0.03,prioGfTurns=2");
        Assert.assertEquals(s.search.prioGfMargin, 0.03);
        Assert.assertEquals(s.search.prioGfTurns, 2);
    }

    @Test
    public void tieSetIsTheMarginOfTheBestOkMean() {
        final double[] ev = {0.50, 0.505, 0.48, NAN, 0.509};
        final boolean[] ok = {true, true, true, false, true};
        final boolean[] all = {true, true, true, true, true};
        Assert.assertEquals(LookaheadSearch.prioGfTieSet(ev, ok, all, 0.01), new boolean[] {true, true, false, false, true});
        // an ineligible (macro / plan) candidate is never in it
        Assert.assertEquals(LookaheadSearch.prioGfTieSet(ev, ok, new boolean[] {true, false, true, true, true}, 0.01),
                new boolean[] {true, false, false, false, true});
        // margin 0: only the best
        Assert.assertEquals(LookaheadSearch.prioGfTieSet(ev, ok, all, 0.0), new boolean[] {false, false, false, false, true});
    }

    @Test
    public void selectionRule() {
        final double[] ev = {0.50, 0.505, 0.509};
        final boolean[] in = {true, true, true};
        // no line in the tie set: nothing changes
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, in, new double[] {NAN, 0.0, NAN}, 2), -1);
        // the soonest line (candidate 1) beats the default's: it replaces the argmax (2)
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, in, new double[] {0.64, 0.8, 0.0}, 2), 1);
        // the default has the best line: the default is played (no gate needed)
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, in, new double[] {0.8, 0.64, 0.0}, 2), 0);
        // the winner is the current pick: nothing changes
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, in, new double[] {0.0, 0.0, 0.8}, 2), -1);
        // a tie with the default (default in the set): ties go to the current pick, then the larger ev; 1 vs 0 tie at 0.8
        // with current 2 (0.5): winner 1 (larger ev), but it does not beat the default by > 0: nothing changes
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, in, new double[] {0.8, 0.8, 0.5}, 2), -1);
        // the default outside the tie set, scored for the comparison: a winner must beat it
        final boolean[] noDef = {false, true, true};
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, noDef, new double[] {0.8, 0.64, 0.0}, 2), -1);
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, noDef, new double[] {0.5, 0.64, 0.0}, 2), 1);
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, noDef, new double[] {NAN, 0.64, 0.0}, 2), 1);
        // fewer than two in the tie set: nothing changes
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, new boolean[] {false, true, false}, new double[] {0, 1, 0}, 0), -1);
        // a tie between two non-default lines goes to the current pick
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, in, new double[] {0.0, 0.8, 0.8}, 2), -1);
        Assert.assertEquals(LookaheadSearch.prioGfSelect(ev, in, new double[] {0.0, 0.8, 0.8}, 0), 2);
    }
}
