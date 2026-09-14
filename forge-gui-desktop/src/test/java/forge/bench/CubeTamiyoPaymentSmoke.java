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
import java.lang.reflect.Field;
import forge.game.spellability.SpellAbility;
import java.util.*;

/** Prepared observation only. Native controllers choose every action. The
 * three-card catalogue entries are resource engines; Reservoir is an explicit
 * additional payoff, not evidence that those entries kill by themselves. */
public final class CubeTamiyoPaymentSmoke {
    private static final String TAMIYO="Tamiyo, Collector of Tales", KITTEN="Displacer Kitten";
    private static final List<String> ARTIFACTS=List.of("Lotus Petal","Lion's Eye Diamond");
    private static final List<String> CONTROLS=List.of("complete","bf-start","no-tamiyo","no-kitten","no-outlet","held-card");
    private static final List<String> CASES=ARTIFACTS.stream().flatMap(a->CONTROLS.stream().map(c->a+":"+c)).toList();
    private static final List<ZoneType> ZONES=List.of(ZoneType.Battlefield,ZoneType.Hand,ZoneType.Library,ZoneType.Graveyard,ZoneType.Exile);
    private record Placement(String name,ZoneType zone) {}
    private static List<Placement> placements(boolean own,String key) {
        String[] fields=key.split(":");String artifact=fields[0],control=fields[1];
        List<Placement> out=new ArrayList<>();
        if(own) {
            if(!control.equals("no-tamiyo"))out.add(new Placement(TAMIYO,ZoneType.Battlefield));
            if(!control.equals("no-kitten"))out.add(new Placement(KITTEN,ZoneType.Battlefield));
            if(!control.equals("no-outlet"))out.add(new Placement("Aetherflux Reservoir",ZoneType.Battlefield));
            out.add(new Placement(artifact,control.equals("bf-start")?ZoneType.Battlefield:ZoneType.Graveyard));
            if(control.equals("held-card"))out.add(new Placement("Counterspell",ZoneType.Hand));
        }
        while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
    }
    private static Deck deck(boolean own,String control) {
        Deck d=new Deck("Tamiyo Kitten native observation");for(var p:placements(own,control))d.getMain().add(p.name(),1);return d;
    }
    private static void populate(Player p,boolean own,String control) {
        for(ZoneType z:ZoneType.values())if(p.getZone(z)!=null)p.getZone(z).removeAllCards(true);
        for(var placement:placements(own,control)) {
            Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(placement.name())),p);
            c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(placement.zone()).add(c);c.setSickness(false);if(c.getName().equals(TAMIYO))c.setCounters(CounterEnumType.LOYALTY,5);
        }
        Map<String,Integer> actual=new TreeMap<>(),registered=new TreeMap<>();
        for(ZoneType z:ZONES)for(Card c:p.getCardsIn(z))actual.merge(c.getPaperCard().getName(),1,Integer::sum);
        for(var e:p.getRegisteredPlayer().getDeck().getMain())registered.merge(e.getKey().getName(),e.getValue(),Integer::sum);
        if(!actual.equals(registered)||actual.values().stream().mapToInt(Integer::intValue).sum()!=40)throw new AssertionError("registered identity");
    }
    public static final class NativeEvents {
        final int playerId;final String key;
        int sacrifices,returns,blinks,discards,gainEvents,lossEvents;
        NativeEvents(Player p,String key){playerId=p.getId();this.key=key;}
        @Subscribe public void moved(GameEventCardChangeZone e) {
            if(e.card().getOwner().getId()!=playerId||e.from()==null||e.to()==null)return;
            String name=e.card().getName();
            if(ARTIFACTS.contains(name)) {
                if(e.from().zoneType()==ZoneType.Battlefield&&e.to().zoneType()==ZoneType.Graveyard)sacrifices++;
                if(e.from().zoneType()==ZoneType.Graveyard&&e.to().zoneType()==ZoneType.Hand)returns++;
            }
            if(name.equals(TAMIYO)&&e.from().zoneType()==ZoneType.Exile&&e.to().zoneType()==ZoneType.Battlefield)blinks++;
            if(e.from().zoneType()==ZoneType.Hand&&e.to().zoneType()==ZoneType.Graveyard)discards++;
            System.out.println("TAMIYO_ZONE "+key+" card="+name.replace(' ','_')+" from="+e.from().zoneType()+" to="+e.to().zoneType());
        }
        @Subscribe public void life(GameEventPlayerLivesChanged e) {
            if(e.player().getId()!=playerId)return;
            if(e.newLives()>e.oldLives())gainEvents++;if(e.newLives()<e.oldLives())lossEvents++;
            System.out.println("TAMIYO_LIFE "+key+" before="+e.oldLives()+" after="+e.newLives());
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
            for(ZoneType zone:List.of(ZoneType.Battlefield,ZoneType.Graveyard,ZoneType.Hand))for(Card c:p.getCardsIn(zone))
                if(ARTIFACTS.contains(c.getName())||Set.of(TAMIYO,KITTEN,"Aetherflux Reservoir").contains(c.getName()))
                    for(var original:c.getAllPossibleAbilities(p,false,null,true)) {
                        var a=original.copy(p);List<String> targets=new ArrayList<>();
                        if(a.usesTargeting())for(ZoneType targetZone:List.of(ZoneType.Graveyard,ZoneType.Battlefield))for(Card target:p.getCardsIn(targetZone))
                            if(a.canTarget(target))targets.add(target.getName().replace(' ','_')+"@"+targetZone);
                        rows.add(c.getName().replace(' ','_')+"@"+zone+":"+a.getApi()+":legal="+forge.ai.CubeComboAi.canPlayNative(a,p)
                            +":pay="+(a.getPayCosts()!=null&&forge.ai.CubeComboAi.canPayCost(a,p,false))+":targets="+targets+":loyalty="+c.getCounters(CounterEnumType.LOYALTY));
                    }
            String value=rows.toString();if(first==null)first=value;else if(!first.equals(value))throw new AssertionError("availability drift");
            if(!before.equals(snapshot(p)))throw new AssertionError("availability native/RNG mutation");
        }
        System.out.println("TAMIYO_QUERY "+key+" repeats=3 unchanged=true available="+first.replace(" ",""));
    }
    /** Read the submitted native ability's payment records, not zone-event counts. */
    private static final class PaidReceipts {
        final Player player;final String key;final Object plan;
        int returns,casts,sacrifices,discarded;
        final Set<SpellAbility> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        private record Before(int actions,int mana,int loyalty,int life,Set<Integer> hand) {}
        PaidReceipts(Player p,String key){player=p;this.key=key;Object found=null;
            if(p.getController() instanceof forge.ai.CubeComboPlayerController)try {
                Field f=forge.ai.CubeComboPlayerController.class.getDeclaredField("tamiyoPlan");f.setAccessible(true);found=f.get(p.getController());
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            plan=found;
        }
        private Object field(String name){try{Field f=plan.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(plan);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
        private int loyalty(){for(Card c:player.getCardsIn(ZoneType.Battlefield))if(c.getName().equals(TAMIYO))return c.getCounters(CounterEnumType.LOYALTY);return -1;}
        Before before(){
            Set<Integer> hand=new HashSet<>();for(Card c:player.getCardsIn(ZoneType.Hand))hand.add(c.getId());
            return new Before(plan==null?0:(int)field("actions"),player.getManaPool().totalMana(),loyalty(),player.getLife(),hand);
        }
        void after(Before before){
            if(plan==null)return;int actions=(int)field("actions");if(actions==before.actions())return;
            if(actions!=before.actions()+1)throw new AssertionError("unobserved native plan action "+key+" before="+before.actions()+" after="+actions);
            SpellAbility a=(SpellAbility)field("played");String kind=String.valueOf(field("kind"));
            if(a==null||!seen.add(a)||a.getActivatingPlayer()!=player||a.isCopied())throw new AssertionError("submitted ability identity "+key);
            Object nativeBefore=snapshot(player);
            if(kind.equals("RETURN")){
                if(!"3".equals(a.getSVar("CostCountersRemoved"))||loyalty()!=before.loyalty()-3)throw new AssertionError("native loyalty payment "+key+" receipt="+a.getSVar("CostCountersRemoved"));
                returns++;
            }else if(kind.equals("SACRIFICE")){
                List<Card> paid=new ArrayList<>();a.getPaidList("Sacrificed").forEach(paid::add);
                if(paid.size()!=1||paid.get(0).getId()!=a.getHostCard().getId())throw new AssertionError("native artifact sacrifice receipt "+key);
                Set<Integer> handPaid=new HashSet<>();a.getPaidList("Discarded").forEach(c->handPaid.add(c.getId()));
                boolean led=a.getHostCard().getName().equals("Lion's Eye Diamond");
                if(player.getManaPool().totalMana()!=before.mana()+(led?3:1))throw new AssertionError("native sacrifice mana "+key);
                if(led&&(!handPaid.equals(before.hand())||!player.getCardsIn(ZoneType.Hand).isEmpty()))throw new AssertionError("native LED whole-hand discard receipt "+key);
                if(!led&&(!handPaid.isEmpty()||player.getCardsIn(ZoneType.Hand).size()!=before.hand().size()))throw new AssertionError("Petal changed hand "+key);
                discarded+=handPaid.size();sacrifices++;
            }else if(kind.equals("CAST")){
                if(!a.isSpell()||a.getPayCosts()==null||!a.getPayCosts().isFree()||player.getManaPool().totalMana()!=before.mana())throw new AssertionError("native free artifact cast "+key);
                boolean pending=false;for(var item:player.getGame().getStack()){var nativeAbility=item.getSpellAbility();if(nativeAbility==a||nativeAbility.getOriginalAbility()==a)pending=true;}
                if(!pending)throw new AssertionError("native submitted cast absent "+key);casts++;
            }else throw new AssertionError("unexpected plan kind "+kind);
            if(player.getLife()!=before.life()||!nativeBefore.equals(snapshot(player)))throw new AssertionError("cost observer mutated native state/RNG "+key);
            System.out.println("TAMIYO_PAID "+key+" kind="+kind+" action="+actions+" loyaltyReceipt="+a.getSVar("CostCountersRemoved")+" sacrificed="+sacrifices+" discarded="+discarded);
        }
        void finish(boolean positive,String control){
            int expectedSac=control.endsWith(":bf-start")?8:7;
            int expectedDiscard=control.equals("Lion's Eye Diamond:held-card")?1:0;
            if(positive&&(returns!=8||casts!=8||sacrifices!=expectedSac||discarded!=expectedDiscard))throw new AssertionError("finite native payment counts "+key+" returns="+returns+" casts="+casts+" sacrifices="+sacrifices+" discarded="+discarded);
            if(!positive&&(returns!=0||casts!=0||sacrifices!=0||discarded!=0))throw new AssertionError("negative control plan payment "+key);
            System.out.println("TAMIYO_PAYMENT_RESULT "+key+" returns="+returns+" casts="+casts+" sacrifices="+sacrifices+" discarded="+discarded+" observerOnly=true");
        }
    }
    private static final class PaymentController extends forge.ai.CubeComboPlayerController {
        PaidReceipts observer;
        PaymentController(Game game,Player player,forge.LobbyPlayer lobby){super(game,player,lobby);}
        @Override public boolean playChosenSpellAbility(SpellAbility ability){
            if(observer==null)return super.playChosenSpellAbility(ability);
            var before=observer.before();boolean result=super.playChosenSpellAbility(ability);observer.after(before);return result;
        }
    }
    private static final class PaymentLobby extends forge.ai.LobbyPlayerAi {
        PaymentLobby(String name){super(name,null);setAiProfile("Default");}
        @Override public Player createIngamePlayer(Game game,int id){
            Player p=new Player(getName(),game,id);p.setFirstController(new PaymentController(game,p,this));return p;
        }
    }
    private static void run(String arm,boolean observed,int seat,String control) {
        List<RegisteredPlayer> entries=new ArrayList<>();
        for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat&&arm.equals("improved")?new PaymentLobby("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);if(lobby instanceof forge.ai.LobbyPlayerAi ai)ai.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game g=new Match(rules,entries,"Tamiyo Kitten observation").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        p.setLife(20,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);
        g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(992300L+100L*seat+CASES.indexOf(control));String key="arm="+arm+" seat="+seat+" case="+control.replace(' ','_');
        NativeEvents events=new NativeEvents(p,key);g.subscribeToEvents(events);PaidReceipts payments=new PaidReceipts(p,key);if(p.getController() instanceof PaymentController controller)controller.observer=payments;
        if(p.getManaPool().totalMana()!=0||p.getLandsPlayedThisTurn()!=0)throw new AssertionError("prepared initial resources");
        System.out.println("TAMIYO_FIXTURE "+key+" registered=40 initialLife="+p.getLife()+" initialMana=0 landsPlayed=0 observed="+observed+" policy="+forge.ai.CubeComboAi.VERSION+" extraPayoff="+(control.endsWith(":no-outlet")?"absent":"Aetherflux_Reservoir"));
        int steps=0,casts=0,loyaltyActions=0;Set<Integer> seen=new HashSet<>();
        while(!g.isGameOver()&&g.getPhaseHandler().getTurn()<=start&&steps<2000) {
            if(observed)observe(p,key);g.getPhaseHandler().mainLoopStep();steps++;
            for(var item:g.getStack())if(seen.add(item.getId())) {
                var a=item.getSpellAbility();if(a.getActivatingPlayer()!=p)continue;
                System.out.println("TAMIYO_STACK "+key+" source="+a.getHostCard().getName().replace(' ','_')+" api="+a.getApi()+" spell="+a.isSpell()+" copied="+a.isCopied());
                if(ARTIFACTS.contains(a.getHostCard().getName())&&a.isSpell()&&!a.isCopied())casts++;
                if(a.getHostCard().getName().equals(TAMIYO)&&a.isActivatedAbility()&&!a.isCopied())loyaltyActions++;

            }
        }
        boolean expect=arm.equals("improved")&&(control.endsWith(":complete")||control.endsWith(":bf-start")||control.endsWith(":held-card"));
        if(p.hasWon()!=expect)throw new AssertionError("native execution expectation "+key+" expected="+expect);
        if(expect&&(events.blinks!=8||events.returns!=8||casts!=8||loyaltyActions!=8))throw new AssertionError("missing finite recurrence receipts");
        if(steps>=2000)throw new AssertionError("step cap");
        payments.finish(expect,control);
        System.out.println("TAMIYO_RNG "+key+" "+((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot());
        System.out.println("TAMIYO_RESULT "+key+" won="+p.hasWon()+" gameOver="+g.isGameOver()+" life="+p.getLife()+" steps="+steps
            +" artifactToGrave="+events.sacrifices+" graveReturns="+events.returns+" tamiyoBlinks="+events.blinks+" artifactCasts="+casts+" loyaltyActions="+loyaltyActions
            +" mana="+p.getManaPool().totalMana()+" hand="+p.getCardsIn(ZoneType.Hand).size()+" grave="+p.getCardsIn(ZoneType.Graveyard).size()+" handToGrave="+events.discards+" lifeGainEvents="+events.gainEvents+" lifeLossEvents="+events.lossEvents+" scriptActions=0");

    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"tamiyo-native-payment-v100";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);
            for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],args[2].equals("observed"),seat,c);
            System.out.println("TAMIYO_PAYMENT_SUITE_COMPLETE cases=24");
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
