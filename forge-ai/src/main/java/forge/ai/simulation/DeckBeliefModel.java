package forge.ai.simulation;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * belief-n11-1030 (need N11): a LEARNED opponent-deck model for the "deck" belief. Masked-card prediction over deck
 * multisets, trained offline on every available decklist (mtgx {@code tools/ml/deckbelief}); cards are represented by
 * their oracle text plus an id term, and no archetype, family or route enters the model.
 *
 * <p>Given the opponent's shown card instances S, the model's "bag" form is
 * {@code h = W2 relu(W1 [mean_{s in S} a(s), log(1+|S|)] + b1) + b2} and {@code p(c | S) = softmax_c (<h, u(c)> + b(c))}
 * over the candidate set (the belief pool minus shown and already-drawn non-basics; basics repeat). {@link #sample}
 * draws the hidden cards one at a time, each drawn card joining the context, so a world is a coherent deck (a world
 * that draws Doomsday is then likelier to hold Thassa's Oracle) and exactly {@code total} cards are drawn.
 *
 * <p>The export ({@code mtgx-deckbelief-export/1}) carries per-card vectors a, u, b already projected from text, so
 * the jar needs no text features. A pool card outside the export's vocabulary scores {@code min b} with u = 0 (it can
 * still be drawn, rarely); a shown card outside it adds nothing to the context.
 */
public final class DeckBeliefModel {
    public static final String SCHEMA = "mtgx-deckbelief-export/1";

    public final String sha256;
    final int d;
    final Map<String, Integer> index = new HashMap<>();
    /** review #1530 S1 ruling: the deck keys this export is held out (and lineage-held-out) from. */
    final Set<String> heldKeys = new HashSet<>();
    final float[][] a;
    final float[][] u;
    final float[] b;
    final float bMin;
    final float[][] w1;
    final float[] b1;
    final float[][] w2;
    final float[] b2;
    static final Set<String> BASICS = new HashSet<>(java.util.Arrays.asList(BeliefSampler.BASICS));

    DeckBeliefModel(JsonObject o, String sha256) {
        if (!o.has("schema") || !SCHEMA.equals(o.get("schema").getAsString())) {
            throw new IllegalArgumentException("deck belief: schema " + o.get("schema") + ", want " + SCHEMA);
        }
        if (!o.has("kind") || !"bag".equals(o.get("kind").getAsString())) {
            throw new IllegalArgumentException("deck belief: only kind=bag is implemented, not " + o.get("kind"));
        }
        checkProvenance(o);
        final JsonArray held = o.getAsJsonObject("source").getAsJsonArray("held_deck_keys");
        for (int i = 0; i < held.size(); i++) {
            heldKeys.add(held.get(i).getAsString());
        }
        this.sha256 = sha256;
        this.d = o.get("d").getAsInt();
        final JsonArray vocab = o.getAsJsonArray("vocab");
        for (int i = 0; i < vocab.size(); i++) {
            index.put(vocab.get(i).getAsString(), i);
        }
        a = mat(o.getAsJsonArray("a"));
        u = mat(o.getAsJsonArray("u"));
        b = vec(o.getAsJsonArray("b"));
        float m = Float.POSITIVE_INFINITY;
        for (float x : b) {
            m = Math.min(m, x);
        }
        bMin = m;
        w1 = mat(o.getAsJsonArray("w1"));
        b1 = vec(o.getAsJsonArray("b1"));
        w2 = mat(o.getAsJsonArray("w2"));
        b2 = vec(o.getAsJsonArray("b2"));
        if (a.length != vocab.size() || u.length != vocab.size() || b.length != vocab.size() || w1[0].length != d + 1
                || w2.length != d || w2[0].length != w1.length || b1.length != w1.length || b2.length != d) {
            throw new IllegalArgumentException("deck belief: inconsistent export shapes");
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i].length != d || u[i].length != d) {
                throw new IllegalArgumentException("deck belief: card vector " + i + " is not of width " + d);
            }
        }
        for (float[] r : w1) {
            if (r.length != d + 1) {
                throw new IllegalArgumentException("deck belief: w1 rows must be of width d + 1");
            }
        }
        for (float[] r : w2) {
            if (r.length != w1.length) {
                throw new IllegalArgumentException("deck belief: w2 rows must be of width " + w1.length);
            }
        }
    }

    static final String LIVE_HUMAN = "live/human";

    /**
     * Review #150 S1: an export must declare it was trained without the decks its seats face, nor their drafted parents.
     * Required in {@code source}: {@code mix} null (a bank mix is a hidden label), {@code near_jaccard} in (0, 0.5] and
     * a non-empty {@code near_banks} (the lineage holdout against the banks the seats face), and {@code excluded_banks}
     * containing every one of those banks. Anything else is refused.
     */
    static void checkProvenance(JsonObject o) {
        final JsonObject src = o.has("source") && o.get("source").isJsonObject() ? o.getAsJsonObject("source") : null;
        if (src == null) {
            throw new IllegalArgumentException("deck belief: the export has no source provenance");
        }
        // review #150 S2 (re-check): the mix must be declared null at the top level AND in the trainer's own args
        final JsonObject train = src.has("train") && src.get("train").isJsonObject() ? src.getAsJsonObject("train") : null;
        if (!src.has("mix") || !src.get("mix").isJsonNull() || train == null || !train.has("mix")
                || !train.get("mix").isJsonNull()) {
            throw new IllegalArgumentException("deck belief: the export must declare mix null (source.mix and "
                    + "source.train.mix); a bank mix is a hidden label");
        }
        final double j = src.has("near_jaccard") && !src.get("near_jaccard").isJsonNull()
                ? src.get("near_jaccard").getAsDouble() : 0;
        if (!(j > 0 && j <= 0.5)) {
            throw new IllegalArgumentException("deck belief: the export lacks the lineage holdout (near_jaccard " + j + ")");
        }
        final java.util.Set<String> near = new HashSet<>(), excl = new HashSet<>();
        if (src.has("near_banks") && src.get("near_banks").isJsonArray()) {
            src.getAsJsonArray("near_banks").forEach(x -> near.add(x.getAsString()));
        }
        if (src.has("excluded_banks") && src.get("excluded_banks").isJsonArray()) {
            src.getAsJsonArray("excluded_banks").forEach(x -> excl.add(x.getAsString()));
        }
        if (!src.has("held_deck_keys") || !src.get("held_deck_keys").isJsonArray()
                || src.getAsJsonArray("held_deck_keys").size() == 0) {
            throw new IllegalArgumentException("deck belief: the export lists no held-out deck keys (held_deck_keys)");
        }
        if (!near.contains(LIVE_HUMAN) || !excl.contains(LIVE_HUMAN)) {
            // review #150 S3 (re-check): the live human decks are excluded and lineage-held-out too
            throw new IllegalArgumentException("deck belief: the export must exclude and lineage-hold-out " + LIVE_HUMAN);
        }
        if (near.isEmpty() || !excl.containsAll(near)) {
            throw new IllegalArgumentException("deck belief: the export's faced banks are not both excluded and "
                    + "lineage-held-out: near " + near + ", excluded " + excl);
        }
    }

    /**
     * The deck key of a main-section multiset: sha256 of the lines "name TAB count" in name order, newline-joined (mtgx
     * deckbelief data.deck_key).
     */
    public static String deckKey(Map<String, Integer> main) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(main).entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException x) {
            throw new IllegalStateException(x);
        }
    }

    /**
     * review #1530 S1 ruling (the per-deck gate, on every load path: RlSearch, StepSearch, RlActorBench, harnesses): an
     * opponent's deck must be one this export was held out from, or the game is refused. Reads the registered decklist
     * for this check only, never for a draw.
     */
    public void requireHeld(forge.deck.Deck deck) {
        if (deck == null || !deck.has(forge.deck.DeckSection.Main)) {
            throw new IllegalStateException("deck belief: the opponent has no registered main deck to check");
        }
        final Map<String, Integer> main = new HashMap<>();
        for (Map.Entry<forge.item.PaperCard, Integer> e : deck.get(forge.deck.DeckSection.Main)) {
            main.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        final String k = deckKey(main);
        if (!heldKeys.contains(k)) {
            throw new IllegalStateException("deck belief: refused: the opponent's deck " + k.substring(0, 12)
                    + " is not one this export (" + sha256.substring(0, 12) + ") was held out from");
        }
    }

    private static float[][] mat(JsonArray j) {
        final float[][] m = new float[j.size()][];
        for (int i = 0; i < m.length; i++) {
            m[i] = vec(j.get(i).getAsJsonArray());
        }
        return m;
    }

    private static float[] vec(JsonArray j) {
        final float[] v = new float[j.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = j.get(i).getAsFloat();
        }
        return v;
    }

    private static final Map<String, DeckBeliefModel> CACHE = new HashMap<>();

    /** Load (once per path and pin) an export from a file or {@code classpath:} resource, refusing a sha256 mismatch. */
    public static synchronized DeckBeliefModel loadPinned(String path, String sha256) {
        final String key = path + "#" + sha256;
        final DeckBeliefModel hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        try {
            final byte[] bytes;
            if (path.startsWith("classpath:")) {
                try (InputStream in = DeckBeliefModel.class.getResourceAsStream(path.substring("classpath:".length()))) {
                    if (in == null) {
                        throw new IllegalArgumentException("deck belief: no resource " + path);
                    }
                    bytes = in.readAllBytes();
                }
            } else {
                bytes = Files.readAllBytes(Paths.get(path));
            }
            final String got = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!got.equals(sha256)) {
                throw new IllegalArgumentException("deck belief: " + path + " sha256 " + got + " != pin " + sha256);
            }
            final JsonObject o = JsonParser.parseReader(new InputStreamReader(new java.io.ByteArrayInputStream(bytes),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            final DeckBeliefModel m = new DeckBeliefModel(o, got);
            CACHE.put(key, m);
            return m;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("deck belief: cannot load " + path + ": " + e, e);
        }
    }

    private static String hex(byte[] h) {
        final StringBuilder sb = new StringBuilder();
        for (byte x : h) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /** The context vector h for the running sum of a-vectors over n context cards. */
    float[] context(float[] sum, int n) {
        final float[] in = new float[d + 1];
        for (int i = 0; i < d; i++) {
            in[i] = n == 0 ? 0f : sum[i] / n;
        }
        in[d] = (float) Math.log1p(n);
        final float[] hid = new float[w1.length];
        for (int j = 0; j < w1.length; j++) {
            float s = b1[j];
            final float[] row = w1[j];
            for (int i = 0; i <= d; i++) {
                s += row[i] * in[i];
            }
            hid[j] = Math.max(0f, s);
        }
        final float[] h = new float[d];
        for (int j = 0; j < d; j++) {
            float s = b2[j];
            final float[] row = w2[j];
            for (int i = 0; i < hid.length; i++) {
                s += row[i] * hid[i];
            }
            h[j] = s;
        }
        return h;
    }

    /** Score of card {@code name} under context h (an unknown name: min b). */
    double score(float[] h, String name) {
        final Integer i = index.get(name);
        return score(h, i == null ? -1 : i);
    }

    /** Score of vocabulary row i under context h (i < 0: an unknown name, min b). */
    double score(float[] h, int i) {
        if (i < 0) {
            return bMin;
        }
        double s = b[i];
        final float[] v = u[i];
        for (int k = 0; k < d; k++) {
            s += h[k] * v[k];
        }
        return s;
    }

    /**
     * Draw {@code total} hidden card names from the pool (names; basics may repeat, any other name at most once and
     * never one of {@code shown}), autoregressively given the shown card instances (names, with repeats for basics).
     * Basics are not capped (the other beliefs cap them at beliefBasics). A non-basic is never drawn twice, whatever
     * beliefSingleton says: the model is trained on near-singleton cube decks (review #150 S2-2, documented).
     */
    public List<String> sample(Collection<String> shown, List<String> pool, int total, Random rng) {
        final float[] sum = new float[d];
        int n = 0;
        final Set<String> used = new HashSet<>();
        for (String s : shown) {
            final Integer i = index.get(s);
            if (i != null) {
                add(sum, a[i]);
                n++;
            }
            if (!BASICS.contains(s)) {
                used.add(s);
            }
        }
        final List<String> cand = new ArrayList<>(new java.util.LinkedHashSet<>(pool));
        final int[] ci = new int[cand.size()];
        for (int c = 0; c < ci.length; c++) {
            final Integer i = index.get(cand.get(c));
            ci[c] = i == null ? -1 : i;
        }
        final double[] lg = new double[cand.size()];
        final List<String> out = new ArrayList<>(total);
        for (int t = 0; t < total; t++) {
            final float[] h = context(sum, n);
            double max = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < lg.length; c++) {
                final String nm = cand.get(c);
                lg[c] = used.contains(nm) ? Double.NEGATIVE_INFINITY : score(h, ci[c]);
                max = Math.max(max, lg[c]);
            }
            if (max == Double.NEGATIVE_INFINITY) {
                throw new IllegalStateException("deck belief: the pool ran out after " + t + " of " + total + " cards");
            }
            double z = 0;
            for (int c = 0; c < lg.length; c++) {
                lg[c] = Math.exp(lg[c] - max);
                z += lg[c];
            }
            double r = rng.nextDouble() * z;
            int pick = lg.length - 1;
            for (int c = 0; c < lg.length; c++) {
                r -= lg[c];
                if (r < 0 && lg[c] > 0) {
                    pick = c;
                    break;
                }
            }
            while (lg[pick] <= 0) {
                pick--; // rounding at the tail: the last card with mass
            }
            final String nm = cand.get(pick);
            out.add(nm);
            if (!BASICS.contains(nm)) {
                used.add(nm);
            }
            final Integer i = index.get(nm);
            if (i != null) {
                add(sum, a[i]);
                n++;
            }
        }
        return out;
    }

    private static void add(float[] s, float[] v) {
        for (int k = 0; k < s.length; k++) {
            s[k] += v[k];
        }
    }
}
