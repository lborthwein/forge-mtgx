package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiFixes;

/**
 * The median departure gate (Config.departMedian, lane misplays-1005), on the paired differences the owner's reports of
 * 2026-10-05 recorded (session 486d4d1b, exact private replay): departZ alone departs to Manamorphose and to a no-op Crew,
 * whose differences are mostly zero or slightly negative with a few large worlds; the median gate keeps Forge's answer
 * there and still departs where most worlds agree (Show and Tell, Portable Hole's flat +75: those are the evaluator's,
 * not this gate's, to fix).
 */
public class LookaheadDepartMedianTest {

    private static double[][] two(double... diff) {
        final double[][] v = new double[2][diff.length];
        for (int w = 0; w < diff.length; w++) {
            v[0][w] = 1000;
            v[1][w] = 1000 + diff[w];
        }
        return v;
    }

    private static LookaheadSearch search(AiFixes.Mode mode) {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        c.departZ = 1.645;
        c.departMedian = mode;
        return new LookaheadSearch(c);
    }

    @Test
    public void offByDefaultAndSilent() {
        LookaheadSearch.Config c = new LookaheadSearch.Config();
        AssertJUnit.assertEquals(AiFixes.Mode.OFF, c.departMedian);
        AssertJUnit.assertFalse(c.toJson().has("departMedian"));
        LookaheadSearch s = new LookaheadSearch(c);
        try {
            AssertJUnit.assertFalse(s.getStats().toJson().has("departMedian"));
        } finally {
            s.shutdown();
        }
    }

    @Test
    public void medianDiff() {
        AssertJUnit.assertEquals(-10.0, LookaheadSearch.medianDiff(two(-10, 575, -10, 136, 483, -10, -10, -10), 1, 8), 1e-9);
        AssertJUnit.assertEquals(0.0, LookaheadSearch.medianDiff(two(249, 0, 0, 249, 0, 0, 0, 178), 1, 8), 1e-9);
        AssertJUnit.assertEquals(96.5, LookaheadSearch.medianDiff(two(101, 92, 101, 2, 234, 88, 144, -1), 1, 8), 1e-9);
        AssertJUnit.assertEquals(5.0, LookaheadSearch.medianDiff(two(5), 1, 1), 1e-9);
    }

    @Test
    public void gateKeepsForgesAnswerWhereAFewWorldsCarryTheDeparture() {
        final boolean[] ok = {true, true};
        LookaheadSearch s = search(AiFixes.Mode.ON);
        try {
            double[][] mana = two(-10, 575, -10, 136, 483, -10, -10, -10);
            AssertJUnit.assertEquals("departZ alone departs (z ~ 1.76)", 1, s.argmax(mana, ok, 2, 8));
            AssertJUnit.assertEquals("the median gate keeps Forge's pass", 0, s.argmaxMedian(mana, ok, 2, 8));
            double[][] crew = two(249, 0, 0, 249, 0, 0, 0, 178);
            AssertJUnit.assertEquals(1, s.argmax(crew, ok, 2, 8));
            AssertJUnit.assertEquals(0, s.argmaxMedian(crew, ok, 2, 8));
            double[][] snt = two(101, 92, 101, 2, 234, 88, 144, -1);
            AssertJUnit.assertEquals("most worlds agree: still departs", 1, s.argmaxMedian(snt, ok, 2, 8));
            double[][] flat = two(75, 75, 75, 75, 80, 75, 75, 75);
            AssertJUnit.assertEquals(1, s.argmaxMedian(flat, ok, 2, 8));
            AssertJUnit.assertEquals(2, s.getStats().departMedianGated);
        } finally {
            s.shutdown();
        }
    }
}
