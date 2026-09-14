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

/** Direct native payment component checks. Actions are explicitly selected by
 * this fixture, not evidence of autonomous full-plan execution. Own hand only. */
public final class CubeStormProbeOwnershipSmoke {
    private static final List<String> CASES=List.of("copy-action","foreign-target","foreign-actor","copied-flag","changed-cost","changed-param","changed-draw","appended-draw","blink-host","other-turn","combat-phase","replay-action","actual-action");
    private record Placement(String name,ZoneType zone){}
    private static List<Placement> placements(boolean own,String control){
        List<Placement> out=new ArrayList<>();
        if(own){out.add(new Placement("Gitaxian Probe",control.equals("grave-no-permission")?ZoneType.Graveyard:ZoneType.Hand));for(int i=0;i<3;i++)out.add(new Placement("Swamp",ZoneType.Battlefield));if(control.startsWith("blue-"))out.add(new Placement("Island",ZoneType.Battlefield));}
        else if(control.contains("no-pay-life"))out.add(new Placement("Angel of Jubilation",ZoneType.Battlefield));
        while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
    }
    private static Deck deck(boolean own,String control){Deck d=new Deck("Probe payment boundary");for(var c:placements(own,control))d.getMain().add(c.name(),1);return d;}
    private static void populate(Player p,boolean own,String control){
        for(ZoneType z:ZoneType.values())if(p.getZone(z)!=null)p.getZone(z).removeAllCards(true);
        for(var v:placements(own,control)){Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(v.name())),p);c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(v.zone()).add(c);c.setSickness(false);}
        Map<String,Integer> actual=new TreeMap<>(),registered=new TreeMap<>();for(ZoneType z:List.of(ZoneType.Hand,ZoneType.Battlefield,ZoneType.Graveyard,ZoneType.Library))for(Card c:p.getCardsIn(z))actual.merge(c.getName(),1,Integer::sum);
        for(var e:p.getRegisteredPlayer().getDeck().getMain())registered.merge(e.getKey().getName(),e.getValue(),Integer::sum);if(!actual.equals(registered))throw new AssertionError("registered identity");
    }
    private static Object snapshot(Player p)throws Exception{var m=CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot",Player.class);m.setAccessible(true);return m.invoke(null,p);}
    private static void run(boolean improved,int seat,String control)throws Exception{
        List<RegisteredPlayer> entries=new ArrayList<>();for(int s=0;s<2;s++){var lobby=s==seat&&improved?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);lobby.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);Game g=new Match(rules,entries,"Probe native boundary").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);populate(p,true,control);populate(op,false,control);
        int life=switch(control){case "life-one"->1;case "life-two","blue-at-two"->2;case "life-three"->3;case "life-four"->4;default->20;};p.setLife(life,null);op.setLife(20,null);g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(993600L+100L*seat+CASES.indexOf(control));
        Card probe=p.getCardsIn(control.equals("grave-no-permission")?ZoneType.Graveyard:ZoneType.Hand).stream().filter(c->c.getName().equals("Gitaxian Probe")).findFirst().orElseThrow();
        var plan=new forge.ai.CubeStormPlan(p);plan.nextAction();
        var helper=plan.getClass().getDeclaredMethod("spell",Card.class);helper.setAccessible(true);SpellAbility chosen=(SpellAbility)helper.invoke(plan,probe);
        if(chosen==null)throw new AssertionError("missing positive Probe");chosen.resetTargets();chosen.getTargets().add(p);
        var select=plan.getClass().getDeclaredMethod("select",SpellAbility.class);select.setAccessible(true);select.invoke(plan,chosen);
        switch(control){
            case "copy-action"->chosen=chosen.copy(p);
            case "foreign-target"->{chosen.resetTargets();chosen.getTargets().add(op);}
            case "foreign-actor"->chosen.setActivatingPlayer(op);
            case "copied-flag"->chosen.setCopied(true);
            case "changed-cost"->chosen.setPayCosts(chosen.getPayCosts().copy());
            case "changed-param"->chosen.getMapParams().put("Look","False");
            case "changed-draw"->chosen.getSubAbility().getMapParams().put("NumCards","2");
            case "appended-draw"->chosen.getSubAbility().setSubAbility((forge.game.spellability.AbilitySub)forge.game.ability.AbilityFactory.getAbility("DB$ Draw",probe));
            case "blink-host"->{g.getAction().exile(probe,null,forge.game.ability.AbilityKey.newMap());g.getAction().moveTo(ZoneType.Hand,g.getCardState(probe,null),null,forge.game.ability.AbilityKey.newMap());g.getAction().checkStateEffects(true);}
            case "other-turn"->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,op,start+1);
            case "combat-phase"->g.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS,p,start);
            case "replay-action"->{if(!plan.play(chosen))throw new AssertionError("first native payment failed");}
            default->{}
        }
        Object before=snapshot(p);boolean played=plan.play(chosen);boolean expected=control.equals("actual-action");
        if(played!=expected)throw new AssertionError("Probe ownership boundary accepted invalid action case="+control+" played="+played);
        if(!expected&&!before.equals(snapshot(p)))throw new AssertionError("rejected action mutated native/RNG");
        if(expected&&(p.getLife()!=18||chosen.getTargets().size()!=1||chosen.getTargets().get(0)!=p))throw new AssertionError("positive native own-target payment missing");
        System.out.println("STORM_PROBE_OWNERSHIP case="+control+" seat="+seat+" played="+played+" rejectedWithoutNativeMutation="+!expected);

    }
    public static void main(String[]args){try{
        GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"storm-probe-boundary-v1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(String n:List.of("Gitaxian Probe","Swamp","Island","Forest","Angel of Jubilation"))StaticData.instance().attemptToLoadCard(n);
        for(String c:CASES)for(int seat=0;seat<2;seat++)run(true,seat,c);System.out.println("STORM_PROBE_OWNERSHIP_COMPLETE cases=26");
    }catch(Throwable e){e.printStackTrace();System.exit(1);}}
}
