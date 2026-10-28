package forge.ai.simulation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.player.Player;
import forge.item.PaperCard;

/**
 * belief-jar-1027 (owner, 2026-10-27: "get good at guessing much better than chance based on the cards we've seen in our
 * opponent's deck so far"): the opponent-deck posterior of lane opponent-belief-1027, as a pinned jar resource
 * ({@code mtgx-belief-posterior/1}). Look-ahead option {@code belief=posterior}.
 *
 * <p>The model is the lane's human stack with its linear features only: for a card {@code c} of the 540-card mush
 * vocabulary and the set {@code S} of the opponent's nonbasic cards the seat has seen,
 * <pre>
 *   z(c) = v[c] + sum_{s in S} M[s][c] + wMax * log1p(gain * max_s cd[s][c]^2) + wMean * mean_s cd[s][c]   (S not empty)
 *   z(c) = v[c]                                                                                       (S empty)
 * </pre>
 * where {@code v} = the static popularity terms (bot drafts, 17Lands drafts, is-land) + {@code wPop} x the human decks'
 * popularity logit, {@code M} = the static pair terms (bot and 17Lands PMI) + {@code wPmi} x the human decks' PMI (built
 * here from the resource's human co-occurrence and popularity COUNTS), and {@code cd} = the jar's co-draft table
 * (defend-prior-cobra-mush.json, sha-pinned). A world's nonbasic cards are a conditional Bernoulli draw with odds weights
 * {@code exp(z)}, so constant terms (the lane's k/10 and bias features) do not matter. A card outside the vocabulary gets
 * {@link #unknownLogit} and no pair terms; a shown card outside it is no evidence.
 *
 * <p>HONESTY, by LINEAGE (owner ruling 2026-10-27: "the cube is only so big, and lots of archetype decks in a given color
 * have a lot of overlap with one another. Memorizing a deck may well be closer to the truth than not"): the posterior is
 * fitted on past decks only, and its {@code manifest} lists every training deck's LINEAGE: salted hashes of the tokens
 * {@code draft:<draft id>}, {@code pod:<pod id>}, {@code deck:<saved deck id>} (its edit history included),
 * {@code game:<recorded game id>}, plus each training deck's exact nonbasic list (the same list is the same deck).
 * {@link #refusal} refuses a game for the MODELLED OPPONENT only: when one of the lineage tokens the host names for their
 * deck (draft, pod, saved deck, match) or their exact list is in the manifest. A merely similar deck from another draft
 * is allowed: cross-draft archetype overlap is legitimate knowledge.
 *
 * <p>Review S2-1: {@link #mixUniform} is the share of worlds drawn uniformly (a world-level mixture), so the posterior
 * never rules a true card out. Review S2-2: a pool the vocabulary covers below {@link #MIN_COVERAGE} gets no posterior.
 */
public final class BeliefPosterior {
    public static final String SCHEMA = "mtgx-belief-posterior/1";
    /** The jar's posterior: fitted on all 110 human decks of the round-2 corpus, static parts on mush TRAIN pods + 17Lands fold 0. */
    public static final String LIVE = "classpath:/forge/ai/simulation/belief-posterior-human-mush.json";
    public static final String LIVE_SHA256 = "fc0be24802586085bc276051415ea51385b0af4100f9f69b502fa457864a1437";
    /** Review S2-2: the share of a pool's nonbasic cards the vocabulary must cover for the posterior to apply. */
    public static final double MIN_COVERAGE = 0.95;

    public final String sha256;
    public final String name;
    public final List<String> vocab;
    /** Mean basics per deck card of the human training decks (the two-stage basics' default deck share). */
    public final double basicsShare;
    public final double unknownLogit;
    /** Review S2-1: the share of worlds drawn uniformly instead of from the posterior (0 = none). */
    public final double mixUniform;
    /** The manifest's canary (a synthetic entry, never a training deck), for tests: its deck list and lineage tokens. */
    public final List<String> canaryDeck;
    public final List<String> canaryLineage;
    public final int manifestDecks;
    public final int manifestGroups;
    private final Map<String, Integer> index = new HashMap<>();
    private final double[] v;
    private final double[][] m;
    private final double[][] cd;
    private final double gain;
    private final double wMax;
    private final double wMean;
    private final Set<String> deckHashes = new HashSet<>();
    private final Set<String> groupHashes = new HashSet<>();

    private static final Map<String, BeliefPosterior> LOADED = new HashMap<>();

    /** Load (once per JVM and path) and check the sha256 pin (required). Throws {@link IllegalStateException}. */
    public static synchronized BeliefPosterior load(final String path, final String expectedSha256) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("belief=posterior needs beliefPosterior (the posterior resource)");
        }
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("belief=posterior needs beliefPosteriorSha256 (64 hex), not " + expectedSha256);
        }
        final BeliefPosterior have = LOADED.get(path);
        if (have != null) {
            if (!expectedSha256.equals(have.sha256)) {
                throw new IllegalStateException("belief posterior " + path + " has sha256 " + have.sha256 + ", pinned " + expectedSha256);
            }
            return have;
        }
        final byte[] bytes;
        try {
            bytes = BeliefSampler.readCube(path);
        } catch (Exception e) {
            throw new IllegalStateException("belief posterior " + path + ": " + e, e);
        }
        final String sha = BeliefSampler.hex(BeliefSampler.sha256(bytes));
        if (!expectedSha256.equals(sha)) {
            throw new IllegalStateException("belief posterior " + path + " has sha256 " + sha + ", pinned " + expectedSha256);
        }
        final BeliefPosterior p = new BeliefPosterior(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(), sha);
        LOADED.put(path, p);
        return p;
    }

    BeliefPosterior(final JsonObject o, final String sha256) {
        if (!o.has("schema") || !SCHEMA.equals(o.get("schema").getAsString())) {
            throw new IllegalStateException("belief posterior: schema must be " + SCHEMA);
        }
        this.sha256 = sha256;
        this.name = o.has("name") ? o.get("name").getAsString() : "?";
        final List<String> voc = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("vocab")) {
            voc.add(e.getAsString());
        }
        this.vocab = Collections.unmodifiableList(voc);
        final int n = voc.size();
        for (int i = 0; i < n; i++) {
            index.putIfAbsent(canon(voc.get(i)), i);
        }
        this.basicsShare = o.get("basicsShare").getAsDouble();
        this.unknownLogit = o.get("unknownLogit").getAsDouble();
        this.mixUniform = o.has("mixUniform") && !o.get("mixUniform").isJsonNull() ? o.get("mixUniform").getAsDouble() : 0.0;
        if (!(mixUniform >= 0 && mixUniform < 1)) {
            throw new IllegalStateException("belief posterior: mixUniform must be in [0, 1), not " + mixUniform);
        }
        // static parts
        final JsonObject st = o.getAsJsonObject("static");
        final JsonArray base = st.getAsJsonArray("base");
        if (base.size() != n) {
            throw new IllegalStateException("belief posterior: static.base has " + base.size() + " entries, vocab " + n);
        }
        this.v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = base.get(i).getAsDouble();
        }
        final ByteBuffer bb = ByteBuffer.wrap(Base64.getDecoder().decode(st.get("pairs_f32le_b64").getAsString()))
                .order(ByteOrder.LITTLE_ENDIAN);
        if (bb.remaining() != 4 * n * n) {
            throw new IllegalStateException("belief posterior: static.pairs holds " + bb.remaining() / 4 + " floats, want " + n * n);
        }
        this.m = new double[n][n];
        for (int s = 0; s < n; s++) {
            for (int c = 0; c < n; c++) {
                m[s][c] = bb.getFloat();
            }
        }
        // the human part, from its counts: PMI(s, c) = log(((C_sc + lam p_c) / (n_s + lam)) / p_c), p = (n + 1) / (N + 2),
        // diagonal 0; popularity logit log(q / (1 - q)), q = (n + 0.5) / (N + 1)
        final JsonObject h = o.getAsJsonObject("human");
        final double bigN = h.get("N").getAsDouble();
        final double lam = h.get("lam").getAsDouble();
        final double wPmi = h.get("wPmi").getAsDouble();
        final double wPop = h.get("wPop").getAsDouble();
        final JsonArray cnt = h.getAsJsonArray("count");
        final double[] nc = new double[n];
        for (int i = 0; i < n; i++) {
            nc[i] = cnt.get(i).getAsDouble();
        }
        final double[][] co = new double[n][n];
        final JsonArray pr = h.getAsJsonArray("pairs");
        for (int j = 0; j + 2 < pr.size(); j += 3) {
            final int a = pr.get(j).getAsInt(), b = pr.get(j + 1).getAsInt();
            co[a][b] = co[b][a] = pr.get(j + 2).getAsDouble();
        }
        final double[] p = new double[n];
        for (int i = 0; i < n; i++) {
            p[i] = (nc[i] + 1) / (bigN + 2);
            final double q = (nc[i] + 0.5) / (bigN + 1);
            v[i] += wPop * Math.log(q / (1 - q));
        }
        for (int s = 0; s < n; s++) {
            for (int c = 0; c < n; c++) {
                if (s != c) {
                    m[s][c] += wPmi * Math.log(((co[s][c] + lam * p[c]) / (nc[s] + lam)) / p[c]);
                }
            }
        }
        // the co-draft table (exact vocabulary names, as the lane's JarCoDraft)
        final JsonObject cdj = o.getAsJsonObject("codraft");
        this.gain = cdj.get("gain").getAsDouble();
        this.wMax = cdj.get("wMax").getAsDouble();
        this.wMean = cdj.get("wMean").getAsDouble();
        this.cd = new double[n][n];
        final String cdPath = cdj.get("resource").getAsString();
        final String cdSha = cdj.get("sha256").getAsString();
        final byte[] cdBytes;
        try {
            cdBytes = BeliefSampler.readCube(cdPath);
        } catch (Exception e) {
            throw new IllegalStateException("belief posterior: co-draft table " + cdPath + ": " + e, e);
        }
        final String gotCd = BeliefSampler.hex(BeliefSampler.sha256(cdBytes));
        if (!cdSha.equals(gotCd)) {
            throw new IllegalStateException("belief posterior: co-draft table " + cdPath + " has sha256 " + gotCd + ", pinned " + cdSha);
        }
        final Map<String, Integer> exact = new HashMap<>();
        for (int i = 0; i < n; i++) {
            exact.putIfAbsent(voc.get(i), i);
        }
        final JsonObject syn = JsonParser.parseString(new String(cdBytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("syn");
        for (Map.Entry<String, JsonElement> e : syn.entrySet()) {
            final Integer a = exact.get(e.getKey());
            if (a == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet()) {
                final Integer c = exact.get(f.getKey());
                if (c != null) {
                    cd[a][c] = f.getValue().getAsDouble();
                }
            }
        }
        // the training manifest
        final JsonObject man = o.getAsJsonObject("manifest");
        for (Map.Entry<String, JsonElement> e : man.getAsJsonObject("decks").entrySet()) {
            for (JsonElement x : e.getValue().getAsJsonArray()) {
                deckHashes.add(x.getAsString());
            }
        }
        for (Map.Entry<String, JsonElement> e : man.getAsJsonObject("groups").entrySet()) {
            for (JsonElement x : e.getValue().getAsJsonArray()) {
                groupHashes.add(x.getAsString());
            }
        }
        this.manifestDecks = deckHashes.size();
        this.manifestGroups = groupHashes.size();
        final List<String> cd = new ArrayList<>(), cl = new ArrayList<>();
        if (man.has("canary") && man.get("canary").isJsonObject()) {
            for (JsonElement x : man.getAsJsonObject("canary").getAsJsonArray("deck")) {
                cd.add(x.getAsString());
            }
            for (JsonElement x : man.getAsJsonObject("canary").getAsJsonArray("lineage")) {
                cl.add(x.getAsString());
            }
        }
        this.canaryDeck = Collections.unmodifiableList(cd);
        this.canaryLineage = Collections.unmodifiableList(cl);
    }

    /** Review S2-2: the share of the given nonbasic names the vocabulary covers (1 for an empty list). */
    public double coverage(final Collection<String> names) {
        int n = 0, in = 0;
        for (String x : names) {
            if (x == null || BeliefSampler.isBasic(x)) {
                continue;
            }
            n++;
            if (indexOf(x) >= 0) {
                in++;
            }
        }
        return n == 0 ? 1.0 : (double) in / n;
    }

    /** A card name's front face (an alias or split-card spelling matches it). */
    static String canon(final String name) {
        final int i = name.indexOf(" // ");
        return (i >= 0 ? name.substring(0, i) : name).trim();
    }

    /** The vocabulary index of a (Forge or cube) card name, or -1. */
    public int indexOf(final String name) {
        final Integer i = name == null ? null : index.get(canon(name));
        return i == null ? -1 : i;
    }

    /** The posterior logit of every vocabulary card given the shown names (unique vocabulary cards; others ignored). */
    public double[] vocabLogits(final Collection<String> shown) {
        final int n = vocab.size();
        final LinkedHashSet<Integer> s = new LinkedHashSet<>();
        if (shown != null) {
            for (String x : shown) {
                final int i = indexOf(x);
                if (i >= 0) {
                    s.add(i);
                }
            }
        }
        final double[] z = v.clone();
        if (s.isEmpty()) {
            return z;
        }
        final double[] mx = new double[n];
        final double[] mean = new double[n];
        for (int a : s) {
            final double[] row = m[a];
            final double[] crow = cd[a];
            for (int c = 0; c < n; c++) {
                z[c] += row[c];
                if (crow[c] > mx[c]) {
                    mx[c] = crow[c];
                }
                mean[c] += crow[c];
            }
        }
        for (int c = 0; c < n; c++) {
            z[c] += wMax * Math.log1p(gain * mx[c] * mx[c]) + wMean * mean[c] / s.size();
        }
        return z;
    }

    /** The logit of each pool name (a basic land gets NaN: the posterior weighs nonbasic cards only). */
    public double[] logits(final List<String> pool, final Collection<String> shown) {
        final double[] zv = vocabLogits(shown);
        final double[] out = new double[pool.size()];
        for (int i = 0; i < out.length; i++) {
            final String nm = pool.get(i);
            if (BeliefSampler.isBasic(nm)) {
                out[i] = Double.NaN;
                continue;
            }
            final int k = indexOf(nm);
            out[i] = k >= 0 ? zv[k] : unknownLogit;
        }
        return out;
    }

    /**
     * The training-manifest hash of a deck: sha256 over its nonbasic card names (front face, sorted, one per line, a
     * name repeated per copy), first 16 hex. The resource builder computes the same.
     */
    public static String deckHash(final Iterable<String> names) {
        final List<String> nb = new ArrayList<>();
        for (String x : names) {
            if (x != null && !BeliefSampler.isBasic(x.trim())) {
                nb.add(canon(x));
            }
        }
        Collections.sort(nb);
        return BeliefSampler.hex(BeliefSampler.sha256(String.join("\n", nb).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    /** The training-manifest hash of a group id (a pod, draft or game id). */
    public static String groupHash(final String group) {
        return BeliefSampler.hex(BeliefSampler.sha256(("mtgx-belief-group|" + group).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }

    /** The main deck of a registered player as card names (one per copy), or null. */
    public static List<String> mainDeckNames(final Player p) {
        final Deck deck = p.getRegisteredPlayer() == null ? null : p.getRegisteredPlayer().getDeck();
        if (deck == null || !deck.has(DeckSection.Main)) {
            return null;
        }
        final List<String> out = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : deck.get(DeckSection.Main)) {
            for (int i = 0; i < e.getValue(); i++) {
                out.add(e.getKey().getName());
            }
        }
        return out;
    }

    /** Whether the manifest holds this deck hash / this group id. */
    public boolean hasDeck(final String hash) {
        return deckHashes.contains(hash);
    }

    public boolean hasGroup(final String group) {
        return groupHashes.contains(groupHash(group));
    }

    /**
     * Null when the game may use this posterior against {@code opp}; else why not (HONESTY by lineage): one of the
     * lineage tokens the host names for {@code opp}'s deck (draft, pod, saved deck, match) is in the training manifest, or
     * {@code opp}'s registered deck is exactly a training deck (the same nonbasic list is the same deck). Our own deck and
     * any merely similar deck are not reasons. A refusal names the token kind or "exact deck", never an id.
     */
    public String refusal(final Player opp, final Collection<String> lineage) {
        if (lineage != null) {
            for (String t : lineage) {
                if (t != null && hasGroup(t.trim())) {
                    final int c = t.indexOf(':');
                    return "the posterior's training manifest holds the modelled opponent's lineage ("
                            + (c > 0 ? t.substring(0, c) : "token") + ")";
                }
            }
        }
        final List<String> d = mainDeckNames(opp);
        if (d != null && hasDeck(deckHash(d))) {
            return "the posterior's training manifest holds the modelled opponent's exact deck";
        }
        return null;
    }
}
