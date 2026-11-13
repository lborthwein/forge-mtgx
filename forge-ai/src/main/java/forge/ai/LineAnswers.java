package forge.ai;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.spellability.SpellAbility;

/**
 * sdcc-tutorcast-1110 (null on every controller by default, and then nothing changes): the answers of a line a seat
 * is following to three asks Forge AI otherwise decides by its own judgement, so a line the forced-win solver verified
 * is replayed the same way in a play-out copy and in the live game. Each method returns null for "not the line's":
 * the ask then goes on exactly as without this. Set on a seat's {@link PlayerControllerAi} by the solver that follows
 * the line ({@link PlayerControllerAi#setLineAnswers}); only a solver with the tutorCast key sets one.
 */
public interface LineAnswers {
    /**
     * An effect offers to cast {@code tgtSA} ("you may cast it"): TRUE = the line casts it now, FALSE = the line
     * declines, null = not the line's.
     */
    Boolean castFromEffect(SpellAbility tgtSA);

    /** A play effect's "which card": the line's card among {@code options}, or null. */
    Card playChoice(SpellAbility sa, Iterable<? extends GameEntity> options);

    /** The cards of a cost part of {@code sa} (all of them in {@code valid}, {@code amount} of them), or null. */
    CardCollectionView costCards(SpellAbility sa, String part, CardCollectionView valid, int amount);

    /**
     * owner-misplays-1113 (the solver's engineModeChoice): the modes of a modal spell or ability the line casts, among
     * {@code possible} (between {@code min} and {@code num} of them), as the line was verified with; null = not the line's.
     */
    default <T> java.util.List<T> modes(SpellAbility sa, java.util.List<T> possible, int min, int num) {
        return null;
    }
}
