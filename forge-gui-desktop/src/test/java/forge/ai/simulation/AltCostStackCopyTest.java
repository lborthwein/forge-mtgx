package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.game.EngineFixes;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Lane loop-exec-1107 (ledger row 44). Underworld Breach grants escape to the cards in our graveyard. A spell cast with
 * that escape sits on the stack with an ability the card has only in the graveyard, so the copier found no ability for
 * it and threw: every play-out of such a position failed. Key off: the copy throws. Key on: the copy holds the spell, with
 * its escape cost.
 */
public class AltCostStackCopyTest extends SimulationTest {

    /** live-fixes-1107: forge.liveFixes defaults the key on in this jar; each test starts from key off, as written. */
    @BeforeMethod
    public void keyOffFirst() {
        reset();
    }

    @AfterMethod
    public void reset() {
        EngineFixes.setCopyAltCostForTest(false);
    }

    private Game live() {
        final Game game = initAndCreateGame();
        final Player me = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, me);
        addCard("Underworld Breach", me);
        addCard("Island", me);
        final Card preordain = addCardToZone("Preordain", me, ZoneType.Graveyard);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Island", me, ZoneType.Graveyard);
        }
        game.getAction().checkStateEffects(true);
        SpellAbility escape = null;
        for (SpellAbility sa : preordain.getAllPossibleAbilities(me, true)) {
            if (sa.isSpell() && sa.isAlternativeCost(AlternativeCost.Escape)) {
                escape = sa;
            }
        }
        AssertJUnit.assertNotNull("Breach grants escape", escape);
        escape.setActivatingPlayer(me);
        game.getStack().addAndUnfreeze(escape);
        AssertJUnit.assertEquals(1, game.getStack().size());
        return game;
    }

    @Test
    public void keyOffTheCopyThrows() {
        final Game live = live();
        final GameCopier c = new GameCopier(live, true);
        c.setCopyStack(true);
        try {
            c.makeCopy();
            AssertJUnit.fail("expected the old stack-copy failure");
        } catch (IllegalStateException e) {
            AssertJUnit.assertTrue(e.getMessage(), e.getMessage().startsWith("stack copy: no ability"));
        }
    }

    @Test
    public void keyOnTheCopyKeepsTheEscapedSpell() {
        final Game live = live();
        EngineFixes.setCopyAltCostForTest(true);
        final GameCopier c = new GameCopier(live, true);
        c.setCopyStack(true);
        final Game copy = c.makeCopy();
        AssertJUnit.assertEquals(1, copy.getStack().size());
        final SpellAbility top = copy.getStack().peekAbility();
        AssertJUnit.assertEquals("Preordain", top.getHostCard().getName());
        AssertJUnit.assertTrue(top.isAlternativeCost(AlternativeCost.Escape));
    }
}
