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

/** Prepared observation only. Native controllers choose every action. The
 * three-card catalogue entries are resource engines; Reservoir is an explicit
 * additional payoff, not evidence that those entries kill by themselves. */
public final class CubeFastbondAdverseSmoke {
    private static final List<String> CASES=List.of("crucible","multiple-lands","grave-start","grave-low-life-used","sulfuric-vortex","rest-in-peace","stony-silence","platinum-angel","no-orb","no-reservoir","no-permission");
    private static final List<ZoneType> ZONES=List.of(ZoneType.Battlefield,ZoneType.Hand,ZoneType.Library,ZoneType.Graveyard,ZoneType.Exile);
    private record Placement(String name,ZoneType zone) {}
    private static List<Placement> placements(boolean own,String control) {
        List<Placement> out=new ArrayList<>();
        if(own) {
            for(String name:List.of("Zuran Orb","Aetherflux Reservoir","Island")) {
                if(name.equals("Zuran Orb")&&control.equals("no-orb")||name.equals("Aetherflux Reservoir")&&control.equals("no-reservoir"))continue;
                out.add(new Placement(name,name.equals("Island")&&control.startsWith("grave-")?ZoneType.Graveyard:ZoneType.Battlefield));
            }
            if(control.equals("multiple-lands"))out.add(new Placement("Mountain",ZoneType.Battlefield));
            if(!control.equals("no-fastbond"))out.add(new Placement("Fastbond",ZoneType.Battlefield));
            if(!control.equals("no-permission"))out.add(new Placement(control.equals("ramunap")?"Ramunap Excavator":"Crucible of Worlds",ZoneType.Battlefield));
        }
        if(!own) {
            String hate=switch(control){case "sulfuric-vortex"->"Sulfuric Vortex";case "rest-in-peace"->"Rest in Peace";case "stony-silence"->"Stony Silence";case "platinum-angel"->"Platinum Angel";default->null;};
            if(hate!=null)out.add(new Placement(hate,ZoneType.Battlefield));
        }
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
            if(p.getController() instanceof forge.ai.CubeComboPlayerController)try {
                var f=forge.ai.CubeComboPlayerController.class.getDeclaredField("fastbondPlan");f.setAccessible(true);Object plan=f.get(p.getController());
                var method=plan.getClass().getDeclaredMethod("nextAction");method.setAccessible(true);var a=(forge.game.spellability.SpellAbility)method.invoke(plan);
                rows.add("plan:"+(a==null?"none":a.getHostCard().getId()+":"+a.getApi()+":"+a.isLandAbility()));
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            String value=rows.toString();if(first==null)first=value;else if(!first.equals(value))throw new AssertionError("availability drift");
            if(!before.equals(snapshot(p)))throw new AssertionError("availability native/RNG mutation");
        }
        System.out.println("FASTBOND_QUERY "+key+" repeats=3 unchanged=true available="+first.replace(" ",""));
    }
    private static void run(String arm,boolean observed,int seat,String control) {
        List<RegisteredPlayer> entries=new ArrayList<>();
        for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat&&arm.equals("improved")?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);if(lobby instanceof forge.ai.LobbyPlayerAi ai)ai.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game g=new Match(rules,entries,"Fastbond Orb observation").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        p.setLife(control.equals("grave-low-life-used")?1:20,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);
        g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        if(control.equals("grave-low-life-used"))p.setLandsPlayedThisTurn(1);
        BenchRandomAudit.install(992000L+100L*seat+CASES.indexOf(control));String key="arm="+arm+" seat="+seat+" case="+control;
        Card land=java.util.stream.Stream.concat(p.getCardsIn(ZoneType.Battlefield).stream(),p.getCardsIn(ZoneType.Graveyard).stream()).filter(c->c.getName().equals("Island")).findFirst().orElseThrow();
        NativeEvents events=new NativeEvents(p,land.getId(),key);g.subscribeToEvents(events);
        if(p.getManaPool().totalMana()!=0||p.getLandsPlayedThisTurn()!=(control.equals("grave-low-life-used")?1:0))throw new AssertionError("prepared initial resources");
        System.out.println("FASTBOND_FIXTURE "+key+" registered=40 initialLife="+p.getLife()+" initialMana=0 landsPlayed="+p.getLandsPlayedThisTurn()+" observed="+observed+" policy="+forge.ai.CubeComboAi.VERSION+" extraPayoff=Aetherflux_Reservoir");
        int steps=0;Set<Integer> seen=new HashSet<>();
        while(!g.isGameOver()&&g.getPhaseHandler().getTurn()<=start&&steps<2000) {
            if(observed)observe(p,key);g.getPhaseHandler().mainLoopStep();steps++;
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
        }
        boolean expected=arm.equals("improved")&&Set.of("crucible","multiple-lands","grave-start").contains(control);
        if(p.hasWon()!=expected)throw new AssertionError("adverse native win expectation "+key+" expected="+expected);
        if(arm.equals("improved")&&control.equals("grave-low-life-used")&&(p.getLife()!=1||events.landPlays!=0||g.isGameOver()))throw new AssertionError("lethal ordinary replay escaped converter guard");
        if(steps>=2000)throw new AssertionError("step cap");
        System.out.println("FASTBOND_RESULT "+key+" won="+p.hasWon()+" gameOver="+g.isGameOver()+" life="+p.getLife()+" steps="+steps+" landToGrave="+events.sacrifices+" graveReturns="+events.returns+" paidOrbActivations="+events.paidOrbActivations+" nativeLandPlays="+events.landPlays+" lifeGainEvents="+events.gainEvents+" lifeLossEvents="+events.lossEvents+" scriptActions=0");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"fastbond-adverse-v1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);
            for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],args[2].equals("observed"),seat,c);
            System.out.println("FASTBOND_SUITE_COMPLETE cases=22");
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
