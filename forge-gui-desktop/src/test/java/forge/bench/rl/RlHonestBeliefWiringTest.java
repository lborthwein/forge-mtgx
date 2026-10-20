package forge.bench.rl;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonParser;

import forge.ai.simulation.BeliefSampler;

/**
 * honest-belief-1020: the S1 search hands its belief to the solver (verification worlds, engine root) exactly when a
 * belief is on, and the live seat's spec always has one.
 */
public class RlHonestBeliefWiringTest extends forge.ai.simulation.SimulationTest {

    private static RlSearch.Config cfg(final String extra) {
        return RlSearch.Config.parse(JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"leaf\":\"value\","
                + "\"playout\":\"policy\"" + extra + "}").getAsJsonObject());
    }

    @Test
    public void theSolverGetsTheRedrawExactlyWhenABeliefIsOn() {
        initAndCreateGame();
        final RlSearch off = new RlSearch(cfg(""), 1L, 2L, null, null, "jar", "t", 1);
        Assert.assertNull(off.ls().opponentRedraw(), "belief off: the solver as before");
        off.close();
        final RlSearch on = new RlSearch(cfg(",\"belief\":\"uniform\",\"beliefCube\":\"" + BeliefSampler.LIVE_CUBE
                + "\",\"beliefCubeSha256\":\"" + BeliefSampler.LIVE_CUBE_SHA256 + "\""), 1L, 2L, null, null, "jar", "t", 1);
        Assert.assertNotNull(on.ls().opponentRedraw());
        Assert.assertEquals(on.ls().getConfig().belief, "uniform");
        on.close();
        Assert.assertThrows(IllegalArgumentException.class, () -> cfg(",\"belief\":\"uniform\""));
        Assert.assertThrows(IllegalArgumentException.class, () -> cfg(",\"belief\":\"human\""));
        Assert.assertFalse(cfg("").toJson().has("belief"), "a spec without belief keeps its JSON");
    }

    @Test
    public void theLiveSeatSpecAlwaysSearchesHonestly() {
        initAndCreateGame();
        final RlLiveSeat.Spec s = RlLiveSeat.Spec.parse("server=127.0.0.1:1,policySha=" + "a".repeat(64) + ",cardIndex=/x");
        final RlSearch live = new RlSearch(s.search, 1L, 2L, null, null, "jar", "t", 1);
        Assert.assertNotNull(live.ls().opponentRedraw());
        live.close();
        Assert.assertThrows(IllegalArgumentException.class,
                () -> RlLiveSeat.Spec.parse("server=127.0.0.1:1,policySha=" + "a".repeat(64) + ",cardIndex=/x,belief=off"));
    }
}
