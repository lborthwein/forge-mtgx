package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

/**
 * owner-misplays-1113: the three keys this branch adds to what the live policy-search string may hold, all absent = off:
 * {@code plans.solverCfg.planLoops} (the planner screens count a copy loop), {@code plans.solverCfg.engineModeChoice}
 * (a modal spell's modes are a choice point of the engine search, bound in the line), {@code choiceScorer.reachDiscards}
 * (the seat's own discards and card costs get the card scorer's reach blend). Configs only: no game, no card database.
 */
public class OwnerMisplaysSpecTest {

    static final String SHIP = ShipASpecParseTest.HOST + ShipASpecParseTest.TODAY + "," + ShipASpecParseTest.SHIPA_ADDS;

    private static JsonObject solverCfg(final String spec) {
        return RlLiveSeat.Spec.parse(spec).search.plans.getAsJsonObject("solverCfg");
    }

    @Test
    public void theShipStringHasNoneOfTheKeys() {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(SHIP).search;
        Assert.assertFalse(solverCfg(SHIP).has("planLoops"));
        Assert.assertFalse(solverCfg(SHIP).has("engineModeChoice"));
        Assert.assertFalse(c.choiceScorer.has("reachDiscards"));
        Assert.assertFalse(PlanSearch.Spec.parse(c.plans).solverConfig().planLoops);
        Assert.assertFalse(PlanSearch.Spec.parse(c.plans).solverConfig().engineModeChoice);
    }

    @Test
    public void eachKeyParsesAndReachesItsConfig() {
        final RlSearch.Config c = RlLiveSeat.Spec.parse(SHIP + ",plans.solverCfg.planLoops=true,plans.solverCfg.engineModeChoice=true,"
                + "choiceScorer.reachDiscards=true").search;
        Assert.assertTrue(PlanSearch.Spec.parse(c.plans).solverConfig().planLoops);
        Assert.assertTrue(PlanSearch.Spec.parse(c.plans).solverConfig().engineModeChoice);
        final ChoiceScorer.Config sc = ChoiceScorer.Config.parse(c.choiceScorer);
        Assert.assertTrue(sc.reachDiscards);
        Assert.assertTrue(sc.families.contains(RlSchema.F_DISCARD_FROM) && sc.families.contains(RlSchema.F_COST_CARDS),
                "reachDiscards adds the discard and card-cost families: " + sc.families);
    }

    @Test
    public void badValuesAreRefusedAndTheScorerKeyNeedsItsScreen() {
        for (String bad : new String[] {"plans.solverCfg.planLoops=1", "plans.solverCfg.engineModeChoice=yes"}) {
            try {
                RlLiveSeat.Spec.parse(SHIP + "," + bad);
                Assert.fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // a flag key takes true or false
            }
        }
        try {
            // before reachAlpha and the screen keys are set the scorer's own parser refuses it: the key goes last in a string
            RlLiveSeat.Spec.parse(ShipASpecParseTest.HOST + ShipASpecParseTest.TODAY + ",choiceScorer.reachDiscards=true");
            Assert.fail("accepted reachDiscards without reachAlpha");
        } catch (IllegalArgumentException expected) {
            // "reachDiscards needs reachAlpha and the planner screen"
        }
    }
}
