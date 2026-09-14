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
public final class CubeKikiSyrCallbackSmoke {
    private static final String KIKI="Kiki-Jiki, Mirror Breaker", META="Phyrexian Metamorph", SYR="Syr Ginger, the Meal Ender", ANTHEM="Intrepid Adversary", SHOT="Aetherflux Reservoir";
    private static final class SpyLobby extends forge.ai.LobbyPlayerAi {
        private final String control;
        SpyLobby(String name,String control){super(name,null);this.control=control;setAiProfile("Default");}
        @Override public Player createIngamePlayer(Game g,int id){Player p=new Player(getName(),g,id);p.setFirstController(new SpyController(g,p,this,control));return p;}
    }
    private static final class SpyController extends forge.ai.CubeComboPlayerController {
        private final Player own;private final String control;boolean tested;
        SpyController(Game g,Player p,forge.LobbyPlayer lobby,String control){super(g,p,lobby);own=p;this.control=control;}
        private Object plan()throws Exception{var f=forge.ai.CubeComboPlayerController.class.getDeclaredField("kikiSyrPlan");f.setAccessible(true);return f.get(this);}
        private GameEntity probe(forge.util.collect.FCollectionView<?> options,forge.game.spellability.SpellAbility a)throws Exception{Object p=plan();var m=p.getClass().getDeclaredMethod("choose",forge.util.collect.FCollectionView.class,forge.game.spellability.SpellAbility.class);m.setAccessible(true);return (GameEntity)m.invoke(p,options,a);}
        private Object snapshot()throws Exception{var m=CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot",Player.class);m.setAccessible(true);return m.invoke(null,own);}
        private Card tracked(String field)throws Exception{Object p=plan();var f=p.getClass().getDeclaredField(field);f.setAccessible(true);return (Card)f.get(p);}
        private int token()throws Exception{Object p=plan();var f=p.getClass().getDeclaredField("newToken");f.setAccessible(true);return f.getInt(p);}
        @Override public <T extends GameEntity>T chooseSingleEntityForEffect(forge.util.collect.FCollectionView<T> options,DelayedReveal reveal,forge.game.spellability.SpellAbility a,String title,boolean optional,Player targeted,Map<String,Object> params){
            boolean clone=a.getApi()==forge.game.ability.ApiType.Clone;
            boolean legend=a.getApi()==forge.game.ability.ApiType.InternalLegendaryRule;
            if(!tested&&(control.startsWith("clone-")&&clone||control.startsWith("legend-")&&legend)){
                tested=true;
                try{
                    var candidate=a;var offered=new forge.util.collect.FCollection<T>();for(T e:options)offered.add(e);
                    Player actor=a.getActivatingPlayer();Card host=a.getHostCard();boolean copied=a.isCopied();var sub=a.getSubAbility();Map<String,String> original=new HashMap<>(a.getMapParams());
                    try{
                        if(control.endsWith("foreign-actor"))candidate.setActivatingPlayer(own.getOpponents().iterator().next());
                        if(control.endsWith("foreign-host"))candidate.setHostCard(own.getCardsIn(ZoneType.Battlefield).stream().filter(c->c.getName().equals("Island")).findFirst().orElseThrow());
                        if(control.endsWith("copied-flag"))candidate.setCopied(true);
                        if(control.equals("clone-copy"))candidate=a.copy(own);
                        if(control.equals("clone-param"))candidate.getMapParams().put("AddTypes","");
                        if(control.equals("clone-subability"))candidate.setSubAbility((forge.game.spellability.AbilitySub)forge.game.ability.AbilityFactory.getAbility("DB$ GainLife | LifeAmount$ 1",host));
                        if(control.equals("clone-missing-option")||control.equals("legend-missing-old"))offered.remove(tracked("kiki"));
                        if(control.equals("legend-missing-new")){int id=token();offered.removeIf(e->e.getId()==id);}
                        Object before=snapshot();GameEntity chosen=probe(offered,candidate);
                        if(control.endsWith("repeat")){
                            if(chosen==null)throw new AssertionError("first native choice missing "+control);
                            for(int i=0;i<20;i++)if(probe(offered,candidate)!=chosen||!before.equals(snapshot()))throw new AssertionError("repeat choice not idempotent "+control);
                        }else if(chosen!=null)throw new AssertionError("invalid native callback accepted "+control);
                        if(!before.equals(snapshot()))throw new AssertionError("callback probe changed native/RNG "+control);
                    }finally{a.setActivatingPlayer(actor);a.setHostCard(host);a.setCopied(copied);a.setSubAbility(sub);a.getMapParams().clear();a.getMapParams().putAll(original);}
                    System.out.println("KIKI_SYR_CALLBACK case="+control+" tested=true nativeUnchanged=true");
                }catch(Exception e){throw new RuntimeException(e);}
            }
            return super.chooseSingleEntityForEffect(options,reveal,a,title,optional,targeted,params);
        }
    }
    private static final List<String> CASES=List.of("clone-foreign-actor","clone-foreign-host","clone-copied-flag","clone-copy","clone-param","clone-subability","clone-missing-option","clone-repeat","legend-foreign-actor","legend-copied-flag","legend-foreign-host","legend-missing-old","legend-missing-new","legend-repeat");
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
        for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat?new SpyLobby("Candidate-"+s,control):new forge.ai.LobbyPlayerAi("Default-"+s,null);if(lobby instanceof forge.ai.LobbyPlayerAi ai)ai.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
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
        boolean expected=true;if(!((SpyController)p.getController()).tested)throw new AssertionError("callback control never reached");if(p.hasWon()!=expected)throw new AssertionError("scripted outcome case="+control+" expected="+expected);
        if(expected&& (copies!=28||events.counterIncreases!=27||p.getLife()!=1))throw new AssertionError("native conversion counts");
        System.out.println("KIKI_SYR_RESULT "+key+" won="+p.hasWon()+" gameOver="+g.isGameOver()+" life="+p.getLife()+" opponentLife="+op.getLife()+" steps="+steps+" copyAbilities="+copies+" artifactDeaths="+events.artifactDeaths+" counterIncreases="+events.counterIncreases+" lifeGainEvents="+events.lifeGainEvents+" scriptActions=0");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"kiki-syr-native-v99-1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);
            for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],args[2].equals("observed"),seat,c);
            System.out.println("KIKI_SYR_SUITE_COMPLETE cases=28");
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
