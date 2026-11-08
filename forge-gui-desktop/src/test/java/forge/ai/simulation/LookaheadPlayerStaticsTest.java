package forge.ai.simulation;

import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Engine-generated player-only effect; real look-ahead resampling and subsequent copying. */
public class LookaheadPlayerStaticsTest extends SimulationTest {
    /**
     * live-fixes-1107: this jar turns round 1's copier fixes on by default (forge.liveFixes), and researchFixes' belief-world
     * static refresh already repairs what this test's "off" leg reproduces. The test reads its own key in isolation.
     */
    private boolean researchWas, robustWas;

    @BeforeMethod
    public void roundOneFixesOff() {
        researchWas = forge.game.EngineFixes.researchFixes();
        robustWas = forge.game.EngineFixes.copyRobust();
        forge.game.EngineFixes.setResearchFixesForTest(false);
        forge.game.EngineFixes.setCopyRobustForTest(false);
    }

    @AfterMethod
    public void roundOneFixesBack() {
        forge.game.EngineFixes.setResearchFixesForTest(researchWas);
        forge.game.EngineFixes.setCopyRobustForTest(robustWas);
    }

    private Game source(String limit) {
        Game game = initAndCreateGame();
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, game.getPlayers().get(0));
        Player owner = game.getPlayers().get(1);
        forge.item.PaperCard paper = new forge.item.PaperCard(forge.card.CardRules.fromScript(List.of(
                "Name:Test library player effect", "ManaCost:0", "Types:Artifact", "Oracle:",
                "S:Mode$ Continuous | EffectZone$ Library | Affected$ You | SetMaxHandSize$ " + limit)),
                "", forge.card.CardRarity.Common);
        Card source = Card.fromPaperCard(paper, owner);
        source.setGameTimestamp(game.getNextTimestamp());
        owner.getZone(ZoneType.Library).add(source);
        game.getAction().checkStateEffects(true);
        Assert.assertEquals(owner.isUnlimitedHandSize(), limit.equals("Unlimited"));
        if (!limit.equals("Unlimited")) Assert.assertEquals(owner.getMaxHandSize(), 3);
        return game;
    }

    private Game world(Game live, boolean enabled) {
        LookaheadSearch.Config cfg = new LookaheadSearch.Config();
        cfg.belief = "uniform";
        cfg.beliefPool = List.of("Grizzly Bears");
        cfg.beliefBasics = 0;
        cfg.threads = 1;
        cfg.beliefStatics = true;
        cfg.beliefPlayerStatics = enabled;
        LookaheadSearch search = new LookaheadSearch(cfg);
        try {
            LookaheadSearch.Prepared p = search.prepare(live, live.getPlayers().get(0),
                    new LookaheadSearch.Cand((SpellAbility) null, true), null, 73, true, null);
            Assert.assertFalse(p.failed, String.valueOf(p.error));
            Assert.assertEquals(p.g.getPlayers().get(1).getCardsIn(ZoneType.Library).get(0).getName(), "Grizzly Bears");
            Assert.assertTrue(p.g.staleStatics);
            Assert.assertEquals(p.g.stalePlayerStatics, enabled);
            return p.g;
        } finally { search.shutdown(); }
    }

    @Test public void unlimitedHandEffectReproducesOffAndRepairsOn() { check("Unlimited"); }
    @Test public void numericHandEffectReproducesOffAndRepairsOn() { check("3"); }
    private void check(String limit) {
        Game live = source(limit);
        Game oldCopy = new GameCopier(world(live, false), true).makeCopy();
        Player wrong = oldCopy.getPlayers().get(1);
        Assert.assertEquals(wrong.isUnlimitedHandSize(), limit.equals("Unlimited"), "Old p8 path preserves stale player-only effect");
        if (!limit.equals("Unlimited")) Assert.assertEquals(wrong.getMaxHandSize(), 3);
        Game fixed = new GameCopier(world(live, true), true).makeCopy();
        Player correct = fixed.getPlayers().get(1);
        Assert.assertFalse(correct.isUnlimitedHandSize());
        Assert.assertEquals(correct.getMaxHandSize(), correct.getStartingHandSize());
        Assert.assertTrue(fixed.stalePlayerStatics);
        Assert.assertFalse(new GameCopier(fixed, true).makeCopy().getPlayers().get(1).isUnlimitedHandSize());
        Assert.assertEquals(live.getPlayers().get(1).isUnlimitedHandSize(), limit.equals("Unlimited"));
        if (!limit.equals("Unlimited")) Assert.assertEquals(live.getPlayers().get(1).getMaxHandSize(), 3);
        Assert.assertFalse(live.stalePlayerStatics);
    }
    @Test public void defaultOffAndRoundTrip() {
        com.google.gson.JsonObject spec = new com.google.gson.JsonObject();
        spec.addProperty("server", "127.0.0.1:1");
        forge.bench.rl.RlSearch.Config off = forge.bench.rl.RlSearch.Config.parse(spec);
        Assert.assertEquals(off.beliefPlayerStatics, LookaheadSearch.LIVE_BELIEF_PLAYER_STATICS); // live-fixes-1107: default follows forge.liveFixes
        Assert.assertFalse(off.toJson().has("beliefPlayerStatics")); // belief off: the belief keys are not in the JSON
        spec.addProperty("belief", "uniform");
        spec.addProperty("beliefCube", "parse-only.json");
        spec.addProperty("beliefPlayerStatics", true);
        forge.bench.rl.RlSearch.Config on = forge.bench.rl.RlSearch.Config.parse(spec);
        Assert.assertTrue(on.beliefPlayerStatics);
        Assert.assertTrue(on.toJson().get("beliefPlayerStatics").getAsBoolean());
    }
}
