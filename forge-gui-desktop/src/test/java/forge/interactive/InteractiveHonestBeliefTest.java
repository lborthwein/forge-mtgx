package forge.interactive;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.ai.simulation.BeliefSampler;
import forge.ai.simulation.LookaheadSearch;

/**
 * honest-belief-1020: the interactive server's look-ahead seats are honest whatever the host's look-ahead spec says
 * (belief=uniform, reuse off), over the event's pool when the config line carries one, else the jar's live pool; the
 * config's beliefPool keys are optional and checked.
 */
public class InteractiveHonestBeliefTest extends forge.ai.simulation.SimulationTest {

    private static final String BASE = "{\"protocol\":\"" + InteractiveProtocol.VERSION + "\",\"type\":\"config\",\"session\":\"s\","
            + "\"humanSeat\":0,\"seed\":1,\"aiProfile\":\"Default\",\"decks\":[\"/a.dck\",\"/b.dck\"]";

    @org.testng.annotations.BeforeMethod
    public void db() {
        initAndCreateGame(); // the card database (pool names resolve against it)
    }

    @AfterMethod
    public void clear() {
        InteractiveMain.resetPool(null);
    }

    @Test
    public void theLiveK8SpecBecomesHonest() {
        InteractiveMain.resetPool(null);
        final LookaheadSearch.Config c = InteractiveMain.parseLookahead(
                "worlds=8,breadth=4,horizon=2,threads=8,budgetMs=8000,log=1,reuse=1,departZ=1.645,aiFixes0928=on").config;
        Assert.assertEquals(c.belief, "uniform");
        Assert.assertFalse(c.reuse, "carried worlds hold real cards");
        Assert.assertEquals(c.beliefCube, BeliefSampler.LIVE_CUBE);
        Assert.assertEquals(c.beliefCubeSha256, BeliefSampler.LIVE_CUBE_SHA256);
        Assert.assertEquals(c.beliefSingleton, BeliefSampler.LIVE_CUBE_SINGLETON);
        Assert.assertNull(c.beliefPool);
        Assert.assertEquals(c.worlds, 8);
    }

    @Test
    public void theEventPoolIsUsedWhenSent() throws Exception {
        final InteractiveProtocol.BeliefPool p = InteractiveProtocol.readBeliefPool(
                BASE + ",\"beliefPool\":[\"Shock\",\" Island \"],\"beliefPoolSingleton\":true}");
        Assert.assertEquals(p.names(), List.of("Shock", "Island"));
        Assert.assertTrue(p.singleton());
        InteractiveMain.resetPool(p);
        final LookaheadSearch.Config c = InteractiveMain.parseLookahead("worlds=8,reuse=1").config;
        Assert.assertEquals(c.beliefPool, List.of("Shock"), "settled: resolved Forge names, basics are the pool's own");
        Assert.assertTrue(c.beliefSingleton);
        Assert.assertNull(c.beliefCube);
        Assert.assertEquals(InteractiveProtocol.readBeliefPool(BASE + ",\"beliefPool\":[\"Shock\"]}").singleton(), false);
        Assert.assertNull(InteractiveProtocol.readBeliefPool(BASE + "}"));
        // the config itself still parses with the extra keys (and an old reader ignores them)
        Assert.assertEquals(InteractiveProtocol.readConfig(BASE + ",\"beliefPool\":[\"Shock\"]}").seed(), 1L);
    }

    @Test
    public void aPoolThatDoesNotResolveFallsBackToTheJarPool() {
        for (List<String> bad : List.of(List.of("Island", "Plains"), List.of("No Such Card 1", "No Such Card 2", "Shock"))) {
            InteractiveMain.resetPool(new InteractiveProtocol.BeliefPool(bad, true));
            final LookaheadSearch.Config c = InteractiveMain.parseLookahead("worlds=8").config;
            Assert.assertNull(c.beliefPool, bad.toString());
            Assert.assertEquals(c.beliefCube, BeliefSampler.LIVE_CUBE);
            Assert.assertEquals(c.beliefSingleton, BeliefSampler.LIVE_CUBE_SINGLETON);
        }
        // a quarter unresolved at most is kept (the resolved names only)
        InteractiveMain.resetPool(new InteractiveProtocol.BeliefPool(
                List.of("Shock", "Lightning Bolt", "Counterspell", "No Such Card"), false));
        Assert.assertEquals(InteractiveMain.parseLookahead("worlds=8").config.beliefPool,
                List.of("Shock", "Lightning Bolt", "Counterspell"));
    }

    @Test
    public void aBadPoolIsRefused() {
        for (String bad : new String[] {",\"beliefPool\":[]}", ",\"beliefPool\":\"Shock\"}", ",\"beliefPool\":[1]}",
                ",\"beliefPool\":[\" \"]}", ",\"beliefPoolSingleton\":true}", ",\"beliefPool\":[\"Shock\"],\"beliefPoolSingleton\":1}"}) {
            try {
                InteractiveProtocol.readBeliefPool(BASE + bad);
                Assert.fail("accepted " + bad);
            } catch (InteractiveProtocol.ProtocolException expected) {
                Assert.assertEquals(expected.code(), "invalid_config");
            }
        }
    }
}
