package forge.ai.simulation;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.AiProfileUtil;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Lane steps-back-1016: the reverse-curriculum generator's reach check. A Doomsday position (puzzle P05: Doomsday and
 * Thassa's Oracle in hand, Jace, the Mind Sculptor and Brazen Borrower for devotion) is a proven win; a piece of its line
 * moved to the top of the library takes the immediate forced win away, and the reach search finds the step back by
 * engine play: Jace's 0 (probed DRAW + PUTBACK, a cantrip-dig) when Jace is there, our draw step otherwise.
 */
public class StepsBackReachTest extends AITest {

    static final String OPP = "aihand=\nailibrary=" + ForcedWinSolverTest.LIB20 + "\naigraveyard=\naiexile=\n"
            + "aibattlefield=Forest;Forest;Forest;Forest;Grizzly Bears\n";

    static String frame(String hand, String battlefield, String library) {
        return "[state]\nturn=7\nactiveplayer=human\nactivephase=MAIN1\nhumanlife=20\nailife=20\nhumanlandsplayed=0\n"
                + "ailandsplayed=0\n" + OPP + "humanhand=" + hand + "\nhumanbattlefield=" + battlefield
                + "\nhumangraveyard=\nhumanexile=\nhumanlibrary=" + library + "\n";
    }

    static final String BOARD = "Underground Sea;Underground Sea;Underground Sea;Island;Island";
    static final String ISLANDS = "Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;Island;Island";

    static StepsBackReach.Config cfg(int steps) {
        final StepsBackReach.Config c = new StepsBackReach.Config();
        c.maxSteps = steps;
        c.maxFixes = steps;
        c.maxOwnTurns = steps + 2;
        c.nodes = 12;
        c.budgetMs = 120_000;
        c.pieces.add("Doomsday");
        c.pieces.add("Thassa's Oracle");
        return c;
    }

    @Test
    public void theHooksAndTheSolverSeatAreOffByDefault() {
        Assert.assertNull(ForcedWinSolver.HOOKS);
        Assert.assertFalse(forge.bench.ForcedWinPolicyController.enabled());
    }

    @Test
    public void theProvenPositionIsDistanceZero() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Doomsday;Thassa's Oracle",
                "Jace, the Mind Sculptor|Counters:LOYALTY=3;Brazen Borrower;" + BOARD, ISLANDS));
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver.Proof p = new ForcedWinSolver(me, new ForcedWinSolver.Config()).prove(g);
        Assert.assertNotNull(p, "P05 is a proven win");
        Assert.assertTrue(p.stepHostNames.contains("Doomsday"), p.line);
    }

    @Test
    public void aPieceOnTopIsOneCantripDigAway() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Thassa's Oracle",
                "Jace, the Mind Sculptor|Counters:LOYALTY=3;Brazen Borrower;" + BOARD, "Doomsday;" + ISLANDS));
        final Player me = g.getPlayers().get(0);
        Assert.assertNull(new ForcedWinSolver(me, new ForcedWinSolver.Config()).prove(g),
                "the immediate forced win is gone: our library order is unknown to the solver");
        final StepsBackReach.Result r = StepsBackReach.search(g, me, cfg(1));
        Assert.assertTrue(r.reached, r.toString());
        Assert.assertEquals(r.fixes + r.passes, 1, r.toString());
        Assert.assertEquals(r.label, "cantrip-dig", r.toString());
    }

    @Test
    public void withoutACantripThePieceIsOneDrawStepAway() {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame("Thassa's Oracle",
                "Brazen Borrower;Brazen Borrower;" + BOARD, "Doomsday;" + ISLANDS));
        final Player me = g.getPlayers().get(0);
        Assert.assertNull(new ForcedWinSolver(me, new ForcedWinSolver.Config()).prove(g));
        // a one-step search with no pass allowed finds nothing; with the pass, our draw step brings Doomsday
        final StepsBackReach.Config none = cfg(1);
        none.maxOwnTurns = 1;
        Assert.assertFalse(StepsBackReach.search(g, me, none).reached);
        final StepsBackReach.Result r = StepsBackReach.search(g, me, cfg(1));
        Assert.assertTrue(r.reached, r.toString());
        Assert.assertEquals(r.passes, 1, r.toString());
        Assert.assertEquals(r.label, "draw-step", r.toString());
    }
}
