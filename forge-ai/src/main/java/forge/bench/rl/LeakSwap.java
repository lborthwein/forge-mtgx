package forge.bench.rl;

import com.google.gson.JsonObject;
import forge.ai.simulation.InfoStates;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * sdcc-leakswap-1115: the hidden-card SWAP leak test of the live priority search (DIAGNOSTICS ONLY).
 *
 * <p>Off unless {@code -Dsdcc.leakSwap=<out.jsonl>} is set ({@code {pid}} in the path is replaced by the JVM's pid); off,
 * nothing in this class runs and no game changes. On, at a sample of a search seat's SEARCHED priority decisions
 * ({@code -Dsdcc.leakSwap.every}, default 3: about one in three, by a hash of the game uid and the decision index; at most
 * {@code -Dsdcc.leakSwap.cap} decisions with a changed hidden card per game, default 10), after the real search has
 * answered and without changing its answer, {@link RlSearch} searches the same decision twice more with the same world
 * seeds: R on the live position as it is (a repeat control) and S on the live position with its hidden cards re-dealt by
 * {@link #redeal} (and put back exactly by {@link Deal#restore}). One JSON line per decision.
 *
 * <p>The re-deal works IN PLACE on the live game's own card objects (no new cards, no events), so every structure keyed by
 * the game or by card ids (InfoStates, the seat's knowledge, readers) stays valid: (1) the opponent's hidden hand cards and
 * the unknown cards of their library are dealt again from their union (a seeded shuffle); (2) the seat's own library is
 * shuffled in its unknown slots. A card stays where it is if the seat can see it (CardView.canBeShownTo), if InfoStates
 * knows its position or its run, if the seat knows or remembers it (RlKnowledge, either zone, own library included: the
 * featurizer shows such a library card with its position), or if it is face down.
 */
final class LeakSwap {
    static final String OUT = System.getProperty("sdcc.leakSwap");
    static final boolean ON = OUT != null && !OUT.isEmpty();
    static final int EVERY = Math.max(1, Integer.getInteger("sdcc.leakSwap.every", 3));
    static final int CAP = Integer.getInteger("sdcc.leakSwap.cap", 10);
    static final long SALT = Long.getLong("sdcc.leakSwap.salt", 0x1eac5a9L);

    private static final Map<Long, Integer> DONE = new HashMap<>();
    private static Writer out;

    private LeakSwap() {
    }

    static long mix(long a, long b) {
        return RlSearch.splitmix(a ^ RlSearch.splitmix(b));
    }

    /** This decision is in the sample (and the game's cap is not reached). */
    static synchronized boolean want(final long uid, final int decIdx) {
        if (DONE.getOrDefault(uid, 0) >= CAP) {
            return false;
        }
        return Long.remainderUnsigned(mix(mix(SALT, uid), decIdx), EVERY) == 0;
    }

    /** A logged decision with at least one hidden card changed (counts toward the game's cap). */
    static synchronized void counted(final long uid) {
        DONE.merge(uid, 1, Integer::sum);
    }

    static synchronized void write(final JsonObject row) {
        try {
            if (out == null) {
                final String path = OUT.replace("{pid}", String.valueOf(ProcessHandle.current().pid()));
                out = new FileWriter(path, StandardCharsets.UTF_8, true);
            }
            out.write(row.toString());
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            System.err.println("[leakswap] write failed: " + e);
        }
    }

    /**
     * The fields of two featurizer observations that differ (reflection over the Obs class: arrays element-wise, scalars
     * by value), as "field[i]: a -> b" for the first differing elements (diagnostics: where a re-deal shows in the frame).
     */
    static com.google.gson.JsonArray obsDiff(final Object a, final Object b) {
        final com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        if (a == null || b == null || a.getClass() != b.getClass()) {
            out.add("class");
            return out;
        }
        for (java.lang.reflect.Field f : a.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            try {
                f.setAccessible(true);
                final Object x = f.get(a), y = f.get(b);
                if (x == null || y == null) {
                    if (x != y) {
                        out.add(f.getName() + ": null");
                    }
                    continue;
                }
                if (x.getClass().isArray()) {
                    final int nx = java.lang.reflect.Array.getLength(x), ny = java.lang.reflect.Array.getLength(y);
                    int shown = 0, diffs = 0;
                    for (int i = 0; i < Math.max(nx, ny); i++) {
                        final Object u = i < nx ? java.lang.reflect.Array.get(x, i) : null;
                        final Object v = i < ny ? java.lang.reflect.Array.get(y, i) : null;
                        final boolean eq = u == null ? v == null : u.getClass().isArray()
                                ? java.util.Objects.deepEquals(u, v) : u.equals(v);
                        if (!eq) {
                            diffs++;
                            if (shown++ < 6) {
                                out.add(f.getName() + "[" + i + "]: " + str(u) + " -> " + str(v));
                            }
                        }
                    }
                    if (diffs > 0 || nx != ny) {
                        out.add(f.getName() + ": " + diffs + " diffs, len " + nx + " -> " + ny);
                    }
                } else if (!x.equals(y) && (x instanceof Number || x instanceof Boolean || x instanceof String)) {
                    out.add(f.getName() + ": " + x + " -> " + y);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                out.add(f.getName() + ": " + e);
            }
        }
        return out;
    }

    private static String str(final Object o) {
        if (o == null) {
            return "null";
        }
        if (o.getClass().isArray()) {
            final StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(12, java.lang.reflect.Array.getLength(o)); i++) {
                sb.append(i > 0 ? "," : "").append(java.lang.reflect.Array.get(o, i));
            }
            return sb.append("]").toString();
        }
        return String.valueOf(o);
    }

    /** The zones a re-deal changed, their original card lists (for the exact restore), and what changed. */
    static final class Deal {
        final List<Zone> zones = new ArrayList<>();
        final List<List<Card>> orig = new ArrayList<>();
        int oppHandChanged, oppLibChanged, ownLibChanged;
        int oppHandSize, oppHandFree, oppLibSize, oppLibFree, ownLibSize, ownLibFree;

        int changed() {
            return oppHandChanged + oppLibChanged + ownLibChanged;
        }

        /** InfoStates of the game (null = none): told of every library rewrite ({@link InfoStates#leakResnap}). */
        InfoStates is;

        void set(final Zone z, final List<Card> now) {
            zones.add(z);
            orig.add(new ArrayList<>(z.getCards()));
            z.setCardsQuiet(now);
            resnap(z);
        }

        private void resnap(final Zone z) {
            if (is != null && z.getZoneType() == ZoneType.Library && z.getPlayer() != null) {
                is.leakResnap(z.getPlayer());
            }
        }

        /** Put every zone back in its original order; true when each zone holds exactly its original list again. */
        boolean restore() {
            boolean ok = true;
            for (int i = zones.size() - 1; i >= 0; i--) {
                zones.get(i).setCardsQuiet(orig.get(i));
                resnap(zones.get(i));
            }
            for (int i = 0; i < zones.size(); i++) {
                final List<Card> now = new ArrayList<>(zones.get(i).getCards());
                final List<Card> was = orig.get(i);
                if (now.size() != was.size()) {
                    ok = false;
                    continue;
                }
                for (int k = 0; k < was.size(); k++) {
                    if (now.get(k) != was.get(k)) {
                        ok = false;
                    }
                }
            }
            return ok;
        }

        void into(final JsonObject row) {
            row.addProperty("changed", changed());
            row.addProperty("opp_hand_changed", oppHandChanged);
            row.addProperty("opp_lib_changed", oppLibChanged);
            row.addProperty("own_lib_changed", ownLibChanged);
            row.addProperty("opp_hand", oppHandSize);
            row.addProperty("opp_hand_free", oppHandFree);
            row.addProperty("opp_lib", oppLibSize);
            row.addProperty("opp_lib_free", oppLibFree);
            row.addProperty("own_lib", ownLibSize);
            row.addProperty("own_lib_free", ownLibFree);
        }
    }

    private static boolean visible(final Card c, final Player me) {
        return c.isFaceDown() || c.getView().canBeShownTo(me.getView());
    }

    private static Set<Integer> fixedIds(final InfoStates is, final Player me, final Player owner) {
        final Set<Integer> out = new HashSet<>();
        if (is == null) {
            return out;
        }
        out.addAll(is.knownIndex(me, owner).keySet());
        for (Set<Integer> run : is.runs(me, owner)) {
            out.addAll(run);
        }
        return out;
    }

    /**
     * Re-deal the hidden cards of {@code g} for the seat {@code me} with {@code seed} (see the class comment). The caller
     * must {@link Deal#restore} the returned deal before the game goes on.
     */
    static Deal redeal(final Game g, final Player me, final RlKnowledge know, final Predicate<Card> remembered,
            final long seed) {
        return redeal(g, me, know, remembered, seed, true, true, false);
    }

    /**
     * As above; {@code opp} / {@code own}: which part is applied (the random stream is the same either way, so the
     * opponent-only and own-only deals are exactly the parts of the full deal with the same seed).
     */
    static Deal redeal(final Game g, final Player me, final RlKnowledge know, final Predicate<Card> remembered,
            final long seed, final boolean opp0, final boolean own) {
        return redeal(g, me, know, remembered, seed, opp0, own, false);
    }

    /**
     * As above; {@code touch}: the TOUCH control: every zone the full deal would rewrite is rewritten quietly with its
     * own cards in their own order (nothing hidden changes), so S against T isolates the hidden cards from the act of
     * rewriting the zones.
     */
    static Deal redeal(final Game g, final Player me, final RlKnowledge know, final Predicate<Card> remembered,
            final long seed, final boolean opp0, final boolean own, final boolean touch) {
        final Deal d = new Deal();
        final Random rng = new Random(seed);
        final InfoStates is = InfoStates.find(g);
        d.is = is;
        final int seat = know == null ? -1 : know.seatOf(me);
        for (Player opp : g.getRegisteredPlayers()) {
            if (opp == me) {
                continue;
            }
            final Zone hz = opp.getZone(ZoneType.Hand);
            final Zone lz = opp.getZone(ZoneType.Library);
            final List<Card> hand = new ArrayList<>(hz.getCards());
            final List<Card> lib = new ArrayList<>(lz.getCards());
            final Set<Integer> fixed = fixedIds(is, me, opp);
            final List<Integer> hFree = new ArrayList<>(), lFree = new ArrayList<>();
            final List<Card> pool = new ArrayList<>();
            for (int i = 0; i < hand.size(); i++) {
                final Card c = hand.get(i);
                if (!visible(c, me) && !remembered.test(c) && !(know != null && know.knows(seat, c))) {
                    hFree.add(i);
                    pool.add(c);
                }
            }
            for (int i = 0; i < lib.size(); i++) {
                final Card c = lib.get(i);
                if (!visible(c, me) && !fixed.contains(c.getId()) && !(know != null && know.knows(seat, c))) {
                    lFree.add(i);
                    pool.add(c);
                }
            }
            d.oppHandSize += hand.size();
            d.oppHandFree += hFree.size();
            d.oppLibSize += lib.size();
            d.oppLibFree += lFree.size();
            if (!touch) {
                pool.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
                Collections.shuffle(pool, rng);
            }
            final List<Card> nh = new ArrayList<>(hand), nl = new ArrayList<>(lib);
            int k = 0;
            for (int i : hFree) {
                nh.set(i, pool.get(k++));
            }
            for (int i : lFree) {
                nl.set(i, pool.get(k++));
            }
            // changed hand cards: the new free hand cards whose names the old free hand did not hold (as multisets)
            final Map<String, Integer> was = new HashMap<>();
            for (int i : hFree) {
                was.merge(hand.get(i).getName(), 1, Integer::sum);
            }
            for (int i : hFree) {
                final String n = nh.get(i).getName();
                final int left = was.getOrDefault(n, 0);
                if (left > 0) {
                    was.put(n, left - 1);
                } else {
                    d.oppHandChanged++;
                }
            }
            for (int i : lFree) {
                if (!nl.get(i).getName().equals(lib.get(i).getName())) {
                    d.oppLibChanged++;
                }
            }
            if (!opp0) {
                d.oppHandChanged = 0;
                d.oppLibChanged = 0;
                continue;
            }
            if (!hFree.isEmpty()) {
                d.set(hz, nh);
            }
            if (!lFree.isEmpty()) {
                d.set(lz, nl);
            }
        }
        final Zone mz = me.getZone(ZoneType.Library);
        final List<Card> lib = new ArrayList<>(mz.getCards());
        final Set<Integer> fixed = fixedIds(is, me, me);
        final List<Integer> free = new ArrayList<>();
        final List<Card> pool = new ArrayList<>();
        for (int i = 0; i < lib.size(); i++) {
            final Card c = lib.get(i);
            if (!visible(c, me) && !fixed.contains(c.getId()) && !(know != null && know.knows(seat, c))) {
                free.add(i);
                pool.add(c);
            }
        }
        d.ownLibSize = lib.size();
        d.ownLibFree = free.size();
        if (!touch) {
            Collections.shuffle(pool, rng);
        }
        final List<Card> nl = new ArrayList<>(lib);
        for (int k = 0; k < free.size(); k++) {
            nl.set(free.get(k), pool.get(k));
            if (!pool.get(k).getName().equals(lib.get(free.get(k)).getName())) {
                d.ownLibChanged++;
            }
        }
        if (!own) {
            d.ownLibChanged = 0;
        } else if (!free.isEmpty()) {
            d.set(mz, nl);
        }
        return d;
    }
}
