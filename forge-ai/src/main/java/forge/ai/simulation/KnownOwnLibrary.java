package forge.ai.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;

/**
 * known-lib-1102 (GOAL N5b / N10; {@link HonestLabel.Spec#knownLib} only, off by default): which cards of a seat's OWN
 * library the seat knows the position of at the ROOT of a label, kept from the live game's events. The label's belief
 * worlds pin these cards at their index and shuffle only the rest ({@link HonestLabel}); the win solver is told the known
 * top (it plans lines that draw and cast those cards: a Doomsday pile drawn by Baleful Strix, a Brainstorm put-back, a
 * Ponder keep) and pins the others in its own worlds, and its q^8 watch does not count them as hidden.
 *
 * <p>Why: Forge marks none of these cards visible to their owner, so the belief worlds (hideOwnLibrary, which keep only
 * the prefix Forge shows the seat) re-shuffled them away, and the label solver (a fresh solver per position, watching a
 * copy) had no memory of moves made turns earlier.
 *
 * <p><b>The rule</b> (owner, 2026-11-02): a card is known at offset k from the top (or the bottom) iff the seat saw its
 * identity and its resulting place is fixed, whoever controls the effect: the opponent's Memory Lapse or Griptide puts it
 * on top, Teferi, Hero of Dominaria's -3 third from the top. Known positions need not be contiguous (third from the top
 * under two unknown cards). Offsets are anchored to the top or to the bottom and follow the card: draws and mills from the
 * top, cards put on top, on the bottom or at a fixed offset, and any move of a known card shift them by public amounts.
 * <ul>
 * <li><b>Identity seen:</b> the card came from the seat's own hand, a graveyard, the battlefield, the stack or the command
 * zone (exile is never: the event cannot tell face-down, review #163 S3-2); or it was already known; or it moved within
 * the library in the seat's own resolution with no random-order step (a search or look: Doomsday, a tutor to the top,
 * Ponder, Top, a scry).</li>
 * <li><b>Place fixed:</b> the top, the bottom, or a fixed offset (the resolving effect's LibraryPosition).</li>
 * <li><b>Order (review #163 S1-1, S3-1):</b> when a resolution the seat does not order (the opponent's, or one with a
 * random-order step: cascade, Endurance, a dig's random rest, discover) puts a second card into the library, the order
 * of that block is unknown: none of its cards is learnt (the block's size, and so every other offset, stays public).</li>
 * <li><b>Fails closed:</b> a hidden card taken from neither the top nor the bottom (a tutor's take) voids every known card
 * with an unknown card between it and its anchor (it may have been above it); a card put in at an unfixed place voids
 * everything; any shuffle, and any change of the library the events do not explain, forgets everything.</li>
 * </ul>
 * Known limit: a hidden card taken from the very top by a search is read as a take from the top (the seat would not
 * know it came from there); searches nearly always shuffle. Read-only: it never changes the game.
 */
public final class KnownOwnLibrary {
    private final int seatId;
    /** Card ids known at a fixed offset from the top / from the bottom (not necessarily contiguous). */
    private final Set<Integer> top = new HashSet<>(), bottom = new HashSet<>();
    /** The library order after the last event (a silent change forgets everything). */
    private List<Integer> snap = null;
    private boolean watching = false;
    /** The game this tracker watches (set by {@link #watch}). */
    private Game game;
    /** Diagnostics: shuffles seen, cards learnt, unexplained changes. */
    public int shuffles = 0, learnt = 0, silent = 0;

    public KnownOwnLibrary(final Player seat) {
        this.seatId = seat.getId();
    }

    /** Subscribe to the live game's events (once). */
    public void watch(final Game live) {
        if (!watching) {
            watching = true;
            game = live;
            final Player me = live.getPlayer(seatId);
            snap = me == null ? new ArrayList<>() : ids(me);
            live.subscribeToEvents(this);
        }
    }

    static List<Integer> ids(final Player me) {
        final List<Integer> out = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            out.add(c.getId());
        }
        return out;
    }

    private void forgetAll() {
        top.clear();
        bottom.clear();
    }

    private static boolean mine(final ZoneView z, final ZoneType t, final int id) {
        return z != null && z.zoneType() == t && z.player() != null && z.player().getId() == id;
    }

    @Subscribe
    public void on(final forge.game.event.GameEventShuffle e) {
        if (e.player() != null && e.player().getId() == seatId) {
            forgetAll();
            shuffles++;
            final Player me = game == null ? null : game.getPlayer(seatId);
            snap = me == null ? null : ids(me);
        }
    }

    @Subscribe
    public void on(final forge.game.event.GameEventCardChangeZone e) {
        final boolean fromLib = mine(e.from(), ZoneType.Library, seatId), toLib = mine(e.to(), ZoneType.Library, seatId);
        if (!fromLib && !toLib) {
            return;
        }
        try {
            change(e, fromLib, toLib);
        } catch (RuntimeException ex) {
            forgetAll(); // fail closed
            snap = null;
        }
    }

    private void change(final forge.game.event.GameEventCardChangeZone e, final boolean fromLib, final boolean toLib) {
        if (e.card() == null) {
            forgetAll();
            snap = null;
            return;
        }
        final Player me = game == null ? null : game.getPlayer(seatId);
        if (me == null) {
            forgetAll();
            snap = null;
            return;
        }
        final int id = e.card().getId();
        final List<Integer> now = ids(me);
        final List<Integer> before = snap;
        // explained? the order before, less this card, equals the order now, less this card
        if (before != null) {
            final List<Integer> exp = new ArrayList<>(before);
            exp.remove(Integer.valueOf(id));
            final List<Integer> cur = new ArrayList<>(now);
            cur.remove(Integer.valueOf(id));
            if (!exp.equals(cur)) {
                silent++;
                forgetAll();
            }
        } else {
            forgetAll(); // after an unexplained state: start from nothing known
        }
        final boolean wasKnown = top.remove(id) | bottom.remove(id);
        if (fromLib && !wasKnown && before != null) {
            final int i = before.indexOf(id);
            if (i != 0 && i != before.size() - 1) {
                // a hidden card taken from an unknown place: it may have been above (or below) any known card
                voidBehindUnknown(before, i);
            }
        }
        if (toLib) {
            final int j = now.indexOf(id);
            final SpellAbility res = resolvingSa(game);
            if (res != resolution) {
                resolution = res;
                resEntered.clear();
                resCount = 0;
            }
            resCount++;
            final boolean ordered = res == null ? !game.getStack().isResolving() : seatOrders(res);
            final boolean seen = wasKnown || (fromLib ? res != null && ordered : seenFrom(e.from(), e.card()));
            final boolean atTop = j == 0, atBottom = j >= 0 && j == now.size() - 1;
            final boolean fixed = atTop || atBottom || j > 0 && res != null && literalOffset(res, j, now.size());
            boolean learn = seen && fixed;
            if (!ordered && resCount > 1) {
                // a block whose order nobody of ours chose: its set is known, the order of its cards is not
                for (int x : resEntered) {
                    top.remove(x);
                    bottom.remove(x);
                }
                learn = false;
            }
            if (learn) {
                if (atBottom && !atTop) {
                    bottom.add(id);
                } else {
                    top.add(id);
                }
                learnt++;
                if (!ordered) {
                    resEntered.add(id);
                }
            } else if (!fixed) {
                forgetAll(); // put in at a place nobody can name: every offset may have shifted
            }
        }
        snap = now;
    }

    /**
     * A hidden card left {@code before} at index {@code i} (not the top or the bottom): every top-anchored card with an
     * unknown card above it, and every bottom-anchored card with an unknown card below it, may have shifted.
     */
    private void voidBehindUnknown(final List<Integer> before, final int i) {
        boolean unknownAbove = false;
        for (int k = 0; k < before.size(); k++) {
            final int x = before.get(k);
            if (unknownAbove) {
                top.remove(x);
            }
            unknownAbove |= k == i || !top.contains(x) && !bottom.contains(x);
        }
        boolean unknownBelow = false;
        for (int k = before.size() - 1; k >= 0; k--) {
            final int x = before.get(k);
            if (unknownBelow) {
                bottom.remove(x);
            }
            unknownBelow |= k == i || !top.contains(x) && !bottom.contains(x);
        }
    }

    /**
     * Review #163 re-check S2-1: the card's index {@code j} (of {@code size}) is a literal LibraryPosition of the resolving
     * effect (Teferi's "third from the top" = 2, Commit's second = 1; a negative literal counts from the bottom). An X or
     * any computed position is not a fixed offset (the caller then forgets everything).
     */
    static boolean literalOffset(final SpellAbility root, final int j, final int size) {
        for (SpellAbility sa = root; sa != null; sa = sa.getSubAbility()) {
            final String v = sa.getParam("LibraryPosition");
            if (v == null) {
                continue;
            }
            try {
                final int l = Integer.parseInt(v.trim());
                if (l >= 0 ? j == l : j == size + l) {
                    return true;
                }
            } catch (NumberFormatException ex) {
                return false; // computed: fail closed
            }
        }
        return false;
    }

    /** The resolution the last library entry belonged to (identity), and the cards it taught with an order nobody chose. */
    private Object resolution = null;
    private final List<Integer> resEntered = new ArrayList<>();
    /** The cards that entered our library in that resolution so far. */
    private int resCount = 0;

    /**
     * The spell or ability now resolving, or null. Not simply the top of the stack: a resolution can put a spell above
     * itself (cascade casts the card it found before it puts the rest on the bottom), so it is the topmost stack item
     * whose source is the resolving card.
     */
    static SpellAbility resolvingSa(final Game g) {
        if (!g.getStack().isResolving()) {
            return null;
        }
        for (forge.game.spellability.SpellAbilityStackInstance si : g.getStack()) {
            final forge.game.card.Card src = si.getSourceCard();
            if (src != null && g.getStack().isResolving(src)) {
                return si.getSpellAbility();
            }
        }
        return null;
    }

    /** Parameters that put cards into a library in a random order (no player chooses it). */
    static final String[] RANDOM_PARAMS = {"RandomOrder", "RestRandomOrder", "RevealRandomOrder", "ShuffleChangedPile"};

    /**
     * Review #163 S1-1 (and S3-1): the seat chooses the order of the cards this resolution puts into its library: its
     * own spell or ability, with no random-order step anywhere in the chain (cascade, Endurance, a dig's random rest,
     * discover). The opponent's ordering (Agonizing Memories from our hand) is treated as unknown (fail closed: Forge does
     * not show the seat that order).
     */
    boolean seatOrders(final SpellAbility root) {
        if (root.getActivatingPlayer() == null || root.getActivatingPlayer().getId() != seatId) {
            return false;
        }
        return staticallyOrdered(root);
    }

    /** Keys whose value names the SVar a choice or a repeat runs (an unresolvable name there fails closed). */
    static final java.util.Set<String> RUN_KEYS = java.util.Set.of("Choices", "RepeatSubAbility", "Execute", "SubAbility");

    /**
     * Review #163 re-check S1-A: the whole set of sub-abilities the resolution can reach, statically, is free of
     * random-order steps: the sub-ability chain, and every SVar any parameter names (GenericChoice's Choices$,
     * RepeatEach's RepeatSubAbility$, a trigger's Execute$, ...), followed transitively. Fails closed: a random-order
     * parameter or a Discover anywhere, a name a choice or repeat runs that does not resolve, or a scan past its bound.
     */
    static boolean staticallyOrdered(final SpellAbility root) {
        final java.util.ArrayDeque<java.util.Map<String, String>> todo = new java.util.ArrayDeque<>();
        for (SpellAbility sa0 = root; sa0 != null; sa0 = sa0.getSubAbility()) {
            // a triggered ability resolves wrapped: its parameters are the wrapped ability's
            final SpellAbility sa = sa0 instanceof forge.game.trigger.WrappedAbility w ? w.getWrappedAbility() : sa0;
            if (sa.getApi() == forge.game.ability.ApiType.Discover) {
                return false;
            }
            for (String k : RANDOM_PARAMS) {
                if (sa.hasParam(k) || sa0.hasParam(k)) {
                    return false;
                }
            }
            todo.add(new java.util.HashMap<>(sa.getMapParams()));
            if (sa != sa0 && sa.getSubAbility() != null) {
                for (SpellAbility sub = sa.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
                    todo.add(new java.util.HashMap<>(sub.getMapParams()));
                    if (sub.getApi() == forge.game.ability.ApiType.Discover) {
                        return false;
                    }
                }
            }
        }
        final Set<String> seen = new HashSet<>();
        int guard = 0;
        while (!todo.isEmpty()) {
            if (++guard > 256) {
                return false;
            }
            final java.util.Map<String, String> m = todo.pop();
            for (String k : RANDOM_PARAMS) {
                if (m.containsKey(k)) {
                    return false;
                }
            }
            for (String k : new String[] {"DB", "AB", "SP"}) {
                if ("Discover".equals(m.get(k))) {
                    return false;
                }
            }
            for (java.util.Map.Entry<String, String> e : m.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                for (String tok : e.getValue().split("[,\\s]+")) {
                    if (tok.isEmpty() || !seen.add(tok)) {
                        continue;
                    }
                    final String txt = (root instanceof forge.game.trigger.WrappedAbility w ? w.getWrappedAbility() : root).getSVar(tok);
                    if (txt == null || txt.isEmpty()) {
                        if (RUN_KEYS.contains(e.getKey())) {
                            return false; // a sub-ability we cannot read
                        }
                        continue;
                    }
                    final String t = txt.trim();
                    if (t.startsWith("DB$") || t.startsWith("AB$") || t.startsWith("SP$")) {
                        todo.add(parseParams(t));
                    }
                }
            }
        }
        return true;
    }

    /** "DB$ DigUntil | Valid$ Card | ..." as a key-value map (DB -> DigUntil, Valid -> Card, ...). */
    static java.util.Map<String, String> parseParams(final String text) {
        final java.util.Map<String, String> m = new java.util.HashMap<>();
        for (String part : text.split("\\|")) {
            final int i = part.indexOf('$');
            if (i > 0) {
                m.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
            }
        }
        return m;
    }

    /** The seat saw the card where it came from (its own hand, a public zone, face-up exile). */
    private boolean seenFrom(final ZoneView from, final forge.game.card.CardView cv) {
        if (from == null || cv == null) {
            return false;
        }
        switch (from.zoneType()) {
            case Hand:
                return from.player() != null && from.player().getId() == seatId;
            case Graveyard: case Battlefield: case Stack: case Command:
                return true;
            case Exile:
                // review #163 S3-2: the event fires after the move, so the view shows the card as it is in the library,
                // not whether it was face down in exile: fail closed (exile is never a seen origin)
                return false;
            default:
                return false;
        }
    }

    /**
     * The ids of {@code me}'s library cards (in the watched game) whose position the seat knows, top first (each at a
     * fixed offset from the top or the bottom; the label pins them at their index). An unexplained change since the last
     * event forgets everything first.
     */
    public Set<Integer> known(final Player me) {
        final List<Integer> now = ids(me);
        if (snap == null || !snap.equals(now)) {
            if (snap != null) {
                silent++;
            }
            forgetAll();
            snap = now;
        }
        final Set<Integer> out = new LinkedHashSet<>();
        for (int x : now) {
            if (top.contains(x) || bottom.contains(x)) {
                out.add(x);
            }
        }
        return out;
    }

    /**
     * order-memory-1102: a copy's tracker starts knowing these cards (the root's known positions; {@link
     * OwnLibraryMemory#seedKnown}), top-anchored: a hidden take from the middle can only void more of them. Call before
     * {@link #watch}, or with no event since the last one (the snapshot is unchanged).
     */
    public void pin(final Iterable<Card> cards) {
        for (Card c : cards) {
            if (c != null) {
                top.add(c.getId());
            }
        }
    }

    /** The known top count of {@code me}'s library (diagnostics). */
    public int topCount(final Player me) {
        final Set<Integer> k = known(me);
        int i = 0;
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            if (!k.contains(c.getId())) {
                break;
            }
            i++;
        }
        return i;
    }
}
