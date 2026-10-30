package forge.ai;

import java.util.List;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * r2-choose-a-card-1029 (research): the choose-a-card hook of one live seat's {@link PlayerControllerAi}. Every method
 * receives Forge AI's own answer ({@code def}), already computed exactly as without the hook, and returns the answer to
 * play. The defaults return {@code def}: a hook that overrides nothing changes nothing.
 */
public interface CacHook {
    /**
     * Called just before Forge AI computes its own answer to a library-search pick or an order (only when a hook is
     * set): the hook may snapshot the random stream that answer will consume (review #143 S2: the baseline replay).
     */
    default void beforeAsk(PlayerControllerAi c) {
    }

    default Card pick(PlayerControllerAi c, ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, boolean isOptional, Player decider, Card def) {
        return def;
    }

    default CardCollectionView order(PlayerControllerAi c, CardCollectionView cards, ZoneType destination,
            SpellAbility sa, CardCollectionView def) {
        return def;
    }

    default boolean confirm(PlayerControllerAi c, SpellAbility sa, PlayerActionConfirmMode mode, String message,
            boolean def) {
        return def;
    }

    default ImmutablePair<CardCollection, CardCollection> scry(PlayerControllerAi c, CardCollection topN,
            ImmutablePair<CardCollection, CardCollection> def) {
        return def;
    }

    default ImmutablePair<CardCollection, CardCollection> surveil(PlayerControllerAi c, CardCollection topN,
            ImmutablePair<CardCollection, CardCollection> def) {
        return def;
    }

    default CardCollection discard(PlayerControllerAi c, Player discarding, SpellAbility sa, CardCollection valid,
            int min, int max, CardCollection def) {
        return def;
    }

    default CardCollectionView tuck(PlayerControllerAi c, CardCollectionView hand, int k, CardCollectionView def) {
        return def;
    }

    default List<SpellAbility> priority(PlayerControllerAi c, List<SpellAbility> def) {
        return def;
    }

    /** A Dig-style pick among looked-at cards (chooseEntitiesForEffect of a Dig ability). */
    default List<Card> dig(PlayerControllerAi c, List<Card> options, int min, int max, SpellAbility sa, List<Card> def) {
        return def;
    }

    /** A return-to-hand cost's cards (Gush's Islands): AiCostDecision's choice {@code def}. */
    default CardCollectionView returnCost(PlayerControllerAi c, SpellAbility sa, String type, int amount,
            CardCollectionView def) {
        return def;
    }
}
