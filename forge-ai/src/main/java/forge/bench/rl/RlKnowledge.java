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

        Pending(final int seat, final int cardId, final String name, final Player handOwner, final long at) {
            this.seat = seat;
            this.cardId = cardId;
            this.name = name;
            this.handOwner = handOwner;
            this.at = at;
        }
    }

    private final List<Pending> pending = new ArrayList<>();
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

    public RlKnowledge(final Game game) {
        this.game = game;
    }

    /** Subscribe to the game's events (call once, after the game is created). */
    public void attach() {
        game.subscribeToEvents(this);
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
            if (p.handOwner != null) {
                blurHand(p.seat, p.handOwner, p.at);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ queries

    /** Does seat {@code s} know this hidden card's identity (zone 16 / 17 / 18)? */
    public boolean knows(final int s, final Card c) {
        settle();
        return s >= 0 && s <= 1 && c != null && known[s].containsKey(c.getId());
    }

    /** The opponent cards seat {@code s} has seen this game, by name, first seen first. */
    public List<String> seenOpponentNames(final int s) {
        settle();
        return s >= 0 && s <= 1 ? Collections.unmodifiableList(new ArrayList<>(seenOpp[s])) : Collections.emptyList();
    }

    /** B4's NAME candidates (ICR B4-families-coordination): the same list as {@link #seenOpponentNames}. */
    public List<String> opponentSeen(final int s) {
        return seenOpponentNames(s);
    }

    /** The last {@link #TAIL} stack events, newest first. */
    public List<StackEvent> tail() {
        settle();
        return new ArrayList<>(tail);
    }

    /** Number of known hidden cards (diagnostics). */
    public int knownCount(final int s) {
        settle();
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
                if ((added != null || otherHand != null) && mayTurnFaceDown(tz)) {
                    pending.add(new Pending(s, c.getId(), added, otherHand, clock));
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
                }
            } else {
                forget(s, c.getId(), "moved " + fz + "->" + tz + " unseen");
            }
        }
    }

    /** Zones where Forge may turn a card face down right after moving it there (foretell, "exile it face down"). */
    private static boolean mayTurnFaceDown(final ZoneType z) {
        return z == ZoneType.Exile || z == ZoneType.Battlefield || z == ZoneType.Stack || z == ZoneType.Command;
    }

    @Subscribe
    public void resolved(final GameEventSpellResolved ev) {
        arranged.clear();
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
            tail.addFirst(e);
            while (tail.size() > TAIL) {
                tail.removeLast();
            }
            if (host != null && !faceDown) {
                for (int s = 0; s < 2; s++) {
                    see(s, host);
                }
            }
        } catch (RuntimeException ex) {
            // a stack event the tail cannot describe is dropped, never the game
        }
    }
}
