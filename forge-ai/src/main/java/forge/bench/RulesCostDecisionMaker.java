package forge.bench;

import forge.game.GameEntityCounterTable;
import forge.game.cost.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Rules-only decisions for the bounded cost subset. Never invokes an AI. */
final class RulesCostDecisionMaker extends CostDecisionMakerBase {
    private final Integer selectedLife;
    private final java.util.function.Function<CostDiscard,java.util.List<forge.game.card.Card>> discardSelector;
    private final java.util.function.Function<CostReturn,java.util.List<forge.game.card.Card>> returnSelector;
    RulesCostDecisionMaker(Player player, SpellAbility ability) {
        this(player, ability, null);
    }
    RulesCostDecisionMaker(Player player, SpellAbility ability, Integer selectedLife) {
        this(player, ability, selectedLife, false);
    }
    RulesCostDecisionMaker(Player player, SpellAbility ability, Integer selectedLife, boolean effect) {
        this(player,ability,selectedLife,effect,null);
    }
    RulesCostDecisionMaker(Player player, SpellAbility ability, Integer selectedLife, boolean effect,
            java.util.function.Function<CostDiscard,java.util.List<forge.game.card.Card>> discardSelector) {
        this(player,ability,selectedLife,effect,discardSelector,null);
    }
    RulesCostDecisionMaker(Player player, SpellAbility ability, Integer selectedLife, boolean effect,
            java.util.function.Function<CostDiscard,java.util.List<forge.game.card.Card>> discardSelector,
            java.util.function.Function<CostReturn,java.util.List<forge.game.card.Card>> returnSelector) {
        super(player, effect, ability, ability.getHostCard());
        this.selectedLife = selectedLife;
        this.discardSelector = discardSelector;
        this.returnSelector = returnSelector;
    }
    @Override public boolean decideAtPayment(CostPart part) { return RulesDiscardCostDomain.supports(part) || RulesReturnCostDomain.supports(part); }
    @Override public boolean paysRightAfterDecision() { return false; }
    private PaymentDecision unsupported(CostPart cost) {
        throw new RulesCostFeasibility.Unsupported("execution cost " + cost.getClass().getSimpleName());
    }
    /** A nonmana cost the feasibility layer admitted and the rules leave exactly
     * one way to pay: pay that. A part with a real choice is NOT decided here —
     * choosing which card to spend is play, and play belongs to the host. The
     * refusal names the ask the host would have to answer.
     */
    private PaymentDecision forced(CostPart cost, String ask) {
        if (isEffect()) return unsupported(cost);
        var selection = RulesCostFeasibility.forcedSelection(player, ability, cost);
        if (selection == null)
            throw new RulesCostFeasibility.Unsupported("execution cost " + cost.getClass().getSimpleName()
                    + ": requires explicit host card selection (" + ask + ")");
        require(cost);
        return PaymentDecision.card(selection);
    }
    private void require(CostPart cost) {
        if (!cost.canPay(ability, player, false))
            throw new RulesCostFeasibility.Unsupported("witness nonmana cost no longer payable");
    }
    @Override public PaymentDecision visit(CostPartMana cost) { return PaymentDecision.number(0); }
    @Override public PaymentDecision visit(CostTap cost) {
        require(cost);
        return PaymentDecision.number(0);
    }
    @Override public PaymentDecision visit(CostSacrifice cost) {
        if (cost.payCostFromSource() && Integer.valueOf(1).equals(cost.convertAmount())) {
            require(cost);
            return PaymentDecision.card(source);
        }
        return forced(cost, "sacrificeCost");
    }
    @Override public PaymentDecision visit(CostRemoveCounter cost) {
        if (!cost.payCostFromSource() || cost.counter == null) return unsupported(cost);
        Integer amount = cost.convertAmount() != null ? cost.convertAmount()
                : RulesCostFeasibility.literalAmount(ability, cost);
        if (amount == null) return unsupported(cost);
        require(cost);
        GameEntityCounterTable table = new GameEntityCounterTable();
        table.put(null, source, cost.counter, amount);
        return PaymentDecision.counters(table);
    }
    @Override public PaymentDecision visit(CostPutCounter cost) {
        if (!cost.payCostFromSource() || cost.convertAmount() == null || !ability.isActivatedAbility())
            return unsupported(cost);
        require(cost);
        return PaymentDecision.card(source);
    }
    @Override public PaymentDecision visit(CostBehold cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostBeholdExile cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostGainControl cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostChooseColor cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostChooseCreatureType cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostCollectEvidence cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostDiscard cost) {
        if (RulesDiscardCostDomain.supports(cost)) {
            if (discardSelector==null || isEffect()) return unsupported(cost);
            require(cost);
            return PaymentDecision.card(discardSelector.apply(cost));
        }
        if (RulesCostFeasibility.isSingleSelfDiscard(cost)) {
            if (!ability.isActivatedAbility() || source != ability.getHostCard() || source.getOwner() != player
                    || player.getCardsIn(forge.game.zone.ZoneType.Hand).stream().noneMatch(c -> c == source))
                return unsupported(cost);
            require(cost);
            return PaymentDecision.card(source);
        }
        return forced(cost, "discardCost");
    }
    @Override public PaymentDecision visit(CostDamage cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostDraw cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostExile cost) { return forced(cost, "exileCost"); }
    @Override public PaymentDecision visit(CostExileFromStack cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostExiledMoveToGrave cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostExert cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostEnlist cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostFlipCoin cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostForage cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostRollDice cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostMill cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostAddMana cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostPayLife cost) {
        Integer amount = cost.convertAmount() != null ? cost.convertAmount()
                : RulesCostFeasibility.literalAmount(ability, cost);
        if (selectedLife == null || amount == null || !selectedLife.equals(amount)) return unsupported(cost);
        require(cost);
        return PaymentDecision.number(selectedLife);
    }
    @Override public PaymentDecision visit(CostPayEnergy cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostGainLife cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostPromiseGift cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostPutCardToLib cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostReturn cost) {
        if (!RulesReturnCostDomain.supports(cost) || returnSelector==null || isEffect()) return unsupported(cost);
        require(cost);
        return PaymentDecision.card(returnSelector.apply(cost));
    }
    @Override public PaymentDecision visit(CostReveal cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostRevealChosen cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostRemoveAnyCounter cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostPutCounterYou cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostUntapType cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostUntap cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostUnattach cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostTapType cost) { return forced(cost, "tapTypeCost"); }
    @Override public PaymentDecision visit(CostPayShards cost) { return unsupported(cost); }
    @Override public PaymentDecision visit(CostBlight cost) { return unsupported(cost); }
}
