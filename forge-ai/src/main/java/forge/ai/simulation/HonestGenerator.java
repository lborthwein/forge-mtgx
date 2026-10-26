package forge.ai.simulation;

import com.google.gson.JsonObject;

import forge.game.player.Player;

/** BVW research generator: ordinary AI plus honestly searched, executed solver lines. No value or policy model. */
public final class HonestGenerator {
    private HonestGenerator() {
    }

    public static ForcedWinSolver solver(final Player me, final ForcedWinSolver.Config cfg) {
        // Reuse the label path's belief and hidden-own-library guard. The player solver keeps its own execution and
        // pile memory; neither exact-world mode nor synthetic frame memory is permitted on a natural live game.
        final HonestLabel.Spec spec = new HonestLabel.Spec();
        spec.redraw = "belief";
        spec.worlds = cfg.worlds;
        spec.solverCfg.addProperty("hideOwnLibrary", String.valueOf(cfg.hideOwnLibrary));
        spec.lossCfg = spec.solverCfg.deepCopy();
        spec.checkHonest();
        if (cfg.exact || cfg.frameMemory || cfg.defense) {
            throw new IllegalArgumentException("refused: honest generator needs exact=false, frameMemory=false, "
                    + "defense=false (ordinary AI handles defense)");
        }
        if (!cfg.act || !cfg.forced || cfg.likely || !cfg.adversary || cfg.threshold != 1.0) {
            throw new IllegalArgumentException("refused: honest generator executes forced lines only: act/forced/"
                    + "adversary=true, likely=false, threshold=1.0");
        }
        cfg.honestProbes = true;
        final LookaheadSearch belief = new LookaheadSearch(spec.beliefConfig());
        final ForcedWinSolver s = new ForcedWinSolver(me, cfg);
        s.redraw = belief.opponentRedraw();
        if (s.redraw == null) {
            throw new IllegalStateException("refused: honest generator has no belief redraw");
        }
        return s;
    }

    public static JsonObject summary(final ForcedWinSolver s) {
        final JsonObject out = new JsonObject();
        final JsonObject spec = new JsonObject();
        spec.addProperty("schema", "mtgx-bvw-honest-generator/1");
        spec.addProperty("redraw", "belief");
        spec.addProperty("beliefCube", BeliefSampler.LIVE_CUBE);
        spec.addProperty("beliefCubeSha256", BeliefSampler.LIVE_CUBE_SHA256);
        spec.addProperty("beliefBasics", 8);
        spec.addProperty("beliefSingleton", false);
        spec.add("solverCfg", new com.google.gson.Gson().toJsonTree(s.config()));
        out.add("spec", spec);
        out.addProperty("decisions", s.decisions);
        out.addProperty("taken", s.taken);
        out.addProperty("replans", s.replans);
        out.addProperty("copies", s.copies);
        out.addProperty("engine_runs", s.engineRuns);
        out.addProperty("engine_found", s.engineFound);
        out.addProperty("planner_ms", s.plannerNanos / 1e6);
        out.addProperty("engine_ms", s.engineNanos / 1e6);
        return out;
    }
}
