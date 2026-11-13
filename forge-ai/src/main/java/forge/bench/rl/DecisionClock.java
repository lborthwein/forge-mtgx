package forge.bench.rl;

import com.google.gson.JsonObject;

import forge.util.StateMachine;

/**
 * state-machines-1113: the phases of one searched pilot decision ({@link RlSearch} decide0), made explicit, and the
 * wall time of each. The decision line's {@code "ms"} object carries them, so where a decision's time went is read off
 * the line instead of being rebuilt from searchMs, scoreMs and the end-of-game lists.
 *
 * <p>The state is the phase that has just ENDED. A searched decision ends SCORE (the SCORE round trip), then at a plan
 * point PROPOSE (pooling, the solver and the sampled plans; its solver part is reported apart as {@code solver}), then
 * GIVEN (the play-outs: guard copy, prepare, run, leaves, choice), then at a plan point APPLY; any phase may be the
 * last. {@code -Dforge.state.trace=decision} prints each transition with its time; an illegal order is logged as
 * {@code [state] ILLEGAL decision ...} (never thrown outside {@code -Dforge.state.strict=true}).
 *
 * <p>Timing only: nothing here reads or changes the game, the search or its budget, so a decision is the same with or
 * without it. The gates' digests drop {@code "ms"} (compare.py's timing keys), and the host's line parser keeps only
 * the fields it names.
 */
public final class DecisionClock {

    public enum Phase { START, SCORE, PROPOSE, GIVEN, APPLY }

    public static final StateMachine<Phase> MACHINE = StateMachine.builder("decision", Phase.class)
            .edge(Phase.START, Phase.SCORE)
            .edge(Phase.SCORE, Phase.PROPOSE, Phase.GIVEN)
            .edge(Phase.PROPOSE, Phase.GIVEN)
            .edge(Phase.GIVEN, Phase.APPLY)
            .doc("one searched pilot decision (RlSearch.decide0); the state is the phase that just ended")
            .build();

    private final StateMachine.Cell<Phase> cell;
    private final long t0;
    private long last;
    private final double[] ms = new double[Phase.values().length];
    private double solverMs = -1;

    /** A decision that started at {@code t0} (System.nanoTime), for seat {@code key}. */
    DecisionClock(final Object key, final long t0) {
        this.cell = MACHINE.cell(key, Phase.START);
        this.t0 = t0;
        this.last = t0;
    }

    /** {@code p} has just ended: its time is the time since the previous phase ended (or the decision started). */
    void ended(final Phase p) {
        final long now = System.nanoTime();
        final double d = (now - last) / 1e6;
        ms[p.ordinal()] += d;
        last = now;
        cell.go(p, MACHINE.trace ? round(d) + " ms" : null);
    }

    /** The solver's part of PROPOSE (ms), when the solver ran. */
    void solver(final double solverMs) {
        this.solverMs = solverMs;
    }

    public Phase phase() {
        return cell.get();
    }

    /** The ended phases (ms, one decimal), the solver's part of PROPOSE, and the time since the decision started. */
    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        for (Phase p : Phase.values()) {
            if (p != Phase.START && (p.ordinal() <= cell.get().ordinal())) {
                if (p == Phase.PROPOSE && ms[p.ordinal()] == 0 && solverMs < 0) {
                    continue; // not a plan point
                }
                o.addProperty(p.name().toLowerCase(java.util.Locale.ROOT), round(ms[p.ordinal()]));
            }
        }
        if (solverMs >= 0) {
            o.addProperty("solver", round(solverMs));
        }
        o.addProperty("total", round((System.nanoTime() - t0) / 1e6));
        return o;
    }

    private static double round(final double v) {
        return Math.round(v * 10) / 10.0;
    }
}
