package forge.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.GameState;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Position starts (lane sdcc-curric-1111): a TRAIN curriculum that begins a game from a generated mid-game position in
 * which one seat's route pieces are assembled (or one piece short), built from the two decks' OWN cards. OFF unless the
 * GAME message carries a {@code "start_state"} object; absent (or JSON null), {@link RlActorBench} never builds one:
 * the game type, the mulligans, the start hook and the tape are today's.
 *
 * <p>Spec ({@value #SCHEMA}; every key required, any other key refused):
 * <pre>
 * {"schema", "seat": 0 | 1,                  the assembled seat
 *  "route": "id", "pieces": ["Forge card name", ...],
 *  "place": ["auto" | "hand" | "battlefield" | "graveyard", ...]   one per piece; auto = a land to the battlefield,
 *                                                                  any other card to the hand
 *  "missing": [ints >= 0]     how many pieces sit on top of the library instead (uniform over the list)
 *  "lands": [lo, hi], "hand": [lo, hi], "gy": [lo, hi], "life": [lo, hi]          the assembled seat
 *  "opp_lands": [lo, hi], "opp_hand": [lo, hi], "opp_board": [lo, hi], "opp_gy": [lo, hi], "opp_life": [lo, hi]
 *  "active": p}               probability that the assembled seat is the active player (main phase 1)
 * </pre>
 * The spec holds data that sets a position up (a route's cards and where they start), as a deck list does; nothing
 * here is read by a seat, by the solver or by the featurizer, and the result reaches no observation.
 *
 * <p>How: the game is a {@code GameType.Puzzle} game (the engine then asks no mulligan); the start hook
 * ({@code Match.startGame(game, hook)}, run after the untap of turn 1) reads each seat's hand + library in their
 * shuffled order (so the game seed decides which lands and fillers a seat gets), deals them to zones with a dedicated
 * {@link Random} seeded from (game seed, {@link #SALT}) (never the game's random stream) and installs the result as a
 * Forge {@link GameState} (no enter-the-battlefield trigger, nothing tapped, no summoning sickness; a planeswalker has
 * its printed loyalty). Every card of both
 * decks stays in the game: zone multisets add up to the decks. Both seats then play on under their controllers; the
 * result is the game's own.
 *
 * <p>Not applied (the game then runs from turn 1 with its opening hands and no mulligan, and says why): a piece is
 * not in the seat's deck ("missing"), or the install threw ("error").
 */
public final class RlStartState {

    public static final String SCHEMA = "mtgx-rl-start/1";
    static final long SALT = 0x5354415254504f53L; // "STARTPOS"

    private static final Set<String> KEYS = new TreeSet<>(Arrays.asList("schema", "seat", "route", "pieces", "place",
            "missing", "lands", "hand", "gy", "life", "opp_lands", "opp_hand", "opp_board", "opp_gy", "opp_life",
            "active"));
    private static final Set<String> PLACES = new TreeSet<>(Arrays.asList("auto", "hand", "battlefield", "graveyard"));

    private final JsonObject spec;
    private final long seed;
    final int seat;
    final String route;
    final List<String> pieces = new ArrayList<>();
    final List<String> place = new ArrayList<>();
    final int[] missing;
    final int[] lands, hand, gy, life, oppLands, oppHand, oppBoard, oppGy, oppLife;
    final double active;
    private JsonObject started;

    private RlStartState(final JsonObject spec, final long seed) {
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
        seat = spec.get("seat").getAsInt();
        if (seat != 0 && seat != 1) {
            throw new IllegalArgumentException("seat " + seat + " is not 0 or 1");
        }
        route = spec.get("route").getAsString();
        for (JsonElement x : spec.getAsJsonArray("pieces")) {
            pieces.add(x.getAsString());
        }
        for (JsonElement x : spec.getAsJsonArray("place")) {
            if (!PLACES.contains(x.getAsString())) {
                throw new IllegalArgumentException("place '" + x.getAsString() + "' is not one of " + PLACES);
            }
            place.add(x.getAsString());
        }
        if (pieces.isEmpty() || place.size() != pieces.size()) {
            throw new IllegalArgumentException("pieces is empty or place has another length");
        }
        final JsonArray mj = spec.getAsJsonArray("missing");
        if (mj.isEmpty()) {
            throw new IllegalArgumentException("missing is empty");
        }
        missing = new int[mj.size()];
        for (int i = 0; i < missing.length; i++) {
            missing[i] = mj.get(i).getAsInt();
            if (missing[i] < 0) {
                throw new IllegalArgumentException("missing " + missing[i] + " < 0");
            }
        }
        lands = range("lands", 0);
        hand = range("hand", 0);
        gy = range("gy", 0);
        life = range("life", 1);
        oppLands = range("opp_lands", 0);
        oppHand = range("opp_hand", 0);
        oppBoard = range("opp_board", 0);
        oppGy = range("opp_gy", 0);
        oppLife = range("opp_life", 1);
        active = spec.get("active").getAsDouble();
        if (!(active >= 0.0 && active <= 1.0)) {
            throw new IllegalArgumentException("active " + active + " is not in [0, 1]");
        }
        started = new JsonObject();
        started.addProperty("applied", false);
        started.addProperty("reason", "not_reached"); // replaced when the hook runs
    }

    private int[] range(final String k, final int min) {
        final JsonArray a = spec.getAsJsonArray(k);
        if (a.size() != 2) {
            throw new IllegalArgumentException(k + " is not [lo, hi]");
        }
        final int lo = a.get(0).getAsInt();
        final int hi = a.get(1).getAsInt();
        if (lo < min || hi < lo) {
            throw new IllegalArgumentException(k + " [" + lo + ", " + hi + "] is not " + min + " <= lo <= hi");
        }
        return new int[] {lo, hi};
    }

    /** The GAME's start state, or null (off) when it has none. Throws IllegalArgumentException on a malformed spec. */
    public static RlStartState of(final JsonObject g, final long gameSeed) {
        if (!g.has("start_state") || g.get("start_state").isJsonNull()) {
            return null;
        }
        if (!g.get("start_state").isJsonObject()) {
            throw new IllegalArgumentException("start_state is not an object");
        }
        return new RlStartState(g.getAsJsonObject("start_state"), gameSeed);
    }

    /** The spec as played (a copy). */
    public JsonObject spec() {
        return spec.deepCopy();
    }

    /** What was installed: {applied, ...} (see {@link #install}). */
    public JsonObject started() {
        return started.deepCopy();
    }

    /** The start hook of this game: install the position on the game thread. Never throws. */
    public Runnable hook(final Game game) {
        return () -> {
            try {
                started = install(game);
            } catch (RuntimeException e) {
                final JsonObject r = new JsonObject();
                r.addProperty("applied", false);
                r.addProperty("reason", "error");
                r.addProperty("error", e.toString());
                started = r;
            }
        };
    }

    private static final class Now extends GameState {
        void now(final Game g) {
            applyGameOnThread(g);
        }
    }

    private static int draw(final Random r, final int[] range) {
        return range[0] + r.nextInt(range[1] - range[0] + 1);
    }

    private static String name(final Card c) {
        return c.getPaperCard() != null ? c.getPaperCard().getName() : c.getName();
    }

    private static boolean is(final Card c, final String piece) {
        if (piece.equals(c.getName()) || piece.equals(name(c))) {
            return true;
        }
        for (String face : name(c).split(" // ")) {
            if (piece.equals(face)) {
                return true;
            }
        }
        return false;
    }

    private static Card take(final List<Card> pool, final Predicate<Card> p) {
        for (int i = 0; i < pool.size(); i++) {
            if (p.test(pool.get(i))) {
                return pool.remove(i);
            }
        }
        return null;
    }

    private static List<Card> takeN(final List<Card> pool, final int n, final Predicate<Card> p) {
        final List<Card> out = new ArrayList<>();
        while (out.size() < n) {
            final Card c = take(pool, p);
            if (c == null) {
                break;
            }
            out.add(c);
        }
        return out;
    }

    private static String join(final List<Card> cards) {
        return join(cards, false);
    }

    /**
     * A zone's cards as GameState text. On the battlefield a planeswalker gets its printed loyalty (the install runs
     * no enters-the-battlefield effect, and a planeswalker without loyalty is put into the graveyard); other
     * enters-with counters are not given.
     */
    private static String join(final List<Card> cards, final boolean battlefield) {
        final StringBuilder b = new StringBuilder();
        for (Card c : cards) {
            if (b.length() > 0) {
                b.append(';');
            }
            b.append(name(c));
            if (battlefield && c.isPlaneswalker()) {
                final String l = c.getCurrentState().getBaseLoyalty();
                if (l != null && l.matches("\\d+")) {
                    b.append("|Counters:LOYALTY=").append(l);
                }
            }
        }
        return b.toString();
    }

    private static JsonArray names(final List<Card> cards) {
        final JsonArray a = new JsonArray();
        for (Card c : cards) {
            a.add(name(c));
        }
        return a;
    }

    /**
     * Deal both seats' cards to zones and install them. Returns {applied: true, seat, route, active (seat), turn,
     * missing: [names], placed: {piece: zone}, seats: [{lands, hand, board, gy, library, life}, x2]} or
     * {applied: false, reason: "missing", piece}.
     */
    JsonObject install(final Game game) {
        final Random r = new Random(RlSeeding.seatSeed(seed ^ SALT, 2));
        final List<Player> players = game.getRegisteredPlayers();
        final JsonObject out = new JsonObject();
        final StringBuilder st = new StringBuilder("[state]\n");
        final String[] who = {"human", "ai"};
        final JsonArray seatsOut = new JsonArray();
        final int[] nLands = new int[2];
        final String[] zoneText = new String[2];
        final JsonArray missingOut = new JsonArray();
        final JsonObject placed = new JsonObject();
        for (int s = 0; s < 2; s++) {
            final Player p = players.get(s);
            final List<Card> pool = new ArrayList<>(p.getCardsIn(ZoneType.Hand));
            pool.addAll(p.getCardsIn(ZoneType.Library));
            final List<Card> bf = new ArrayList<>();
            final List<Card> hd = new ArrayList<>();
            final List<Card> grave = new ArrayList<>();
            final List<Card> top = new ArrayList<>();
            final int lifeNow;
            if (s == seat) {
                final List<Card> found = new ArrayList<>();
                for (String piece : pieces) {
                    final Card c = take(pool, x -> is(x, piece));
                    if (c == null) {
                        out.addProperty("applied", false);
                        out.addProperty("reason", "missing");
                        out.addProperty("piece", piece);
                        return out;
                    }
                    found.add(c);
                }
                // the pieces left one step away: on top of the library (never a graveyard piece or a land)
                final List<Integer> movable = new ArrayList<>();
                for (int i = 0; i < found.size(); i++) {
                    if (!"graveyard".equals(place.get(i)) && !found.get(i).isLand()) {
                        movable.add(i);
                    }
                }
                final int nMissing = Math.min(missing[r.nextInt(missing.length)], movable.size());
                Collections.shuffle(movable, r);
                final Set<Integer> away = new TreeSet<>(movable.subList(0, nMissing));
                for (int i = 0; i < found.size(); i++) {
                    final Card c = found.get(i);
                    String z = place.get(i);
                    if ("auto".equals(z)) {
                        z = c.isLand() ? "battlefield" : "hand";
                    }
                    if (away.contains(i)) {
                        z = "library";
                        top.add(c);
                        missingOut.add(name(c));
                    } else if ("battlefield".equals(z)) {
                        bf.add(c);
                    } else if ("graveyard".equals(z)) {
                        grave.add(c);
                    } else {
                        hd.add(c);
                    }
                    placed.addProperty(pieces.get(i), z);
                }
                nLands[s] = draw(r, lands);
                bf.addAll(takeN(pool, nLands[s], Card::isLand));
                grave.addAll(takeN(pool, draw(r, gy), x -> !x.isLand()));
                hd.addAll(takeN(pool, Math.max(0, draw(r, hand) - hd.size()), x -> true));
                lifeNow = draw(r, life);
            } else {
                nLands[s] = draw(r, oppLands);
                bf.addAll(takeN(pool, nLands[s], Card::isLand));
                bf.addAll(takeN(pool, draw(r, oppBoard), Card::isCreature));
                grave.addAll(takeN(pool, draw(r, oppGy), x -> !x.isLand()));
                hd.addAll(takeN(pool, draw(r, oppHand), x -> true));
                lifeNow = draw(r, oppLife);
            }
            final List<Card> lib = new ArrayList<>(top);
            lib.addAll(pool);
            zoneText[s] = who[s] + "life=" + lifeNow + "\n" + who[s] + "landsplayed=0\n" + who[s] + "hand=" + join(hd)
                    + "\n" + who[s] + "battlefield=" + join(bf, true) + "\n" + who[s] + "graveyard=" + join(grave) + "\n"
                    + who[s] + "exile=\n" + who[s] + "library=" + join(lib) + "\n";
            final JsonObject so = new JsonObject();
            so.addProperty("lands", nLands[s]);
            so.add("hand", names(hd));
            so.add("battlefield", names(bf));
            so.add("graveyard", names(grave));
            so.addProperty("library", lib.size());
            so.addProperty("life", lifeNow);
            seatsOut.add(so);
        }
        final int act = r.nextDouble() < active ? seat : 1 - seat;
        final int turn = Math.max(1, 2 * nLands[act] - 1);
        st.append("turn=").append(turn).append("\nactiveplayer=").append(who[act]).append("\nactivephase=MAIN1\n");
        st.append(zoneText[0]).append(zoneText[1]);
        final Now gs = new Now();
        gs.parse(Arrays.asList(st.toString().split("\\R")));
        gs.now(game);
        out.addProperty("applied", true);
        out.addProperty("seat", seat);
        out.addProperty("route", route);
        out.addProperty("active", act);
        out.addProperty("turn", turn);
        out.add("missing", missingOut);
        out.add("placed", placed);
        out.add("seats", seatsOut);
        return out;
    }

    /** JSON null-safe accessor for the tape. */
    public static JsonElement orNull(final RlStartState s) {
        return s == null ? JsonNull.INSTANCE : s.started();
    }
}
