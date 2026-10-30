package forge.ai.simulation;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Labeller v2 item (d) (PREDECLARATION-labeller-v2.md §1 (d), §3 test 5): the solver's verification takes the MIN over the
 * opponent's response tree. Exact mode (their real hand, as the loss check uses it): seat "ai" has Lightning Bolt and its
 * own Counterspell to protect it; we are at 3 life.
 */
public class LabellerV2MinTest extends AITest {

    static ForcedWinSolver.Config cfg(final boolean oppMin, final int nodes) {
        final ForcedWinSolver.Config c = ForcedWinSolver.Config.fromProps(k -> switch (k) {
            case "budget" -> "counter";
            case "defense" -> "false";
            case "exact" -> "true";
            case "act" -> "false";
            case "engine" -> "false";
            case "horizon" -> "1";
            case "nodes" -> "3000";
            case "oppMin" -> oppMin ? "true" : null;
            case "oppNodes" -> String.valueOf(nodes);
            default -> null;
        });
        return c;
    }

    static boolean proven(final Game g, final ForcedWinSolver.Config c) {
        final Player ai = g.getPlayers().get(1);
        g.AI_TIMEOUT = 600;
        final ForcedWinSolver s = new ForcedWinSolver(ai, c);
        final List<ForcedWinSolver.Proposal> ps = s.propose(g);
        for (ForcedWinSolver.Proposal p : ps) {
            if (p.proven(1)) return true;
        }
        return false;
    }

    static Game board(final String ourHand, final String ourLands) {
        return ForcedWinSolverTest.game("Default", "Default", ForcedWinSolverTest.frame(ourHand, ourLands, 3,
                "Lightning Bolt;Counterspell", "Mountain;Island;Island", 20, "ai"));
    }

    @Test
    public void noResponseIsProvenEitherWay() {
        Assert.assertTrue(proven(board("", "Island;Island"), cfg(false, 64)));
        Assert.assertTrue(proven(board("", "Island;Island"), cfg(true, 64)));
    }

    @Test
    public void ourOneCounterAgainstTheirProtectionStaysProven() {
        Assert.assertTrue(proven(board("Counterspell", "Island;Island"), cfg(true, 64)),
                "their Counterspell answers ours: the Bolt resolves in every branch");
    }

    @Test
    public void twoCountersRefuteTheLineUnderMinOnly() {
        final boolean v1 = proven(board("Counterspell;Counterspell", "Island;Island;Island;Island"), cfg(false, 64));
        final boolean v2 = proven(board("Counterspell;Counterspell", "Island;Island;Island;Island"), cfg(true, 64));
        Assert.assertFalse(v2, "a second counter on their protection stops the Bolt");
        // v1 (one counter at one spell) is reported, not asserted: the point is that v2 is no weaker
        System.err.println("[labeller-v2] two counters: v1 proven=" + v1 + " v2 proven=" + v2);
    }

    @Test
    public void aTreeOverTheNodeCapIsNotWon() {
        Assert.assertFalse(proven(board("Counterspell", "Island;Island"), cfg(true, 1)),
                "the root play-out alone is the cap: the response branches are not run, so the world is not won");
    }

    @Test
    public void offIsTheV1Config() {
        final ForcedWinSolver.Config c = cfg(false, 64);
        Assert.assertFalse(c.oppMin);
        Assert.assertFalse(c.toString().contains("oppMin"));
        Assert.assertThrows(IllegalArgumentException.class, () -> ForcedWinSolver.Config.fromProps(k ->
                "oppMin".equals(k) ? "true" : "defense".equals(k) ? "false" : null)); // counts-only
    }

    static ForcedWinSolver.Config cfgEngine(final boolean oppMin) {
        return ForcedWinSolver.Config.fromProps(k -> switch (k) {
            case "budget" -> "counter";
            case "defense" -> "false";
            case "exact" -> "true";
            case "act" -> "false";
            case "horizon" -> "1";
            case "nodes" -> "3000";
            case "engineNodes" -> "200";
            case "engineSteps" -> "4000";
            case "oppMin" -> oppMin ? "true" : null;
            default -> null;
        });
    }

    /**
     * Predeclared test 5 (the v1 gap): a line v1 proves (its adversary only counters SPELLS with counterspells) that v2
     * does not: (i) their Rod of Ruin activation for our last life point, answered by our Stifle on the ability;
     * (ii) their pumped attacker, answered by our Lightning Bolt on it. At least one of the two must be proven by v1
     * for the test to mean anything; every case v1 proves, v2 must not.
     */
    @Test
    public void v2RefutesLinesV1ProvesAgainstAbilitiesAndRemoval() {
        final String[][] cases = {
            {"Stifle", "Island", "1", "", "Rod of Ruin;Mountain;Mountain;Mountain"},
            {"Lightning Bolt", "Mountain", "4", "Giant Growth", "Grizzly Bears;Forest;Forest"},
        };
        int v1Proofs = 0;
        final StringBuilder sb = new StringBuilder();
        for (String[] c : cases) {
            final java.util.function.Supplier<Game> g = () -> ForcedWinSolverTest.game("Default", "Default",
                    ForcedWinSolverTest.frame(c[0], c[1], Integer.parseInt(c[2]), c[3], c[4], 20, "ai"));
            final boolean v1 = proven(g.get(), cfgEngine(false)) || proven(g.get(), cfg(false, 64));
            final boolean v2 = proven(g.get(), cfgEngine(true)) || proven(g.get(), cfg(true, 64));
            sb.append(c[0]).append(": v1=").append(v1).append(" v2=").append(v2).append("; ");
            if (v1) {
                v1Proofs++;
                Assert.assertFalse(v2, "v2 must refute what only the counterspell adversary let through: " + sb);
            }
            // without our answer in hand both prove the line (the MIN is not a blanket refusal)
            final java.util.function.Supplier<Game> open = () -> ForcedWinSolverTest.game("Default", "Default",
                    ForcedWinSolverTest.frame("", c[1], Integer.parseInt(c[2]), c[3], c[4], 20, "ai"));
            if (v1) {
                Assert.assertTrue(proven(open.get(), cfgEngine(true)) || proven(open.get(), cfg(true, 64)),
                        "v2 proves the same line with no answer in our hand: " + sb);
            }
        }
        System.err.println("[labeller-v2] stricter cases: " + sb);
        Assert.assertTrue(v1Proofs >= 1, "no case v1 proves, the test says nothing: " + sb);
    }
}
