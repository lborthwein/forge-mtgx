package forge.ai.simulation;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;
import forge.game.Game;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Real look-ahead world construction must permit the engine's later copies of that world. */
public class LookaheadBeliefStaticsTest extends SimulationTest {
    private Game source() {
        Game g = initAndCreateGame();
        g.getPhaseHandler().devModeSet(PhaseType.MAIN2, g.getPlayers().get(0));
        addCardToZone("Grist, the Hunger Tide", g.getPlayers().get(1), ZoneType.Library);
        g.getAction().checkStateEffects(true);
        return g;
    }

    private Game world(Game live, boolean on) {
        LookaheadSearch.Config cfg = new LookaheadSearch.Config();
        cfg.belief = "uniform";
        cfg.beliefPool = List.of("Grizzly Bears");
        cfg.beliefBasics = 2;
        cfg.threads = 1;
        cfg.beliefStatics = on;
        LookaheadSearch search = new LookaheadSearch(cfg);
        try {
            Player me = live.getPlayers().get(0);
            LookaheadSearch.Prepared p = search.prepare(live, me,
                    new LookaheadSearch.Cand((SpellAbility) null, true), null, 73, true, null);
            Assert.assertFalse(p.failed, String.valueOf(p.error));
            Assert.assertNotEquals(p.g.getPlayers().get(1).getCardsIn(ZoneType.Library).get(0).getName(),
                    "Grist, the Hunger Tide");
            return p.g;
        } finally { search.shutdown(); }
    }

    @Test
    public void resampledLookaheadWorldReproducesOffAndCopiesOn() {
        Game live = source();
        Game world = world(live, false);
        try {
            new GameCopier(world, true).makeCopy();
            Assert.fail("Expected the existing resampled look-ahead path to reproduce the missing-card mapping failure");
        } catch (RuntimeException failure) {
            Assert.assertTrue(failure.getMessage().startsWith("Couldn't map Grist"), failure.toString());
        }
        Assert.assertTrue(live.getPlayers().get(1).getCardsIn(ZoneType.Library).get(0).isCreature());
        Assert.assertFalse(live.staleStatics);
        Game enabled = world(live, true);
        Assert.assertTrue(enabled.staleStatics);
        Game copied = new GameCopier(enabled, true).makeCopy();
        Assert.assertEquals(copied.getPlayers().get(1).getCardsIn(ZoneType.Library).size(), 1);
        Assert.assertTrue(copied.staleStatics);
        Assert.assertNotNull(new GameCopier(copied, true).makeCopy());
        Assert.assertFalse(live.staleStatics, "The flag belongs to the disposable world, not the live game");
    }

    @Test
    public void benchConfigurationIsDefaultOffAndRoundTripsWhenEnabled() {
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        spec.addProperty("server", "127.0.0.1:1");
        forge.bench.rl.RlSearch.Config off = forge.bench.rl.RlSearch.Config.parse(spec);
        Assert.assertFalse(off.beliefStatics);
        Assert.assertFalse(off.toJson().has("beliefStatics"));
        spec.addProperty("belief", "uniform");
        spec.addProperty("beliefCube", "parse-only-pool.json");
        spec.addProperty("beliefStatics", true);
        forge.bench.rl.RlSearch.Config on = forge.bench.rl.RlSearch.Config.parse(spec);
        Assert.assertTrue(on.beliefStatics);
        Assert.assertTrue(on.toJson().get("beliefStatics").getAsBoolean());
    }
}
