package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.util.TreeSet;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.bench.PlayerControllerBridge;
import forge.bench.rl.BvwStepSearchSpecTest;
import forge.bench.rl.CardIndex;
import forge.bench.rl.FakeSearchService;
import forge.bench.rl.RlKnowledge;
import forge.bench.rl.StepSearch;
import forge.game.Game;
import forge.game.player.Player;
import forge.util.IdScope;

/**
 * Lane bvw-step1-build-1024, review #16a: the step-1 search's value of a position does not depend on hidden cards.
 * The same position is searched with the opponent's real hand and library replaced by other cards and our own library
 * permuted (same counts, same visible state): the result must be identical. The test service values every leaf by a
 * hash of its frame bytes, so any hidden card that reached a frame, a play-out or a candidate would change a value. A
 * visible change (our hand) must change the result, which shows the test can see a difference. Fixture positions only.
 */
public class BvwStepSearchHonestyTest extends SimulationTest {

    static final String SHA = "a".repeat(64);

    static String frame(final String ourHand, final String ourLib, final String theirHand, final String theirLib) {
        return "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\nhumanlandsplayed=0\n"
                + "ailandsplayed=0\naihand=" + theirHand + "\nailibrary=" + theirLib + "\naigraveyard=\naiexile=\n"
                + "aibattlefield=Mountain;Mountain;Grizzly Bears\nhumanhand=" + ourHand + "\nhumanbattlefield=Forest;Forest;Mountain\n"
                + "humangraveyard=\nhumanexile=\nhumanlibrary=" + ourLib + "\n";
    }

    static final String OUR_HAND = "Grizzly Bears;Lightning Bolt;Llanowar Elves";
    static final String OUR_LIB = "Island;Forest;Mountain;Swamp;Plains;Shock;Island;Forest;Hill Giant;Giant Growth";
    static final String OUR_LIB_PERMUTED = "Giant Growth;Plains;Shock;Island;Hill Giant;Swamp;Forest;Island;Mountain;Forest";

    static CardIndex index() {
        final TreeSet<String> names = new TreeSet<>(java.util.Arrays.asList("Island", "Forest", "Mountain", "Swamp",
                "Plains", "Shock", "Hill Giant", "Giant Growth", "Grizzly Bears", "Lightning Bolt", "Llanowar Elves",
                "Counterspell", "Craw Wurm", "Raging Goblin", "Serra Angel", "Dark Ritual"));
        final StringBuilder sb = new StringBuilder("<pad>\t0\n<unk>\t1\n");
        int i = 2;
        for (String n : names) sb.append(n).append('\t').append(i++).append('\n');
        return CardIndex.of(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static StepSearch.Spec spec(final int port) {
        final JsonObject b = JsonParser.parseString("{\"rlsearch\":{\"server\":\"127.0.0.1:" + port + "\",\"policySha\":\""
                + SHA + "\",\"worlds\":8,\"breadth\":3,\"horizon\":1,\"leaf\":\"value\",\"playout\":\"policy\",\"threads\":1,"
                + "\"belief\":\"uniform\",\"beliefCube\":\"" + BeliefSampler.LIVE_CUBE + "\",\"beliefCubeSha256\":\""
                + BeliefSampler.LIVE_CUBE_SHA256 + "\",\"beliefSingleton\":false,\"plans\":{\"samples\":2,"
                + "\"minProvenWorlds\":8,\"solverCfg\":{\"budget\":\"counter\",\"hideOwnLibrary\":\"true\",\"worlds\":\"8\","
                + "\"engineSteps\":\"500\",\"evalSteps\":\"500\",\"engineCards\":\"8000\",\"enginePathCards\":\"8000\"}}},"
                + "\"control\":true,\"maxCopyCards\":200000}").getAsJsonObject();
        return StepSearch.Spec.parse(b, BvwStepSearchSpecTest.label(), 3, 5);
    }

    static JsonObject searchOnce(final StepSearch ss, final String state) {
        final Game g = ForcedWinSolverTest.game("Default", "Default", state);
        final Player me = g.getPlayers().get(0);
        final RlKnowledge know = new RlKnowledge(g);
        know.v2 = true;
        know.attach();
        final String before = LookaheadSearch.fingerprint(g);
        final StepSearch.Result r = PlayerControllerBridge.isolated(g, () -> IdScope.detached(
                () -> ss.value(g, me, know, 2988000001L, 4242L)));
        Assert.assertEquals(LookaheadSearch.fingerprint(g), before, "the search moved the game");
        final JsonObject o = r.toJson();
        o.remove("ms");
        return o;
    }

    @Test(timeOut = 1800000)
    public void searchValuesDoNotDependOnHiddenCards() throws Exception {
        initAndCreateGame();
        try (FakeSearchService svc = new FakeSearchService(SHA, 2)) {
            svc.leafValue = p -> (java.util.Arrays.hashCode(p) % 1000) / 1000.0;
            final StepSearch ss = new StepSearch(spec(svc.port()), index(), 2, "test", "honesty");
            final JsonObject real = searchOnce(ss, frame(OUR_HAND, OUR_LIB, "Counterspell;Hill Giant;Craw Wurm",
                    "Raging Goblin;Island;Island;Shock;Giant Growth;Mountain;Swamp;Plains"));
            System.err.println("[bvw-honesty test] real " + real + " leaves " + svc.leaves.get());
            Assert.assertTrue("searched".equals(real.get("why").getAsString()) || "plan".equals(real.get("why").getAsString()),
                    "the position must be searched: " + real);
            Assert.assertTrue(svc.leaves.get() > 0, "no leaves were valued");
            Assert.assertFalse(real.get("v").isJsonNull(), real.toString());
            // other hidden cards for them (hand and library), our library permuted: identical
            final JsonObject hidden = searchOnce(ss, frame(OUR_HAND, OUR_LIB_PERMUTED, "Serra Angel;Dark Ritual;Island",
                    "Craw Wurm;Counterspell;Hill Giant;Forest;Lightning Bolt;Llanowar Elves;Swamp;Island"));
            Assert.assertEquals(hidden, real, "the search value depends on hidden cards");
            // and once more with the first hidden cards: the test itself repeats
            Assert.assertEquals(searchOnce(ss, frame(OUR_HAND, OUR_LIB, "Counterspell;Hill Giant;Craw Wurm",
                    "Raging Goblin;Island;Island;Shock;Giant Growth;Mountain;Swamp;Plains")), real);
            // a visible change (our hand) changes the result: the hash-valued leaves see differences
            final JsonObject visible = searchOnce(ss, frame("Grizzly Bears;Shock;Llanowar Elves", OUR_LIB,
                    "Counterspell;Hill Giant;Craw Wurm", "Raging Goblin;Island;Island;Shock;Giant Growth;Mountain;Swamp;Plains"));
            Assert.assertNotEquals(visible, real, "a visible change left the result unchanged: the test cannot see");
        }
    }
}
