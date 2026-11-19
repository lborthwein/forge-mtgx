package forge.ai.simulation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.ai.simulation.ForcedWinModel.ACard;
import forge.ai.simulation.ForcedWinModel.Abil;
import forge.ai.simulation.ForcedWinModel.Eff;
import forge.ai.simulation.ForcedWinModel.EffKind;
import forge.ai.simulation.ForcedWinPlanner.Line;
import forge.ai.simulation.ForcedWinPlanner.Step;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-sacmana-1117 (solver key {@code grantSacMana}; off by default): a spell that gives our lands "sacrifice this: add
 * one mana" until end of turn is learned as such, and the plan sacrifices lands (already tapped) for the mana it lacks.
 * Card names are test data only.
 */
public class SacManaTest extends AITest {

    static ForcedWinSolver.Config cfg(final boolean on) {
        final Map<String, String> kv = new HashMap<>();
        kv.put("grantSacMana", on ? "true" : "false");
        kv.put("planOrder", "7");
        kv.put("hideOwnLibrary", "true");
        kv.put("horizon", "1");
        kv.put("engine", "false"); // the width search and its verification only
        kv.put("defense", "false");
        kv.put("act", "false");
        kv.put("budgetMs", "60000");
        kv.put("planMs", "20000");
        return ForcedWinSolver.Config.fromProps(kv::get);
    }

    /**
     * Two black and three blue lands, the land drop used: the pile spell (BBB) is castable only with a land sacrificed for
     * black; then a draw-three and the creature that wins on a small library.
     */
    static final String KILL = ForcedWinSolverTest.frame("Rain of Filth;Doomsday;Ancestral Recall;Thassa's Oracle",
            "Swamp;Swamp;Island;Island;Island", 20, "", "Forest", 20, "human").replace("humanlandsplayed=0", "humanlandsplayed=1");

    private static Eff eff(final Abil a, final EffKind k) {
        for (Eff e : a.effects) {
            if (e.kind == k) {
                return e;
            }
        }
        return null;
    }

    @Test
    public void theProbeSeesTheGrantedSacrificeManaOnlyWithTheKey() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", KILL);
        final Player me = g.getPlayers().get(0);
        final ACard off = new ForcedWinModel.Reader().card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Rain of Filth"));
        for (Abil a : off.fromHand) {
            Assert.assertNull(eff(a, EffKind.GRANT_SAC_MANA), off.fromHand.toString());
        }
        final ForcedWinModel.Reader r = new ForcedWinModel.Reader();
        r.grantSacMana = true;
        final ACard on = r.card(g, me, ForcedWinSolverTest.inZone(me, ZoneType.Hand, "Rain of Filth"));
        Eff grant = null;
        for (Abil a : on.fromHand) {
            grant = grant != null ? grant : eff(a, EffKind.GRANT_SAC_MANA);
        }
        Assert.assertNotNull(grant, on.fromHand.toString());
        Assert.assertEquals(grant.n, 1, "lands only");
        Assert.assertEquals(grant.colors, 1 << ForcedWinModel.B, "black mana");
    }

    private static ForcedWinSolver.Proposal best(final List<ForcedWinSolver.Proposal> props) {
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        return best;
    }

    @Test
    public void thePlanSacrificesTappedLandsForThePile() {
        final Game g0 = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", KILL);
        final ForcedWinSolver off = new ForcedWinSolver(g0.getPlayers().get(0), cfg(false));
        final ForcedWinSolver.Proposal pOff = best(off.propose(g0));
        Assert.assertTrue(pOff == null || !pOff.proven(1), "key off: no proven line: " + (pOff == null ? off.summary() : pOff.text));
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", KILL);
        final ForcedWinSolver on = new ForcedWinSolver(g.getPlayers().get(0), cfg(true));
        final ForcedWinSolver.Proposal p = best(on.propose(g));
        Assert.assertNotNull(p, "key on: a line: " + on.summary());
        Assert.assertTrue(p.proven(1), p.text);
        boolean rain = false;
        for (Step st : ((Line) p.line).steps) {
            rain |= "Rain of Filth".equals(st.hostName);
        }
        Assert.assertTrue(rain, p.text);
    }
}
