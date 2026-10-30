package forge.ai.simulation;

/**
 * r1-assembly-bank-1029 (research only; nothing in play reads it): the honest belief re-draw of the opponent's hidden
 * cards that the BVW generator and labeller use (belief "uniform" over the observation-only cube pool, the same spec
 * {@link HonestGenerator#solver} builds), for the assembly bank's play-out worlds.
 */
public final class AssemblyWorlds {
    private AssemblyWorlds() {
    }

    /** The opponent re-draw of an honest label spec (redraw belief, hideOwnLibrary true); never null. */
    public static ForcedWinSolver.Redraw opponentBelief() {
        final HonestLabel.Spec spec = new HonestLabel.Spec();
        spec.redraw = "belief";
        spec.solverCfg.addProperty("hideOwnLibrary", "true");
        spec.lossCfg = spec.solverCfg.deepCopy();
        spec.checkHonest();
        final LookaheadSearch belief = new LookaheadSearch(spec.beliefConfig());
        final ForcedWinSolver.Redraw r = belief.opponentRedraw();
        if (r == null) {
            throw new IllegalStateException("refused: no belief redraw");
        }
        return r;
    }

    /** The belief spec's sha (what the bank rows record as their world spec). */
    public static String beliefSpecSha() {
        final HonestLabel.Spec spec = new HonestLabel.Spec();
        spec.redraw = "belief";
        spec.solverCfg.addProperty("hideOwnLibrary", "true");
        spec.lossCfg = spec.solverCfg.deepCopy();
        return spec.sha();
    }
}
