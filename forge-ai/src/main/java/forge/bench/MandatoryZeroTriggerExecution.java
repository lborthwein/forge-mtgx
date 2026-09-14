package forge.bench;

import forge.card.mana.ManaCost;
import forge.game.cost.*;
import forge.game.mana.ManaConversionMatrix;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;

/** Executes mandatory, literal-zero triggered abilities and separately scoped
 * replacement callbacks through Forge's
 * cost/stack/resolve lifecycle. No affordability heuristic or AI cost visitor.
 * This does not establish ownership of decisions made by the effect itself. */
final class MandatoryZeroTriggerExecution {
    private final Player payer;
    private final SpellAbility ability;
    private final Cost cost;
    private final boolean effect;
    private final RulesReplacementExecution replacement;
    private boolean paid;
    private int visits;

    MandatoryZeroTriggerExecution(Player payer, SpellAbility ability, boolean effect) {
        this(payer,ability,effect,null);
    }

    MandatoryZeroTriggerExecution(Player payer, SpellAbility ability, boolean effect, RulesReplacementExecution replacement) {
        this.payer = payer; this.ability = ability; this.effect = effect;
        this.replacement = replacement;
        validate(ability);
        this.cost = ability.getPayCosts();
    }

    private void validate(SpellAbility actual) {
        if (replacement==null) require(payer,actual);
        else {
            if (!effect) fail("replacement must execute as an effect");
            replacement.requirePayment(payer,actual);
            requireZero(actual.getPayCosts());
            requirePreparation(actual);
        }
    }

    static void require(Player payer, SpellAbility ability) {
        if (payer == null || ability == null || ability.getHostCard() == null
                || ability.getHostCard().getGame() != payer.getGame() || ability.getActivatingPlayer() != payer
                || !ability.isTrigger() || ability.getTrigger() == null || ability.isSpell() || ability.isCopied())
            fail("not an ordinary owned trigger");
        if (ability.isOptionalTrigger() || ability.getTrigger().hasParam("OptionalDecider")
                || ability instanceof WrappedAbility wrapper && wrapper.getDecider() != null)
            fail("optional trigger confirmation is not yet host controlled");
        requireZero(ability.getPayCosts());
        if (ability instanceof WrappedAbility wrapper) {
            if (wrapper.getWrappedAbility().getHostCard() != ability.getHostCard()
                    || wrapper.getWrappedAbility().getActivatingPlayer() != payer
                    || wrapper.getWrappedAbility().getTrigger() != ability.getTrigger())
                fail("wrapped trigger identity mismatch");
            requireZero(wrapper.getWrappedAbility().getPayCosts());
        }
        requirePreparation(ability);
    }

    private static void requirePreparation(SpellAbility ability) {
        for (SpellAbility current = ability; current != null; current = current.getSubAbility()) {
            if (current.getApi() == forge.game.ability.ApiType.Charm || current.hasParam("Announce")
                    || current.costHasX())
                fail("modal/announced preparation is not supported by zero execution");
            if (current.hasParam("TargetingPlayer")) TargetingPlayerRouting.chooser(current);
        }
    }

    private static void requireZero(Cost cost) {
        // No null-cost substitution: retain the exact original engine cost object.
        if (cost == null || !cost.getCostParts().stream().allMatch(
                part -> part instanceof CostPartMana mana && mana.isUnmodifiedZero()))
            fail("trigger cost is not literal zero");
    }

    boolean pay(ManaCost toPay, CostPartMana part, SpellAbility actual, ManaConversionMatrix matrix, boolean actualEffect) {
        validate(actual);
        if (actual != ability || actual.getPayCosts() != cost || actualEffect != effect
                || matrix != null || toPay == null || !toPay.isZero() || part == null || !part.isUnmodifiedZero()
                || visits != 1 || paid)
            fail("changed, unrelated or repeated zero payment callback");
        paid = true;
        return true;
    }

    void payCost() {
        validate(ability);
        if (ability.getPayCosts() != cost || paid || visits != 0) fail("changed or repeated trigger payment");
        if (!CostPayment.canPayAdditionalCosts(cost, ability, effect, payer))
            fail("mandatory zero trigger additional costs are not payable");
        var payment = new CostPayment(cost, ability);
        // Ordinary non-spell trigger cannot move its source to Stack; preserve
        // playStack's rules restriction check without its AI affordability gate.
        if (!effect && !ability.checkRestrictions(payer)) fail("trigger stack restrictions failed");
        if (!payment.payComputerCosts(new ZeroDecision()) || !paid || visits != 1)
            fail("literal-zero cost lifecycle did not complete exactly once");
    }

    private static void fail(String message) {
        throw new RulesCostFeasibility.Unsupported("mandatory zero trigger: " + message);
    }

    private final class ZeroDecision extends CostDecisionMakerBase {
        ZeroDecision() { super(payer, MandatoryZeroTriggerExecution.this.effect,
                MandatoryZeroTriggerExecution.this.ability, MandatoryZeroTriggerExecution.this.ability.getHostCard()); }
        @Override public boolean paysRightAfterDecision() { return false; }
        @Override public PaymentDecision visit(CostPartMana part) {
            validate(ability);
            if (!part.isUnmodifiedZero() || visits != 0) fail("nonzero or repeated cost visitor");
            visits++;
            return PaymentDecision.number(0);
        }
        private PaymentDecision unsupported(CostPart part) {
            fail("unexpected cost visitor " + part.getClass().getSimpleName()); return null;
        }
        @Override public PaymentDecision visit(CostBehold part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostBeholdExile part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostGainControl part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostChooseColor part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostChooseCreatureType part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostCollectEvidence part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostDiscard part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostDamage part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostDraw part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostExile part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostExileFromStack part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostExiledMoveToGrave part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostExert part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostEnlist part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostFlipCoin part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostForage part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostRollDice part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostMill part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostAddMana part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostPayLife part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostPayEnergy part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostGainLife part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostPromiseGift part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostPutCardToLib part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostTap part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostSacrifice part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostReturn part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostReveal part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostRevealChosen part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostRemoveAnyCounter part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostRemoveCounter part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostPutCounter part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostPutCounterYou part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostUntapType part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostUntap part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostUnattach part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostTapType part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostPayShards part) { return unsupported(part); }
        @Override public PaymentDecision visit(CostBlight part) { return unsupported(part); }
    }
}
