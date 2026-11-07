package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * r2-choose-a-card-1029 (research; GOAL N5b): which of a seat's OWN library cards have a position the seat knows, kept
 * from the live game. A card that moves into our library (a Brainstorm put-back, a pile, a bottomed card, a card from a
 * public zone), or that we looked at to arrange it (scry, surveil, Ponder), is known at its current index (draws and
 * moves shift the index; the card's id follows it). A shuffle of our library forgets everything.
 *
 * <p>Why it exists: {@link LookaheadSearch#shuffleOwnLibrary} keeps only the leading prefix that Forge marks visible to
 * the seat, and Forge marks none of the above visible to its owner, so every belief world re-shuffled them away (scry
 * and put-back choices then have no value). {@link #keepKnown} restores them after any such shuffle; the default paths
 * of the label search are unchanged (this class is opt-in).
 */
public final class OwnLibraryMemory {
    private final int seatId;
    private final Set<Integer> known = new HashSet<>();
    private boolean watching = false;
    /** Diagnostics: shuffles seen, cards noted. */
    public int shuffles = 0, noted = 0;

    /**
     * order-memory-1102 (R3 spec {@code knownLib}; null = off, the legacy rule above, byte for byte): the known positions
     * are {@link KnownOwnLibrary}'s instead (the known top and bottom plus non-contiguous fixed offsets). Every card that
     * enters our library is no longer known by default: a random-order block (cascade, discover, hideaway, Atraxa, the
     * Oracle's rest, Endurance) and an order the opponent chose (Portent, their fateseal) stay unknown. Brainstorm,
     * Doomsday, Ponder, scry and tutor tops stay known.
     */
    private final KnownOwnLibrary delegate;

    public OwnLibraryMemory(final Player seat) {
        this(seat, false);
    }

    /** {@code knownLib}: delegate to {@link KnownOwnLibrary} (order-memory-1102); false = the legacy rule. */
    public OwnLibraryMemory(final Player seat, final boolean knownLib) {
        this(seat, knownLib, false);
    }

    /**
     * order-memory-1102 layer 1 (R3 spec {@code infoState}; wins over knownLib): the known positions are the seat's
     * {@link InfoStates} view of its own library (the game's shared instance, attached on {@link #watch}).
     */
    public OwnLibraryMemory(final Player seat, final boolean knownLib, final boolean infoState) {
        this.seatId = seat.getId();
        this.seat = seat;
        this.infoState = infoState;
        this.delegate = knownLib && !infoState ? new KnownOwnLibrary(seat) : null;
    }

    private final Player seat;
    private final boolean infoState;
    /** InfoState mode: the watched game's state, and the cards seeded before the watch. */
    private InfoStates is;
    private final List<Card> seeded = new ArrayList<>();

    /** Is this memory {@link KnownOwnLibrary}'s (the R3 spec's knownLib) or {@link InfoStates}' (infoState)? */
    public boolean delegating() {
        return delegate != null || infoState;
    }

    /** InfoState mode: the game's state this memory reads (null before {@link #watch} or in the other modes). */
    public InfoStates infoStates() {
        return is;
    }

    /** Subscribe to the live game's events (once). Read-only: it never changes the game. */
    public void watch(final Game live) {
        if (!watching) {
            watching = true;
            if (infoState) {
                is = InfoStates.attach(live);
                final Player me = live.getPlayer(seatId);
                if (me != null && !seeded.isEmpty()) {
                    is.pin(me, me, seeded);
                }
            } else if (delegate != null) {
                delegate.watch(live);
            } else {
                live.subscribeToEvents(this);
            }
        }
    }

    @Subscribe
    public void on(final forge.game.event.GameEventShuffle e) {
        if (e.player() != null && e.player().getId() == seatId) {
            synchronized (known) {
                known.clear();
                shuffles++;
            }
        }
    }

    @Subscribe
    public void on(final forge.game.event.GameEventCardChangeZone e) {
        if (e.card() != null && e.to() != null && e.to().zoneType() == ZoneType.Library && e.to().player() != null
                && e.to().player().getId() == seatId) {
            synchronized (known) {
                known.add(e.card().getId());
                noted++;
            }
        }
    }

    /**
     * The seat looked at these library cards to arrange them (they stay at known positions). Delegating: a no-op (the
     * arrangement's own moves teach {@link KnownOwnLibrary}, which also knows when the order was not the seat's).
     */
    public void noteLooked(final Iterable<Card> cards) {
        if (cards == null || delegate != null || infoState) {
            return;
        }
        synchronized (known) {
            for (Card c : cards) {
                if (c != null) {
                    known.add(c.getId());
                    noted++;
                }
            }
        }
    }

    /**
     * A copy's memory starts from the root's known positions ({@code cards}: an R3 world, an in-line choose-a-card).
     * Legacy: {@link #noteLooked}. Delegating: pinned in the tracker (as top-anchored: a hidden take from the middle can
     * only void more of them, never fewer), before {@link #watch}.
     */
    public void seedKnown(final Iterable<Card> cards) {
        if (infoState) {
            if (cards != null) {
                for (Card c : cards) {
                    seeded.add(c);
                }
                if (is != null) {
                    final Player me = is.watched().getPlayer(seatId);
                    if (me != null) {
                        is.pin(me, me, cards);
                    }
                }
            }
        } else if (delegate == null) {
            noteLooked(cards);
        } else if (cards != null) {
            delegate.pin(cards);
        }
    }

    /** The ids of {@code me}'s library cards (in {@code g}, a copy or the live game) whose positions the seat knows. */
    public Set<Integer> known(final Player me) {
        if (infoState) {
            // read-only when me is a copy's player (InfoStates.known)
            return is == null ? new HashSet<>() : new HashSet<>(is.known(me, me));
        }
        if (delegate != null) {
            if (me.getGame() == delegate.watched()) {
                return new HashSet<>(delegate.known(me));
            }
            // review #168 S2 / S1 re-check: a copy: read the watched game's known ids without mutating, and only when the
            // copy's library is in the watched order (each known card at its computed index); otherwise none
            return new HashSet<>(delegate.knownIn(me));
        }
        final Set<Integer> out = new HashSet<>();
        synchronized (known) {
            for (Card c : me.getCardsIn(ZoneType.Library)) {
                if (known.contains(c.getId())) {
                    out.add(c.getId());
                }
            }
        }
        return out;
    }

    /**
     * Review #168 S3: the caller just reordered the watched game's library with every known card kept at its index (an
     * R3 world's stratify). Delegating: the tracker accepts the order (its pins survive); legacy: nothing to do.
     */
    public void resync() {
        if (infoState) {
            if (is != null && is.watched().getPlayer(seatId) != null) {
                is.resync(is.watched().getPlayer(seatId));
            }
        } else if (delegate != null) {
            delegate.acceptPinnedReorder();
        }
    }

    /** Library ids of {@code me}, top first. */
    public static List<Integer> order(final Player me) {
        final List<Integer> out = new ArrayList<>();
        for (Card c : me.getCardsIn(ZoneType.Library)) {
            out.add(c.getId());
        }
        return out;
    }

    /**
     * After a re-draw shuffled {@code me}'s library: put every card of {@code known} back at its index in {@code before}
     * (the order before the shuffle, top first); the other cards fill the other slots in their shuffled relative order.
     * The library's card set must be unchanged.
     */
    public static void keepKnown(final Player me, final List<Integer> before, final Set<Integer> known) {
        if (known == null || known.isEmpty()) {
            return;
        }
        final List<Card> now = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        if (now.size() != before.size()) {
            return; // not a pure shuffle: leave it
        }
        final Card[] out = new Card[now.size()];
        final java.util.Map<Integer, Card> byId = new java.util.HashMap<>();
        for (Card c : now) {
            byId.put(c.getId(), c);
        }
        for (int i = 0; i < before.size(); i++) {
            if (known.contains(before.get(i))) {
                out[i] = byId.get(before.get(i));
                if (out[i] == null) {
                    return;
                }
            }
        }
        int j = 0;
        for (Card c : now) {
            if (known.contains(c.getId())) {
                continue;
            }
            while (out[j] != null) {
                j++;
            }
            out[j] = c;
        }
        me.getZone(ZoneType.Library).setCards(java.util.Arrays.asList(out));
    }

    /**
     * Shuffle only the unknown slots of {@code me}'s library ({@code known} stay at their indices), with {@code rng}.
     */
    /**
     * order-memory-1102 (infoState consumers): as {@link #shuffleUnknown}, the unknown cards put in a canonical order
     * (by id) before the shuffle, so the result does not depend on their true order (LeakCheck: a world built from an
     * InfoState never reads what it marks unknown).
     */
    public static void shuffleUnknownCanonical(final Player me, final Set<Integer> known, final Random rng) {
        final Set<Integer> k = known == null ? Collections.emptySet() : known;
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        final List<Card> unknown = new ArrayList<>();
        for (Card c : lib) {
            if (!k.contains(c.getId())) {
                unknown.add(c);
            }
        }
        unknown.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        Collections.shuffle(unknown, rng);
        final List<Card> out = new ArrayList<>(lib.size());
        int u = 0;
        for (Card c : lib) {
            out.add(k.contains(c.getId()) ? c : unknown.get(u++));
        }
        me.getZone(ZoneType.Library).setCards(out);
    }

    public static void shuffleUnknown(final Player me, final Set<Integer> known, final Random rng) {
        final List<Integer> before = order(me);
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        Collections.shuffle(lib, rng);
        me.getZone(ZoneType.Library).setCards(lib);
        keepKnown(me, before, known == null ? Collections.emptySet() : known);
    }
}
