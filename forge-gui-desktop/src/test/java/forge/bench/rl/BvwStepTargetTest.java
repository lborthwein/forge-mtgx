package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Lane bvw-step1-build-1024: a step row's target ({@code RlReplayLabeller.StepTarget}). A proof is 1 / 0, else the
 * search value, else none; OPEN-6 ruling (2026-10-24): at a hidden face-down exile the row is
 * {@code unsupported-hidden-exile} with no target, the proof withheld (kept as unsupported_proof for the audit).
 */
public class BvwStepTargetTest {

    static StepSearch.Result searched(final double v) {
        final StepSearch.Result r = new StepSearch.Result();
        r.why = "searched";
        r.v = v;
        return r;
    }

    @Test
    public void proofsSearchValuesAndHiddenExiles() {
        RlReplayLabeller.StepTarget t = RlReplayLabeller.StepTarget.of("win", searched(0.3));
        Assert.assertEquals(t.source, "proof");
        Assert.assertEquals(t.proof, "win");
        Assert.assertEquals(t.target, 1.0);
        t = RlReplayLabeller.StepTarget.of("unresolved", searched(0.3));
        Assert.assertEquals(t.source, "search");
        Assert.assertNull(t.proof);
        Assert.assertEquals(t.target, 0.3);
        t = RlReplayLabeller.StepTarget.of("unresolved", null);
        Assert.assertEquals(t.source, "none");
        Assert.assertTrue(Double.isNaN(t.target));
        // OPEN-6: a hidden face-down exile withholds a proof as well as the search
        final StepSearch.Result hx = new StepSearch.Result();
        hx.why = HiddenExile.TARGET_SOURCE;
        hx.hiddenExile = new JsonArray();
        final JsonObject e = new JsonObject();
        e.addProperty("owner", "opponent");
        e.addProperty("exiled_with", "Shelldock Isle");
        hx.hiddenExile.add(e);
        for (String label : new String[] {"win", "loss", "unresolved"}) {
            t = RlReplayLabeller.StepTarget.of(label, hx);
            Assert.assertEquals(t.source, "unsupported-hidden-exile", label);
            Assert.assertNull(t.proof, label);
            Assert.assertTrue(Double.isNaN(t.target), label);
            Assert.assertEquals(t.unsupportedProof, "unresolved".equals(label) ? null : label);
            Assert.assertEquals(t.hiddenExile.size(), 1);
        }
        // an empty scan is no hidden exile
        final StepSearch.Result none = searched(0.4);
        none.hiddenExile = new JsonArray();
        Assert.assertEquals(RlReplayLabeller.StepTarget.of("unresolved", none).source, "search");
    }
}
