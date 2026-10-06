package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * MX1 OOM voids (lane forge-spellhand-loop-1006): the look-ahead seat (deck ap06) cast Yawgmoth's Will. Its effect card
 * is a static ("you may play cards from your graveyard") plus a replacement ("if a card would be put into your graveyard
 * from anywhere, exile it instead"). GameCopier rebuilt effect cards with their statics only, so in every play-out Black
 * Lotus went to the graveyard after use and was cast again, +3 mana each time, until the play-out hit maxSteps or the
 * heap ran out. A copied effect card must keep its replacement effects (and triggers).
 */
public class GameCopierEffectReplacementTest extends SimulationTest {

    private static Card effectIn(Player p, String prefix) {
        for (Card c : p.getCardsIn(ZoneType.Command)) {
            if (c.getName().startsWith(prefix)) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void copiedYawgmothsWillEffectStillExilesInsteadOfGraveyard() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        addCards("Swamp", 3, p);
        Card will = addCardToZone("Yawgmoth's Will", p, ZoneType.Hand);
        addCardToZone("Black Lotus", p, ZoneType.Graveyard);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = will.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        AbilityUtils.resolve(sa);
        game.getAction().checkStateEffects(true);

        Card eff = effectIn(p, "Yawgmoth's Will");
        AssertJUnit.assertNotNull("Yawgmoth's Will made its effect card", eff);
        AssertJUnit.assertEquals(1, eff.getStaticAbilities().size());
        AssertJUnit.assertEquals(1, eff.getReplacementEffects().size());

        GameCopier copier = new GameCopier(game);
        Game copy = copier.makeCopy();
        Player cp = (Player) copier.find(p);
        Card ceff = effectIn(cp, "Yawgmoth's Will");
        AssertJUnit.assertNotNull("the copy has the effect card", ceff);
        AssertJUnit.assertEquals("the copied effect keeps its static", 1, ceff.getStaticAbilities().size());
        AssertJUnit.assertEquals("the copied effect keeps its replacement", 1, ceff.getReplacementEffects().size());

        // The rule itself, in the copy: Black Lotus put onto the battlefield, then into the graveyard, is exiled instead.
        Card lotus = null;
        for (Card c : cp.getCardsIn(ZoneType.Graveyard)) {
            if (c.getName().equals("Black Lotus")) {
                lotus = c;
            }
        }
        AssertJUnit.assertNotNull(lotus);
        Card onBf = copy.getAction().moveToPlay(lotus, cp, null, null);
        copy.getAction().checkStateEffects(true);
        copy.getAction().moveToGraveyard(onBf, null);
        boolean inExile = false, inGrave = false;
        for (Card c : cp.getCardsIn(ZoneType.Exile)) {
            inExile |= c.getName().equals("Black Lotus");
        }
        for (Card c : cp.getCardsIn(ZoneType.Graveyard)) {
            inGrave |= c.getName().equals("Black Lotus");
        }
        AssertJUnit.assertTrue("in the copy, Black Lotus is exiled instead of going to the graveyard", inExile && !inGrave);
    }
}
