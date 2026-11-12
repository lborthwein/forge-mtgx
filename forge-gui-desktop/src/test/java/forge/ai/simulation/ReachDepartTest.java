package forge.ai.simulation;

import java.util.Arrays;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * solver-distill-cc-1109: the reach departure rule of the anytime choice. A candidate whose value gain comes from a line
 * the planner found in one or two of eight worlds fails the z test (the gain is real but its spread is large); with the
 * rule it replaces the default when its reach part is never below the default's and above it on average.
 */
public class ReachDepartTest {

    private static boolean[][] all(final int n, final int k) {
        final boolean[][] f = new boolean[n][k];
        for (boolean[] x : f) {
            Arrays.fill(x, true);
        }
        return f;
    }

    @Test
    public void aLineInTwoWorldsDeparts() {
        final int n = 2, k = 8;
        final double[][] v = new double[n][k], rp = new double[n][k];
        for (int w = 0; w < k; w++) {
            v[0][w] = 0.02;
            v[1][w] = w < 2 ? 0.85 : 0.02;
            rp[1][w] = w < 2 ? 0.85 : 0.0;
        }
        final boolean[] ok = {true, true};
        Assert.assertEquals(LookaheadSearch.chooseAnytime(v, all(n, k), ok, n, k, 1.645, 0.0, Double.NaN), 0, "the z test alone keeps the default");
        Assert.assertEquals(LookaheadSearch.chooseAnytime(v, all(n, k), ok, n, k, 1.645, 0.0, Double.NaN, rp, 0.04, null), 1);
        Assert.assertEquals(LookaheadSearch.chooseAnytime(v, all(n, k), ok, n, k, 1.645, 0.0, Double.NaN, rp, 0.0, null), 0, "depart 0 = off");
        Assert.assertEquals(LookaheadSearch.chooseAnytime(v, all(n, k), ok, n, k, 1.645, 0.0, Double.NaN, rp, 0.5, null), 0, "below the threshold");
    }

    @Test
    public void lessReachInAWorldDoesNot() {
        final int n = 2, k = 8;
        final double[][] v = new double[n][k], rp = new double[n][k];
        for (int w = 0; w < k; w++) {
            v[0][w] = w == 5 ? 0.85 : 0.02;
            rp[0][w] = w == 5 ? 0.85 : 0.0;
            v[1][w] = w < 3 ? 0.85 : 0.02;
            rp[1][w] = w < 3 ? 0.85 : 0.0;
        }
        final boolean[] ok = {true, true};
        Assert.assertFalse(LookaheadSearch.reachExempt(rp, all(n, k), 1, k, 0.04), "the default has a line the candidate lacks");
        Assert.assertEquals(LookaheadSearch.chooseAnytime(v, all(n, k), ok, n, k, 1.645, 0.0, Double.NaN, rp, 0.04, null), 0);
    }

    @Test
    public void aThinSampleStillDeparts() {
        final int n = 2, k = 8;
        final double[][] v = new double[n][k], rp = new double[n][k];
        final boolean[][] f = new boolean[n][k];
        for (int w = 0; w < 3; w++) {
            f[0][w] = true;
            f[1][w] = true;
            v[0][w] = 0.02;
            v[1][w] = w == 0 ? 0.85 : 0.02;
            rp[1][w] = w == 0 ? 0.85 : 0.0;
        }
        final boolean[] ok = {true, true};
        Assert.assertEquals(LookaheadSearch.chooseAnytime(v, f, ok, n, k, 1.645, 0.0, Double.NaN), 0);
        Assert.assertEquals(LookaheadSearch.chooseAnytime(v, f, ok, n, k, 1.645, 0.0, Double.NaN, rp, 0.04, null), 1);
    }
}
