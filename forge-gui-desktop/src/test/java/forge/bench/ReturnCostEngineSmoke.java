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

/** Native cost execution, including alternative costs and controlled but not
 * owned lands. Scripted capability fixture, not an AI strength measurement. */
public final class ReturnCostEngineSmoke {
    static int checks;
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;}
    static Card card(String n,Player p,ZoneType z){return SourceDiscardCostEngineSmoke.card(n,p,z);}
    static SpellAbility alternative(Card source,Player p) {
        var base=source.getFirstSpellAbility();base.setActivatingPlayer(p);
        return GameActionUtil.getAlternativeCosts(base,p,false,true).stream()
            .filter(a->a.getPayCosts().getCostParts().stream().anyMatch(c->c instanceof CostReturn))
            .findFirst().orElseThrow();
    }
    static CostReturn cost(SpellAbility a){return (CostReturn)a.getPayCosts().getCostParts().stream().filter(c->c instanceof CostReturn).findFirst().orElseThrow();}
    static JsonObject answer(Card... cards){var a=new JsonObject();var ids=new JsonArray();for(Card c:cards)ids.add(c.getId());a.add("choices",ids);return a;}
    static void rejects(int seat) {
        var g=SourceDiscardCostEngineSmoke.game(seat);var p=g.getPlayers().get(seat);
        Card x=card("Island",p,ZoneType.Battlefield),y=card("Island",p,ZoneType.Battlefield);
        var a=alternative(card("Gush",p,ZoneType.Hand),p);
        for(String invalid:List.of("{}","{\"choices\":[]}","{\"choices\":[-1,2]}","{\"choices\":[1.5,2]}",
                "{\"choices\":[\"1\",2]}","{\"choices\":[999999,888888]}","{\"delegate\":false,\"choices\":[1,2]}")) {
            try{new RulesReturnCostDomain(p,a,cost(a)).select(JsonParser.parseString(invalid).getAsJsonObject());throw new AssertionError(invalid);}
            catch(RulesCostFeasibility.Unsupported expected){checks++;}
        }
        try{new RulesReturnCostDomain(p,a,cost(a)).select(answer(x,x));throw new AssertionError("duplicate");}
        catch(RulesCostFeasibility.Unsupported expected){checks++;}
        var stale=new RulesReturnCostDomain(p,a,cost(a));x.setGameTimestamp(g.getNextTimestamp());
        try{stale.select(answer(x,y));throw new AssertionError("stale");}catch(RulesCostFeasibility.Unsupported expected){checks++;}
        var once=new RulesReturnCostDomain(p,a,cost(a));once.select(answer(x,y));
        try{once.select(answer(x,y));throw new AssertionError("repeat");}catch(RulesCostFeasibility.Unsupported expected){checks++;}
        var changed=new RulesReturnCostDomain(p,a,cost(a));card("Island",p,ZoneType.Battlefield);
        try{changed.select(answer(x,y));throw new AssertionError("changed domain");}catch(RulesCostFeasibility.Unsupported expected){checks++;}
    }
    static void run(int seat,String name,boolean tax,boolean borrowed,boolean extra) {
        var g=SourceDiscardCostEngineSmoke.game(seat);var p=g.getPlayers().get(seat);var opp=g.getPlayers().get(1-seat);
        Card first=card("Island",p,ZoneType.Battlefield),second=name.equals("Gush")?card("Underground Sea",p,ZoneType.Battlefield):null;
        Card unselected=extra?card("Island",p,ZoneType.Battlefield):null;
        if(borrowed){first.setOwner(opp);first.setController(p,g.getNextTimestamp());}
        if(!tax)first.setTapped(true); // Return costs do not require untapped lands.
        if(tax)card("Thalia, Guardian of Thraben",opp,ZoneType.Battlefield);
        var source=card(name,p,ZoneType.Hand);card("Lightning Bolt",p,ZoneType.Library);card("Grief",p,ZoneType.Library);
        g.getAction().checkStateEffects(true);
        var a=alternative(source,p);a.setActivatingPlayer(p);
        var quote=RulesCostFeasibility.assess(p,a);
        check(quote.status()==RulesCostFeasibility.Status.PAYABLE,"native return cost payable "+name+": "+quote.reason());
        check(quote.witness().cost().getGenericCost()==(tax?1:0),"actual tax amount");
        try{new RulesPaymentExecutor(p,a,quote.witness());throw new AssertionError("missing controller accepted");}
        catch(RulesCostFeasibility.Unsupported expected){checks++;}
        int[] calls={0};
        var executor=new RulesPaymentExecutor(p,a,quote.witness(),null,null,null,domain->{
            calls[0]++;check(domain.forced()==!extra,"exact forced cardinality");
            check(domain.request().get("returnValid").getAsString().contains("Island"),"native eligibility predicate");
            check(p.getManaPool().totalMana()==0,"tax spent before return selection");
            if(tax)check(quote.witness().sources().stream().allMatch(s->s.ability().getHostCard().isTapped()),"mana sources already tapped");
            check(p.getCardsIn(ZoneType.Library).size()==2,"no premature draw during cost");
            return domain.select(second==null?answer(first):answer(first,second));
        });
        p.dangerouslySetController(new forge.ai.PlayerControllerAi(g,p,p.getLobbyPlayer()){
            @Override public boolean payManaCost(forge.card.mana.ManaCost mana,CostPartMana part,SpellAbility actual,
                    String prompt,forge.game.mana.ManaConversionMatrix matrix,boolean effect){return executor.pay(mana,part,actual,effect);}
        });
        // Cost-only for Daze: its targeting/optional counter payment is tested
        // separately. Gush is cast and resolved through native execution below.
        if(name.equals("Daze"))check(new CostPayment(a.getPayCosts(),a).payComputerCosts(executor.decisions(a)),"Daze native cost");
        else check(forge.ai.ComputerUtil.handlePlayingSpellAbility(p,a,null,executor::decisions),"Gush native cast");
        executor.assertPaid();checks++;
        check(calls[0]==1,"one return selection");
        check(first.getOwner().getCardsIn(ZoneType.Hand).stream().anyMatch(c->c.getId()==first.getId()),"returned to OWNER hand");
        if(second!=null)check(p.getCardsIn(ZoneType.Hand).stream().anyMatch(c->c.getId()==second.getId()),"second Island returned");
        if(extra)check(p.getCardsIn(ZoneType.Battlefield).contains(unselected),"unselected land remains");
        if(name.equals("Gush")) {
            check(!g.getStack().isEmpty(),"Gush on stack after costs");
            check(p.getCardsIn(ZoneType.Library).size()==2,"Gush draw waits for resolution");
            g.getStack().resolveStack();check(p.getCardsIn(ZoneType.Library).isEmpty(),"Gush draws two natively");
        } else {
            // A same-id result in another zone is not an ordinary-return receipt.
            var moved=a.getPaidList("ReturnedCards",true).get(0);
            g.getAction().moveToGraveyard(moved,null);
            try{executor.assertPaid();throw new AssertionError("wrong return destination accepted");}
            catch(RulesCostFeasibility.Unsupported expected){checks++;}
        }
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (proxy,m,v)->switch(m.getName()){
                    case "getAssetsDir"->args[0]+"/forge-gui/";
                    case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                    case "getCurrentVersion"->"return-cost";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){
            for(String name:List.of("Gush","Daze"))for(boolean extra:List.of(false,true)){
                run(seat,name,false,false,extra);run(seat,name,false,true,extra);run(seat,name,true,false,extra);
            }
            rejects(seat);
        }
        System.out.println("PASS "+checks+" return cost checks; not playing strength");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
