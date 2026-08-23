package forge.ai.simulation;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A node budget, and a progress trace, for the simulation picker's search.
 *
 * <p><b>Why this exists.</b> {@link SpellAbilityPicker} has no bound of any kind on the
 * amount of work one priority decision may do. {@code simMaxDepth} bounds the
 * <i>recursion</i>, and that is the only lever the bench had; it does not bound the
 * <i>width</i>. The width is the odometer in {@link SpellAbilityChoicesIterator}: one
 * {@code ChoicePoint} per hidden-origin card choice a resolving spell makes, enumerated
 * exhaustively, with a full {@link GameSimulator} (a whole-game copy, every card rebuilt
 * from its script) per combination.
 *
 * <p>Measured on the mtgx owner corpus, frame a.145 of the Doomsday tape: Doomsday is
 * {@code ChangeNum$ 5} out of {@code Origin$ Graveyard,Library} with a 30-card library and
 * one card in the graveyard, so the iterator builds five choice points of ~31 distinct
 * names each and the odometer is ~31^5 ~ 2.9e7 combinations — each of which then pays for
 * the nested candidate recursion beneath it. The JVM never returned: five runs at
 * {@code --sim-max-depth 4} and {@code 2}, all aborted at the harness's 300 s wall timeout
 * with zero Forge events. Depth was not the variable, and lowering it does not help,
 * because the odometer sits <i>inside</i> a single depth level.
 *
 * <p><b>What this does.</b> Counts {@code GameSimulator} evaluations per top-level
 * decision and, once the count passes a configured budget, stops <i>expanding new work</i>:
 * the candidate loop breaks and the choices odometer is abandoned (unwound through its own
 * bookkeeping, so the controller's decision stack still balances). The best score found so
 * far is what the decision returns — the search becomes an anytime search rather than an
 * exhaustive one.
 *
 * <p><b>Deterministic on purpose.</b> The budget is a count of simulations, not a
 * wall-clock deadline. A wall-clock cut would make the same seed answer differently on a
 * loaded machine and would quietly break the bench's reproducibility claim; a node count
 * does not. Wall time is reported by the trace but never decides anything.
 *
 * <p><b>Never silent.</b> Every clip prints one line to {@code stderr} naming the decision
 * it clipped, the budget, and what was still unexplored. A reading taken from a run whose
 * stderr carries {@code [simbudget] clipped} is a reading from a truncated search and must
 * say so.
 *
 * <p><b>Off by default.</b> {@code budget == 0} means unbounded, which is Forge's own
 * behaviour, unchanged. Only the bench turns it on ({@code simMaxSimulations} in the
 * BenchMain config).
 */
public final class SimSearchBudget {
    private SimSearchBudget() {
    }

    /** 0 = unbounded (Forge's own behaviour). */
    private static volatile int budget = 0;

    /** 0 = no trace. Otherwise the trace thread prints progress every this many ms. */
    private static volatile long traceMs = 0;

    /** Simulations spent inside the current top-level decision. */
    private static volatile int spent = 0;

    /** Simulations spent since the JVM started; reported by the trace only. */
    private static final AtomicLong lifetime = new AtomicLong();

    /** Number of top-level decisions that hit the budget in this JVM. */
    private static final AtomicLong clips = new AtomicLong();

    /** Human-readable label of the decision currently being searched, for the trace. */
    private static volatile String label = "(idle)";

    /** The choices odometer currently being enumerated, for the trace. Weakly held by intent. */
    private static volatile SpellAbilityChoicesIterator odometer = null;

    private static volatile long decisionStartedAt = 0;
    private static volatile boolean clippedThisDecision = false;
    private static Thread tracer = null;

    public static void setBudget(final int simulations) {
        budget = Math.max(0, simulations);
    }

    public static int getBudget() {
        return budget;
    }

    public static long getClipCount() {
        return clips.get();
    }

    public static long getLifetimeSimulations() {
        return lifetime.get();
    }

    /**
     * Turn on the periodic progress trace. Diagnostic only: it never changes a decision.
     * Idempotent; a second call with a positive interval leaves the existing thread alone.
     */
    public static synchronized void setTraceMs(final long ms) {
        traceMs = Math.max(0, ms);
        if (traceMs > 0 && tracer == null) {
            tracer = new Thread(SimSearchBudget::traceLoop, "sim-search-trace");
            tracer.setDaemon(true);
            tracer.start();
        }
    }

    private static void traceLoop() {
        while (true) {
            final long ms = traceMs;
            if (ms <= 0) {
                return;
            }
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                return;
            }
            final SpellAbilityChoicesIterator it = odometer;
            final long elapsed = decisionStartedAt == 0 ? 0 : System.currentTimeMillis() - decisionStartedAt;
            System.err.println("[simtrace] " + label
                    + " | sims=" + spent + "/" + (budget == 0 ? "inf" : Integer.toString(budget))
                    + " lifetime=" + lifetime.get()
                    + " ms=" + elapsed
                    + " | odometer " + (it == null ? "(none)" : it.describeProgress()));
        }
    }

    /** Called when a top-level (non-recursive) decision starts its search. */
    static void beginDecision(final String what) {
        spent = 0;
        clippedThisDecision = false;
        label = what;
        decisionStartedAt = System.currentTimeMillis();
    }

    /** Called when a top-level decision is done, whether it finished or was clipped. */
    static void endDecision() {
        odometer = null;
        label = "(idle)";
    }

    /**
     * Publish the odometer the search is currently turning, and hand back the one it
     * replaces so the caller can put it back — {@code evaluateSa} nests, so without the
     * restore the trace would keep reporting a finished inner odometer forever.
     * Trace bookkeeping only; a no-op when the trace is off.
     */
    static SpellAbilityChoicesIterator swapOdometer(final SpellAbilityChoicesIterator it) {
        if (traceMs <= 0) {
            return null;
        }
        final SpellAbilityChoicesIterator prev = odometer;
        odometer = it;
        return prev;
    }

    /** One {@code GameSimulator} evaluation was spent. */
    static void countSimulation() {
        spent++;
        lifetime.incrementAndGet();
    }

    /** True once the current top-level decision has spent its budget. */
    static boolean exhausted() {
        return budget > 0 && spent >= budget;
    }

    /**
     * Report a clip exactly once per top-level decision, then stay quiet: a clipped
     * Doomsday decision would otherwise print a line per abandoned choice point.
     *
     * @param where  which of the two expansions was cut
     * @param detail what was left unexplored
     */
    static void reportClip(final String where, final String detail) {
        if (clippedThisDecision) {
            return;
        }
        clippedThisDecision = true;
        clips.incrementAndGet();
        final long elapsed = decisionStartedAt == 0 ? 0 : System.currentTimeMillis() - decisionStartedAt;
        System.err.println("[simbudget] clipped " + where + " after " + spent
                + " simulations (budget " + budget + ", " + elapsed + " ms) at " + label
                + " -- " + detail
                + " -- the search is TRUNCATED; any reading from this run must say so");
    }
}
