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
public final class CubeStormProbeBoundarySmoke {
    private static final List<String> CASES=List.of("life-one","life-two","life-three","life-four","life-twenty","blue-at-two","no-pay-life","blue-no-pay-life","grave-no-permission");
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
        int life=switch(control){case "life-one"->1;case "life-two","blue-at-two"->2;case "life-three"->3;case "life-four"->4;default->20;};p.setLife(life,null);op.setLife(20,null);g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(993200L+100L*seat+CASES.indexOf(control));
        Card probe=p.getCardsIn(control.equals("grave-no-permission")?ZoneType.Graveyard:ZoneType.Hand).stream().filter(c->c.getName().equals("Gitaxian Probe")).findFirst().orElseThrow();
        var plan=new forge.ai.CubeStormPlan(p);var helper=plan.getClass().getDeclaredMethod("spell",Card.class);helper.setAccessible(true);Object before=snapshot(p);SpellAbility chosen=null;Boolean first=null;
        for(int i=0;i<30;i++){
            chosen=(SpellAbility)helper.invoke(plan,probe);boolean available=chosen!=null;if(first!=null&&first!=available)throw new AssertionError("query drift");first=available;
            if(!before.equals(snapshot(p)))throw new AssertionError("query native/RNG mutation");
            if(!"Never".equals(probe.getSpellAbilities().get(0).getParam("AIPhyrexianPayment")))throw new AssertionError("printed instruction mutated");
        }
        boolean expected=control.startsWith("blue-")||improved&&Set.of("life-three","life-four","life-twenty").contains(control);
        if((chosen!=null)!=expected)throw new AssertionError("payment feasibility case="+control+" improved="+improved+" offered="+(chosen!=null));
        int paidLife=0,mana=0,drawn=0;
        if(chosen!=null){chosen.resetTargets();chosen.getTargets().add(p);int library=p.getCardsIn(ZoneType.Library).size();boolean paid=forge.ai.ComputerUtil.handlePlayingSpellAbility(p,chosen,null,a->new forge.ai.AiCostDecision(p,a,false));if(!paid)throw new AssertionError("native payment failed");paidLife=life-p.getLife();mana=chosen.getPayingMana().size();if(chosen.getSpendPhyrexianMana()!=paidLife||paidLife!=(control.startsWith("blue-")?0:2))throw new AssertionError("life payment mismatch");if(chosen.getTargets().size()!=1||chosen.getTargets().get(0)!=p)throw new AssertionError("foreign hand target");g.getStack().resolveStack();drawn=library-p.getCardsIn(ZoneType.Library).size();if(drawn!=1)throw new AssertionError("native draw missing");}
        System.out.println("STORM_PROBE_BOUNDARY case="+control+" seat="+seat+" improved="+improved+" offered="+expected+" repeatedQueries=30 unchanged=true paidLife="+paidLife+" paidMana="+mana+" drawn="+drawn);
    }
    public static void main(String[]args){try{
        GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"storm-probe-boundary-v1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(String n:List.of("Gitaxian Probe","Swamp","Island","Forest","Angel of Jubilation"))StaticData.instance().attemptToLoadCard(n);
        for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1].equals("improved"),seat,c);System.out.println("STORM_PROBE_BOUNDARY_COMPLETE cases=18");
    }catch(Throwable e){e.printStackTrace();System.exit(1);}}
}
