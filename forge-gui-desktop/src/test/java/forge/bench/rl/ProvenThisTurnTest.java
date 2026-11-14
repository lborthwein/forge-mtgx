package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonParser;

/**
 * proven-turn-1114: plans.provenThisTurn (default off). The owner's NA05 position (live 2026-11-14, sessions 51aa319c
 * and 2b39bb16): on turn 14 the policy seat's solver line "Chandra +1 now, Chandra +1 on our next turn" was proven
 * (it won unopposed and in its belief worlds, Forge AI playing the other seat with a sampled hand) and was taken at
 * once, so Chandra's -3 on Restoration Angel was never a candidate; the owner had Kiki-Jiki and won. With the key the
 * same line (winTurn 1) is no longer taken at once and goes to S-c as a line candidate; a line that wins this turn
 * (winTurn 0) still is. No game, no card database: the gate and the spec only.
 */
public class ProvenThisTurnTest {

    @Test
    public void offByDefaultAndTheGateIsUnchanged() {
        final PlanSearch.Spec off = PlanSearch.Spec.parse(JsonParser.parseString("{\"unified\":true}").getAsJsonObject());
        Assert.assertFalse(off.provenThisTurn);
        // NA05's turn-14 line: proven, wins on our next turn
        Assert.assertTrue(PlanSearch.takenAtOnce(true, 1, off.provenThisTurn), "today: a proven next-turn line is taken");
        Assert.assertTrue(PlanSearch.takenAtOnce(true, 0, off.provenThisTurn));
        Assert.assertFalse(PlanSearch.takenAtOnce(false, 0, off.provenThisTurn));
    }

    @Test
    public void withTheKeyOnlyThisTurnLinesAreTakenAtOnce() {
        final PlanSearch.Spec on = PlanSearch.Spec.parse(
                JsonParser.parseString("{\"unified\":true,\"provenThisTurn\":true}").getAsJsonObject());
        Assert.assertTrue(on.provenThisTurn);
        Assert.assertFalse(PlanSearch.takenAtOnce(true, 1, on.provenThisTurn),
                "NA05: a proven line that passes the turn is searched, not taken at once");
        Assert.assertFalse(PlanSearch.takenAtOnce(true, 2, on.provenThisTurn));
        Assert.assertTrue(PlanSearch.takenAtOnce(true, 0, on.provenThisTurn), "a this-turn kill is still taken at once");
        Assert.assertFalse(PlanSearch.takenAtOnce(false, 0, on.provenThisTurn));
    }

    @Test
    public void liveStringAcceptsTheKey() {
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse(ShipASpecParseTest.HOST + ShipASpecParseTest.TODAY
                + ",plans.unified=true,plans.provenThisTurn=true");
        Assert.assertTrue(PlanSearch.Spec.parse(s.search.plans).provenThisTurn);
        final RlLiveSeat.Spec today = RlLiveSeat.Spec.parse(ShipASpecParseTest.HOST + ShipASpecParseTest.TODAY);
        Assert.assertFalse(today.search.plans.has("provenThisTurn"), "today's string carries no new key");
        Assert.assertThrows(IllegalArgumentException.class, () -> RlLiveSeat.Spec.parse(ShipASpecParseTest.HOST
                + ShipASpecParseTest.TODAY + ",plans.provenThisTurn=1"));
    }
}
