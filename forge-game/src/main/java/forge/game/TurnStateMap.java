package forge.game;

import forge.game.card.Card;
import forge.game.card.CardDamageHistory;
import forge.game.player.Player;

/**
 * mtgx (lane copy-fidelity-1010): maps the objects of one game into a copy of it, for the copy's per-turn and history
 * state ({@code copyTurnStateFrom} on {@link Game}, {@link Player}, {@link Card}, {@link CardDamageHistory},
 * {@link forge.game.zone.MagicStack} and {@link forge.game.zone.Zone}). Only the look-ahead's game copier uses it, and
 * only when its copy-fidelity option is on.
 */
public interface TurnStateMap {
    /** The copy's player for a player of the original game (null for null; never null for a seated player). */
    Player player(Player p);

    /**
     * The copy's object for a card object of the original game: its copy when the card is in the copied game (the same
     * object, or the card's current object by id), else a stand-in card of the copy (a last-known-information object of a
     * card that no longer exists, e.g. a token that died). Memoised: one original object, one result. Never null for a
     * non-null card.
     */
    Card card(Card c);

    /** A player as {@link #player}, a card as {@link #card}; null for anything else. */
    default GameEntity entity(final GameEntity e) {
        if (e instanceof Player) {
            return player((Player) e);
        }
        if (e instanceof Card) {
            return card((Card) e);
        }
        return null;
    }

    /** The copy's damage history for a damage history of the original game (a copied card's), or null. */
    CardDamageHistory history(CardDamageHistory h);
}
