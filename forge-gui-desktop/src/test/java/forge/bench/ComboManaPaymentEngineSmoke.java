package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Actual Hierarch scripts and exact combo production; no benchmark outcomes. */
public final class ComboManaPaymentEngineSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static void refused(Runnable action,String why){try{action.run();throw new AssertionError(why);}catch(RulesCostFeasibility.Unsupported expected){checks++;}}
    private static Card card(String name,Player owner,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),owner);
        c.setGameTimestamp(owner.getGame().getNextTimestamp());owner.getZone(zone).add(c);c.setSickness(false);return c;
    }
    private static Game game(int seat){
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1",1,0,null,"Default")));
        var g=new Match(new GameRules(GameType.Constructed),players,"Combo mana fixture").createGame();g.setAge(GameStage.Play);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1,g.getPlayers().get(seat));return g;
    }
    private static void execute(int seat,String name,String choice,boolean multi){
        var g=game(seat);var p=g.getPlayers().get(seat);var source=card(name,p,ZoneType.Battlefield);
        if(multi)source.addSpellAbility(forge.game.ability.AbilityFactory.getAbility("AB$ Mana | Cost$ T | Produced$ Combo W U | Amount$ 2",source));
        var spell=card(multi?"Silvercoat Lion":"Sol Ring",p,ZoneType.Hand).getFirstSpellAbility().copyForEnumeration(p);
        g.getAction().checkStateEffects(true);var before=BenchMenuStateAudit.capture(g);var rng=BenchRandomAudit.begin();
        var assessed=RulesCostFeasibility.assess(p,spell);check(assessed.status()==RulesCostFeasibility.Status.PAYABLE,"actual combo payable: "+assessed.reason());
        var domain=new RulesPaymentDomain(p,spell);var ask=domain.request();var options=ask.getAsJsonArray("sourceOptions");
        check(options.size()==(multi?4:3),"complete choices, including ordered mixed outputs");
        var selected=options.asList().stream().map(JsonElement::getAsJsonObject).filter(o->choice.equals(o.get("choice").getAsString())).findFirst().orElseThrow();
        check(selected.getAsJsonArray("output").size()==(multi?2:1),"one choice does not create every allowed color");
        check(options.asList().stream().map(o->o.getAsJsonObject().get("group").getAsString()).distinct().count()==1,"one physical mana-source group");
        BenchMenuStateAudit.assertUnchanged(before,g);BenchRandomAudit.assertUnchanged(rng,"combo enumeration");
        String id=selected.get("id").getAsString();var answer=new JsonObject();answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);
        answer.addProperty("x",0);answer.addProperty("lifePaid",0);var order=new JsonArray();order.add(id);answer.add("sourceOrder",order);
        var spend=new JsonArray();var shards=ask.getAsJsonObject("cost").getAsJsonArray("shards");var output=selected.getAsJsonArray("output");
        // Explicit fixture allocation: white shard receives the white token.
        var unused=new ArrayList<Integer>();for(int i=0;i<output.size();i++)unused.add(i);
        for(int i=0;i<shards.size();i++){
            boolean white=shards.get(i).getAsString().equals("WHITE");
            int unit=unused.stream().filter(j->!white||output.get(j).getAsString().equals("W")).findFirst().orElseThrow();
            if(!white&&multi&&unused.size()>1)unit=unused.stream().filter(j->!output.get(j).getAsString().equals("W")).findFirst().orElse(unit);
            unused.remove(Integer.valueOf(unit));var a=new JsonObject();a.addProperty("token",id+":"+unit);a.addProperty("shardIndex",i);spend.add(a);
        }
        answer.add("spend",spend);var payment=new RulesPaymentExecutor(p,spell,domain.select(answer));
        refused(()->payment.chooseSourceColor(spell,forge.card.ColorSet.fromMask(forge.card.MagicColor.ALL_COLORS)),"inactive callback must refuse");
        int[] callbacks={0};
        p.dangerouslySetController(new forge.ai.PlayerControllerAi(g,p,p.getLobbyPlayer()){
            @Override public boolean payManaCost(forge.card.mana.ManaCost c,forge.game.cost.CostPartMana part,SpellAbility actual,String prompt,forge.game.mana.ManaConversionMatrix matrix,boolean effect){return payment.pay(c,part,actual,effect);}
            @Override public byte chooseColor(String message,SpellAbility actual,forge.card.ColorSet colors){callbacks[0]++;return payment.chooseSourceColor(actual,colors);}
        });
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(p,spell,null,payment::decisions),"actual combo-funded cast executes");payment.assertPaid();
        check(callbacks[0]==(multi?2:1),"each exact singleton callback accounted, no AI color policy");
        check(source.isTapped()&&spell.getHostCard().isInZone(ZoneType.Stack)&&p.getManaPool().totalMana()==0,"exact source/stack/pool receipt");
        check(source.getManaAbilities().get(0).getManaPart().getExpressChoice().isEmpty(),"engine clears express choices");
        refused(()->payment.chooseSourceColor(spell,forge.card.ColorSet.fromMask(forge.card.MagicColor.ALL_COLORS)),"completed callback must refuse");
        System.out.println("COMBO_EXECUTED seat="+seat+" source="+name+" choice="+choice);
    }
    private static void legality(int seat){
        var g=game(seat);var p=g.getPlayers().get(seat);var noble=card("Noble Hierarch",p,ZoneType.Battlefield);
        var bolt=card("Lightning Bolt",p,ZoneType.Hand).getFirstSpellAbility().copyForEnumeration(p);
        check(RulesCostFeasibility.assess(p,bolt).status()==RulesCostFeasibility.Status.UNPAYABLE,"subset does not grant red");
        var counter=card("Counterspell",p,ZoneType.Hand).getFirstSpellAbility().copyForEnumeration(p);
        check(RulesCostFeasibility.assess(p,counter).status()==RulesCostFeasibility.Status.UNPAYABLE,"one source cannot pay two blue");
        var ring=card("Sol Ring",p,ZoneType.Hand).getFirstSpellAbility().copyForEnumeration(p);
        noble.setSickness(true);check(RulesCostFeasibility.assess(p,ring).status()==RulesCostFeasibility.Status.UNPAYABLE,"sick tap source unavailable");
        noble.setSickness(false);noble.setTapped(true);check(RulesCostFeasibility.assess(p,ring).status()==RulesCostFeasibility.Status.UNPAYABLE,"tapped source unavailable");
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"combo-mana-v1";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});BenchRandomAudit.install(94001);
        check(RulesCostFeasibility.literalComboChoices("Combo Any",2).size()==25,"all ordered two-color choices");
        for(String bad:List.of("Combo Chosen","Combo Different W U","Combo C W","Combo W W"))refused(()->RulesCostFeasibility.literalComboChoices(bad,1),"unknown syntax refused");
        refused(()->RulesCostFeasibility.literalComboChoices("Combo Any",4),"whole oversized domain refused, never truncated");
        for(int seat=0;seat<2;seat++){
            for(String color:List.of("W","U","G"))execute(seat,"Noble Hierarch",color,false);
            for(String color:List.of("B","R","G"))execute(seat,"Ignoble Hierarch",color,false);
            for(String choice:List.of("W W","W U","U W"))execute(seat,"Grizzly Bears",choice,true);
            legality(seat);
        }
        System.out.println("PASS "+checks+" combo mana checks; not full bridge coverage");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
