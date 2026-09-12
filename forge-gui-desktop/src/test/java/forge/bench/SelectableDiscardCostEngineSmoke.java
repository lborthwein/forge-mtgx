package forge.bench;

import com.google.gson.*;
import forge.game.*;
import forge.game.card.Card;
import forge.game.cost.*;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.util.*;

/** Actual card costs; explicit fixture choices, no Default payment selector. */
public final class SelectableDiscardCostEngineSmoke {
    static int checks;
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;}
    static Card card(String n,Player p,ZoneType z){return SourceDiscardCostEngineSmoke.card(n,p,z);}
    static JsonObject answer(Card c){var a=new JsonObject();var ids=new JsonArray();ids.add(c.getId());a.add("choices",ids);return a;}
    static SpellAbility activation(Card source,Player p){
        var a=source.getSpellAbilities().stream().filter(s->s.isActivatedAbility()&&s.getPayCosts()!=null
            &&s.getPayCosts().getCostParts().stream().anyMatch(RulesDiscardCostDomain::supports)).findFirst().orElseThrow();
        a.setActivatingPlayer(p);return a;
    }
    static void run(String name,int seat,boolean forced){
        var g=SourceDiscardCostEngineSmoke.game(seat);var p=g.getPlayers().get(seat);
        var source=card(name,p,ZoneType.Battlefield);
        if(name.equals("Pack Rat"))for(int i=0;i<3;i++)card("Swamp",p,ZoneType.Battlefield);
        var selected=card("Lightning Bolt",p,ZoneType.Hand);
        var other=forced?null:card("Grief",p,ZoneType.Hand);
        var a=activation(source,p);g.getAction().checkStateEffects(true);
        var quote=RulesCostFeasibility.assess(p,a);
        check(quote.status()==RulesCostFeasibility.Status.PAYABLE,"payable "+name);
        try{new RulesPaymentExecutor(p,a,quote.witness());throw new AssertionError("no host accepted");}
        catch(RulesCostFeasibility.Unsupported expected){checks++;}
        int[] calls={0};
        var executor=new RulesPaymentExecutor(p,a,quote.witness(),null,null,domain->{
            calls[0]++;check(domain.forced()==forced,"forced exact count");
            check(p.getManaPool().totalMana()==0,"after mana spent");
            if(name.equals("Pack Rat"))check(p.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c->c.getName().equals("Swamp")).allMatch(Card::isTapped),"after all sources tapped");
            var wire=domain.request();wire.addProperty("seat",seat);wire.addProperty("id",10);wire.addProperty("kind","cardsChoice");
            wire.addProperty("type","ask");wire.addProperty("game","discard-fixture");wire.add("state",StateEncoder.encodeWithStackInstances(g,p));
            wire.add("priorityOption",StateEncoder.encodePriorityAbilityWithTargetDomains(a,p.getView()));
            check("intrinsic-discard".equals(wire.getAsJsonObject("priorityOption").getAsJsonObject("activationIdentity").get("kind").getAsString()),"explicit priority cost identity");
            if(!forced)System.out.println("DISCARD_COST_CASE "+wire);
            return forced?domain.forcedSelection():domain.select(answer(selected));
        });
        p.dangerouslySetController(new forge.ai.PlayerControllerAi(g,p,p.getLobbyPlayer()){
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost,CostPartMana part,SpellAbility actual,
                String prompt,forge.game.mana.ManaConversionMatrix matrix,boolean effect){return executor.pay(cost,part,actual,effect);}
        });
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(p,a,null,executor::decisions),"native action executes");
        executor.assertPaid();checks++;
        check(calls[0]==1,"one explicit choice");
        check(p.getCardsIn(ZoneType.Graveyard).stream().anyMatch(c->c.getId()==selected.getId()),"selected card discarded");
        check(forced||p.getCardsIn(ZoneType.Hand).contains(other),"unselected preserved");
    }
    static void rejects(int seat){
        var g=SourceDiscardCostEngineSmoke.game(seat);var p=g.getPlayers().get(seat);
        var a=activation(card("Putrid Imp",p,ZoneType.Battlefield),p);
        var x=card("Lightning Bolt",p,ZoneType.Hand);card("Grief",p,ZoneType.Hand);
        var cost=(CostDiscard)a.getPayCosts().getCostParts().stream().filter(RulesDiscardCostDomain::supports).findFirst().orElseThrow();
        for(String malformed:List.of("{}","{\"choices\":[]}","{\"choices\":[-1]}","{\"choices\":[1,1]}","{\"choices\":[\"1\"]}","{\"delegate\":false,\"choices\":[1]}")){
            try{new RulesDiscardCostDomain(p,a,cost).select(JsonParser.parseString(malformed).getAsJsonObject());throw new AssertionError("accepted "+malformed);}
            catch(RulesCostFeasibility.Unsupported expected){checks++;}
        }
        var stale=new RulesDiscardCostDomain(p,a,cost);x.setGameTimestamp(g.getNextTimestamp());
        try{stale.select(answer(x));throw new AssertionError("stale accepted");}catch(RulesCostFeasibility.Unsupported expected){checks++;}
        var once=new RulesDiscardCostDomain(p,a,cost);once.select(answer(x));
        try{once.select(answer(x));throw new AssertionError("repeat accepted");}catch(RulesCostFeasibility.Unsupported expected){checks++;}
    }
    static void timing(boolean late){
        var g=SourceDiscardCostEngineSmoke.game(0);var p=g.getPlayers().get(0);
        var a=activation(card("Pack Rat",p,ZoneType.Battlefield),p);var x=card("Lightning Bolt",p,ZoneType.Hand);
        var events=new ArrayList<String>();
        p.dangerouslySetController(new forge.ai.PlayerControllerAi(g,p,p.getLobbyPlayer()){
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost,CostPartMana part,SpellAbility actual,
                String prompt,forge.game.mana.ManaConversionMatrix matrix,boolean effect){events.add("mana");return true;}
        });
        var visitor=new forge.ai.AiCostDecision(p,a,false){
            @Override public PaymentDecision visit(CostDiscard cost){events.add("choice");return PaymentDecision.card(x);}
            @Override public PaymentDecision visit(CostPartMana cost){return PaymentDecision.number(0);}
            @Override public boolean decideAtPayment(CostPart part){return late?RulesDiscardCostDomain.supports(part):super.decideAtPayment(part);}
        };
        check(new CostPayment(a.getPayCosts(),a).payComputerCosts(visitor),"timing payment");
        check(events.equals(late?List.of("mana","choice"):List.of("choice","mana")),"default retains old order: "+events);
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
            (proxy,method,values)->switch(method.getName()){
                case "getAssetsDir" -> args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "select-discard-fixture";
                default -> throw new AssertionError("Unexpected GUI call "+method.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){for(String name:List.of("Pack Rat","Putrid Imp","Oona's Prowler")){run(name,seat,false);run(name,seat,true);}rejects(seat);}
        timing(false);timing(true);System.out.println("PASS selectable discard "+checks+" checks");System.exit(0);
    }catch(Throwable t){t.printStackTrace();System.exit(1);}}
}
