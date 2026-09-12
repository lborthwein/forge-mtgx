package forge.bench;

import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import java.util.*;

/** Rules-only forecast for a mandatory one-unit reflection of a tapped nonland's
 * produced type. No card-name dispatch, trigger initialization, or AI simulation.
 * Multi-type reflections need a host color choice and remain explicit failures.
 */
final class ReflectedManaProduction {
    record Bonus(Trigger trigger, int producerId, Player recipient, int color,
                 RulesCostFeasibility.OutputTraits traits) {}

    static boolean supported(Trigger trigger) {
        if (trigger.getMode() != TriggerType.TapsForMana || !trigger.isStatic()
                || trigger.getSpawningAbility() != null
                || !Set.of("Mode", "ValidCard", "Activator", "Execute", "TriggerZones", "Static", "TriggerDescription")
                    .containsAll(trigger.getMapParams().keySet())
                || !"Permanent.nonLand".equals(trigger.getParam("ValidCard"))
                || !"You".equals(trigger.getParam("Activator"))
                || !"Battlefield".equals(trigger.getParam("TriggerZones"))
                || !trigger.hasParam("Execute")
                || !trigger.getHostCard().getChangedTextColorWords().isEmpty()
                || !trigger.getHostCard().getChangedTextTypeWords().isEmpty()) return false;
        var ability = trigger.getOverridingAbility();
        Map<String, String> params;
        if (ability == null) {
            var state = trigger.getHostCard().getCurrentState();
            if (!state.hasSVar(trigger.getParam("Execute"))) return false;
            params = AbilityFactory.getMapParams(state.getSVar(trigger.getParam("Execute")));
        } else {
            if (ability.getHostCard().getId() != trigger.getHostCard().getId()
                    || ability.getApi() != ApiType.ManaReflected || ability.getManaPart() == null
                    || ability.getSubAbility() != null || ability.usesTargeting()
                    || !ability.getManaPart().getExpressChoice().isEmpty()
                    || !ability.getManaPart().getManaRestrictions().isEmpty()
                    || !ability.getManaPart().getExtraManaRestriction().isEmpty()) return false;
            params = ability.getMapParams();
        }
        return params.equals(Map.of("DB", "ManaReflected", "ColorOrType", "Type",
                "ReflectProperty", "Produced", "Defined", "You"));
    }

    static List<Bonus> forecast(Player payer, SpellAbility source, List<Integer> output) {
        var event = AbilityKey.mapFromCard(source.getHostCard());
        event.put(AbilityKey.Activator, payer);
        event.put(AbilityKey.AbilityMana, source);
        event.put(AbilityKey.Produced, output.stream().map(c -> forge.card.MagicColor.toShortString(c.byteValue()))
                .collect(java.util.stream.Collectors.joining(" ")));
        var matches = payer.getGame().getTriggerHandler().getActiveTrigger(TriggerType.TapsForMana, event);
        var bonuses = new ArrayList<Bonus>();
        for (var trigger : matches) {
            // Ordinary stacked triggers are not immediate mana production.
            // Native execution must still enqueue them; never forecast their
            // future resources as spendable during this payment.
            if (!trigger.isStatic()) continue;
            if (!supported(trigger) || trigger.getHostCard().getController() != payer
                    || !trigger.getHostCard().isInZone(ZoneType.Battlefield))
                throw new RulesCostFeasibility.Unsupported("unverified reflected mana trigger");
            if (new HashSet<>(output).size() != 1)
                throw new RulesCostFeasibility.Unsupported("reflected mana type requires host choice");
            bonuses.add(new Bonus(trigger, trigger.getHostCard().getId(), payer, output.get(0),
                    new RulesCostFeasibility.OutputTraits(false, false, trigger.getHostCard().isSnow())));
        }
        return List.copyOf(bonuses);
    }
}
