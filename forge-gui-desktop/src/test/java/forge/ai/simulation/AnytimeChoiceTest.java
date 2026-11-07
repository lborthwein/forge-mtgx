package forge.ai.simulation;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.Test;

/** unified-search-1106: the anytime comparison. */
public class AnytimeChoiceTest {
    private static boolean[][] all(int n, int k) {
        final boolean[][] f = new boolean[n][k];
        for (boolean[] r : f) {
            java.util.Arrays.fill(r, true);
        }
        return f;
    }

    @Test
    public void lineAgainstDefaultInTheSameWorlds() {
        final double[][] v = {{.89, .90, .89, .88}, {1, 1, 1, 1}};
        assertEquals(LookaheadSearch.chooseAnytime(v, all(2, 4), new boolean[] {true, true}, 2, 4, 1.645, 0, Double.NaN), 1);
    }

    @Test
    public void noisyGainStaysOnTheDefault() {
        final double[][] v = {{.5, .9, .1, .6}, {.9, .2, .9, .3}};
        assertEquals(LookaheadSearch.chooseAnytime(v, all(2, 4), new boolean[] {true, true}, 2, 4, 1.645, 0, Double.NaN), 0);
    }

    @Test
    public void failedDefaultDoesNotEndTheSearch() {
        final boolean[][] f = all(2, 4);
        java.util.Arrays.fill(f[0], false);
        final double[][] v = {{0, 0, 0, 0}, {1, 1, 1, 1}};
        assertEquals(LookaheadSearch.chooseAnytime(v, f, new boolean[] {false, true}, 2, 4, 1.645, 0, .5), 1);
        assertEquals(LookaheadSearch.chooseAnytime(v, f, new boolean[] {false, true}, 2, 4, 1.645, 0, Double.NaN), 0);
    }

    @Test
    public void thinSampleCarriesThePooledUncertainty() {
        final boolean[][] f = all(3, 4);
        f[2] = new boolean[] {true, false, false, false};
        final double[][] v = {{.5, .5, .5, .5}, {.9, .1, .9, .1}, {.6, 0, 0, 0}};
        assertEquals(LookaheadSearch.chooseAnytime(v, f, new boolean[] {true, true, true}, 3, 4, 1.645, 0, Double.NaN), 0);
    }
}
