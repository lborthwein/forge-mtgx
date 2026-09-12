package forge.bench;

import forge.card.mana.ManaCost;
import forge.game.cost.*;
import forge.game.mana.ManaConversionMatrix;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;

/** Nonzero optional-trigger costs are paid only during their exact accepted
 * native resolution. Wrapper insertion is still literal-zero. Never changes
 * trigger costs, turns a trigger into an activation, or invokes AI payment. */
final class OptionalManaTriggerExecution {
    static boolean hasPaidUnderlying(SpellAbility ability) {
        SpellAbility underlying = ability instanceof WrappedAbility w ? w.getWrappedAbility() : ability;
        Cost cost = underlying.getPayCosts();
        return cost != null && cost.getCostParts().stream().anyMatch(p -> !(p instanceof CostPartMana m) || !m.isUnmodifiedZero());
    }
    static ManaCost require(Player actor, SpellAbility ability) {
        SpellAbility underlying = ability instanceof WrappedAbility w ? w.getWrappedAbility() : ability;
        boolean repeated = repeated(underlying);
        OptionalZeroTriggerExecution.requireIdentity(actor, ability, true, repeated);
        if (ability instanceof WrappedAbility && !zero(ability.getPayCosts())) fail("wrapper insertion cost is not zero");
        Cost cost = underlying.getPayCosts();
        if (cost == null || cost.getCostParts().size() != 1 || !(cost.getCostParts().get(0) instanceof CostPartMana part)
                || part.isExiledCreatureCost() || part.isEnchantedCreatureCost() || part.getMaxWaterbend() != null
                || part.getXMin() != 0 || part.getMana().isNoCost() || part.getMana().isZero()
                || part.getMana().countX() != 0 || part.isPayAnyNumberOfTimes() != repeated)
            fail("requires an ordinary nonzero mana-only trigger cost");
        ManaCost mana = cost.getCostMana().getMana();
        for (var shard : mana) if (shard.isPhyrexian() || shard.isSnow() || shard.isOr2Generic()) fail("complex trigger mana shard");
        return mana;
    }

    /** Repeat only a cost whose consequence consumes the count, not the last
     * payment's receipt. No modal/target/nonmana/extra effect preparation here. */
    static boolean repeated(SpellAbility ability) {
        if (ability instanceof WrappedAbility w) ability = w.getWrappedAbility();
        if (!ability.hasParam("Announce")) return false;
        if (!"NumTimes".equals(ability.getParam("Announce"))
                || ability.getApi() != forge.game.ability.ApiType.ImmediateTrigger
                || !"NumTimes".equals(ability.getParam("ConditionCheckSVar"))
                || !"NumTimes".equals(ability.getParam("RememberSVarAmount"))
                || !ability.hasParam("Execute")
                || ability.usesTargeting() || ability.getSubAbility() != null
                || !ability.getMapParams().keySet().stream().allMatch(java.util.Set.of(
                    "AB", "Cost", "Announce", "ConditionCheckSVar", "RememberSVarAmount", "Execute", "TriggerDescription")::contains))
            fail("unscoped repeated-payment effect");
        var execute = ability.getAdditionalAbility("Execute");
        var params = execute != null ? execute.getMapParams()
                : forge.game.ability.AbilityFactory.getMapParams(ability.getHostCard().getSVar(ability.getParam("Execute")));
        if (!"PutCounter".equals(params.get("DB")) || !"X".equals(params.get("CounterNum"))
                || params.get("CounterType") == null || params.size() != 3
                || !"Count$TriggerRememberAmount".equals(execute == null ? ability.getHostCard().getSVar("X") : execute.getSVar("X"))
                || (execute != null && (execute.getHostCard() != ability.getHostCard()
                    || execute.getApi() != forge.game.ability.ApiType.PutCounter
                    || execute.usesTargeting() || execute.getSubAbility() != null)))
            fail("repeated-payment consequence may depend on individual receipts");
        return true;
    }
    private static java.util.Map<String, String> repeatedConsequence(SpellAbility ability) {
        var execute = ability.getAdditionalAbility("Execute");
        return java.util.Map.copyOf(execute != null ? execute.getMapParams()
            : forge.game.ability.AbilityFactory.getMapParams(ability.getHostCard().getSVar(ability.getParam("Execute"))));
    }
    private static boolean zero(Cost cost) {
        return cost != null && cost.getCostParts().stream().allMatch(p -> p instanceof CostPartMana m && m.isUnmodifiedZero());
    }
    static final class Resolution implements AutoCloseable, RulesResolutionPayment {
        private final Player actor;
        private final WrappedAbility wrapper;
        private final SpellAbility underlying;
        private final forge.game.card.Card source;
        private final forge.game.trigger.Trigger trigger;
        private final Cost cost;
        private final CostPartMana manaPart;
        private final ManaCost mana;
        private final boolean repeated;
        private final java.util.Map<String, String> params;
        private final String initialAbilityCount, initialHostCount;
        private final SpellAbility execute;
        private final java.util.Map<String, String> consequence;
        private int completed;
        private boolean finalCount;
        private boolean answered, accepted, consumed, closed;
        Resolution(Player actor, WrappedAbility wrapper) {
            this.mana = require(actor, wrapper); this.actor = actor; this.wrapper = wrapper;
            underlying = wrapper.getWrappedAbility(); source = wrapper.getHostCard(); trigger = wrapper.getTrigger();
            cost = underlying.getPayCosts();
            manaPart = cost.getCostMana();
            repeated = OptionalManaTriggerExecution.repeated(underlying); params = java.util.Map.copyOf(underlying.getMapParams());
            initialAbilityCount = underlying.getSVar("NumTimes"); initialHostCount = source.getSVar("NumTimes");
            execute = repeated ? underlying.getAdditionalAbility("Execute") : null;
            consequence = repeated ? repeatedConsequence(underlying) : java.util.Map.of();
        }
        void requireConfirmation(Player actor, WrappedAbility actual) {
            requireCurrent();
            if (this.actor != actor || actual != wrapper || answered) fail("unrelated/repeated confirmation");
        }
        void answer(boolean yes) { requireCurrent(); if (answered) fail("repeated answer"); answered = true; accepted = yes; }
        void consume(Player actor, SpellAbility actual) {
            requireQuote(actor, actual);
            if (!answered || !accepted || consumed) fail("unaccepted/repeated effect execution");
            consumed = true;
            if (repeated) setCount(1);
        }
        public void requireQuote(Player payer, SpellAbility actual) {
            requireCurrent(); if (payer != actor || actual != underlying || finalCount) fail("unrelated/finalized trigger quote");
        }
        public void requirePayment(Player payer, SpellAbility actual) {
            requireQuote(payer, actual);
            if (!answered || !accepted || !consumed || (repeated && completed >= 128))
                fail("payment without accepted native effect callback or beyond execution bound");
        }
        public ManaCost manaCost(Player payer, SpellAbility actual) { requireQuote(payer, actual); return mana; }
        public Cost cost(Player payer, SpellAbility actual) { requireQuote(payer, actual); return cost; }
        SpellAbility ability() { requireCurrent(); return underlying; }
        public boolean repeated() { requireCurrent(); return repeated; }
        int completed() { requireCurrent(); return completed; }
        WrappedAbility wrapper() { requireCurrent(); return wrapper; }
        void paymentCompleted() {
            requirePayment(actor, underlying);
            if (!repeated || finalCount || completed >= 128) fail("repeated-payment execution bound or phase");
            completed++; answered = false; accepted = false;
        }
        void finishRepeatedPayments() {
            requireCurrent();
            if (!repeated || !consumed || accepted || finalCount) fail("unfinished repeated payment");
            finalCount = true; setCount(completed);
        }
        private void setCount(int count) {
            String value = "Number$" + count;
            underlying.setSVar("NumTimes", value); source.setSVar("NumTimes", value);
        }
        void finish() {
            requireCurrent();
            if (accepted && !consumed) fail("accepted trigger omitted native callback");
            if (repeated && consumed && !finalCount) fail("repeated payment omitted final count");
        }
        private void requireCurrent() {
            if (closed || wrapper.getWrappedAbility() != underlying || wrapper.getHostCard() != source
                    || underlying.getHostCard() != source || wrapper.getTrigger() != trigger || underlying.getTrigger() != trigger
                    || wrapper.getDecider() != actor || underlying.getPayCosts() != cost || cost.getCostMana() != manaPart
                    || !underlying.getMapParams().equals(params)
                    || (repeated && (underlying.getAdditionalAbility("Execute") != execute || !repeatedConsequence(underlying).equals(consequence)))
                    || (repeated && (!java.util.Objects.equals(underlying.getSVar("NumTimes"), consumed ? "Number$" + (finalCount ? completed : 1) : initialAbilityCount)
                        || !java.util.Objects.equals(source.getSVar("NumTimes"), consumed ? "Number$" + (finalCount ? completed : 1) : initialHostCount)))
                    || !RulesPaymentExecutor.sameManaCost(mana, require(actor, wrapper))) fail("stale/changed paid-trigger scope");
        }
        @Override public void close() { closed = true; }
    }

    /** Native stack insertion must not pay the wrapped ability's eventual cost. */
    static final class WrapperPayment {
        private final Player actor;
        private final WrappedAbility wrapper;
        private final Cost cost;
        private boolean started, paid;
        WrapperPayment(Player actor, WrappedAbility wrapper) {
            require(actor, wrapper); this.actor = actor; this.wrapper = wrapper; cost = wrapper.getPayCosts();
        }
        void payCost() {
            require(actor, wrapper);
            if (started || paid || wrapper.getPayCosts() != cost) fail("repeated/changed wrapper cost");
            started = true;
            if (!wrapper.checkRestrictions(actor) || !new CostPayment(cost, wrapper).payComputerCosts(new RulesCostDecisionMaker(actor, wrapper)) || !paid)
                fail("native wrapper cost lifecycle failed");
        }
        boolean pay(ManaCost toPay, CostPartMana part, SpellAbility actual, ManaConversionMatrix matrix, boolean effect) {
            require(actor, wrapper);
            if (!started || paid || actual != wrapper || actual.getPayCosts() != cost || effect || matrix != null
                    || toPay == null || !toPay.isZero() || part == null || !part.isUnmodifiedZero()) fail("wrong wrapper payment callback");
            paid = true; return true;
        }
    }
    private static void fail(String why) { throw new RulesCostFeasibility.Unsupported("optional mana trigger: " + why); }
}
