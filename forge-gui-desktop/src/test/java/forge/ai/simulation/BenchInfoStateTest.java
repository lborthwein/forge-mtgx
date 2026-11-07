package forge.ai.simulation;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.rl.RlSearch;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane judge lane. A bench seat with infoState reads no InfoStates (only the live seat attaches one), so each world
 * forgets the top the seat put back. Key off: it forgets. Key on: it keeps, and the solver is armed as live.
 */
public class BenchInfoStateTest extends forge.ai.AITest {
    private static Set<String> tops(final Game g, final InfoStates s) {
        final Set<String> tops = new HashSet<>();
        final Player me = InfoStateLiveTest.me(g);
        for (long seed = 1; seed <= 16; seed++) {
            final Game c = new GameCopier(g, true).makeCopy();
            final Player cm = c.getPlayer(me.getId());
            InfoStates.keepOwnLibrary(s, me, cm, new Random(seed));
            tops.add(InfoStateLiveTest.top(cm, 1).get(0));
        }
        return tops;
    }

    private static RlSearch.Config cfg(final boolean key) {
        final JsonObject o = JsonParser.parseString("{\"server\":\"127.0.0.1:1\",\"playout\":\"policy\",\"leaf\":\"value\",\"budgetMs\":8000,\"infoState\":true" + (key ? ",\"infoAttach\":true" : "")
                + ",\"plans\":{\"defend\":true,\"solverCfg\":{\"hideOwnLibrary\":\"true\"},\"defendCfg\":{}}}").getAsJsonObject();
        return RlSearch.Config.parse(o);
    }

    private static Game putBack(final boolean key) {
        final Game g = InfoStateLiveTest.game("Lightning Bolt", "Island", InfoStateLiveTest.LIB8);
        RlSearch.attachInfoState(g, cfg(key));
        final Player me = InfoStateLiveTest.me(g);
        g.getAction().moveToLibrary(InfoStateLiveTest.find(me, ZoneType.Hand, "Lightning Bolt"), 0, null);
        return g;
    }

    @Test
    public void keyOffForgetsTheTop() {
        final Game g = putBack(false);
        final InfoStates s = InfoStates.find(g);
        Assert.assertNull(s, "no key: nothing attached");
        Assert.assertTrue(tops(g, s).size() > 1, "the top is shuffled away");
    }

    @Test
    public void keyOnKeepsTheTop() {
        final Game g = putBack(true);
        final InfoStates s = InfoStates.find(g);
        Assert.assertNotNull(s);
        Assert.assertEquals(tops(g, s), Set.of("Lightning Bolt"));
    }

    @Test
    public void keyArmsTheSolverAsLive() {
        Assert.assertFalse(cfg(false).plans.getAsJsonObject("solverCfg").has("infoState"));
        final RlSearch.Config on = cfg(true);
        Assert.assertEquals(on.plans.getAsJsonObject("solverCfg").get("infoState").getAsString(), "true");
        Assert.assertEquals(on.plans.getAsJsonObject("defendCfg").get("infoState").getAsString(), "true");
    }
}
