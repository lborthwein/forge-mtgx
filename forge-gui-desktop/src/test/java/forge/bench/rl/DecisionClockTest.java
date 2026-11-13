package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.util.StateMachine;

/** state-machines-1113: {@link DecisionClock}, the explicit phases of one searched pilot decision. */
public class DecisionClockTest {

    @Test
    public void aPriorityDecisionHasScoreAndGiven() {
        final DecisionClock ck = new DecisionClock("seat0", System.nanoTime());
        ck.ended(DecisionClock.Phase.SCORE);
        ck.ended(DecisionClock.Phase.GIVEN);
        final JsonObject o = ck.toJson();
        Assert.assertEquals(o.keySet(), java.util.Set.of("score", "given", "total"), o.toString());
        Assert.assertEquals(ck.phase(), DecisionClock.Phase.GIVEN);
    }

    @Test
    public void aPlanPointHasEveryPhaseAndTheSolverPart() throws InterruptedException {
        final DecisionClock ck = new DecisionClock("seat0", System.nanoTime());
        ck.ended(DecisionClock.Phase.SCORE);
        Thread.sleep(5);
        ck.solver(3.0);
        ck.ended(DecisionClock.Phase.PROPOSE);
        ck.ended(DecisionClock.Phase.GIVEN);
        ck.ended(DecisionClock.Phase.APPLY);
        final JsonObject o = ck.toJson();
        Assert.assertEquals(o.keySet(), java.util.Set.of("score", "propose", "solver", "given", "apply", "total"), o.toString());
        Assert.assertTrue(o.get("propose").getAsDouble() >= 4.5, o.toString());
        Assert.assertEquals(o.get("solver").getAsDouble(), 3.0);
        double sum = 0;
        for (String k : new String[] {"score", "propose", "given", "apply"}) {
            sum += o.get(k).getAsDouble();
        }
        Assert.assertTrue(sum <= o.get("total").getAsDouble() + 0.4, o.toString());
    }

    @Test
    public void aProvenLineEndsAtPropose() {
        final DecisionClock ck = new DecisionClock("seat0", System.nanoTime());
        ck.ended(DecisionClock.Phase.SCORE);
        ck.solver(1.0);
        ck.ended(DecisionClock.Phase.PROPOSE);
        Assert.assertEquals(ck.toJson().keySet(), java.util.Set.of("score", "propose", "solver", "total"));
    }

    @Test
    public void theTableIsTheDecisionOrder() {
        final StateMachine<DecisionClock.Phase> m = DecisionClock.MACHINE;
        Assert.assertTrue(m.allowed(DecisionClock.Phase.SCORE, DecisionClock.Phase.GIVEN), "S-c: no plan point");
        Assert.assertTrue(m.allowed(DecisionClock.Phase.SCORE, DecisionClock.Phase.PROPOSE));
        Assert.assertFalse(m.allowed(DecisionClock.Phase.GIVEN, DecisionClock.Phase.PROPOSE), "no proposal after play-outs");
        Assert.assertFalse(m.allowed(DecisionClock.Phase.START, DecisionClock.Phase.GIVEN), "no search before SCORE");
        Assert.assertTrue(StateMachine.registry().contains(m));
    }
}
