package forge.ai.simulation;

import java.io.FileReader;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.ai.AiProfileUtil;
import forge.bench.rl.SolverDriver;
import forge.game.Game;
import forge.game.player.Player;

/**
 * Diagnostics (opt-in, -Dplandump.positions=FILE in the OwnerLinesTest format; does nothing without it): the width model
 * of the first position (every hand / battlefield card with its abilities, the library contents) and a plan of it at
 * -Dplandump.nodes (default 200000) nodes, with the planner's stop reason and its lines.
 */
public class PlanDumpTest extends forge.ai.AITest {
    @Test
    public void dump() throws Exception {
        final String path = System.getProperty("plandump.positions");
        if (path == null) {
            return;
        }
        final JsonObject root;
        try (FileReader r = new FileReader(path)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        final JsonObject cfg = root.getAsJsonObject("solverCfg").deepCopy();
        cfg.addProperty("act", "false");
        cfg.addProperty("defense", "false");
        for (com.google.gson.JsonElement pe : root.getAsJsonArray("positions")) {
        final JsonObject o = pe.getAsJsonObject();
        System.out.println("PLANDUMP == " + o.get("game").getAsString());
        final Game g = ForcedWinSolverTest.game(AiProfileUtil.FORCED_WIN_PROFILE, "Default", o.get("state").getAsString());
        final Player me = g.getPlayers().get(0);
        final ForcedWinSolver s = new ForcedWinSolver(me, SolverDriver.config(cfg));
        final ForcedWinModel.Position pos = s.readPosition(g, s.knownTop(g));
        System.out.println("PLANDUMP life=" + pos.life + " landDrop=" + pos.landDrop + " sorcery=" + pos.sorcerySpeed + " storm=" + pos.storm);
        for (ForcedWinModel.ACard a : pos.hand) {
            System.out.println("PLANDUMP hand " + a + " fromHand=" + a.fromHand + " onBf=" + a.onBattlefield);
        }
        for (ForcedWinModel.ACard a : pos.battlefield) {
            System.out.println("PLANDUMP bf " + a + " src=" + a.sourceColors + "x" + a.sourceAmount + " onBf=" + a.onBattlefield);
        }
        System.out.println("PLANDUMP library " + pos.library.size() + " graveyard " + pos.graveyard.size());
        for (ForcedWinModel.ACard a : pos.library) {
            System.out.println("PLANDUMP lib " + a + " fromHand=" + a.fromHand + " onBf=" + a.onBattlefield + " src=" + a.sourceColors);
        }
        for (String hz : System.getProperty("plandump.horizons", System.getProperty("plandump.horizon", "1")).split(",")) {
        final ForcedWinPlanner.Limits lim = s.limits();
        lim.nodes = Integer.getInteger("plandump.nodes", 200_000);
        lim.horizon = Integer.parseInt(hz.trim());
        lim.payPolicies = Integer.getInteger("plandump.pay", lim.payPolicies);
        lim.ms = -1L;
        final ForcedWinPlanner.Result r = ForcedWinPlanner.plan(pos, lim);
        System.out.println("PLANDUMP plan horizon=" + lim.horizon + " nodes=" + r.nodes + " exhausted=" + r.exhausted + " stoppedBy=" + r.stoppedBy
                + " depthStops=" + r.depthStops + " lines=" + r.lines.size() + " ms=" + r.nanos / 1_000_000);
        for (int li = 0; li < Math.min(5, r.lines.size()); li++) {
            System.out.println("PLANDUMP line " + r.lines.get(li));
        }
        }
        }
    }
}
