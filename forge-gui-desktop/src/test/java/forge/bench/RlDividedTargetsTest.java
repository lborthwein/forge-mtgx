package forge.bench;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.SpellApiToAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/**
 * Divided-as-you-choose targets for an RL seat (lane rl-r0-b4b-1006). In 1,000 r1-bank train games every refused
 * TARGETS frame was one of two shapes:
 * <ul>
 * <li>a trigger Forge's AI prepared (Inferno Titan 66, Fury 45, The Grand Evolution 8): the AI never sets the
 * amount to divide, and the seat's retargeting reset the targets without computing it, so the answer was refused "no
 * total to divide". The seat now clears targets the way Forge does ({@code clearTargets}), which computes it;</li>
 * <li>Fire Covenant with X = 2 or 0: the seat could pick more targets than the amount ("cannot divide 2 among 4
 * targets"). The seat's maximum is now capped at the amount, since every target gets at least one (CR 601.2d).</li>
 * </ul>
 */
public class RlDividedTargetsTest extends AITest {

    private static SpellAbility trigger(final Card c, final Player p) {
        for (Trigger t : c.getTriggers()) {
            final SpellAbility sa = t.ensureAbility();
            if (sa != null && sa.usesTargeting() && sa.isDividedAsYouChoose()) {
                sa.setActivatingPlayer(p);
                return sa;
            }
        }
        throw new AssertionError("no divided trigger on " + c);
    }

    private void aiPreparedTriggerGetsItsTotal(final String name, final int amount) {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        addCards("Grizzly Bears", 3, opp);
        final Card host = addCard(name, p);
        final SpellAbility sa = trigger(host, p);
        Assert.assertTrue(SpellApiToAi.Converter.get(sa).doTrigger(p, sa, true), "Forge's AI prepares the trigger");
        Assert.assertNull(sa.getDividedValue(), "the corpus shape: Forge's AI leaves no total on the trigger");
        PlayerControllerBridge.clearForSeat(sa);
        Assert.assertEquals(sa.getTargets().size(), 0);
        Assert.assertEquals(sa.getDividedValue(), Integer.valueOf(amount), "the seat's ask has a total to divide");
        Assert.assertEquals(PlayerControllerBridge.dividedTargetCap0(sa, sa.getMaxTargets()),
                Math.min(sa.getMaxTargets(), amount));
    }

    @Test
    public void infernoTitanTriggerHasATotal() {
        aiPreparedTriggerGetsItsTotal("Inferno Titan", 3);
    }

    @Test
    public void furyTriggerHasATotal() {
        aiPreparedTriggerGetsItsTotal("Fury", 4);
    }

    @Test
    public void fireCovenantTargetsCappedAtX() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        addCards("Grizzly Bears", 4, opp);
        final Card spell = addCardToZone("Fire Covenant", p, ZoneType.Hand);
        final SpellAbility sa = spell.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        sa.setXManaCostPaid(2);
        sa.clearTargets();
        Assert.assertEquals(sa.getMaxTargets(), 4, "any number of target creatures");
        Assert.assertEquals(PlayerControllerBridge.dividedTargetCap0(sa, sa.getMaxTargets()), 2,
                "two damage: at most two targets");
        sa.setXManaCostPaid(0);
        sa.clearTargets();
        Assert.assertEquals(PlayerControllerBridge.dividedTargetCap0(sa, sa.getMaxTargets()), 0,
                "no damage: no target");
    }

    @Test
    public void undividedAbilityKeepsItsMaximum() {
        final Game game = initAndCreateGame();
        final Player p = game.getPlayers().get(1);
        final Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        final SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        sa.clearTargets();
        Assert.assertEquals(PlayerControllerBridge.dividedTargetCap0(sa, 1), 1);
    }
}
