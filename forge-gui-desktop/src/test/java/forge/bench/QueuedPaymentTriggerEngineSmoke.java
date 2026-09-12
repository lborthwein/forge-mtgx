package forge.bench;

import com.google.gson.*;
import forge.StaticData;
import forge.ai.PlayerControllerAi;
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
import java.io.*;
import java.util.*;

/** Cost forecasting must not resolve, discard, or spend ordinary tap triggers. */
public final class QueuedPaymentTriggerEngineSmoke {
    private static int checks;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    private static Card card(String name,Player p,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);
        var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),p);
        c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(zone).add(c);c.setSickness(false);return c;
    }
    private record Result(boolean tapped,int pool,int treasures,boolean ringResolved){}
    private static Result run(int seat,boolean bridge,boolean equipped,boolean opposite,boolean makeStatic){
        return run(seat,bridge,equipped,opposite,makeStatic,"Taps");
    }
    private static Result run(int seat,boolean bridge,boolean equipped,boolean opposite,boolean makeStatic,String event){
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B",1,0,null,"Default")));
        var g=new Match(new GameRules(GameType.Constructed),players,"Queued payment trigger").createGame();
        var p=g.getPlayers().get(seat);g.setAge(GameStage.Play);g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p);
        var magda=card(event.equals("Taps")?"Magda, Brazen Outlaw":"Grizzly Bears",g.getPlayers().get(opposite?1-seat:seat),ZoneType.Battlefield);
        if(!event.equals("Taps")){
            magda.getCurrentState().setSVar("FixtureToken","DB$ Token | TokenScript$ c_a_treasure_sac | TokenOwner$ You");
            String validity=event.equals("ManaAdded")?"ValidSource$ Card.Self":"ValidCard$ Card.Self";
            magda.addTrigger(forge.game.trigger.TriggerHandler.parseTrigger("Mode$ "+event+" | "+validity+" | Execute$ FixtureToken | TriggerZones$ Battlefield | TriggerDescription$ Queue a Treasure after payment.",magda,true));
        }
        Card source;
        if(equipped){var mantle=card("Paradise Mantle",p,ZoneType.Battlefield);mantle.attachToEntity(magda,null);source=magda;}
        else source=card("Mountain",p,ZoneType.Battlefield);
        var ring=card("Sol Ring",p,ZoneType.Hand).getFirstSpellAbility().copyForEnumeration(p);
        var two=card("Mind Stone",p,ZoneType.Hand).getFirstSpellAbility().copyForEnumeration(p);
        g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(97411);
        if(makeStatic)magda.getTriggers().get(0).getMapParams().put("Static","True");
        var before=BenchMenuStateAudit.capture(g);var random=BenchRandomAudit.begin();
        var assessed=RulesCostFeasibility.assess(p,ring);
        if(makeStatic){check(assessed.status()==RulesCostFeasibility.Status.UNSUPPORTED,"immediate unmodeled tap trigger remains refused");return null;}
        check(assessed.status()==RulesCostFeasibility.Status.PAYABLE,"queued tap trigger does not prohibit payment: "+assessed.reason());
        check(RulesCostFeasibility.assess(p,two).status()==RulesCostFeasibility.Status.UNPAYABLE,"future Treasure cannot fund current cost");
        var domain=new RulesPaymentDomain(p,ring);var request=domain.request();
        var option=request.getAsJsonArray("sourceOptions").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(o->o.get("fid").getAsInt()==source.getId()&&o.getAsJsonArray("output").get(0).getAsString().equals("R")).findFirst().orElseThrow();
        check(option.getAsJsonArray("output").size()==1,"queued trigger creates no forecast token");
        BenchMenuStateAudit.assertUnchanged(before,g);BenchRandomAudit.assertUnchanged(random,"queued trigger forecast");
        var answer=new JsonObject();answer.addProperty("paymentVersion",RulesPaymentDomain.PAYMENT_VERSION);answer.addProperty("x",0);answer.addProperty("lifePaid",0);
        String id=option.get("id").getAsString();var order=new JsonArray();order.add(id);answer.add("sourceOrder",order);
        var spend=new JsonArray();var allocation=new JsonObject();allocation.addProperty("token",id+":0");allocation.addProperty("shardIndex",0);spend.add(allocation);answer.add("spend",spend);
        final RulesPaymentExecutor[] payment={new RulesPaymentExecutor(p,ring,domain.select(answer))};
        var channel=new JsonRpcChannel(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream());var session=new BenchSession(channel);session.setLiveGame(g);
        var bridgeController=new PlayerControllerBridge(g,p,p.getLobbyPlayer(),session,BenchSession.Mode.BRIDGE,seat,new CallCounter());
        p.dangerouslySetController(new PlayerControllerAi(g,p,p.getLobbyPlayer()){
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost,forge.game.cost.CostPartMana part,SpellAbility ability,String prompt,forge.game.mana.ManaConversionMatrix matrix,boolean effect){return payment[0].pay(cost,part,ability,effect);}
            @Override public byte chooseColor(String prompt,SpellAbility ability,forge.card.ColorSet colors){return payment[0].chooseSourceColor(ability,colors);}
            @Override public boolean playTrigger(Card host,forge.game.trigger.WrappedAbility ability,boolean mandatory){throw new AssertionError("ordinary trigger resolved during payment");}
        });
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(p,ring,null,payment[0]::decisions),"actual full cast succeeds");payment[0].assertPaid();
        check(source.isTapped()&&ring.getHostCard().isInZone(ZoneType.Stack)&&p.getManaPool().totalMana()==0,"exact cast payment receipt");
        check(p.getCardsIn(ZoneType.Battlefield).stream().noneMatch(c->c.isToken()),"Treasure not created during payment");
        p.dangerouslySetController(bridge?bridgeController:new PlayerControllerAi(g,p,p.getLobbyPlayer()));
        g.getTriggerHandler().runWaitingTriggers();
        check(g.getStack().hasSimultaneousStackEntries()==equipped,"actual tap trigger preserved iff a Dwarf tapped");
        if(equipped){
            check(g.getStack().addAllTriggeredAbilitiesToStack(),"queued trigger receives ordinary stack handling");
            check(g.getStack().peekAbility().getHostCard()==magda,"Magda trigger above paid spell");
            g.getStack().resolveStack();
            check(ring.getHostCard().isInZone(ZoneType.Stack),"trigger resolves before spell");
        }
        g.getStack().resolveStack();
        int treasures=(int)p.getCardsIn(ZoneType.Battlefield).stream().filter(c->c.isToken()&&c.getType().hasSubtype("Treasure")).count();
        check(treasures==(equipped?1:0),"exact delayed Treasure count");
        check(session.integrityFailure(g)==null,"no swallowed controller failure");
        if(bridge&&equipped){
            var coverage=bridgeController.getCounters().toJson().getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
            for(var method:coverage.entrySet()){
                var buckets=method.getValue().getAsJsonObject();check(buckets.get("stock").getAsInt()==0&&buckets.get("unclassified").getAsInt()==0,"postpayment trigger has no Default or unclassified callback: "+method.getKey());
            }
        }
        return new Result(source.isTapped(),p.getManaPool().totalMana(),treasures,ring.getHostCard().isInZone(ZoneType.Battlefield));
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"queued-payment-trigger";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){
            for(boolean equipped:List.of(false,true))check(run(seat,false,equipped,false,false).equals(run(seat,true,equipped,false,false)),"native/bridge effect receipts equal");
            run(seat,true,false,true,false);run(seat,true,false,false,true);
            for(String event:List.of("TapsForMana","ManaAdded")){
                check(run(seat,false,true,false,false,event).equals(run(seat,true,true,false,false,event)),"native/bridge queued "+event+" receipts equal");
                run(seat,true,true,false,true,event);
            }
        }
        System.out.println("PASS "+checks+" queued payment trigger checks; not policy equivalence");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
