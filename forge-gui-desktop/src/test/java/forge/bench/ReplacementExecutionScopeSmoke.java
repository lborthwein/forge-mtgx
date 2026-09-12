package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.RegisteredPlayer;
import forge.game.replacement.ReplacementHandler;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.List;

/** Deliberate authorization fault injection, not proof of replacement selection.
 * Real Adventure/ETB resolution is checked by SpellFaceExecutionSmoke. */
public final class ReplacementExecutionScopeSmoke {
    private static int checks;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static void rejected(Runnable run){try{run.run();throw new AssertionError("unexpected acceptance");}
        catch(RulesCostFeasibility.Unsupported expected){checks++;}}
    private static void run(int seat,String fault)throws Exception{
        var session=new BenchSession(new JsonRpcChannel(new ByteArrayInputStream(new byte[0]),new ByteArrayOutputStream()));
        var lobby=new LobbyPlayerBridge("Owner",null,session,BenchSession.Mode.BRIDGE,seat);lobby.setAiProfile("Default");
        var own=new RegisteredPlayer(new Deck()).setPlayer(lobby);
        var other=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Other",1-seat,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),seat==0?List.of(own,other):List.of(other,own),"Replacement scope injection").createGame();
        var actor=game.getPlayers().get(seat);session.setLiveGame(game);game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        StaticData.instance().attemptToLoadCard("Grizzly Bears");
        var card=Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard("Grizzly Bears"),actor);
        card.setGameTimestamp(game.getNextTimestamp());actor.getZone(ZoneType.Battlefield).add(card);
        var re=ReplacementHandler.parseReplacement("Event$ Moved | ValidCard$ Card.Self | Origin$ Stack | Destination$ Battlefield",card,true);
        var ability=AbilityFactory.getAbility("DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1",card);
        ability.setActivatingPlayer(actor);ability.setReplacementEffect(re);re.setOverridingAbility(ability);re.setHasRun(true);
        var controller=(PlayerControllerBridge)actor.getController();
        var rng=BenchRandomAudit.begin();
        if(fault.equals("healthy")){
            controller.withReplacementExecutionScope(re,ability,()->controller.playSpellAbilityNoStack(ability,true));
            check(card.getCounters(CounterEnumType.P1P1)==1,"exact authorized effect resolves");
            check(session.integrityFailure(game)==null,"healthy scope not invalidated");
        } else {
            switch(fault){
                case "unscoped" -> rejected(()->controller.playSpellAbilityNoStack(ability,true));
                case "inactive" -> {re.setHasRun(false);rejected(()->controller.withReplacementExecutionScope(re,ability,()->{}));}
                case "unconsumed" -> rejected(()->controller.withReplacementExecutionScope(re,ability,()->{}));
                case "wrong-actor" -> {ability.setActivatingPlayer(game.getPlayers().get(1-seat));rejected(()->controller.withReplacementExecutionScope(re,ability,()->{}));}
                case "repeat" -> rejected(()->controller.withReplacementExecutionScope(re,ability,()->{
                    controller.playSpellAbilityNoStack(ability,true);controller.playSpellAbilityNoStack(ability,true);}));
                case "wrong-ability" -> {var otherAbility=AbilityFactory.getAbility("DB$ PutCounter | Defined$ Self | CounterType$ P1P1 | CounterNum$ 1",card);
                    otherAbility.setActivatingPlayer(actor);otherAbility.setReplacementEffect(re);
                    rejected(()->controller.withReplacementExecutionScope(re,ability,()->controller.playSpellAbilityNoStack(otherAbility,true)));}
                case "runtime" -> {try{controller.withReplacementExecutionScope(re,ability,()->{throw new IllegalStateException("injected");});throw new AssertionError("swallowed");}
                    catch(IllegalStateException expected){checks++;}}
                case "error" -> {try{controller.withReplacementExecutionScope(re,ability,()->{throw new LinkageError("injected");});throw new AssertionError("swallowed");}
                    catch(LinkageError expected){checks++;}}
                case "nonzero" -> {ability.setPayCosts(new forge.game.cost.Cost("1",true));rejected(()->controller.withReplacementExecutionScope(re,ability,()->controller.playSpellAbilityNoStack(ability,true)));}
                default -> throw new AssertionError(fault);
            }
            check(session.integrityFailure(game)!=null,"failed scope invalidates live game");
            check(card.getCounters(CounterEnumType.P1P1)==(fault.equals("repeat")?1:0),"no unauthorized extra resolution");
        }
        for(String name:List.of("activeReplacementExecution","activeZeroTriggerPayment")){
            var field=PlayerControllerBridge.class.getDeclaredField(name);field.setAccessible(true);check(field.get(controller)==null,"scope unwound "+name);
        }
        check(game.costPaymentStack.peek()==null,"cost stack balanced");BenchRandomAudit.assertUnchanged(rng,"replacement scope injection");
        // A retained token cannot authorize subsequent payment after scope exit.
        re.setHasRun(true);ability.setActivatingPlayer(actor);
        var scope=new RulesReplacementExecution(actor,re,ability);scope.consume(actor,ability,true);scope.close();
        rejected(()->scope.requirePayment(actor,ability));
        System.out.println("PASS replacement scope seat="+seat+" fault="+fault);
    }
    public static void main(String[]args){try{
        GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,a)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"replacement-scope-injection";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        BenchRandomAudit.install(91604);
        for(int seat=0;seat<2;seat++)for(String fault:List.of("healthy","unscoped","inactive","unconsumed","wrong-actor","repeat","wrong-ability","runtime","error","nonzero"))run(seat,fault);
        System.out.println("PASS "+checks+" replacement scope injection checks");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
