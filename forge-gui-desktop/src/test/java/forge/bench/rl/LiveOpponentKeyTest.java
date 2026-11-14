package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * e2-live-1114: the live k=v string takes {@code opponent} (forge, the default, or policy), the key bench specs already
 * carry as {@code search.opponent}. Absent, the seat is as before; {@code policy} plays every play-out's opponent with the
 * same policy seat as ours (it needs playout=policy, the live default); any other value refuses the whole spec, as the
 * bench config does.
 */
public class LiveOpponentKeyTest {

    private static final String LIVE = ShipASpecParseTest.HOST + ShipASpecParseTest.TODAY;

    @Test
    public void absentIsForgeAndPolicyIsTaken() {
        final RlSearch.Config off = RlLiveSeat.Spec.parse(LIVE).search;
        Assert.assertEquals(off.opponent, "forge", "absent = the Forge AI opponent, as before");
        Assert.assertFalse(off.toJson().has("opponent"), "absent writes no opponent into the spec's JSON");
        Assert.assertEquals(RlLiveSeat.Spec.parse(LIVE + ",opponent=forge").search.opponent, "forge");
        final RlSearch.Config on = RlLiveSeat.Spec.parse(LIVE + ",opponent=policy").search;
        Assert.assertEquals(on.playout, "policy", "the live default play-out");
        Assert.assertEquals(on.opponent, "policy");
        Assert.assertEquals(on.toJson().get("opponent").getAsString(), "policy", "the journaled spec records it");
    }

    @Test
    public void otherValuesAndAForgePlayoutAreRefused() {
        for (String kv : new String[] {"opponent=Policy", "opponent=lookahead", "opponent=", "opponent=1",
                "playout=forge,opponent=policy"}) {
            try {
                RlLiveSeat.Spec.parse(LIVE + "," + kv);
                Assert.fail("accepted " + kv);
            } catch (IllegalArgumentException expected) {
                // forge|policy, and policy only with policy play-outs
            }
        }
    }
}
