package forge.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.ai.ComputerUtilMana;
import forge.ai.simulation.GameCopier;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameState;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
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
 *
 * <p><b>Castable starts</b> (lane sdcc-startfix-1113; ONE optional key, {@code "cast": ["Forge card name", ...]};
 * absent = everything above, byte for byte). The cards the assembled seat still has to CAST for its route (the
 * sender's list: e.g. a search spell, the pieces in hand, the pieces left in the library). Today's deal puts the
 * FIRST N lands of the shuffled library onto the battlefield, whatever their colours. With the key:
 * <ol>
 * <li>the deal as it is today is built on a COPY of the game (own ids, random stream and AI cache: the live game is
 * not touched) with the cast cards in hand and everything untapped, and FORGE'S OWN cost payment
 * ({@link ComputerUtilMana}, every way of casting the card that the engine offers) is asked whether each cast card can
 * be paid. Yes for all: the deal is kept ("dealt", check "now"); it is today's deal.</li>
 * <li>otherwise other choices of the same number of lands from the seat's library are tried: the earliest lands, in
 * library order, whose colours (the engine's own mana abilities; a land that fetches a land counts as the lands it
 * can fetch) cover the cast cards' coloured pips. That matching only CHOOSES; the engine's check judges every
 * choice. The best-judged choice is dealt (ties: the earlier one, the deal as it was first).</li>
 * <li>a choice whose only shortfall is the AMOUNT of mana (the engine pays the coloured part of every cast card's
 * cost) is dealt (check "colours"): later land drops are not modelled.</li>
 * <li>the start is NOT applied (reason "uncastable", with the card, its cost and every choice tried) only when the
 * engine cannot pay some cast card's coloured pips under any choice tried, not even with the lands of the dealt
 * hand in play as well (check "hand_lands"), and the dealt hand holds no other card that makes mana (check
 * "mana_in_hand": rituals and the like are not modelled, such a start is dealt).</li>
 * </ol>
 * Lands in the cast list are skipped. Costs, colours and what a permanent can produce are the engine's; no card is
 * named here. {@code started.cast} says what happened.
 */
public final class RlStartState {

    public static final String SCHEMA = "mtgx-rl-start/1";
    static final long SALT = 0x5354415254504f53L; // "STARTPOS"

    private static final Set<String> KEYS = new TreeSet<>(Arrays.asList("schema", "seat", "route", "pieces", "place",
            "missing", "lands", "hand", "gy", "life", "opp_lands", "opp_hand", "opp_board", "opp_gy", "opp_life",
            "active"));
    /** sdcc-startfix-1113: the one optional key (absent = off). */
    static final String CAST = "cast";
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
    /** sdcc-startfix-1113: the cards the assembled seat has to cast, or null (key absent: today's deal). */
    final List<String> cast;
    private JsonObject started;

    private RlStartState(final JsonObject spec, final long seed) {
        this.spec = spec.deepCopy();
        this.seed = seed;
        for (String k : spec.keySet()) {
            if (!KEYS.contains(k) && !CAST.equals(k)) {
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
        if (spec.has(CAST)) {
            if (!spec.get(CAST).isJsonArray()) {
                throw new IllegalArgumentException("cast is not a list of card names");
            }
            cast = new ArrayList<>();
            for (JsonElement x : spec.getAsJsonArray(CAST)) {
                if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isString() || x.getAsString().isEmpty()) {
                    throw new IllegalArgumentException("cast is not a list of card names");
                }
                cast.add(x.getAsString());
            }
        } else {
            cast = null;
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

    /** One seat's deal: the zones in order, and for the assembled seat with a cast list the draws still to be dealt. */
    private static final class Deal {
        List<Card> pool = new ArrayList<>();
        List<Card> bf = new ArrayList<>();
        List<Card> hd = new ArrayList<>();
        List<Card> grave = new ArrayList<>();
        List<Card> top = new ArrayList<>();
        int nLands, gyN, handN, life;

        Deal copy() {
            final Deal d = new Deal();
            d.pool = new ArrayList<>(pool);
            d.bf = new ArrayList<>(bf);
            d.hd = new ArrayList<>(hd);
            d.grave = new ArrayList<>(grave);
            d.top = new ArrayList<>(top);
            d.nLands = nLands;
            d.gyN = gyN;
            d.handN = handN;
            d.life = life;
            return d;
        }

        List<Card> lib() {
            final List<Card> lib = new ArrayList<>(top);
            lib.addAll(pool);
            return lib;
        }

        /** The assembled seat's fillers with these lands on the battlefield (today's order: lands, graveyard, hand). */
        void fill(final List<Card> lands) {
            for (Card c : lands) {
                removeSame(pool, c);
            }
            bf.addAll(lands);
            grave.addAll(takeN(pool, gyN, x -> !x.isLand()));
            hd.addAll(takeN(pool, Math.max(0, handN - hd.size()), x -> true));
        }
    }

    private static boolean removeSame(final List<Card> l, final Card c) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i) == c) {
                l.remove(i);
                return true;
            }
        }
        return false;
    }

    private static boolean hasSame(final List<Card> l, final Card c) {
        for (Card x : l) {
            if (x == c) {
                return true;
            }
        }
        return false;
    }

    private static final String[] WHO = {"human", "ai"};

    private static String zoneText(final int s, final Deal d) {
        return WHO[s] + "life=" + d.life + "\n" + WHO[s] + "landsplayed=0\n" + WHO[s] + "hand=" + join(d.hd)
                + "\n" + WHO[s] + "battlefield=" + join(d.bf, true) + "\n" + WHO[s] + "graveyard=" + join(d.grave) + "\n"
                + WHO[s] + "exile=\n" + WHO[s] + "library=" + join(d.lib()) + "\n";
    }

    private static String stateText(final int turn, final int act, final String zone0, final String zone1) {
        final StringBuilder st = new StringBuilder("[state]\n");
        st.append("turn=").append(turn).append("\nactiveplayer=").append(WHO[act]).append("\nactivephase=MAIN1\n");
        st.append(zone0).append(zone1);
        return st.toString();
    }

    /**
     * Deal both seats' cards to zones and install them. Returns {applied: true, seat, route, active (seat), turn,
     * missing: [names], placed: {piece: zone}, seats: [{lands, hand, board, gy, library, life}, x2]} or
     * {applied: false, reason: "missing", piece}; with a cast list also {applied: false, reason: "uncastable", ...}
     * and, when applied, the key {@code cast} (see {@link #chooseLands}).
     */
    JsonObject install(final Game game) {
        final Random r = new Random(RlSeeding.seatSeed(seed ^ SALT, 2));
        final List<Player> players = game.getRegisteredPlayers();
        final JsonObject out = new JsonObject();
        final JsonArray seatsOut = new JsonArray();
        final int[] nLands = new int[2];
        final String[] zoneText = new String[2];
        final JsonArray missingOut = new JsonArray();
        final JsonObject placed = new JsonObject();
        final Deal[] deal = new Deal[2];
        for (int s = 0; s < 2; s++) {
            final Player p = players.get(s);
            final Deal d = new Deal();
            deal[s] = d;
            final List<Card> pool = d.pool;
            pool.addAll(p.getCardsIn(ZoneType.Hand));
            pool.addAll(p.getCardsIn(ZoneType.Library));
            final List<Card> bf = d.bf;
            final List<Card> hd = d.hd;
            final List<Card> grave = d.grave;
            final List<Card> top = d.top;
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
                if (cast == null) {
                    bf.addAll(takeN(pool, nLands[s], Card::isLand));
                    grave.addAll(takeN(pool, draw(r, gy), x -> !x.isLand()));
                    hd.addAll(takeN(pool, Math.max(0, draw(r, hand) - hd.size()), x -> true));
                    d.life = draw(r, life);
                } else {
                    // sdcc-startfix-1113: the same draws in the same order; the fillers are dealt once the lands are chosen
                    d.nLands = nLands[s];
                    d.gyN = draw(r, gy);
                    d.handN = draw(r, hand);
                    d.life = draw(r, life);
                }
            } else {
                nLands[s] = draw(r, oppLands);
                bf.addAll(takeN(pool, nLands[s], Card::isLand));
                bf.addAll(takeN(pool, draw(r, oppBoard), Card::isCreature));
                grave.addAll(takeN(pool, draw(r, oppGy), x -> !x.isLand()));
                hd.addAll(takeN(pool, draw(r, oppHand), x -> true));
                d.life = draw(r, oppLife);
            }
        }
        JsonObject castOut = null;
        if (cast != null) {
            castOut = new JsonObject();
            final JsonObject refused = chooseLands(game, deal, castOut);
            if (refused != null) {
                return refused;
            }
        }
        for (int s = 0; s < 2; s++) {
            final Deal d = deal[s];
            zoneText[s] = zoneText(s, d);
            final JsonObject so = new JsonObject();
            so.addProperty("lands", nLands[s]);
            so.add("hand", names(d.hd));
            so.add("battlefield", names(d.bf));
            so.add("graveyard", names(d.grave));
            so.addProperty("library", d.lib().size());
            so.addProperty("life", d.life);
            seatsOut.add(so);
        }
        final int act = r.nextDouble() < active ? seat : 1 - seat;
        final int turn = Math.max(1, 2 * nLands[act] - 1);
        final Now gs = new Now();
        gs.parse(Arrays.asList(stateText(turn, act, zoneText[0], zoneText[1]).split("\\R")));
        gs.now(game);
        out.addProperty("applied", true);
        out.addProperty("seat", seat);
        out.addProperty("route", route);
        out.addProperty("active", act);
        out.addProperty("turn", turn);
        out.add("missing", missingOut);
        out.add("placed", placed);
        out.add("seats", seatsOut);
        if (castOut != null) {
            out.add("cast", castOut);
        }
        return out;
    }

    // ------------------------------------------------------------------------------- castable starts (sdcc-startfix-1113)

    private static final String[] COLS = {"W", "U", "B", "R", "G", "C"};
    private static final int ONE_SHOT = 1 << 6;
    private static final int MAX_LEAVES = 50000;

    /** The engine's verdict on one deal: 2 = every cast card payable now, 1 = their coloured pips are, 0 = not. */
    private static final class Verdict {
        int level = 2;
        String card;
        String cost;
        String error;
    }

    /**
     * Choose the assembled seat's lands (class comment, "Castable starts") and deal its fillers. Returns null when a
     * deal was made ({@code info}: {cards, lands: "dealt" | "chosen", check: "none" | "now" | "colours" | "hand_lands" |
     * "mana_in_hand", tried, [dealt], [short: {card, cost}]}), or the not-applied record.
     */
    private JsonObject chooseLands(final Game game, final Deal[] deal, final JsonObject info) {
        final Deal d = deal[seat];
        // the cards to cast, as this deck's own cards: in hand (a placed piece), on top or in the library. A land, and
        // a card the spec places on the battlefield or in the graveyard, is not cast.
        final List<Card> castCards = new ArrayList<>();
        final JsonArray castNames = new JsonArray();
        for (String nm : cast) {
            Card c = null;
            for (List<Card> zone : Arrays.asList(d.hd, d.top, d.pool)) {
                for (Card x : zone) {
                    if (is(x, nm) && !hasSame(castCards, x)) {
                        c = x;
                        break;
                    }
                }
                if (c != null) {
                    break;
                }
            }
            if (c == null) {
                boolean elsewhere = false;
                for (List<Card> zone : Arrays.asList(d.bf, d.grave)) {
                    for (Card x : zone) {
                        elsewhere |= is(x, nm);
                    }
                }
                if (elsewhere) {
                    continue;
                }
                final JsonObject no = new JsonObject();
                no.addProperty("applied", false);
                no.addProperty("reason", "missing");
                no.addProperty("piece", nm);
                return no;
            }
            if (!c.isLand()) {
                castCards.add(c);
                castNames.add(name(c));
            }
        }
        info.add("cards", castNames);
        final List<Card> poolLands = new ArrayList<>();
        for (Card c : d.pool) {
            if (c.isLand()) {
                poolLands.add(c);
            }
        }
        final int n = Math.min(d.nLands, poolLands.size());
        final List<Card> dealt = new ArrayList<>(poolLands.subList(0, n));
        if (castCards.isEmpty()) {
            d.fill(dealt);
            info.addProperty("lands", "dealt");
            info.addProperty("check", "none");
            info.addProperty("tried", 0);
            return null;
        }
        final List<List<Card>> cands = new ArrayList<>();
        final List<Verdict> verdicts = new ArrayList<>();
        cands.add(dealt);
        verdicts.add(judge(game, deal, dealt, castCards, false));
        if (verdicts.get(0).level < 2) {
            for (boolean generous : new boolean[] {true, false}) {
                final List<Card> c = pick(d, poolLands, n, castCards, generous);
                boolean seen = false;
                for (List<Card> o : cands) {
                    seen |= sameCards(o, c);
                }
                if (!seen) {
                    cands.add(c);
                    verdicts.add(judge(game, deal, c, castCards, false));
                }
            }
        }
        int best = 0;
        for (int i = 1; i < cands.size(); i++) {
            if (verdicts.get(i).level > verdicts.get(best).level) {
                best = i;
            }
        }
        String check = verdicts.get(best).level == 2 ? "now" : verdicts.get(best).level == 1 ? "colours" : null;
        if (check == null) {
            // not known to this rule, so the start is dealt: lands of the dealt hand (later land drops), then any
            // other mana maker in the dealt hand (a ritual, a rock: not modelled)
            for (int i = 0; i < cands.size() && check == null; i++) {
                if (judge(game, deal, cands.get(i), castCards, true).level >= 1) {
                    best = i;
                    check = "hand_lands";
                }
            }
            for (int k = 0; k < cands.size() && check == null; k++) {
                final int i = (k + 1) % cands.size(); // the chooser's first pick, then its second, then the deal as it was
                final Deal x = d.copy();
                x.fill(cands.get(i));
                for (Card c : x.hd) {
                    if (!c.isLand() && makesMana(c)) {
                        best = i;
                        check = "mana_in_hand";
                        break;
                    }
                }
            }
        }
        if (check == null) {
            final JsonObject no = new JsonObject();
            no.addProperty("applied", false);
            no.addProperty("reason", "uncastable");
            no.addProperty("seat", seat);
            no.addProperty("route", route);
            no.addProperty("card", verdicts.get(0).card);
            no.addProperty("cost", verdicts.get(0).cost);
            no.add("cards", castNames);
            no.addProperty("lands", d.nLands);
            final JsonArray tried = new JsonArray();
            for (int i = 0; i < cands.size(); i++) {
                final JsonObject t = new JsonObject();
                t.add("lands", names(cands.get(i)));
                t.addProperty("card", verdicts.get(i).card);
                t.addProperty("cost", verdicts.get(i).cost);
                tried.add(t);
            }
            no.add("tried", tried);
            no.add("placed_battlefield", names(d.bf));
            no.add("library_lands", names(poolLands));
            final Deal x = d.copy();
            x.fill(dealt);
            no.add("hand", names(x.hd));
            return no;
        }
        final Verdict v = verdicts.get(best);
        info.addProperty("lands", best == 0 ? "dealt" : "chosen");
        info.addProperty("check", check);
        info.addProperty("tried", cands.size());
        if (best != 0) {
            info.add("dealt", names(dealt));
        }
        if (v.level < 2 && v.card != null) {
            final JsonObject sh = new JsonObject();
            sh.addProperty("card", v.card);
            sh.addProperty("cost", v.cost);
            info.add("short", sh);
        }
        if (v.error != null) {
            info.addProperty("judge_error", v.error);
        }
        d.fill(cands.get(best));
        return null;
    }

    private static boolean sameCards(final List<Card> a, final List<Card> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Card c : a) {
            if (!hasSame(b, c)) {
                return false;
            }
        }
        return true;
    }

    /** A non-land card that makes mana: it has a mana ability, or one of its spell abilities adds mana. */
    private static boolean makesMana(final Card c) {
        try {
            if (!c.getManaAbilities().isEmpty()) {
                return true;
            }
            for (SpellAbility sa : c.getSpellAbilities()) {
                for (SpellAbility x = sa; x != null; x = x.getSubAbility()) {
                    if (x.getApi() == ApiType.Mana || x.getApi() == ApiType.ManaReflected) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            return true; // unknown: the rule does not reject on what it cannot read
        }
        return false;
    }

    // ---- the judge: Forge's own cost payment, on a copy of the game

    /**
     * The seat's deal with these lands, installed on a COPY of the game with the cast cards in hand and the seat in its
     * main phase, everything untapped; a land without a mana ability that fetches a land is replaced by a land it can
     * fetch; {@code handLands}: the lands of the dealt hand are in play too. The live game is not touched (own ids,
     * random stream, AI cache). A card the engine cannot be asked about counts as payable (and is reported).
     */
    private Verdict judge(final Game game, final Deal[] deal, final List<Card> lands, final List<Card> castCards,
            final boolean handLands) {
        final Verdict v = new Verdict();
        final Deal x = deal[seat].copy();
        x.fill(lands);
        if (handLands) {
            for (Card c : new ArrayList<>(x.hd)) {
                if (c.isLand()) {
                    removeSame(x.hd, c);
                    x.bf.add(c);
                }
            }
        }
        for (Card c : castCards) {
            if (!hasSame(x.hd, c)) {
                removeSame(x.top, c);
                removeSame(x.pool, c);
                removeSame(x.grave, c);
                x.hd.add(c);
            }
        }
        final int needed = neededColours(castCards);
        for (Card f : new ArrayList<>(x.bf)) {
            if (!f.isLand() || mask(f, null, true, null) != 0) {
                continue;
            }
            final SpellAbility fetch = fetchAbility(f);
            if (fetch == null) {
                continue;
            }
            Card bestT = null;
            int bestN = -1;
            for (Card t : x.lib()) {
                if (t.isLand() && fetches(f, fetch, t)) {
                    final int k = Integer.bitCount(mask(t, null, true, null) & needed);
                    if (k > bestN) {
                        bestN = k;
                        bestT = t;
                    }
                }
            }
            if (bestT != null) {
                removeSame(x.top, bestT);
                removeSame(x.pool, bestT);
                x.bf.set(x.bf.indexOf(f), bestT);
                x.grave.add(f);
            }
        }
        final String[] zt = new String[2];
        zt[seat] = zoneText(seat, x);
        zt[1 - seat] = zoneText(1 - seat, deal[1 - seat]);
        final String text = stateText(Math.max(1, 2 * x.nLands - 1), seat, zt[0], zt[1]);
        final Object prevIds = forge.util.IdScope.capture();
        final Random prevRandom = forge.util.MyRandom.getThreadRandom();
        final Object prevCache = forge.ai.AiCache.captureScope();
        forge.util.MyRandom.setThreadRandom(new Random(seed ^ SALT));
        forge.ai.AiCache.openScope();
        forge.util.IdScope.open();
        try {
            final Game g = new GameCopier(game, true).makeCopy();
            final Now gs = new Now();
            gs.parse(Arrays.asList(text.split("\\R")));
            gs.now(g);
            final Player me = g.getPlayers().get(seat);
            final List<Card> used = new ArrayList<>();
            for (Card want : castCards) {
                Card c = null;
                for (Card h : me.getCardsIn(ZoneType.Hand)) {
                    if (is(h, name(want)) && !hasSame(used, h)) {
                        c = h;
                        break;
                    }
                }
                if (c == null) {
                    continue;
                }
                used.add(c);
                int level;
                try {
                    level = payable(c, me);
                } catch (RuntimeException e) {
                    level = 2;
                    v.error = name(want) + ": " + e;
                }
                if (level < v.level) {
                    v.level = level;
                    v.card = name(want);
                    v.cost = String.valueOf(want.getManaCost());
                }
            }
        } catch (RuntimeException e) {
            v.level = 2; // the copy could not be built or installed: no verdict, the deal stands
            v.error = "copy: " + e;
        } finally {
            forge.ai.AiCache.installScope(prevCache);
            forge.util.IdScope.install(prevIds);
            forge.util.MyRandom.setThreadRandom(prevRandom);
        }
        return v;
    }

    /** 2: some way of casting the card is payable now; 1: the coloured part of some way's mana cost is; 0: neither. */
    private static int payable(final Card c, final Player me) {
        int level = 0;
        for (SpellAbility sa : c.getAllPossibleAbilities(me, false)) {
            if (!sa.isSpell() || sa.isCastFaceDown()) {
                continue;
            }
            sa.setActivatingPlayer(me);
            final Cost cost = sa.getPayCosts();
            if (cost == null) {
                return 2;
            }
            boolean others = true;
            for (CostPart part : cost.getCostParts()) {
                if (!(part instanceof CostPartMana) && !part.canPay(sa, me, false)) {
                    others = false;
                }
            }
            if (!others) {
                continue;
            }
            if (ComputerUtilMana.canPayManaCost(cost, sa, me, 0, false)) {
                return 2;
            }
            final ManaCostBeingPaid m = ComputerUtilMana.calculateManaCost(cost, sa, me, true, 0, false);
            // a Phyrexian pip may be paid with 2 life (the AI's payment test does not count that): taken out when the
            // seat can pay the life
            int phy = 0;
            for (ManaCostShard sh : com.google.common.collect.Lists.newArrayList(m.getDistinctShards())) {
                if (sh.isPhyrexian()) {
                    final int k = m.getUnpaidShards(sh);
                    phy += k;
                    m.decreaseShard(sh, k);
                }
            }
            if (phy > 0 && !me.canPayLife(2 * phy, false, sa)) {
                continue;
            }
            if (phy > 0 && (m.isPaid() || ComputerUtilMana.canPayManaCost(m, sa, me, false))) {
                return 2;
            }
            m.decreaseGenericMana(m.getGenericManaAmount());
            if (m.isPaid() || ComputerUtilMana.canPayManaCost(m, sa, me, false)) {
                level = 1;
            }
        }
        return level;
    }

    // ---- the chooser: which lands to try (colours only; it never decides whether a start is dealt)

    private static SpellAbility fetchAbility(final Card c) {
        try {
            for (SpellAbility sa : c.getNonManaAbilities()) {
                if (sa.isActivatedAbility() && sa.getApi() == ApiType.ChangeZone && sa.hasParam("ChangeType")
                        && "Library".equals(sa.getParam("Origin")) && "Battlefield".equals(sa.getParam("Destination"))) {
                    return sa;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean fetches(final Card f, final SpellAbility fetch, final Card t) {
        try {
            return t != f && t.isValid(fetch.getParam("ChangeType").split(","), f.getOwner(), f, fetch);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The colours a card's mana abilities can make, as bits of {@link #COLS} (+ {@link #ONE_SHOT} when every such ability
     * sacrifices or exiles the card). {@code generous}: every mana ability, and for a land that fetches a land the
     * colours of the lands of {@code targets} it can fetch; otherwise only unrestricted abilities whose whole cost is
     * to tap. {@code cache}: per card and mode, or null.
     */
    private static int mask(final Card c, final List<Card> targets, final boolean generous,
            final Map<Card, Integer> cache) {
        if (cache != null && cache.containsKey(c)) {
            return cache.get(c);
        }
        int rep = 0;
        int once = 0;
        try {
            for (SpellAbility sa : c.getManaAbilities()) {
                boolean tapOnly = sa.getPayCosts() != null;
                boolean oneShot = false;
                if (sa.getPayCosts() != null) {
                    for (CostPart part : sa.getPayCosts().getCostParts()) {
                        tapOnly &= part instanceof CostTap;
                        oneShot |= (part instanceof CostSacrifice || part instanceof CostExile) && part.payCostFromSource();
                    }
                }
                final boolean plain = tapOnly && sa.getManaPart() != null
                        && sa.getManaPart().getManaRestrictions().isEmpty();
                if (!generous && !plain) {
                    continue;
                }
                int m = 0;
                for (int i = 0; i < COLS.length; i++) {
                    if (produces(sa, COLS[i])) {
                        m |= 1 << i;
                    }
                }
                if (oneShot) {
                    once |= m;
                } else {
                    rep |= m;
                }
            }
            if (generous && targets != null && rep == 0 && once == 0 && c.isLand()) {
                final SpellAbility fetch = fetchAbility(c);
                if (fetch != null) {
                    for (Card t : targets) {
                        if (fetches(c, fetch, t)) {
                            rep |= mask(t, null, false, null) & ~ONE_SHOT;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // a card the engine cannot be asked about is no source for the chooser; the judge still sees it
        }
        final int out = rep != 0 ? rep : once != 0 ? (once | ONE_SHOT) : 0;
        if (cache != null) {
            cache.put(c, out);
        }
        return out;
    }

    /** Whether the ability (or one chained to it) can add this colour: read from its mana part, nothing is set. */
    private static boolean produces(final SpellAbility sa, final String col) {
        for (SpellAbility x = sa; x != null; x = x.getSubAbility()) {
            final AbilityManaPart mp = x.getManaPart();
            if (mp != null && !mp.isSpecialMana() && mp.canProduce(col, x)) {
                return true;
            }
        }
        return false;
    }

    /** A card's coloured pips as colour bit sets (hybrid: either colour; {C}: colourless). Generic, X, Phyrexian and
     *  two-generic hybrid pips need no colour. */
    private static List<Integer> pips(final Card c) {
        final List<Integer> out = new ArrayList<>();
        if (c.getManaCost() == null) {
            return out;
        }
        for (ManaCostShard sh : c.getManaCost()) {
            if (sh.isPhyrexian() || sh.isOr2Generic()) {
                continue;
            }
            int m = 0;
            if (sh.isWhite()) {
                m |= 1;
            }
            if (sh.isBlue()) {
                m |= 2;
            }
            if (sh.isBlack()) {
                m |= 4;
            }
            if (sh.isRed()) {
                m |= 8;
            }
            if (sh.isGreen()) {
                m |= 16;
            }
            if (sh.isColorless()) {
                m |= 32;
            }
            if (m != 0) {
                out.add(m);
            }
        }
        return out;
    }

    private static int neededColours(final List<Card> castCards) {
        int m = 0;
        for (Card c : castCards) {
            for (int p : pips(c)) {
                m |= p;
            }
        }
        return m;
    }

    /** Most pips that can each take their own source (augmenting paths; the lists are short). */
    private static int matched(final List<Integer> pips, final List<Integer> sources) {
        final int[] owner = new int[sources.size()];
        Arrays.fill(owner, -1);
        int n = 0;
        for (int i = 0; i < pips.size(); i++) {
            if (augment(i, pips, sources, owner, new boolean[sources.size()])) {
                n++;
            }
        }
        return n;
    }

    private static boolean augment(final int i, final List<Integer> pips, final List<Integer> sources, final int[] owner,
            final boolean[] seen) {
        for (int j = 0; j < sources.size(); j++) {
            if (seen[j] || (pips.get(i) & sources.get(j)) == 0) {
                continue;
            }
            seen[j] = true;
            if (owner[j] < 0 || augment(owner[j], pips, sources, owner, seen)) {
                owner[j] = i;
                return true;
            }
        }
        return false;
    }

    /**
     * Pips covered over all cast cards, each card on its own with every repeatable source; a one-shot source (it
     * sacrifices itself) serves one card only (the first three such sources are assigned in every way, further ones are
     * left out).
     */
    private static int score(final List<List<Integer>> need, final List<Integer> sources) {
        final List<Integer> rep = new ArrayList<>();
        final List<Integer> once = new ArrayList<>();
        for (int s : sources) {
            if ((s & ONE_SHOT) != 0) {
                if (once.size() < 3) {
                    once.add(s & ~ONE_SHOT);
                }
            } else if (s != 0) {
                rep.add(s);
            }
        }
        return score(need, rep, once, 0, new int[once.size()]);
    }

    private static int score(final List<List<Integer>> need, final List<Integer> rep, final List<Integer> once,
            final int k, final int[] to) {
        if (k == once.size()) {
            int total = 0;
            for (int i = 0; i < need.size(); i++) {
                final List<Integer> src = new ArrayList<>(rep);
                for (int j = 0; j < once.size(); j++) {
                    if (to[j] == i) {
                        src.add(once.get(j));
                    }
                }
                total += matched(need.get(i), src);
            }
            return total;
        }
        int best = -1;
        for (int i = 0; i < Math.max(1, need.size()); i++) {
            to[k] = i;
            best = Math.max(best, score(need, rep, once, k + 1, to));
        }
        return best;
    }

    /**
     * The n lands of the library that cover the most coloured pips of the cast cards (with the mana permanents the
     * start places); among those the earliest in library order (so the deal as it was is the answer whenever it covers
     * as many as any other). Lands of one kind (same colours) are interchangeable: the search is over how many of each
     * kind, taking the earliest of the kind.
     */
    private List<Card> pick(final Deal d, final List<Card> poolLands, final int n, final List<Card> castCards,
            final boolean generous) {
        final Map<Card, Integer> cache = new IdentityHashMap<>();
        final List<List<Integer>> need = new ArrayList<>();
        for (Card c : castCards) {
            need.add(pips(c));
        }
        final List<Integer> fixed = new ArrayList<>();
        for (Card c : d.bf) {
            final int m = mask(c, poolLands, generous, cache);
            if (m != 0) {
                fixed.add(m);
            }
        }
        final Map<Integer, List<Integer>> kinds = new LinkedHashMap<>();
        for (int i = 0; i < poolLands.size(); i++) {
            kinds.computeIfAbsent(mask(poolLands.get(i), poolLands, generous, cache), k -> new ArrayList<>()).add(i);
        }
        final List<Integer> keys = new ArrayList<>(kinds.keySet());
        final int[] count = new int[keys.size()];
        final int[] bestCount = new int[keys.size()];
        final int[] state = {-1, 0}; // best score, leaves visited
        final List<Integer> bestIdx = new ArrayList<>();
        search(0, n, keys, kinds, count, need, fixed, state, bestCount, bestIdx);
        final List<Card> out = new ArrayList<>();
        for (int i : bestIdx) {
            out.add(poolLands.get(i));
        }
        return out;
    }

    private static void search(final int k, final int left, final List<Integer> keys, final Map<Integer, List<Integer>> kinds,
            final int[] count, final List<List<Integer>> need, final List<Integer> fixed, final int[] state,
            final int[] bestCount, final List<Integer> bestIdx) {
        if (state[1] >= MAX_LEAVES) {
            return;
        }
        if (k == keys.size()) {
            if (left != 0) {
                return;
            }
            state[1]++;
            final List<Integer> sources = new ArrayList<>(fixed);
            final List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                for (int j = 0; j < count[i]; j++) {
                    sources.add(keys.get(i));
                    idx.add(kinds.get(keys.get(i)).get(j));
                }
            }
            Collections.sort(idx);
            final int sc = score(need, sources);
            boolean better = sc > state[0];
            if (sc == state[0]) {
                for (int i = 0; i < idx.size(); i++) {
                    if (!idx.get(i).equals(bestIdx.get(i))) {
                        better = idx.get(i) < bestIdx.get(i);
                        break;
                    }
                }
            }
            if (better) {
                state[0] = sc;
                bestIdx.clear();
                bestIdx.addAll(idx);
                System.arraycopy(count, 0, bestCount, 0, count.length);
            }
            return;
        }
        final int avail = kinds.get(keys.get(k)).size();
        int rest = 0;
        for (int i = k + 1; i < keys.size(); i++) {
            rest += kinds.get(keys.get(i)).size();
        }
        for (int c = Math.min(left, avail); c >= Math.max(0, left - rest); c--) {
            count[k] = c;
            search(k + 1, left - c, keys, kinds, count, need, fixed, state, bestCount, bestIdx);
        }
        count[k] = 0;
    }

    /** JSON null-safe accessor for the tape. */
    public static JsonElement orNull(final RlStartState s) {
        return s == null ? JsonNull.INSTANCE : s.started();
    }
}
