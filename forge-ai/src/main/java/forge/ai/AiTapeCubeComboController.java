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
 * Lane bvw-decision-log-1027: the AI tape's controller ({@link AiTapeController}) for a cube combo seat
 * ({@link CubeComboSeat}: the research bench's HonestForcedWin generator seats). CubeComboAi reads a seat's policy through
 * {@code player.getController() instanceof CubeComboSeat}; a plain taping controller would turn the forced-win solver off
 * for the seat it records and change the game. This one is still a cube combo seat: its policy is the wrapped seat's own
 * ({@link #cubeCombo()}), and the native answers are the wrapped seat's. Decisions are taped exactly as for any seat.
 */
public final class AiTapeCubeComboController extends AiTapeController implements CubeComboSeat {
    private final CubeComboSeat seat;

    AiTapeCubeComboController(final PlayerControllerAi inner, final AiTape tape) {
        super(inner, tape);
        this.seat = (CubeComboSeat) inner;
    }

    @Override public CubeComboControl cubeCombo() { return seat.cubeCombo(); }
    @Override public PlayerController asController() { return this; }

    @Override public List<SpellAbility> nativeChooseSpellAbilityToPlay() { return seat.nativeChooseSpellAbilityToPlay(); }
    @Override public boolean nativePlayChosenSpellAbility(final SpellAbility sa) { return seat.nativePlayChosenSpellAbility(sa); }
    @Override public Mana nativeChooseManaFromPool(final List<Mana> manaChoices) { return seat.nativeChooseManaFromPool(manaChoices); }
    @Override public boolean nativeConfirmReplacementEffect(final ReplacementEffect replacementEffect, final SpellAbility effectSA,
            final GameEntity affected, final String question) {
        return seat.nativeConfirmReplacementEffect(replacementEffect, effectSA, affected, question);
    }
    @Override public void nativeOrderAndPlaySimultaneousSa(final List<SpellAbility> activePlayerSAs) {
        seat.nativeOrderAndPlaySimultaneousSa(activePlayerSAs);
    }
    @Override public boolean nativeChooseTargetsFor(final SpellAbility currentAbility) { return seat.nativeChooseTargetsFor(currentAbility); }
    @Override public <T extends GameEntity> T nativeChooseSingleEntityForEffect(final FCollectionView<T> optionList,
            final DelayedReveal delayedReveal, final SpellAbility sa, final String title, final boolean isOptional,
            final Player targetedPlayer, final Map<String, Object> params) {
        return seat.nativeChooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
    }
    @Override public Card nativeChooseSingleCardForZoneChange(final ZoneType destination, final List<ZoneType> origin,
            final SpellAbility sa, final CardCollection fetchList, final DelayedReveal delayedReveal, final String selectPrompt,
            final boolean isOptional, final Player decider) {
        return seat.nativeChooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt,
                isOptional, decider);
    }
    @Override public ImmutablePair<CardCollection, CardCollection> nativeArrangeForSurveil(final CardCollection topN) {
        return seat.nativeArrangeForSurveil(topN);
    }
    @Override public ImmutablePair<CardCollection, CardCollection> nativeArrangeForScry(final CardCollection topN) {
        return seat.nativeArrangeForScry(topN);
    }
    @Override public void nativeDeclareAttackers(final Player attacker, final forge.game.combat.Combat combat) {
        seat.nativeDeclareAttackers(attacker, combat);
    }
    @Override public CardCollectionView nativeOrderMoveToZoneList(final CardCollectionView cards, final ZoneType destinationZone,
            final SpellAbility source) {
        return seat.nativeOrderMoveToZoneList(cards, destinationZone, source);
    }
    @Override public CardCollection nativeChooseCardsToDiscardFrom(final Player p, final SpellAbility sa,
            final CardCollection validCards, final int min, final int max, final CardCollectionView visibleToChooser) {
        return seat.nativeChooseCardsToDiscardFrom(p, sa, validCards, min, max, visibleToChooser);
    }
    @Override public CardCollectionView nativeChooseCardsToDiscardToMaximumHandSize(final int numDiscard) {
        return seat.nativeChooseCardsToDiscardToMaximumHandSize(numDiscard);
    }
    @Override public boolean nativeConfirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String message,
            final List<String> options, final Card cardToShow, final Map<String, Object> params) {
        return seat.nativeConfirmAction(sa, mode, message, options, cardToShow, params);
    }
    @Override public boolean nativeConfirmTrigger(final WrappedAbility wrapper) { return seat.nativeConfirmTrigger(wrapper); }
    @Override public boolean nativeChooseBinary(final SpellAbility sa, final String question,
            final PlayerController.BinaryChoiceType kindOfChoice, final Boolean defaultVal) {
        return seat.nativeChooseBinary(sa, question, kindOfChoice, defaultVal);
    }
    @Override public boolean nativeOrderedMoveToTopOfLibrary(final ZoneType destinationZone, final SpellAbility source) {
        return seat.nativeOrderedMoveToTopOfLibrary(destinationZone, source);
    }
}
