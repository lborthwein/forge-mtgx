package forge.ai.simulation;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * solver-distill-cc-1109 (planLoops): the screens count a loop the engine found in a sandbox. The frames are card facts
 * used as tests only (Kiki-Jiki, Mirror Breaker copies a creature with haste; Pestermite untaps a permanent when it
 * enters, so each copy untaps Kiki-Jiki; Grizzly Bears does nothing on entering).
 */
public class LoopTest extends forge.ai.AITest {

    static final String KIKI = "Kiki-Jiki, Mirror Breaker";
    static final String LANDS = "Mountain;Mountain;Mountain;Mountain;Mountain";

    private static ForcedWinSolver solver(final Player me, final boolean loops) {
        final Map<String, String> p = new java.util.HashMap<>();
        for (String k : new String[] {"cycles", "engineLoopReach", "engineLongLoops", "engineLoopBisect", "engineLoopHoist",
                "engineMoves", "engineTriggerTargets", "turnLimits", "stackAbilityCopies", "stackHistory", "historyLki",
                "copyBackFace", "offBattlefieldLimits", "staleStatics", "stalePlayerStatics", "planExpandCap"}) {
            p.put(k, "true");
        }
        p.put("planLoops", String.valueOf(loops));
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(p::get);
        c.sharedReader = false;
        c.honestProbes = false;
        return ForcedWinSolver.withReader(me, c, ForcedWinSolver.newSharedReader());
    }

    private static ForcedWinSolver.Reach reach(final String battlefield, final String hand, final String library,
            final boolean loops) {
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(me, loops);
        if (loops) {
            // the models the reader learned for our hand and battlefield (diagnostics)
            for (forge.game.zone.ZoneType z : new forge.game.zone.ZoneType[] {forge.game.zone.ZoneType.Hand, forge.game.zone.ZoneType.Battlefield}) {
                for (forge.game.card.Card c : me.getCardsIn(z)) {
                    if (!c.isLand()) {
                        final ForcedWinModel.ACard a = s.readerForTest().card(g, me, c);
                        System.out.println("LOOP model " + a.name + " loopWith=" + a.loopWith + " fromHand=" + a.loopFromHand + " haste=" + a.loopHaste
                                + " note=" + a.note + " hand=" + a.fromHand + " bf=" + a.onBattlefield);
                    }
                }
            }
        }
        final ForcedWinSolver.ReachOpts o = new ForcedWinSolver.ReachOpts();
        o.nodes = 4000;
        o.ms = -1L;
        o.nameOnePass = true;
        final ForcedWinSolver.Reach r = s.reach(g, s.knownTop(g), o);
        System.out.println("LOOP loops=" + loops + " bf=" + battlefield + " hand=" + hand + " lib=" + library.substring(0, Math.min(30, library.length()))
                + " -> p=" + r.p + " direct=" + r.direct + " failed=" + r.failed);
        Assert.assertFalse(r.failed);
        return r;
    }

    @Test
    public void bothInPlayIsALine() {
        final String lib = "Mountain;Mountain;Mountain;Mountain";
        Assert.assertEquals(reach(LANDS + ";" + KIKI + ";Pestermite", "", lib, true).p, 1.0, 1e-9);
        Assert.assertEquals(reach(LANDS + ";" + KIKI + ";Pestermite", "", lib, false).p, 0.0, 1e-9, "off: the width model has no loop");
        Assert.assertEquals(reach(LANDS + ";" + KIKI + ";Grizzly Bears", "", lib, true).p, 0.0, 1e-9, "a creature that untaps nothing");
    }

    @Test
    public void castTheLoopPieceThisTurn() {
        // Kiki-Jiki has haste: cast with the partner in play, it taps the turn it enters
        Assert.assertEquals(reach(LANDS + ";Pestermite", KIKI, "Mountain;Mountain;Mountain;Mountain", true).p, 1.0, 1e-9);
    }

    /** The sandbox the probe runs: the Aura in hand with mana, the partner in play, the engine's loop options, 300 nodes. */
    @Test
    public void auraSandboxFindsThePair() {
        final String frame = ForcedWinSolverTest.frame("Splinter Twin", "Mountain;Mountain;Mountain;Mountain;Pestermite", 20, "", "", 20, "human");
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = solver(me, false);
        int twin = -1, mite = -1;
        for (forge.game.card.Card c : me.getCardsIn(forge.game.zone.ZoneType.Hand)) {
            twin = c.getId();
        }
        for (forge.game.card.Card c : me.getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
            if (c.isCreature()) {
                mite = c.getId();
            }
        }
        Assert.assertTrue(ForcedWinProbe.sandboxWin(g, me, twin, mite, true, ForcedWinSolver.loopLimits(s.config()), 7L, ForcedWinSolver.LOOP_NANOS));
        Assert.assertFalse(ForcedWinProbe.sandboxWin(g, me, twin, -1, true, ForcedWinSolver.loopLimits(s.config()), 7L, ForcedWinSolver.LOOP_NANOS), "the Aura alone");
    }

    /** An Aura that grants the copy ability: cast on the partner already in play (it can tap), the same loop. */
    @Test
    public void auraOnThePartner() {
        final String lib = "Mountain;Mountain;Mountain;Mountain";
        Assert.assertEquals(reach("Mountain;Mountain;Mountain;Mountain;Pestermite", "Splinter Twin", lib, true).p, 1.0, 1e-9);
        Assert.assertEquals(reach("Mountain;Mountain;Mountain;Mountain;Grizzly Bears", "Splinter Twin", lib, true).p, 0.0, 1e-9);
        Assert.assertEquals(reach("Mountain;Mountain;Mountain;Pestermite", "Splinter Twin", lib, true).p, 1.0, 1e-9, "three lands: the fourth is the next draw");
        Assert.assertEquals(reach("Mountain;Mountain;Mountain;Pestermite", "Splinter Twin", "Grizzly Bears;Grizzly Bears", true).p, 0.0, 1e-9, "three lands and no fourth to draw");
    }

    @Test
    public void reachOverTheDraw() {
        // the partner in play, five lands, Kiki-Jiki one of two unknown library cards: half the draws make the line
        Assert.assertEquals(reach(LANDS + ";Pestermite", "", KIKI + ";Mountain", true).p, 0.5, 1e-9);
        Assert.assertEquals(reach(LANDS + ";Pestermite", "", KIKI + ";Mountain", false).p, 0.0, 1e-9);
    }
}
