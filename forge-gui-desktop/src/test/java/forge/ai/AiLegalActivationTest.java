package forge.ai;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * mtgx fork (lane rl-r0-b4b-1006): an activation whose targets break their own count is neither chosen by the AI nor
 * paid for ({@link AiLegalActivation}). The shapes are W's corpus refusals: Garruk Wildspeaker and Koth of the Hammer
 * +1 activated "for the cost" in main 2 without targets (Koth: refused 999 times in one turn, loyalty 3 to 1001), and
 * Tear Asunder with a target left on its kicker-only sub-ability.
 */
public class AiLegalActivationTest extends AITest {

    @AfterMethod
    public void restore() {
        AiLegalActivation.setEnabled(true);
    }

    private static SpellAbility plusOne(final Card pw, final Player p) {
        for (SpellAbility sa : pw.getSpellAbilities()) {
            if (sa.isPwAbility() && sa.usesTargeting()) {
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        throw new AssertionError("no targeted loyalty ability on " + pw);
    }

    private static AiPlayDecision aiDecides(final Player p, final SpellAbility sa) {
        return ((PlayerControllerAi) p.getController()).getAi().canPlaySa(sa);
    }

    private Card planeswalker(final Game game, final Player p, final String name, final String land, final int lands) {
        addCards(land, lands, p);
        final Card pw = addCard(name, p);
        pw.addCounterInternal(CounterEnumType.LOYALTY, 3, p, false, null, null);
        moveToMain2(game, p);
        return pw;
    }

    @Test
    public void garrukPlusOneForTheCostNowHasLegalTargets() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final SpellAbility sa = plusOne(planeswalker(game, p, "Garruk Wildspeaker", "Forest", 3), p);
        Assert.assertEquals(aiDecides(p, sa), AiPlayDecision.WillPlay, "the AI still activates +1 for the loyalty");
        Assert.assertEquals(sa.getTargets().size(), 2, "two target lands, as the ability requires");
        Assert.assertTrue(sa.isTargetNumberValid());
        Assert.assertTrue(game.getStack().hasLegalTargeting(sa), "the stack accepts it");
    }

    @Test
    public void kothPlusOneForTheCostNowHasALegalTarget() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final SpellAbility sa = plusOne(planeswalker(game, p, "Koth of the Hammer", "Mountain", 2), p);
        Assert.assertEquals(aiDecides(p, sa), AiPlayDecision.WillPlay);
        Assert.assertEquals(sa.getTargets().size(), 1);
        Assert.assertTrue(game.getStack().hasLegalTargeting(sa));
    }

    @Test
    public void fixOffReproducesTheIllegalChoice() {
        AiLegalActivation.setEnabled(false);
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final SpellAbility sa = plusOne(planeswalker(game, p, "Garruk Wildspeaker", "Forest", 3), p);
        Assert.assertEquals(aiDecides(p, sa), AiPlayDecision.WillPlay);
        Assert.assertEquals(sa.getTargets().size(), 0, "upstream: chosen with no targets");
        Assert.assertFalse(game.getStack().hasLegalTargeting(sa));
    }

    @Test
    public void refusedActivationIsReversedCostsIncluded() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card garruk = planeswalker(game, p, "Garruk Wildspeaker", "Forest", 3);
        final SpellAbility sa = plusOne(garruk, p);
        // an activation with no targets reaching the play path (what the AI used to choose)
        Assert.assertFalse(ComputerUtil.handlePlayingSpellAbility(p, sa, null), "not played");
        Assert.assertEquals(garruk.getCounters(CounterEnumType.LOYALTY), 3, "the +1 is refunded");
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertEquals(garruk.getPlaneswalkerAbilityActivated(), 0);
    }

    @Test
    public void fixOffKeepsThePaidCost() {
        AiLegalActivation.setEnabled(false);
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card garruk = planeswalker(game, p, "Garruk Wildspeaker", "Forest", 3);
        final SpellAbility sa = plusOne(garruk, p);
        ComputerUtil.handlePlayingSpellAbility(p, sa, null);
        Assert.assertTrue(game.getStack().isEmpty(), "the stack refused it");
        Assert.assertEquals(garruk.getCounters(CounterEnumType.LOYALTY), 4, "upstream: the paid +1 stays (the defect)");
        Assert.assertEquals(garruk.getPlaneswalkerAbilityActivated(), 0, "and the activation is not counted");
    }

    @Test
    public void strayTargetOnKickerOnlyAbilityIsDropped() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card ring = addCard("Sol Ring", opp);
        final Card bears = addCard("Grizzly Bears", opp);
        final Card spell = addCardToZone("Tear Asunder", p, ZoneType.Hand);
        final SpellAbility root = spell.getFirstSpellAbility();
        root.setActivatingPlayer(p);
        root.getTargets().add(ring);
        root.getSubAbility().getTargets().add(bears);
        Assert.assertFalse(game.getStack().hasLegalTargeting(root), "the corpus shape: refused");
        Assert.assertTrue(AiLegalActivation.legalTargeting(game, root), "legal once the stray target is dropped");
        Assert.assertEquals(root.getTargets().size(), 1, "the unkicked target stays");
        Assert.assertEquals(root.getSubAbility().getTargets().size(), 0);
    }

    @Test
    public void legalActivationIsUntouched() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card ring = addCard("Sol Ring", opp);
        final Card spell = addCardToZone("Tear Asunder", p, ZoneType.Hand);
        final SpellAbility root = spell.getFirstSpellAbility();
        root.setActivatingPlayer(p);
        root.getTargets().add(ring);
        Assert.assertTrue(AiLegalActivation.legalTargeting(game, root));
        Assert.assertEquals(root.getTargets().size(), 1);
    }
}
