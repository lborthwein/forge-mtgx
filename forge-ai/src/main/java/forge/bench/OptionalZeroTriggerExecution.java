package forge.bench;

import forge.card.mana.ManaCost;
import forge.game.cost.*;
import forge.game.mana.ManaConversionMatrix;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;

/** Separate optional literal-zero cost executor. Resolution confirmation is
 * authorized by a lexically scoped, single-use Resolution, never by this cost
 * visitor. MandatoryZeroTriggerExecution and Default remain unchanged. */
final class OptionalZeroTriggerExecution {
    static final class Resolution implements AutoCloseable {
        private final Player actor;
        private final WrappedAbility wrapper;
        private final SpellAbility underlying;
        private final forge.game.card.Card source;
        private final forge.game.trigger.Trigger trigger;
        private boolean answered, accepted, consumed, closed;
        Resolution(Player actor, WrappedAbility wrapper) {
            require(actor, wrapper);
            this.actor=actor; this.wrapper=wrapper; underlying=wrapper.getWrappedAbility();
            source=wrapper.getHostCard(); trigger=wrapper.getTrigger();
        }
        void requireConfirmation(Player actor, WrappedAbility actual) {
            requireCurrent();
            if (actor!=this.actor || actual!=wrapper || answered) fail("unrelated or repeated optional confirmation");
        }
        void answer(boolean accepted) { requireCurrent(); if(answered) fail("repeated confirmation"); answered=true; this.accepted=accepted; }
        void consume(Player actor, SpellAbility actual) {
            requireCurrent();
            if(actor!=this.actor || actual!=underlying || !answered || !accepted || consumed)
                fail("missing, unrelated, declined or consumed resolution authorization");
            consumed=true;
        }
        void finish() { requireCurrent(); if(accepted&&!consumed) fail("accepted trigger omitted native noStack callback"); }
        private void requireCurrent() {
            if(closed || wrapper.getWrappedAbility()!=underlying || wrapper.getHostCard()!=source
                    || underlying.getHostCard()!=source || wrapper.getTrigger()!=trigger || underlying.getTrigger()!=trigger
                    || wrapper.getDecider()!=actor) fail("stale optional resolution authorization");
            require(actor,wrapper); require(actor,underlying);
        }
        @Override public void close(){closed=true;}
    }
    private final Player payer;
    private final SpellAbility ability;
    private final Cost cost;
    private final boolean effect;
    private boolean paid;
    private int visits;

    OptionalZeroTriggerExecution(Player payer, SpellAbility ability, boolean effect) {
        require(payer, ability);
        this.payer = payer; this.ability = ability; this.cost = ability.getPayCosts(); this.effect = effect;
    }

    static void require(Player payer, SpellAbility ability) {
        requireIdentity(payer, ability);
        requireZero(ability.getPayCosts());
        if (ability instanceof WrappedAbility wrapper) requireZero(wrapper.getWrappedAbility().getPayCosts());
    }

    // Shared identity/choice checks, not payment authorization. The separate
    // paid-trigger executor must validate its own costs and resolution scope.
    static void requireIdentity(Player payer, SpellAbility ability) {
        requireIdentity(payer, ability, false, false);
    }

    static void requireIdentity(Player payer, SpellAbility ability, boolean paid, boolean repeated) {
        if (payer == null || ability == null || ability.getHostCard() == null
                || ability.getHostCard().getGame() != payer.getGame() || ability.getActivatingPlayer() != payer
                || !ability.isTrigger() || ability.getTrigger() == null || ability.isSpell() || ability.isCopied())
            fail("not an ordinary owned trigger");
        if (!ability.isOptionalTrigger() || (!paid && !ability.getTrigger().hasParam("OptionalDecider"))
                || ability.getTrigger().isStatic() || ability.getApi() == null)
            fail("not an ordinary optional nonstatic API trigger");
        if (ability instanceof WrappedAbility wrapper && wrapper.getDecider() != payer)
            fail("optional decider is not the controlled actor");
        if (ability instanceof WrappedAbility wrapper) {
            if (wrapper.getWrappedAbility().getHostCard() != ability.getHostCard()
                    || wrapper.getWrappedAbility().getActivatingPlayer() != payer
                    || wrapper.getWrappedAbility().getTrigger() != ability.getTrigger())
                fail("wrapped trigger identity mismatch");
        }
        for (SpellAbility current = ability; current != null; current = current.getSubAbility()) {
            if (current.getApi() == forge.game.ability.ApiType.Charm
                    || (current.hasParam("Announce") && !(repeated && "NumTimes".equals(current.getParam("Announce"))))
                    || current.costHasX() || current.hasParam("TargetingPlayer"))
                fail("modal/announced/other-player preparation is not supported by zero execution");
        }
    }

    private static void requireZero(Cost cost) {
        // No null-cost substitution: retain the exact original engine cost object.
        if (cost == null || !cost.getCostParts().stream().allMatch(
                part -> part instanceof CostPartMana mana && mana.isUnmodifiedZero()))
            fail("trigger cost is not literal zero");
    }

    boolean pay(ManaCost toPay, CostPartMana part, SpellAbility actual, ManaConversionMatrix matrix, boolean actualEffect) {
        require(payer, actual);
        if (actual != ability || actual.getPayCosts() != cost || actualEffect != effect
                || matrix != null || toPay == null || !toPay.isZero() || part == null || !part.isUnmodifiedZero()
                || visits != 1 || paid)
            fail("changed, unrelated or repeated zero payment callback");
        paid = true;
        return true;
    }

    void payCost() {
        require(payer, ability);
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
        throw new RulesCostFeasibility.Unsupported("optional zero trigger: " + message);
    }

    private final class ZeroDecision extends CostDecisionMakerBase {
        ZeroDecision() { super(payer, OptionalZeroTriggerExecution.this.effect,
                OptionalZeroTriggerExecution.this.ability, OptionalZeroTriggerExecution.this.ability.getHostCard()); }
        @Override public boolean paysRightAfterDecision() { return false; }
        @Override public PaymentDecision visit(CostPartMana part) {
            require(payer, ability);
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
