package forge.ai;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.mana.Mana;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlayerController;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

/**
 * combo-ai-port-1009: a controller that runs the cube combo policy ({@link CubeComboControl}). Two classes implement it:
 * {@link CubeComboPlayerController} (a {@link PlayerControllerAi}) and {@code forge.bench.CubeComboBridgeController}
 * (the bench's NULL-mode bridge, the class every Forge seat of RlActorBench / RlSimBench plays on). Each overrides the
 * fifteen controller methods the v104 controller overrode by delegating to its {@link #cubeCombo()}, and answers the
 * {@code nativeX} methods with its own superclass's method: the control's fallback is always the seat's native answer.
 *
 * <p>Created only for a seat whose AI profile sets {@link AiProps#CUBE_COMBO_PLANS}; never for a bridged (RL or record)
 * seat.
 */
public interface CubeComboSeat {
    CubeComboControl cubeCombo();

    PlayerController asController();

    List<SpellAbility> nativeChooseSpellAbilityToPlay();

    boolean nativePlayChosenSpellAbility(SpellAbility sa);

    Mana nativeChooseManaFromPool(List<Mana> manaChoices);

    boolean nativeConfirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected,
            String question);

    void nativeOrderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs);

    boolean nativeChooseTargetsFor(SpellAbility currentAbility);

    <T extends GameEntity> T nativeChooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal,
            SpellAbility sa, String title, boolean isOptional, Player targetedPlayer, Map<String, Object> params);

    Card nativeChooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider);

    ImmutablePair<CardCollection, CardCollection> nativeArrangeForSurveil(CardCollection topN);

    CardCollectionView nativeOrderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source);

    CardCollection nativeChooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min, int max,
            CardCollectionView visibleToChooser);

    CardCollectionView nativeChooseCardsToDiscardToMaximumHandSize(int numDiscard);

    boolean nativeConfirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
            Card cardToShow, Map<String, Object> params);

    boolean nativeConfirmTrigger(WrappedAbility wrapper);

    boolean nativeChooseBinary(SpellAbility sa, String question, PlayerController.BinaryChoiceType kindOfChoice,
            Boolean defaultVal);

    boolean nativeOrderedMoveToTopOfLibrary(ZoneType destinationZone, SpellAbility source);
}
