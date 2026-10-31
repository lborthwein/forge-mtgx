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

    // ---------------------------------------------------------------- r3-inline-choices-1030 (FLAG-R3-5)

    /**
     * A cost part's cards as Forge AI decided them (exile: Force of Will's pitched card; discard; sacrifice: Thopter
     * Foundry's artifact; tap-type; delve's exiled cards; convoke's tappers), during payment. {@code part} names the
     * part's class ("CostExile", "CostDiscard", "CostSacrifice", "CostTapType", "Delve", "Convoke"); {@code valid} is
     * every card the part could take.
     */
    default CardCollectionView costCards(PlayerControllerAi c, SpellAbility sa, String part, CardCollectionView valid,
            int amount, CardCollectionView def) {
        return def;
    }

    /** One Phyrexian pip Forge AI is about to pay with mana ({@code def} false): true pays 2 life instead. */
    default boolean phyrexianLife(PlayerControllerAi c, SpellAbility sa, boolean def) {
        return def;
    }

    /** choosePermanentsToSacrifice during a resolution (edicts, "sacrifice a creature"). */
    default CardCollectionView sacrifice(PlayerControllerAi c, SpellAbility sa, int min, int max,
            CardCollectionView valid, CardCollectionView def) {
        return def;
    }

    /** The legend rule's "choose one to keep" ({@code def}: Forge AI's). */
    default Card legendKeep(PlayerControllerAi c, List<Card> options, Card def) {
        return def;
    }

    /** The order our simultaneous triggers go on the stack (first = bottom, Forge's convention). */
    default List<SpellAbility> orderTriggers(PlayerControllerAi c, List<SpellAbility> sas, List<SpellAbility> def) {
        return def;
    }

    /** Pay a cost to prevent an effect (Daze, Mana Leak, taxes)? {@code canPay}: payable now. */
    default boolean payToPrevent(PlayerControllerAi c, forge.game.cost.Cost cost, SpellAbility sa, boolean canPay,
            boolean def) {
        return def;
    }

    /** A colour for mana of any colour chosen while an ability resolves (a ritual's, Manamorphose's). */
    default byte chooseColor(PlayerControllerAi c, SpellAbility sa, forge.card.ColorSet colors, byte def) {
        return def;
    }

    /** r3-target-choices-1030: a lone trigger about to go on the stack with Forge AI's targets; may retarget in place. */
    default void triggerTargets(PlayerControllerAi c, SpellAbility sa) {
    }
}
