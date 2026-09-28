package forge.ai.simulation;

import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Target variants (Config.targetVariants): a single-target spell among the look-ahead's candidates is also offered
 * with the other legal targets, so play-outs (not Forge AI's targeting) can choose, e.g., burn to the face. Off (0)
 * adds nothing; a variant maps to the live game with exactly its target; enumeration never touches the live game.
 */
public class LookaheadTargetVariantsTest extends SimulationTest {

    private Game board() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        for (Player p : new Player[] {a, b}) {
            for (int i = 0; i < 20; i++) {
                addCardToZone(i % 2 == 0 ? "Mountain" : "Grizzly Bears", p, ZoneType.Library);
            }
            addCard("Mountain", p);
            addCard("Mountain", p);
        }
        addCard("Llanowar Elves", b);
        addCard("Grizzly Bears", b);
        addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private static LookaheadSearch search(int variants) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.worlds = 2;
        c.breadth = 4;
        c.horizonTurns = 1;
        c.threads = 1;
        c.seed = 719_000_002L;
        c.targetVariants = variants;
        return new LookaheadSearch(c);
    }

    private static SpellAbility bolt(Player a) {
        for (Card c : a.getCardsIn(ZoneType.Hand)) {
            if (c.getName().equals("Lightning Bolt")) {
                return c.getFirstSpellAbility();
            }
        }
        return null;
    }

    @Test
    public void offAddsNoVariant() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        LookaheadSearch s = search(0);
        try {
            List<LookaheadSearch.Cand> cands = s.enumerate(game, a, null, 1L, null);
            for (LookaheadSearch.Cand c : cands) {
                AssertJUnit.assertNull(c.tgt);
            }
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void variantsOfForgesAnswerAndTheirMapping() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        // Forge's answer: Lightning Bolt at the Grizzly Bears.
        SpellAbility def = bolt(a);
        def.setActivatingPlayer(a);
        Card bears = null;
        for (Card c : b.getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals("Grizzly Bears")) {
                bears = c;
            }
        }
        def.getTargets().add(bears);
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = search(3);
        try {
            List<LookaheadSearch.Cand> cands = s.enumerate(game, a, def, 1L, null);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            LookaheadSearch.Cand face = null;
            int variants = 0;
            for (LookaheadSearch.Cand c : cands) {
                if (c.tgt != null) {
                    variants++;
                    AssertJUnit.assertFalse("Forge's own target is not repeated", c.tgt.equals("C" + bears.getId()));
                    if (c.tgt.equals("P1")) {
                        face = c;
                    }
                }
            }
            AssertJUnit.assertEquals(3, variants);
            AssertJUnit.assertNotNull("the opponent's face is the first variant", face);
            AssertJUnit.assertEquals("P1", cands.get(2).tgt);
            List<SpellAbility> mapped = s.mapToLive(ctrl, game, a, face);
            AssertJUnit.assertNotNull(mapped);
            AssertJUnit.assertSame(b, mapped.get(0).getTargets().getFirstTargetedPlayer());
            AssertJUnit.assertEquals(1, mapped.get(0).getTargets().size());
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void searchedDecisionWithVariantsLeavesLiveUntouched() {
        Game game = board();
        Player a = game.getPlayers().get(0);
        PlayerControllerAi ctrl = (PlayerControllerAi) a.getController();
        List<SpellAbility> def = ctrl.chooseSpellAbilityToPlay();
        String before = LookaheadSearch.fingerprint(game);
        LookaheadSearch s = search(3);
        try {
            s.decide(ctrl, def);
            AssertJUnit.assertEquals(before, LookaheadSearch.fingerprint(game));
            AssertJUnit.assertEquals(0, s.getStats().rolloutFailures);
        } finally {
            s.shutdown();
        }
    }
}
