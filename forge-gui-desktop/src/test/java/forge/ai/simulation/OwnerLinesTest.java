package forge.ai.simulation;

import java.io.FileReader;
import java.util.List;

import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;

/**
 * solver-distill-cc-1109: positions from the owner's own winning games as TESTS of the solver (card facts are test data
 * only; nothing here reaches a pilot or a label). Each position is what he could see at the start of his main phase on
 * the turn he went for the kill. With the cheap pilot's solver config: does {@link ForcedWinSolver#propose} prove a kill
 * this turn, and does the planner ({@link ForcedWinSolver#setup}) see a kill within one more of our turns, at which
 * node allowance? Input: the JSON of the run folder's tools/ownerpos.py ({@code -Downer.positions=FILE}); without the
 * property the test does nothing.
 */
public class OwnerLinesTest extends forge.ai.AITest {

    private static String cut(final Object o, final int n) {
        final String s = String.valueOf(o).replaceAll("\\s+", " ");
        return s.substring(0, Math.min(n, s.length()));
    }

    @Test
    public void ownerPositions() throws Exception {
        final String path = System.getProperty("owner.positions");
        if (path == null || path.isEmpty()) {
            System.out.println("OWNERLINES skipped: no -Downer.positions");
            return;
        }
        final JsonObject root;
        try (FileReader r = new FileReader(path)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        final JsonObject solverCfg = root.getAsJsonObject("solverCfg").deepCopy();
        solverCfg.addProperty("act", "false");
        solverCfg.addProperty("defense", "false");
        final JsonArray ps = root.getAsJsonArray("positions");
        final int[][] budgets = "true".equals(System.getProperty("owner.quick")) ? new int[][] {{12000, 48000}}
                : new int[][] {{4000, 12000}, {12000, 48000}, {40000, 160000}};
        for (JsonElement e : ps) {
            final JsonObject o = e.getAsJsonObject();
            final String id = o.get("game").getAsString();
            final String frame = o.get("state").getAsString();
            System.out.println("OWNERLINES " + id + " win=" + o.get("win") + " go_turn=" + o.get("go_turn")
                    + " own_turns_after_go=" + o.get("own_turns_after_go") + " pile=" + o.get("pile")
                    + (o.has("he_did") ? " he_did=" + o.get("he_did") : ""));
            try {
                final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
                final Player me = g.getPlayers().get(0);
                final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(solverCfg));
                final long t0 = System.nanoTime();
                final List<ForcedWinSolver.Proposal> props = s.propose(g);
                ForcedWinSolver.Proposal best = null;
                for (ForcedWinSolver.Proposal p : props) {
                    if (best == null || p.pBelief > best.pBelief) {
                        best = p;
                    }
                }
                System.out.println("OWNERLINES " + id + " PROPOSE ms=" + (System.nanoTime() - t0) / 1_000_000 + " lines=" + props.size()
                        + (best == null ? " none" : " proven=" + best.proven(1) + " pUnopposed=" + best.pUnopposed + " pBelief=" + best.pBelief
                                + " steps=" + best.steps + " winTurn=" + best.winTurn + " how=" + best.how + " | " + cut(best.text, 260))
                        + " cap=" + s.lastCap() + " || " + s.summary());
            } catch (RuntimeException | Error ex) {
                System.out.println("OWNERLINES " + id + " PROPOSE failed: " + cut(ex, 200));
            }
            for (int[] b : budgets) {
                try {
                    final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
                    final Player me = g.getPlayers().get(0);
                    final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(solverCfg));
                    final ForcedWinSolver.GoldfishOpts go = new ForcedWinSolver.GoldfishOpts();
                    go.horizon = 3;
                    go.active = 3;
                    go.nodes = b[0];
                    go.maxNodes = b[1];
                    go.ms = -1L;
                    final long t0 = System.nanoTime();
                    final ForcedWinSolver.Proposal p = s.setup(g, s.knownTop(g), go);
                    System.out.println("OWNERLINES " + id + " SETUP nodes=" + b[0] + "/" + b[1] + " ms=" + (System.nanoTime() - t0) / 1_000_000
                            + (p == null ? " none" : " gf=" + p.gf + " turn+" + p.winTurn + " steps=" + p.steps + " | " + cut(p.text != null ? p.text : p.line, 260)));
                } catch (RuntimeException | Error ex) {
                    System.out.println("OWNERLINES " + id + " SETUP " + b[0] + " failed: " + cut(ex, 200));
                }
            }
        }
    }
}
