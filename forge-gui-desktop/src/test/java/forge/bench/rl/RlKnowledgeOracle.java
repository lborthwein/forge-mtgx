package forge.bench.rl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.eventbus.Subscribe;

import forge.bench.BenchSession;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventShuffle;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Test-scope witness for observation v1 (lane rl-r0-b5-1006): an independent, deliberately simple record of what each
 * seat observed. A hidden card may appear in a seat's zone 16 / 17 / 18 only if that seat observed it (a reveal to it,
 * its own look, or seeing it in a public zone or its own hand as it moved) AFTER the last event that hid it from the
 * seat again (a shuffle of its library, a move between hidden zones the seat did not see, or another seat's
 * rearrangement). It never reads a hidden zone's contents.
 */
public final class RlKnowledgeOracle implements BenchSession.KnowledgeObserver {
    private final Game game;
    private long clock = 0;
    @SuppressWarnings("unchecked")
    private final Map<Integer, Long>[] observed = new Map[] {new HashMap<>(), new HashMap<>()};
    @SuppressWarnings("unchecked")
    private final Map<Integer, Long>[] hidden = new Map[] {new HashMap<>(), new HashMap<>()};

    public RlKnowledgeOracle(final Game game) {
        this.game = game;
        game.subscribeToEvents(this);
    }

    private int seat(final Player p) {
        return game.getRegisteredPlayers().indexOf(p);
    }

    /** May seat {@code s} know hidden card {@code c} right now? */
    public boolean justified(final int s, final Card c) {
        final Long o = observed[s].get(c.getId());
        if (o == null) {
            return false;
        }
        final Long h = hidden[s].get(c.getId());
        return h == null || o > h;
    }

    @Override
    public void onReveal(final Game g, final Player viewer, final List<Card> cards, final ZoneType zone,
            final Player owner) {
        final int s = seat(viewer);
        clock++;
        for (Card c : cards) {
            if (s >= 0) {
                observed[s].put(c.getId(), clock);
            }
        }
    }

    @Override
    public void onLook(final Game g, final Player viewer, final List<Card> cards, final ZoneType dest) {
        final int s = seat(viewer);
        clock++;
        for (Card c : cards) {
            for (int t = 0; t < 2; t++) {
                if (t == s) {
                    observed[t].put(c.getId(), clock + 1);
                } else {
                    hidden[t].put(c.getId(), clock);
                }
            }
        }
        clock++;
    }

    @Subscribe
    public void zone(final GameEventCardChangeZone ev) {
        final Card c = ev.card() == null ? null : game.findByView(ev.card());
        if (c == null) {
            return;
        }
        clock++;
        final ZoneType fz = ev.from() == null ? null : ev.from().zoneType();
        for (int s = 0; s < 2; s++) {
            final Player me = game.getRegisteredPlayers().get(s);
            final boolean ownHandOrigin = fz == ZoneType.Hand && ev.from().player() != null
                    && ev.from().player().getId() == me.getView().getId();
            final boolean publicOrigin = fz == ZoneType.Battlefield || fz == ZoneType.Graveyard
                    || fz == ZoneType.Stack || fz == ZoneType.Command;
            if (ownHandOrigin || publicOrigin) {
                observed[s].put(c.getId(), clock);
            } else if (fz == ZoneType.Library && ev.to() != null && ev.to().zoneType() == ZoneType.Library) {
                // a rearrangement: onLook settled it
            } else {
                hidden[s].put(c.getId(), clock);
            }
        }
    }

    @Subscribe
    public void shuffle(final GameEventShuffle ev) {
        clock++;
        for (Player p : game.getPlayers()) {
            if (p.getView().getId() == ev.player().getId()) {
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    hidden[0].put(c.getId(), clock);
                    hidden[1].put(c.getId(), clock);
                }
            }
        }
    }
}
