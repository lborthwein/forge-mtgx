package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-integ-1110 (card facts are test data only): the planner-fix keys together. probeSettle and sourceLeaves
 * both observe an activated ability that puts its own source into our library; with both on the move is modelled once
 * (the SELF_TO_LIBRARY effect owns it). With probeSettle and tutorCast both on, what a permanent does when it is
 * sacrificed to another card's cost is observed.
 */
public class IntegKeysTest extends AITest {

    private static Game game() {
        return ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", TutorCastTest.frame("Beseech the Mirror",
                "Swamp;Swamp;Swamp;Swamp;Sensei's Divining Top;Chromatic Star", 20,
                "Island;Island;Island;Island;Island;Island;Island"));
    }

    private static ACard learn(final Game g, final String name, final boolean settle, final boolean leaves, final boolean cast) {
        final Player me = g.getPlayers().get(0);
        final Card c = ForcedWinSolverTest.inZone(me, ZoneType.Battlefield, name);
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.probeSettle = settle;
        r.sourceLeaves = leaves;
        r.tutorCast = cast;
        final ACard t = r.card(g, me, c);
        System.out.println("INTEGKEYS settle=" + settle + " leaves=" + leaves + " cast=" + cast + " " + name + " bf="
                + t.onBattlefield + " dies=" + t.dies);
        Assert.assertFalse(ForcedWinProbe.PROBE_SETTLE.get() || ForcedWinProbe.SOURCE_LEAVES.get() || ForcedWinProbe.TUTOR_CAST.get());
        return t;
    }

    private static int count(final Abil a, final EffKind k) {
        int n = 0;
        for (Eff e : a.effects) {
            n += e.kind == k ? 1 : 0;
        }
        return n;
    }

    @Test
    public void aSourceThatReturnsToTheLibraryIsModelledOnceWhateverKeysAreOn() {
        final Game g = game();
        final String top = "Sensei's Divining Top";
        int byEffect = 0, byField = 0;
        // each key alone: its own mechanism
        for (Abil a : learn(g, top, true, false, false).onBattlefield) {
            Assert.assertEquals(a.selfTo, 0, a.toString());
            byEffect += count(a, EffKind.SELF_TO_LIBRARY);
        }
        Assert.assertEquals(byEffect, 1);
        for (Abil a : learn(g, top, false, true, false).onBattlefield) {
            Assert.assertEquals(count(a, EffKind.SELF_TO_LIBRARY), 0, a.toString());
            byField += a.selfTo == Abil.SELF_TOP ? 1 : 0;
        }
        Assert.assertEquals(byField, 1);
        // both (and all three): the effect owns the move, the field stays 0
        for (boolean cast : new boolean[] {false, true}) {
            int effects = 0;
            for (Abil a : learn(g, top, true, true, cast).onBattlefield) {
                Assert.assertEquals(a.selfTo, 0, a.toString());
                effects += count(a, EffKind.SELF_TO_LIBRARY);
                if (a.has(EffKind.SELF_TO_LIBRARY)) {
                    Assert.assertEquals(count(a, EffKind.DRAW), 1, a.toString());
                    Assert.assertEquals(a.effects.get(a.effects.size() - 1).n, ForcedWinModel.LIB_TOP, a.toString());
                }
            }
            Assert.assertEquals(effects, 1);
        }
        // no key: neither
        for (Abil a : learn(g, top, false, false, false).onBattlefield) {
            Assert.assertEquals(a.selfTo, 0, a.toString());
            Assert.assertEquals(count(a, EffKind.SELF_TO_LIBRARY), 0, a.toString());
        }
    }

    @Test
    public void aPermanentSacrificedToAnotherCardsCostDrawsOnlyWithBothKeys() {
        final Game g = game();
        final String star = "Chromatic Star";
        Assert.assertTrue(learn(g, star, true, false, false).dies.isEmpty());
        Assert.assertTrue(learn(g, star, false, false, true).dies.isEmpty());
        final ACard both = learn(g, star, true, true, true);
        Assert.assertEquals(both.dies.size(), 1, both.dies.toString());
        Assert.assertEquals(both.dies.get(0).kind, EffKind.DRAW);
        Assert.assertEquals(both.dies.get(0).n, 1);
        // its own "sacrifice this:" ability holds the draw once (its settled run), not twice
        for (Abil a : both.onBattlefield) {
            if (a.sacSelf) {
                int draws = 0;
                for (Eff e : a.effects) {
                    draws += e.kind == EffKind.DRAW ? e.n : 0;
                }
                Assert.assertEquals(draws, 1, a.toString());
            }
        }
    }
}
