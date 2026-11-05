package forge.bench.rl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.eventbus.Subscribe;

import forge.bench.BenchSession;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

/**
 * Seat knowledge for observation v1 (interfaces.md Appendix B.2; lane rl-r0-b5-1006). One tracker per live game,
 * installed as the session's {@link BenchSession.KnowledgeObserver} and subscribed to the game's event bus. For each
 * seat it keeps:
 * <ul>
 *   <li>the hidden cards whose identity the seat knows (zone 16: the opponent's hand; zones 17/18: libraries, where the
 *       position is the card's current index, which a seat that knows the card follows through draws and public
 *       moves);</li>
 *   <li>the opponent cards it has seen this game, by name, in first-seen order (the NAME family's candidates);</li>
 * </ul>
 * and for both seats the last {@link #TAIL} spells and abilities put on the stack (zones 20/21).
 *
 * <p><b>Only observed events teach a seat anything:</b> a reveal shown to it (PlayerController.reveal), a look it
 * makes while arranging cards (scry, surveil, "put them back in any order"), and public zone changes (a card from a
 * public zone or its own hand going into a hidden zone it can follow). Knowledge is dropped on a shuffle of that
 * library, when another player rearranges cards it knew, and on any move between hidden zones that it cannot follow.
 * Forge's hidden truth is read for one thing only: the current index of a card the seat already knows.
 *
 * <p><b>Hands (finding F1, lane rl-obs-v2-1006).</b> When a card leaves another player's hand and the seat does not see
 * which card it was (a Brainstorm put-back, a foretell, a morph cast face down), the seat cannot tell whether it was
 * one it knew, so it forgets every card of that hand it knew before the move. Whether the seat saw the card is decided
 * once Forge has finished the move: Forge exiles a foretold card (and a card exiled "face down") face up, then turns it
 * face down. Such moves wait in {@link #pending} until the next query or resolution, and a card that is face down by
 * then was not seen: it does not enter the seen-cards set (NAME) either.
 */
public final class RlKnowledge implements BenchSession.KnowledgeObserver {
    public static final int TAIL = 16;

    /** One stack event (zones 20/21). */
    public static final class StackEvent {
        public final String name;
        /** Forge's full card name (C3: a face resolves through it); for an emblem, its walker's (C4'). */
        public final String fullName;
        public final boolean faceDown;
        public final boolean ability;
        public final int controllerSeat;
        /** v2 only: stack facts recorded at cast, flattened (fact id, arg, num) triples (RlStackFacts). */
        public int[] facts = new int[0];
        /**
         * v2 only: the targets recorded at cast, each {kind, ref, depth, divided}: kind 0 a card (ref = card id), 1 a
         * player (ref = seat), 2 a stack item (ref = stack instance id); depth = the targeting ability's place in the
         * sub-ability chain; divided = the amount assigned, or -1.
         */
        public List<int[]> targets = java.util.Collections.emptyList();

        StackEvent(final String name, final String fullName, final boolean faceDown, final boolean ability,
                final int controllerSeat) {
            this.name = name;
            this.fullName = fullName;
            this.faceDown = faceDown;
            this.ability = ability;
            this.controllerSeat = controllerSeat;
        }
    }

    /** Observation log for witness tests: every fact that taught a seat a card, in order. Null by default. */
    public interface Log {
        void learned(int seat, Card card, String how);

        void forgot(int seat, int cardId, String why);
    }

    private final Game game;
    /** Per seat: the hidden cards it knows, by id, with the event clock of the latest observation that taught it. */
    @SuppressWarnings("unchecked")
    private final Map<Integer, Long>[] known = new Map[] {new HashMap<>(), new HashMap<>()};
    /** Event clock: one tick per zone change. */
    private long clock = 0;

    /**
     * A move whose visibility to {@code seat} is settled later ({@link #settle}): the card entered a zone where Forge
     * may still turn it face down. {@code name}: the seen-cards entry the move added (removed again if the card was not
     * seen); {@code handOwner}: the player whose hand it left, if not the seat itself.
     */
    private static final class Pending {
        final int seat;
        final int cardId;
        final String name;
        final Player handOwner;
        final long at;

        /** v2: the seen-ids entry the move added (removed again if the card was not seen). */
        boolean idAdded;

        Pending(final int seat, final int cardId, final String name, final Player handOwner, final long at) {
            this.seat = seat;
            this.cardId = cardId;
            this.name = name;
            this.handOwner = handOwner;
            this.at = at;
        }
    }

    private final List<Pending> pending = new ArrayList<>();

    /** Observation v2 (lane rl-obs-v2-1006): keep the v2 extras (seen ids, tail facts and targets). Off for v1. */
    public boolean v2 = false;
    /** v2: per seat, the opponent cards it has seen face up, by id, with {name, full name} as seen (zone o_seen). */
    @SuppressWarnings("unchecked")
    private final java.util.LinkedHashMap<Integer, String[]>[] seenIds = new java.util.LinkedHashMap[] {
        new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>()};
    @SuppressWarnings("unchecked")
    private final LinkedHashSet<String>[] seenOpp = new LinkedHashSet[] {new LinkedHashSet<>(), new LinkedHashSet<>()};
    private final Deque<StackEvent> tail = new ArrayDeque<>();
    /**
     * Library cards a seat has just looked at in order to arrange them (scry, surveil, "in any order"): their next move
     * within the library is that arrangement. Any other move within a library (Forge's "in a random order") leaves
     * nobody knowing where the card went. Cleared when the resolving spell or ability finishes.
     */
    private final Set<Integer> arranged = new HashSet<>();
    public Log log;

    /**
     * knowledge-settle-1104 (research; -Dforge.bench.rl.knowledgeSettle=fixed, default off = every path unchanged): the
     * settle schedule of the pending sightings. Off, a query ({@link #knows}, {@link #seenOpponentIds}, ...) settles them
     * against the card's visibility at that moment, so what a frame shows depends on when earlier frames were taken
     * (lane outcome-arm-1104). Fixed: queries never settle; the tracker settles at fixed game points only, every spell
     * resolution (as before) and every priority decision of either seat (a {@link forge.game.PriorityDecisionListener}
     * added by {@link #attach}, ahead of any listener added after it). Then the state at any moment is a function of the
     * game alone, whoever queries it and whenever. Read at construction; tests set {@link #FIXED_SETTLE}.
     */
    public static volatile boolean FIXED_SETTLE = "fixed".equals(System.getProperty("forge.bench.rl.knowledgeSettle"));
    private final boolean fixedSettle;

    /**
     * knowledge-searched-top-1105 (research; -Dforge.bench.rl.knowledgeSearchedTop=true, default off = every path
     * unchanged): a card the seat searched for and put at a set place in a library ("search your library for a card,
     * then shuffle and put that card on top") stays known to it, and to every seat it was revealed to during that
     * resolution. Off, Forge's order (shuffle, then the move within the library) forgets it: the shuffle clears the
     * library and the move counts as one in a random order. The searcher is read from the resolving ability (the rule
     * holds the same in live play, forced replays and copies, where no controller call is made): a hidden-origin
     * ChangeZone from the activator's own library to a library position, with no Defined, Chooser, DefinedPlayer,
     * targets or random order. Read at construction; tests set {@link #SEARCHED_TOP}.
     */
    public static volatile boolean SEARCHED_TOP = "true".equals(System.getProperty("forge.bench.rl.knowledgeSearchedTop"));
    private final boolean searchedTop;
    /** knowledge-searched-top-1105: per card id, the seats it was revealed to during the current resolution. */
    private final Map<Integer, Set<Integer>> revealedInResolution = new HashMap<>();

    public RlKnowledge(final Game game) {
        this.game = game;
        this.fixedSettle = FIXED_SETTLE;
        this.searchedTop = SEARCHED_TOP;
    }

    /** knowledge-searched-top-1105: whether this tracker keeps searched-to-a-place cards (see {@link #SEARCHED_TOP}). */
    public boolean searchedTop() {
        return searchedTop;
    }

    /** knowledge-settle-1104: whether this tracker settles on the fixed schedule (see {@link #FIXED_SETTLE}). */
    public boolean fixedSettle() {
        return fixedSettle;
    }

    /** Subscribe to the game's events (call once, after the game is created). */
    public void attach() {
        game.subscribeToEvents(this);
        if (fixedSettle) {
            // knowledge-settle-1104: settle at every priority decision, before anyone observes it
            game.addPriorityDecisionListener(new forge.game.PriorityDecisionListener() {
                @Override
                public void beforeDecision(final Player p) {
                    settle();
                }

                @Override
                public void afterDecision(final Player p, final List<forge.game.spellability.SpellAbility> chosen) {
                }
            });
        }
    }

    /** A query's settle: the old schedule (settle now) unless this tracker settles on the fixed one. */
    private void querySettle() {
        if (!fixedSettle) {
            settle();
        }
    }

    /**
     * S1 (lane s1-search-1007): this tracker's state carried into a {@link forge.ai.simulation.GameCopier} copy of its
     * game (card and player ids survive the copy), NOT attached. A look-ahead play-out attaches it to the copy so the
     * leaf observation and a policy-piloted seat see what the live seat knew at the root, plus the public events of the
     * play-out. Reads this tracker's fields only (no settle): safe while the live game waits for the search. Reveals
     * and looks inside the copy are not reported (no bridge there).
     */
    public RlKnowledge forkFor(final Game copy) {
        final RlKnowledge k = new RlKnowledge(copy);
        k.v2 = v2;
        k.clock = clock;
        for (int s = 0; s < 2; s++) {
            k.known[s].putAll(known[s]);
            k.seenIds[s].putAll(seenIds[s]);
            k.seenOpp[s].addAll(seenOpp[s]);
        }
        k.tail.addAll(tail);
        k.arranged.addAll(arranged);
        for (Map.Entry<Integer, Set<Integer>> e : revealedInResolution.entrySet()) {
            k.revealedInResolution.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        for (Pending p : pending) {
            Player owner = null;
            if (p.handOwner != null) {
                final int i = game.getRegisteredPlayers().indexOf(p.handOwner);
                owner = i >= 0 && i < copy.getRegisteredPlayers().size() ? copy.getRegisteredPlayers().get(i) : null;
            }
            final Pending q = new Pending(p.seat, p.cardId, p.name, owner, p.at);
            q.idAdded = p.idAdded;
            k.pending.add(q);
        }
        return k;
    }

    public Game game() {
        return game;
    }

    int seatOf(final Player p) {
        return p == null ? -1 : game.getRegisteredPlayers().indexOf(p);
    }

    private Player seatPlayer(final int s) {
        return s >= 0 && s < game.getRegisteredPlayers().size() ? game.getRegisteredPlayers().get(s) : null;
    }

    private Player playerOf(final PlayerView v) {
        if (v == null) {
            return null;
        }
        for (Player p : game.getPlayers()) {
            if (p.getView() == v || p.getView().getId() == v.getId()) {
                return p;
            }
        }
        return null;
    }

    private void learn(final int s, final Card c, final String how) {
        if (s < 0 || s > 1 || c == null) {
            return;
        }
        if (known[s].put(c.getId(), clock) == null && log != null) {
            log.learned(s, c, how);
        }
    }

    private void forget(final int s, final int id, final String why) {
        if (s < 0 || s > 1) {
            return;
        }
        if (known[s].remove(id) != null && log != null) {
            log.forgot(s, id, why);
        }
    }

    /** Adds an opponent card's name to the seen-cards set; returns the name if this call added it, else null. */
    private String see(final int s, final Card c) {
        if (s < 0 || s > 1 || c == null || c.isFaceDown()) {
            return null;
        }
        final forge.card.GamePieceType gp = c.getGamePieceType();
        if (gp == forge.card.GamePieceType.EFFECT || gp == forge.card.GamePieceType.DUNGEON) {
            return null; // emblems, designations, dungeons and Forge's effect objects are not nameable cards
        }
        final Player owner = c.getOwner();
        if (owner != null && seatOf(owner) != s && !c.isToken()) {
            final String n = c.getName();
            return seenOpp[s].add(n) ? n : null;
        }
        return null;
    }

    /** v2: records an opponent card seen face up by id; true if this call added it. */
    private boolean seeId(final int s, final Card c) {
        if (!v2 || s < 0 || s > 1 || c == null || c.isFaceDown() || c.isToken()) {
            return false;
        }
        final forge.card.GamePieceType gp = c.getGamePieceType();
        if (gp == forge.card.GamePieceType.EFFECT || gp == forge.card.GamePieceType.DUNGEON) {
            return false;
        }
        final Player owner = c.getOwner();
        if (owner == null || seatOf(owner) == s) {
            return false;
        }
        return seenIds[s].putIfAbsent(c.getId(), new String[] {c.getName(), RlFeaturizer.fullName(c)}) == null;
    }

    /** F1: seat {@code s} forgets every card of {@code p}'s hand it knew before event {@code at}. */
    private void blurHand(final int s, final Player p, final long at) {
        for (Card h : p.getCardsIn(ZoneType.Hand)) {
            final Long t = known[s].get(h.getId());
            if (t != null && t < at) {
                forget(s, h.getId(), "an unseen card left that hand");
            }
        }
    }

    /** Settles the pending moves: a card that is not face up where the seat can see it now was not seen. */
    private void settle() {
        if (pending.isEmpty()) {
            return;
        }
        final List<Pending> ps = new ArrayList<>(pending);
        pending.clear();
        for (Pending p : ps) {
            final Player me = seatPlayer(p.seat);
            final Card c = game.findById(p.cardId);
            final boolean seen = me != null && c != null && !c.isFaceDown() && c.getView().canBeShownTo(me.getView());
            if (seen) {
                continue;
            }
            if (p.name != null && seenOpp[p.seat].remove(p.name) && log != null) {
                log.forgot(p.seat, p.cardId, "seen name withdrawn: the card never showed face up");
            }
            if (p.idAdded) {
                seenIds[p.seat].remove(p.cardId);
            }
            if (p.handOwner != null) {
                blurHand(p.seat, p.handOwner, p.at);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ queries

    /** Does seat {@code s} know this hidden card's identity (zone 16 / 17 / 18)? */
    public boolean knows(final int s, final Card c) {
        querySettle();
        return s >= 0 && s <= 1 && c != null && known[s].containsKey(c.getId());
    }

    /** The opponent cards seat {@code s} has seen this game, by name, first seen first. */
    public List<String> seenOpponentNames(final int s) {
        querySettle();
        return s >= 0 && s <= 1 ? Collections.unmodifiableList(new ArrayList<>(seenOpp[s])) : Collections.emptyList();
    }

    /** B4's NAME candidates (ICR B4-families-coordination): the same list as {@link #seenOpponentNames}. */
    public List<String> opponentSeen(final int s) {
        return seenOpponentNames(s);
    }

    /**
     * v2: the opponent cards seat {@code s} has seen face up this game, by card id, first seen first, each with
     * {name, full name} as seen. The o_seen zone holds those not shown in the frame by other tokens.
     */
    public Map<Integer, String[]> seenOpponentIds(final int s) {
        querySettle();
        return s >= 0 && s <= 1 ? Collections.unmodifiableMap(new java.util.LinkedHashMap<>(seenIds[s]))
                : Collections.emptyMap();
    }

    /** The last {@link #TAIL} stack events, newest first. */
    public List<StackEvent> tail() {
        querySettle();
        return new ArrayList<>(tail);
    }

    /** Number of known hidden cards (diagnostics). */
    public int knownCount(final int s) {
        querySettle();
        return known[s].size();
    }

    // ------------------------------------------------------------------------------------------------ bridge hooks

    @Override
    public void onReveal(final Game g, final Player viewer, final List<Card> cards, final ZoneType zone,
            final Player owner) {
        if (g != game) {
            return;
        }
        final int s = seatOf(viewer);
        for (Card c : cards) {
            learn(s, c, "reveal:" + zone);
            see(s, c);
            seeId(s, c);
            if (searchedTop && s >= 0 && s <= 1 && c != null && game.getStack().isResolving()) {
                revealedInResolution.computeIfAbsent(c.getId(), x -> new HashSet<>()).add(s);
            }
        }
    }

    @Override
    public void onLook(final Game g, final Player viewer, final List<Card> cards, final ZoneType destination) {
        if (g != game) {
            return;
        }
        final int s = seatOf(viewer);
        for (Card c : cards) {
            see(s, c);
            seeId(s, c);
            if (destination == ZoneType.Library) {
                arranged.add(c.getId());
                learn(s, c, "look");
                // the other seat cannot know the order the looker leaves them in
                for (int t = 0; t < 2; t++) {
                    if (t != s) {
                        forget(t, c.getId(), "rearranged by the other seat");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ events

    private static boolean isPublic(final ZoneType z) {
        return z == ZoneType.Battlefield || z == ZoneType.Graveyard || z == ZoneType.Stack || z == ZoneType.Command;
    }

    @Subscribe
    public void zoneChanged(final GameEventCardChangeZone ev) {
        final CardView cv = ev.card();
        final Card c = cv == null ? null : game.findByView(cv);
        if (c == null) {
            return;
        }
        final ZoneView from = ev.from();
        final ZoneView to = ev.to();
        final ZoneType fz = from == null ? null : from.zoneType();
        final ZoneType tz = to == null ? null : to.zoneType();
        final Player fp = from == null ? null : playerOf(from.player());
        final Player tp = to == null ? null : playerOf(to.player());
        final boolean withinLibrary = fz == ZoneType.Library && tz == ZoneType.Library && fp == tp;
        final boolean arrangedMove = withinLibrary && arranged.remove(c.getId());
        if (searchedTop && CENSUS_FILE != null && withinLibrary) {
            // knowledge-searched-top-1105 census: what the rule does with this move (arranged moves are known anyway)
            if (arrangedMove) {
                census("arranged");
            } else {
                searcherSeat(c, fp);
                final String w = CENSUS_WHY.get();
                final boolean rev = !revealedInResolution.getOrDefault(c.getId(), Collections.emptySet()).isEmpty();
                census("learn".equals(w) ? "learn" : rev ? "learn_revealed_only:" + w : w);
            }
        }
        clock++;
        for (int s = 0; s < 2; s++) {
            final Player me = seatPlayer(s);
            if (me == null) {
                continue;
            }
            final boolean visibleNow = c.getView().canBeShownTo(me.getView()) && !c.isFaceDown();
            // F1: a card leaving another player's hand; seen only if it ends up face up where this seat can see it
            final Player otherHand = fz == ZoneType.Hand && fp != null && fp != me ? fp : null;
            if (visibleNow) {
                final String added = see(s, c);
                final boolean idAdded = seeId(s, c);
                if ((added != null || idAdded || otherHand != null) && mayTurnFaceDown(tz)) {
                    final Pending p = new Pending(s, c.getId(), added, otherHand, clock);
                    p.idAdded = idAdded;
                    pending.add(p);
                }
            } else if (otherHand != null) {
                blurHand(s, otherHand, clock);
            }
            final boolean hiddenNow = tz == ZoneType.Library || (tz == ZoneType.Hand && tp != me)
                    || (tz == ZoneType.Exile && c.isFaceDown());
            if (!hiddenNow) {
                forget(s, c.getId(), "now public or own hand");
                continue;
            }
            final boolean fromSeen = (fz != null && isPublic(fz)) || (fz == ZoneType.Hand && fp == me);
            if (withinLibrary) {
                if (!arrangedMove && searchedTop && (s == searcherSeat(c, fp)
                        || revealedInResolution.getOrDefault(c.getId(), Collections.emptySet()).contains(s))) {
                    // knowledge-searched-top-1105: searched for (or shown) and put at a set place by the effect
                    learn(s, c, "searched or revealed, put at a set library place");
                    continue;
                }
                if (!arrangedMove) {
                    // put back in an order nobody chose or saw (Forge's "in a random order"): the card is still among
                    // those cards, but its position is unknown; obs-v1 has no segment, so it is forgotten
                    forget(s, c.getId(), "moved within the library in a random order");
                }
                continue; // an arrangement: the looker learnt it in onLook, the other seat forgot it there
            }
            if (fromSeen) {
                learn(s, c, fz + "->" + tz);
                if (fz != null && isPublic(fz)) {
                    see(s, c);
                    seeId(s, c);
                }
            } else {
                forget(s, c.getId(), "moved " + fz + "->" + tz + " unseen");
            }
        }
    }

    /**
     * knowledge-searched-top-1105: the seat that searched its own library for {@code c} and is putting it at a set place
     * (the resolving ability's one hidden-origin library-to-library ChangeZone with a LibraryPosition, of the activator's
     * own library, no Defined / ChooseFromDefined / Chooser / DefinedPlayer / targets / RandomOrder), or -1.
     */
    private int searcherSeat(final Card c, final Player libraryOwner) {
        final String[] why = new String[1];
        final int s = searcherSeat(c, libraryOwner, why);
        CENSUS_WHY.set(why[0]);
        return s;
    }

    /** As above; {@code why[0]}: "learn", "not_resolving", "random" (no searched placement) or "unmatched:&lt;shape&gt;". */
    private int searcherSeat(final Card c, final Player libraryOwner, final String[] why) {
        if (!game.getStack().isResolving()) {
            why[0] = "not_resolving";
            return -1;
        }
        forge.game.spellability.SpellAbility root;
        try {
            root = game.getStack().peekAbility();
        } catch (RuntimeException e) {
            why[0] = "not_resolving";
            return -1;
        }
        if (root instanceof forge.game.trigger.WrappedAbility) {
            root = ((forge.game.trigger.WrappedAbility) root).getWrappedAbility();
        }
        forge.game.spellability.SpellAbility hit = null;
        int hits = 0;
        final java.util.TreeSet<String> shape = new java.util.TreeSet<>();
        for (forge.game.spellability.SpellAbility x = root; x != null; x = x.getSubAbility()) {
            if (x.getApi() == forge.game.ability.ApiType.ChangeZone && x.hasParam("Origin")
                    && x.getParam("Origin").contains("Library") && "Library".equals(x.getParam("Destination"))) {
                shape.addAll(x.getMapParams().keySet());
                if (x.isHidden() && x.hasParam("LibraryPosition")) {
                    hit = x;
                    hits++;
                }
            }
        }
        shape.removeAll(java.util.Arrays.asList("SpellDescription", "StackDescription", "AILogic", "SubAbility",
                "PrecostDesc", "CostDesc", "AITgts", "AIPreference"));
        if (hit == null) {
            if (shape.isEmpty()) {
                final java.util.TreeSet<String> apis = new java.util.TreeSet<>();
                for (forge.game.spellability.SpellAbility x = root; x != null; x = x.getSubAbility()) {
                    apis.add(String.valueOf(x.getApi()));
                }
                why[0] = "other_api:" + String.join(",", apis); // no library-to-library ChangeZone: Dig, Shuffle, ...
            } else {
                why[0] = "unmatched:" + String.join(",", shape);
            }
            return -1;
        }
        String reject = null;
        if (hits > 1) {
            reject = "two_searches";
        } else if (hit.hasParam("RandomOrder")) {
            why[0] = "random";
            return -1;
        } else if (hit.hasParam("Defined") || hit.hasParam("ChooseFromDefined") || hit.hasParam("Chooser")
                || hit.hasParam("DefinedPlayer") || hit.usesTargeting()) {
            reject = "param";
        }
        final Player act = hit.getActivatingPlayer();
        if (reject == null && (act == null || act != libraryOwner || c.getOwner() != act)) {
            reject = "not_own_library";
        }
        if (reject != null) {
            why[0] = "unmatched:" + reject + ":" + String.join(",", shape);
            return -1;
        }
        why[0] = "learn";
        return seatOf(act);
    }

    /**
     * knowledge-searched-top-1105 coverage census (diagnostics; only with -Dforge.bench.rl.searchedTopCensus=FILE and the
     * key on): every within-library move of a card, by what the rule did with it (per seat-independent move). Written
     * as JSON at JVM exit.
     */
    static final String CENSUS_FILE = System.getProperty("forge.bench.rl.searchedTopCensus");
    static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> CENSUS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<String> CENSUS_WHY = new ThreadLocal<>();
    static {
        if (CENSUS_FILE != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                for (Map.Entry<String, java.util.concurrent.atomic.AtomicLong> e : new java.util.TreeMap<>(CENSUS)
                        .entrySet()) {
                    o.addProperty(e.getKey(), e.getValue().get());
                }
                try {
                    java.nio.file.Files.write(java.nio.file.Paths.get(CENSUS_FILE + "-" + ProcessHandle.current().pid()
                            + ".json"), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException ex) {
                    System.err.println("[searched-top census] " + ex);
                }
            }));
        }
    }

    private static void census(final String k) {
        CENSUS.computeIfAbsent(k, x -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
    }

    /** Zones where Forge may turn a card face down right after moving it there (foretell, "exile it face down"). */
    private static boolean mayTurnFaceDown(final ZoneType z) {
        return z == ZoneType.Exile || z == ZoneType.Battlefield || z == ZoneType.Stack || z == ZoneType.Command;
    }

    @Subscribe
    public void resolved(final GameEventSpellResolved ev) {
        arranged.clear();
        revealedInResolution.clear();
        settle();
    }

    @Subscribe
    public void shuffled(final GameEventShuffle ev) {
        final Player p = playerOf(ev.player());
        if (p == null) {
            return;
        }
        final List<Card> lib = new ArrayList<>(p.getCardsIn(ZoneType.Library));
        for (int s = 0; s < 2; s++) {
            for (Card c : lib) {
                forget(s, c.getId(), "shuffle");
            }
        }
    }

    @Subscribe
    public void cast(final GameEventSpellAbilityCast ev) {
        try {
            final CardView hv = ev.sa() == null ? null : ev.sa().getHostCard();
            // C4': an "X's Effect" host stands for its source card; an emblem keeps its name with its walker's
            final Card host = RlFeaturizer.shownHost(hv == null ? null : game.findByView(hv));
            final Player act = ev.si() == null ? null : playerOf(ev.si().getActivatingPlayer());
            final boolean faceDown = host == null || host.isFaceDown();
            final String name = host == null ? null : host.getName();
            final String full = host == null ? null : RlFeaturizer.isEmblemName(name)
                    ? RlFeaturizer.emblemWalkerFullName(host) : RlFeaturizer.fullName(host);
            final StackEvent e = new StackEvent(name, full, faceDown, ev.sa() != null && !ev.sa().isSpell(),
                    seatOf(act));
            if (v2 && ev.si() != null) {
                for (forge.game.spellability.SpellAbilityStackInstance si : game.getStack()) {
                    if (si.getId() == ev.si().getId()) {
                        final forge.game.spellability.SpellAbility root = si.getSpellAbility();
                        e.facts = RlStackFacts.facts(root, si.getSourceCard());
                        e.targets = RlStackFacts.targetRefs(game, root);
                        break;
                    }
                }
            }
            tail.addFirst(e);
            while (tail.size() > TAIL) {
                tail.removeLast();
            }
            if (host != null && !faceDown) {
                for (int s = 0; s < 2; s++) {
                    see(s, host);
                    seeId(s, host);
                }
            }
        } catch (RuntimeException ex) {
            // a stack event the tail cannot describe is dropped, never the game
        }
    }
}
