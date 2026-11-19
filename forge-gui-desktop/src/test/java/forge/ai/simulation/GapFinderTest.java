package forge.ai.simulation;

import java.io.FileReader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * sdcc-gapfind-1117 (diagnostics, opt-in: -Dgap.positions=FILE in the OwnerLinesTest format; nothing without it). For each
 * position: (b) the solver's propose with the file's solverCfg (the live path); when (b) proves nothing, (a) the same
 * solver with the width model off (nodes 1) and the engine search at a large budget (-Dgap.engineMs, default 20000):
 * an honest search (own library order hidden, the opponent's hidden cards hidden or resampled, as the live path). A
 * position (a) proves and (b) does not is a GAP row, with the engine line and, for every card the line uses, how the
 * width model reads it (unmodeled flag, abilities and effects).
 */
public class GapFinderTest extends forge.ai.AITest {

    private static ForcedWinSolver.Proposal best(final List<ForcedWinSolver.Proposal> props) {
        ForcedWinSolver.Proposal best = null;
        for (ForcedWinSolver.Proposal p : props) {
            if (best == null || p.pBelief > best.pBelief) {
                best = p;
            }
        }
        return best;
    }

    private static String flat(final Object o) {
        return String.valueOf(o).replaceAll("\\s+", " ");
    }

    @Test
    public void gaps() throws Exception {
        final String path = System.getProperty("gap.positions");
        if (path == null || path.isEmpty()) {
            return;
        }
        final JsonObject root;
        try (FileReader r = new FileReader(path)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        final JsonObject cfgB = root.getAsJsonObject("solverCfg").deepCopy();
        cfgB.addProperty("act", "false");
        cfgB.addProperty("defense", "false");
        final int nodes = cfgB.has("nodes") ? cfgB.get("nodes").getAsInt() : new ForcedWinSolver.Config().nodes;
        final int engineMs = cfgB.has("engineMs") ? cfgB.get("engineMs").getAsInt() : 4000;
        final int budgetMs = cfgB.has("budgetMs") ? cfgB.get("budgetMs").getAsInt() : 6000;
        // (b4) the fast solver at 4x budget
        final JsonObject cfg4 = cfgB.deepCopy();
        cfg4.addProperty("nodes", String.valueOf(nodes * 4));
        cfg4.addProperty("engineMs", String.valueOf(engineMs * 4));
        cfg4.addProperty("budgetMs", String.valueOf(budgetMs * 4));
        cfg4.addProperty("planMs", String.valueOf(budgetMs * 4));
        // (rp) reference planner: Nx the node cap, its own time (engine at the live budget)
        final int px = Integer.getInteger("gap.planX", 8);
        final JsonObject cfgP = cfgB.deepCopy();
        cfgP.addProperty("nodes", String.valueOf(nodes * px));
        cfgP.addProperty("planMs", String.valueOf(budgetMs * px));
        cfgP.addProperty("budgetMs", String.valueOf(budgetMs * px));
        // (re) reference engine: the width model off (nodes 1), the engine search at a large budget
        final int eMs = Integer.getInteger("gap.engineMs", 20000);
        final JsonObject cfgE = cfgB.deepCopy();
        cfgE.addProperty("nodes", "1");
        cfgE.addProperty("engineMs", String.valueOf(eMs));
        cfgE.addProperty("budgetMs", String.valueOf(eMs * 2));
        final JsonArray ps = root.getAsJsonArray("positions");
        for (JsonElement e : ps) {
            final JsonObject o = e.getAsJsonObject();
            final String id = o.get("game").getAsString();
            final String frame = o.get("state").getAsString();
            try {
                final StringBuilder row = new StringBuilder("GAPROW " + id);
                final ForcedWinSolver.Proposal pb = run(frame, cfgB, row, "B");
                if (pb != null && pb.proven(1)) {
                    System.out.println(row + " => covered");
                    continue;
                }
                // the references first (most positions have no kill); the 4x budget only to classify a found kill
                final ForcedWinSolver.Proposal pp = run(frame, cfgP, row, "RP");
                ForcedWinSolver.Proposal ref = pp != null && pp.proven(1) ? pp : null;
                String by = "planner" + px + "x";
                if (ref == null) {
                    final ForcedWinSolver.Proposal pe = run(frame, cfgE, row, "RE");
                    ref = pe != null && pe.proven(1) ? pe : null;
                    by = "engine";
                }
                if (ref == null) {
                    System.out.println(row + " => none");
                    continue;
                }
                final ForcedWinSolver.Proposal p4 = run(frame, cfg4, row, "B4");
                if (p4 != null && p4.proven(1)) {
                    System.out.println(row + " => budget");
                    System.out.println("GAP " + id + " budget | " + flat(p4.line));
                    continue;
                }
                System.out.println(row + " => model(" + by + ")");
                System.out.println("GAP " + id + " model(" + by + ") | " + flat(ref.line));
                final Game gb = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
                final Player me = gb.getPlayers().get(0);
                final ForcedWinSolver sb = new ForcedWinSolver(me, SolverDriver.config(cfgB));
                final String line = flat(ref.line);
                final Set<String> named = new LinkedHashSet<>();
                for (ZoneType z : new ZoneType[] {ZoneType.Hand, ZoneType.Battlefield, ZoneType.Library, ZoneType.Graveyard}) {
                    for (Card c : me.getCardsIn(z)) {
                        if (line.contains(c.getName()) && named.add(c.getName())) {
                            final ForcedWinModel.ACard a = sb.readerForTest().card(gb, me, c);
                            System.out.println("GAPCARD " + id + " | " + c.getName() + " | zone=" + z + " unmodeled=" + a.unmodeled
                                    + " fromHand=" + flat(a.fromHand) + " onBf=" + flat(a.onBattlefield));
                        }
                    }
                }
            } catch (RuntimeException | Error ex) {
                System.out.println("GAPROW " + id + " failed: " + flat(ex).substring(0, Math.min(200, flat(ex).length())));
            }
        }
    }

    private static ForcedWinSolver.Proposal run(final String frame, final JsonObject cfg, final StringBuilder row, final String tag) {
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", frame);
        final ForcedWinSolver s = new ForcedWinSolver(g.getPlayers().get(0), SolverDriver.config(cfg));
        final long t0 = System.nanoTime();
        final ForcedWinSolver.Proposal p = best(s.propose(g));
        row.append(" ").append(tag).append("=").append(p == null ? "none" : p.proven(1) ? "proven" : "pB" + p.pBelief)
                .append(" ms").append(tag).append("=").append((System.nanoTime() - t0) / 1_000_000);
        return p;
    }
}
