package forge.bench.rl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.common.eventbus.Subscribe;

import forge.bench.BenchSession;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
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
    @SuppressWarnings("unchecked")
    private final Set<Integer>[] known = new Set[] {new HashSet<>(), new HashSet<>()};
    @SuppressWarnings("unchecked")
    private final LinkedHashSet<String>[] seenOpp = new LinkedHashSet[] {new LinkedHashSet<>(), new LinkedHashSet<>()};
    private final Deque<StackEvent> tail = new ArrayDeque<>();
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
        if (known[s].add(c.getId()) && log != null) {
            log.learned(s, c, how);
        }
    }

    private void forget(final int s, final int id, final String why) {
        if (s < 0 || s > 1) {
            return;
        }
        if (known[s].remove(id) && log != null) {
            log.forgot(s, id, why);
        }
    }

    private void see(final int s, final Card c) {
        if (s < 0 || s > 1 || c == null || c.isFaceDown()) {
            return;
        }
        final forge.card.GamePieceType gp = c.getGamePieceType();
        if (gp == forge.card.GamePieceType.EFFECT || gp == forge.card.GamePieceType.DUNGEON) {
            return; // emblems, designations, dungeons and Forge's effect objects are not nameable cards
        }
        final Player owner = c.getOwner();
        if (owner != null && seatOf(owner) != s && !c.isToken()) {
            seenOpp[s].add(c.getName());
        }
    }

    // ------------------------------------------------------------------------------------------------ queries

    /** Does seat {@code s} know this hidden card's identity (zone 16 / 17 / 18)? */
    public boolean knows(final int s, final Card c) {
        return s >= 0 && s <= 1 && c != null && known[s].contains(c.getId());
    }

    /** The opponent cards seat {@code s} has seen this game, by name, first seen first. */
    public List<String> seenOpponentNames(final int s) {
        return s >= 0 && s <= 1 ? Collections.unmodifiableList(new ArrayList<>(seenOpp[s])) : Collections.emptyList();
    }

    /** B4's NAME candidates (ICR B4-families-coordination): the same list as {@link #seenOpponentNames}. */
    public List<String> opponentSeen(final int s) {
        return seenOpponentNames(s);
    }

    /** The last {@link #TAIL} stack events, newest first. */
    public List<StackEvent> tail() {
        return new ArrayList<>(tail);
    }

    /** Number of known hidden cards (diagnostics). */
    public int knownCount(final int s) {
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
        for (int s = 0; s < 2; s++) {
            final Player me = seatPlayer(s);
            if (me == null) {
                continue;
            }
            final boolean visibleNow = c.getView().canBeShownTo(me.getView()) && !c.isFaceDown();
            if (visibleNow) {
                see(s, c);
            }
            final boolean hiddenNow = tz == ZoneType.Library || (tz == ZoneType.Hand && tp != me)
                    || (tz == ZoneType.Exile && c.isFaceDown());
            if (!hiddenNow) {
                forget(s, c.getId(), "now public or own hand");
                continue;
            }
            final boolean fromSeen = (fz != null && isPublic(fz)) || (fz == ZoneType.Hand && fp == me);
            if (fz == ZoneType.Library && tz == ZoneType.Library && fp == tp) {
                continue; // a rearrangement: the looker learnt it in onLook, the other seat forgot it there
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
