package forge.bench;

import com.google.gson.JsonObject;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import java.util.Map;

/** One ordinary colored mana from a native queued trigger. The host selects;
 * ManaEffect produces. No Default color ranking, synthetic tokens or timing. */
final class TriggeredManaChoice implements AutoCloseable {
    private final Player actor;
    private final SpellAbility ability;
    private final WrappedAbility wrapper;
    private final Card source;
    private final long timestamp;
    private final Map<String,String> params;
    private final forge.game.spellability.AbilityManaPart producer;
    private byte selected;
    private boolean closed;

    TriggeredManaChoice(Player actor, SpellAbility ability) {
        this.actor=actor; this.ability=ability; source=ability.getHostCard(); timestamp=source.getGameTimestamp();
        if (actor.getGame().getStack().isEmpty()
                || !(actor.getGame().getStack().peekAbility() instanceof WrappedAbility actual))
            throw unsupported("not a native resolving wrapper");
        wrapper=actual; params=Map.copyOf(ability.getMapParams()); producer=ability.getManaPart();
        require();
        if (producer==null || !"Any".equals(producer.getOrigProduced()) || !producer.getExpressChoice().isEmpty()
                || !params.keySet().stream().allMatch(java.util.Set.of("DB","Produced","Amount","Defined")::contains)
                || !"Mana".equals(params.get("DB")) || !"Any".equals(params.get("Produced"))
                || !"1".equals(params.getOrDefault("Amount","1")) || !"You".equals(params.getOrDefault("Defined","You")))
            throw unsupported("unrepresented mana production definition");
    }
    private void require() {
        if (closed || !actor.getGame().getStack().isResolving() || actor.getGame().getStack().isEmpty()
                || actor.getGame().getStack().peekAbility()!=wrapper
                || wrapper.getWrappedAbility()!=ability || wrapper.getTrigger()!=ability.getTrigger()
                || wrapper.getHostCard()!=source || source.getGameTimestamp()!=timestamp
                || source.getController()!=actor || !source.isInZone(ZoneType.Battlefield)
                || source.isFaceDown() || source.isCloned() || source.isToken()
                || !"Original".equals(source.getCurrentStateName().name())
                || ability.getHostCard()!=source || ability.getApi()!=ApiType.Mana || ability.getSubAbility()!=null
                || ability.usesTargeting() || ability.isCopiedTrait() || ability.getTrigger()==null || ability.getTrigger().isStatic()
                || !ability.getMapParams().equals(params) || ability.getManaPart()!=producer)
            throw unsupported("changed or unrelated mana trigger");
        MandatoryZeroTriggerExecution.require(actor,ability);
    }
    JsonObject request(SpellAbility actual, ColorSet colors) {
        require();
        if (actual!=ability || selected!=0 || colors==null || !colors.equals(ColorSet.WUBRG)
                || !producer.getExpressChoice().isEmpty()) throw unsupported("unrelated/repeated color callback");
        var out=new JsonObject();out.addProperty("manaChoiceVersion","native-trigger-mana-color-v1");
        out.addProperty("amount",1);
        out.add("ability",StateEncoder.encodeSpellAbility(ability,actor.getView()));
        var choices=new com.google.gson.JsonArray();for(String color:java.util.List.of("W","U","B","R","G"))choices.add(color);
        out.add("colors",choices);return out;
    }
    byte select(JsonObject answer) {
        require();
        var value=answer==null?null:answer.get("color");
        if(selected!=0 || value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !java.util.Set.of("W","U","B","R","G").contains(value.getAsString()))
            throw unsupported("explicit legal color required");
        selected=MagicColor.fromName(value.getAsString());return selected;
    }
    void finish() {
        require();
        var emitted=producer.getLastManaProduced();
        if(selected==0 || emitted.size()!=1 || !producer.getExpressChoice().isEmpty())
            throw unsupported("missing choice or different production count");
        var mana=emitted.iterator().next();
        // Mana retains a last-known-information card copy, not the live object.
        if(mana.getColor()!=selected || mana.getPlayer()!=actor || mana.getSourceCard().getId()!=source.getId()
                || mana.getSourceCard().getGameTimestamp()!=timestamp || mana.getManaAbility()!=producer)
            throw unsupported("production differs from selected color/source/recipient");
    }
    @Override public void close(){closed=true;}
    private static RulesCostFeasibility.Unsupported unsupported(String why){return new RulesCostFeasibility.Unsupported("trigger mana choice: "+why);}
}
