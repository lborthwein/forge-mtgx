package forge.bench;

import com.google.common.eventbus.Subscribe;
import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.*;
import forge.game.event.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.lang.reflect.Proxy;
import java.util.*;

/** Registered disruption diagnostic: candidate chooses every combo action;
 * a scripted opponent pays/resolves native Disallow with native priority.
 * Removal cases explicitly inject a native move-to-exile after the first Orb
 * payment. These interventions are not natural opponent-policy evidence. */
public final class CubeFastbondInterruptionSmoke {
    private static Object value(Player p,String name) {
        try { var f=forge.ai.CubeComboPlayerController.class.getDeclaredField("fastbondPlan");f.setAccessible(true);Object plan=f.get(p.getController());var v=plan.getClass().getDeclaredField(name);v.setAccessible(true);return v.get(plan); }
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static final class CounterLobby extends forge.ai.LobbyPlayerAi {
        CounterLobby(int seat){super("Counter-"+seat,null);setAiProfile("Default");}
        @Override public Player createIngamePlayer(Game g,int id){Player p=new Player(getName(),g,id);p.setFirstController(new CounterController(g,p,this));return p;}
    }
    private static final class CounterController extends forge.ai.PlayerControllerAi {
        String control,key; boolean attempted,resolved; int actionsAtCounter;
        forge.game.spellability.SpellAbility choice,target;
        CounterController(Game g,Player p,forge.LobbyPlayer l){super(g,p,l);}
        @Override public List<forge.game.spellability.SpellAbility> chooseSpellAbilityToPlay() {
            if(attempted||getGame().getStack().isEmpty())return null;
            var top=getGame().getStack().peekAbility();
            String name=control.equals("counter-orb")?"Zuran Orb":control.equals("counter-shot")?"Aetherflux Reservoir":"";
            if(top.isCopied()||!top.isActivatedAbility()||!top.getHostCard().getName().equals(name)||top.getActivatingPlayer()==getPlayer())return null;
            for(Card c:getPlayer().getCardsIn(ZoneType.Hand))if(c.getName().equals("Disallow")) {
                var a=c.getSpellAbilities().get(0).copy(getPlayer());if(!a.canTarget(top))continue;a.getTargets().add(top);
                if(forge.ai.CubeComboAi.canPlayNative(a,getPlayer())&&forge.ai.CubeComboAi.canPayCost(a,getPlayer(),false)){choice=a;target=top;return List.of(a);}
            }
            return null;
        }
        @Override public boolean playChosenSpellAbility(forge.game.spellability.SpellAbility a) {
            if(a!=choice)throw new AssertionError("foreign counter choice");
            if(getGame().getPhaseHandler().getPriorityPlayer()!=getPlayer()||value(target.getActivatingPlayer(),"pending")!=target)throw new AssertionError("not actual native owned counter window");
            actionsAtCounter=(Integer)value(target.getActivatingPlayer(),"actions");
            boolean paid=forge.ai.ComputerUtil.handlePlayingSpellAbility(getPlayer(),a,null,current->new forge.ai.AiCostDecision(getPlayer(),current,false));
            if(!paid)throw new AssertionError("counter payment failed");attempted=true;
            System.out.println("FASTBOND_COUNTER_PLAY "+key+" nativePriority=true nativePaid=true target="+target.getApi()+" planActions="+actionsAtCounter);return true;
        }
        void report(){
            if(!attempted||resolved||!getGame().getStack().isEmpty())return;
            if(!getGame().getCardState(choice.getHostCard()).isInZone(ZoneType.Graveyard))throw new AssertionError("counter failed native resolve");
            resolved=true;System.out.println("FASTBOND_COUNTER_RESOLVED "+key+" counterZone=Graveyard stackEmpty=true");
        }
    }
    private static final List<String> CASES=List.of("complete","counter-orb","counter-shot","remove-orb","remove-permission","remove-outlet","exile-land");
    private static final List<ZoneType> ZONES=List.of(ZoneType.Battlefield,ZoneType.Hand,ZoneType.Library,ZoneType.Graveyard,ZoneType.Exile);
    private record Placement(String name,ZoneType zone) {}
    private static List<Placement> placements(boolean own,String control) {
        List<Placement> out=new ArrayList<>();
        if(own) {
            for(String name:List.of("Zuran Orb","Aetherflux Reservoir","Island"))out.add(new Placement(name,ZoneType.Battlefield));
            if(!control.equals("no-fastbond"))out.add(new Placement("Fastbond",ZoneType.Battlefield));
            if(!control.equals("no-permission"))out.add(new Placement(control.equals("ramunap")?"Ramunap Excavator":"Crucible of Worlds",ZoneType.Battlefield));
        }
        if(!own&&control.startsWith("counter-")) { for(int i=0;i<3;i++)out.add(new Placement("Island",ZoneType.Battlefield));out.add(new Placement("Disallow",ZoneType.Hand)); }
        while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
    }
    private static Deck deck(boolean own,String control) {
        Deck d=new Deck("Fastbond Orb native observation");for(var p:placements(own,control))d.getMain().add(p.name(),1);return d;
    }
    private static void populate(Player p,boolean own,String control) {
        for(ZoneType z:ZoneType.values())if(p.getZone(z)!=null)p.getZone(z).removeAllCards(true);
        for(var placement:placements(own,control)) {
            Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(placement.name())),p);
            c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(placement.zone()).add(c);c.setSickness(false);
        }
        Map<String,Integer> actual=new TreeMap<>(),registered=new TreeMap<>();
        for(ZoneType z:ZONES)for(Card c:p.getCardsIn(z))actual.merge(c.getPaperCard().getName(),1,Integer::sum);
        for(var e:p.getRegisteredPlayer().getDeck().getMain())registered.merge(e.getKey().getName(),e.getValue(),Integer::sum);
        if(!actual.equals(registered)||actual.values().stream().mapToInt(Integer::intValue).sum()!=40)throw new AssertionError("registered identity");
    }
    public static final class NativeEvents {
        final int playerId,landId;final String key;
        int sacrifices,returns,landPlays,gainEvents,lossEvents,paidOrbActivations;
        NativeEvents(Player p,int land,String key){playerId=p.getId();landId=land;this.key=key;}
        @Subscribe public void moved(GameEventCardChangeZone e) {
            if(e.card().getId()!=landId||e.from()==null||e.to()==null)return;
            if(e.from().zoneType()==ZoneType.Battlefield&&e.to().zoneType()==ZoneType.Graveyard)sacrifices++;
            if(e.from().zoneType()==ZoneType.Graveyard&&e.to().zoneType()==ZoneType.Battlefield)returns++;
            System.out.println("FASTBOND_ZONE "+key+" from="+e.from().zoneType()+" to="+e.to().zoneType());
        }
        @Subscribe public void land(GameEventLandPlayed e) {
            if(e.player().getId()!=playerId||e.land().getId()!=landId)return;landPlays++;
            System.out.println("FASTBOND_LAND_PLAY "+key+" count="+landPlays);
        }
        @Subscribe public void life(GameEventPlayerLivesChanged e) {
            if(e.player().getId()!=playerId)return;
            if(e.newLives()>e.oldLives())gainEvents++;if(e.newLives()<e.oldLives())lossEvents++;
            System.out.println("FASTBOND_LIFE "+key+" before="+e.oldLives()+" after="+e.newLives());
        }
    }
    private static Object snapshot(Player p) {
        try{var m=CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot",Player.class);m.setAccessible(true);return m.invoke(null,p);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void observe(Player p,String key) {
        if(!p.getGame().getStack().isEmpty()||!p.getGame().getPhaseHandler().is(PhaseType.MAIN1,p))return;
        Object before=snapshot(p);String first=null;
        for(int i=0;i<3;i++) {
            List<String> rows=new ArrayList<>();
            for(Card c:p.getCardsIn(ZoneType.Battlefield))if(Set.of("Zuran Orb","Aetherflux Reservoir").contains(c.getName()))for(var original:c.getSpellAbilities())if(original.isActivatedAbility()) {
                var a=original.copy(p);rows.add(c.getName().replace(' ','_')+":"+a.getApi()+":"+forge.ai.CubeComboAi.canPlayNative(a,p)+":"+forge.ai.CubeComboAi.canPayCost(a,p,false));
            }
            String value=rows.toString();if(first==null)first=value;else if(!first.equals(value))throw new AssertionError("availability drift");
            if(!before.equals(snapshot(p)))throw new AssertionError("availability native/RNG mutation");
        }
        System.out.println("FASTBOND_QUERY "+key+" repeats=3 unchanged=true available="+first.replace(" ",""));
    }
    private static void run(String arm,boolean observed,int seat,String control) {
        List<RegisteredPlayer> entries=new ArrayList<>();
        for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new CounterLobby(s);if(lobby instanceof forge.ai.LobbyPlayerAi ai)ai.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game g=new Match(rules,entries,"Fastbond Orb observation").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        p.setLife(control.equals("low-life")?1:20,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);
        g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(992400L+100L*seat+CASES.indexOf(control));String key="arm="+arm+" seat="+seat+" case="+control;
        CounterController counter=(CounterController)op.getController();counter.control=control;counter.key=key;
        Card land=p.getCardsIn(ZoneType.Battlefield).stream().filter(c->c.getName().equals("Island")).findFirst().orElseThrow();
        NativeEvents events=new NativeEvents(p,land.getId(),key);g.subscribeToEvents(events);
        if(p.getManaPool().totalMana()!=0||p.getLandsPlayedThisTurn()!=0)throw new AssertionError("prepared initial resources");
        System.out.println("FASTBOND_FIXTURE "+key+" registered=40 initialLife="+p.getLife()+" initialMana=0 landsPlayed=0 observed="+observed+" policy="+forge.ai.CubeComboAi.VERSION+" extraPayoff=Aetherflux_Reservoir");
        boolean intervened=false; int actionsAtIntervention=-1;
        int steps=0;Set<Integer> seen=new HashSet<>();
        while(!g.isGameOver()&&g.getPhaseHandler().getTurn()<=start&&steps<2000) {
            if(observed)observe(p,key);g.getPhaseHandler().mainLoopStep();steps++;
            counter.report();
            for(var item:g.getStack())if(seen.add(item.getId())) {
                var a=item.getSpellAbility();if(a.getActivatingPlayer()!=p)continue;
                System.out.println("FASTBOND_STACK "+key+" source="+a.getHostCard().getName().replace(' ','_')+" api="+a.getApi()+" spell="+a.isSpell()+" copied="+a.isCopied());
                if(a.getHostCard().getName().equals("Zuran Orb")&&a.isActivatedAbility()&&!a.isCopied()) {
                    int paid=0,tracked=0;for(Card c:a.getPaidList("Sacrificed")){paid++;if(c.getId()==land.getId())tracked++;}
                    if(paid!=1||tracked!=1)throw new AssertionError("Orb native sacrifice receipt does not identify the registered land");
                    events.paidOrbActivations++;
                    System.out.println("FASTBOND_ORB_PAYMENT "+key+" sacrificed="+paid+" trackedLand="+tracked+" count="+events.paidOrbActivations);
                }
            }
            if(!intervened && (control.startsWith("remove-")||control.equals("exile-land")) && events.paidOrbActivations==1) {
                String name=switch(control){case "remove-orb"->"Zuran Orb";case "remove-permission"->"Crucible of Worlds";case "remove-outlet"->"Aetherflux Reservoir";default->"Island";};
                ZoneType zone=control.equals("exile-land")?ZoneType.Graveyard:ZoneType.Battlefield;
                Card c=p.getCardsIn(zone).stream().filter(x->x.getName().equals(name)).findFirst().orElseThrow();
                actionsAtIntervention=(Integer)value(p,"actions");g.getAction().exile(c,null,forge.game.ability.AbilityKey.newMap());g.getAction().checkStateEffects(true);intervened=true;
                System.out.println("FASTBOND_INTERVENTION "+key+" nativeMove=true destination=Exile source="+name.replace(' ','_')+" planActions="+actionsAtIntervention);
            }
        }
        if(p.hasWon()!=control.equals("complete"))throw new AssertionError("unexpected interruption outcome "+key);
        if(control.startsWith("counter-")&&(!counter.attempted||!counter.resolved||(Integer)value(p,"actions")!=counter.actionsAtCounter))throw new AssertionError("missing counter or continued failed plan "+key);
        if(control.startsWith("remove-")||control.equals("exile-land"))if(!intervened||(Integer)value(p,"actions")!=actionsAtIntervention)throw new AssertionError("missing intervention or continued plan "+key);
        if(steps>=2000)throw new AssertionError("step cap");
        System.out.println("FASTBOND_RESULT "+key+" won="+p.hasWon()+" gameOver="+g.isGameOver()+" life="+p.getLife()+" steps="+steps+" landToGrave="+events.sacrifices+" graveReturns="+events.returns+" paidOrbActivations="+events.paidOrbActivations+" nativeLandPlays="+events.landPlays+" lifeGainEvents="+events.gainEvents+" lifeLossEvents="+events.lossEvents+" scriptActions=0");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"fastbond-interruption-v1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);
            for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],args[2].equals("observed"),seat,c);
            System.out.println("FASTBOND_SUITE_COMPLETE cases=14");
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
