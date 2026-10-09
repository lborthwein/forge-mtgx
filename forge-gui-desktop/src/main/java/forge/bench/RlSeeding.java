package forge.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventGameStarted;
import forge.game.player.Player;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;

/**
 * G3 opening-hand seeding (lane cm-curriculum-1009; cm-design-1009 §2.1 item 3): a TRAIN curriculum that reorders a
 * seat's shuffled library so that some pieces of a route sit in its opening hand and the rest just below it. OFF unless
 * the GAME message carries a {@code "seeding"} object; absent (or JSON null), {@link RlActorBench} never builds one, so
 * nothing subscribes, nothing draws a random number and no tape key is written.
 *
 * <p>Spec ({@value #SCHEMA}): {@code {"schema", "p" (0..1), "k" (non-empty list of ints >= 0), "window" (int >= 0),
 * "seats": [null | {"route": "<id>", "pieces": ["<Forge card name>", ...]}, x2]}}; any other key is refused.
 *
 * <p>Per seat with a non-null entry, a dedicated {@link Random} seeded from (game seed, seat index, {@link #SALT})
 * draws, in order: {@code u = nextDouble()} (applied iff {@code u < p}); {@code k} = one value of the {@code k} list
 * (uniform), clipped to the number of pieces; a shuffle of the piece list; the hand positions; the window positions.
 * It never draws from the game's random stream ({@code MyRandom}). The first k shuffled pieces go to distinct random
 * positions in {@code [0, H)} (library top = index 0, H = the player's starting hand size), the others to distinct
 * random positions in {@code [H, H + window)}; every other card keeps its relative order, so the library's multiset is
 * unchanged. A piece is the first library card whose name or paper-card name equals it, else the first whose face name
 * (split half, adventure, back face) equals it. A seat is not seeded when a piece is missing ("missing"), when
 * {@code u >= p} ("p"), or when the hand or window has too few slots ("room").
 *
 * <p>When: on {@link GameEventGameStarted}, which {@code GameAction.startGame} fires synchronously after
 * {@code Match.prepareAllZones}' shuffle and before the opening hands are drawn. Applied once per game: a Karn restart
 * fires the event again and is left alone. Mulligans stay the seat's decision (a London mulligan reshuffles). No
 * curriculum information reaches the observation: the reorder fires no shuffle or reveal event, and nothing here is
 * visible to the seat or the featurizer.
 */
public final class RlSeeding {

    public static final String SCHEMA = "mtgx-rl-seeding/1";
    /** Mixed into every seat's seed so this stream never coincides with a stream seeded from the game seed alone. */
    static final long SALT = 0x47335f5345454431L; // "G3_SEED1"

    private static final Set<String> KEYS = new TreeSet<>(java.util.Arrays.asList("schema", "p", "k", "window",
            "seats"));
    private static final Set<String> SEAT_KEYS = new TreeSet<>(java.util.Arrays.asList("route", "pieces"));

    private final JsonObject spec;
    private final long seed;
    final double p;
    final int[] ks;
    final int window;
    final String[] routes = new String[2];
    final List<List<String>> pieces = new ArrayList<>();
    private final JsonElement[] seeded = new JsonElement[2];
    private Game game;
    private boolean done;

    private RlSeeding(final JsonObject spec, final long seed) {
        this.spec = spec.deepCopy();
        this.seed = seed;
        for (String k : spec.keySet()) {
            if (!KEYS.contains(k)) {
                throw new IllegalArgumentException("unknown key '" + k + "' (allowed " + KEYS + ")");
            }
        }
        for (String k : KEYS) {
            if (!spec.has(k) || spec.get(k).isJsonNull()) {
                throw new IllegalArgumentException("missing key '" + k + "'");
            }
        }
        if (!SCHEMA.equals(spec.get("schema").getAsString())) {
            throw new IllegalArgumentException("schema " + spec.get("schema") + " is not " + SCHEMA);
        }
        p = spec.get("p").getAsDouble();
        if (!(p >= 0.0 && p <= 1.0)) {
            throw new IllegalArgumentException("p " + p + " is not in [0, 1]");
        }
        final JsonArray kj = spec.getAsJsonArray("k");
        if (kj.isEmpty()) {
            throw new IllegalArgumentException("k is empty");
        }
        ks = new int[kj.size()];
        for (int i = 0; i < ks.length; i++) {
            ks[i] = kj.get(i).getAsInt();
            if (ks[i] < 0) {
                throw new IllegalArgumentException("k " + ks[i] + " < 0");
            }
        }
        window = spec.get("window").getAsInt();
        if (window < 0) {
            throw new IllegalArgumentException("window " + window + " < 0");
        }
        final JsonArray sj = spec.getAsJsonArray("seats");
        if (sj.size() != 2) {
            throw new IllegalArgumentException("seats has " + sj.size() + " entries, not 2");
        }
        for (int s = 0; s < 2; s++) {
            final JsonElement e = sj.get(s);
            if (e.isJsonNull()) {
                pieces.add(null);
                seeded[s] = JsonNull.INSTANCE;
                continue;
            }
            final JsonObject o = e.getAsJsonObject();
            for (String k : o.keySet()) {
                if (!SEAT_KEYS.contains(k)) {
                    throw new IllegalArgumentException("seat " + s + ": unknown key '" + k + "' (allowed " + SEAT_KEYS
                            + ")");
                }
            }
            if (!o.has("route") || !o.has("pieces")) {
                throw new IllegalArgumentException("seat " + s + " needs route and pieces");
            }
            routes[s] = o.get("route").getAsString();
            final List<String> l = new ArrayList<>();
            for (JsonElement x : o.getAsJsonArray("pieces")) {
                l.add(x.getAsString());
            }
            if (l.isEmpty()) {
                throw new IllegalArgumentException("seat " + s + ": pieces is empty");
            }
            pieces.add(Collections.unmodifiableList(l));
            final JsonObject r = new JsonObject();
            r.addProperty("route", routes[s]);
            r.addProperty("applied", false);
            r.addProperty("reason", "not_reached"); // replaced when the game starts
            seeded[s] = r;
        }
    }

    /**
     * The GAME's seeding, or null (off) when it has none. Throws IllegalArgumentException on a malformed spec (the
     * caller refuses the GAME).
     */
    public static RlSeeding of(final JsonObject g, final long gameSeed) {
        if (!g.has("seeding") || g.get("seeding").isJsonNull()) {
            return null;
        }
        if (!g.get("seeding").isJsonObject()) {
            throw new IllegalArgumentException("seeding is not an object");
        }
        return new RlSeeding(g.getAsJsonObject("seeding"), gameSeed);
    }

    /** Subscribe to this game's events (before {@code Match.startGame}). */
    public void attach(final Game g) {
        this.game = g;
        g.subscribeToEvents(this);
    }

    @Subscribe
    public void started(final GameEventGameStarted ev) {
        if (done) {
            return; // a restart (Karn) fires the event again: applied once per game
        }
        done = true;
        for (int s = 0; s < 2; s++) {
            if (pieces.get(s) == null) {
                continue;
            }
            try {
                seeded[s] = applySeat(game.getRegisteredPlayers().get(s), s);
            } catch (RuntimeException e) {
                final JsonObject r = new JsonObject();
                r.addProperty("route", routes[s]);
                r.addProperty("applied", false);
                r.addProperty("reason", "error");
                r.addProperty("error", e.toString());
                seeded[s] = r;
            }
        }
    }

    /** The spec as played (a copy). */
    public JsonObject spec() {
        return spec.deepCopy();
    }

    /** Per seat: null (no entry), or {route, applied, u, ...}; see {@link #applySeat}. */
    public JsonArray seeded() {
        final JsonArray a = new JsonArray();
        for (JsonElement e : seeded) {
            a.add(e.deepCopy());
        }
        return a;
    }

    /** The seat's dedicated stream's seed: SplitMix64 of the game seed, the seat index and {@link #SALT}. */
    static long seatSeed(final long gameSeed, final int seat) {
        long z = gameSeed ^ SALT ^ (0x9E3779B97F4A7C15L * (seat + 1));
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The outcome of {@link #plan}: the new library order, or why there is none. */
    static final class Plan {
        double u;
        int k = -1;
        String reason;                          // null when applied
        final List<String> missing = new ArrayList<>();
        final List<String> top = new ArrayList<>(), win = new ArrayList<>();
        final List<Integer> pos = new ArrayList<>();
        List<Card> order;                       // the new library (top = index 0), when applied
    }

    /**
     * The seat's plan over a library (top = index 0) with starting hand size {@code hand}. Draws only from its own
     * stream; reads the library, changes nothing.
     */
    static Plan plan(final List<Card> lib, final int hand, final List<String> want, final double p, final int[] ks,
            final int window, final long gameSeed, final int seat) {
        final Plan pl = new Plan();
        final Random r = new Random(seatSeed(gameSeed, seat));
        pl.u = r.nextDouble();
        final Map<Card, Boolean> taken = new IdentityHashMap<>();
        for (String name : want) {
            final Card c = locate(lib, name, taken);
            if (c == null) {
                pl.missing.add(name);
            } else {
                taken.put(c, Boolean.TRUE);
            }
        }
        if (!pl.missing.isEmpty()) {
            pl.reason = "missing";
            return pl;
        }
        if (!(pl.u < p)) {
            pl.reason = "p";
            return pl;
        }
        final List<String> order = new ArrayList<>(want);
        pl.k = Math.min(ks[r.nextInt(ks.length)], order.size());
        Collections.shuffle(order, r);
        final int handSlots = Math.min(hand, lib.size());
        final int winSlots = Math.max(0, Math.min(window, lib.size() - handSlots));
        final int rest = order.size() - pl.k;
        if (pl.k > handSlots || rest > winSlots) {
            pl.reason = "room";
            return pl;
        }
        final List<Integer> hp = new ArrayList<>(), wp = new ArrayList<>();
        for (int i = 0; i < handSlots; i++) hp.add(i);
        for (int i = 0; i < winSlots; i++) wp.add(handSlots + i);
        Collections.shuffle(hp, r);
        Collections.shuffle(wp, r);
        // the pieces in shuffled order, located again in that order (the same cards as above for distinct names)
        taken.clear();
        final Card[] out = new Card[lib.size()];
        for (int i = 0; i < order.size(); i++) {
            final Card c = locate(lib, order.get(i), taken);
            taken.put(c, Boolean.TRUE);
            final int at = i < pl.k ? hp.get(i) : wp.get(i - pl.k);
            out[at] = c;
            pl.pos.add(at);
            (i < pl.k ? pl.top : pl.win).add(order.get(i));
        }
        int j = 0;
        for (Card c : lib) {
            if (taken.containsKey(c)) {
                continue;
            }
            while (out[j] != null) j++;
            out[j++] = c;
        }
        pl.order = java.util.Arrays.asList(out);
        return pl;
    }

    /** The first library card named {@code name} (name or paper name), else the first with that face name; not taken. */
    static Card locate(final List<Card> lib, final String name, final Map<Card, Boolean> taken) {
        for (Card c : lib) {
            if (!taken.containsKey(c) && (name.equals(c.getName()) || (c.getPaperCard() != null
                    && name.equals(c.getPaperCard().getName())))) {
                return c;
            }
        }
        for (Card c : lib) {
            if (!taken.containsKey(c) && hasFace(c, name)) {
                return c;
            }
        }
        return null;
    }

    static boolean hasFace(final Card c, final String name) {
        for (CardStateName st : c.getStates()) {
            if (st != CardStateName.FaceDown && name.equals(c.getState(st).getName())) {
                return true;
            }
        }
        final String full = c.getPaperCard() != null ? c.getPaperCard().getName() : c.getName();
        for (String part : full.split(" // ")) {
            if (name.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private JsonObject applySeat(final Player pl, final int s) {
        final PlayerZone zone = pl.getZone(ZoneType.Library);
        final List<Card> lib = new ArrayList<>(zone.getCards(false));
        final int hand = pl.getStartingHandSize();
        final Plan plan = plan(lib, hand, pieces.get(s), p, ks, window, seed, s);
        final JsonObject r = new JsonObject();
        r.addProperty("route", routes[s]);
        r.addProperty("applied", plan.reason == null);
        r.addProperty("u", plan.u);
        if (plan.k >= 0) {
            r.addProperty("k", plan.k);
        }
        r.addProperty("hand_size", hand);
        if (plan.reason != null) {
            r.addProperty("reason", plan.reason);
            if (!plan.missing.isEmpty()) {
                final JsonArray m = new JsonArray();
                for (String x : plan.missing) m.add(x);
                r.add("missing", m);
            }
            return r;
        }
        zone.setCards(plan.order);
        final JsonArray top = new JsonArray(), win = new JsonArray(), pos = new JsonArray();
        for (String x : plan.top) top.add(x);
        for (String x : plan.win) win.add(x);
        for (int x : plan.pos) pos.add(x);
        r.add("top", top);
        r.add("window", win);
        r.add("pos", pos);
        return r;
    }
}
