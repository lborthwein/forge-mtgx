package forge.bench;

import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Executes the checker's ordinary-mana witness without any AI payment method.
 * Failure invalidates the run; no alternative payment is guessed or delegated.
 */
public final class RulesPaymentExecutor {
    private final Player payer;
    private final String selectedAction;
    private final RulesCostFeasibility.PaymentWitness witness;
    private boolean paid;
    private final int lifeBefore;
    private SpellAbility paidAction;
    private SpellAbility activeSource;
    private RulesCostFeasibility.SourceChoice activeSourceChoice;
    private int sourceColorVisits;
    private final java.util.ArrayList<Mana> reflectedOutput = new java.util.ArrayList<>();
    private ManaCostBeingPaid paidManaCost;
    private NestedTrigger nestedTrigger;
    private final RulesResolutionPayment triggerAuthority;
    private final Card sacrificedSource;
    private final boolean sacrificeRequiresTap;
    private final Card discardedSource;
    private final long discardedSourceTimestamp;
    private final java.util.function.Function<RulesDiscardCostDomain,List<Card>> discardChoice;
    private final java.util.function.Function<RulesReturnCostDomain,List<Card>> returnChoice;
    private final boolean returnRequired;
    private List<Card> chosenReturn;
    private final Map<Integer,Long> returnVisits = new LinkedHashMap<>();
    private final Map<Integer,Player> returnOwners = new LinkedHashMap<>();
    private List<Card> chosenDiscard;
    private final Map<Integer,Long> discardVisits = new LinkedHashMap<>();
    /** Receipts for nonmana costs whose payment the rules left forced, keyed by
     * the part's own paid-list hash. Recorded before anything is paid. */
    private final Map<String,List<Card>> forcedSpend = new LinkedHashMap<>();
    private final Map<Integer,Long> forcedVisits = new LinkedHashMap<>();
    private boolean forcedRecorded;

    private static final class NestedTrigger {
        final Card host;
        final WrappedAbility wrapper;
        final SpellAbility ability;
        final forge.game.trigger.Trigger trigger;
        boolean wrapperPaid, abilityPaid;
        NestedTrigger(Card host, WrappedAbility wrapper) {
            this.host = host; this.wrapper = wrapper; this.ability = wrapper.getWrappedAbility();
            this.trigger = wrapper.getTrigger();
        }
    }

    /** ManaEffect still calls chooseColor for an express singleton. That is a
     * receipt of the host's complete payment, not a fresh Default AI decision.
     */
    public byte chooseSourceColor(SpellAbility actual, forge.card.ColorSet colors) {
        if (activeSource == null || activeSourceChoice == null || actual != activeSource || nestedTrigger != null
                || !(actual.getManaPart().isComboMana() || "Any".equals(actual.getManaPart().getOrigProduced()))
                || actual.getActivatingPlayer() != payer)
            fail("color callback outside selected mana source");
        var choices = activeSourceChoice.choice().split(" ");
        int count = actual.getManaPart().isComboMana() ? activeSourceChoice.primaryCount() : 1;
        if (sourceColorVisits >= choices.length || choices.length != count)
            fail("repeated or incomplete combo source color callback");
        byte chosen = forge.card.MagicColor.fromName(choices[sourceColorVisits]);
        if (chosen == 0 || colors == null || colors.getColor() != chosen)
            fail("combo source callback is not the exact selected singleton");
        sourceColorVisits++;
        return chosen;
    }

    private static boolean literalZeroCost(Cost cost) {
        return cost == null || cost.getCostParts().stream().allMatch(
                part -> part instanceof CostPartMana mana && mana.isUnmodifiedZero());
    }

    /** Scope only the engine-delivered mandatory static trigger and its exact
     * wrapped ability. This does not choose targets, effects or optional costs.
     * Unrelated callbacks never inherit permission to pay zero for free.
     */
    public boolean duringMandatoryTrigger(Card host, WrappedAbility wrapper, boolean mandatory, BooleanSupplier run) {
        if (!mandatory || host == null || wrapper == null || wrapper.getTrigger() == null || !wrapper.getTrigger().isStatic()
                || wrapper.getDecider() != null || wrapper.isOptionalTrigger()
                || wrapper.getTrigger().hasParam("OptionalDecider")
                || wrapper.getHostCard() != host || wrapper.getWrappedAbility().getHostCard() != host
                || wrapper.getTrigger().getHostCard() != host || host.getGame() != payer.getGame()
                || wrapper.getActivatingPlayer() != payer || wrapper.getWrappedAbility().getActivatingPlayer() != payer
                || !literalZeroCost(wrapper.getPayCosts()) || !literalZeroCost(wrapper.getWrappedAbility().getPayCosts()))
            fail("nested trigger is not an exact mandatory zero-cost engine callback");
        NestedTrigger previous = nestedTrigger;
        nestedTrigger = new NestedTrigger(host, wrapper);
        try {
            ReflectedManaProduction.Bonus bonus = null;
            if (wrapper.getTrigger().getMode() == forge.game.trigger.TriggerType.TapsForMana) {
                if (activeSourceChoice == null || activeSource == null || previous != null
                        || reflectedOutput.size() >= activeSourceChoice.bonuses().size())
                    fail("unforecast reflected mana callback");
                bonus = activeSourceChoice.bonuses().get(reflectedOutput.size());
                var effect = wrapper.getWrappedAbility();
                Object tapped = effect.getTriggeringObject(forge.game.ability.AbilityKey.Card);
                if (bonus.trigger() != wrapper.getTrigger() || bonus.producerId() != host.getId()
                        || bonus.recipient() != payer || !(tapped instanceof Card tappedCard)
                        || tappedCard.getId() != activeSource.getHostCard().getId()
                        || effect.getTriggeringObject(forge.game.ability.AbilityKey.Activator) != payer
                        || !ReflectedManaProduction.matchesEffect(wrapper.getTrigger(), effect, bonus.color()))
                    fail("reflected mana callback identity/definition changed");
            }
            boolean result = run.getAsBoolean();
            if (bonus != null) {
                var effect = wrapper.getWrappedAbility();
                if (!result || effect.getManaPart() == null) fail("reflected mana callback did not resolve");
                var emitted = List.copyOf(effect.getManaPart().getLastManaProduced());
                if (emitted.size()!=1) fail("reflected mana amount changed");
                requireOutput(emitted.get(0), bonus.color(), bonus.producerId(), effect.getManaPart(), bonus.traits(), paidAction);
                reflectedOutput.add(emitted.get(0));
            }
            return result;
        }
        finally { nestedTrigger = previous; }
    }

    /** Explicit witness entry point. A protocol decoder must resolve host-selected
     * source/token identities against this decision's offered witnesses first.
     */
    public RulesPaymentExecutor(Player payer, SpellAbility selected, RulesCostFeasibility.PaymentWitness requested) {
        this(payer, selected, requested, null);
    }

    RulesPaymentExecutor(Player payer, SpellAbility selected, RulesCostFeasibility.PaymentWitness requested, RulesCastingAuthorization announcement) {
        this(payer, selected, requested, announcement, null);
    }

    RulesPaymentExecutor(Player payer, SpellAbility selected, RulesCostFeasibility.PaymentWitness requested,
            RulesCastingAuthorization announcement, RulesResolutionPayment trigger) {
        this(payer,selected,requested,announcement,trigger,null);
    }
    RulesPaymentExecutor(Player payer, SpellAbility selected, RulesCostFeasibility.PaymentWitness requested,
            RulesCastingAuthorization announcement, RulesResolutionPayment trigger,
            java.util.function.Function<RulesDiscardCostDomain,List<Card>> discardChoice) {
        this(payer,selected,requested,announcement,trigger,discardChoice,null);
    }
    RulesPaymentExecutor(Player payer, SpellAbility selected, RulesCostFeasibility.PaymentWitness requested,
            RulesCastingAuthorization announcement, RulesResolutionPayment trigger,
            java.util.function.Function<RulesDiscardCostDomain,List<Card>> discardChoice,
            java.util.function.Function<RulesReturnCostDomain,List<Card>> returnChoice) {
        if (requested == null) fail("explicit host-selected witness required");
        this.discardChoice=discardChoice;
        this.returnChoice=returnChoice;
        var paymentCost = trigger == null ? selected.getPayCosts() : trigger.cost(payer, selected);
        returnRequired=paymentCost.getCostParts().stream().anyMatch(RulesReturnCostDomain::supports);
        if(returnRequired && (returnChoice==null || trigger!=null)) fail("explicit return-cost controller required");
        if(paymentCost.getCostParts().stream().anyMatch(RulesDiscardCostDomain::supports)
                && (discardChoice==null || trigger!=null)) fail("explicit discard-cost controller required");
        triggerAuthority = trigger;
        if (trigger != null) trigger.requirePayment(payer, selected);
        this.payer = payer;
        sacrificedSource = paymentCost.getCostParts().stream().anyMatch(RulesCostFeasibility::isSingleSelfSacrifice)
                ? selected.getHostCard() : null;
        sacrificeRequiresTap = sacrificedSource != null && paymentCost.hasTapCost();
        discardedSource = paymentCost.getCostParts().stream().anyMatch(RulesCostFeasibility::isSingleSelfDiscard)
                ? selected.getHostCard() : null;
        discardedSourceTimestamp = discardedSource == null ? -1 : discardedSource.getGameTimestamp();
        lifeBefore = payer.getLife();
        selectedAction = actionKey(selected);
        var result = RulesCostFeasibility.assess(payer, selected, announcement, trigger);
        if (result.status() != RulesCostFeasibility.Status.PAYABLE || result.witness() == null)
            throw new RulesCostFeasibility.Unsupported("selected action has no executable payment witness: " + result.reason());
        witness = requested;
        if (!sameManaCost(witness.cost(), result.witness().cost()) || witness.life() != result.witness().life())
            fail("requested witness prices a different mana/life cost");
        if (witness.totalLife() > 0 && witness.totalLife() > payer.getLife()) fail("aggregate source/action life is unaffordable");
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
        if (discardedSource != null && (actual.getHostCard() != discardedSource
                || discardedSource.getGameTimestamp() != discardedSourceTimestamp
                || payer.getCardsIn(forge.game.zone.ZoneType.Hand).stream().noneMatch(c -> c == discardedSource)))
            fail("source-discard hand visit changed before payment");
        if (triggerAuthority != null) triggerAuthority.requirePayment(payer, actual);
        recordForcedSpend(actual);
        return new RulesCostDecisionMaker(payer, actual, witness.life(), triggerAuthority != null,
                cost -> selectDiscard(actual,cost), cost -> selectReturn(actual,cost));
    }

    /** The exact cards a forced nonmana cost must consume, captured before any
     * part of the cost is paid. A part with a genuine choice is not recorded:
     * the decision maker refuses it and names the host ask instead.
     */
    private void recordForcedSpend(SpellAbility actual) {
        if (forcedRecorded) return;
        forcedRecorded = true;
        var paymentCost = triggerAuthority == null ? actual.getPayCosts() : triggerAuthority.cost(payer, actual);
        for (forge.game.cost.CostPart part : paymentCost.getCostParts()) {
            if (!(part instanceof forge.game.cost.CostPartWithList listed)) continue;
            if (RulesCostFeasibility.isSingleSelfDiscard(part) || RulesCostFeasibility.isSingleSelfSacrifice(part)
                    || RulesDiscardCostDomain.supports(part) || RulesReturnCostDomain.supports(part)) continue;
            var selection = RulesCostFeasibility.forcedSelection(payer, actual, part);
            if (selection == null) continue;
            String key = listed.getHashForLKIList();
            if (("Sacrificed".equals(key) && sacrificedSource != null)
                    || ("Discarded".equals(key) && discardedSource != null)
                    || forcedSpend.put(key, List.copyOf(selection)) != null)
                fail("two nonmana cost parts share one paid-list receipt");
            for (Card card : selection) forcedVisits.put(card.getId(), card.getGameTimestamp());
        }
    }

    private List<Card> selectReturn(SpellAbility actual, forge.game.cost.CostReturn cost) {
        if(returnChoice==null || chosenReturn!=null || !selectedAction.equals(actionKey(actual))) fail("invalid return callback");
        if(!paid && !witness.cost().isZero() && !witness.cost().isNoCost()) fail("return decision preceded mana payment");
        var domain=new RulesReturnCostDomain(payer,actual,cost);
        var choice=returnChoice.apply(domain);
        if(!domain.selection().equals(choice)) fail("return controller bypassed domain validation");
        chosenReturn=List.copyOf(choice);
        for(Card card:chosenReturn) {
            returnVisits.put(card.getId(),card.getGameTimestamp());returnOwners.put(card.getId(),card.getOwner());
        }
        return chosenReturn;
    }

    private List<Card> selectDiscard(SpellAbility actual, forge.game.cost.CostDiscard cost) {
        if(discardChoice==null || chosenDiscard!=null || !selectedAction.equals(actionKey(actual))) fail("invalid discard callback");
        if(!paid && !witness.cost().isZero() && !witness.cost().isNoCost()) fail("discard decision preceded mana payment");
        var domain=new RulesDiscardCostDomain(payer,actual,cost);
        var choice=discardChoice.apply(domain);
        if(!domain.selection().equals(choice)) fail("discard controller bypassed domain validation");
        chosenDiscard=List.copyOf(choice);
        for(Card card:chosenDiscard)discardVisits.put(card.getId(),card.getGameTimestamp());
        return chosenDiscard;
    }

    public boolean pay(ManaCost toPay, CostPartMana costPart, SpellAbility actual, boolean effect) {
        if (nestedTrigger != null && (actual == nestedTrigger.wrapper || actual == nestedTrigger.ability)) {
            boolean wrapper = actual == nestedTrigger.wrapper;
            if (!effect || !toPay.isZero() || costPart == null || !costPart.isUnmodifiedZero()
                    || actual.getActivatingPlayer() != payer || actual.getHostCard() != nestedTrigger.host
                    || nestedTrigger.wrapper.getHostCard() != nestedTrigger.host || nestedTrigger.ability.getHostCard() != nestedTrigger.host
                    || nestedTrigger.wrapper.getTrigger() != nestedTrigger.trigger || nestedTrigger.ability.getTrigger() != nestedTrigger.trigger
                    || nestedTrigger.wrapper.getDecider() != null || nestedTrigger.wrapper.isOptionalTrigger()
                    || nestedTrigger.trigger.hasParam("OptionalDecider")
                    || !literalZeroCost(actual.getPayCosts())
                    || (wrapper ? nestedTrigger.wrapperPaid : nestedTrigger.abilityPaid))
                fail("nested trigger payment changed, repeated or is not literal zero");
            if (wrapper) nestedTrigger.wrapperPaid = true; else nestedTrigger.abilityPaid = true;
            return true; // No mana, life, source, RNG, or original-action receipt is changed.
        }
        if (nestedTrigger != null) fail("unrelated payment inside nested trigger scope");
        if (actual == activeSource) {
            if (!toPay.isZero() || effect) fail("unexpected nested mana source cost");
            return true;
        }
        if (paid || !selectedAction.equals(actionKey(actual))) fail("unexpected payment action or repeat payment");
        // CostPartMana arrives after Forge's real additional-cost adjustment. Exact
        // equality catches repricing rather than charging a stale forecast.
        if (effect != (triggerAuthority != null)) fail("payment effect context differs from authorization");
        if (triggerAuthority != null) triggerAuthority.requirePayment(payer, actual);
        var triggerCost = triggerAuthority == null ? null : triggerAuthority.manaCost(payer, actual);
        var actualPrice = triggerCost == null ? forge.game.cost.CostAdjustment.benchmarkManaPrice(actual)
                : new forge.game.cost.CostAdjustment.BenchmarkManaPrice(triggerCost, triggerCost);
        if (actualPrice == null || !sameManaCost(actualPrice.beforeReduction(), toPay)
                || !sameManaCost(witness.cost(), actualPrice.payable()))
            fail("actual pre/post-reduction mana cost differs from witness: " + toPay + " vs " + witness.cost());
        Map<RulesCostFeasibility.SourceChoice, List<Mana>> produced = new LinkedHashMap<>();
        paidAction = actual; // Used only to validate emitted spending properties until paid=true.
        for (var choice : witness.sources()) {
            SpellAbility source = choice.ability();
            if (!source.isManaAbility() || source.getHostCard().getController() != payer)
                fail("witness source is not the payer's mana ability");
            source.setActivatingPlayer(payer);
            if (!source.canPlay() || !source.metConditions()) fail("witness source no longer playable");
            if (!choice.choice().isEmpty()) source.getManaPart().setExpressChoice(choice.choice());
            var poolsBefore = poolSnapshot();
            var producer = source.getManaPart();
            int producerId = source.getHostCard().getId();
            activeSource = source;
            activeSourceChoice = choice;
            sourceColorVisits = 0;
            reflectedOutput.clear();
            int sourceLifeBefore = payer.getLife();
            try {
                if (!new CostPayment(source.getPayCosts(), source).payComputerCosts(new RulesCostDecisionMaker(payer, source, choice.life())))
                    fail("source payment failed");
                if (payer.getLife() != sourceLifeBefore - choice.life()
                        || (choice.life() > 0 && source.getAmountLifePaid() != choice.life())) fail("actual source life payment differs from witness");
                // The normal engine mana-ability path records activation and resolves
                // immediately; it is not a call to ComputerUtil.playNoStack/AI.
                payer.getGame().getStack().addAndUnfreeze(source);
                int expectedColors = source.getManaPart().isComboMana() ? choice.primaryCount()
                        : "Any".equals(source.getManaPart().getOrigProduced()) ? 1 : 0;
                if (sourceColorVisits != expectedColors)
                    fail("missing or unexpected combo source color receipt");
            } finally { activeSource = null; activeSourceChoice = null; }
            var emitted = new java.util.ArrayList<>(source.getManaPart().getLastManaProduced());
            if (emitted.size() != choice.primaryCount()) fail("source output amount changed");
            for (int i = 0; i < emitted.size(); i++) {
                requireOutput(emitted.get(i), choice.output().get(i), producerId, producer, choice.traits(), actual);
            }
            if (reflectedOutput.size()!=choice.bonuses().size()) fail("forecast reflected mana callback missing");
            emitted.addAll(reflectedOutput);
            // Forge reuses one Mana object for multiple units of a color from
            // one activation. Count object occurrences, not a Set and not
            // Mana.equals (which deliberately collapses different producers).
            var expectedPool = poolsBefore.get(payer);
            for (Mana mana : emitted) {
                if (expectedPool.containsKey(mana)) fail("source reused an existing floating token");
            }
            for (Mana mana : emitted) expectedPool.merge(mana, 1, Integer::sum);
            requirePools(poolsBefore, "source production");
            produced.put(choice, emitted);
            actual.getPayingManaAbilities().add(source);
        }
        ManaCostBeingPaid remaining = forge.game.cost.CostAdjustment.benchmarkExpandedMana(actual, actualPrice.payable());
        if (remaining == null) fail("actual X announcement cannot be expanded exactly");
        var expectedPools = poolSnapshot();
        for (var allocation : witness.allocations()) {
            var token = allocation.token();
            Mana mana = token.floating() != null ? token.floating() : produced.get(token.source()).get(token.outputIndex());
            if (!payer.getManaPool().payExactShard(actual, remaining, mana, allocation.shard()))
                fail("witness token/shard could not be consumed exactly");
            var pool = expectedPools.get(payer);
            int units = pool.getOrDefault(mana, 0);
            if (units < 1) fail("spent token absent from pre-payment pool");
            if (units == 1) pool.remove(mana); else pool.put(mana, units - 1);
            requirePools(expectedPools, "exact token consumption");
            actual.getPayingMana().add(mana);
        }
        if (!remaining.isPaid()) fail("witness left an unpaid mana cost");
        paidManaCost = new ManaCostBeingPaid(remaining);
        paid = true;
        paidAction = actual;
        return true;
    }

    private Map<Player, java.util.IdentityHashMap<Mana, Integer>> poolSnapshot() {
        var pools = new java.util.IdentityHashMap<Player, java.util.IdentityHashMap<Mana, Integer>>();
        for (Player player : payer.getGame().getPlayers()) {
            var units = new java.util.IdentityHashMap<Mana, Integer>();
            for (Mana mana : player.getManaPool()) units.merge(mana, 1, Integer::sum);
            pools.put(player, units);
        }
        return pools;
    }

    /** The selected priority action itself produces mana. Native MagicStack
     * resolves mana immediately (without a stack object) and records activation.
     * Run only AFTER complete payment, retaining queued non-mana triggers. */
    void resolveSelectedMana(SpellAbility actual, RulesCostFeasibility.SourceChoice choice, Runnable nativeExecution) {
        assertPaid();
        if(actual!=paidAction || choice.ability()!=actual || !actual.isManaAbility()
                || actual.getActivatingPlayer()!=payer || activeSource!=null || nestedTrigger!=null)
            fail("unrelated standalone mana execution");
        var producer=actual.getManaPart();int id=actual.getHostCard().getId();
        var expected=poolSnapshot();
        activeSource=actual;activeSourceChoice=choice;sourceColorVisits=0;reflectedOutput.clear();
        if(!choice.choice().isEmpty())producer.setExpressChoice(choice.choice());
        try {
            nativeExecution.run();
            if(!producer.getExpressChoice().isEmpty())fail("native mana effect retained express choice");
            int colors=producer.isComboMana()?choice.primaryCount():"Any".equals(producer.getOrigProduced())?1:0;
            if(sourceColorVisits!=colors)fail("standalone mana missing color callback receipt");
            var emitted=new java.util.ArrayList<>(producer.getLastManaProduced());
            if(emitted.size()!=choice.primaryCount() || reflectedOutput.size()!=choice.bonuses().size())
                fail("standalone mana production count mismatch");
            for(int i=0;i<emitted.size();i++)requireOutput(emitted.get(i),choice.output().get(i),id,producer,choice.traits(),actual);
            emitted.addAll(reflectedOutput);
            var own=expected.get(payer);
            for(var mana:emitted)if(own.containsKey(mana))fail("standalone production reused existing token");
            for(var mana:emitted)own.merge(mana,1,Integer::sum);
            requirePools(expected,"standalone mana production");
        } finally {activeSource=null;activeSourceChoice=null;}
    }

    private void requireOutput(Mana mana, int color, int producerId, forge.game.spellability.AbilityManaPart producer,
                               RulesCostFeasibility.OutputTraits traits, SpellAbility actual) {
        if (mana.getColor() != color) fail("source output color changed");
        if (mana.getPlayer() != payer || mana.getSourceCard().getId() != producerId || mana.getManaAbility() != producer)
            fail("source output producer/recipient changed");
        if (!traits.matches(mana)) fail("source output persistence/combat/snow traits changed");
        if (actual == null || mana.isRestricted() || mana.triggersWhenSpent() || mana.addsCounters(actual)
                || mana.addsKeywords(actual) || mana.addsNoCounterMagic(actual)) fail("source emitted unsupported effectful mana");
    }

    private void requirePools(Map<Player, java.util.IdentityHashMap<Mana, Integer>> expected, String stage) {
        var actual = poolSnapshot();
        if (actual.size() != expected.size()) fail(stage + " changed pool ownership");
        for (var entry : expected.entrySet()) {
            var observed = actual.get(entry.getKey());
            var wanted = entry.getValue();
            if (observed == null || observed.size() != wanted.size()
                    || wanted.entrySet().stream().anyMatch(e -> !e.getValue().equals(observed.get(e.getKey()))))
                fail(stage + " changed mana outside the exact receipt");
        }
    }

    public void assertPaid() {
        if (!paid) fail("selected action did not execute its mana payment");
        if(returnRequired) {
            if(chosenReturn==null)fail("return cost was not selected");
            var lki=paidAction.getPaidList("Returned",true);
            var moved=paidAction.getPaidList("ReturnedCards",true);
            if(lki==null || moved==null || lki.size()!=chosenReturn.size() || moved.size()!=chosenReturn.size())
                fail("return receipt count mismatch");
            for(int i=0;i<chosenReturn.size();i++) {
                Card chosen=chosenReturn.get(i),spent=lki.get(i),result=moved.get(i);
                if(spent.getId()!=chosen.getId() || result.getId()!=chosen.getId()
                        || !java.util.Objects.equals(returnVisits.get(chosen.getId()),spent.getGameTimestamp())
                        || spent.getController()!=payer || spent.getOwner()!=returnOwners.get(chosen.getId())
                        || result.getOwner()!=returnOwners.get(chosen.getId())
                        // This domain certifies ordinary return-to-owner-hand.
                        // A destination replacement needs its own execution
                        // contract; absence from battlefield alone is not proof.
                        || !result.isInZone(forge.game.zone.ZoneType.Hand)
                        || result.getOwner().getCardsIn(forge.game.zone.ZoneType.Hand).stream().noneMatch(c->c==result)
                        || payer.getGame().getCardsIn(forge.game.zone.ZoneType.Battlefield).stream().anyMatch(c->c.getId()==chosen.getId()))
                    fail("return receipt differs from selected battlefield visit");
            }
        }
        if(chosenDiscard!=null) {
            var lki=paidAction.getPaidList("Discarded",true);
            if(lki==null || lki.size()!=chosenDiscard.size())fail("discard receipt count mismatch");
            for(int i=0;i<chosenDiscard.size();i++) {
                Card chosen=chosenDiscard.get(i),spent=lki.get(i);
                if(spent.getId()!=chosen.getId() || !java.util.Objects.equals(discardVisits.get(chosen.getId()),spent.getGameTimestamp())
                        || spent.getOwner()!=payer || payer.getCardsIn(forge.game.zone.ZoneType.Hand).stream().anyMatch(c->c.getId()==chosen.getId()))
                    fail("discard receipt differs from selected hand visit");
            }
        }
        if (discardedSource != null) {
            var lki = paidAction.getPaidList("Discarded", true);
            if (lki == null || lki.size() != 1 || lki.get(0).getId() != discardedSource.getId()
                    || lki.get(0).getGameTimestamp() != discardedSourceTimestamp || lki.get(0).getOwner() != payer
                    || payer.getCardsIn(forge.game.zone.ZoneType.Hand).stream().anyMatch(c -> c.getId() == discardedSource.getId()))
                fail("native source-discard did not consume the exact selected hand visit");
        }
        if (sacrificedSource != null) {
            var lki = paidAction.getPaidList("Sacrificed", true);
            if (lki == null || lki.size() != 1 || lki.get(0).getId() != sacrificedSource.getId()
                    || lki.get(0).getController() != payer || (sacrificeRequiresTap && !lki.get(0).isTapped())
                    || payer.getGame().getCardsIn(forge.game.zone.ZoneType.Battlefield).stream()
                        .anyMatch(c -> c.getId() == sacrificedSource.getId()))
                fail("native tap/self-sacrifice did not consume the exact selected source");
        }
        for (var receipt : forcedSpend.entrySet()) {
            var lki = paidAction.getPaidList(receipt.getKey(), true);
            var expected = receipt.getValue();
            if (lki == null || lki.size() != expected.size()) fail("forced nonmana cost receipt count mismatch");
            var spent = lki.stream().map(Card::getId).sorted().toList();
            if (!spent.equals(expected.stream().map(Card::getId).sorted().toList()))
                fail("forced nonmana cost spent cards differ from the rules-forced selection");
            for (Card card : lki) if (!java.util.Objects.equals(forcedVisits.get(card.getId()), card.getGameTimestamp()))
                fail("forced nonmana cost receipt consumed a different visit");
        }
        if (witness.totalLife() > 0 && ((witness.life() > 0 && paidAction.getAmountLifePaid() != witness.life())
                || payer.getLife() != lifeBefore - witness.totalLife())) fail("actual life payment differs from host-selected witness");
    }
    /** Private fixture/audit metadata, never a player observation or policy input. */
    ManaCostBeingPaid paidCostReceipt() {
        if (!paid || paidManaCost==null) fail("mana receipt requested before payment");
        return new ManaCostBeingPaid(paidManaCost);
    }
    /** ManaCost deliberately has identity equals. Tax adjustment constructs a new
     * immutable cost each time, so compare the complete symbolic multiset instead
     * of object identity, rendered labels or converted mana value alone. */
    static boolean sameManaCost(ManaCost a, ManaCost b) {
        if (a==null || b==null || a.isNoCost()!=b.isNoCost() || a.getGenericCost()!=b.getGenericCost()) return false;
        var counts=new java.util.EnumMap<forge.card.mana.ManaCostShard,Integer>(forge.card.mana.ManaCostShard.class);
        for(var shard:a) counts.merge(shard,1,Integer::sum);
        for(var shard:b) counts.merge(shard,-1,Integer::sum);
        return counts.values().stream().allMatch(n->n==0);
    }
    private static void fail(String reason) { throw new RulesCostFeasibility.Unsupported("payment witness: " + reason); }
}
