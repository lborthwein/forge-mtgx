package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.*;
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

/** Native resource assignment drives all six external mana actions. The spell sequence remains a scripted component control,
 * never an evaluation of autonomous policy or replay of a spent sample. */
public final class CubeDoomStarResourceSmoke {
    private static final List<String> CASES=List.of("complete","no-land-drop","low-devotion","draw-blocked","colorless-land","no-safe-blue");
    private record Placement(String name, ZoneType zone) {}
    private static List<Placement> placements(boolean own,String control) {
        List<Placement> out=new ArrayList<>();
        if(own) {
            for(String name:List.of("Swamp","Swamp","Urborg, Tomb of Yawgmoth","Underground Sea","Talisman of Dominance","Snapcaster Mage")) out.add(new Placement(name,ZoneType.Battlefield));
            if(!control.equals("low-devotion")) for(String name:List.of("Vendilion Clique","True-Name Nemesis")) out.add(new Placement(name,ZoneType.Battlefield));
            for(String name:List.of("Doomsday","Chromatic Star")) out.add(new Placement(name,ZoneType.Hand));
            if(!control.equals("no-land-drop")) for(String name:List.of("Island","Swamp","Swamp","Watery Grave")) out.add(new Placement(name,ZoneType.Hand));
            out.add(new Placement("Thassa's Oracle",ZoneType.Library));
        } else if(control.equals("draw-blocked")) out.add(new Placement("Omen Machine",ZoneType.Battlefield));
        if(own && (control.equals("colorless-land") || control.equals("no-safe-blue"))) {
            for(int i=0;i<out.size();i++){var p=out.get(i);if(p.name().equals("Island"))out.set(i,new Placement(control.equals("colorless-land")?"Wastes":"Swamp",p.zone()));else if(control.equals("no-safe-blue") && p.name().equals("Underground Sea"))out.set(i,new Placement("Swamp",p.zone()));}
        }
        while(out.size()<40) out.add(new Placement("Forest",ZoneType.Library));
        return out;
    }
    private static Deck deck(boolean own,String control) { Deck deck=new Deck("Doom Star diagnostic");for(var p:placements(own,control))deck.getMain().add(p.name(),1);return deck; }
    private static void populate(Player player,boolean own,String control) {
        for(ZoneType z:ZoneType.values())if(player.getZone(z)!=null)player.getZone(z).removeAllCards(true);
        for(var p:placements(own,control)) { Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(p.name())),player);c.setGameTimestamp(player.getGame().getNextTimestamp());player.getZone(p.zone()).add(c);c.setSickness(false); }
        Map<String,Integer> actual=new TreeMap<>(),registered=new TreeMap<>();
        for(ZoneType z:List.of(ZoneType.Battlefield,ZoneType.Hand,ZoneType.Library))for(Card c:player.getCardsIn(z))actual.merge(c.getPaperCard().getName(),1,Integer::sum);
        for(var e:player.getRegisteredPlayer().getDeck().getMain())registered.merge(e.getKey().getName(),e.getValue(),Integer::sum);
        if(!actual.equals(registered)||actual.values().stream().mapToInt(Integer::intValue).sum()!=40)throw new AssertionError("registered identity");
    }
    private static final class ScriptLobby extends forge.ai.LobbyPlayerAi {
        ScriptLobby(int seat){super("Script-"+seat,null);setAiProfile("Default");}
        @Override public Player createIngamePlayer(Game game,int id){Player p=new Player(getName(),game,id);p.setFirstController(new ScriptController(game,p,this));return p;}
    }
    private static final class ScriptController extends forge.ai.PlayerControllerAi {
        private java.util.List<forge.ai.CubeDoomStarResources.Payment> payments; private int stage,plays,searchChoices; private SpellAbility selected,doom; private String key;
        ScriptController(Game game,Player player,forge.LobbyPlayer lobby){super(game,player,lobby);}
        private Card find(String name,ZoneType zone){for(Card c:getPlayer().getCardsIn(zone))if(!c.isFaceDown()&&c.getName().equals(name))return c;return null;}
        @Override public List<SpellAbility> chooseSpellAbilityToPlay(){
            if(!getGame().getStack().isEmpty()||!getGame().getPhaseHandler().is(PhaseType.MAIN1,getPlayer()))return null;
            if(stage==0){Card land=find("Island",ZoneType.Hand);if(land==null)land=find("Wastes",ZoneType.Hand);if(land==null)land=find("Swamp",ZoneType.Hand);if(land==null)stage++;else for(var original:land.getAllPossibleAbilities(getPlayer(),false,null,true)){var a=original.copy(getPlayer());if(a.isLandAbility()&&forge.ai.CubeComboAi.canPlayNative(a,getPlayer())){selected=a;return List.of(a);}}}
            if(stage==1 && payments==null){
                try {var snap=CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot",Player.class);snap.setAccessible(true);Object before=snap.invoke(null,getPlayer());String identity=null;
                    for(int q=0;q<3;q++){var got=forge.ai.CubeDoomStarResources.assign(getPlayer(),List.of("1","B","B","B","1","U"),Set.of());String receipt=got==null?"none":got.stream().map(p->p.source().getId()+":"+p.color()).toList().toString();if(identity!=null&&!identity.equals(receipt))throw new AssertionError("resource assignment drift");identity=receipt;if(!before.equals(snap.invoke(null,getPlayer())))throw new AssertionError("resource native/RNG mutation");payments=got;}
                    if(payments!=null&&payments.stream().map(p->p.source().getId()).distinct().count()!=6)throw new AssertionError("source reused");
                    System.out.println("DOOM_STAR_RESOURCES "+key+" disjoint="+(payments!=null)+" repeats=3 unchanged=true assignment="+identity.replace(" ",""));
                }catch(ReflectiveOperationException e){throw new AssertionError(e);}
                if(payments==null)return null;
            }
            int paymentSlot=switch(stage){case 1->0;case 3->1;case 4->2;case 5->3;case 7->4;case 9->5;default->-1;};
            if(paymentSlot>=0){if(payments==null)return null;var a=payments.get(paymentSlot).ability();if(!forge.ai.CubeComboAi.canPlayNative(a,getPlayer())||!forge.ai.CubeComboAi.canPayCost(a,getPlayer(),false))return null;selected=a;return List.of(a);}
            String name=switch(stage){case 0,9->"Island";case 1->"Talisman of Dominance";case 2,8->"Chromatic Star";case 3,4->"Swamp";case 5->"Urborg, Tomb of Yawgmoth";case 6->"Doomsday";case 7->"Underground Sea";case 10->"Thassa's Oracle";default->null;};
            if(name==null)return null;
            boolean manaStage=Set.of(1,3,4,5,7,8,9).contains(stage);
            Card card=null;
            for(Card c:getPlayer().getCardsIn(manaStage?ZoneType.Battlefield:ZoneType.Hand))
                if(!c.isFaceDown()&&c.getName().equals(name)&&(!manaStage||c.isUntapped())){card=c;break;}
            if(card==null)return null;
            Iterable<SpellAbility> available=manaStage?card.getManaAbilities():card.getAllPossibleAbilities(getPlayer(),false,null,true);
            for(SpellAbility original:available){SpellAbility action=original.copy(getPlayer());
                if(stage==0&&!action.isLandAbility()||stage!=0&&!manaStage&&!action.isSpell())continue;
                if(manaStage){String color=stage==1?"C":stage>=7?"U":"B";if(action.getManaPart()==null||!action.canProduce(color))continue;
                    if(!color.equals("C"))action.setManaExpressChoice(forge.card.ColorSet.fromMask(color.equals("U")?forge.card.MagicColor.BLUE:forge.card.MagicColor.BLACK));}
                if(!forge.ai.CubeComboAi.canPlayNative(action,getPlayer())||!action.isLandAbility()&&!forge.ai.CubeComboAi.canPayCost(action,getPlayer(),false))continue;
                selected=action;return List.of(action);
            }return null;
        }
        @Override public boolean playChosenSpellAbility(SpellAbility action){
            if(action!=selected||getGame().getPhaseHandler().getPriorityPlayer()!=getPlayer())throw new AssertionError("unowned scripted action or priority");
            if(!forge.ai.CubeComboAi.canPlayNative(action,getPlayer())||!action.isLandAbility()&&!forge.ai.CubeComboAi.canPayCost(action,getPlayer(),false))throw new AssertionError("script native legality/payment");
            if(stage==6)doom=action;
            boolean success;
            if(action.isLandAbility()){action.resolve();success=action.getHostCard().isInZone(ZoneType.Battlefield);}
            else success=forge.ai.ComputerUtil.handlePlayingSpellAbility(getPlayer(),action,null,a->new forge.ai.AiCostDecision(getPlayer(),a,false));
            if(!success)throw new AssertionError("script native payment failed");
            System.out.println("DOOM_STAR_SCRIPT "+key+" stage="+stage+" nativePriority=true paid=true source="+action.getHostCard().getName().replace(' ','_')+" api="+action.getApi()+" life="+getPlayer().getLife()+" pool="+getPlayer().getManaPool().totalMana()+" mana="+java.util.stream.StreamSupport.stream(getPlayer().getManaPool().spliterator(),false).map(m->Byte.toString(m.getColor())).toList().toString().replace(" ","")+" ownHand="+getPlayer().getCardsIn(ZoneType.Hand).stream().map(c->c.getName().replace(' ','_')).sorted().toList().toString().replace(" ",""));
            stage++;plays++;return true;
        }
        private boolean ownedSearch(SpellAbility source){return doom!=null&&source!=null&&source.getRootAbility()==doom&&source.getActivatingPlayer()==getPlayer();}
        @Override public Card chooseSingleCardForZoneChange(ZoneType destination,List<ZoneType> origin,SpellAbility source,CardCollection choices,DelayedReveal reveal,String prompt,boolean optional,Player decider){
            if(destination!=ZoneType.Library||decider!=getPlayer()||!ownedSearch(source))return super.chooseSingleCardForZoneChange(destination,origin,source,choices,reveal,prompt,optional,decider);
            if(reveal!=null)reveal(reveal);
            for(Card c:choices)if(c.getOwner()!=getPlayer()||getGame().getCardState(c,null)!=c)throw new AssertionError("foreign/noncanonical granted choice");
            Card chosen=choices.stream().filter(c->c.getName().equals("Thassa's Oracle")).findFirst().orElse(choices.get(0));searchChoices++;
            System.out.println("DOOM_STAR_SEARCH "+key+" nativeGranted=true chosen="+chosen.getName().replace(' ','_'));return chosen;
        }
        @Override public CardCollectionView orderMoveToZoneList(CardCollectionView cards,ZoneType destination,SpellAbility source){
            if(destination!=ZoneType.Library||!ownedSearch(source))return super.orderMoveToZoneList(cards,destination,source);
            CardCollection order=new CardCollection();for(Card c:cards)if(!c.getName().equals("Thassa's Oracle"))order.add(c);for(Card c:cards)if(c.getName().equals("Thassa's Oracle"))order.add(c);
            System.out.println("DOOM_STAR_ORDER "+key+" nativeGranted=true size="+cards.size()+" oracleLast=true");return order;
        }
    }
    private record SavedField(Object owner, java.lang.reflect.Field field, Object value) {}
    private static void savePlanner(Object plan, List<SavedField> saved) throws ReflectiveOperationException {
        for (var field : plan.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true); Object value = field.get(plan);
            if (java.lang.reflect.Modifier.isFinal(field.getModifiers())) {
                if (value != null && value.getClass().getName().equals("forge.ai.CubeDoomStarPlan")) savePlanner(value, saved);
            } else saved.add(new SavedField(plan, field, value));
        }
    }
    private static Object listeners(Game game) throws ReflectiveOperationException {
        var events = Game.class.getDeclaredField("events"); events.setAccessible(true);
        Object bus = events.get(game);
        var registry = bus.getClass().getDeclaredField("subscribers"); registry.setAccessible(true);
        Object holder = registry.get(bus);
        var subscribers = holder.getClass().getDeclaredField("subscribers"); subscribers.setAccessible(true);
        Map<?, ?> original = (Map<?, ?>)subscribers.get(holder);
        Map<Object, Object> copy = new java.util.HashMap<>();
        for (var entry : original.entrySet()) copy.put(entry.getKey(), Set.copyOf((java.util.Collection<?>)entry.getValue()));
        return copy;
    }
    private static Object nativeSnapshot(Player player) throws ReflectiveOperationException {
        var m=CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot",Player.class);m.setAccessible(true);return m.invoke(null,player);
    }
    private static void actualPlan(Player player, String key, int step) {
        if (!(player.getController() instanceof forge.ai.CubeComboPlayerController)) return;
        try {
            var field = forge.ai.CubeComboPlayerController.class.getDeclaredField("doomsdayPlan"); field.setAccessible(true);
            Object plan = field.get(player.getController()); List<SavedField> saved = new ArrayList<>(); savePlanner(plan, saved);
            Object before = nativeSnapshot(player), beforeListeners = listeners(player.getGame()); String first = null;
            for (int i = 0; i < 3; i++) {
                String receipt;
                try {
                    var action = (forge.game.spellability.SpellAbility)plan.getClass().getMethod("nextAction").invoke(plan);
                    receipt = action == null ? "none" : action.getHostCard().getId() + ":" + action.getApi() + ":" + action.getTargets();
                } finally {
                    for (var value : saved) value.field().set(value.owner(), value.value());
                }
                if (first == null) first = receipt;
                else if (!first.equals(receipt)) throw new AssertionError("actual plan query drift " + key);
                if (!before.equals(nativeSnapshot(player)) || !beforeListeners.equals(listeners(player.getGame())))
                    throw new AssertionError("actual plan query changed native state/RNG/listeners " + key);
            }
            System.out.println("DOOM_STAR_PLAN_QUERY " + key + " step=" + step + " repeats=3 unchanged=true listenersUnchanged=true action=" + first);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void ownership(Player player,String key,int step) {
        if(!(player.getController() instanceof forge.ai.CubeComboPlayerController controller))return;
        try {
            var parent=controller.doomsdayPlan();var childField=parent.getClass().getDeclaredField("starPlan");childField.setAccessible(true);Object child=childField.get(parent);
            var pendingField=child.getClass().getDeclaredField("pendingDoom");pendingField.setAccessible(true);SpellAbility pending=(SpellAbility)pendingField.get(child);
            if(pending==null)return;
            if(player.getGame().getStack().isEmpty()){
                Object before=nativeSnapshot(player);boolean accepted=parent.ownsPileDecision(pending);
                if(accepted||!before.equals(nativeSnapshot(player)))throw new AssertionError("empty-stack search authorization");
                System.out.println("DOOM_STAR_EMPTY_STACK "+key+" accepted=false unchanged=true");return;
            }
            if(player.getGame().getStack().peekAbility()!=pending)return;
            Object before=nativeSnapshot(player),beforeListeners=listeners(player.getGame());int aliases=0,accepted=0,foreign=0;boolean genuine=true;
            for(SpellAbility source=pending;source!=null;source=source.getSubAbility()) {
                genuine&=parent.ownsPileDecision(source);
                if(source instanceof forge.game.spellability.AbilitySub sub){
                    var alias=(forge.game.spellability.AbilitySub)source.copy(player);alias.setParent(sub.getParent());aliases++;if(parent.ownsPileDecision(alias))accepted++;
                    alias.setActivatingPlayer(player.getOpponents().get(0));if(parent.ownsPileDecision(alias))foreign++;
                }
            }
            boolean detached=parent.ownsPileDecision(pending.copy(player));
            if(!before.equals(nativeSnapshot(player))||!beforeListeners.equals(listeners(player.getGame())))throw new AssertionError("ownership query native mutation");
            System.out.println("DOOM_STAR_OWNERSHIP "+key+" step="+step+" genuine="+genuine+" aliases="+aliases+" acceptedAliases="+accepted+" foreign="+foreign+" detached="+detached+" unchanged=true");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void observe(Player player,String key,int step){
        if(!player.getGame().getStack().isEmpty()||!player.getGame().getPhaseHandler().is(PhaseType.MAIN1,player))return;
        actualPlan(player,key,step);
        try{var m=CubeTopTutorAvailabilitySmoke.class.getDeclaredMethod("snapshot",Player.class);m.setAccessible(true);Object before=m.invoke(null,player);String first=null;
            for(int i=0;i<3;i++){List<String> rows=new ArrayList<>();for(ZoneType zone:List.of(ZoneType.Hand,ZoneType.Battlefield))for(Card c:player.getCardsIn(zone))if(Set.of("Chromatic Star","Doomsday","Thassa's Oracle").contains(c.getName()))for(var original:c.getSpellAbilities()){var a=original.copy(player);rows.add(c.getId()+":"+a.getApi()+":"+forge.ai.CubeComboAi.canPlayNative(a,player)+":"+forge.ai.CubeComboAi.canPayCost(a,player,false));}String receipt=rows.toString();if(first==null)first=receipt;else if(!first.equals(receipt))throw new AssertionError("query drift");if(!before.equals(m.invoke(null,player)))throw new AssertionError("native/RNG mutation");}
            System.out.println("DOOM_STAR_QUERY "+key+" step="+step+" repeats=3 unchanged=true");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void run(String arm,boolean observed,int seat,String control){
        List<RegisteredPlayer> entries=new ArrayList<>();for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat?(arm.equals("scripted")?new ScriptLobby(s):new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s)):new forge.ai.LobbyPlayerAi("Default-"+s,null);entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);Game game=new Match(rules,entries,"Doom Star diagnostic").createGame();Player p=game.getPlayers().get(seat),op=game.getPlayers().get(1-seat);p.setLife(5,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);game.setAge(GameStage.Play);int start=seat==0?1:2;game.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(989300L+seat*100L+CASES.indexOf(control));String key="arm="+arm+" seat="+seat+" case="+control;if(p.getController() instanceof ScriptController c)c.key=key;
        if(p.getManaPool().totalMana()!=0)throw new AssertionError("initial mana");
        System.out.println("DOOM_STAR_FIXTURE "+key+" registered=40 initialMana=0 initialLife=5 observed="+observed+" policy="+forge.ai.CubeComboAi.VERSION);
        int steps=0;Set<Integer> seen=new HashSet<>();while(!game.isGameOver()&&game.getPhaseHandler().getTurn()<=start&&steps<1000){if(observed){ownership(p,key,steps);observe(p,key,steps);}game.getPhaseHandler().mainLoopStep();steps++;for(var entry:game.getStack())if(seen.add(entry.getId())){var a=entry.getSpellAbility();if(a.getActivatingPlayer()==p)System.out.println("DOOM_STAR_STACK "+key+" step="+steps+" source="+a.getHostCard().getName().replace(' ','_')+" api="+a.getApi()+" spell="+a.isSpell()+" copied="+a.isCopied());}}
        if(steps>=1000)throw new AssertionError("step cap");System.out.println("DOOM_STAR_RESULT "+key+" won="+p.hasWon()+" gameOver="+game.isGameOver()+" steps="+steps+" life="+p.getLife()+" library="+p.getCardsIn(ZoneType.Library).size()+" oracleInPlay="+p.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals("Thassa's Oracle"))+" scriptPlays="+(p.getController() instanceof ScriptController c?c.plays:0)+" searches="+(p.getController() instanceof ScriptController c?c.searchChoices:0));
    }
    public static void main(String[] args){try{GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"doom-star-observation-v1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],args[2].equals("observed"),seat,c);System.out.println("DOOM_STAR_SUITE_COMPLETE cases=12");}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
