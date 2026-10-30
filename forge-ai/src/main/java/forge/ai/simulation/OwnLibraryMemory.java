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

    public OwnLibraryMemory(final Player seat) {
        this.seatId = seat.getId();
    }

    /** Subscribe to the live game's events (once). Read-only: it never changes the game. */
    public void watch(final Game live) {
        if (!watching) {
            watching = true;
            live.subscribeToEvents(this);
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

    /** The seat looked at these library cards to arrange them (they stay at known positions). */
    public void noteLooked(final Iterable<Card> cards) {
        if (cards == null) {
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

    /** The ids of {@code me}'s library cards (in {@code g}, a copy or the live game) whose positions the seat knows. */
    public Set<Integer> known(final Player me) {
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
    public static void shuffleUnknown(final Player me, final Set<Integer> known, final Random rng) {
        final List<Integer> before = order(me);
        final List<Card> lib = new ArrayList<>(me.getCardsIn(ZoneType.Library));
        Collections.shuffle(lib, rng);
        me.getZone(ZoneType.Library).setCards(lib);
        keepKnown(me, before, known == null ? Collections.emptySet() : known);
    }
}
