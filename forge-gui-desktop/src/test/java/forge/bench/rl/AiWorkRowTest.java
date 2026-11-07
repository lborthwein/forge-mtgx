package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiWork;
import forge.ai.simulation.HonestLabel;

/**
 * rollout-budget-1101 (review #160 S2-2): a capped solver is named in the step row and its index row: the proof check's
 * cap (cap, cap_hits, worlds_capped, proof_cap) and the plan search's (search.solver_caps). Uncapped rows are unchanged.
 */
public class AiWorkRowTest {

    static JsonObject header(final HonestLabel.Result r, final StepSearch.Result sr) {
        // as RlReplayLabeller.row + stepFields build it: the label's JSON, then the step fields
        final JsonObject h = new JsonObject();
        h.addProperty("game_uid", "1");
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : r.toJson().entrySet()) {
            h.add(e.getKey(), e.getValue());
        }
        final RlReplayLabeller.StepTarget st = RlReplayLabeller.StepTarget.of(r.label, sr);
        h.addProperty("target_source", st.source);
        RlReplayLabeller.markCap(h, r);
        h.add("search", sr.toJson());
        return h;
    }

    @Test
    public void aCappedCheckReachesTheStepAndIndexRow() {
        final HonestLabel.Result r = new HonestLabel.Result();
        r.check = "loss";
        r.cap = AiWork.CAP;
        r.capHits = 1;
        r.worldsCapped = 1;
        r.worldsLoss = 7;
        r.lostWorlds = 7;
        final StepSearch.Result sr = new StepSearch.Result();
        sr.solverCaps = 2;
        final JsonObject ir = RlReplayLabeller.indexRow(header(r, sr));
        Assert.assertEquals(ir.get("cap").getAsString(), AiWork.CAP, ir.toString());
        Assert.assertEquals(ir.get("cap_hits").getAsInt(), 1);
        Assert.assertEquals(ir.get("worlds_capped").getAsInt(), 1);
        Assert.assertEquals(ir.get("proof_cap").getAsString(), AiWork.CAP);
        Assert.assertEquals(ir.getAsJsonObject("search").get("solver_caps").getAsInt(), 2);
        Assert.assertEquals(ir.get("label").getAsString(), "unresolved");
    }

    @Test
    public void anUncappedRowHasNoCapKeys() {
        final HonestLabel.Result r = new HonestLabel.Result();
        r.check = "win";
        final JsonObject ir = RlReplayLabeller.indexRow(header(r, new StepSearch.Result()));
        for (String k : new String[] {"cap", "cap_hits", "worlds_capped", "proof_cap"}) {
            Assert.assertFalse(ir.has(k), k + " in " + ir);
        }
        Assert.assertFalse(ir.getAsJsonObject("search").has("solver_caps"), ir.toString());
    }
}
