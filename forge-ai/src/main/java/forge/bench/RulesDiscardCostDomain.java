package forge.bench;

import com.google.gson.*;
import forge.game.card.Card;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostPart;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.*;

/** A native discard-cost choice, never a Discard effect or an AI selection. */
final class RulesDiscardCostDomain {
    static final String VERSION = "rules-discard-cost-v1";
    final Player payer;
    final SpellAbility ability;
    final CostDiscard cost;
    final List<Card> cards;
    final Map<Integer,Long> visits = new LinkedHashMap<>();
    final int count;
    boolean selected;
    private List<Card> selection;

    static boolean supports(CostPart part) {
        return part instanceof CostDiscard c && !c.payCostFromSource() && "Card".equals(c.getType())
                && c.convertAmount()!=null && c.convertAmount()>0 && c.convertAmount()<=16;
    }
    static List<Card> eligible(Player payer, SpellAbility ability, CostDiscard cost) {
        if (!supports(cost) || !(ability.isSpell() || ability.isActivatedAbility())) fail("unsupported cost shape");
        if (ability.isActivatedAbility() && !ability.getHostCard().isInZone(ZoneType.Battlefield))
            fail("selectable discard activation outside battlefield");
        if (!payer.canDiscardBy(ability,false)) return List.of();
        return payer.getCardsIn(ZoneType.Hand).stream().filter(c -> c != ability.getHostCard()
                && c.getOwner()==payer && c.canBeDiscardedBy(ability,false)).toList();
    }
    RulesDiscardCostDomain(Player payer, SpellAbility ability, CostDiscard cost) {
        this.payer=payer;this.ability=ability;this.cost=cost;
        cards=List.copyOf(eligible(payer,ability,cost));count=cost.convertAmount();
        if(cards.size()<count || !cost.canPay(ability,payer,false)) fail("unpayable current domain");
        for(Card card:cards) if(visits.put(card.getId(),card.getGameTimestamp())!=null) fail("duplicate card");
    }
    JsonObject request() {
        var out=new JsonObject();out.addProperty("reason","discardCost");out.addProperty("discardCostVersion",VERSION);
        out.addProperty("min",count);out.addProperty("max",count);out.addProperty("discardValid","Card");
        out.addProperty("sourceState",ability.getCardStateName().name());
        out.addProperty("sourceIntrinsic",ability.isIntrinsic() && !ability.isCopiedTrait() && !ability.isCopied()
                && !ability.getHostCard().isCloned() && !ability.getHostCard().isToken());
        out.addProperty("afterManaPayment",true);
        out.add("ability",StateEncoder.encodeSpellAbility(ability,payer.getView()));
        out.add("discardSource",StateEncoder.encodeCard(ability.getHostCard(),payer.getView()));
        var menu=new JsonArray();for(Card card:cards)menu.add(StateEncoder.encodeCard(card,payer.getView()));out.add("menu",menu);
        return out;
    }
    boolean forced() { return cards.size()==count; }
    List<Card> forcedSelection() {
        if(!forced())fail("not forced");var answer=new JsonObject();var choices=new JsonArray();
        for(Card c:cards)choices.add(c.getId());answer.add("choices",choices);return select(answer);
    }
    List<Card> select(JsonObject answer) {
        if(selected || answer==null || answer.has("delegate") || !answer.has("choices") || !answer.get("choices").isJsonArray())
            fail("missing/repeated host choice");
        var choices=answer.getAsJsonArray("choices");if(choices.size()!=count)fail("wrong count");
        var current=eligible(payer,ability,cost);
        if(current.size()!=cards.size())fail("hand domain changed");
        for(int i=0;i<cards.size();i++)if(current.get(i)!=cards.get(i))fail("hand identity/order changed");
        for(Card c:cards)if(!Objects.equals(visits.get(c.getId()),c.getGameTimestamp()))fail("hand visit changed");
        var out=new ArrayList<Card>();var seen=new HashSet<Integer>();
        for(var raw:choices) {
            if(!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber() || !raw.toString().matches("[0-9]+"))fail("noninteger identity");
            int id;try{id=raw.getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new RulesCostFeasibility.Unsupported("discard cost identity range");}
            if(!seen.add(id))fail("duplicate selection");
            var card=cards.stream().filter(c->c.getId()==id).findFirst().orElseThrow(()->new RulesCostFeasibility.Unsupported("discard cost outside domain"));
            out.add(card);
        }
        selected=true;selection=List.copyOf(out);return selection;
    }
    List<Card> selection() { if(!selected)fail("host did not select");return selection; }
    static void fail(String why) { throw new RulesCostFeasibility.Unsupported("discard cost: "+why); }
}
