package forge.interactive;

import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.bench.rl.RlReviewObserver;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.player.PlayerControllerHuman;

/**
 * GAME REVIEW (lane game-review-1010; off unless {@code -Dforge.interactive.review=<spec>}): the human seat of a
 * replayed live game, observed for its review ({@link RlReviewObserver}).
 *
 * <p>The seat's controller is {@link PlayerControllerHuman} with one addition: what the seat is shown (reveals) and what
 * it looks at to arrange (scry, surveil, an ordered move) is reported to the observer's seat knowledge, as
 * {@code PlayerControllerBridge} reports a bridged seat's to the RL seat. Every decision is the superclass's: the browser
 * (here, the journal) still answers every request.
 */
final class InteractiveReview {
    private InteractiveReview() {
    }

    /** The review spec, or null (off). */
    static String spec() {
        final String v = System.getProperty("forge.interactive.review");
        return v == null || v.isBlank() ? null : v.trim();
    }

    /** The review's search spec ({@code RlLiveSeat.Spec} format), or null. */
    static String searchSpec() {
        final String v = System.getProperty("forge.interactive.reviewSearch");
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * Seat the observed controller on {@code human} (created, not started) and attach the observer. The controller is
     * swapped before anything holds the first one (InteractiveMain reads it right after).
     */
    static RlReviewObserver install(final Game game, final Player human, final int humanSeat, final long seed)
            throws java.io.IOException {
        final RlReviewObserver observer = RlReviewObserver.attach(game, human, humanSeat, seed, spec(), searchSpec());
        final LobbyPlayer lp = human.getController().getLobbyPlayer();
        human.dangerouslySetController(new Controller(game, human, lp, observer));
        return observer;
    }

    /** The human seat's controller, reporting reveals and looks to the review observer. */
    static final class Controller extends PlayerControllerHuman {
        private final RlReviewObserver observer;

        Controller(final Game game, final Player p, final LobbyPlayer lp, final RlReviewObserver observer) {
            super(game, p, lp);
            this.observer = observer;
        }

        @Override
        public void reveal(final CardCollectionView cards, final ZoneType zone, final Player owner, final String message,
                final boolean addSuffix) {
            if (cards != null) {
                observer.onReveal(Lists.newArrayList(cards), zone, owner);
            }
            super.reveal(cards, zone, owner, message, addSuffix);
        }

        @Override
        public void reveal(final List<CardView> cards, final ZoneType zone, final PlayerView owner, final String message,
                final boolean addSuffix) {
            if (cards != null) {
                final List<Card> found = Lists.newArrayList();
                for (CardView v : cards) {
                    final Card c = v == null ? null : getGame().findByView(v);
                    if (c != null) {
                        found.add(c);
                    }
                }
                Player own = null;
                for (Player p : getGame().getPlayers()) {
                    if (owner != null && p.getView() == owner) {
                        own = p;
                    }
                }
                observer.onReveal(found, zone, own);
            }
            super.reveal(cards, zone, owner, message, addSuffix);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForScry(final CardCollection topN) {
            if (topN != null) {
                observer.onLook(Lists.newArrayList(topN), ZoneType.Library);
            }
            return super.arrangeForScry(topN);
        }

        @Override
        public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(final CardCollection topN) {
            if (topN != null) {
                observer.onLook(Lists.newArrayList(topN), ZoneType.Library);
            }
            return super.arrangeForSurveil(topN);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(final CardCollectionView cards, final ZoneType destinationZone,
                final SpellAbility source) {
            if (cards != null) {
                observer.onLook(Lists.newArrayList(cards), destinationZone);
            }
            return super.orderMoveToZoneList(cards, destinationZone, source);
        }
    }
}
