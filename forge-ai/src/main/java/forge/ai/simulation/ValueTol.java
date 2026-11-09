package forge.ai.simulation;

import java.util.Arrays;

/**
 * deadband-1108: a value dead-band for every search comparison that steers play ({@code -Dforge.search.valueTol=EPS},
 * off by default: EPS 0).
 *
 * <p>Why: the value and policy services infer in float32 on the CPU. The same position gives values that differ by about
 * 1e-7 between CPU classes (Zen 3 AVX2 vs Zen 4 AVX-512) and with the batch a row is computed in on one host. Search
 * compares those values directly, so a near-tie (two candidates whose play-outs reach the same position, every world
 * tied) is decided by the noise: the argmax, the departure z gate with zero spread, prior orderings and the policy's
 * greedy pick in play-outs flip from host to host, and a game seed plays a different game.
 *
 * <p>What: with EPS &gt; 0 a difference of at most EPS counts as none. "Better" means better by more than EPS
 * ({@link #gt}); a positive gain must exceed EPS ({@link #pos}); among values within EPS of the best the lower index wins
 * ({@link #argmaxLow}); a descending order puts values whose sorted gaps are all within EPS into one run, ordered by index
 * ({@link #orderDesc}). A comparison then changes only when a real difference sits within the noise of EPS, which is rare
 * for a continuous difference, instead of at every exact tie, which is common. The rule knows no cards, combos or
 * answers: it reads numbers only.
 *
 * <p>With EPS 0 every method is the plain comparison it replaces, bit for bit (a &gt; b + 0.0 is a &gt; b; the orders are
 * the old comparators' orders), so the key off changes nothing.
 */
public final class ValueTol {
    public static final String KEY = "forge.search.valueTol";

    private static volatile double eps = parse(System.getProperty(KEY));

    private ValueTol() {
    }

    static double parse(String s) {
        if (s == null || s.isBlank()) {
            return 0.0;
        }
        final double e;
        try {
            e = Double.parseDouble(s.trim());
        } catch (NumberFormatException x) {
            throw new IllegalArgumentException(KEY + ": not a number: " + s);
        }
        if (!(e >= 0.0) || e > 0.01 || Double.isInfinite(e)) {
            throw new IllegalArgumentException(KEY + " must be in [0, 0.01], not " + s);
        }
        return e;
    }

    /** The dead-band EPS (0 = off). */
    public static double eps() {
        return eps;
    }

    public static boolean on() {
        return eps > 0.0;
    }

    /** Tests only: set EPS (returns the previous value). */
    static double setForTest(double e) {
        final double prev = eps;
        eps = e;
        return prev;
    }

    /** a better than b by more than EPS (EPS 0: a &gt; b). False when either is NaN. */
    public static boolean gt(double a, double b) {
        return a > b + eps;
    }

    /** A gain above the band (EPS 0: x &gt; 0). */
    public static boolean pos(double x) {
        return x > eps;
    }

    /**
     * The lowest index whose value is within EPS of the largest (EPS 0: the first maximum, i.e. ties to the lower index);
     * NaN entries never win; -1 when every entry is NaN or the array is empty.
     */
    public static int argmaxLow(double[] v) {
        double max = Double.NEGATIVE_INFINITY;
        int any = -1;
        for (int i = 0; i < v.length; i++) {
            if (!Double.isNaN(v[i]) && (any < 0 || v[i] > max)) {
                max = v[i];
                any = i;
            }
        }
        if (any < 0) {
            return -1;
        }
        for (int i = 0; i < v.length; i++) {
            if (!Double.isNaN(v[i]) && !(v[i] < max - eps)) {
                return i;
            }
        }
        return any;
    }

    /** As {@link #argmaxLow} for float probabilities (the policy's scores). */
    public static int argmaxLow(float[] v) {
        final double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i];
        }
        return argmaxLow(d);
    }

    /**
     * Indices by value, largest first. EPS 0: exactly the order of {@code Double.compare(v[y], v[x])} then the index
     * (NaN first, as that comparator puts it). EPS &gt; 0: that order, then each maximal run of neighbours whose gaps are
     * all at most EPS is put in index order. A run is cut where a gap exceeds EPS or is not a number (NaN, inf - inf).
     */
    public static int[] orderDesc(double[] v) {
        final Integer[] o = new Integer[v.length];
        for (int i = 0; i < o.length; i++) {
            o[i] = i;
        }
        Arrays.sort(o, (x, y) -> {
            final int c = Double.compare(v[y], v[x]);
            return c != 0 ? c : Integer.compare(x, y);
        });
        final int[] out = new int[o.length];
        for (int i = 0; i < o.length; i++) {
            out[i] = o[i];
        }
        final double e = eps;
        if (!(e > 0.0)) {
            return out;
        }
        int s = 0;
        for (int i = 1; i <= out.length; i++) {
            if (i == out.length || !(v[out[i - 1]] - v[out[i]] <= e)) {
                if (i - s > 1) {
                    Arrays.sort(out, s, i);
                }
                s = i;
            }
        }
        return out;
    }

    /** {@link #orderDesc} of {@code Double} values, a null counted as NaN. */
    public static int[] orderDesc(Double[] v) {
        final double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i] == null ? Double.NaN : v[i];
        }
        return orderDesc(d);
    }
}
