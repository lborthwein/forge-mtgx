package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * architect-o ruling 10-30 (the storm question): the shared budgeted MIN ({@link OppMin}) on scripted lines. A line is
 * n response points; at each, the opponent holds the answers it has not spent; the line is lost only when the scripted
 * response hits a point the test calls a kill. Node counts per world are printed against DESIGN-R3's estimate
 * (sum over j <= budget of C(n, j) b^j, plus Forge AI's own root).
 */
public class OppMinTest {

    /** A line of {@code n} points; the opponent holds {@code answers} distinct answers (b per point while unspent). */
    static java.util.function.Function<OppMin.Script, OppMin.Leaf> line(final int n, final int answers, final int hint,
            final java.util.function.IntPredicate kills) {
        return s -> {
            final List<OppMin.Point> pts = new ArrayList<>();
            int spent = 0;
            boolean lost = false;
            final StringBuilder state = new StringBuilder();
            for (int j = 0; j < n; j++) {
                final int left = s.forge ? answers : answers - spent;
                pts.add(new OppMin.Point(Math.max(0, left), "pt" + j + "/" + state, j == n - 1 ? 3 : 1,
                        left <= 0 ? 0 : spent == 0 ? hint : 1));
                final int r = s.respAt(j);
                if (r >= 0) {
                    spent++;
                    state.append(j).append(':').append(r).append(';');
                    lost |= kills.test(j);
                }
            }
            return new OppMin.Leaf(lost ? 0.0 : 1.0, lost ? "terminal" : "terminal", pts);
        };
    }

    static OppMin.Cfg cfg() {
        return new OppMin.Cfg(1000, 4, 2);
    }

    @Test
    public void aSevenSpellLineAgainstNothingHasATargetAndNoCap() {
        final OppMin.Out o = OppMin.min(cfg(), line(7, 0, 0, j -> true));
        AssertJUnit.assertEquals(1.0, o.v, 1e-12);
        AssertJUnit.assertNull(o.cap);
        AssertJUnit.assertEquals("Forge AI's root and pass-everywhere only", 2, o.nodes);
        System.err.println("[oppmin] n=7 b=0: nodes " + o.nodes);
    }

    @Test
    public void oneCounterIsTriedAtEveryPoint() {
        // countering only the kill (the last point) refutes the line: the min is 0, found first (highest impact)
        final OppMin.Out kill = OppMin.min(cfg(), line(7, 1, 1, j -> j == 6));
        AssertJUnit.assertEquals(0.0, kill.v, 1e-12);
        AssertJUnit.assertEquals("[6:0]", kill.why);
        AssertJUnit.assertEquals("root, pass-all, then the kill point first", 3, kill.nodes);
        // no point is a kill: every point is tried once (budget 1), the line is won with no cap
        final OppMin.Out none = OppMin.min(cfg(), line(7, 1, 1, j -> false));
        AssertJUnit.assertEquals(1.0, none.v, 1e-12);
        AssertJUnit.assertNull(none.cap);
        AssertJUnit.assertEquals(2 + 7, none.nodes);
        AssertJUnit.assertEquals(1, none.budget);
        System.err.println("[oppmin] n=7 b=1 k=1: nodes " + none.nodes + " (estimate 8 + root)");
    }

    @Test
    public void twoAnswersWithManaForBothGetBudgetTwo() {
        final OppMin.Out o = OppMin.min(cfg(), line(7, 2, 2, j -> false));
        AssertJUnit.assertEquals(1.0, o.v, 1e-12);
        AssertJUnit.assertNull(o.cap);
        AssertJUnit.assertEquals(2, o.budget);
        // b = 2 at the first response, 1 after it: 2 + 7*2 + sum over pairs i<j of 2*1 = 2 + 14 + 42
        AssertJUnit.assertEquals(2 + 14 + 42, o.nodes);
        final OppMin.Out k1 = OppMin.min(cfg(), line(7, 2, 1, j -> false));
        AssertJUnit.assertEquals("two answers, mana for one: budget 1, and the spare answer is a cap hit",
                "budget", k1.cap);
        AssertJUnit.assertTrue(Double.isNaN(k1.v));
        System.err.println("[oppmin] n=7 b=2 k=2: nodes " + o.nodes + " (estimate 99 + root at b=2 throughout); k=1: "
                + k1.nodes);
        for (int n : new int[] {3, 5, 9, 12}) {
            System.err.println("[oppmin] n=" + n + " b=1 k=1: " + OppMin.min(cfg(), line(n, 1, 1, j -> false)).nodes
                    + "; b=2 k=2: " + OppMin.min(cfg(), line(n, 2, 2, j -> false)).nodes);
        }
    }

    /** Review #148 (fail closed): a point whose set has an answer OppMin cannot enumerate is a cap hit, logged by reason. */
    @Test
    public void anUnenumerableAnswerIsACapHit() {
        final OppMin.Out o = OppMin.min(cfg(), s -> {
            final List<OppMin.Point> pts = new ArrayList<>();
            pts.add(new OppMin.Point(0, "p0", 1, 0, null));
            pts.add(new OppMin.Point(0, "p1", 1, 0, "unknown-api")); // a castable answer with no modelled effect
            return new OppMin.Leaf(1.0, "terminal", pts);
        });
        AssertJUnit.assertTrue(Double.isNaN(o.v));
        AssertJUnit.assertEquals("gap:unknown-api", o.cap);
        AssertJUnit.assertEquals(Integer.valueOf(1), o.caps.get("gap:unknown-api"));
        // a refutation still wins over the gap: the min is 0
        final OppMin.Out z = OppMin.min(cfg(), s -> new OppMin.Leaf(s.forge ? 1.0 : 0.0, "terminal",
                java.util.Collections.singletonList(new OppMin.Point(0, "p", 1, 0, "unknown-api"))));
        AssertJUnit.assertEquals(0.0, z.v, 1e-12);
        // the per-point cap is bounded by the modal enumeration cap
        boolean refused = false;
        try {
            new OppMin.Cfg(64, OppMin.CHARM_CAP + 1, 2);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        AssertJUnit.assertTrue(refused);
    }

    @Test
    public void theNodeCapAndTheMemo() {
        final OppMin.Cfg c = new OppMin.Cfg(5, 4, 2);
        final OppMin.Out o = OppMin.min(c, line(7, 1, 1, j -> false));
        AssertJUnit.assertTrue(Double.isNaN(o.v));
        AssertJUnit.assertEquals("nodes", o.cap);
        // the memo: points with the same state and budget left expand once
        final OppMin.Out m = OppMin.min(cfg(), s -> {
            final List<OppMin.Point> pts = new ArrayList<>();
            for (int j = 0; j < 4; j++) pts.add(new OppMin.Point(s.respAt(j) >= 0 || s.pts.length > 0 ? 0 : 1, "same", 1, 1));
            return new OppMin.Leaf(1.0, "terminal", pts);
        });
        AssertJUnit.assertEquals(3, m.memoHits);
        AssertJUnit.assertEquals(3, m.nodes);
    }
}
