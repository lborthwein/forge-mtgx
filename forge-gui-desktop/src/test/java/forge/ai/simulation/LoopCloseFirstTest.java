package forge.ai.simulation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;

/**
 * sdcc-loopclose-1119 (lane solver-distill-cc): solver key engineLoopCloseFirst, on top of engineLoopLethalFirst (both
 * arms). The positions are {@link LoopLethalFirstTest#FRAME} variants (a recorded pilot frame), as a TEST of the engine
 * search. Card facts are test data only; nothing here reaches a pilot or a label.
 *
 * <p>The copier's copy has an enters trigger that can target any permanent and untaps it. Forge AI's own target for that
 * trigger is not the copier, and the engine's target variants list the copier after other tapped permanents; in a wide
 * position the search spends its budget under the earlier branches. With the key, the variant aimed at the move's own
 * host (the copier, tapped by the move) is searched first. A copy whose enters trigger is harmful (damage to target
 * creature) must not be led that way (the guard counts it), and no frame may lose a proven line.</p>
 */
public class LoopCloseFirstTest extends forge.ai.AITest {

    private static String with(final String from, final String to) {
        Assert.assertTrue(LoopLethalFirstTest.FRAME.contains(from), from);
        return LoopLethalFirstTest.FRAME.replace(from, to);
    }

    static JsonObject cfg(final boolean closeFirst) {
        final JsonObject o = LoopLethalFirstTest.fb(true);
        if (closeFirst) {
            o.addProperty("engineLoopCloseFirst", "true");
        }
        return o;
    }

    private static String cut(final Object o, final int n) {
        final String s = String.valueOf(o).replaceAll("\\s+", " ");
        return s.substring(0, Math.min(n, s.length()));
    }

    /** {proven: -1 no line, 0 lines none proven, 1 proven; closeFirst; closeGuarded}. */
    private static int[] propose(final String name, final String frame, final boolean on) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(cfg(on)));
        final long t0 = System.nanoTime();
        final List<ForcedWinSolver.Proposal> props = s.propose(g);
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        final boolean proven = best != null && best.proven(1);
        System.out.println("LOOPCLOSE " + name + " key=" + (on ? "on" : "off") + " ms=" + (System.nanoTime() - t0) / 1_000_000
                + " lines=" + props.size() + (best == null ? " none" : " proven=" + proven + " | " + cut(best.text, 1200))
                + " || " + s.engineStats.summary() + " || " + s.summary());
        return new int[] {best == null ? -1 : proven ? 1 : 0, s.engineStats.closeFirst, s.engineStats.closeGuarded};
    }

    @Test
    public void hostVariantFirst() {
        final String pest = with("Ancient Tomb;Pestermite;", "Ancient Tomb;Deceiver Exarch;")
                .replace("Deceiver Exarch|SummonSick", "Pestermite|SummonSick");
        final Map<String, String> frames = new LinkedHashMap<>();
        frames.put("pestermite", pest);
        frames.put("pestermiteNoRec", pest.replace("Karakas;Imperial Recruiter;", "Karakas;"));
        frames.put("recorded", LoopLethalFirstTest.FRAME);
        frames.put("norecruiter", with("Karakas;Imperial Recruiter;", "Karakas;"));
        // a copy whose enters trigger deals damage to target creature: the copier is a legal target, never led
        frames.put("harmful", with("Karakas;Imperial Recruiter;", "Karakas;Flametongue Kavu;"));
        final Map<String, int[][]> got = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : frames.entrySet()) {
            got.put(e.getKey(), new int[][] {propose(e.getKey(), e.getValue(), false), propose(e.getKey(), e.getValue(), true)});
        }
        final StringBuilder bad = new StringBuilder();
        check(bad, "pestermite: key on finds a line", got.get("pestermite")[1][0] >= 0);
        check(bad, "pestermiteNoRec: key on finds a line", got.get("pestermiteNoRec")[1][0] >= 0);
        check(bad, "pestermite: key on leads a host variant", got.get("pestermite")[1][1] > 0);
        check(bad, "harmful: the guard refused a host variant", got.get("harmful")[1][2] > 0);
        for (Map.Entry<String, int[][]> e : got.entrySet()) {
            check(bad, e.getKey() + ": key off leads nothing", e.getValue()[0][1] == 0 && e.getValue()[0][2] == 0);
            check(bad, e.getKey() + ": no proven line lost", e.getValue()[0][0] != 1 || e.getValue()[1][0] == 1);
            check(bad, e.getKey() + ": no found line lost", e.getValue()[0][0] < 0 || e.getValue()[1][0] >= 0);
        }
        Assert.assertEquals(bad.toString(), "", "engineLoopCloseFirst");
    }

    private static void check(final StringBuilder bad, final String what, final boolean ok) {
        System.out.println("LOOPCLOSE check " + (ok ? "ok   " : "FAIL ") + what);
        if (!ok) {
            bad.append(what).append("; ");
        }
    }
}
