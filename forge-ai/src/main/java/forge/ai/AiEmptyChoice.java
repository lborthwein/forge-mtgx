package forge.ai;

import java.util.List;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;

/**
 * mtgx fork (lane jar-glow-valki-1027; owner game forge-mvr55yi8, 2026-10-27): an ability that can do nothing because
 * the card it is about to choose does not exist. Valki, God of Lies: "{X}: Choose a creature card exiled with Valki with
 * mana value X. Valki becomes a copy of that card." With no card exiled with Valki, every activation, at every X, chooses
 * nothing and copies nothing. The rules allow the activation (CR 602.2: nothing in its cost or announcement needs a card
 * to exist), so it stays legal for a human; an AI seat never chooses it (owner: "should not let it cast that ability when
 * there are no valid targets"). Volatile Chimera's "{1}{R}: Choose a card at random you exiled with cards named Volatile
 * Chimera ..." is the same shape.
 *
 * <p>The shape, read conservatively (anything else is NOT flagged, and then decides exactly as before):
 * <ul>
 *   <li>the ability is a spell or an activated ability whose own effect is {@code ChooseCard}, with no targets and none
 *       of the parameters that choose from somewhere else ({@code DefinedCards}, {@code AllCards},
 *       {@code IncludeSpellsOnStack}, {@code ChooseEach}, {@code EachBasicType}, {@code ControlledByPlayer},
 *       {@code TargetControls}) or let another player choose ({@code Defined} other than {@code You});</li>
 *   <li>its cost is mana alone (a tap, a sacrifice or a counter would do something by itself);</li>
 *   <li>every sub-ability acts on the chosen card only ({@code Defined$ ChosenCard}, no targets) or is a Cleanup;</li>
 *   <li>no trigger in play watches abilities being activated or resolving (then the activation itself does
 *       something);</li>
 *   <li>and the cards {@code ChooseCardEffect} would offer ({@code ChoiceZone}, default the battlefield, filtered by
 *       {@code Choices} for the activating player) are none, at the X asked about.</li>
 * </ul>
 *
 * <p>Reads only: the X asked about is set on the ability's root for the filter and restored after; no copy, no id, no
 * random number.
 */
public final class AiEmptyChoice {
    /** The largest X {@link #emptyForEveryX} tries when the choice filter reads X (the RL schema's X_MAX). */
    public static final int X_SCAN = 20;

    private static final String[] ELSEWHERE = {"DefinedCards", "AllCards", "IncludeSpellsOnStack", "ChooseEach",
            "EachBasicType", "ControlledByPlayer", "TargetControls"};

    private AiEmptyChoice() {
    }

    /** True if {@code sa} has the shape above and chooses nothing at EVERY X from 0 to {@code maxX} (at its own X when
     * the filter does not read X). */
    public static boolean emptyForEveryX(final SpellAbility sa, final Player ai, final int maxX) {
        if (!shape(sa, ai)) {
            return false;
        }
        if (!readsX(sa)) {
            return noChoices(sa, ai, null);
        }
        for (int x = 0; x <= Math.max(0, Math.min(maxX, X_SCAN)); x++) {
            if (!noChoices(sa, ai, x)) {
                return false;
            }
        }
        return true;
    }

    /** {@link #emptyForEveryX} up to {@link #X_SCAN}: no X the AI could announce gives it a card to choose. */
    public static boolean emptyForEveryX(final SpellAbility sa, final Player ai) {
        return emptyForEveryX(sa, ai, X_SCAN);
    }

    /** True if {@code sa} has the shape above and chooses nothing at this X. */
    public static boolean emptyAt(final SpellAbility sa, final Player ai, final int x) {
        return shape(sa, ai) && noChoices(sa, ai, readsX(sa) ? x : null);
    }

    /** The shape test; one that throws is not the shape (not flagged). */
    static boolean shape(final SpellAbility sa, final Player ai) {
        try {
            return shapeUnchecked(sa, ai);
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean shapeUnchecked(final SpellAbility sa, final Player ai) {
        if (sa == null || ai == null || sa.getApi() != ApiType.ChooseCard || sa.isTrigger() || sa.isReplacementAbility()
                || sa.isManaAbility() || !(sa.isSpell() || sa.isActivatedAbility()) || sa.usesTargeting()
                || sa.getHostCard() == null) {
            return false;
        }
        for (String p : ELSEWHERE) {
            if (sa.hasParam(p)) {
                return false;
            }
        }
        if (sa.hasParam("Defined") && !"You".equals(sa.getParam("Defined"))) {
            return false;
        }
        if (sa.isActivatedAbility() && sa.getPayCosts() != null) {
            for (CostPart part : sa.getPayCosts().getCostParts()) {
                if (!(part instanceof CostPartMana)) {
                    return false;
                }
            }
        }
        for (SpellAbility sub = sa.getSubAbility(); sub != null; sub = sub.getSubAbility()) {
            if (sub.usesTargeting()) {
                return false;
            }
            if (sub.getApi() == ApiType.Cleanup) {
                continue;
            }
            if (!"ChosenCard".equals(sub.getParam("Defined"))) {
                return false;
            }
        }
        final Game game = ai.getGame();
        for (Card c : game.getCardsIn(List.of(ZoneType.Battlefield, ZoneType.Command, ZoneType.Stack))) {
            for (Trigger t : c.getTriggers()) {
                final TriggerType mode = t.getMode();
                if (mode == TriggerType.AbilityCast || mode == TriggerType.SpellAbilityCast
                        || mode == TriggerType.AbilityResolves) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean readsX(final SpellAbility sa) {
        return sa.getParamOrDefault("Choices", "").contains("X");
    }

    /** The cards ChooseCardEffect would offer, at X (null: the ability's own X), are none. */
    private static boolean noChoices(final SpellAbility sa, final Player ai, final Integer x) {
        final SpellAbility root = sa.getRootAbility();
        final Integer was = root.getXManaCostPaid();
        try {
            if (x != null) {
                root.setXManaCostPaid(x);
            }
            final List<ZoneType> zones = sa.hasParam("ChoiceZone") ? ZoneType.listValueOf(sa.getParam("ChoiceZone"))
                    : List.of(ZoneType.Battlefield);
            CardCollectionView choices = ai.getGame().getCardsIn(zones);
            if (sa.hasParam("Choices")) {
                choices = CardLists.getValidCards(choices, sa.getParam("Choices"), ai, sa.getHostCard(), sa);
            }
            return choices.isEmpty();
        } catch (RuntimeException unreadable) {
            return false; // a filter this check cannot evaluate: not flagged
        } finally {
            if (x != null) {
                root.setXManaCostPaid(was);
            }
        }
    }
}
