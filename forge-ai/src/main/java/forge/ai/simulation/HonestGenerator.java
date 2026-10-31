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
        if (cfg.counter) {
            // review #3: counter budgets are host-independent only with Forge AI's own clocks non-binding
            final String no = ForcedWinSolver.counterRuntimeRefusal(me.getGame().getAITimeout());
            if (no != null) {
                throw new IllegalArgumentException(no);
            }
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
        spec.add("solverCfg", solverCfgJson(s.config()));
        if (s.config().counter) {
            // review #3: the Forge AI settings a counter run depends on are part of the recorded spec (replays compare it)
            final JsonObject rt = new JsonObject();
            rt.addProperty("sequentialAi", Boolean.getBoolean("forge.bench.sequentialAi"));
            rt.addProperty("aiTimeoutSec", aiTimeoutOf(s));
            spec.add("runtime", rt);
        }
        out.add("spec", spec);
        out.addProperty("decisions", s.decisions);
        out.addProperty("taken", s.taken);
        out.addProperty("replans", s.replans);
        out.addProperty("copies", s.copies);
        out.addProperty("engine_runs", s.engineRuns);
        out.addProperty("engine_found", s.engineFound);
        out.addProperty("planner_ms", s.plannerNanos / 1e6);
        out.addProperty("engine_ms", s.engineNanos / 1e6);
        if (s.config().counter || ForcedWinSolver.METER_RECEIPTS) {
            // bvw-step1-build-1024: the game's work in counter units (outside the spec: diagnostics only)
            out.add("meter", s.meter.toJson());
        }
        return out;
    }

    private static int aiTimeoutOf(final ForcedWinSolver s) {
        try {
            return s.seatGame().getAITimeout();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * The solver config as the tape records it (the generator spec replays compare). bvw-step1-build-1024: the keys
     * added after the step-0 freeze ({@link ForcedWinSolver.Config#STEP1_DEFAULTS}) appear only when they differ from
     * their defaults, so a step-0 (jar 30dae214) tape's spec is reproduced byte for byte by this jar.
     */
    public static JsonObject solverCfgJson(final ForcedWinSolver.Config c) {
        final JsonObject o = new com.google.gson.Gson().toJsonTree(c).getAsJsonObject();
        final java.util.Map<String, Object> defaults = new java.util.HashMap<>(ForcedWinSolver.Config.STEP1_DEFAULTS);
        defaults.putAll(ForcedWinSolver.Config.V2_DEFAULTS); // labeller v2: off = the recorded config unchanged
        defaults.putAll(ForcedWinSolver.Config.R2_DEFAULTS); // solver-cap12 round 2: off = unchanged
        for (java.util.Map.Entry<String, Object> e : defaults.entrySet()) {
            final com.google.gson.JsonElement v = o.get(e.getKey());
            if (v != null && v.isJsonPrimitive() && String.valueOf(e.getValue()).equals(v.getAsString())) {
                o.remove(e.getKey());
            }
        }
        return o;
    }
}
