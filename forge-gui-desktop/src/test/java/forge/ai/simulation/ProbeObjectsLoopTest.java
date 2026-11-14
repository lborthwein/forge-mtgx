package forge.ai.simulation;

import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * sdcc-probeobj-loop-1114: with {@code probeObjects} on, the planLoops cast of a loop piece onto its partner in play
 * (an Aura that grants the copy ability) is still a line. Before, the cast went through the object branches, which
 * need an object the width model can express the outcome for, and the reach of that position fell from 1.0 to 0.0
 * ({@code LoopTest.auraOnThePartner} with the key on by default). Test data only.
 */
public class ProbeObjectsLoopTest extends AITest {
    private static double reach(final String battlefield, final String hand, final String library, final boolean objects) {
        final Map<String, String> p = new java.util.HashMap<>();
        for (String k : new String[] {"cycles", "engineLoopReach", "engineLongLoops", "engineLoopBisect", "engineLoopHoist",
                "engineMoves", "engineTriggerTargets", "turnLimits", "stackAbilityCopies", "stackHistory", "historyLki",
                "copyBackFace", "offBattlefieldLimits", "staleStatics", "stalePlayerStatics", "planExpandCap", "planLoops"}) {
            p.put(k, "true");
        }
        p.put("probeObjects", String.valueOf(objects));
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(p::get);
        c.sharedReader = false;
        c.honestProbes = false;
        final String frame = ForcedWinSolverTest.frame(hand, battlefield, 20, "", "", 20, "human")
                .replace("humanlibrary=" + ForcedWinSolverTest.OURLIB, "humanlibrary=" + library);
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = ForcedWinSolver.withReader(me, c, ForcedWinSolver.newSharedReader());
        final ForcedWinSolver.ReachOpts o = new ForcedWinSolver.ReachOpts();
        o.nodes = 4000;
        o.ms = -1L;
        o.nameOnePass = true;
        final ForcedWinSolver.Reach r = s.reach(g, s.knownTop(g), o);
        Assert.assertFalse(r.failed);
        return r.p;
    }

    @Test
    public void auraOnThePartnerWithObjectsOn() {
        final String lib = "Mountain;Mountain;Mountain;Mountain";
        for (boolean on : new boolean[] {false, true}) {
            Assert.assertEquals(reach("Mountain;Mountain;Mountain;Mountain;Pestermite", "Splinter Twin", lib, on), 1.0, 1e-9, "objects " + on);
            Assert.assertEquals(reach("Mountain;Mountain;Mountain;Mountain;Grizzly Bears", "Splinter Twin", lib, on), 0.0, 1e-9, "objects " + on);
            Assert.assertEquals(reach("Mountain;Mountain;Mountain;Pestermite", "Splinter Twin", lib, on), 1.0, 1e-9, "objects " + on);
        }
    }
}
