package forge.bench;

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
import java.lang.reflect.Proxy;
import java.util.*;

/** Direct ownership-boundary controls, not whole games or policy strength.
 * Mutations are explicit adversarial fixture interventions. Rejection must
 * occur without payment, stack insertion or other native/RNG changes. */
public final class CubeKikiSyrOwnershipSmoke {
    private static final List<String> CASES=List.of("copy-action","copied-flag","changed-cost","changed-param","added-subability","foreign-actor","foreign-host","retarget-copy","retarget-shot","exile-kiki","exile-meta","exile-syr","exile-outlet","blink-syr","tap-syr","sicken-syr","other-turn","combat-phase","replay-paid-action","repeat-query","actual-action");
    private static Object invoke(Object object,String name,Class<?>[] types,Object...args)throws Exception {var m=object.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(object,args);}
    private static Object plan(Player p)throws Exception {var f=forge.ai.CubeComboPlayerController.class.getDeclaredField("kikiSyrPlan");f.setAccessible(true);return f.get(p.getController());}
    private static Object snapshot(Player p)throws Exception {var m=CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot",Player.class);m.setAccessible(true);return m.invoke(null,p);}
    private static Object fixture(String name,Class<?>[] types,Object...args)throws Exception {var m=CubeKikiSyrExecutionSmoke.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}
    private static Card find(Player p,String name){return p.getCardsIn(ZoneType.Battlefield).stream().filter(c->c.getName().equals(name)).findFirst().orElseThrow();}
    private static void run(String control,int seat)throws Exception {
        List<RegisteredPlayer> entries=new ArrayList<>();
        for(int s=0;s<2;s++){var lobby=s==seat?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);lobby.setAiProfile("Default");entries.add(new RegisteredPlayer((Deck)fixture("deck",new Class<?>[]{boolean.class,String.class},s==seat,"complete")).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game g=new Match(rules,entries,"Kiki Syr ownership boundary").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        p.setLife(control.equals("retarget-shot")?51:20,null);op.setLife(20,null);
        fixture("populate",new Class<?>[]{Player.class,boolean.class,String.class},p,true,"complete");fixture("populate",new Class<?>[]{Player.class,boolean.class,String.class},op,false,"complete");
        g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(992800L+100L*seat+CASES.indexOf(control));
        Object plan=plan(p);SpellAbility a=(SpellAbility)invoke(plan,"nextAction",new Class<?>[0]);
        if(a==null||!Boolean.TRUE.equals(invoke(plan,"owns",new Class<?>[]{SpellAbility.class},a)))throw new AssertionError("no positive proposal");
        switch(control){
            case "copy-action"->a=a.copy(p);
            case "copied-flag"->a.setCopied(true);
            case "changed-cost"->a.setPayCosts(a.getPayCosts().copy());
            case "changed-param"->a.getMapParams().put("LifeAmount","99");
            case "added-subability"->a.setSubAbility((forge.game.spellability.AbilitySub)forge.game.ability.AbilityFactory.getAbility("DB$ GainLife | LifeAmount$ 99",a.getHostCard()));
            case "foreign-actor"->a.setActivatingPlayer(op);
            case "foreign-host"->a.setHostCard(find(p,"Island"));
            case "retarget-shot"->{a.resetTargets();a.getTargets().add(p);}
            case "retarget-copy"->{a.resetTargets();a.getTargets().add(find(p,"Island"));}
            case "exile-kiki","exile-meta","exile-syr","exile-outlet"->{String n=switch(control){case "exile-kiki"->"Kiki-Jiki, Mirror Breaker";case "exile-meta"->"Phyrexian Metamorph";case "exile-syr"->"Syr Ginger, the Meal Ender";default->"Aetherflux Reservoir";};g.getAction().exile(find(p,n),null,forge.game.ability.AbilityKey.newMap());g.getAction().checkStateEffects(true);}
            case "blink-syr"->{Card c=find(p,"Syr Ginger, the Meal Ender");g.getAction().exile(c,null,forge.game.ability.AbilityKey.newMap());g.getAction().moveTo(ZoneType.Battlefield,g.getCardState(c,null),null,forge.game.ability.AbilityKey.newMap());g.getAction().checkStateEffects(true);}
            case "tap-syr"->find(p,"Syr Ginger, the Meal Ender").setTapped(true);
            case "sicken-syr"->find(p,"Syr Ginger, the Meal Ender").setSickness(true);
            case "repeat-query"->{Object before=snapshot(p);for(int i=0;i<200;i++){if(invoke(plan,"nextAction",new Class<?>[0])!=a||!before.equals(snapshot(p)))throw new AssertionError("query changed native/RNG or selected identity");}var f=plan.getClass().getDeclaredField("actions");f.setAccessible(true);if(f.getInt(plan)!=0)throw new AssertionError("queries spent action budget");}
            case "other-turn"->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,op,start+1);
            case "combat-phase"->g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,p,start);
            case "replay-paid-action"->{if(!Boolean.TRUE.equals(invoke(plan,"play",new Class<?>[]{SpellAbility.class},a)))throw new AssertionError("first payment failed");}
            default->{}
        }
        Object before=snapshot(p);boolean played=Boolean.TRUE.equals(invoke(plan,"play",new Class<?>[]{SpellAbility.class},a));
        boolean expected=List.of("actual-action","repeat-query").contains(control);
        if(played!=expected)throw new AssertionError("ownership accepted invalid action case="+control+" seat="+seat+" played="+played);
        if(!expected&&!before.equals(snapshot(p)))throw new AssertionError("rejection mutated native/RNG case="+control);
        System.out.println("KIKI_SYR_OWNERSHIP case="+control+" seat="+seat+" played="+played+" rejectedWithoutNativeMutation="+!expected);
    }
    public static void main(String[]args){try {
        GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"kiki-syr-ownership-v1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(String name:List.of("Kiki-Jiki, Mirror Breaker","Phyrexian Metamorph","Syr Ginger, the Meal Ender","Intrepid Adversary","Aetherflux Reservoir","Island","Forest"))StaticData.instance().attemptToLoadCard(name);
        for(String c:CASES)for(int seat=0;seat<2;seat++)run(c,seat);
        System.out.println("KIKI_SYR_OWNERSHIP_COMPLETE cases=42");
    }catch(Throwable e){e.printStackTrace();System.exit(1);}}
}
