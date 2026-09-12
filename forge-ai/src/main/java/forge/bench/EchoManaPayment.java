package forge.bench;

import forge.card.mana.ManaCost;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import java.util.Map;

/** Echo's separately supplied resolution cost belongs to the actual resolving
 * native trigger. Never installs it as the trigger's insertion cost, and never
 * asks Default whether to pay. Other unless/cumulative-upkeep costs are explicit
 * unsupported domains until their own native policy correspondence is tested. */
final class EchoManaPayment implements RulesResolutionPayment, AutoCloseable {
    private final Player actor;
    private final SpellAbility ability;
    private final WrappedAbility wrapper;
    private final Card source;
    private final long timestamp;
    private final Trigger trigger;
    private final Cost cost;
    private final CostPartMana part;
    private final ManaCost mana;
    private final Map<String,String> params;
    private boolean answered, accepted, completed, closed;

    EchoManaPayment(Player actor, Cost cost, SpellAbility ability, boolean alreadyPaid, FCollectionView<Player> payers) {
        if (actor == null || cost == null || ability == null || alreadyPaid || payers == null
                || payers.size() != 1 || payers.get(0) != actor
                || !(actor.getGame().getStack().peekAbility() instanceof WrappedAbility wrapped))
            throw unsupported("not one native echo payer/resolving wrapper");
        this.actor = actor; this.ability = ability; this.wrapper = wrapped;
        source = ability.getHostCard(); timestamp = source == null ? -1 : source.getGameTimestamp();
        trigger = ability.getTrigger(); this.cost = cost; part = cost.getCostMana();
        if (cost.getCostParts().size() != 1 || part == null || part.isExiledCreatureCost()
                || part.isEnchantedCreatureCost() || part.getMaxWaterbend() != null || part.getXMin() != 0
                || part.isPayAnyNumberOfTimes() || part.getMana().isNoCost() || part.getMana().countX() != 0)
            throw unsupported("nonordinary echo cost");
        mana = part.getMana();
        for (var shard : mana) if (shard.isPhyrexian() || shard.isSnow() || shard.isOr2Generic())
            throw unsupported("unrepresented echo mana shard");
        params = Map.copyOf(ability.getMapParams());
        requireQuote(actor, ability);
    }

    public void requireQuote(Player payer, SpellAbility actual) {
        if (closed || completed || payer != actor || actual != ability || source == null
                || !actor.getGame().getStack().isResolving() || actor.getGame().getStack().peekAbility() != wrapper
                || wrapper.getWrappedAbility() != ability || wrapper.getHostCard() != source
                || wrapper.getTrigger() != trigger || wrapper.getDecider() != null
                || ability.getHostCard() != source || ability.getTrigger() != trigger
                || source.getGameTimestamp() != timestamp || source.getGame() != actor.getGame()
                || source.getController() != actor || !source.isInZone(ZoneType.Battlefield)
                || source.isFaceDown() || source.isCloned() || source.isToken()
                || !"Original".equals(source.getCurrentStateName().name())
                || !source.hasKeyword(Keyword.ECHO) || ability.isCopied() || ability.isCopiedTrait()
                || ability.getApi() != ApiType.Sacrifice || ability.getSubAbility() != null || ability.usesTargeting()
                || !ability.getMapParams().equals(params) || !params.keySet().equals(java.util.Set.of("DB", "SacValid", "Echo"))
                || !"Sacrifice".equals(params.get("DB")) || !"Self".equals(params.get("SacValid"))
                || trigger == null || trigger.getMode() != TriggerType.Phase || trigger.isStatic()
                || !"Upkeep".equals(trigger.getParam("Phase")) || !"You".equals(trigger.getParam("ValidPlayer"))
                || !"Card.Self+cameUnderControlSinceLastUpkeep".equals(trigger.getParam("IsPresent"))
                || cost.getCostParts().size() != 1 || cost.getCostMana() != part
                || !RulesPaymentExecutor.sameManaCost(mana, part.getMana())
                || !RulesPaymentExecutor.sameManaCost(mana, new Cost(params.get("Echo"), true).getTotalMana())
                || actor.hasKeyword("You may pay 0 rather than pay the echo cost for permanents you control."))
            throw unsupported("changed or unscoped native echo resolution");
        MandatoryZeroTriggerExecution.require(actor, ability);
    }

    void answer(boolean yes) {
        requireQuote(actor, ability);
        if (answered) throw unsupported("repeated confirmation");
        answered = true; accepted = yes;
    }
    public void requirePayment(Player payer, SpellAbility actual) {
        requireQuote(payer, actual);
        if (!answered || !accepted) throw unsupported("payment without host acceptance");
    }
    public ManaCost manaCost(Player payer, SpellAbility actual) { requireQuote(payer, actual); return mana; }
    public Cost cost(Player payer, SpellAbility actual) { requireQuote(payer, actual); return cost; }
    public boolean repeated() { return false; }
    void finish(boolean paid) {
        requireQuote(actor, ability);
        if (!answered || paid != accepted) throw unsupported("payment result differs from accepted decision");
        completed = true;
    }
    @Override public void close() { closed = true; }
    private static RulesCostFeasibility.Unsupported unsupported(String why) {
        return new RulesCostFeasibility.Unsupported("echo payment: " + why);
    }
}
