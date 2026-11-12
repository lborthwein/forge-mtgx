package forge.bench.rl;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Lane sdcc-route-1111: route labels, a TRAINING-DATA label generator (owner ruling 2026-11-12: labels derived from a
 * route file are allowed in training data only, as a short-term bootstrap).
 *
 * <p>A route file (schema {@link #SCHEMA}; a data file shipped to the actor like a deck list) lists card sets
 * ("routes": {@code id}, {@code family}, {@code pieces}, and optionally the pieces that belong in the {@code graveyard}
 * or stay in the {@code library}). This class reads the file and, AFTER the seat's answer to a card-choice ask is
 * final, says which answer would have served the routes the seat's own deck holds. The label is written to GAME_END
 * and the tape as an imitation label of reason {@link RlSeat#IMIT_ROUTE}; it is never returned to the code that
 * answers. Nothing in the pilot's decision path (policy inputs, search, solver, scorer) reads this class or the file,
 * and the file is not loaded unless the search spec sets {@code routeLabels} together with {@code imit}.
 *
 * <p>No card name and no card rule is in this code: every name comes from the data file and from the game's cards.
 *
 * <p>Rules (all per seat, over the routes whose pieces are ALL in the seat's own deck, in the file's order):
 * <ul>
 * <li>A piece is <i>held</i> when the seat has it in hand, on the battlefield or on the stack. A piece is <i>in
 * place</i> when it is held, or it is a graveyard piece of the route and in the seat's graveyard, or it is a library
 * piece of the route and still in the seat's library. A route's <i>missing</i> count is its pieces not in place.</li>
 * <li>{@link #TAKE} (a card pick out of the seat's own library, or out of its hand onto the battlefield): the label
 * picks the offered route piece that is not held (from the hand: any piece) and whose route is left with the fewest
 * missing pieces after the pick; ties go to the route named first in the file, then to the piece named first in that
 * route. A graveyard piece is picked only by a move to the graveyard, a library piece only by a move to the
 * battlefield, any other piece by a move to the hand, the library top or the battlefield. A pick of several cards
 * takes the best pieces in that order.</li>
 * <li>{@link #PROTECT} (a discard, cost cards paid from the hand, a put-back from the hand, the mulligan's bottom
 * cards, a scry's bottom): only when the answer played gives up a route piece while a card that is no route piece was
 * on offer; the label is the answer played with each such piece replaced by the first offered card that is not one. A
 * graveyard piece is not protected from a move to the graveyard, a library piece not from a move to the library.</li>
 * <li>{@link #KEEP} (the mulligan's keep-or-not): the label is "keep" when the hand holds at least one route piece
 * and two to five lands.</li>
 * </ul>
 */
public final class RlRouteLabels {
    public static final String SCHEMA = "mtgx-rl-route-labels/1";
    /** The kinds of route label (the audit list of GAME_END / the tape: {@code route}). */
    public static final int TAKE = 1, KEEP = 2, PROTECT = 3;
    /** Files read by this JVM (a test's proof that the file is not loaded without the key). */
    public static final AtomicInteger LOADS = new AtomicInteger();
    private static final Map<String, RlRouteLabels> CACHE = new ConcurrentHashMap<>();

    private static final int W_ANY = 0, W_GRAVEYARD = 1, W_LIBRARY = 2;
    private static final int Z_HAND = 0, Z_LIBRARY = 1, Z_BATTLEFIELD = 2, Z_GRAVEYARD = 3, Z_OTHER = 4;

    static final class Route {
        int index;
        String id;
        String family;
        final List<String> pieces = new ArrayList<>();
        final Map<String, Integer> want = new HashMap<>();
    }

    /** One label: the steps, its kind, the route that named it and whether the answer played was the same. */
    public static final class Label {
        public final short[] steps;
        public final int kind;
        public final String route;
        public final String family;
        public boolean same;

        Label(final short[] steps, final int kind, final Route r) {
            this.steps = steps;
            this.kind = kind;
            this.route = r.id;
            this.family = r.family;
        }
    }

    private final List<Route> routes = new ArrayList<>();
    public final String path;

    private RlRouteLabels(final String path) {
        this.path = path;
    }

    /** The route file at {@code path}, read once per JVM. A file that is missing or malformed is an error. */
    public static RlRouteLabels load(final String path) {
        return CACHE.computeIfAbsent(path, RlRouteLabels::read);
    }

    private static RlRouteLabels read(final String path) {
        final JsonObject o;
        try (Reader r = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
            o = JsonParser.parseReader(r).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("routeLabels: cannot read " + path + ": " + e);
        }
        LOADS.incrementAndGet();
        if (!o.has("schema") || !SCHEMA.equals(o.get("schema").getAsString())) {
            throw new IllegalStateException("routeLabels: " + path + " is not " + SCHEMA);
        }
        final Set<String> held = new HashSet<>();
        if (o.has("held_out")) {
            for (JsonElement e : o.getAsJsonArray("held_out")) {
                held.add(e.getAsString());
            }
        }
        final RlRouteLabels out = new RlRouteLabels(path);
        for (JsonElement e : o.getAsJsonArray("routes")) {
            final JsonObject x = e.getAsJsonObject();
            final Route r = new Route();
            r.index = out.routes.size();
            r.id = x.get("id").getAsString();
            r.family = x.get("family").getAsString();
            if (held.contains(r.family)) {
                // the file's own held-out list: a route of such a family must not be shipped at all
                throw new IllegalStateException("routeLabels: " + path + " holds route " + r.id + " of held-out family "
                        + r.family);
            }
            for (JsonElement p : x.getAsJsonArray("pieces")) {
                r.pieces.add(p.getAsString());
            }
            wants(x, "graveyard", W_GRAVEYARD, r);
            wants(x, "library", W_LIBRARY, r);
            if (r.pieces.isEmpty()) {
                throw new IllegalStateException("routeLabels: route " + r.id + " has no pieces");
            }
            out.routes.add(r);
        }
        return out;
    }

    private static void wants(final JsonObject x, final String key, final int w, final Route r) {
        if (!x.has(key) || x.get(key).isJsonNull()) {
            return;
        }
        final JsonArray a = x.getAsJsonArray(key);
        for (JsonElement p : a) {
            if (!r.pieces.contains(p.getAsString())) {
                throw new IllegalStateException("routeLabels: route " + r.id + ": " + key + " names a card that is no piece");
            }
            r.want.put(p.getAsString(), w);
        }
    }

    public int size() {
        return routes.size();
    }

    static String nameOf(final Card c) {
        try {
            if (c.getPaperCard() != null && !c.isToken()) {
                return c.getPaperCard().getName();
            }
        } catch (RuntimeException e) {
            // a card without a paper card: its own name
        }
        return c.getName();
    }

    /** One seat of one game: the routes its own deck holds. */
    public final class Seat {
        private final Player p;
        private final List<Route> deck = new ArrayList<>();
        private final Set<String> pieces = new HashSet<>();

        Seat(final Game g, final Player p) {
            this.p = p;
            final Set<String> names = new HashSet<>();
            for (Card c : g.getCardsInGame()) {
                if (c.getOwner() == p && !c.isToken()) {
                    names.add(nameOf(c));
                }
            }
            for (Route r : routes) {
                if (names.containsAll(r.pieces)) {
                    deck.add(r);
                    pieces.addAll(r.pieces);
                }
            }
        }

        public int routes() {
            return deck.size();
        }

        private Set<String> names(final Game g, final ZoneType z) {
            final Set<String> out = new HashSet<>();
            if (z == ZoneType.Stack) {
                for (Card c : g.getCardsIn(ZoneType.Stack)) {
                    if (c.getOwner() == p && !c.isToken()) {
                        out.add(nameOf(c));
                    }
                }
                return out;
            }
            for (Card c : p.getCardsIn(z)) {
                if (!c.isToken()) {
                    out.add(nameOf(c));
                }
            }
            return out;
        }

        private int want(final Route r, final String n) {
            final Integer w = r.want.get(n);
            return w == null ? W_ANY : w;
        }

        /** The answer the seat's routes name at this ask, or null (no opinion). Reads the game; changes nothing. */
        public Label label(final Game g, final int family, final String method, final JsonObject body,
                final RlCandidates.Menu m, final short[] played) {
            if (deck.isEmpty() || m == null || played == null) {
                return null;
            }
            final Label out;
            switch (family) {
                case RlSchema.F_MULLIGAN:
                    out = keep(m);
                    break;
                case RlSchema.F_CARDS:
                    out = cards(g, method, body, m, played);
                    break;
                case RlSchema.F_DISCARD_FROM:
                    out = body != null && body.has("ownHand") && body.get("ownHand").getAsBoolean() && allIn(m, ZoneType.Hand)
                            ? protect(m, played, Z_GRAVEYARD) : null;
                    break;
                case RlSchema.F_COST_CARDS: {
                    final String cost = body != null && body.has("cost") ? body.get("cost").getAsString() : "";
                    out = allIn(m, ZoneType.Hand) ? protect(m, played, cost.contains("Discard") ? Z_GRAVEYARD : Z_OTHER) : null;
                    break;
                }
                case RlSchema.F_MULLIGAN_BOTTOM:
                    out = allIn(m, ZoneType.Hand) ? protect(m, played, Z_LIBRARY) : null;
                    break;
                case RlSchema.F_SCRY:
                    out = scry(g, m, played);
                    break;
                default:
                    out = null;
            }
            if (out == null || m.validate(out.steps) != null) {
                return null;
            }
            out.same = java.util.Arrays.equals(out.steps, played);
            return out;
        }

        // ------------------------------------------------------------------------------------------------ keep

        private Label keep(final RlCandidates.Menu m) {
            final int k = m.keepIndex();
            if (k < 0) {
                return null;
            }
            int lands = 0;
            Route first = null;
            for (Card c : p.getCardsIn(ZoneType.Hand)) {
                if (c.isLand()) {
                    lands++;
                }
                final String n = nameOf(c);
                if (pieces.contains(n)) {
                    for (Route r : deck) {
                        if (r.pieces.contains(n) && (first == null || r.index < first.index)) {
                            first = r;
                        }
                    }
                }
            }
            if (first == null || lands < 2 || lands > 5) {
                return null;
            }
            return new Label(new short[] {(short) k}, KEEP, first);
        }

        // ------------------------------------------------------------------------------------------------ card picks

        private boolean allIn(final RlCandidates.Menu m, final ZoneType z) {
            if (m.mode != RlSchema.M_SUBSET || m.C() == 0) {
                return false;
            }
            for (RlCandidates.Cand x : m.cands) {
                if (x.kind != RlSchema.K_CARD || x.host == null || x.hidden || x.host.getOwner() != p
                        || x.host.getZone() == null || x.host.getZone().getZoneType() != z) {
                    return false;
                }
            }
            return true;
        }

        private Label cards(final Game g, final String method, final JsonObject body, final RlCandidates.Menu m,
                final short[] played) {
            if ("chooseCardsToDiscardToMaximumHandSize".equals(method)) {
                return allIn(m, ZoneType.Hand) ? protect(m, played, Z_GRAVEYARD) : null;
            }
            if (!"zoneChange".equals(m.kind) || body == null || !body.has("destination")) {
                return null;
            }
            final int dest;
            switch (body.get("destination").getAsString()) {
                case "Hand":
                    dest = Z_HAND;
                    break;
                case "Library":
                    dest = Z_LIBRARY;
                    break;
                case "Battlefield":
                    dest = Z_BATTLEFIELD;
                    break;
                case "Graveyard":
                    dest = Z_GRAVEYARD;
                    break;
                default:
                    dest = Z_OTHER;
            }
            if (allIn(m, ZoneType.Hand)) {
                return dest == Z_BATTLEFIELD ? take(g, m, dest, true) : protect(m, played, dest);
            }
            // a search of the seat's own library (a graveyard card may be on offer beside it)
            if (m.mode != RlSchema.M_SUBSET || dest == Z_OTHER) {
                return null;
            }
            boolean lib = false;
            for (RlCandidates.Cand x : m.cands) {
                if (x.kind != RlSchema.K_CARD || x.host == null || x.hidden || x.host.getOwner() != p
                        || x.host.getZone() == null) {
                    return null;
                }
                final ZoneType z = x.host.getZone().getZoneType();
                if (z == ZoneType.Library) {
                    lib = true;
                } else if (z != ZoneType.Graveyard) {
                    return null;
                }
            }
            return lib ? take(g, m, dest, false) : null;
        }

        private Label take(final Game g, final RlCandidates.Menu m, final int dest, final boolean fromHand) {
            if (m.maxPick < 1) {
                return null;
            }
            final Set<String> held = names(g, ZoneType.Battlefield);
            held.addAll(names(g, ZoneType.Stack));
            final Set<String> onField = new HashSet<>(held);
            held.addAll(names(g, ZoneType.Hand));
            final Set<String> grave = names(g, ZoneType.Graveyard), library = names(g, ZoneType.Library);
            // per candidate: {missing after the pick, route index, piece index, candidate index}
            final List<int[]> ranked = new ArrayList<>();
            for (int i = 0; i < m.C(); i++) {
                final String n = nameOf(m.cands.get(i).host);
                if (!pieces.contains(n) || (fromHand ? onField.contains(n) : held.contains(n))) {
                    continue;
                }
                int[] best = null;
                for (Route r : deck) {
                    final int pi = r.pieces.indexOf(n);
                    if (pi < 0) {
                        continue;
                    }
                    final int w = want(r, n);
                    final boolean fit = dest == Z_GRAVEYARD ? w == W_GRAVEYARD
                            : dest == Z_BATTLEFIELD ? w != W_GRAVEYARD : w == W_ANY;
                    if (!fit) {
                        continue;
                    }
                    int missing = 0;
                    for (String q : r.pieces) {
                        final int wq = want(r, q);
                        final boolean in = q.equals(n) || held.contains(q) || wq == W_GRAVEYARD && grave.contains(q)
                                || wq == W_LIBRARY && library.contains(q);
                        if (!in) {
                            missing++;
                        }
                    }
                    final int[] key = {missing, r.index, pi, i};
                    if (best == null || less(key, best)) {
                        best = key;
                    }
                }
                if (best != null) {
                    ranked.add(best);
                }
            }
            if (ranked.isEmpty()) {
                return null;
            }
            ranked.sort((a, b) -> less(a, b) ? -1 : less(b, a) ? 1 : 0);
            final int k = Math.min(m.maxPick, ranked.size());
            if (k < m.minPick) {
                return null;
            }
            final short[] steps = new short[k];
            for (int j = 0; j < k; j++) {
                steps[j] = (short) ranked.get(j)[3];
            }
            return new Label(steps, TAKE, routes.get(ranked.get(0)[1]));
        }

        private boolean less(final int[] a, final int[] b) {
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return a[i] < b[i];
                }
            }
            return false;
        }

        // ------------------------------------------------------------------------------------------------ give-ups

        /** The first deck route that protects the card from a move to {@code dest}, or null (not protected). */
        private Route guard(final String n, final int dest) {
            if (!pieces.contains(n)) {
                return null;
            }
            Route first = null;
            for (Route r : deck) {
                if (!r.pieces.contains(n)) {
                    continue;
                }
                final int w = want(r, n);
                if (dest == Z_GRAVEYARD && w == W_GRAVEYARD || dest == Z_LIBRARY && w == W_LIBRARY) {
                    return null; // a route wants the card there
                }
                if (first == null) {
                    first = r;
                }
            }
            return first;
        }

        private Label protect(final RlCandidates.Menu m, final short[] played, final int dest) {
            if (played.length == 0) {
                return null;
            }
            final boolean[] used = new boolean[m.C()];
            for (short s : played) {
                if (s < 0 || s >= m.C()) {
                    return null;
                }
                used[s] = true;
            }
            final short[] steps = played.clone();
            Route why = null;
            int next = 0;
            for (int j = 0; j < steps.length; j++) {
                final Route r = guard(nameOf(m.cands.get(steps[j]).host), dest);
                if (r == null) {
                    continue;
                }
                while (next < m.C() && (used[next] || guard(nameOf(m.cands.get(next).host), dest) != null)) {
                    next++;
                }
                if (next >= m.C()) {
                    break; // nothing else on offer: the piece has to go
                }
                used[next] = true;
                steps[j] = (short) next;
                if (why == null) {
                    why = r;
                }
            }
            return why == null ? null : new Label(steps, PROTECT, why);
        }

        private Label scry(final Game g, final RlCandidates.Menu m, final short[] played) {
            if (m.mode != RlSchema.M_ASSIGN || played.length != m.S()) {
                return null;
            }
            final Set<String> held = names(g, ZoneType.Battlefield);
            held.addAll(names(g, ZoneType.Stack));
            held.addAll(names(g, ZoneType.Hand));
            final short[] steps = played.clone();
            Route why = null;
            for (int s = 0; s < steps.length; s++) {
                final RlCandidates.Cand x = m.cands.get(steps[s]);
                if (x.kind != RlSchema.K_NO || x.host == null || x.host.getOwner() != p) {
                    continue;
                }
                final String n = nameOf(x.host);
                final Route r = held.contains(n) ? null : guard(n, Z_LIBRARY);
                if (r == null || want(r, n) != W_ANY) {
                    continue;
                }
                for (int i = 0; i < m.C(); i++) {
                    final RlCandidates.Cand y = m.cands.get(i);
                    if (y.slot == s && y.kind == RlSchema.K_YES) {
                        steps[s] = (short) i;
                        if (why == null) {
                            why = r;
                        }
                        break;
                    }
                }
            }
            return why == null ? null : new Label(steps, PROTECT, why);
        }
    }

    public Seat seat(final Game g, final Player p) {
        return new Seat(g, p);
    }
}
