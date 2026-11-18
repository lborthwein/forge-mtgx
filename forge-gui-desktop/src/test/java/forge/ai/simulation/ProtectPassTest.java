package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-protect-1117 (solver keys {@code protectUntilNext}, {@code drawGrowth}; off by default): a permanent whose entering
 * gives our player protection from everything, and whose tap ability draws one more card each time, are learned as such
 * only by readers with the keys. Card names are test data only.
 */
public class ProtectPassTest extends AITest {

    static final String FRAME = ForcedWinSolverTest.frame("The One Ring;Teferi's Protection",
            "Island;Island;Island;Island;Island;Island;Island", 20, "", "Forest", 20, "human");

    @Test
    public void theProbesSeeTheProtectionAndTheGrowingDrawOnlyWithTheKeys() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", FRAME);
        final Player me = g.getPlayers().get(0);
        final ForcedWinModel.Reader off = new ForcedWinModel.Reader();
        final ACard ringOff = off.card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "The One Ring"));
        for (Abil a : ringOff.fromHand) {
            Assert.assertFalse(a.protectsPass, ringOff.fromHand.toString());
        }
        for (Abil a : ringOff.onBattlefield) {
            Assert.assertEquals(a.drawGrowth, 0, ringOff.onBattlefield.toString());
        }
        final ForcedWinModel.Reader on = new ForcedWinModel.Reader();
        on.protectPass = true;
        on.drawGrowth = true;
        final ACard ring = on.card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "The One Ring"));
        boolean prot = false, grows = false;
        for (Abil a : ring.fromHand) {
            prot |= a.kind == ForcedWinModel.AbilKind.SPELL && a.protectsPass;
        }
        for (Abil a : ring.onBattlefield) {
            grows |= a.has(EffKind.DRAW) && a.drawGrowth == 1;
        }
        Assert.assertTrue(prot, ring.fromHand.toString());
        Assert.assertTrue(grows, ring.onBattlefield.toString());
        final ACard tp = on.card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Teferi's Protection"));
        boolean tprot = false;
        for (Abil a : tp.fromHand) {
            tprot |= a.protectsPass;
        }
        Assert.assertTrue(tprot, "an instant giving the same protection: " + tp.fromHand);
        Assert.assertEquals(me.getCardsIn(ZoneType.Hand).size(), 2, "the sandbox never touches the live game");
    }
}
