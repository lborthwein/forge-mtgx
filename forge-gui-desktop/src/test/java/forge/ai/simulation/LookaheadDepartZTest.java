package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * The look-ahead's departure confidence (Config.departZ): a candidate replaces Forge's answer only when its mean
 * paired difference over the worlds exceeds z standard errors. Off (0) by default.
 */
public class LookaheadDepartZTest {

    private static double[][] vals(double[] forge, double[] cand) {
        return new double[][] {forge, cand};
    }

    @Test
    public void oneLuckyTerminalWorldIsNotConfident() {
        // The report's shape: the candidate costs 5 points in seven worlds and one play-out happens to end in a win.
        double[] f = {1000, 900, 950, 1100, 980, 1010, 930, 990};
        double[] c = {995, 895, 945, 1095, 975, 1005, 925, LookaheadSearch.TERMINAL};
        AssertJUnit.assertFalse(LookaheadSearch.confident(vals(f, c), 1, 8, 1.645));
        AssertJUnit.assertTrue("mean alone would depart", mean(c) > mean(f));
    }

    @Test
    public void consistentGainIsConfident() {
        double[] f = {1000, 900, 950, 1100, 980, 1010, 930, 990};
        double[] c = {1100, 1010, 1040, 1180, 1100, 1090, 1050, 1080};
        AssertJUnit.assertTrue(LookaheadSearch.confident(vals(f, c), 1, 8, 1.645));
    }

    @Test
    public void identicalPositiveDifferenceIsConfident() {
        double[] f = {1000, 900, 950};
        double[] c = {1005, 905, 955};
        AssertJUnit.assertTrue(LookaheadSearch.confident(vals(f, c), 1, 3, 1.645));
        AssertJUnit.assertFalse("a loss in every world is never confident",
                LookaheadSearch.confident(vals(c, f), 1, 3, 1.645));
    }

    @Test
    public void singleWorldNeedsOnlyAPositiveDifference() {
        AssertJUnit.assertTrue(LookaheadSearch.confident(vals(new double[] {1}, new double[] {2}), 1, 1, 1.645));
        AssertJUnit.assertFalse(LookaheadSearch.confident(vals(new double[] {1}, new double[] {1}), 1, 1, 1.645));
    }

    @Test
    public void offByDefault() {
        AssertJUnit.assertEquals(0.0, new LookaheadSearch.Config().departZ, 0.0);
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s / a.length;
    }
}
