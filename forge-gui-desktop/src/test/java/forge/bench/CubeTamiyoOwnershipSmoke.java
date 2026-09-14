package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.lang.reflect.*;
import java.util.*;

/** Prepared availability and authority checks. No sampled play-strength claim. */
public final class CubeTamiyoOwnershipSmoke {
    private static int checks,worlds;
    private static final List<String> CASES=List.of("repeat","foreign-copy","cost-change","resource-left");
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Object fixture(String name,Class<?>[] types,Object... args)throws Exception {
        Method m=CubeTamiyoExecutionSmoke.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);
    }
    private static Object snapshot(Player p)throws Exception{return fixture("snapshot",new Class<?>[]{Player.class},p);}
    private static Object invoke(Object plan,String name,Class<?>[] types,Object... args)throws Exception {
        Method m=plan.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(plan,args);
    }
    private static SpellAbility next(Object plan)throws Exception{return (SpellAbility)invoke(plan,"nextAction",new Class<?>[]{});}
    private static boolean owns(Object plan,SpellAbility a)throws Exception{return (boolean)invoke(plan,"owns",new Class<?>[]{SpellAbility.class},a);}
    private static boolean play(Object plan,SpellAbility a)throws Exception{return (boolean)invoke(plan,"play",new Class<?>[]{SpellAbility.class},a);}
    private static void run(String artifact,int seat,String control)throws Exception {
        String prepared=artifact+":complete",key=artifact.replace(' ','_')+"/"+seat+"/"+control;
        List<RegisteredPlayer> entries=new ArrayList<>();
        for(int s=0;s<2;s++){
            Deck d=(Deck)fixture("deck",new Class<?>[]{boolean.class,String.class},s==seat,prepared);
            var lobby=new forge.ai.LobbyPlayerCubeComboAi("Ownership-"+s);lobby.setAiProfile("Default");entries.add(new RegisteredPlayer(d).setPlayer(lobby));
        }
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game g=new Match(rules,entries,"Tamiyo ownership").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        p.setLife(20,null);op.setLife(20,null);
        fixture("populate",new Class<?>[]{Player.class,boolean.class,String.class},p,true,prepared);
        fixture("populate",new Class<?>[]{Player.class,boolean.class,String.class},op,false,prepared);
        g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(993000L+seat*100+CASES.indexOf(control));
        Class<?> type=Class.forName("forge.ai.CubeTamiyoPlan");Constructor<?> ctor=type.getDeclaredConstructor(Player.class);ctor.setAccessible(true);Object plan=ctor.newInstance(p);
        Object before=snapshot(p);SpellAbility selected=next(plan);
        check(selected!=null,"positive availability "+key);check(before.equals(snapshot(p)),"first query state/RNG "+key);
        for(int i=0;i<3;i++){check(next(plan)==selected,"query identity "+key);check(before.equals(snapshot(p)),"repeated query state/RNG "+key);}
        check(owns(plan,selected)&&!owns(plan,null),"exact ownership "+key);
        if(control.equals("foreign-copy")){
            SpellAbility copy=selected.copy(p);check(!owns(plan,copy),"foreign ownership "+key);check(!play(plan,copy),"foreign payment "+key);check(before.equals(snapshot(p)),"foreign refusal native mutation "+key);
        }else if(control.equals("cost-change")){
            selected.setPayCosts(new Cost("1",true));Object changed=snapshot(p);check(!play(plan,selected),"changed cost accepted "+key);check(changed.equals(snapshot(p)),"changed cost refusal native mutation "+key);
        }else if(control.equals("resource-left")){
            Card resource=null;for(Card c:p.getCardsIn(ZoneType.Graveyard))if(c.getName().equals(artifact))resource=c;check(resource!=null,"prepared resource "+key);
            g.getAction().moveToHand(resource,null);Object changed=snapshot(p);
            check(next(plan)==null,"stale resource offered "+key);check(!play(plan,selected),"stale resource payment "+key);check(changed.equals(snapshot(p)),"stale resource refusal native mutation "+key);
        }
        worlds++;System.out.println("TAMIYO_OWNERSHIP_PASS "+key+" checks="+checks);
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"tamiyo-ownership-v100";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(String name:List.of("Tamiyo, Collector of Tales","Displacer Kitten","Aetherflux Reservoir","Lotus Petal","Lion's Eye Diamond","Forest"))StaticData.instance().attemptToLoadCard(name);
        for(String artifact:List.of("Lotus Petal","Lion's Eye Diamond"))for(int seat=0;seat<2;seat++)for(String control:CASES)run(artifact,seat,control);
        System.out.println("TAMIYO_OWNERSHIP_COMPLETE worlds="+worlds+" checks="+checks);
    }catch(Throwable e){e.printStackTrace();System.exit(1);}}
}
