package forge.game;

import java.util.List;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * order-memory-1102 (InfoState layer 1; research, nothing calls it unless a listener was added with
 * {@link Game#addLibraryInfoListener}): who learnt what about library cards, at the engine primitive that teaches it,
 * with the observer set taken from the effect. Separate from the game's event bus on purpose: the live host forwards
 * every bus event to the browser and the bench counts every bus event, so an observation there would change client
 * traffic and bench output. A game with no listener builds nothing (every site checks
 * {@link Game#hasLibraryInfoListeners} first).
 *
 * <p>Listeners run on the game thread, inside the effect. They must not change the game, draw from any RNG, or call
 * a player controller. A game copy ({@code GameCopier}) never has listeners.</p>
 */
public interface LibraryInfoListener {
    enum Kind {
        /** {@code GameAction.reveal} / {@code revealTo} of library cards: every observer sees them. */
        REVEAL,
        /** PeekAndReveal's look: only the peeker sees them. */
        PEEK,
        /**
         * The observer (one player) chose the order of these cards going to {@code zone} (orderMoveToZoneList, scry,
         * surveil's kept cards): the cards in the chosen order, as the controller returned them.
         */
        ARRANGE,
        /** These cards are about to go to {@code zone} in a random order nobody chose ({@code CardLists.shuffle}). */
        RANDOM
    }

    /**
     * @param kind what happened
     * @param observers who learnt it (REVEAL/PEEK/ARRANGE); every player for RANDOM
     * @param cards the cards, a copy of the effect's list in its order
     * @param zone the cards' zone (REVEAL/PEEK) or destination (ARRANGE/RANDOM)
     */
    void onLibraryInfo(Kind kind, List<Player> observers, List<Card> cards, ZoneType zone);
}
