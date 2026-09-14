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

/** Scripted native Pyromancer cast; the real controller owns every discard.
 * Prepared choice/transition evidence only, never autonomous assembly or strength. */
public final class CubeKikiDiscardSmoke {
    private static final List<String> CASES=List.of("twin-conscripts","twin-exarch","twin-pestermite","kiki-conscripts","kiki-restoration","wrong-twin-restoration","missing-source","forced-pair", "no-blue-twin-exarch", "two-red-kiki-restoration", "hand-island-twin-exarch", "hand-mountain-kiki-restoration", "field-body-twin-exarch", "field-body-kiki-restoration", "field-engine-kiki-conscripts", "both-field-kiki-conscripts", "duplicate-twin-conscripts");
    private static final List<ZoneType> ZONES=List.of(ZoneType.Battlefield,ZoneType.Hand,ZoneType.Library,ZoneType.Graveyard,ZoneType.Exile);
    private record Placement(String name,ZoneType zone) {}
    private static String source(String key){return key.contains("kiki-")?"Kiki-Jiki, Mirror Breaker":key.equals("missing-source")?"Mountain":"Splinter Twin";}
    private static String partner(String key){return key.contains("exarch")?"Deceiver Exarch":key.contains("pestermite")?"Pestermite":key.contains("restoration")?"Restoration Angel":"Zealous Conscripts";}
    private static List<Placement> placements(boolean own,String key) {
        List<Placement> out=new ArrayList<>();
        if(own){for(int i=0;i<3;i++)out.add(new Placement(i==2&&(key.contains("two-red")||key.contains("hand-mountain"))?"Forest":"Mountain",ZoneType.Battlefield));out.add(new Placement(key.contains("no-blue")||key.contains("hand-island")?"Forest":"Island",ZoneType.Battlefield));out.add(new Placement("Plains",ZoneType.Battlefield));out.add(new Placement("Seasoned Pyromancer",ZoneType.Hand));out.add(new Placement(source(key),key.contains("field-engine")||key.contains("both-field")?ZoneType.Battlefield:ZoneType.Hand));out.add(new Placement(partner(key),key.contains("field-body")||key.contains("both-field")?ZoneType.Battlefield:ZoneType.Hand));if(key.contains("hand-island"))out.add(new Placement("Island",ZoneType.Hand));if(key.contains("hand-mountain"))out.add(new Placement("Mountain",ZoneType.Hand));if(key.contains("duplicate"))out.add(new Placement(source(key),ZoneType.Hand));if(!key.equals("forced-pair")){out.add(new Placement("Inti, Seneschal of the Sun",ZoneType.Hand));out.add(new Placement("Orcish Lumberjack",ZoneType.Hand));}}
        while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
    }
    private static Deck deck(boolean own,String key){Deck d=new Deck("Kiki native mandatory discard");for(var p:placements(own,key))d.getMain().add(p.name(),1);return d;}
    private static void populate(Player p,boolean own,String key){
        for(ZoneType z:ZoneType.values())if(p.getZone(z)!=null)p.getZone(z).removeAllCards(true);
        for(var placement:placements(own,key)){Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(placement.name())),p);c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(placement.zone()).add(c);c.setSickness(false);}
        Map<String,Integer> actual=new TreeMap<>(),registered=new TreeMap<>();for(ZoneType z:ZONES)for(Card c:p.getCardsIn(z))actual.merge(c.getPaperCard().getName(),1,Integer::sum);for(var e:p.getRegisteredPlayer().getDeck().getMain())registered.merge(e.getKey().getName(),e.getValue(),Integer::sum);if(!actual.equals(registered)||actual.values().stream().mapToInt(Integer::intValue).sum()!=40)throw new AssertionError("registered identity");
    }
    public static final class NativeEvents {
        final Player player;final String key;final List<String> discarded=new ArrayList<>(),drawn=new ArrayList<>();
        NativeEvents(Player p,String k){player=p;key=k;}
        @Subscribe public void moved(GameEventCardChangeZone e){
            if(e.card().getOwner().getId()!=player.getId()||e.from()==null||e.to()==null)return;
            if(e.from().zoneType()==ZoneType.Hand&&e.to().zoneType()==ZoneType.Graveyard)discarded.add(e.card().getName());
            if(e.from().zoneType()==ZoneType.Library&&e.to().zoneType()==ZoneType.Hand)drawn.add(e.card().getName());
            System.out.println("KIKI_DISCARD_ZONE "+key+" card="+e.card().getName().replace(' ','_')+" from="+e.from().zoneType()+" to="+e.to().zoneType());
        }
    }
    private static Set<String> names(Player p,ZoneType zone){Set<String> out=new TreeSet<>();for(Card c:p.getCardsIn(zone))out.add(c.getName());return out;}
    private static void run(String arm,int seat,String control){
        List<RegisteredPlayer> entries=new ArrayList<>();for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat&&arm.equals("improved")?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);if(lobby instanceof forge.ai.LobbyPlayerAi ai)ai.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);Game g=new Match(rules,entries,"Kiki discard").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);p.setLife(20,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(995100L+100L*seat+CASES.indexOf(control));
        String key="arm="+arm+" seat="+seat+" case="+control;NativeEvents events=new NativeEvents(p,key);g.subscribeToEvents(events);Card pyro=null;for(Card c:p.getCardsIn(ZoneType.Hand))if(c.getName().equals("Seasoned Pyromancer"))pyro=c;if(pyro==null)throw new AssertionError("source");var cast=pyro.getFirstSpellAbility();cast.setActivatingPlayer(p);
        System.out.println("KIKI_DISCARD_FIXTURE "+key+" registered=40 scriptedInitiatingCasts=1 nativeDiscardChoice=true policy="+forge.ai.CubeComboAi.VERSION);
        if(!forge.ai.CubeComboAi.canPlayNative(cast,p)||!forge.ai.ComputerUtil.handlePlayingSpellAbility(p,cast,null,a->new forge.ai.AiCostDecision(p,a,false))||cast.getPayingMana().size()!=3)throw new AssertionError("native cast/payment");
        int steps=0;while(steps<400&&!g.isGameOver()&&g.getPhaseHandler().getTurn()==start){g.getPhaseHandler().mainLoopStep();steps++;if(events.discarded.size()==2&&events.drawn.size()==2&&g.getStack().isEmpty())break;}
        if(steps>=400||events.discarded.size()!=2||events.drawn.size()!=2||!events.drawn.equals(List.of("Forest","Forest")))throw new AssertionError("native discard/draw receipts");
        Set<String> hand=names(p,ZoneType.Hand);Set<String> available=new TreeSet<>(hand);available.addAll(names(p,ZoneType.Battlefield));boolean kept=available.contains(source(control))&&available.contains(partner(control));boolean require=arm.equals("improved")&&!Set.of("wrong-twin-restoration","missing-source","forced-pair","no-blue-twin-exarch","two-red-kiki-restoration").contains(control);
        System.out.println("KIKI_DISCARD_RESULT "+key+" keptPair="+kept+" discarded="+events.discarded.toString().replace(' ','_')+" drawn=2 paidMana=3 steps="+steps+" rng="+((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot());
        if(require&&control.contains("hand-island")&&!hand.contains("Island"))throw new AssertionError("discarded required blue source");
        if(require&&control.contains("hand-mountain")&&!hand.contains("Mountain"))throw new AssertionError("discarded required third red source");
        if(require&&!kept)throw new AssertionError("complete own combo discarded despite two legal alternatives "+key);
        if(control.equals("forced-pair")&&kept)throw new AssertionError("mandatory discard evaded");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"kiki-native-discard";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);
            for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],seat,c);
            System.out.println("KIKI_DISCARD_SUITE_COMPLETE cases="+(2*CASES.size()));
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
