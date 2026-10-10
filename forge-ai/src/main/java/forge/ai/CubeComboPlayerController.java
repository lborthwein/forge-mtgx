package forge.ai;

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutablePair;

import forge.LobbyPlayer;
import forge.game.Game;
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
 * combo-ai-port-1009: the cube combo policy on a plain {@link PlayerControllerAi} seat (LobbyPlayerAi under an AI
 * profile that sets {@link AiProps#CUBE_COMBO_PLANS}: LookaheadBench / BenchMain "default" seats, any LobbyPlayerAi).
 * Everything this class decides is {@link CubeComboControl}'s; the native answer is PlayerControllerAi's.
 */
public class CubeComboPlayerController extends PlayerControllerAi implements CubeComboSeat {
    private final CubeComboControl combo;

    public CubeComboPlayerController(Game game, Player player, LobbyPlayer lobby) {
        super(game, player, lobby);
        combo = new CubeComboControl(this, player);
    }

    @Override public CubeComboControl cubeCombo() { return combo; }
    @Override public PlayerController asController() { return this; }

    // ------------------------------------------------------------------ the policy's overrides
    @Override public List<SpellAbility> chooseSpellAbilityToPlay() { return combo.chooseSpellAbilityToPlay(); }
    @Override public boolean playChosenSpellAbility(SpellAbility sa) { return combo.playChosenSpellAbility(sa); }
    @Override public Mana chooseManaFromPool(List<Mana> manaChoices) { return combo.chooseManaFromPool(manaChoices); }
    @Override public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
            GameEntity affected, String question) {
        return combo.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
    }
    @Override public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        combo.orderAndPlaySimultaneousSa(activePlayerSAs);
    }
    @Override public boolean chooseTargetsFor(SpellAbility currentAbility) { return combo.chooseTargetsFor(currentAbility); }
    @Override public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList,
            DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer,
            Map<String, Object> params) {
        return combo.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
    }
    @Override public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
            CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        return combo.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                isOptional, decider);
    }
    @Override public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return combo.arrangeForSurveil(topN);
    }
    @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
            SpellAbility source) {
        return combo.orderMoveToZoneList(cards, destinationZone, source);
    }
    @Override public CardCollection chooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards, int min,
            int max, CardCollectionView visibleToChooser) {
        return combo.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
    }
    @Override public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return combo.chooseCardsToDiscardToMaximumHandSize(numDiscard);
    }
    @Override public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        return combo.confirmAction(sa, mode, message, options, cardToShow, params);
    }
    @Override public boolean confirmTrigger(WrappedAbility wrapper) { return combo.confirmTrigger(wrapper); }
    @Override public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice,
            Boolean defaultVal) {
        return combo.chooseBinary(sa, question, kindOfChoice, defaultVal);
    }

    // ------------------------------------------------------------------ the native answers
    @Override public List<SpellAbility> nativeChooseSpellAbilityToPlay() { return super.chooseSpellAbilityToPlay(); }
    @Override public boolean nativePlayChosenSpellAbility(SpellAbility sa) { return super.playChosenSpellAbility(sa); }
    @Override public Mana nativeChooseManaFromPool(List<Mana> manaChoices) { return super.chooseManaFromPool(manaChoices); }
    @Override public boolean nativeConfirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
            GameEntity affected, String question) {
        return super.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
    }
    @Override public void nativeOrderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        super.orderAndPlaySimultaneousSa(activePlayerSAs);
    }
    @Override public boolean nativeChooseTargetsFor(SpellAbility currentAbility) {
        return super.chooseTargetsFor(currentAbility);
    }
    @Override public <T extends GameEntity> T nativeChooseSingleEntityForEffect(FCollectionView<T> optionList,
            DelayedReveal delayedReveal, SpellAbility sa, String title, boolean isOptional, Player targetedPlayer,
            Map<String, Object> params) {
        return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
    }
    @Override public Card nativeChooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
            SpellAbility sa, CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt,
            boolean isOptional, Player decider) {
        return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                isOptional, decider);
    }
    @Override public ImmutablePair<CardCollection, CardCollection> nativeArrangeForSurveil(CardCollection topN) {
        return super.arrangeForSurveil(topN);
    }
    @Override public CardCollectionView nativeOrderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
            SpellAbility source) {
        return super.orderMoveToZoneList(cards, destinationZone, source);
    }
    @Override public CardCollection nativeChooseCardsToDiscardFrom(Player p, SpellAbility sa, CardCollection validCards,
            int min, int max, CardCollectionView visibleToChooser) {
        return super.chooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
    }
    @Override public CardCollectionView nativeChooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        return super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
    }
    @Override public boolean nativeConfirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
            List<String> options, Card cardToShow, Map<String, Object> params) {
        return super.confirmAction(sa, mode, message, options, cardToShow, params);
    }
    @Override public boolean nativeConfirmTrigger(WrappedAbility wrapper) { return super.confirmTrigger(wrapper); }
    @Override public boolean nativeChooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice,
            Boolean defaultVal) {
        return super.chooseBinary(sa, question, kindOfChoice, defaultVal);
    }
    @Override public boolean nativeOrderedMoveToTopOfLibrary(ZoneType destinationZone, SpellAbility source) {
        return orderedMoveToTopOfLibrary(destinationZone, source);
    }
}
