package forge.bench;

import forge.card.mana.ManaCost;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes the checker's ordinary-mana witness without any AI payment method.
 * Failure invalidates the run; no alternative payment is guessed or delegated.
 */
public final class RulesPaymentExecutor {
    private final Player payer;
    private final String selectedAction;
    private final RulesCostFeasibility.PaymentWitness witness;
    private boolean paid;
    private SpellAbility activeSource;

    public RulesPaymentExecutor(Player payer, SpellAbility selected) {
        this(payer, selected, null);
    }

    /** Explicit witness entry point. A protocol decoder must resolve host-selected
     * source/token identities against this decision's offered witnesses first.
     */
    public RulesPaymentExecutor(Player payer, SpellAbility selected, RulesCostFeasibility.PaymentWitness requested) {
        this.payer = payer;
        selectedAction = actionKey(selected);
        var result = RulesCostFeasibility.assess(payer, selected);
        if (result.status() != RulesCostFeasibility.Status.PAYABLE || result.witness() == null)
            throw new RulesCostFeasibility.Unsupported("selected action has no executable payment witness: " + result.reason());
        witness = requested == null ? result.witness() : requested;
        if (!witness.cost().equals(result.witness().cost())) fail("requested witness prices a different cost");
    }

    static String actionKey(SpellAbility sa) {
        var key = new StringBuilder().append(sa.getHostCard().getId()).append('|').append(sa.getApi())
                .append('|').append(sa.getXManaCostPaid()).append('|').append(sa.getPayCosts().getTotalMana())
                .append('|').append(sa.getPayCosts().toSimpleString());
        for (SpellAbility current = sa; current != null; current = current.getSubAbility())
            key.append('|').append(current.getApi()).append(':').append(current.getTargets());
        return key.toString();
    }

    public CostDecisionMakerBase decisions(SpellAbility actual) {
        if (!selectedAction.equals(actionKey(actual))) fail("selected action/cost/targets changed before payment");
        return new RulesCostDecisionMaker(payer, actual);
    }

    public boolean pay(ManaCost toPay, CostPartMana costPart, SpellAbility actual, boolean effect) {
        if (actual == activeSource) {
            if (!toPay.isZero() || effect) fail("unexpected nested mana source cost");
            return true;
        }
        if (paid || effect || !selectedAction.equals(actionKey(actual))) fail("unexpected payment action or repeat payment");
        // CostPartMana arrives after Forge's real additional-cost adjustment. Exact
        // equality catches repricing rather than charging a stale forecast.
        if (!witness.cost().equals(toPay)) fail("actual adjusted mana cost differs from witness: " + toPay + " vs " + witness.cost());
        Map<RulesCostFeasibility.SourceChoice, List<Mana>> produced = new LinkedHashMap<>();
        for (var choice : witness.sources()) {
            SpellAbility source = choice.ability();
            if (!source.isManaAbility() || source.getHostCard().getController() != payer)
                fail("witness source is not the payer's mana ability");
            source.setActivatingPlayer(payer);
            if (!source.canPlay() || !source.metConditions()) fail("witness source no longer playable");
            if (!choice.choice().isEmpty()) source.getManaPart().setExpressChoice(choice.choice());
            activeSource = source;
            try {
                if (!new CostPayment(source.getPayCosts(), source).payComputerCosts(new RulesCostDecisionMaker(payer, source)))
                    fail("source payment failed");
                // The normal engine mana-ability path records activation and resolves
                // immediately; it is not a call to ComputerUtil.playNoStack/AI.
                payer.getGame().getStack().addAndUnfreeze(source);
            } finally { activeSource = null; }
            List<Mana> emitted = List.copyOf(source.getManaPart().getLastManaProduced());
            if (emitted.size() != choice.output().size()) fail("source output amount changed");
            for (int i = 0; i < emitted.size(); i++) {
                if (emitted.get(i).getColor() != choice.output().get(i)) fail("source output color changed");
                Mana mana = emitted.get(i);
                if (mana.isRestricted() || mana.triggersWhenSpent() || mana.addsCounters(actual)
                        || mana.addsKeywords(actual) || mana.addsNoCounterMagic(actual)) fail("source emitted unsupported effectful mana");
            }
            produced.put(choice, emitted);
            actual.getPayingManaAbilities().add(source);
        }
        ManaCostBeingPaid remaining = new ManaCostBeingPaid(toPay);
        for (var allocation : witness.allocations()) {
            var token = allocation.token();
            Mana mana = token.floating() != null ? token.floating() : produced.get(token.source()).get(token.outputIndex());
            if (!payer.getManaPool().payExactShard(actual, remaining, mana, allocation.shard()))
                fail("witness token/shard could not be consumed exactly");
            actual.getPayingMana().add(mana);
        }
        if (!remaining.isPaid()) fail("witness left an unpaid mana cost");
        paid = true;
        return true;
    }

    public void assertPaid() { if (!paid) fail("selected action did not execute its mana payment"); }
    private static void fail(String reason) { throw new RulesCostFeasibility.Unsupported("payment witness: " + reason); }
}
