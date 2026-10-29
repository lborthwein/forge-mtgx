package forge.game.event;

import java.util.List;

import forge.game.card.CardView;
import forge.game.player.PlayerView;

/**
 * A vote (CR 701.38): its start, each vote as it is cast, and its end. Fired by {@code VoteEffect} so that a client
 * can show who voted for what while the remaining players decide (Council's Judgment, Coercive Portal, Tivit).
 *
 * <p>Purely informational: nothing in the rules engine or the AI reads it. It is fired after the engine has recorded
 * the vote and creates no object, id or timestamp and consumes no randomness.</p>
 *
 * @param stage        {@link Stage#STARTED}, {@link Stage#CAST} or {@link Stage#ENDED}
 * @param source       the card whose ability holds the vote
 * @param order        the voting order (the players who vote, starting with the ability's controller); every stage
 * @param voter        CAST: the player whose vote this is ({@code null} otherwise)
 * @param chooser      CAST: the player who chose this vote when another player controls the votes
 *                     ({@code Game.getControlVote}); {@code null} when the voter chose it, and for another stage
 * @param choiceCard   CAST: the card voted for, when the vote is for a card
 * @param choicePlayer CAST: the player voted for, when the vote is for a player
 * @param choiceLabel  CAST: the option voted for, when the vote is for a named option ("Grace", "Condemnation"); also
 *                     the readable name of a card or player choice
 * @param secret       the vote is secret (votes are revealed only at the end)
 */
public record GameEventVote(Stage stage, CardView source, List<PlayerView> order, PlayerView voter,
                            PlayerView chooser, CardView choiceCard, PlayerView choicePlayer, String choiceLabel,
                            boolean secret) implements GameEvent {

    public enum Stage { STARTED, CAST, ENDED }

    public GameEventVote {
        order = order == null ? List.of() : List.copyOf(order);
    }

    @Override
    public <T> T visit(IGameEventVisitor<T> visitor) {
        return visitor.visit(this);
    }

    @Override
    public String toString() {
        return "Vote " + stage + (voter == null ? "" : " " + voter + " -> " + choiceLabel);
    }
}
