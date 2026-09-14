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
 * produced type or a fixed-color attached-permanent trigger. No card-name dispatch, trigger initialization, or AI simulation.
 * Multi-type reflections need a host color choice and remain explicit failures.
 */
final class ReflectedManaProduction {
    record Bonus(Trigger trigger, int producerId, Player recipient, int color,
                 RulesCostFeasibility.OutputTraits traits) {}

    private static final Map<String,String> REFLECTION = Map.of("DB", "ManaReflected", "ColorOrType", "Type",
            "ReflectProperty", "Produced", "Defined", "You");

    private static boolean attachedFixed(Trigger trigger) {
        return "Card.AttachedBy".equals(trigger.getParam("ValidCard")) && !trigger.hasParam("Activator");
    }

    private static boolean fixedDefinition(Map<String,String> params) {
        String color = params.get("Produced");
        return color != null && Set.of("W", "U", "B", "R", "G", "C").contains(color)
                && params.equals(Map.of("DB", "Mana", "Produced", color, "Amount", "1",
                    "Defined", "TriggeredCardController"));
    }

    static boolean supported(Trigger trigger) {
        if (trigger.getMode() != TriggerType.TapsForMana || !trigger.isStatic()
                || trigger.getSpawningAbility() != null
                || !Set.of("Mode", "ValidCard", "Activator", "Execute", "TriggerZones", "Static", "TriggerDescription")
                    .containsAll(trigger.getMapParams().keySet())
                || trigger.hasParam("TriggerZones") && !"Battlefield".equals(trigger.getParam("TriggerZones"))
                || !trigger.hasParam("Execute")
                || !trigger.getHostCard().getChangedTextColorWords().isEmpty()
                || !trigger.getHostCard().getChangedTextTypeWords().isEmpty()) return false;
        boolean fixed = attachedFixed(trigger);
        if (!fixed && !("Permanent.nonLand".equals(trigger.getParam("ValidCard"))
                && "You".equals(trigger.getParam("Activator"))
                && "Battlefield".equals(trigger.getParam("TriggerZones")))) return false;
        var ability = trigger.getOverridingAbility();
        Map<String, String> params;
        if (ability == null) {
            var state = trigger.getHostCard().getCurrentState();
            if (!state.hasSVar(trigger.getParam("Execute"))) return false;
            params = AbilityFactory.getMapParams(state.getSVar(trigger.getParam("Execute")));
        } else {
            if (ability.getHostCard().getId() != trigger.getHostCard().getId()
                    || ability.getApi() != (fixed ? ApiType.Mana : ApiType.ManaReflected) || ability.getManaPart() == null
                    || ability.getSubAbility() != null || ability.usesTargeting()
                    || !ability.getManaPart().getExpressChoice().isEmpty()
                    || !ability.getManaPart().getManaRestrictions().isEmpty()
                    || !ability.getManaPart().getExtraManaRestriction().isEmpty()) return false;
            params = ability.getMapParams();
        }
        return fixed ? fixedDefinition(params) : params.equals(REFLECTION);
    }

    static boolean matchesEffect(Trigger trigger, SpellAbility effect, int expectedColor) {
        if (!supported(trigger) || effect.getSubAbility() != null || effect.usesTargeting()
                || effect.getManaPart() == null || !effect.getManaPart().getExpressChoice().isEmpty()
                || !effect.getManaPart().getManaRestrictions().isEmpty()
                || !effect.getManaPart().getExtraManaRestriction().isEmpty()) return false;
        if (!attachedFixed(trigger)) return effect.getApi() == ApiType.ManaReflected
                && effect.getMapParams().equals(REFLECTION);
        return effect.getApi() == ApiType.Mana && fixedDefinition(effect.getMapParams())
                && forge.card.mana.ManaAtom.fromName(effect.getParam("Produced")) == expectedColor;
    }

    private static int fixedColor(Trigger trigger) {
        var ability = trigger.getOverridingAbility();
        var params = ability == null
                ? AbilityFactory.getMapParams(trigger.getHostCard().getCurrentState().getSVar(trigger.getParam("Execute")))
                : ability.getMapParams();
        return forge.card.mana.ManaAtom.fromName(params.get("Produced"));
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
            if (!attachedFixed(trigger) && new HashSet<>(output).size() != 1)
                throw new RulesCostFeasibility.Unsupported("reflected mana type requires host choice");
            bonuses.add(new Bonus(trigger, trigger.getHostCard().getId(), payer, attachedFixed(trigger) ? fixedColor(trigger) : output.get(0),
                    new RulesCostFeasibility.OutputTraits(false, false, trigger.getHostCard().isSnow())));
        }
        return List.copyOf(bonuses);
    }
}
