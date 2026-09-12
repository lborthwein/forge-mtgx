package forge.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.card.mana.ManaAtom;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit output domain for a standalone mana activation, not a payment plan
 * or an AI recommendation. Costs are still quoted/executed by RulesPaymentDomain. */
final class PriorityManaActivation {
    static final String VERSION = "priority-mana-v2-native-cost";
    private final Player actor;
    private final SpellAbility ability;
    private final forge.game.card.Card source;
    private final long timestamp;
    private final Map<String,String> params;
    private final List<RulesCostFeasibility.SourceChoice> outputs;
    private RulesCostFeasibility.SourceChoice selected;

    PriorityManaActivation(Player actor, SpellAbility ability) {
        this.actor=actor; this.ability=ability; source=ability.getHostCard();
        timestamp=source.getGameTimestamp(); params=Map.copyOf(ability.getMapParams());
        require();
        var mana=ability.getManaPart();
        if (mana==null || ability.getSubAbility()!=null || ability.usesTargeting()
                || !Set.of("AB","Cost","Produced","Amount","AILogic","SpellDescription","Description",
                    "ActivationZone","Secondary").containsAll(params.keySet())
                || !mana.getManaRestrictions().isEmpty() || !mana.getExtraManaRestriction().isEmpty())
            throw unsupported("unrepresented production definition");
        String count=ability.getParamOrDefault("Amount","1"), produced=mana.getOrigProduced();
        if (!count.matches("[1-9][0-9]?")) throw unsupported("dynamic production amount");
        int amount=Integer.parseInt(count);
        var primary=new ArrayList<RulesCostFeasibility.SourceChoice>();
        if ("Any".equals(produced)) {
            for (String color:List.of("W","U","B","R","G"))
                primary.add(new RulesCostFeasibility.SourceChoice(ability,color,
                    java.util.Collections.nCopies(amount,(int)ManaAtom.fromName(color))));
        } else if (produced.startsWith("Combo")) {
            for (String choice:RulesCostFeasibility.literalComboChoices(produced,amount))
                primary.add(new RulesCostFeasibility.SourceChoice(ability,choice,
                    java.util.Arrays.stream(choice.split(" ")).map(c->(int)ManaAtom.fromName(c)).toList()));
        } else if (produced.matches("[WUBRGC]( [WUBRGC])*")) {
            var colors=new ArrayList<Integer>();
            for(int i=0;i<amount;i++)for(String color:produced.split(" "))colors.add((int)ManaAtom.fromName(color));
            primary.add(new RulesCostFeasibility.SourceChoice(ability,"",colors));
        } else throw unsupported("dynamic or unrepresented production colors");
        var complete=new ArrayList<RulesCostFeasibility.SourceChoice>();
        for(var output:primary) {
            var bonuses=ReflectedManaProduction.forecast(actor,ability,output.output());
            var colors=new ArrayList<>(output.output());for(var bonus:bonuses)colors.add(bonus.color());
            complete.add(new RulesCostFeasibility.SourceChoice(ability,output.choice(),colors,0,output.traits(),bonuses));
        }
        outputs=List.copyOf(complete);
    }
    private void require() {
        if (!ability.isManaAbility() || !ability.isActivatedAbility() || ability.isTrigger()
                || ability.getActivatingPlayer()!=actor || ability.getHostCard()!=source
                || source.getGame()!=actor.getGame() || source.getGameTimestamp()!=timestamp
                || source.getController()!=actor || !source.isInZone(forge.game.zone.ZoneType.Battlefield)
                || !ability.getMapParams().equals(params)) throw unsupported("changed source/actor/ability");
    }
    JsonObject request() {
        require();var out=new JsonObject();out.addProperty("version",VERSION);var choices=new JsonArray();
        out.addProperty("state",ability.getCardStateName().name());
        out.addProperty("originalSource",!source.isCloned() && !source.isToken()
                && !ability.isCopied() && !ability.isCopiedTrait());
        var cost=new JsonObject();cost.addProperty("mana",ability.getPayCosts().hasNoManaCost()?"":ability.getPayCosts().getTotalMana().toString());
        boolean tap=false,sacrifice=false;int life=0;
        for(var part:ability.getPayCosts().getCostParts()) {
            if(part instanceof forge.game.cost.CostPartMana)continue;
            if(part instanceof forge.game.cost.CostTap && !tap)tap=true;
            else if(RulesCostFeasibility.isSingleSelfSacrifice(part) && !sacrifice)sacrifice=true;
            else if(part instanceof forge.game.cost.CostPayLife p && p.convertAmount()!=null && p.convertAmount()>0 && life==0)life=p.convertAmount();
            else throw unsupported("native cost correspondence not represented");
        }
        cost.addProperty("tap",tap);cost.addProperty("sacrificeSelf",sacrifice);cost.addProperty("life",life);out.add("cost",cost);
        for(var output:outputs) {
            var row=new JsonObject();row.addProperty("choice",output.choice());var colors=new JsonArray();
            for(int color:output.output())colors.add(color);row.add("colors",colors);
            row.addProperty("primaryCount",output.primaryCount());choices.add(row);
        }
        out.add("outputs",choices);return out;
    }
    void select(JsonObject answer) {
        require();var raw=answer.get("manaOutput");
        if(selected!=null || raw==null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber())
            throw unsupported("explicit output index required");
        final int index;try {index=raw.getAsBigDecimal().intValueExact();}
        catch(ArithmeticException|NumberFormatException invalid){throw unsupported("non-integral output index");}
        if(index<0 || index>=outputs.size())throw unsupported("output index out of range");
        selected=outputs.get(index);
    }
    RulesCostFeasibility.SourceChoice selected() {
        require();if(selected==null)throw unsupported("missing output selection");return selected;
    }
    private static RulesCostFeasibility.Unsupported unsupported(String why) {
        return new RulesCostFeasibility.Unsupported("priority mana: "+why);
    }
}
