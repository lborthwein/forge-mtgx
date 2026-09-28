package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiPlayDecision;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * candprep (Config.forceRefused): a targeted candidate that Forge AI refuses to play (CantPlayAi) is played out with the
 * targets Forge AI picks when the ability is mandatory, if they are legal, instead of being dropped unscored. "off" (the
 * default) and "shadow" leave every decision and play-out unchanged; the live game is never touched by enumeration.
 */
public class LookaheadForceRefusedTest extends SimulationTest {

    /** Player a: Swords to Plowshares in hand, its only creature target its own Grizzly Bears; b has no creature. */
    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Plains" : "Grizzly Bears", p, ZoneType.Library);
            }
            addCard("Plains", p);
            addCard("Plains", p);
        }
        addCard("Grizzly Bears", a);
        addCardToZone("Swords to Plowshares", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch search(String force) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 1;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 719_000_003L;
        c.forceRefused = force;
        return new LookaheadSearch(c);
    }

    private static Card named(Player p, ZoneType z, String name) {
        for (Card c : p.getCardsIn(z)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void offByDefault() {
        AssertJUnit.assertEquals("off", new LookaheadSearch.Config().forceRefused);
        AssertJUnit.assertFalse(new LookaheadSearch.Config().forceComputed());
    }

    @Test
    public void forcedTargetsAreForgesMandatoryChoiceAndLegal() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        Card bears = named(a, ZoneType.Battlefield, "Grizzly Bears");
        SpellAbility sa = named(a, ZoneType.Hand, "Swords to Plowshares").getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        AiPlayDecision dec = ((PlayerControllerAi) a.getController()).getAi().canPlaySa(sa);
        AssertJUnit.assertEquals("Forge AI will not exile its own creature", AiPlayDecision.CantPlayAi, dec);
        String[] why = new String[1];
        String spec = LookaheadSearch.forcedTargets(game, a, sa, why);
        AssertJUnit.assertEquals("0:ChangeZone:C" + bears.getId() + ";", spec);

        // The spec maps into a copy (same card ids), onto the copy's own card.
        Game copy = new GameCopier(game).makeCopy();
        Player ca = copy.getPlayers().get(0);
        SpellAbility csa = named(ca, ZoneType.Hand, "Swords to Plowshares").getFirstSpellAbility();
        csa.setActivatingPlayer(ca);
        AssertJUnit.assertTrue(LookaheadSearch.applyForce(csa, spec, copy, ca));
        Card target = (Card) csa.getTargets().getFirstTargetedCard();
        AssertJUnit.assertSame(copy, target.getGame());
        AssertJUnit.assertEquals(bears.getId(), target.getId());
    }

    @Test
    public void anIllegalSpecIsRefused() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        Card plains = named(a, ZoneType.Battlefield, "Plains");
        SpellAbility sa = named(a, ZoneType.Hand, "Swords to Plowshares").getFirstSpellAbility();
        sa.setActivatingPlayer(a);
        AssertJUnit.assertFalse("a land is not a legal target", LookaheadSearch.applyForce(sa, "0:ChangeZone:C" + plains.getId() + ";", game, a));
        AssertJUnit.assertTrue("targets are left clear", sa.getTargets().isEmpty());
        AssertJUnit.assertFalse("a player is not a legal target", LookaheadSearch.applyForce(sa, "0:ChangeZone:P1;", game, a));
        AssertJUnit.assertFalse("the API must match", LookaheadSearch.applyForce(sa, "0:Destroy:C" + named(a, ZoneType.Battlefield, "Grizzly Bears").getId() + ";", game, a));
        AssertJUnit.assertFalse("no target is not enough", LookaheadSearch.applyForce(sa, "0:ChangeZone:;", game, a));
    }

    private static LookaheadSearch.Stats run(String force, String[] fpOut) {
        LookaheadForceRefusedTest t = new LookaheadForceRefusedTest();
        Game game = t.board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = search(force);
        try {
            List<SpellAbility> ans = s.decide(ctrl, def);
            AssertJUnit.assertEquals("the live game is untouched", before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertTrue("exiling its own creature is never chosen", ans == null || ans.isEmpty());
            fpOut[0] = before;
            return s.getStats();
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void offDropsShadowCountsOnPlays() {
        String[] fp = new String[1];
        LookaheadSearch.Stats off = run("off", fp);
        AssertJUnit.assertTrue("off: the refused candidate is dropped", off.rolloutFailures > 0);
        AssertJUnit.assertEquals(0, off.forceTargeted);
        AssertJUnit.assertTrue(off.forcePrepWhy.isEmpty());

        LookaheadSearch.Stats sh = run("shadow", fp);
        AssertJUnit.assertEquals("shadow plays out exactly what off does", off.rollouts, sh.rollouts);
        AssertJUnit.assertEquals(off.rolloutFailures, sh.rolloutFailures);
        AssertJUnit.assertEquals(off.steps, sh.steps);
        AssertJUnit.assertEquals(0, sh.forcePrepared);
        AssertJUnit.assertTrue(sh.forceSpecs >= 1);
        AssertJUnit.assertTrue(sh.forcePrepWhy.keySet().iterator().next().endsWith("|shadowSpec"));

        LookaheadSearch.Stats on = run("on", fp);
        AssertJUnit.assertEquals("on: nothing is dropped", 0, on.rolloutFailures);
        AssertJUnit.assertTrue(on.forcePrepared >= 1);
        AssertJUnit.assertEquals(off.rollouts, on.rollouts);
        AssertJUnit.assertEquals(0, on.forceLive);
    }
}
