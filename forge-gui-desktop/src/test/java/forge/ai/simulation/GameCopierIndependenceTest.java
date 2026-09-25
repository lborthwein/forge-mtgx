package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartWithList;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * A copied game must not share mutable payment state with its original: look-ahead play-outs of one live game run
 * on several threads, and two play-outs sacrificing the same copied Treasure/Clue paid into one shared list
 * (ConcurrentModificationException in CostPartWithList.reportPaidCardsTo). GameCopier copies a token's abilities
 * by cloning the live token's, so this is where the sharing came from.
 */
public class GameCopierIndependenceTest extends SimulationTest {

    private static List<CostPartWithList> listParts(Card c) {
        List<CostPartWithList> out = new ArrayList<>();
        for (SpellAbility sa : c.getAllSpellAbilities()) {
            if (sa.getPayCosts() == null) {
                continue;
            }
            for (CostPart p : sa.getPayCosts().getCostParts()) {
                if (p instanceof CostPartWithList l) {
                    out.add(l);
                }
            }
        }
        return out;
    }

    @Test
    public void copiedTokenCostsHaveTheirOwnPaidLists() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Card clue = addToken("c_a_clue_draw", p);
        game.getAction().checkStateEffects(true);

        List<CostPartWithList> live = listParts(clue);
        AssertJUnit.assertFalse("the Clue's draw ability should have a sacrifice cost", live.isEmpty());

        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        Card copied = (Card) copier.find(clue);
        AssertJUnit.assertNotNull(copied);
        List<CostPartWithList> copiedParts = listParts(copied);
        AssertJUnit.assertEquals(live.size(), copiedParts.size());
        for (CostPartWithList c : copiedParts) {
            for (CostPartWithList l : live) {
                AssertJUnit.assertNotSame("a copied cost part must not share its original's cost part", l, c);
                AssertJUnit.assertNotSame("a copied cost part must not share its original's paid-card list",
                        l.getCardList(), c.getCardList());
                AssertJUnit.assertNotSame("a copied cost part must not share its original's LKI list",
                        l.getLKIList(), c.getLKIList());
            }
        }
        AssertJUnit.assertNotSame(game, copy);
    }

    @Test
    public void forgesOwnCopiesOutsideGameCopierAreUnchanged() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card clue = addToken("c_a_clue_draw", p);
        for (SpellAbility sa : clue.getAllSpellAbilities()) {
            if (sa.getPayCosts() == null) {
                continue;
            }
            List<CostPart> parts = sa.getPayCosts().getCostParts();
            for (CostPart part : parts) {
                if (part instanceof CostPartWithList l) {
                    // outside GameCopier, CostPart.copy() keeps Forge's clone() behaviour (shared lists)
                    CostPartWithList c = (CostPartWithList) l.copy();
                    AssertJUnit.assertSame(l.getCardList(), c.getCardList());
                }
            }
        }
    }
}
