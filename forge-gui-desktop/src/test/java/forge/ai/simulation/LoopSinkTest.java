package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane loopmacro-1118 (solverCfg engineLoopSink, read with engineLoopCredit). An untap loop that nets mana (the rock taps
 * for three, its untap costs two here) whose payoff is a big spell with no X: the credit's sink search (X above zero only)
 * found no kill, and the kill is the spell's attacks over our next turns. With the key: the move the loop made legal is
 * cast, then the pass and the attack. The cards are named here only (the pilot code reads none).
 */
public class LoopSinkTest extends forge.ai.AITest {
    private static final String BF = "Grim Monolith;Zirda, the Dawnwaker;Mountain;Mountain;Island";
    private static final String HAND = "Ulamog, the Infinite Gyre";

    private static JsonObject cfg(final int sink, final int engineMs) {
        final JsonObject o = new JsonObject();
        for (String k : new String[] {"copyBackFace", "cycles", "engineAfterPlanFail", "engineLongLoops", "engineLoopBisect",
                "engineLoopHoist", "engineLoopReach", "engineMoves", "engineNodesPerSearch", "engineOpen", "engineRerun",
                "engineTriggerTargets", "engineVerify", "followPay", "hideOwnLibrary", "historyLki", "landEntersProbe",
                "lineUncapped", "loopMarginal", "offBattlefieldLimits", "paySearch", "planDiscardWall", "planExpandCap",
                "probeSettle", "readerPerGame", "sourceColorsLive", "sourceLeaves", "sourceOutput", "stackAbilityCopies",
                "stackHistory", "stalePlayerStatics", "staleStatics", "turnLimits", "tutorCast", "unknownWorlds",
                "verifyReplan", "engineLoopCredit"}) {
            o.addProperty(k, "true");
        }
        o.addProperty("planOrder", "7");
        o.addProperty("planMs", "1500");
        o.addProperty("engineMs", String.valueOf(engineMs));
        o.addProperty("budgetMs", String.valueOf(engineMs + 4000));
        o.addProperty("evalReserveMs", "3000");
        o.addProperty("act", "false");
        o.addProperty("defense", "false");
        if (sink > 0) {
            o.addProperty("engineLoopSink", String.valueOf(sink));
        }
        return o;
    }

    private static ForcedWinSolver.Proposal best(final int oppLife, final int sink, final int engineMs) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default",
                ForcedWinSolverTest.frame(HAND, BF, 20, "", "Forest;Forest;Forest;Forest", oppLife, "human"));
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(cfg(sink, engineMs)));
        final List<ForcedWinSolver.Proposal> props = s.propose(g);
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        System.out.println("LOOPSINK life=" + oppLife + " sink=" + sink + " -> " + (best == null ? "none"
                : "proven=" + best.proven(1) + " winTurn=" + best.winTurn + " " + best.text) + " || " + s.summary());
        return best;
    }

    /** The key is off by default, parsed, and read only with engineLoopCredit. */
    @Test
    public void keyDefaultsOff() {
        Assert.assertEquals(ForcedWinSolver.Config.fromProps(k -> null).engineLoopSink, 0);
        Assert.assertEquals(SolverDriver.config(cfg(3, 1000)).engineLoopSink, 3);
        Assert.assertEquals(SolverDriver.config(cfg(0, 1000)).engineLoopSink, 0);
    }

    /** A line's turn count: as before unless the key is on and the line passes more than once. */
    @Test
    public void passTurnsCountsPassesOnlyWithTheKey() {
        final ForcedWinPlanner.Step atk = ForcedWinPlanner.Step.engine(ForcedWinModel.AbilKind.ATTACK, -1, "attack", "attack", "", false);
        final List<ForcedWinPlanner.Step> two = List.of(ForcedWinPlanner.Step.PASS, atk, ForcedWinPlanner.Step.PASS, atk);
        Assert.assertEquals(ForcedWinEngine.passTurns(two, false, 1), 1);
        Assert.assertEquals(ForcedWinEngine.passTurns(two, true, 1), 2);
        Assert.assertEquals(ForcedWinEngine.passTurns(List.of(ForcedWinPlanner.Step.PASS, atk), true, 1), 1);
    }

    /** One attack of the big spell kills (their life 10): cast after the loop, pass, attack. */
    @Test
    public void loopThenBigSpellThenOneAttack() {
        Assert.assertNull(best(10, 0, 8000), "credit alone: no line");
        final ForcedWinSolver.Proposal p = best(10, 2, 8000);
        Assert.assertNotNull(p, "a line");
        Assert.assertTrue(p.text.contains("REPEAT") && p.text.contains("Ulamog") && p.text.contains("ATTACK"), p.text);
        Assert.assertTrue(p.pUnopposed >= 1.0, "wins unopposed: " + p.text);
    }

    /** Their life 20: two attacks, so two passes; inside N = 3 own turns, not inside N = 2. */
    @Test
    public void twoAttacksNeedThreeTurns() {
        Assert.assertNull(best(20, 2, 8000), "N = 2: no line");
        final ForcedWinSolver.Proposal p = best(20, 3, 8000);
        Assert.assertNotNull(p, "a line");
        Assert.assertEquals(p.winTurn, 2, p.text);
        Assert.assertTrue(p.pUnopposed >= 1.0, "wins unopposed: " + p.text);
    }
}
