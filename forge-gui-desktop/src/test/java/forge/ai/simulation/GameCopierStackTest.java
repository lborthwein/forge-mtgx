package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Look-ahead C1: {@link GameCopier#setCopyStack} rebuilds the original stack in the copy (bottom first) with each
 * spell's targets mapped into the copy, including a counterspell's target spell; nothing of the original changes.
 */
public class GameCopierStackTest extends SimulationTest {

    private static SpellAbility putOnStack(Game game, Card card, Player caster, GameObject target) {
        SpellAbility sa = card.getFirstSpellAbility();
        sa.setActivatingPlayer(caster);
        if (target != null) {
            sa.getTargets().add(target);
        }
        game.getStackZone().add(card);
        game.getStack().add(sa);
        return sa;
    }

    @Test
    public void stackOfSpellsIsCopiedWithMappedTargets() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        Card bolt = addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        Card counter = addCardToZone("Counterspell", b, ZoneType.Hand);
        SpellAbility boltSa = putOnStack(game, bolt, a, b);
        SpellAbility counterSa = putOnStack(game, counter, b, boltSa);
        AssertJUnit.assertEquals(2, game.getStack().size());
        AssertJUnit.assertNull(GameCopier.stackUnsupported(game));

        GameCopier copier = new GameCopier(game, true);
        copier.setCopyStack(true);
        Game copy = copier.makeCopy();

        AssertJUnit.assertEquals(2, copy.getStack().size());
        SpellAbilityStackInstance top = copy.getStack().peek();
        SpellAbility topSa = top.getSpellAbility();
        AssertJUnit.assertEquals("Counterspell", topSa.getHostCard().getName());
        AssertJUnit.assertSame(copy, topSa.getHostCard().getGame());
        AssertJUnit.assertSame(copier.find(b), topSa.getActivatingPlayer());

        SpellAbility copiedBolt = null;
        for (SpellAbilityStackInstance si : copy.getStack()) {
            if (si.getSpellAbility().getHostCard().getName().equals("Lightning Bolt")) {
                copiedBolt = si.getSpellAbility();
            }
        }
        AssertJUnit.assertNotNull(copiedBolt);
        AssertJUnit.assertSame(copier.find(b), copiedBolt.getTargets().getFirstTargetedPlayer());
        AssertJUnit.assertSame("the counterspell targets the copied bolt", copiedBolt, topSa.getTargets().getFirstTargetedSpell());
        AssertJUnit.assertSame(copiedBolt, copier.findWithStack(boltSa));

        // The original is untouched: same entries, same targets.
        AssertJUnit.assertSame(counterSa, game.getStack().peekAbility());
        AssertJUnit.assertSame(b, boltSa.getTargets().getFirstTargetedPlayer());
        AssertJUnit.assertSame(game, bolt.getGame());
    }

    @Test
    public void copiedStackResolvesInTheCopyOnly() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        Card bolt = addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        putOnStack(game, bolt, a, b);
        final int life = b.getLife();

        GameCopier copier = new GameCopier(game, true);
        copier.setCopyStack(true);
        Game copy = copier.makeCopy();
        Player copiedB = (Player) copier.find(b);
        copy.getStack().resolveStack();

        AssertJUnit.assertEquals(life - 3, copiedB.getLife());
        AssertJUnit.assertEquals(life, b.getLife());
        AssertJUnit.assertEquals(1, game.getStack().size());
        AssertJUnit.assertTrue(copy.getStack().isEmpty());
    }

    @Test
    public void stackIsNotCopiedUnlessAsked() {
        Game game = initAndCreateGame();
        Player a = game.getPlayers().get(0);
        Player b = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, a);
        Card bolt = addCardToZone("Lightning Bolt", a, ZoneType.Hand);
        putOnStack(game, bolt, a, b);

        Game copy = new GameCopier(game, true).makeCopy();
        AssertJUnit.assertTrue(copy.getStack().isEmpty());
    }
}
