package forge.bench;

import com.google.gson.*;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.cost.CostPart;
import forge.game.cost.CostReturn;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import java.util.*;

/** Selection for native return-to-hand costs, made after ordinary mana payment.
 * Ownership is not eligibility: a controlled permanent returns to its owner.
 */
final class RulesReturnCostDomain {
    static final String VERSION = "rules-return-cost-v1";
    final Player payer;
    final SpellAbility ability;
    final CostReturn cost;
    final List<Card> cards;
    final int count;
    private final Map<Integer,Long> visits = new LinkedHashMap<>();
    private final Map<Integer,Player> owners = new LinkedHashMap<>();
    private final String action;
    private List<Card> selection;

    static boolean supports(CostPart part) {
        return part instanceof CostReturn c && !c.payCostFromSource()
                && c.getType()!=null && !c.getType().isEmpty()
                && c.convertAmount()!=null && c.convertAmount()>0 && c.convertAmount()<=16;
    }
    static List<Card> eligible(Player payer, SpellAbility ability, CostReturn cost) {
        if (!supports(cost) || !(ability.isSpell() || ability.isActivatedAbility())) fail("unsupported cost shape");
        return List.copyOf(CardLists.getValidCards(payer.getCardsIn(ZoneType.Battlefield),
                cost.getType().split(";"),payer,ability.getHostCard(),ability));
    }
    RulesReturnCostDomain(Player payer, SpellAbility ability, CostReturn cost) {
        this.payer=payer;this.ability=ability;this.cost=cost;
        cards=eligible(payer,ability,cost);count=cost.convertAmount();
        action=RulesPaymentExecutor.actionKey(ability);
        if(ability.getActivatingPlayer()!=payer || cards.size()<count || !cost.canPay(ability,payer,false))
            fail("unpayable current domain");
        for(Card card:cards) {
            if(visits.put(card.getId(),card.getGameTimestamp())!=null)fail("duplicate identity");
            owners.put(card.getId(),card.getOwner());
        }
    }
    JsonObject request() {
        var out=new JsonObject();out.addProperty("reason","returnCost");out.addProperty("returnCostVersion",VERSION);
        out.addProperty("min",count);out.addProperty("max",count);out.addProperty("returnValid",cost.getType());
        out.addProperty("sourceState",ability.getCardStateName().name());
        out.addProperty("sourceIntrinsic",ability.isIntrinsic() && !ability.isCopiedTrait() && !ability.isCopied()
                && !ability.getHostCard().isCloned() && !ability.getHostCard().isToken());
        out.addProperty("afterManaPayment",true);
        out.add("ability",StateEncoder.encodeSpellAbility(ability,payer.getView()));
        out.add("returnSource",StateEncoder.encodeCard(ability.getHostCard(),payer.getView()));
        var menu=new JsonArray();for(Card card:cards)menu.add(StateEncoder.encodeCard(card,payer.getView()));out.add("menu",menu);
        return out;
    }
    boolean forced() { return cards.size()==count; }
    List<Card> forcedSelection() {
        if(!forced())fail("not forced");var answer=new JsonObject();var ids=new JsonArray();
        for(Card c:cards)ids.add(c.getId());answer.add("choices",ids);return select(answer);
    }
    List<Card> select(JsonObject answer) {
        if(selection!=null || answer==null || answer.has("delegate") || !answer.has("choices") || !answer.get("choices").isJsonArray())
            fail("missing/repeated host choice");
        var ids=answer.getAsJsonArray("choices");if(ids.size()!=count)fail("wrong count");
        if(ability.getActivatingPlayer()!=payer || !action.equals(RulesPaymentExecutor.actionKey(ability)))fail("action changed");
        var current=eligible(payer,ability,cost);
        if(current.size()!=cards.size())fail("battlefield domain changed");
        for(int i=0;i<cards.size();i++) {
            Card c=cards.get(i);
            if(current.get(i)!=c || c.getController()!=payer || c.getOwner()!=owners.get(c.getId())
                    || !Objects.equals(visits.get(c.getId()),c.getGameTimestamp()))fail("battlefield visit changed");
        }
        var out=new ArrayList<Card>();var seen=new HashSet<Integer>();
        for(var raw:ids) {
            if(!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber() || !raw.toString().matches("[0-9]+"))fail("noninteger identity");
            int id;try{id=raw.getAsBigDecimal().intValueExact();}catch(ArithmeticException e){throw new RulesCostFeasibility.Unsupported("return cost identity range");}
            if(!seen.add(id))fail("duplicate selection");
            out.add(cards.stream().filter(c->c.getId()==id).findFirst()
                    .orElseThrow(()->new RulesCostFeasibility.Unsupported("return cost outside domain")));
        }
        selection=List.copyOf(out);return selection;
    }
    List<Card> selection() { if(selection==null)fail("host did not select");return selection; }
    static void fail(String why) { throw new RulesCostFeasibility.Unsupported("return cost: "+why); }
}
