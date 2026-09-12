package forge.bench;

import com.google.gson.JsonObject;
import forge.game.cost.*;
import forge.game.spellability.SpellAbility;

/** Intrinsic fixed-cost identity for native ability correspondence, not a policy rank. */
final class PriorityActivationIdentity {
    private PriorityActivationIdentity() {}
    static JsonObject encode(SpellAbility sa) {
        var out=new JsonObject();out.addProperty("version","priority-activation-identity-v1");
        var host=sa.getHostCard();
        if (!sa.isActivatedAbility() || !sa.isIntrinsic() || sa.isCopiedTrait() || sa.isCopied()
                || host==null || host.isCloned() || host.isToken() || host.isFaceDown()
                || !"Original".equals(sa.getCardStateName().name()) || sa.getPayCosts()==null
                || sa.costHasX() || sa.hasParam("Announce") || sa.getOptionalCosts().iterator().hasNext()) {
            out.addProperty("kind","unsupported");return out;
        }
        int tap=0,sac=0,life=0,discard=0,chosenDiscard=0;
        for(var part:sa.getPayCosts().getCostParts()) {
            if(part instanceof CostPartMana)continue;
            if(part instanceof CostTap){if(++tap>1){out.addProperty("kind","unsupported");return out;}}
            else if(RulesCostFeasibility.isSingleSelfSacrifice(part)){if(++sac>1){out.addProperty("kind","unsupported");return out;}}
            else if(RulesCostFeasibility.isSingleSelfDiscard(part)){if(++discard>1){out.addProperty("kind","unsupported");return out;}}
            else if(RulesDiscardCostDomain.supports(part) && chosenDiscard==0)chosenDiscard=((CostDiscard)part).convertAmount();
            else if(part instanceof CostPayLife pay && pay.convertAmount()!=null && life==0 && pay.convertAmount()>0)life=pay.convertAmount();
            else {out.addProperty("kind","unsupported");return out;}
        }
        out.addProperty("kind","intrinsic-fixed");out.addProperty("state","Original");
        out.addProperty("mana",sa.getPayCosts().getTotalMana().toString());
        out.addProperty("tap",tap==1);out.addProperty("sacrificeSelf",sac==1);out.addProperty("life",life);
        out.addProperty("text",sa.getOriginalDescription());
        var modes=new com.google.gson.JsonArray();
        if(sa.getApi()==forge.game.ability.ApiType.Charm) {
            var choices=sa.getAdditionalAbilityList("Choices");
            if(choices==null){out.addProperty("kind","unsupported");return out;}
            for(int i=0;i<choices.size();i++) {
                var mode=new JsonObject();mode.addProperty("index",i);mode.addProperty("text",choices.get(i).getOriginalDescription());modes.add(mode);
            }
        }
        out.add("modes",modes);
        if(sa.isKeyword(forge.game.keyword.Keyword.LEVEL_UP)) {
            // Identify the actual keyword-generated effect, not a label that
            // happens to say "level up". Mutated/extended effects must refuse.
            if(sa.getApi()!=forge.game.ability.ApiType.PutCounter || sa.usesTargeting() || sa.getSubAbility()!=null
                    || !"LEVEL".equals(sa.getParam("CounterType")) || !"1".equals(sa.getParamOrDefault("CounterNum","1"))
                    || !"Self".equals(sa.getParamOrDefault("Defined","Self")) || !"True".equals(sa.getParam("SorcerySpeed"))
                    || tap!=0 || sac!=0 || life!=0 || discard!=0 || chosenDiscard!=0 || modes.size()!=0
                    || !java.util.Set.of("AB","Cost","PrecostDesc","CostDesc","SorcerySpeed","Secondary",
                        "CounterType","CounterNum","Defined","StackDescription","SpellDescription").containsAll(sa.getMapParams().keySet())) {
                out.addProperty("kind","unsupported");return out;
            }
            out.addProperty("version","priority-activation-identity-v4");
            out.addProperty("kind","intrinsic-level-up");out.addProperty("counter","level");
            out.addProperty("amount",1);out.addProperty("sorceryOnly",true);
        }
        if(chosenDiscard>0) {
            if(tap!=0 || sac!=0 || life!=0 || discard!=0 || modes.size()!=0
                    || !host.isInZone(forge.game.zone.ZoneType.Battlefield)
                    || !"Battlefield".equals(sa.getParamOrDefault("ActivationZone","Battlefield"))) {
                out.addProperty("kind","unsupported");return out;
            }
            out.addProperty("version","priority-activation-identity-v3");
            out.addProperty("kind","intrinsic-discard");out.addProperty("discardCount",chosenDiscard);
        }
        if(discard==1) {
            if(tap!=0 || sac!=0 || !"Hand".equals(sa.getParam("ActivationZone"))
                    || !host.isInZone(forge.game.zone.ZoneType.Hand) || modes.size()!=0) {
                out.addProperty("kind","unsupported");return out;
            }
            if(sa.isCycling()) {
                var cycling=new JsonObject();
                if(sa.getSubAbility()!=null || sa.usesTargeting() || sa.hasParam("Defined")) {
                    out.addProperty("kind","unsupported");return out;
                }
                if(sa.isKeyword(forge.game.keyword.Keyword.CYCLING) && sa.getApi()==forge.game.ability.ApiType.Draw
                        && "1".equals(sa.getParamOrDefault("NumCards","1"))) cycling.addProperty("kind","draw");
                else if(sa.isKeyword(forge.game.keyword.Keyword.TYPECYCLING) && sa.getApi()==forge.game.ability.ApiType.ChangeZone
                        && "Library".equals(sa.getParam("Origin")) && "Hand".equals(sa.getParam("Destination"))
                        && "1".equals(sa.getParamOrDefault("ChangeNum","1"))
                        && java.util.List.of("Plains","Island","Swamp","Mountain","Forest").contains(sa.getParam("ChangeType"))) {
                    cycling.addProperty("kind","land");cycling.addProperty("subtype",sa.getParam("ChangeType"));
                } else {out.addProperty("kind","unsupported");return out;}
                out.add("cycling",cycling);
            }
            out.addProperty("version","priority-activation-identity-v2");
            out.addProperty("kind","intrinsic-hand-discard");out.addProperty("discardSelf",true);
        }
        return out;
    }
}
