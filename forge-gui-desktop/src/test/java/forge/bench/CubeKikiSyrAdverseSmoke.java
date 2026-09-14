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

/** Prepared autonomous native observation; no assembly or sampled strength claim. */
public final class CubeKikiSyrAdverseSmoke {
    private static final String KIKI="Kiki-Jiki, Mirror Breaker", META="Phyrexian Metamorph", SYR="Syr Ginger, the Meal Ender", ANTHEM="Intrepid Adversary", SHOT="Aetherflux Reservoir";
    private static final List<String> CASES=List.of("complete","tainted-remedy","no-life-gain","grave-replacement","counter-prevention","null-rod","cursed-totem");
    private static final List<ZoneType> ZONES=List.of(ZoneType.Battlefield,ZoneType.Hand,ZoneType.Library,ZoneType.Graveyard,ZoneType.Exile);
    private record Placement(String name,ZoneType zone) {}
    private static List<Placement> placements(boolean own,String control) {
        List<Placement> out=new ArrayList<>();
        if(own) {
            for(String name:List.of(KIKI,META))out.add(new Placement(name,ZoneType.Battlefield));
            if(!control.equals("no-anthem"))out.add(new Placement(ANTHEM,ZoneType.Battlefield));
            if(!control.equals("no-syr"))out.add(new Placement(SYR,ZoneType.Battlefield));
            if(!control.equals("no-reservoir"))out.add(new Placement(SHOT,ZoneType.Battlefield));
            if(!control.equals("no-mana"))for(int i=0;i<2;i++)out.add(new Placement("Island",ZoneType.Battlefield));
        }
        if(!own){String hate=switch(control){case "tainted-remedy"->"Tainted Remedy";case "no-life-gain"->"Erebos, God of the Dead";case "grave-replacement"->"Rest in Peace";case "counter-prevention"->"Solemnity";case "null-rod"->"Null Rod";case "cursed-totem"->"Cursed Totem";default->null;};if(hate!=null)out.add(new Placement(hate,ZoneType.Battlefield));}
        while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
    }
    private static Deck deck(boolean own,String control) {
        Deck d=new Deck("Kiki Metamorph Syr prepared observation");for(var p:placements(own,control))d.getMain().add(p.name(),1);return d;
    }
    private static void populate(Player p,boolean own,String control) {
        for(ZoneType z:ZoneType.values())if(p.getZone(z)!=null)p.getZone(z).removeAllCards(true);
        for(var placement:placements(own,control)) {
            Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(placement.name())),p);
            c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(placement.zone()).add(c);c.setSickness(false);
            if(c.getName().equals(ANTHEM))c.setCounters(CounterEnumType.VALOR,1);
            if(c.getName().equals(SYR)&&control.equals("syr-tapped"))c.setTapped(true);
            if(c.getName().equals(SYR)&&control.equals("syr-sick"))c.setSickness(true);
        }
        Map<String,Integer> actual=new TreeMap<>(),registered=new TreeMap<>();
        for(ZoneType z:ZONES)for(Card c:p.getCardsIn(z))actual.merge(c.getPaperCard().getName(),1,Integer::sum);
        for(var e:p.getRegisteredPlayer().getDeck().getMain())registered.merge(e.getKey().getName(),e.getValue(),Integer::sum);
        if(!actual.equals(registered)||actual.values().stream().mapToInt(Integer::intValue).sum()!=40)throw new AssertionError("registered identity");
    }
    public static final class NativeEvents {
        final int playerId;final String key;
        int artifactDeaths,counterIncreases,lifeGainEvents;
        NativeEvents(Player p,String key){playerId=p.getId();this.key=key;}
        @Subscribe public void moved(GameEventCardChangeZone e) {
            if(e.card().getOwner()==null||e.card().getOwner().getId()!=playerId||e.from()==null||e.to()==null)return;
            if(e.from().zoneType()!=ZoneType.Battlefield&&e.to().zoneType()!=ZoneType.Battlefield)return;
            boolean artifact=e.card().getCurrentState().isArtifact();
            if(artifact&&e.from().zoneType()==ZoneType.Battlefield&&e.to().zoneType()==ZoneType.Graveyard)artifactDeaths++;
            System.out.println("KIKI_SYR_ZONE "+key+" card="+e.card().getName().replace(' ','_')+" id="+e.card().getId()+" artifact="+artifact+" token="+e.card().isToken()+" from="+e.from().zoneType()+" to="+e.to().zoneType());
        }
        @Subscribe public void counters(GameEventCardCounters e) {
            if(e.card().getOwner()==null||e.card().getOwner().getId()!=playerId||!e.card().getName().equals(SYR))return;
            if(e.newValue()>e.oldValue())counterIncreases+=e.newValue()-e.oldValue();
            System.out.println("KIKI_SYR_COUNTER "+key+" type="+e.type()+" before="+e.oldValue()+" after="+e.newValue());
        }
        @Subscribe public void life(GameEventPlayerLivesChanged e) {
            if(e.player().getId()!=playerId)return;if(e.newLives()>e.oldLives())lifeGainEvents++;
            System.out.println("KIKI_SYR_LIFE "+key+" before="+e.oldLives()+" after="+e.newLives());
        }
    }
    private static String board(Player p) {
        List<String> rows=new ArrayList<>();
        for(Card c:p.getCardsIn(ZoneType.Battlefield))if(c.isCreature())rows.add(c.getId()+":"+c.getName().replace(' ','_')+":artifact="+c.isArtifact()+":token="+c.isToken()+":tapped="+c.isTapped()+":pt="+c.getNetPower()+"/"+c.getNetToughness());
        return String.join(";",rows);
    }
    private static void run(String arm,boolean observed,int seat,String control) {
        List<RegisteredPlayer> entries=new ArrayList<>();
        for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat&&arm.equals("improved")?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);if(lobby instanceof forge.ai.LobbyPlayerAi ai)ai.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game g=new Match(rules,entries,"Kiki Metamorph Syr observation").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        p.setLife(20,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);
        g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));
        // First-turn setup executes untap before its hook. Apply the registered
        // readiness controls after that native startup, before any priority.
        for(Card c:p.getCardsIn(ZoneType.Battlefield))if(c.getName().equals(SYR)){
            c.setTapped(control.equals("syr-tapped"));c.setSickness(control.equals("syr-sick"));
            if(c.isTapped()!=control.equals("syr-tapped")||c.isAbilitySick()!=control.equals("syr-sick"))throw new AssertionError("Syr readiness premise");
        }
        String key="arm="+arm+" seat="+seat+" case="+control;NativeEvents events=new NativeEvents(p,key);g.subscribeToEvents(events);
        g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(991400L+100L*seat+CASES.indexOf(control));
        boolean metaPresent=p.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals(META));
        if(metaPresent==control.equals("no-anthem"))throw new AssertionError("un-copied Metamorph anthem survival");
        if(p.getManaPool().totalMana()!=0)throw new AssertionError("prepared initial mana");
        System.out.println("KIKI_SYR_FIXTURE "+key+" registered=40 initialLife=20 initialMana=0 observed="+observed+" policy="+forge.ai.CubeComboAi.VERSION+" syrTapped="+control.equals("syr-tapped")+" syrSick="+control.equals("syr-sick")+" preparedValor="+(control.equals("no-anthem")?0:1)+" extraPayoff=Syr_Ginger+Aetherflux_Reservoir");
        int steps=0,copies=0;Set<Integer> seen=new HashSet<>();String previous="";
        while(!g.isGameOver()&&g.getPhaseHandler().getTurn()<=start&&steps<2000) {
            if(observed){String current=board(p);if(!current.equals(previous))System.out.println("KIKI_SYR_BOARD "+key+" step="+steps+" cards=["+current+"]");previous=current;}
            g.getPhaseHandler().mainLoopStep();steps++;
            for(var item:g.getStack())if(seen.add(item.getId())) {
                var a=item.getSpellAbility();if(a.getActivatingPlayer()!=p)continue;
                if(a.getHostCard().getName().equals(KIKI)&&a.getApi()==forge.game.ability.ApiType.CopyPermanent)copies++;
                System.out.println("KIKI_SYR_STACK "+key+" step="+steps+" source="+a.getHostCard().getName().replace(' ','_')+" api="+a.getApi()+" sourceId="+a.getHostCard().getId()+" targets="+a.getTargets().getTargetCards().stream().map(c->c.getId()+":"+c.getName().replace(' ','_')).toList());
                if(a.getHostCard().getName().equals(SYR)&&a.isActivatedAbility())for(Card c:a.getPaidList("Sacrificed"))System.out.println("KIKI_SYR_SACRIFICE "+key+" id="+c.getId()+" name="+c.getName().replace(' ','_')+" lkiPower="+c.getNetPower());
            }
        }
        if(steps>=2000)throw new AssertionError("step cap");
        boolean expected=arm.equals("improved")&&control.equals("complete");if(p.hasWon()!=expected)throw new AssertionError("scripted outcome case="+control+" expected="+expected);
        if(expected&& (copies!=28||events.counterIncreases!=27||p.getLife()!=1))throw new AssertionError("native conversion counts");
        System.out.println("KIKI_SYR_RESULT "+key+" won="+p.hasWon()+" gameOver="+g.isGameOver()+" life="+p.getLife()+" opponentLife="+op.getLife()+" steps="+steps+" copyAbilities="+copies+" artifactDeaths="+events.artifactDeaths+" counterIncreases="+events.counterIncreases+" lifeGainEvents="+events.lifeGainEvents+" scriptActions=0");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"kiki-syr-native-v99-1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);
            for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],args[2].equals("observed"),seat,c);
            System.out.println("KIKI_SYR_SUITE_COMPLETE cases=14");
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
