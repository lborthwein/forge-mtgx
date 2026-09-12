package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.card.GamePieceType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import java.util.*;

/** A serial deepest-first permutation of actual pending trigger instances.
 * Labels are resolved to native script text by the host, never ranked here.
 */
final class TriggerOrderChoices {
    static final String VERSION = "host-trigger-order-v1";
    private final List<SpellAbility> original;
    private final List<Integer> remaining = new ArrayList<>();
    private final List<SpellAbility> ordered = new ArrayList<>();
    private final List<JsonObject> identities = new ArrayList<>();
    private final Player actor;

    TriggerOrderChoices(Player actor, List<SpellAbility> abilities) {
        this.actor=actor; this.original=List.copyOf(abilities);
        var seen=Collections.newSetFromMap(new IdentityHashMap<SpellAbility,Boolean>());
        for(int i=0;i<original.size();i++) {
            if(!seen.add(original.get(i))) fail("same pending instance listed twice");
            remaining.add(i); identities.add(identity(actor, original.get(i)));
        }
    }
    boolean needsChoice() { return remaining.size()>1; }
    JsonObject request() {
        var out=new JsonObject();out.addProperty("triggerOrderVersion",VERSION);
        out.addProperty("order","deepest-first");out.addProperty("min",1);out.addProperty("max",1);
        var menu=new JsonArray();
        for(int id:remaining) {
            var item=identities.get(id).deepCopy();item.addProperty("instance",id);menu.add(item);
        }
        out.add("menu",menu);return out;
    }
    void choose(JsonObject answer) {
        if(!needsChoice() || answer==null || answer.has("delegate") || answer.has("choices")
                || !answer.has("triggerOrderVersion") || !answer.get("triggerOrderVersion").isJsonPrimitive()
                || !answer.get("triggerOrderVersion").getAsJsonPrimitive().isString()
                || !VERSION.equals(answer.get("triggerOrderVersion").getAsString())) fail("missing exact host answer version");
        var chosen=answer.get("choice");
        if(chosen==null || !chosen.isJsonPrimitive() || !chosen.getAsJsonPrimitive().isNumber()
                || !chosen.getAsString().matches("0|[1-9][0-9]{0,8}")) fail("invalid pending instance identity");
        int id=chosen.getAsInt();
        if(!remaining.contains(id)) fail("unknown or already-ordered pending instance");
        // Nothing about the pending trigger may change while the host answers.
        for(int pending:remaining) if(!identities.get(pending).equals(identity(actor,original.get(pending))))
            fail("pending trigger identity changed during host decision");
        ordered.add(original.get(id));remaining.remove(Integer.valueOf(id));
    }
    List<SpellAbility> finish() {
        if(needsChoice()) fail("incomplete permutation");
        for(int i=0;i<original.size();i++) if(!identities.get(i).equals(identity(actor,original.get(i))))
            fail("pending trigger identity changed before insertion");
        var result=new ArrayList<>(ordered);
        for(int id:remaining) result.add(original.get(id));
        return List.copyOf(result);
    }
    private static JsonObject identity(Player actor, SpellAbility ability) {
        if(!(ability instanceof WrappedAbility) || ability.getActivatingPlayer()!=actor || ability.getTrigger()==null
                || ability.getTrigger().isStatic() || ability.isCopied() || ability.getHostCard()==null)
            fail("not an owned ordinary pending trigger");
        var trigger=ability.getTrigger();var source=ability.getHostCard();
        if(source.getGame()!=actor.getGame() || trigger.getHostCard()!=source || trigger.getSpawningAbility()!=null
                || !trigger.isIntrinsic() || trigger.isCopiedTrait() || source.isFaceDown() || source.isCloned()
                || !source.getView().canBeShownTo(actor.getView()) || !trigger.getMapParams().equals(trigger.getOriginalMapParams()))
            fail("unresolved, hidden, changed or granted trigger identity");
        var out=new JsonObject();out.addProperty("sourceFid",source.getId());out.addProperty("source",source.getName());
        out.addProperty("triggerId",trigger.getId());
        if(source.getGamePieceType()==GamePieceType.EFFECT) {
            var body=((WrappedAbility)ability).getWrappedAbility();
            if(!source.getName().equals("The Monarch") || !source.isInZone(ZoneType.Command) || actor.getGame().getMonarch()!=actor
                    || !trigger.getMapParams().equals(Map.of("Mode","Phase","Phase","End of Turn","TriggerZones","Command",
                        "ValidPlayer","You","TriggerDescription","At the beginning of your end step, draw a card."))
                    || !body.getMapParams().equals(Map.of("DB","Draw","Defined","You")) || body.getSubAbility()!=null || body.usesTargeting())
                fail("unrecognized rules-mechanic trigger");
            out.addProperty("kind","monarch-end-draw");return out;
        }
        if(source.isToken() || !source.isInZone(ZoneType.Battlefield) || trigger.getCardStateName()==null
                || !"Original".equals(trigger.getCardStateName().name()) || !"Original".equals(source.getCurrentStateName().name())
                || !trigger.hasParam("TriggerDescription")) fail("unsupported intrinsic trigger source");
        out.addProperty("kind","intrinsic");out.addProperty("state","Original");
        out.addProperty("text",trigger.getParam("TriggerDescription"));return out;
    }
    private static void fail(String why) { throw new RulesCostFeasibility.Unsupported("trigger ordering: "+why); }
}
