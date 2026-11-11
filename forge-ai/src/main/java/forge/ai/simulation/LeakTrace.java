package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventCardChangeZone;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

/**
 * sdcc-leak-1111, diagnostics only ({@code -Dforge.ai.leakTrace=true}; absent: one static check per game copy, nothing
 * else): which search component reads hidden information from a copy of a live game. A live game is armed with the
 * seat to watch ({@link #arm}); every copy made from it (and every copy of a copy) is tagged with the code that made
 * it. At the FIRST card that leaves the seat's library in a copy, the copy's remaining unknown library cards are
 * compared with the live game's order: the same relative order (four or more unknown cards) means the component read
 * the seat's REAL library order (a shuffled copy matches by chance once in 24 or less). At the first card that
 * leaves an opponent's hand in a copy, the copy's hand is compared with the live hand: the same cards, some of them
 * hidden from the seat, means the component played against the opponent's REAL hand. Counters per tag; never changes
 * a game.
 */
public final class LeakTrace {
    public static final boolean ON = System.getProperty("forge.ai.leakTrace") != null;

    private LeakTrace() {
    }

    private static final class Armed {
        final int seatId;
        volatile Set<Integer> known = Collections.emptySet();

        Armed(final int seatId) {
            this.seatId = seatId;
        }
    }

    private static final Map<Game, Armed> ROOTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Game, Game> ROOT_OF = Collections.synchronizedMap(new WeakHashMap<>());
    /** Per tag: copies, library reads, real-order reads, reads too small to tell, opp-hand reads, real-hand reads. */
    private static final Map<String, long[]> COUNTS = new ConcurrentHashMap<>();
    static final int COPIES = 0, LIB = 1, LIB_REAL = 2, LIB_SMALL = 3, OPP = 4, OPP_REAL = 5, LIB_TOP = 6, N = 7;

    /** Watch {@code seat}'s hidden zones in every copy of {@code live}; {@code known} = library ids the seat knows. */
    public static void arm(final Game live, final Player seat, final Set<Integer> known) {
        if (!ON || live == null || seat == null || ROOT_OF.containsKey(live)) {
            return; // off, or a copy (a solver inside a play-out world): only real games are armed
        }
        dump();
        Armed a = ROOTS.get(live);
        if (a == null || a.seatId != seat.getId()) {
            a = new Armed(seat.getId());
            ROOTS.put(live, a);
        }
        a.known = known == null ? Collections.emptySet() : new HashSet<>(known);
    }

    private static String tag() {
        final StringBuilder sb = new StringBuilder();
        int n = 0;
        String lastClass = null;
        for (StackTraceElement f : new Throwable().getStackTrace()) {
            final String c = f.getClassName();
            if (!c.startsWith("forge.") || c.endsWith("GameCopier") || c.endsWith("LeakTrace")) {
                continue;
            }
            final String simple = c.substring(c.lastIndexOf('.') + 1);
            final String top = simple.contains("$") ? simple.substring(0, simple.indexOf('$')) : simple;
            if (n == 0) {
                sb.append(top).append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
                lastClass = top;
                n++;
            } else if (!top.equals(lastClass)) {
                sb.append(" < ").append(top).append('.').append(f.getMethodName());
                lastClass = top;
                if (++n >= 4) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** Called by {@link GameCopier} for every copy. */
    static void copied(final Game orig, final Game copy) {
        if (orig == null || copy == null) {
            return;
        }
        Game root = ROOT_OF.get(orig);
        if (root == null) {
            root = orig;
        }
        ROOT_OF.put(copy, root); // every copy is recorded, so a copy is never armed as a real game
        final Armed a = ROOTS.get(root);
        if (a == null) {
            return;
        }
        final String tag = tag();
        COUNTS.computeIfAbsent(tag, k -> new long[N])[COPIES]++;
        try {
            copy.subscribeToEvents(new Watch(root, copy, a, tag));
        } catch (RuntimeException e) {
            // diagnostics never break a search
        }
    }

    /** One copy's listener. */
    public static final class Watch {
        private final Game root, copy;
        private final Armed a;
        private final String tag;
        private boolean libDone, oppDone;

        Watch(final Game root, final Game copy, final Armed a, final String tag) {
            this.root = root;
            this.copy = copy;
            this.a = a;
            this.tag = tag;
        }

        private static boolean zone(final ZoneView z, final ZoneType t) {
            return z != null && z.zoneType() == t && z.player() != null;
        }

        @Subscribe
        public void on(final GameEventCardChangeZone e) {
            if (e.card() == null || libDone && oppDone) {
                return;
            }
            try {
                if (!libDone && zone(e.from(), ZoneType.Library) && e.from().player().getId() == a.seatId
                        && !(zone(e.to(), ZoneType.Library) && e.to().player().getId() == a.seatId)) {
                    if (!knownNow().contains(e.card().getId())) {
                        libDone = true; // a card the seat knows (a pile's top) is not a hidden read: wait for the next
                        library(e.card().getId());
                    }
                } else if (!oppDone && zone(e.from(), ZoneType.Hand) && e.from().player().getId() != a.seatId) {
                    oppDone = true;
                    oppHand(e.from().player().getId(), e.card().getId());
                }
            } catch (RuntimeException ex) {
                // diagnostics never break a search
            }
        }

        private Set<Integer> knownNow() {
            Set<Integer> known = a.known;
            final Player liveSeat = root.getPlayer(a.seatId);
            final InfoStates is = InfoStates.find(root);
            if (is != null && liveSeat != null) {
                try {
                    final Set<Integer> k = new HashSet<>(known);
                    k.addAll(is.known(liveSeat, liveSeat));
                    known = k;
                } catch (RuntimeException ex) {
                    // keep the armed set
                }
            }
            return known;
        }

        private void library(final int moved) {
            final Player liveSeat = root.getPlayer(a.seatId), seat = copy.getPlayer(a.seatId);
            if (liveSeat == null || seat == null) {
                return;
            }
            final long[] c = COUNTS.computeIfAbsent(tag, k -> new long[N]);
            final Set<Integer> known = knownNow();
            final List<Integer> here = new ArrayList<>();
            for (Card x : seat.getCardsIn(ZoneType.Library)) {
                if (!known.contains(x.getId())) {
                    here.add(x.getId());
                }
            }
            final Set<Integer> hereSet = new HashSet<>(here);
            final List<Integer> real = new ArrayList<>();
            Integer realTop = null;
            for (Card x : liveSeat.getCardsIn(ZoneType.Library)) {
                if (known.contains(x.getId())) {
                    continue;
                }
                if (realTop == null) {
                    realTop = x.getId();
                }
                if (hereSet.contains(x.getId())) {
                    real.add(x.getId());
                }
            }
            synchronized (c) {
                c[LIB]++;
                if (here.size() < 4 || real.size() != here.size()) {
                    c[LIB_SMALL]++;
                } else if (here.equals(real)) {
                    c[LIB_REAL]++;
                    if (realTop != null && realTop == moved) {
                        c[LIB_TOP]++;
                    }
                }
            }
        }

        private void oppHand(final int oppId, final int moved) {
            final Player liveOpp = root.getPlayer(oppId), opp = copy.getPlayer(oppId), liveSeat = root.getPlayer(a.seatId);
            if (liveOpp == null || opp == null || liveSeat == null) {
                return;
            }
            final Set<Integer> liveHand = new HashSet<>();
            int hidden = 0;
            for (Card x : liveOpp.getCardsIn(ZoneType.Hand)) {
                liveHand.add(x.getId());
                if (!x.getView().canBeShownTo(liveSeat.getView())) {
                    hidden++;
                }
            }
            final Set<Integer> hand = new HashSet<>();
            hand.add(moved);
            for (Card x : opp.getCardsIn(ZoneType.Hand)) {
                hand.add(x.getId());
            }
            final long[] c = COUNTS.computeIfAbsent(tag, k -> new long[N]);
            synchronized (c) {
                c[OPP]++;
                if (hidden >= 1 && liveHand.size() >= 2 && hand.equals(liveHand)) {
                    c[OPP_REAL]++;
                }
            }
        }
    }

    private static final String FILE = System.getProperty("forge.ai.leakTrace");
    private static volatile long lastDump = System.nanoTime();

    /** A file name as the property's value: the counters are written there (FILE.pid) every minute and at exit. */
    private static void dump() {
        if (FILE == null || FILE.isEmpty() || "true".equals(FILE)) {
            return;
        }
        final long now = System.nanoTime();
        if (now - lastDump < 60_000_000_000L) {
            return;
        }
        lastDump = now;
        write();
    }

    private static synchronized void write() {
        try {
            java.nio.file.Files.write(java.nio.file.Paths.get(FILE + "." + ProcessHandle.current().pid()), report("LEAKTRACE", false));
        } catch (java.io.IOException | RuntimeException e) {
            // diagnostics never break a run
        }
    }

    static {
        if (FILE != null && !FILE.isEmpty() && !"true".equals(FILE)) {
            Runtime.getRuntime().addShutdownHook(new Thread(LeakTrace::write));
        }
    }

    /** The counters, one line per tag ({@code prefix} first), and optionally a reset. */
    public static List<String> report(final String prefix, final boolean reset) {
        final List<String> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : new TreeMap<>(COUNTS).entrySet()) {
            final long[] c = e.getValue();
            synchronized (c) {
                out.add(prefix + " copies=" + c[COPIES] + " libReads=" + c[LIB] + " libReal=" + c[LIB_REAL] + " libRealTop=" + c[LIB_TOP]
                        + " libSmall=" + c[LIB_SMALL] + " oppReads=" + c[OPP] + " oppReal=" + c[OPP_REAL] + " | " + e.getKey());
            }
        }
        if (reset) {
            COUNTS.clear();
        }
        return out;
    }
}
