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
    private static final List<String> CASES=List.of("twin-conscripts","twin-exarch","twin-pestermite","kiki-conscripts","kiki-restoration","wrong-twin-restoration","missing-source","forced-pair", "no-blue-twin-exarch", "two-red-kiki-restoration", "hand-island-twin-exarch", "hand-mountain-kiki-restoration", "field-body-twin-exarch", "field-body-kiki-restoration", "field-engine-kiki-conscripts", "both-field-kiki-conscripts", "duplicate-twin-conscripts", "shroud-field-body-twin-exarch");
    private static boolean nativeBlink;
    private static final List<String> RESERVE_CASES=List.of("reserve-active-twin","reserve-wrong-twin-body","reserve-suppressed-engine","reserve-phased-partner","reserve-facedown-partner","reserve-other-controller");
    private static final List<String> BLINK_CASES=List.of("blink-risk","blink-zero-expendable","blink-enough","blink-mandatory","blink-other-actor","blink-no-kiki","blink-no-pyro","blink-alternative","blink-shroud-pyro","blink-phased-pyro","blink-facedown-kiki","blink-wrong-shape","blink-mandatory-risk","blink-mandatory-alternative");
    private static final List<ZoneType> ZONES=List.of(ZoneType.Battlefield,ZoneType.Hand,ZoneType.Library,ZoneType.Graveyard,ZoneType.Exile);
    private record Placement(String name,ZoneType zone) {}
    private static String source(String key){return key.contains("kiki-")?"Kiki-Jiki, Mirror Breaker":key.equals("missing-source")?"Mountain":"Splinter Twin";}
    private static String partner(String key){return key.contains("exarch")?"Deceiver Exarch":key.contains("pestermite")?"Pestermite":key.contains("restoration")?"Restoration Angel":"Zealous Conscripts";}
    private static List<Placement> placements(boolean own,String key) {
        List<Placement> out=new ArrayList<>();
        if(own&&key.startsWith("reserve-")) {
            for(int i=0;i<3;i++)out.add(new Placement("Mountain",ZoneType.Battlefield));
            out.add(new Placement("Island",ZoneType.Battlefield));out.add(new Placement("Plains",ZoneType.Battlefield));
            if(key.equals("reserve-active-twin")) {
                out.add(new Placement("Splinter Twin",ZoneType.Battlefield));out.add(new Placement("Deceiver Exarch",ZoneType.Battlefield));out.add(new Placement("Splinter Twin",ZoneType.Hand));
            }else if(key.equals("reserve-wrong-twin-body")) {
                out.add(new Placement("Splinter Twin",ZoneType.Battlefield));out.add(new Placement("Seasoned Pyromancer",ZoneType.Battlefield));out.add(new Placement("Deceiver Exarch",ZoneType.Hand));
            }else {
                boolean engine=key.equals("reserve-suppressed-engine");
                out.add(new Placement("Kiki-Jiki, Mirror Breaker",engine?ZoneType.Battlefield:ZoneType.Hand));
                out.add(new Placement("Restoration Angel",engine?ZoneType.Hand:ZoneType.Battlefield));
            }
            while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
        }
        if(own&&key.startsWith("blink-")) {
            for(int i=0;i<3;i++)out.add(new Placement("Mountain",ZoneType.Battlefield));
            out.add(new Placement("Island",ZoneType.Battlefield));out.add(new Placement("Plains",ZoneType.Battlefield));
            out.add(new Placement("Restoration Angel",nativeBlink?ZoneType.Hand:ZoneType.Battlefield));
            if(!key.equals("blink-no-pyro"))out.add(new Placement("Seasoned Pyromancer",ZoneType.Battlefield));
            if(!key.equals("blink-no-kiki"))out.add(new Placement("Kiki-Jiki, Mirror Breaker",ZoneType.Hand));
            if(!key.equals("blink-zero-expendable"))out.add(new Placement("Forest",ZoneType.Hand));
            if(key.equals("blink-enough"))out.add(new Placement("Forest",ZoneType.Hand));
            if(key.contains("alternative"))out.add(new Placement("Wall of Omens",ZoneType.Battlefield));
            while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
        }
        if(own){for(int i=0;i<3;i++)out.add(new Placement(i==2&&(key.contains("two-red")||key.contains("hand-mountain"))?"Forest":"Mountain",ZoneType.Battlefield));out.add(new Placement(key.contains("no-blue")||key.contains("hand-island")?"Forest":"Island",ZoneType.Battlefield));out.add(new Placement("Plains",ZoneType.Battlefield));out.add(new Placement("Seasoned Pyromancer",ZoneType.Hand));out.add(new Placement(source(key),key.contains("field-engine")||key.contains("both-field")?ZoneType.Battlefield:ZoneType.Hand));out.add(new Placement(partner(key),key.contains("field-body")||key.contains("both-field")?ZoneType.Battlefield:ZoneType.Hand));if(key.contains("hand-island"))out.add(new Placement("Island",ZoneType.Hand));if(key.contains("hand-mountain"))out.add(new Placement("Mountain",ZoneType.Hand));if(key.contains("duplicate"))out.add(new Placement(source(key),ZoneType.Hand));if(!key.equals("forced-pair")){out.add(new Placement("Inti, Seneschal of the Sun",ZoneType.Hand));out.add(new Placement("Orcish Lumberjack",ZoneType.Hand));}}
        while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
    }
    private static Deck deck(boolean own,String key){Deck d=new Deck("Kiki native mandatory discard");for(var p:placements(own,key))d.getMain().add(p.name(),1);return d;}
    private static void populate(Player p,boolean own,String key){
        for(ZoneType z:ZoneType.values())if(p.getZone(z)!=null)p.getZone(z).removeAllCards(true);
        for(var placement:placements(own,key)){Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(placement.name())),p);c.setGameTimestamp(p.getGame().getNextTimestamp());p.getZone(placement.zone()).add(c);c.setSickness(false);if(key.contains("shroud")&&c.getName().equals(partner(key)))c.addIntrinsicKeyword("Shroud");}
        Map<String,Integer> actual=new TreeMap<>(),registered=new TreeMap<>();for(ZoneType z:ZONES)for(Card c:p.getCardsIn(z))actual.merge(c.getPaperCard().getName(),1,Integer::sum);for(var e:p.getRegisteredPlayer().getDeck().getMain())registered.merge(e.getKey().getName(),e.getValue(),Integer::sum);if(!actual.equals(registered)||actual.values().stream().mapToInt(Integer::intValue).sum()!=40)throw new AssertionError("registered identity");
    }
    public static final class NativeEvents {
        final Player player;final String key;final List<String> discarded=new ArrayList<>(),drawn=new ArrayList<>(),blinked=new ArrayList<>();
        NativeEvents(Player p,String k){player=p;key=k;}
        @Subscribe public void moved(GameEventCardChangeZone e){
            if(e.card().getOwner().getId()!=player.getId()||e.from()==null||e.to()==null)return;
            if(e.from().zoneType()==ZoneType.Battlefield&&e.to().zoneType()==ZoneType.Exile)blinked.add(e.card().getName());
            if(e.from().zoneType()==ZoneType.Hand&&e.to().zoneType()==ZoneType.Graveyard)discarded.add(e.card().getName());
            if(e.from().zoneType()==ZoneType.Library&&e.to().zoneType()==ZoneType.Hand)drawn.add(e.card().getName());
            System.out.println("KIKI_DISCARD_ZONE "+key+" card="+e.card().getName().replace(' ','_')+" from="+e.from().zoneType()+" to="+e.to().zoneType());
        }
    }
    private static Set<String> names(Player p,ZoneType zone){Set<String> out=new TreeSet<>();for(Card c:p.getCardsIn(zone))out.add(c.getName());return out;}
    private static Map<String,Object> observationState(Player p) {
        try {
            var snapshot=CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot",Player.class);
            snapshot.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,Object> state=new LinkedHashMap<>((Map<String,Object>)snapshot.invoke(null,p));
            var ids=forge.game.spellability.SpellAbility.class.getDeclaredField("maxId");ids.setAccessible(true);
            state.put("abilityMaxId",ids.getInt(null));
            for(ZoneType zone:List.of(ZoneType.Hand,ZoneType.Battlefield)) {
                state.put(zone+"AbilityScratch",p.getCardsIn(zone).stream().flatMap(c->c.getSpellAbilities().stream())
                        .map(a->a.getId()+":"+a.getPipsToReduce()+":"+a.getPayingMana()+":"+a.getPayCosts()).toList());
            }
            return state;
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void observe(Player p,String key,String control,boolean initial,boolean badCopy) {
        try {
            var query=forge.ai.CubeComboAi.class.getDeclaredMethod("kikiDiscardProtectedCards",Player.class);query.setAccessible(true);
            var before=observationState(p);List<Integer> first=null;
            for(int i=0;i<3;i++) {
                if(badCopy)p.getCardsIn(ZoneType.Hand).get(0).getFirstSpellAbility().copy(p);
                CardCollection reserved=(CardCollection)query.invoke(null,p);
                List<Integer> ids=reserved.stream().map(Card::getId).toList();
                if(first==null)first=ids;else if(!first.equals(ids))throw new AssertionError("discard reservation identity drift");
                var after=observationState(p);
                if(!before.equals(after)) {
                    List<String> changed=new ArrayList<>();for(String field:before.keySet())if(!Objects.equals(before.get(field),after.get(field)))changed.add(field);
                    throw new AssertionError("discard observation mutated "+changed);
                }
                if(initial) {
                    int expected=Set.of("wrong-twin-restoration","missing-source","no-blue-twin-exarch","two-red-kiki-restoration","both-field-kiki-conscripts","shroud-field-body-twin-exarch").contains(control)?0:control.contains("hand-island")||control.contains("hand-mountain")?3:control.contains("field-body")||control.contains("field-engine")?1:2;
                    if(ids.size()!=expected)throw new AssertionError("reservation count "+control+" expected="+expected+" actual="+ids.size());
                    for(Card c:reserved)if(!c.isInZone(ZoneType.Hand)||c.getOwner()!=p)throw new AssertionError("reservation outside own hand");
                }
            }
            System.out.println("KIKI_DISCARD_QUERY "+key+" initial="+initial+" repeats=3 reserved="+first.size()+" stateUnchanged=true");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void reserveBoundary(String arm,int seat,String control) {
        List<RegisteredPlayer> entries=new ArrayList<>();
        for(int i=0;i<2;i++) {
            forge.ai.LobbyPlayerAi lobby=i==seat&&arm.equals("improved")?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+i):new forge.ai.LobbyPlayerAi("Default-"+i,null);
            lobby.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(i==seat,control)).setPlayer(lobby));
        }
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        Game g=new Match(rules,entries,"Discard reservation boundaries").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        populate(p,true,control);populate(op,false,control);g.setAge(GameStage.Play);g.getPhaseHandler().setupFirstTurn(p,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p));
        Card aura=null,body=null;
        for(Card c:p.getCardsIn(ZoneType.Battlefield)) {
            if(c.getName().equals("Splinter Twin"))aura=c;
            if(c.getName().equals(control.equals("reserve-wrong-twin-body")?"Seasoned Pyromancer":"Deceiver Exarch"))body=c;
            if(c.getName().equals("Restoration Angel")) {
                if(control.equals("reserve-phased-partner"))c.setPhasedOut(p);
                if(control.equals("reserve-facedown-partner"))c.turnFaceDown(true);
                if(control.equals("reserve-other-controller"))c.setController(op,g.getNextTimestamp());
            }
            if(control.equals("reserve-suppressed-engine")&&c.getName().equals("Kiki-Jiki, Mirror Breaker"))for(var a:c.getSpellAbilities())a.setSuppressed(true);
        }
        if(aura!=null)aura.attachToEntity(Objects.requireNonNull(body),null);
        g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        if(control.equals("reserve-active-twin")&&body.getSpellAbilities().stream().noneMatch(a->a.getApi()==forge.game.ability.ApiType.CopyPermanent&&"Self".equals(a.getParam("Defined"))))throw new AssertionError("native Twin grant missing");
        BenchRandomAudit.install(998100L+100L*seat+RESERVE_CASES.indexOf(control));
        var before=observationState(p);
        try {
            var query=forge.ai.CubeComboAi.class.getDeclaredMethod("kikiDiscardProtectedCards",Player.class);query.setAccessible(true);
            for(int i=0;i<3;i++) {
                CardCollection reserved=(CardCollection)query.invoke(null,p);
                if(!reserved.isEmpty())throw new AssertionError("unnecessary or invalid reservation "+control+" count="+reserved.size());
                if(!before.equals(observationState(p)))throw new AssertionError("reservation boundary query mutation");
            }
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        System.out.println("KIKI_RESERVE_BOUNDARY arm="+arm+" seat="+seat+" case="+control+" reserved=0 repeats=3 stateUnchanged=true registered=40");
    }
    private static void mandatoryFixtureTriggers(List<forge.game.spellability.SpellAbility> abilities) {
        for(var a:abilities)if(a.getHostCard().getName().equals("Restoration Angel")&&a.isTrigger()) {
            a.setOptionalTrigger(false);
            System.out.println("KIKI_BLINK_MANDATORY_FIXTURE optional=false beforeNativePreparation=true");
        }
    }
    private static final class MandatoryDefaultController extends forge.ai.PlayerControllerAi {
        MandatoryDefaultController(Game g,Player p,forge.LobbyPlayer lobby){super(g,p,lobby);}
        @Override public void orderAndPlaySimultaneousSa(List<forge.game.spellability.SpellAbility> abilities){mandatoryFixtureTriggers(abilities);super.orderAndPlaySimultaneousSa(abilities);}
    }
    private static final class MandatoryComboController extends forge.ai.CubeComboPlayerController {
        MandatoryComboController(Game g,Player p,forge.LobbyPlayer lobby){super(g,p,lobby);}
        @Override public void orderAndPlaySimultaneousSa(List<forge.game.spellability.SpellAbility> abilities){mandatoryFixtureTriggers(abilities);super.orderAndPlaySimultaneousSa(abilities);}
    }
    private static void blinkBoundary(String arm,int seat,String control) {
        List<RegisteredPlayer> entries=new ArrayList<>();
        for(int i=0;i<2;i++) {
            forge.ai.LobbyPlayerAi lobby=i==seat&&arm.equals("improved")?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+i):new forge.ai.LobbyPlayerAi("Default-"+i,null);
            lobby.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(i==seat,control)).setPlayer(lobby));
        }
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game g=new Match(rules,entries,"Blink guard boundaries").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);
        populate(p,true,control);populate(op,false,control);g.setAge(GameStage.Play);
        g.getPhaseHandler().setupFirstTurn(p,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p));
        Card angel=null;
        for(Card c:p.getCardsIn(ZoneType.Battlefield)) {
            if(c.getName().equals("Restoration Angel"))angel=c;
            if(c.getName().equals("Seasoned Pyromancer")) {
                if(control.equals("blink-shroud-pyro"))c.addIntrinsicKeyword("Shroud");
                if(control.equals("blink-phased-pyro"))c.setPhasedOut(p);
            }
        }
        if(nativeBlink)for(Card c:p.getCardsIn(ZoneType.Hand))if(c.getName().equals("Restoration Angel"))angel=c;
        if(control.equals("blink-facedown-kiki"))for(Card c:p.getCardsIn(ZoneType.Hand))if(c.getName().equals("Kiki-Jiki, Mirror Breaker"))c.turnFaceDown(true);
        if(nativeBlink&&control.startsWith("blink-mandatory-"))p.dangerouslySetController(arm.equals("improved")
                ?new MandatoryComboController(g,p,p.getLobbyPlayer()):new MandatoryDefaultController(g,p,p.getLobbyPlayer()));
        g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();
        if(nativeBlink) {
            BenchRandomAudit.install(997100L+100L*seat+BLINK_CASES.indexOf(control));
            String key="arm="+arm+" seat="+seat+" case="+control;
            NativeEvents events=new NativeEvents(p,key);g.subscribeToEvents(events);
            var cast=Objects.requireNonNull(angel).getFirstSpellAbility();cast.setActivatingPlayer(p);
            System.out.println("KIKI_BLINK_NATIVE_FIXTURE "+key+" registered=40 scriptedInitiatingCasts=1");
            if(!forge.ai.CubeComboAi.canPlayNative(cast,p)||!forge.ai.ComputerUtil.handlePlayingSpellAbility(p,cast,null,a->new forge.ai.AiCostDecision(p,a,false))||cast.getPayingMana().size()!=4)throw new AssertionError("native Restoration cast/payment");
            int steps=0;boolean mandatoryReceipt=false;
            while(!g.isGameOver()&&steps<400) {
                g.getPhaseHandler().mainLoopStep();steps++;
                for(var item:g.getStack()) {
                    var a=item.getSpellAbility();
                    if(!a.isSpell()&&a.getHostCard().getName().equals("Restoration Angel")&&a.getApi()==forge.game.ability.ApiType.ChangeZone&&control.startsWith("blink-mandatory-")) {
                        if(a.isOptionalTrigger())throw new AssertionError("mandatory fixture retained optional flag");
                        mandatoryReceipt=true;
                    }
                }
                if(names(p,ZoneType.Battlefield).contains("Restoration Angel")&&g.getStack().isEmpty()&&!g.getStack().hasSimultaneousStackEntries())break;
            }
            boolean kept=names(p,ZoneType.Hand).contains("Kiki-Jiki, Mirror Breaker");
            System.out.println("KIKI_BLINK_NATIVE_RESULT "+key+" keptKiki="+kept+" paidMana=4 steps="+steps+" blinked="+events.blinked.toString().replace(' ','_')+" discarded="+events.discarded.toString().replace(' ','_')+" drawn="+events.drawn.size());
            if(steps>=400)throw new AssertionError("native blink step cap");
            if(control.startsWith("blink-mandatory-")&&(!mandatoryReceipt||kept))throw new AssertionError("mandatory native trigger control");
            if(arm.equals("improved")&&!control.equals("blink-no-kiki")&&!control.startsWith("blink-mandatory-")&&!kept)throw new AssertionError("native blink discarded Kiki "+key);
            return;
        }
        var effect=forge.game.ability.AbilityFactory.getAbility(Objects.requireNonNull(angel).getSVar("RestorationExile"),angel);
        effect.setActivatingPlayer(control.equals("blink-other-actor")?op:p);
        if(control.equals("blink-wrong-shape"))effect.putParam("Destination","Graveyard");
        BenchRandomAudit.install(996100L+100L*seat+BLINK_CASES.indexOf(control));
        var before=observationState(p);var actor=effect.getActivatingPlayer();var targets=effect.getTargets();String targetText=targets.toString();
        boolean expected=arm.equals("improved")&&Set.of("blink-risk","blink-zero-expendable").contains(control);
        for(int i=0;i<3;i++) {
            boolean actual=forge.ai.CubeComboAi.declineDestructiveComboBlink(p,effect,control.startsWith("blink-mandatory"));
            if(actual!=expected)throw new AssertionError("blink boundary "+control+" expected="+expected+" actual="+actual);
            if(!before.equals(observationState(p))||actor!=effect.getActivatingPlayer()||targets!=effect.getTargets()||!targetText.equals(targets.toString()))throw new AssertionError("blink query mutation "+control);
        }
        System.out.println("KIKI_BLINK_BOUNDARY arm="+arm+" seat="+seat+" case="+control+" declined="+expected+" repeats=3 stateUnchanged=true registered=40");
    }
    private static void run(String arm,int seat,String control,boolean observed,boolean badCopy,boolean continuation){
        List<RegisteredPlayer> entries=new ArrayList<>();for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat&&arm.equals("improved")?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);if(lobby instanceof forge.ai.LobbyPlayerAi ai)ai.setAiProfile("Default");entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);Game g=new Match(rules,entries,"Kiki discard").createGame();Player p=g.getPlayers().get(seat),op=g.getPlayers().get(1-seat);p.setLife(20,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);g.setAge(GameStage.Play);int start=seat==0?1:2;g.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->g.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));g.getAction().checkStateEffects(true);g.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(995100L+100L*seat+CASES.indexOf(control));
        String key="arm="+arm+" seat="+seat+" case="+control;NativeEvents events=new NativeEvents(p,key);g.subscribeToEvents(events);Card pyro=null;for(Card c:p.getCardsIn(ZoneType.Hand))if(c.getName().equals("Seasoned Pyromancer"))pyro=c;if(pyro==null)throw new AssertionError("source");var cast=pyro.getFirstSpellAbility();cast.setActivatingPlayer(p);
        System.out.println("KIKI_DISCARD_FIXTURE "+key+" registered=40 scriptedInitiatingCasts=1 nativeDiscardChoice=true policy="+forge.ai.CubeComboAi.VERSION);
        if(observed)observe(p,key,control,true,badCopy);
        if(!forge.ai.CubeComboAi.canPlayNative(cast,p)||!forge.ai.ComputerUtil.handlePlayingSpellAbility(p,cast,null,a->new forge.ai.AiCostDecision(p,a,false))||cast.getPayingMana().size()!=3)throw new AssertionError("native cast/payment");
        int steps=0;while(steps<400&&!g.isGameOver()&&g.getPhaseHandler().getTurn()==start){if(observed)observe(p,key,control,false,false);g.getPhaseHandler().mainLoopStep();steps++;if(events.discarded.size()==2&&events.drawn.size()==2&&g.getStack().isEmpty())break;}
        if(steps>=400||events.discarded.size()!=2||events.drawn.size()!=2||!events.drawn.equals(List.of("Forest","Forest")))throw new AssertionError("native discard/draw receipts");
        Set<String> hand=names(p,ZoneType.Hand);Set<String> available=new TreeSet<>(hand);available.addAll(names(p,ZoneType.Battlefield));boolean kept=available.contains(source(control))&&available.contains(partner(control));boolean require=arm.equals("improved")&&!Set.of("wrong-twin-restoration","missing-source","forced-pair","no-blue-twin-exarch","two-red-kiki-restoration","shroud-field-body-twin-exarch").contains(control);
        System.out.println("KIKI_DISCARD_RESULT "+key+" keptPair="+kept+" discarded="+events.discarded.toString().replace(' ','_')+" drawn=2 paidMana=3 steps="+steps+" rng="+((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot());
        if(require&&control.contains("hand-island")&&!hand.contains("Island"))throw new AssertionError("discarded required blue source");
        if(require&&control.contains("hand-mountain")&&!hand.contains("Mountain"))throw new AssertionError("discarded required third red source");
        if(require&&!kept)throw new AssertionError("complete own combo discarded despite two legal alternatives "+key);
        if(control.contains("shroud")&&arm.equals("improved")&&hand.contains(source(control)))throw new AssertionError("reserved Twin despite public shroud on only partner");
        if(control.equals("forced-pair")&&kept)throw new AssertionError("mandatory discard evaded");
        if(continuation) {
            int nativeSteps=0,copies=0,maxTokens=0;Set<Integer> seen=new HashSet<>();
            while(!g.isGameOver()&&g.getPhaseHandler().getTurn()<=start+8&&nativeSteps<5000) {
                g.getPhaseHandler().mainLoopStep();nativeSteps++;
                maxTokens=Math.max(maxTokens,(int)p.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isToken).count());
                for(var item:g.getStack())if(seen.add(item.getId())) {
                    var a=item.getSpellAbility();if(a.getActivatingPlayer()!=p)continue;
                    if(a.isActivatedAbility()&&!a.isCopied()&&a.getApi()==forge.game.ability.ApiType.CopyPermanent)copies++;
                    System.out.println("KIKI_CONTINUATION_STACK "+key+" host="+a.getHostCard().getName().replace(' ','_')+" api="+a.getApi()+" spell="+a.isSpell()+" paidMana="+a.getPayingMana().size());
                }
            }
            System.out.println("KIKI_CONTINUATION_RESULT "+key+" won="+p.hasWon()+" gameOver="+g.isGameOver()+" turns="+(g.getPhaseHandler().getTurn()-start)+" steps="+nativeSteps+" nativeCopyActivations="+copies+" maxOwnTokens="+maxTokens+" furtherScriptedActions=0");
            if(nativeSteps>=5000)throw new AssertionError("continuation step cap");
            if(arm.equals("improved")&&!control.contains("shroud")&&(!p.hasWon()||copies<3))throw new AssertionError("retained pair did not reach native combo win "+key);
        }
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"kiki-native-discard";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            nativeBlink=args.length>2&&args[2].equals("blink-native");
            Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String c:BLINK_CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String c:RESERVE_CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);
            if(args.length>2&&args[2].equals("reserve-boundaries")) {
                for(String c:RESERVE_CASES)for(int seat=0;seat<2;seat++)reserveBoundary(args[1],seat,c);
                System.out.println("KIKI_RESERVE_BOUNDARY_COMPLETE cases=12");return;
            }
            if(nativeBlink) {
                for(String c:List.of("blink-risk","blink-enough","blink-alternative","blink-no-kiki","blink-shroud-pyro","blink-mandatory-risk","blink-mandatory-alternative"))for(int seat=0;seat<2;seat++)blinkBoundary(args[1],seat,c);
                System.out.println("KIKI_BLINK_NATIVE_COMPLETE cases=14");return;
            }
            if(args.length>2&&args[2].equals("blink-boundaries")) {
                for(String c:BLINK_CASES)for(int seat=0;seat<2;seat++)blinkBoundary(args[1],seat,c);
                System.out.println("KIKI_BLINK_BOUNDARY_COMPLETE cases=28");return;
            }
            boolean continuation=args.length>2&&args[2].equals("continue");
            List<String> selected=continuation?List.of("twin-conscripts","twin-exarch","kiki-restoration","shroud-field-body-twin-exarch"):CASES;
            for(String c:selected)for(int seat=0;seat<2;seat++)run(args[1],seat,c,args.length>2&&!args[2].equals("plain")&&!continuation,args.length>2&&args[2].equals("bad-copy"),continuation);
            System.out.println("KIKI_DISCARD_SUITE_COMPLETE cases="+(2*selected.size()));
        }catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
}
