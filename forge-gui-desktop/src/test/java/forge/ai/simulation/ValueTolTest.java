package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * deadband-1108: the value dead-band behind {@code -Dforge.search.valueTol} ({@link ValueTol}). The suite runs with the
 * key off and sets EPS per test. It checks (1) off = the old comparisons bit for bit, (2) on = choices that do not move
 * when every value moves by noise far below EPS (the cross-CPU float32 case: about 1e-7), and (3) the search's own
 * gates (argmax, departZ {@code confident}, the anytime choice) on the recorded near-tie shapes.
 */
public class ValueTolTest {
    private static final double EPS = 1e-5;

    @AfterMethod
    public void off() {
        ValueTol.setForTest(0.0);
    }

    private static boolean[][] all(int n, int k) {
        final boolean[][] f = new boolean[n][k];
        for (boolean[] r : f) {
            Arrays.fill(r, true);
        }
        return f;
    }

    private static boolean[] oks(int n) {
        final boolean[] o = new boolean[n];
        Arrays.fill(o, true);
        return o;
    }

    @Test
    public void keyIsOffByDefaultAndParses() {
        assertEquals(System.getProperty(ValueTol.KEY), null, "the suite runs with -Dforge.search.valueTol unset");
        assertEquals(ValueTol.eps(), 0.0);
        assertEquals(ValueTol.parse(null), 0.0);
        assertEquals(ValueTol.parse(""), 0.0);
        assertEquals(ValueTol.parse("1e-5"), 1e-5);
        for (String bad : new String[] {"-1e-5", "0.5", "NaN", "x"}) {
            try {
                ValueTol.parse(bad);
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // refused
            }
        }
    }

    @Test
    public void offIsThePlainComparison() {
        final Random r = new Random(1108);
        for (int t = 0; t < 2000; t++) {
            final double a = r.nextInt(5) / 4.0, b = r.nextInt(5) / 4.0;
            assertEquals(ValueTol.gt(a, b), a > b);
            assertEquals(ValueTol.pos(a - b), a - b > 0);
            final double[] v = new double[1 + r.nextInt(7)];
            for (int i = 0; i < v.length; i++) {
                v[i] = r.nextInt(10) == 0 ? Double.NaN : r.nextInt(4) / 3.0;
            }
            final Integer[] o = new Integer[v.length];
            for (int i = 0; i < o.length; i++) {
                o[i] = i;
            }
            Arrays.sort(o, (x, y) -> {
                final int c = Double.compare(v[y], v[x]);
                return c != 0 ? c : Integer.compare(x, y);
            });
            final int[] got = ValueTol.orderDesc(v);
            for (int i = 0; i < o.length; i++) {
                assertEquals(got[i], (int) o[i], "order " + Arrays.toString(v));
            }
        }
    }

    /** The recorded cross-CPU shape (seed 747624057, a plan decision): four candidates tied up to 1e-7. */
    @Test
    public void tiedPlanCandidatesKeepTheDefaultOnEveryHost() {
        final double base = 0.4534;
        final Random r = new Random(57);
        for (int host = 0; host < 200; host++) {
            final double[][] v = new double[5][8];
            for (int w = 0; w < 8; w++) {
                final double x = base + (w - 4) * 0.01;
                for (int c : new int[] {0, 1, 3, 4}) {
                    v[c][w] = x + (r.nextDouble() - 0.5) * 2e-7; // float32 noise between hosts and batch slots
                }
                v[2][w] = 0.1846 + (w - 4) * 0.01;
            }
            ValueTol.setForTest(EPS);
            assertEquals(LookaheadSearch.chooseAnytime(v, all(5, 8), oks(5), 5, 8, 1.645, 0, Double.NaN), 0);
            assertFalse(LookaheadSearch.confident(v, 1, 8, 1.645));
            assertFalse(LookaheadSearch.confident(v, 3, 8, 1.645));
        }
    }

    /** Zero spread: the same difference in every world. Off, a 1e-7 gain departs (the flip); on, it does not. */
    @Test
    public void zeroSpreadNoiseIsNoGain() {
        final double[][] v = {{.5, .6, .7}, {.5 + 1e-7, .6 + 1e-7, .7 + 1e-7}};
        assertTrue(LookaheadSearch.confident(v, 1, 3, 1.645), "off: noise passes the z gate (se = 0)");
        ValueTol.setForTest(EPS);
        assertFalse(LookaheadSearch.confident(v, 1, 3, 1.645));
        final double[][] real = {{.5, .6, .7}, {.6, .7, .8}};
        assertTrue(LookaheadSearch.confident(real, 1, 3, 1.645), "a real gain still departs");
        assertEquals(LookaheadSearch.chooseAnytime(real, all(2, 3), oks(2), 2, 3, 1.645, 0, Double.NaN), 1);
    }

    /** Among departures within the band the lower index wins, whatever the noise order. */
    @Test
    public void departuresWithinTheBandGoToTheLowerIndex() {
        final Random r = new Random(5108);
        for (int host = 0; host < 200; host++) {
            final double[][] v = new double[4][4];
            for (int w = 0; w < 4; w++) {
                v[0][w] = .4 + w * .01;
                v[1][w] = .3 + w * .01;
                v[2][w] = .6 + w * .01 + (r.nextDouble() - 0.5) * 2e-7;
                v[3][w] = .6 + w * .01 + (r.nextDouble() - 0.5) * 2e-7;
            }
            ValueTol.setForTest(EPS);
            assertEquals(LookaheadSearch.chooseAnytime(v, all(4, 4), oks(4), 4, 4, 1.645, 0, Double.NaN), 2);
        }
    }

    @Test
    public void argmaxLowAndOrderAreStableUnderNoise() {
        final Random r = new Random(9654);
        ValueTol.setForTest(EPS);
        for (int host = 0; host < 500; host++) {
            final double n1 = (r.nextDouble() - 0.5) * 2e-7, n2 = (r.nextDouble() - 0.5) * 2e-7, n3 = (r.nextDouble() - 0.5) * 2e-7;
            final double[] p = {0.2, 0.49678 + n1, 0.49678 + n2, 0.0023, 0.49678 + n3, Double.NaN};
            assertEquals(ValueTol.argmaxLow(p), 1);
            assertEquals(ValueTol.orderDesc(p), new int[] {5, 1, 2, 4, 0, 3});
        }
        // a real gap is kept, NaN never wins, all-NaN is -1
        assertEquals(ValueTol.argmaxLow(new double[] {0.3, 0.5, Double.NaN}), 1);
        assertEquals(ValueTol.argmaxLow(new double[] {Double.NaN}), -1);
        assertEquals(ValueTol.argmaxLow(new float[] {0.25f, 0.25f, 0.5f}), 2);
    }

    @Test
    public void selectExtrasAndMedianFollowTheBand() {
        ValueTol.setForTest(EPS);
        final Double[] p = {null, null, 0.30 + 1e-7, 0.30, null, 0.10};
        assertEquals(LookaheadSearch.selectExtras(p, 2, 1), new int[] {2});
        final Double[] q = {null, null, 0.30, 0.30 + 1e-7, null, 0.10};
        assertEquals(LookaheadSearch.selectExtras(q, 2, 1), new int[] {2}, "a near-tie goes to the lower index");
        ValueTol.setForTest(0.0);
        assertEquals(LookaheadSearch.selectExtras(q, 2, 1), new int[] {3}, "off: the larger value, as before");
    }
}
