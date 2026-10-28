package forge.game.event;

import forge.game.card.CardView;
import forge.game.zone.ZoneType;

/**
 * A replacement effect changed where a card was going, or stopped it from moving (CR 614.1, 614.6): Containment
 * Priest exiles a creature that would enter the battlefield without being cast, Rest in Peace exiles a card that would
 * go to a graveyard, a flashback spell is exiled instead of going to its owner's graveyard.
 *
 * <p>Fired once the replacement has been applied, so {@code actual} is where the card is now. Purely informational:
 * nothing in the rules engine reads it.</p>
 *
 * @param card      the affected card where it is now
 * @param source    the card whose replacement effect applied (for an effect object, the card that created it)
 * @param from      the zone the card was leaving ({@code null} when it came from nowhere)
 * @param intended  the zone the card would have gone to
 * @param actual    the zone the card is in after the replacement ({@code null} when it no longer exists)
 * @param prevented the move was prevented outright: the card stayed where it was
 */
public record GameEventZoneChangeReplaced(CardView card, CardView source, ZoneType from, ZoneType intended,
                                          ZoneType actual, boolean prevented) implements GameEvent {

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return source + " replaced " + card + " [" + from + "] -> [" + intended + "]: now [" + actual + "]"
                + (prevented ? " (prevented)" : "");
    }
}
