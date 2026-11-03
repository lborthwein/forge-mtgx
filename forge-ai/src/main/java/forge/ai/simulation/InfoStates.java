package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.LibraryInfoListener;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

/**
 * order-memory-1102 layer 1 (ARCH-PROPOSAL-1102; DESIGN-2B): what each seat knows about every library card, per
 * OBSERVER, kept from the game's own primitives. One instance per game ({@link #attach}); research consumers read it
 * behind keys that are off by default. Read-only: it never changes the game, draws no random number and calls no
 * controller.
 *
 * <p><b>State</b>, for each observer seat o and each library L (o's own and the opponent's), per card: {@code TOP(k)}
 * or {@code BOTTOM(k)}, an exact offset maintained from events (positions may be non-contiguous); {@code RUN(r)}, a
 * member of a set-known bottom run whose internal order is unknown; or unknown (absent). "o knows c at a position" iff
 * c is TOP or BOTTOM for o. Offsets are computed from events: the only engine indices read are the landing index of a
 * card put into a library and whether a hidden card left from the top, the bottom or the middle (KnownOwnLibrary's
 * rules); {@link #assertConsistent} (tests) checks every offset against Forge's true index.
 *
 * <p><b>Rules</b> (KnownOwnLibrary's, generalised per observer):
 * <ul>
 * <li>A card put into L is learnt by o iff o saw its identity (o knew it where it came from: o's own hand, a public
 * zone, its exact place in a library; or it was revealed or peeked to o, or arranged by o, in this resolution; or it
 * moved within a library in o's own resolution with no random-order step) and its place is fixed (top, bottom, or the
 * resolving effect's literal LibraryPosition).</li>
 * <li>A resolution o does not order (another seat's, one with a random-order step, a {@code RANDOM} batch) that puts a
 * second card into L teaches o no order: its cards are forgotten (a run of cards o saw going to the bottom becomes a
 * RUN, the set known, the order not).</li>
 * <li>Fails closed: a hidden card taken from the middle voids each known card with an unknown slot between it and its
 * anchor; a card put in at an unnamed place forgets L; a shuffle, and any change the events do not explain (snapshot),
 * forget L for every observer.</li>
 * <li>Channels ({@link LibraryInfoListener}, not the event bus): REVEAL to a player set (GameAction.reveal: every
 * player, the owner included), PEEK (the peeker), ARRANGE (the chooser), RANDOM (a batch shuffled before its moves).
 * A revealed or peeked top (or bottom) run is learnt where it is.</li>
 * <li>Statics (MayLookAt: Courser, Future Sight = everyone; Bolas's Citadel, Mystic Forge = the owner): after every
 * library event, at every priority and at every read, each library's leading run that Forge shows to o
 * ({@code canBeShownTo}) is learnt as TOP(k) for o. Nothing ever clears it on visibility loss: it persists after the
 * static ends until the card moves, an unknown insert could have shifted it, or a shuffle.</li>
 * </ul>
 *
 * <p><b>Threads:</b> fed on the game thread; every public method is synchronized. A query with a player of ANOTHER game
 * (a copy) is read-only against this state (review #168 S2) and keeps the ids found in the copy's library.
 */
public final class InfoStates implements LibraryInfoListener {

    enum Anchor { TOP, BOTTOM, RUN }

    /** One card's position for one observer. RUN: {@code off} = the run's lowest bottom offset (the band's base). */
    static final class Pos {
        final Anchor a;
        int off;
        final int run;

        Pos(final Anchor a, final int off, final int run) {
            this.a = a;
            this.off = off;
            this.run = run;
        }

        Pos copy() {
            return new Pos(a, off, run);
        }

        @Override
        public String toString() {
            return a + "(" + off + (a == Anchor.RUN ? ",r" + run : "") + ")";
        }
    }

    /** What one observer knows of one library, plus its view of the current resolution's block. */
    static final class View {
        final Map<Integer, Pos> pos = new LinkedHashMap<>();
        /** Cards this observer took in, in the current unordered block, and whether each was seen and went to the bottom. */
        final List<Integer> blk = new ArrayList<>();
        boolean blkClean = true;
        int blkRun = -1;

        View copy() {
            final View v = new View();
            for (Map.Entry<Integer, Pos> e : pos.entrySet()) {
                v.pos.put(e.getKey(), e.getValue().copy());
            }
            return v;
        }
    }

    /** A set of card ids valid for one resolution (its key; null = outside any resolution). */
    static final class ResSet {
        Object key = null;
        final Set<Integer> ids = new HashSet<>();
        int batch = 0;

        void add(final Object res, final Iterable<Card> cards) {
            if (res != key) {
                key = res;
                ids.clear();
                batch = 0;
            }
            int n = 0;
            for (Card c : cards) {
                ids.add(c.getId());
                n++;
            }
            batch = Math.max(batch, n);
        }

        boolean has(final Object res, final int id) {
            return res == key && ids.contains(id);
        }

        void clear() {
            key = null;
            ids.clear();
            batch = 0;
        }
    }

    private final Game game;
    /** Registered player ids (the observers and the library owners). */
    private final List<Integer> seats = new ArrayList<>();
    /** (observer, owner) -> view. */
    private final Map<Long, View> views = new HashMap<>();
    /** owner -> the library order after the last event (null: unexplained; start from nothing). */
    private final Map<Integer, List<Integer>> snap = new HashMap<>();
    /** owner -> the resolution the last entry into that library belonged to, and its entries so far. */
    private final Map<Integer, Object> libRes = new HashMap<>();
    private final Map<Integer, Integer> libResCount = new HashMap<>();
    private final Map<Integer, ResSet> revealed = new HashMap<>(), arranged = new HashMap<>();
    private final ResSet random = new ResSet();
    private int nextRun = 1;
    private boolean attached = false;
    /** Diagnostics. */
    public int shuffles = 0, learnt = 0, silent = 0, events = 0, observations = 0;

    private InfoStates(final Game g) {
        this.game = g;
        for (Player p : g.getRegisteredPlayers()) {
            seats.add(p.getId());
            revealed.put(p.getId(), new ResSet());
            arranged.put(p.getId(), new ResSet());
        }
        for (int o : seats) {
            for (int w : seats) {
                views.put(key(o, w), new View());
            }
        }
        for (Player p : g.getRegisteredPlayers()) {
            snap.put(p.getId(), ids(p));
        }
    }

    /** The game's InfoStates, attached on first use (events from then on; attach at game creation to see the mulligan). */
    public static synchronized InfoStates attach(final Game g) {
        final InfoStates found = find(g);
        if (found != null) {
            return found;
        }
        final InfoStates s = new InfoStates(g);
        s.register();
        return s;
    }

    /** The InfoStates attached to {@code g}, or null. */
    public static InfoStates find(final Game g) {
        if (g == null) {
            return null;
        }
        for (LibraryInfoListener l : g.getLibraryInfoListeners()) {
            if (l instanceof InfoStates s) {
                return s;
            }
        }
        return null;
    }

    private void register() {
        if (!attached) {
            attached = true;
            game.subscribeToEvents(this);
            game.addLibraryInfoListener(this);
        }
    }

    /** The game this state watches. */
    public Game watched() {
        return game;
    }

    /**
     * A search copy's state: this state, kept where it holds in the copy's order (a TOP/BOTTOM card at its computed index,
     * a RUN inside its band; anything else is dropped), attached to the copy so it evolves from the copy's own events.
     */
    public synchronized InfoStates forkFor(final Game copy) {
        final InfoStates existing = find(copy);
        final InfoStates s = existing != null ? existing : new InfoStates(copy);
        for (Map.Entry<Long, View> e : views.entrySet()) {
            s.views.put(e.getKey(), e.getValue().copy());
        }
        s.nextRun = nextRun;
        for (int w : seats) {
            final Player cp = copy.getPlayer(w);
            if (cp != null) {
                s.snap.put(w, ids(cp));
                s.prune(w, ids(cp));
            }
        }
        s.register();
        return s;
    }

    static long key(final int observer, final int owner) {
        return ((long) observer << 32) ^ (owner & 0xffffffffL);
    }

    private View view(final int observer, final int owner) {
        return views.computeIfAbsent(key(observer, owner), k -> new View());
    }

    static List<Integer> ids(final Player p) {
        final List<Integer> out = new ArrayList<>();
        for (Card c : p.getCardsIn(ZoneType.Library)) {
            out.add(c.getId());
        }
        return out;
    }

    private void forgetLib(final int owner) {
        for (int o : seats) {
            final View v = view(o, owner);
            v.pos.clear();
            v.blk.clear();
            v.blkClean = true;
            v.blkRun = -1;
        }
    }

    // ------------------------------------------------------------------ offsets

    /** The computed top index of each position entry (RUN: its band's lowest and highest index), for a library of n. */
    private static int absTop(final Pos p, final int n) {
        return p.a == Anchor.TOP ? p.off : n - 1 - p.off;
    }

    private static int runSize(final View v, final int run) {
        int k = 0;
        for (Pos p : v.pos.values()) {
            if (p.a == Anchor.RUN && p.run == run) {
                k++;
            }
        }
        return k;
    }

    /** The slots (top indices) of a library of n this observer can name the card of (exact) or the set of (a RUN band). */
    private static boolean[] knownSlots(final View v, final int n) {
        final boolean[] k = new boolean[Math.max(n, 0)];
        final Map<Integer, Integer> bandSize = new HashMap<>();
        for (Pos p : v.pos.values()) {
            if (p.a == Anchor.RUN) {
                bandSize.merge(p.run, 1, Integer::sum);
            }
        }
        for (Pos p : v.pos.values()) {
            if (p.a == Anchor.RUN) {
                final int hi = n - 1 - p.off, lo = hi - bandSize.get(p.run) + 1;
                for (int i = Math.max(lo, 0); i <= hi && i < n; i++) {
                    k[i] = true;
                }
            } else {
                final int i = absTop(p, n);
                if (i >= 0 && i < n) {
                    k[i] = true;
                }
            }
        }
        return k;
    }

    private static void voidRun(final View v, final int run) {
        v.pos.values().removeIf(p -> p.a == Anchor.RUN && p.run == run);
    }

    /** A card left the library from the top indices [lo, hi] (one slot, or a RUN band) of a library of n. */
    private static void shiftRemove(final View v, final int lo, final int hi, final int n) {
        for (Pos p : v.pos.values()) {
            if (p.a == Anchor.TOP) {
                if (p.off > hi) {
                    p.off--;
                }
            } else {
                // BOTTOM / RUN: the entry's highest top index (a RUN band's highest = its base)
                final int a = n - 1 - p.off;
                if (a < lo) {
                    p.off--;
                }
            }
        }
    }

    /** A card was put in at top index j of a library that had n cards. A RUN band it lands inside is voided. */
    private static void shiftInsert(final View v, final int j, final int n) {
        final Set<Integer> broken = new HashSet<>();
        final Map<Integer, Integer> size = new HashMap<>();
        for (Pos p : v.pos.values()) {
            if (p.a == Anchor.RUN) {
                size.merge(p.run, 1, Integer::sum);
            }
        }
        for (Pos p : v.pos.values()) {
            if (p.a == Anchor.TOP) {
                if (p.off >= j) {
                    p.off++;
                }
            } else if (p.a == Anchor.BOTTOM) {
                if (n - 1 - p.off < j) {
                    p.off++;
                }
            } else {
                final int hi = n - 1 - p.off, lo = hi - size.get(p.run) + 1;
                if (hi < j) {
                    p.off++;
                } else if (lo < j) {
                    broken.add(p.run);
                }
            }
        }
        for (int r : broken) {
            voidRun(v, r);
        }
    }

    /**
     * A hidden card left a library of n from a middle slot: every known entry with an unknown slot between it and its
     * anchor may have shifted (void it); the others stay where they are.
     */
    private static void voidBehindUnknown(final View v, final int n) {
        final boolean[] k = knownSlots(v, n);
        final Map<Integer, Integer> size = new HashMap<>();
        for (Pos p : v.pos.values()) {
            if (p.a == Anchor.RUN) {
                size.merge(p.run, 1, Integer::sum);
            }
        }
        final Set<Integer> brokenRuns = new HashSet<>();
        v.pos.values().removeIf(p -> {
            if (p.a == Anchor.TOP) {
                for (int i = 0; i < p.off && i < n; i++) {
                    if (!k[i]) {
                        return true;
                    }
                }
                return false;
            }
            final int hi = n - 1 - p.off;
            for (int i = hi + 1; i < n; i++) {
                if (!k[i]) {
                    if (p.a == Anchor.RUN) {
                        brokenRuns.add(p.run);
                    }
                    return true;
                }
            }
            return false;
        });
        for (int r : brokenRuns) {
            voidRun(v, r);
        }
    }

    /** Keep only what holds in {@code order} (a TOP/BOTTOM card at its computed index, a RUN inside its band). */
    private void prune(final int owner, final List<Integer> order) {
        final int n = order.size();
        for (int o : seats) {
            final View v = view(o, owner);
            final Map<Integer, Integer> size = new HashMap<>();
            for (Pos p : v.pos.values()) {
                if (p.a == Anchor.RUN) {
                    size.merge(p.run, 1, Integer::sum);
                }
            }
            final Set<Integer> brokenRuns = new HashSet<>();
            v.pos.entrySet().removeIf(e -> {
                final int i = order.indexOf(e.getKey());
                final Pos p = e.getValue();
                if (i < 0) {
                    if (p.a == Anchor.RUN) {
                        brokenRuns.add(p.run);
                    }
                    return true;
                }
                if (p.a == Anchor.RUN) {
                    final int hi = n - 1 - p.off, lo = hi - size.get(p.run) + 1;
                    if (i < lo || i > hi) {
                        brokenRuns.add(p.run);
                        return true;
                    }
                    return false;
                }
                return absTop(p, n) != i;
            });
            for (int r : brokenRuns) {
                voidRun(v, r);
            }
            v.blk.clear();
            v.blkClean = true;
            v.blkRun = -1;
        }
    }

    // ------------------------------------------------------------------ the static channel

    /** Each library's leading run Forge shows to an observer is learnt as TOP(k) (never cleared on visibility loss). */
    private void scan(final int owner) {
        final Player w = game.getPlayer(owner);
        if (w == null) {
            return;
        }
        final List<Card> lib = new ArrayList<>(w.getCardsIn(ZoneType.Library));
        for (int o : seats) {
            final Player obs = game.getPlayer(o);
            if (obs == null) {
                continue;
            }
            final View v = view(o, owner);
            for (int k = 0; k < lib.size(); k++) {
                final Card c = lib.get(k);
                if (!c.getView().canBeShownTo(obs.getView())) {
                    break;
                }
                final Pos was = v.pos.get(c.getId());
                if (was != null && was.a == Anchor.RUN) {
                    voidRun(v, was.run);
                }
                if (was == null || was.a != Anchor.TOP || was.off != k) {
                    v.pos.put(c.getId(), new Pos(Anchor.TOP, k, 0));
                    learnt++;
                }
            }
        }
    }

    private void scanAll() {
        for (int w : seats) {
            scan(w);
        }
    }

    // ------------------------------------------------------------------ the engine channel

    @Override
    public synchronized void onLibraryInfo(final Kind kind, final List<Player> observers, final List<Card> cards,
            final ZoneType zone) {
        try {
            onLibraryInfo0(kind, observers, cards, zone);
        } catch (RuntimeException e) {
            failClosed(e);
        }
    }

    /** A consumer's read failed: fail closed (synchronized, as every state change). */
    synchronized void failClosedRead(final RuntimeException e) {
        failClosed(e);
    }

    /** Diagnostics: internal failures (each one forgot every library). */
    public int failures = 0;

    /**
     * Review #172 S1 (fail closed): an internal failure forgets every library for every observer (knowing nothing is
     * always safe), logs once, and never reaches the game.
     */
    private void failClosed(final RuntimeException e) {
        if (failures++ == 0) {
            System.err.println("[infostate] internal failure, every library forgotten (logged once): " + e);
        }
        try {
            for (int w : seats) {
                forgetLib(w);
                final Player wp = game.getPlayer(w);
                snap.put(w, wp == null ? null : ids(wp));
            }
            for (ResSet s : revealed.values()) {
                s.clear();
            }
            for (ResSet s : arranged.values()) {
                s.clear();
            }
            random.clear();
            libRes.clear();
            libResCount.clear();
        } catch (RuntimeException ignored) {
            // nothing more to do: the views are cleared first
        }
    }

    private void onLibraryInfo0(final Kind kind, final List<Player> observers, final List<Card> cards,
            final ZoneType zone) {
        observations++;
        final Object res = KnownOwnLibrary.resolvingSa(game);
        switch (kind) {
            case RANDOM:
                random.add(res, cards);
                return;
            case ARRANGE:
                for (Player p : observers) {
                    final ResSet s = arranged.get(p.getId());
                    if (s != null) {
                        s.add(res, cards);
                    }
                }
                return;
            case REVEAL: case PEEK:
                if (zone != ZoneType.Library) {
                    return;
                }
                for (Player p : observers) {
                    final ResSet s = revealed.get(p.getId());
                    if (s != null) {
                        s.add(res, cards);
                    }
                }
                learnRevealed(observers, cards);
                return;
            default:
        }
    }

    /** A revealed or peeked run at the top (or the bottom) of a library is learnt where it is, by every observer. */
    private void learnRevealed(final List<Player> observers, final List<Card> cards) {
        final Map<Integer, List<Integer>> byOwner = new LinkedHashMap<>();
        for (Card c : cards) {
            if (c.getZone() != null && c.getZone().is(ZoneType.Library) && c.getOwner() != null) {
                byOwner.computeIfAbsent(c.getOwner().getId(), k -> new ArrayList<>()).add(c.getId());
            }
        }
        for (Map.Entry<Integer, List<Integer>> e : byOwner.entrySet()) {
            final int w = e.getKey();
            final Player wp = game.getPlayer(w);
            if (wp == null) {
                continue;
            }
            checkExplained(w);
            final List<Integer> order = ids(wp);
            final int n = order.size(), m = e.getValue().size();
            final Set<Integer> set = new HashSet<>(e.getValue());
            final boolean atTop = m <= n && set.equals(new HashSet<>(order.subList(0, m)));
            final boolean atBottom = !atTop && m <= n && set.equals(new HashSet<>(order.subList(n - m, n)));
            if (!atTop && !atBottom) {
                continue; // identity only (a searched card): its place is not shown
            }
            for (Player p : observers) {
                final View v = views.get(key(p.getId(), w));
                if (v == null) {
                    continue;
                }
                for (int id : e.getValue()) {
                    final int i = order.indexOf(id);
                    final Pos was = v.pos.get(id);
                    if (was != null && was.a == Anchor.RUN) {
                        voidRun(v, was.run);
                    }
                    v.pos.put(id, atTop ? new Pos(Anchor.TOP, i, 0) : new Pos(Anchor.BOTTOM, n - 1 - i, 0));
                    learnt++;
                }
            }
        }
    }

    /** The library changed in a way no event explains: forget it for every observer. */
    private void checkExplained(final int owner) {
        final Player wp = game.getPlayer(owner);
        if (wp == null) {
            return;
        }
        final List<Integer> now = ids(wp);
        final List<Integer> s = snap.get(owner);
        if (s == null || !s.equals(now)) {
            if (s != null) {
                silent++;
            }
            forgetLib(owner);
            snap.put(owner, now);
        }
    }

    // ------------------------------------------------------------------ game events

    @Subscribe
    public synchronized void on(final forge.game.event.GameEventPlayerPriority e) {
        try {
            onPriority0();
        } catch (RuntimeException ex) {
            failClosed(ex);
        }
    }

    private void onPriority0() {
        // between actions: the per-resolution channels end; statics have been re-applied
        for (ResSet s : revealed.values()) {
            s.clear();
        }
        for (ResSet s : arranged.values()) {
            s.clear();
        }
        random.clear();
        libRes.clear();
        libResCount.clear();
        for (View v : views.values()) {
            v.blk.clear();
            v.blkClean = true;
            v.blkRun = -1;
        }
        for (int w : seats) {
            checkExplained(w);
        }
        scanAll();
    }

    @Subscribe
    public synchronized void on(final forge.game.event.GameEventShuffle e) {
        if (e.player() == null || !seats.contains(e.player().getId())) {
            return;
        }
        events++;
        try {
            final int w = e.player().getId();
            forgetLib(w);
            shuffles++;
            final Player wp = game.getPlayer(w);
            snap.put(w, wp == null ? null : ids(wp));
            scan(w);
        } catch (RuntimeException ex) {
            failClosed(ex);
        }
    }

    private Integer libOwner(final ZoneView z) {
        if (z == null || z.zoneType() != ZoneType.Library || z.player() == null) {
            return null;
        }
        return seats.contains(z.player().getId()) ? z.player().getId() : null;
    }

    @Subscribe
    public synchronized void on(final forge.game.event.GameEventCardChangeZone e) {
        final Integer fo = libOwner(e.from()), to = libOwner(e.to());
        if (fo == null && to == null) {
            return;
        }
        events++;
        try {
            change(e, fo, to);
        } catch (RuntimeException ex) {
            // fail closed
            if (fo != null) {
                forgetLib(fo);
                snap.put(fo, null);
            }
            if (to != null) {
                forgetLib(to);
                snap.put(to, null);
            }
        }
    }

    private void change(final forge.game.event.GameEventCardChangeZone e, final Integer fo, final Integer to) {
        if (e.card() == null) {
            for (Integer w : new Integer[] {fo, to}) {
                if (w != null) {
                    forgetLib(w);
                    snap.put(w, null);
                }
            }
            return;
        }
        final int id = e.card().getId();
        // explained? each touched library's order before, less this card, equals its order now, less this card
        final Map<Integer, List<Integer>> before = new HashMap<>(), now = new HashMap<>();
        for (Integer w : new Integer[] {fo, to}) {
            if (w == null || now.containsKey(w)) {
                continue;
            }
            final Player wp = game.getPlayer(w);
            final List<Integer> cur = wp == null ? new ArrayList<>() : ids(wp);
            final List<Integer> b = snap.get(w);
            now.put(w, cur);
            if (b != null) {
                final List<Integer> exp = new ArrayList<>(b);
                exp.remove(Integer.valueOf(id));
                final List<Integer> c2 = new ArrayList<>(cur);
                c2.remove(Integer.valueOf(id));
                if (!exp.equals(c2)) {
                    silent++;
                    forgetLib(w);
                    before.put(w, null);
                    continue;
                }
                before.put(w, b);
            } else {
                forgetLib(w); // after an unexplained state: start from nothing known
                before.put(w, null);
            }
        }
        // the removal (per observer)
        final Set<Integer> knewIt = new HashSet<>();
        if (fo != null) {
            final List<Integer> b = before.get(fo);
            final int n = b == null ? 0 : b.size();
            final int i = b == null ? -1 : b.indexOf(id);
            for (int o : seats) {
                final View v = view(o, fo);
                final Pos p = v.pos.remove(id);
                if (b == null || i < 0) {
                    continue;
                }
                if (p != null && p.a != Anchor.RUN) {
                    knewIt.add(o);
                    final int r = absTop(p, n);
                    shiftRemove(v, r, r, n);
                } else if (p != null) {
                    // a member of a set-known run left: the run's order was never known, forget the run
                    final int hi = n - 1 - p.off, lo = hi - runSize(v, p.run); // runSize no longer counts this card
                    voidRun(v, p.run);
                    shiftRemove(v, lo, hi, n);
                } else if (i == 0) {
                    shiftRemove(v, 0, 0, n);
                } else if (i == n - 1) {
                    shiftRemove(v, n - 1, n - 1, n);
                } else {
                    // a hidden card taken from an unknown place: it may have been above (or below) any known card
                    voidBehindUnknown(v, n);
                }
            }
        }
        // the insertion
        if (to != null) {
            final List<Integer> cur = now.get(to);
            final int j = cur.indexOf(id), n1 = cur.size();
            SpellAbility res0 = KnownOwnLibrary.resolvingSa(game);
            if (res0 == null && game.getStack().isResolving()) {
                // a spell that puts itself into a library (Approach of the Second Sun) has left the stack already: its
                // own spell is the resolution (the moved card is the resolving card)
                final Card mc = game.findById(id);
                if (mc != null && game.getStack().isResolving(mc) && mc.getFirstSpellAbility() != null) {
                    res0 = mc.getFirstSpellAbility();
                }
            }
            final SpellAbility res = res0;
            if (libRes.get(to) != res || !libRes.containsKey(to)) {
                libRes.put(to, res);
                libResCount.put(to, 0);
                for (int o : seats) {
                    final View v = view(o, to);
                    v.blk.clear();
                    v.blkClean = true;
                    v.blkRun = -1;
                }
            }
            final int count = libResCount.merge(to, 1, Integer::sum);
            final boolean atTop = j == 0, atBottom = j >= 0 && j == n1 - 1;
            final boolean fixed = j >= 0 && (atTop || atBottom || j > 0 && res != null && fixedOffset(res, j, n1));
            final boolean inRandom = random.has(res, id);
            final boolean resolving = game.getStack().isResolving();
            final boolean statOrdered = res != null && KnownOwnLibrary.staticallyOrdered(res);
            if (!fixed) {
                forgetLib(to); // put in at a place nobody can name: every offset may have shifted
            } else {
                for (int o : seats) {
                    final View v = view(o, to);
                    shiftInsert(v, j, n1 - 1);
                    final boolean arr = arranged.get(o).has(res, id);
                    final boolean ordered = !inRandom && (arr || (res == null ? !resolving
                            : res.getActivatingPlayer() != null && res.getActivatingPlayer().getId() == o && statOrdered));
                    final boolean seen = knewIt.contains(o) || revealed.get(o).has(res, id) || arr
                            || (fo != null ? res != null && ordered : seenFrom(e.from(), o));
                    boolean learn = seen;
                    final boolean block = !ordered && (count > 1 || inRandom && random.batch > 1);
                    if (block) {
                        // a block whose order this observer did not choose: the set may be known, the order is not
                        final boolean clean = v.blkClean && seen && atBottom;
                        if (clean) {
                            if (v.blkRun < 0) {
                                v.blkRun = nextRun++;
                            }
                            for (int x : v.blk) {
                                v.pos.put(x, new Pos(Anchor.RUN, 0, v.blkRun));
                            }
                            v.pos.put(id, new Pos(Anchor.RUN, 0, v.blkRun));
                            // the band is the bottom |run| slots: its base is 0 (shiftInsert moved it to 1)
                            for (Pos p : v.pos.values()) {
                                if (p.a == Anchor.RUN && p.run == v.blkRun) {
                                    p.off = 0;
                                }
                            }
                        } else {
                            for (int x : v.blk) {
                                final Pos p = v.pos.get(x);
                                if (p != null && (p.a != Anchor.RUN || p.run == v.blkRun)) {
                                    v.pos.remove(x);
                                }
                            }
                            if (v.blkRun >= 0) {
                                voidRun(v, v.blkRun);
                            }
                            v.blkRun = -1;
                        }
                        learn = false;
                    }
                    if (learn) {
                        v.pos.put(id, atBottom && !atTop ? new Pos(Anchor.BOTTOM, 0, 0) : new Pos(Anchor.TOP, j, 0));
                        learnt++;
                    }
                    if (!ordered) {
                        v.blk.add(id);
                        v.blkClean &= seen && atBottom;
                        if (!v.blkClean && v.blkRun >= 0) {
                            voidRun(v, v.blkRun);
                            v.blkRun = -1;
                        }
                    }
                }
            }
        }
        for (Map.Entry<Integer, List<Integer>> x : now.entrySet()) {
            snap.put(x.getKey(), x.getValue());
            scan(x.getKey());
        }
    }

    /**
     * The landing index {@code j} (of {@code size}) is a literal LibraryPosition of the resolving effect: KnownOwnLibrary's
     * sub-chain rule, or (order-memory-1102) a literal reachable through the effect's SVars (Approach of the Second
     * Sun's Branch -> GainLife -> ChangeZone LibraryPosition$ 6). Fails closed: any computed LibraryPosition reachable,
     * or a scan past its bound, is no fixed offset.
     */
    static boolean fixedOffset(final SpellAbility root, final int j, final int size) {
        if (KnownOwnLibrary.literalOffset(root, j, size)) {
            return true;
        }
        final SpellAbility base = root instanceof forge.game.trigger.WrappedAbility w ? w.getWrappedAbility() : root;
        final java.util.ArrayDeque<Map<String, String>> todo = new java.util.ArrayDeque<>();
        for (SpellAbility sa = base; sa != null; sa = sa.getSubAbility()) {
            todo.add(new HashMap<>(sa.getMapParams()));
        }
        final Set<String> seen = new HashSet<>();
        boolean match = false;
        int guard = 0;
        while (!todo.isEmpty()) {
            if (++guard > 256) {
                return false;
            }
            final Map<String, String> m = todo.pop();
            final String v = m.get("LibraryPosition");
            if (v != null) {
                try {
                    final int l = Integer.parseInt(v.trim());
                    match |= l >= 0 ? j == l : j == size + l;
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                for (String tok : e.getValue().split("[,\\s]+")) {
                    if (tok.isEmpty() || !seen.add(tok)) {
                        continue;
                    }
                    final String txt = base.getSVar(tok);
                    if (txt != null) {
                        final String t = txt.trim();
                        if (t.startsWith("DB$") || t.startsWith("AB$") || t.startsWith("SP$")) {
                            todo.add(KnownOwnLibrary.parseParams(t));
                        }
                    }
                }
            }
        }
        return match;
    }

    /** The observer saw the card where it came from (its own hand, a public zone). Exile never (it may be face down). */
    private static boolean seenFrom(final ZoneView from, final int observer) {
        if (from == null) {
            return false;
        }
        switch (from.zoneType()) {
            case Hand:
                return from.player() != null && from.player().getId() == observer;
            case Graveyard: case Battlefield: case Stack: case Command:
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ reads

    /** Refresh before a read of the watched game: an unexplained change forgets; statics teach the visible top. */
    private void refresh(final int owner) {
        checkExplained(owner);
        scan(owner);
    }

    /**
     * The ids of {@code owner}'s library cards whose position {@code observer} knows (TOP or BOTTOM), top first. With a
     * player of the watched game: the state refreshed first. With a copy's player: read-only (the watched game's state;
     * empty when the watched library changed unexplained), the ids kept that are in the copy's library.
     */
    public synchronized Set<Integer> known(final Player observer, final Player owner) {
        final Map<Integer, Integer> idx = knownIndex(observer, owner);
        final Set<Integer> out = new LinkedHashSet<>();
        for (Card c : owner.getCardsIn(ZoneType.Library)) {
            if (idx.containsKey(c.getId())) {
                out.add(c.getId());
            }
        }
        return out;
    }

    /** As {@link #known}, each id with its computed top index (in the watched game). */
    public synchronized Map<Integer, Integer> knownIndex(final Player observer, final Player owner) {
        final int w = owner.getId();
        final Map<Integer, Integer> out = new LinkedHashMap<>();
        final View v = views.get(key(observer.getId(), w));
        final Player wp = game.getPlayer(w);
        if (v == null || wp == null) {
            return out;
        }
        if (owner.getGame() == game) {
            refresh(w);
            final int n = ids(wp).size();
            for (Map.Entry<Integer, Pos> e : v.pos.entrySet()) {
                if (e.getValue().a != Anchor.RUN) {
                    out.put(e.getKey(), absTop(e.getValue(), n));
                }
            }
            return sortByIndex(out);
        }
        // a copy: read-only against the watched state, and only when the copy's library is in exactly the watched
        // order (review #171 S1 / #168 S1: a shuffled or reordered copy would pin known cards at moved indices)
        final List<Integer> cur = ids(wp);
        final List<Integer> s = snap.get(w);
        if (s == null || !s.equals(cur) || !ids(owner).equals(cur)) {
            return out;
        }
        final int n = cur.size();
        for (Map.Entry<Integer, Pos> e : v.pos.entrySet()) {
            if (e.getValue().a != Anchor.RUN) {
                out.put(e.getKey(), absTop(e.getValue(), n));
            }
        }
        final Player obs = game.getPlayer(observer.getId());
        if (obs != null) {
            // the static channel, without storing it
            final List<Card> lib = new ArrayList<>(wp.getCardsIn(ZoneType.Library));
            for (int k = 0; k < lib.size() && lib.get(k).getView().canBeShownTo(obs.getView()); k++) {
                out.put(lib.get(k).getId(), k);
            }
        }
        final Set<Integer> inCopy = new HashSet<>(ids(owner));
        out.keySet().retainAll(inCopy);
        return sortByIndex(out);
    }

    private static Map<Integer, Integer> sortByIndex(final Map<Integer, Integer> m) {
        final List<Map.Entry<Integer, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort(Map.Entry.comparingByValue());
        final Map<Integer, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> e : l) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The set-known bottom runs of {@code owner}'s library for {@code observer} (order unknown inside each). */
    public synchronized List<Set<Integer>> runs(final Player observer, final Player owner) {
        if (owner.getGame() == game) {
            refresh(owner.getId());
        } else {
            // a copy: only when its library is in exactly the watched order (review #171 S1)
            final Player wp = game.getPlayer(owner.getId());
            final List<Integer> s = snap.get(owner.getId());
            if (wp == null || s == null || !s.equals(ids(wp)) || !ids(owner).equals(s)) {
                return new ArrayList<>();
            }
        }
        final View v = views.get(key(observer.getId(), owner.getId()));
        final Map<Integer, Set<Integer>> by = new LinkedHashMap<>();
        if (v != null) {
            for (Map.Entry<Integer, Pos> e : v.pos.entrySet()) {
                if (e.getValue().a == Anchor.RUN) {
                    by.computeIfAbsent(e.getValue().run, k -> new LinkedHashSet<>()).add(e.getKey());
                }
            }
        }
        return new ArrayList<>(by.values());
    }

    /** The length of the leading run of {@code owner}'s library that {@code observer} knows (diagnostics, solver tops). */
    public synchronized int topCount(final Player observer, final Player owner) {
        final Set<Integer> k = known(observer, owner);
        int i = 0;
        for (Card c : owner.getCardsIn(ZoneType.Library)) {
            if (!k.contains(c.getId())) {
                break;
            }
            i++;
        }
        return i;
    }

    /**
     * Seed: {@code observer} knows these cards of {@code owner}'s library at their current index (a copy's root
     * knowledge). Call with no unexplained change pending.
     */
    public synchronized void pin(final Player observer, final Player owner, final Iterable<Card> cards) {
        final Player wp = game.getPlayer(owner.getId());
        if (wp == null || cards == null) {
            return;
        }
        refresh(owner.getId());
        final List<Integer> order = ids(wp);
        final View v = view(observer.getId(), owner.getId());
        for (Card c : cards) {
            final int i = c == null ? -1 : order.indexOf(c.getId());
            if (i >= 0) {
                v.pos.put(c.getId(), new Pos(Anchor.TOP, i, 0));
            }
        }
    }

    /**
     * Review #168 S3: the caller reordered {@code owner}'s watched library itself, keeping the known cards at their
     * indices (a pinned shuffle): accept the order; anything that no longer holds is dropped.
     */
    public synchronized void resync(final Player owner) {
        final Player wp = game.getPlayer(owner.getId());
        if (wp == null) {
            return;
        }
        final List<Integer> order = ids(wp);
        if (snap.get(owner.getId()) == null) {
            return; // nothing explained to keep
        }
        prune(owner.getId(), order);
        snap.put(owner.getId(), order);
    }

    /**
     * Tests only: every TOP/BOTTOM offset equals Forge's true index, every RUN member lies inside its band. Throws
     * IllegalStateException naming the first mismatch. Does not refresh (it checks the state as the events left it).
     */
    public synchronized void assertConsistent() {
        for (int o : seats) {
            for (int w : seats) {
                final Player wp = game.getPlayer(w);
                final View v = views.get(key(o, w));
                if (wp == null || v == null) {
                    continue;
                }
                final List<Integer> order = ids(wp);
                final int n = order.size();
                for (Map.Entry<Integer, Pos> e : v.pos.entrySet()) {
                    final int i = order.indexOf(e.getKey());
                    final Pos p = e.getValue();
                    if (i < 0) {
                        throw new IllegalStateException("observer " + o + " library " + w + ": card " + e.getKey() + " "
                                + p + " is not in the library");
                    }
                    if (p.a == Anchor.RUN) {
                        final int hi = n - 1 - p.off, lo = hi - runSize(v, p.run) + 1;
                        if (i < lo || i > hi) {
                            throw new IllegalStateException("observer " + o + " library " + w + ": card " + e.getKey()
                                    + " " + p + " at index " + i + " outside its band [" + lo + "," + hi + "]");
                        }
                    } else if (absTop(p, n) != i) {
                        throw new IllegalStateException("observer " + o + " library " + w + ": card " + e.getKey() + " "
                                + p + " computed index " + absTop(p, n) + " but true index " + i);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ belief worlds (the infoState consumers)

    /**
     * The game's state for a consumer key that is on: the attached one, or one attached now (late: it knows what the
     * statics show from now on, and nothing earlier).
     */
    public static InfoStates forConsumer(final Game live) {
        final InfoStates s = find(live);
        return s != null ? s : attach(live);
    }

    /**
     * Our library in a belief world {@code me} (a copy of {@code liveMe}'s game in the live order): every card
     * InfoState(liveMe) knows stays at its index, each set-known run is permuted within its own slots, and the other
     * cards fill the other slots in a shuffle of a canonical order (by id), so the result never depends on the true
     * order of what is unknown.
     */
    public static void keepOwnLibrary(final InfoStates is, final Player liveMe, final Player me, final java.util.Random rng) {
        // review #172 S2: a live consumer reads find() only; absent (or a failed read) = nothing known
        Map<Integer, Integer> idx = java.util.Collections.emptyMap();
        List<Set<Integer>> runs = java.util.Collections.emptyList();
        if (is != null) {
            try {
                idx = is.knownIndex(liveMe, liveMe);
                runs = is.runs(liveMe, liveMe);
            } catch (RuntimeException e) {
                is.failClosedRead(e);
                idx = java.util.Collections.emptyMap();
                runs = java.util.Collections.emptyList();
            }
        }
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final int n = lib.size();
        final Card[] out = new Card[n];
        final Map<Integer, Card> byId = new HashMap<>();
        for (Card c : lib) {
            byId.put(c.getId(), c);
        }
        final Set<Integer> used = new HashSet<>();
        for (Map.Entry<Integer, Integer> e : idx.entrySet()) {
            final Card c = byId.get(e.getKey());
            final int i = e.getValue();
            if (c != null && i >= 0 && i < n && out[i] == null) {
                out[i] = c;
                used.add(c.getId());
            }
        }
        for (Set<Integer> run : runs) {
            final List<Card> members = new ArrayList<>();
            final List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final Card c = lib.get(i);
                if (run.contains(c.getId()) && !used.contains(c.getId()) && out[i] == null) {
                    members.add(c);
                    slots.add(i);
                }
            }
            members.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
            java.util.Collections.shuffle(members, rng);
            for (int k = 0; k < slots.size(); k++) {
                out[slots.get(k)] = members.get(k);
                used.add(members.get(k).getId());
            }
        }
        final List<Card> rest = new ArrayList<>();
        for (Card c : lib) {
            if (!used.contains(c.getId())) {
                rest.add(c);
            }
        }
        rest.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        java.util.Collections.shuffle(rest, rng);
        int r = 0;
        for (int i = 0; i < n; i++) {
            if (out[i] == null) {
                out[i] = rest.get(r++);
            }
        }
        me.getZone(ZoneType.Library).setCards(java.util.Arrays.asList(out));
    }

    /**
     * The opponent-library cards InfoState(liveMe) knows, as the copy {@code g}'s cards by their index (a Courser top that
     * outlived Courser, a peeked card, a card we put there); belief worlds keep them and draw only the other slots.
     */
    public static Map<Integer, Card> oppPins(final InfoStates is, final Player liveMe, final Player liveOpp, final Game g) {
        final Map<Integer, Card> out = new java.util.TreeMap<>();
        if (is == null) {
            return out; // review #172 S2: not attached = nothing known
        }
        Map<Integer, Integer> idx;
        try {
            idx = is.knownIndex(liveMe, liveOpp);
        } catch (RuntimeException ex) {
            is.failClosedRead(ex);
            return out;
        }
        for (Map.Entry<Integer, Integer> e : idx.entrySet()) {
            final Card c = g.findById(e.getKey());
            if (c != null) {
                out.put(e.getValue(), c);
            }
        }
        return out;
    }

    /** A library of {@code size}: the pins at their indices, the drawn cards in order in the other slots. */
    public static List<Card> assemble(final int size, final Map<Integer, Card> pins, final List<Card> drawn) {
        final List<Card> out = new ArrayList<>(size);
        int d = 0;
        for (int i = 0; i < size; i++) {
            final Card p = pins.get(i);
            if (p != null) {
                out.add(p);
            } else if (d < drawn.size()) {
                out.add(drawn.get(d++));
            }
        }
        while (d < drawn.size()) {
            out.add(drawn.get(d++));
        }
        return out;
    }

    /** Diagnostics: the state as text (observer -> owner -> card:pos). */
    public synchronized String describe() {
        final StringBuilder sb = new StringBuilder();
        for (int o : seats) {
            for (int w : seats) {
                final View v = views.get(key(o, w));
                if (v != null && !v.pos.isEmpty()) {
                    sb.append(o).append("->").append(w).append(v.pos).append(' ');
                }
            }
        }
        return sb.toString().trim();
    }
}
