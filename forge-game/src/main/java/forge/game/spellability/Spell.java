/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.game.spellability;

import java.util.Map;
import java.util.Objects;

import forge.game.card.CardCopyService;

import forge.card.CardStateName;
import forge.game.Game;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.card.ColorSet;
import forge.card.mana.ManaCost;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.player.PlayerController.FullControlFlag;
import forge.game.replacement.ReplacementType;
import forge.game.staticability.StaticAbilityCantBeCast;
import forge.game.zone.ZoneType;

/**
 * <p>
 * Abstract Spell class.
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public abstract class Spell extends SpellAbility implements java.io.Serializable, Cloneable {

    /** Constant <code>serialVersionUID=-7930920571482203460L</code>. */
    private static final long serialVersionUID = -7930920571482203460L;

    private static boolean performanceMode = false;

    public static void setPerformanceMode(boolean performanceMode){
        Spell.performanceMode=performanceMode;
    }

    public static boolean isPerformanceMode() {
        return performanceMode;
    }

    private boolean castFaceDown = false;

    public Spell(final Card sourceCard, final Cost abCost) {
        super(sourceCard, abCost);

        this.setStackDescription(sourceCard.getSpellText());
        this.getRestrictions().setZone(ZoneType.Hand);
    }

    /** {@inheritDoc} */
    @Override
    public boolean canPlay() {
        return canPlayFromHost() != null;
    }

    public Card canPlayFromHost() {
        return canPlayFromHost(false);
    }

    public Card canPlayFromHostForEnumeration() {
        return canPlayFromHost(true);
    }

    private Card canPlayFromHost(boolean readOnly) {
        Card card = this.getHostCard();
        if (card.isInPlay()) {
            return null;
        }

        // CR 118.6 cost is unpayable
        if (!isCastFromPlayEffect() && getPayCosts().hasManaCost() && getPayCosts().getCostMana().getMana().isNoCost()) {
            return null;
        }

        Player activator = this.getActivatingPlayer();
        if (activator == null) {
            activator = card.getController();
            if (activator == null) {
            	return null;
            }
        }

        final Game game = activator.getGame();
        if (game.getStack().isSplitSecondOnStack()) {
            return null;
        }

        // do performanceMode only for cases where the activator is different than controller
        if (!Spell.performanceMode && !card.getController().equals(activator)) {
            // always make a lki copy in this case?
            card = CardCopyService.getLKICopy(card);
            card.setController(activator, 0);
        }

        card = Objects.requireNonNullElse(readOnly ? getAlternateHostForEnumeration(card) : getAlternateHost(card), card);

        if (!this.getRestrictions().canPlay(card, this)) {
            return null;
        }

        if (!activator.getController().isFullControl(FullControlFlag.AllowPaymentStartWithMissingResources) &&
                !CostPayment.canPayAdditionalCosts(this.getPayCosts(), this, false)) {
            return null;
        }

        return card;
    }

    /** {@inheritDoc} */
    @Override
    public boolean checkRestrictions(Card host, Player activator) {
        return !StaticAbilityCantBeCast.cantBeCastAbility(this, host, activator);
    }

    /** {@inheritDoc} */
    @Override
    public final Object clone() {
        try {
            return super.clone();
        } catch (final Exception ex) {
            throw new RuntimeException("Spell : clone() error, " + ex);
        }
    }

    @Override
    public boolean isSpell() { return true; }
    @Override
    public boolean isAbility() { return false; }

    /**
     * @return the castFaceDown
     */
    @Override
    public boolean isCastFaceDown() {
        return castFaceDown;
    }

    /**
     * @param faceDown the castFaceDown to set
     */
    public void setCastFaceDown(boolean faceDown) {
        this.castFaceDown = faceDown;
    }

    @Override
    public Card getAlternateHost(Card source) {
        return getAlternateHost(source, false);
    }

    @Override
    public Card getAlternateHostForEnumeration(Card source) {
        return getAlternateHost(source, true);
    }

    private Card getAlternateHost(Card source, boolean readOnly) {
        boolean lkicheck = false;

        // need to be done before so it works with Vivien and Zoetic Cavern
        if (source.isFaceDown() && source.isInZone(ZoneType.Exile)) {
            if (readOnly && (source.hasMergedCard() || getHostCard().hasMergedCard()))
                throw new IllegalStateException("BENCH_INTEGRITY_UNSUPPORTED: face-up projection requires merged-card execution");
            if (readOnly && (source.hasPendingFaceupCommands() || getHostCard().hasPendingFaceupCommands()))
                throw new IllegalStateException("BENCH_INTEGRITY_UNSUPPORTED: face-up projection requires face-up command execution");
            if (!source.isLKI() || readOnly) {
                source = CardCopyService.getLKICopy(source);
            }

            if (readOnly) source.forceTurnFaceUpForEnumeration();
            else source.forceTurnFaceUp();
            source.setLKICMC(-1);
            source.setLKICMC(source.getCMC());
            lkicheck = true;
        }

        if (isBestow() && !source.isBestowed()) {
            if (!source.isLKI() || readOnly) {
                source = CardCopyService.getLKICopy(source);
            }

            source.animateBestow();
            lkicheck = true;
        } else if (isCastFaceDown()) {
            // need a copy of the card to turn facedown without trigger anything
            if (!source.isLKI() || readOnly) {
                source = CardCopyService.getLKICopy(source);
            }
            source.turnFaceDownNoUpdate();
            lkicheck = true;
        } else if (getCardState() != null && source.getCurrentStateName() != getCardStateName() && getHostCard().getState(getCardStateName()) != null) {
            if (!source.isLKI() || readOnly) {
                source = CardCopyService.getLKICopy(source);
            }
            CardStateName stateName = getCardStateName();
            if (!source.hasState(stateName)) {
                source.addAlternateState(stateName, false);
                source.getState(stateName).copyFrom(getHostCard().getState(stateName), true);
            }

            source.setState(stateName, false);
            if (getHostCard().isDoubleFaced()) {
                source.setBackSide(getHostCard().getRules().getSplitType().getChangedStateName().equals(stateName));
            }

            // need to reset CMC
            source.setLKICMC(-1);
            source.setLKICMC(source.getCMC());
            lkicheck = true;
        } else if (hasParam("Prototype") && source.getPrototypeTimestamp() == -1) {
            if (!source.isLKI() || readOnly) {
                source = CardCopyService.getLKICopy(source);
            }
            if (readOnly) {
                // Prototype's printed alternative changes only cost, color and P/T.
                // Applying those values to this LKI state gives legality checks a
                // prospective host without cloning abilities or taking a timestamp.
                if (!hasParam("SetManaCost") || !hasParam("SetColorByManaCost")
                        || !hasParam("SetPower") || !hasParam("SetToughness"))
                    throw new IllegalStateException("BENCH_INTEGRITY_UNSUPPORTED: incomplete Prototype characteristics");
                for (String param : new String[] {"AddAbilities", "AddColors", "AddKeywords", "AddSVars",
                        "AddStaticAbilities", "AddTriggers", "AddTypes", "GainTextAbilities", "GainTextOf",
                        "GainThisAbility", "KeepName", "NewName", "NonLegendary", "RemoveCardTypes",
                        "RemoveCost", "RemoveKeywords", "RemoveSubTypes", "SetColor", "SetCreatureTypes",
                        "SetLoyalty"})
                    if (hasParam(param)) throw new IllegalStateException("BENCH_INTEGRITY_UNSUPPORTED: Prototype clone parameter " + param);
                for (var staticAbility : source.getCurrentState().getStaticAbilities())
                    if (staticAbility.isCharacteristicDefining() && (staticAbility.hasParam("SetPower")
                            || staticAbility.hasParam("SetToughness") || staticAbility.hasParam("SetColor")))
                        throw new IllegalStateException("BENCH_INTEGRITY_UNSUPPORTED: Prototype characteristic-defining ability");
                final int power;
                final int toughness;
                try {
                    power = Integer.parseInt(getParam("SetPower"));
                    toughness = Integer.parseInt(getParam("SetToughness"));
                } catch (NumberFormatException invalid) {
                    throw new IllegalStateException("BENCH_INTEGRITY_UNSUPPORTED: dynamic Prototype P/T", invalid);
                }
                ManaCost cost = new ManaCost(getParam("SetManaCost"));
                source.getCurrentState().setManaCost(cost);
                source.getCurrentState().setColor(ColorSet.fromManaCost(cost));
                source.getCurrentState().setBasePower(power);
                source.getCurrentState().setBaseToughness(toughness);
                source.getCurrentState().removeIntrinsicKeyword(Keyword.DEVOID);
                source.setLKICMC(-1);
                source.setLKICMC(source.getCMC());
            } else {
                long next = source.getGame().getNextTimestamp();
                source.addCloneState(CardFactory.getCloneStates(source, source, this), next);
            }
            lkicheck = true;
        }

        return lkicheck ? source : null;
    }

    public boolean isCounterableBy(final SpellAbility sa) {
        final Map<AbilityKey, Object> repParams = AbilityKey.mapFromAffected(getHostCard());
        repParams.put(AbilityKey.SpellAbility, this);
        repParams.put(AbilityKey.Cause, sa);
        return !getHostCard().getGame().getReplacementHandler().cantHappenCheck(ReplacementType.Counter, repParams);
    }
}
