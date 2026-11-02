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
 * worlds keep these cards where they are and shuffle only the rest ({@link HonestLabel}); the win solver is told they are
 * placed (its known top and known bottom), so it can plan lines that draw them and cast them (a Doomsday pile drawn by
 * Baleful Strix or Chart a Course, a Brainstorm put-back, a Ponder keep), and its q^8 watch does not count drawing them
 * as a read of a hidden card.
 *
 * <p>Why: Forge marks none of these cards visible to their owner, so the belief worlds (hideOwnLibrary, which keep only
 * the prefix Forge shows the seat) re-shuffled them away, and the label solver (a fresh solver per position, watching a
 * copy) had no pile memory of moves made turns earlier. Rows on held-out Doomsday kill turns found no proposal for any
 * line that casts a card drawn from the pile.
 *
 * <p>Positions are kept relative to the TOP (the longest prefix of known cards) or to the BOTTOM (the longest suffix):
 * draws, mills and takes from below a known prefix never move it, and a card put above or among known cards at an
 * unknown position voids what it could have shifted. A card is known when it enters the seat's library
 * <ul>
 * <li>from a zone the seat sees it in (its own hand, a graveyard, the battlefield, the stack, the command zone, face-up
 * exile) onto the known top (every card above it known: a Brainstorm put-back, a card put on top or a mulligan's
 * bottom), or onto the known bottom; or</li>
 * <li>from the seat's own library onto the known top while a spell or ability the seat controls resolves: a search that
 * puts the card on top (Doomsday's pile, Vampiric, Mystical and Personal Tutor), a look that reorders the top (Ponder,
 * Sensei's Top, Doomsday's own rearrange) and a scry kept on top.</li>
 * </ul>
 * A move within the library to anywhere but the known top (a scry or dig to the bottom: Forge's "in a random order" looks
 * the same), a move the seat does not control (the opponent's Portent) and a card whose identity the seat did not see
 * are unknown, and void what they could have shifted (fails closed). Any shuffle of the library, and any change of it
 * the events do not explain, forgets everything. Read-only: it never changes the game.
 */
public final class KnownOwnLibrary {
    private final int seatId;
    /** Card ids known relative to the top / to the bottom (only their contiguous prefix / suffix counts). */
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
        // explained? the order before, less this card, equals the order now, less this card
        if (snap != null) {
            final List<Integer> exp = new ArrayList<>(snap);
            exp.remove(Integer.valueOf(id));
            final List<Integer> cur = new ArrayList<>(now);
            cur.remove(Integer.valueOf(id));
            if (!exp.equals(cur)) {
                silent++;
                forgetAll();
            }
        } else {
            forgetAll(); // after a shuffle (already forgotten) or an unexplained state: start from nothing known
        }
        top.remove(id);
        bottom.remove(id);
        if (toLib) {
            final int j = now.indexOf(id);
            final boolean seen = fromLib ? ownResolving(game) : seenFrom(e.from(), e.card());
            boolean onTop = seen && j >= 0, onBottom = seen && j >= 0 && !fromLib;
            for (int k = 0; onTop && k < j; k++) {
                onTop = top.contains(now.get(k));
            }
            for (int k = now.size() - 1; onBottom && k > j; k--) {
                onBottom = bottom.contains(now.get(k));
            }
            if (onTop) {
                top.add(id);
                learnt++;
            } else if (onBottom) {
                bottom.add(id);
                learnt++;
            } else {
                // an unknown card (or a known one at an unknown place): it may have shifted every known card below it
                // relative to the top, and every one above it relative to the bottom
                for (int k = 0; k < now.size(); k++) {
                    if (k > j || j < 0) {
                        top.remove(now.get(k));
                    }
                    if (k < j || j < 0) {
                        bottom.remove(now.get(k));
                    }
                }
            }
        }
        snap = now;
    }

    /** Our spell or ability is resolving: its library-to-library moves are the seat's own search, look or order. */
    private boolean ownResolving(final Game g) {
        if (!g.getStack().isResolving() || g.getStack().isEmpty()) {
            return false;
        }
        final SpellAbility sa = g.getStack().peekAbility();
        return sa != null && sa.getActivatingPlayer() != null && sa.getActivatingPlayer().getId() == seatId;
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
                return !cv.isFaceDown();
            default:
                return false;
        }
    }

    /**
     * The ids of {@code me}'s library cards (in the watched game) whose position the seat knows: the known top prefix and
     * the known bottom suffix. An unexplained change since the last event forgets everything first.
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
        int i = 0;
        while (i < now.size() && top.contains(now.get(i))) {
            out.add(now.get(i++));
        }
        for (int k = now.size() - 1; k >= i && bottom.contains(now.get(k)); k--) {
            out.add(now.get(k));
        }
        return out;
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
