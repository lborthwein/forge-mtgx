package forge.ai.simulation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.StaticData;
import forge.item.PaperCard;

/**
 * Opponent-hand belief for the look-ahead's worlds (lane belief-sampling-0928, option {@code belief}).
 *
 * <p>The candidate pool is observation-only: the cube's cards (a pinned cube file) minus the searching seat's own
 * registered deck, minus every opponent card the seat can see (battlefield, graveyards, face-up exile, stack,
 * revealed hand or library cards), plus {@code M} copies of each basic land type. A world's hidden opponent hand is a
 * draw of exactly {@code n} pool slots from the conditional Bernoulli distribution P(S) proportional to prod w_i
 * (weights from the human-trained model, or all 1 = uniform); the hidden library is a uniform draw from the slots
 * left. Everything here is a pure function of (weights, n, the world's Random), so worlds are reproducible.
 */
public final class BeliefSampler {
    private BeliefSampler() {
    }

    public static final String[] BASICS = {"Plains", "Island", "Swamp", "Mountain", "Forest"};

    /** The pinned cube: Forge names in file order, their tags, the basic multiplicity M. */
    public static final class Cube {
        public final List<String> names = new ArrayList<>();
        public final Map<String, Set<String>> tags = new HashMap<>();
        public final Map<String, PaperCard> paper = new HashMap<>();
        public final List<String> unresolved = new ArrayList<>();
        public int basics = 8;
        public String sha256;
    }

    private static final Map<String, Cube> CUBES = new HashMap<>();

    /**
     * Load (once per JVM and path) the cube file {@code {"schema":"mtgx-belief-cube/1","cards":[{"name","tags"}]}},
     * check its sha256 pin, and resolve every name to a Forge paper card up front (so no card lookup happens inside
     * a game). Names Forge cannot resolve are dropped from the pool and listed in {@link Cube#unresolved}.
     */
    public static synchronized Cube loadCube(String path, String expectedSha256, int basics) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("belief needs beliefCube (the cube list file)");
        }
        final String key = path + "|" + basics;
        final Cube have = CUBES.get(key);
        if (have != null) {
            if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equals(have.sha256)) {
                throw new IllegalStateException("belief cube " + path + " has sha256 " + have.sha256 + ", pinned " + expectedSha256);
            }
            return have;
        }
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(path));
        } catch (Exception e) {
            throw new IllegalStateException("belief cube " + path + ": " + e, e);
        }
        final String sha = hex(sha256(bytes));
        if (expectedSha256 != null && !expectedSha256.isBlank() && !expectedSha256.equals(sha)) {
            throw new IllegalStateException("belief cube " + path + " has sha256 " + sha + ", pinned " + expectedSha256);
        }
        final JsonObject o = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        final Cube cube = new Cube();
        cube.sha256 = sha;
        cube.basics = basics;
        for (JsonElement e : o.getAsJsonArray("cards")) {
            final JsonObject c = e.getAsJsonObject();
            final String name = c.get("name").getAsString();
            if (isBasic(name)) {
                continue;
            }
            final java.util.TreeSet<String> t = new java.util.TreeSet<>();
            if (c.has("tags")) {
                for (JsonElement x : c.getAsJsonArray("tags")) {
                    t.add(x.getAsString());
                }
            }
            final PaperCard pc = resolve(name);
            if (pc == null) {
                cube.unresolved.add(name);
                continue;
            }
            cube.names.add(name);
            cube.tags.put(name, t);
            cube.paper.put(name, pc);
        }
        for (String b : BASICS) {
            final PaperCard pc = resolve(b);
            if (pc == null) {
                throw new IllegalStateException("belief: Forge cannot resolve basic land " + b);
            }
            cube.paper.put(b, pc);
            cube.tags.put(b, new java.util.TreeSet<>(List.of("land", "basic")));
        }
        CUBES.put(key, cube);
        return cube;
    }

    static PaperCard resolve(String name) {
        try {
            PaperCard pc = StaticData.instance().getCommonCards().getCard(name);
            if (pc == null && name.contains(" // ")) {
                pc = StaticData.instance().getCommonCards().getCard(name.substring(0, name.indexOf(" // ")));
            }
            return pc;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean isBasic(String name) {
        for (String b : BASICS) {
            if (b.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One decision's belief: the pool (unique names, cube order, basics last), each pool item's weight, the expanded
     * slots (a basic is M slots of one weight), the hidden hand size n and the suffix elementary symmetric
     * polynomials that make an exact sequential draw possible.
     */
    public static final class Dist {
        public final List<String> pool;
        public final double[] itemW;
        public final int n;
        public final int libHidden;
        public final String[] slotName;
        public final double[] w;
        /** S[i][r] = e_r(w[i..J-1]), r = 0..n. */
        final double[][] suffix;
        public boolean uniform;
        public boolean fallback;
        public int oppId;

        public Dist(List<String> pool, double[] itemW, int basics, int n, int libHidden) {
            this.pool = pool;
            this.itemW = itemW;
            this.n = n;
            this.libHidden = libHidden;
            final List<String> names = new ArrayList<>();
            final List<Double> ws = new ArrayList<>();
            for (int i = 0; i < pool.size(); i++) {
                final int m = isBasic(pool.get(i)) ? basics : 1;
                for (int j = 0; j < m; j++) {
                    names.add(pool.get(i));
                    ws.add(itemW[i]);
                }
            }
            this.slotName = names.toArray(new String[0]);
            this.w = new double[ws.size()];
            for (int i = 0; i < w.length; i++) {
                w[i] = ws.get(i);
            }
            this.suffix = suffix(w, n);
        }

        public int slots() {
            return w.length;
        }

        /** Draw exactly n slots (ascending slot order) from the conditional Bernoulli distribution. */
        public int[] sampleHand(Random rng) {
            final int[] out = new int[n];
            int r = n;
            int k = 0;
            for (int i = 0; i < w.length && r > 0; i++) {
                final double denom = suffix[i][r];
                final double p = denom <= 0 ? 0.0 : w[i] * suffix[i + 1][r - 1] / denom;
                final double u = rng.nextDouble();
                if (u < p || w.length - i == r) {
                    out[k++] = i;
                    r--;
                }
            }
            if (k != n) {
                throw new IllegalStateException("belief: drew " + k + " of " + n + " slots from " + w.length);
            }
            return out;
        }

        /** The slots not in the hand, uniformly shuffled; the first libHidden are the hidden library (top first). */
        public List<Integer> library(int[] hand, Random rng) {
            final boolean[] used = new boolean[w.length];
            for (int i : hand) {
                used[i] = true;
            }
            final List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < w.length; i++) {
                if (!used[i]) {
                    rest.add(i);
                }
            }
            Collections.shuffle(rest, rng);
            return rest.subList(0, Math.min(libHidden, rest.size()));
        }

        /** Inclusion probability of every slot: w_i e_{n-1}(w without i) / e_n(w). Sums to n. */
        public double[] marginals() {
            return BeliefSampler.marginals(w, n, suffix);
        }
    }

    /** S[i][r] = e_r(w[i..]) for r = 0..n (S[J][0] = 1). Weights must be positive and at most ~1. */
    static double[][] suffix(double[] w, int n) {
        final int J = w.length;
        final double[][] s = new double[J + 1][n + 1];
        s[J][0] = 1.0;
        for (int i = J - 1; i >= 0; i--) {
            s[i][0] = 1.0;
            for (int r = 1; r <= n; r++) {
                s[i][r] = s[i + 1][r] + w[i] * s[i + 1][r - 1];
            }
        }
        return s;
    }

    static double[] marginals(double[] w, int n, double[][] suffix) {
        final int J = w.length;
        final double[] out = new double[J];
        if (n == 0) {
            return out;
        }
        final double en = suffix[0][n];
        // prefix P[r] = e_r(w[0..i-1]) as i advances
        double[] pre = new double[n + 1];
        pre[0] = 1.0;
        for (int i = 0; i < J; i++) {
            double e = 0;
            for (int a = 0; a <= n - 1; a++) {
                e += pre[a] * suffix[i + 1][n - 1 - a];
            }
            out[i] = en <= 0 ? 0 : w[i] * e / en;
            final double[] nx = new double[n + 1];
            nx[0] = 1.0;
            for (int r = 1; r <= n; r++) {
                nx[r] = pre[r] + w[i] * pre[r - 1];
            }
            pre = nx;
        }
        return out;
    }

    /**
     * Log-weights -> positive weights in (0, 1]: exp(lw - max), floored at exp(-30) (a slot the model rules out keeps a
     * tiny weight, so every hand of the pool stays possible and the polynomials never underflow).
     */
    public static double[] weights(double[] lw) {
        double mx = Double.NEGATIVE_INFINITY;
        for (double x : lw) {
            mx = Math.max(mx, x);
        }
        final double[] out = new double[lw.length];
        for (int i = 0; i < lw.length; i++) {
            out[i] = Math.exp(Math.max(-30.0, lw[i] - mx));
        }
        return out;
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] d) {
        final StringBuilder sb = new StringBuilder();
        for (byte x : d) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /** Expected number of pool slots carrying a tag, from per-slot inclusion probabilities. */
    public static double expectedTagged(Dist d, double[] pi, Cube cube, String tag) {
        double s = 0;
        for (int i = 0; i < pi.length; i++) {
            final Set<String> t = cube.tags.get(d.slotName[i]);
            if (t != null && t.contains(tag)) {
                s += pi[i];
            }
        }
        return s;
    }

    /** Top-k pool items by inclusion probability (a basic's M slots summed), as "name:p". */
    public static JsonArray top(Dist d, double[] pi, int k) {
        final Map<String, Double> by = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pi.length; i++) {
            by.merge(d.slotName[i], pi[i], Double::sum);
        }
        final List<Map.Entry<String, Double>> es = new ArrayList<>(by.entrySet());
        es.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        final JsonArray out = new JsonArray();
        for (int i = 0; i < Math.min(k, es.size()); i++) {
            out.add(es.get(i).getKey() + ":" + Math.round(es.get(i).getValue() * 1000) / 1000.0);
        }
        return out;
    }
}
