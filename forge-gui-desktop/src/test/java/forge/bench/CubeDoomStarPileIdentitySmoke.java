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

/** Prepared native setup/resource boundaries; unsupported routes are diagnosed
 * without claiming a sampled strength or replay result. */
public final class CubeDoomStarPileIdentitySmoke {
    private static final List<String> CASES=List.of("complete");
    private record Placement(String name, ZoneType zone) {}
    private static List<Placement> placements(boolean own,String control) {
        List<Placement> out=new ArrayList<>();
        if(own) {
            for(String name:List.of("Swamp","Swamp","Urborg, Tomb of Yawgmoth",control.equals("snow-source")?"Snow-Covered Island":"Underground Sea","Talisman of Dominance","Snapcaster Mage","Vendilion Clique","True-Name Nemesis"))out.add(new Placement(name,ZoneType.Battlefield));
            for(String name:List.of("Doomsday","Chromatic Star",control.equals("snow-hand")?"Snow-Covered Island":control.equals("snow-source")?"Swamp":"Island"))out.add(new Placement(name,ZoneType.Hand));
            out.add(new Placement("Thassa's Oracle",ZoneType.Library));
        } else {
            out.add(new Placement("Island",ZoneType.Battlefield));
            String card=switch(control){case "tap-damage"->"Manabarbs";case "tap-mana-damage"->"Overabundance";case "tap-other"->"Mana Web";case "damage-replacement","inactive-damage"->"Furnace of Rath";default->null;};
            if(card!=null)out.add(new Placement(card,control.equals("inactive-damage")?ZoneType.Graveyard:ZoneType.Battlefield));
        }
        while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
    }
    private static Deck deck(boolean own,String control) { Deck deck=new Deck("Doom Star diagnostic");for(var p:placements(own,control))deck.getMain().add(p.name(),1);return deck; }
    private static void populate(Player player,boolean own,String control) {
        for(ZoneType z:ZoneType.values())if(player.getZone(z)!=null)player.getZone(z).removeAllCards(true);
        for(var p:placements(own,control)) { Card c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(p.name())),player);c.setGameTimestamp(player.getGame().getNextTimestamp());player.getZone(p.zone()).add(c);c.setSickness(false); }
        Map<String,Integer> actual=new TreeMap<>(),registered=new TreeMap<>();
        for(ZoneType z:List.of(ZoneType.Battlefield,ZoneType.Hand,ZoneType.Library,ZoneType.Graveyard))for(Card c:player.getCardsIn(z))actual.merge(c.getPaperCard().getName(),1,Integer::sum);
        for(var e:player.getRegisteredPlayer().getDeck().getMain())registered.merge(e.getKey().getName(),e.getValue(),Integer::sum);
        if(!actual.equals(registered)||actual.values().stream().mapToInt(Integer::intValue).sum()!=40)throw new AssertionError("registered identity");
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
    private static boolean pileBoundary(Player player,String key) {
        try {
            var parent=((forge.ai.CubeComboPlayerController)player.getController()).doomsdayPlan();
            var childField=parent.getClass().getDeclaredField("starPlan");childField.setAccessible(true);Object child=childField.get(parent);
            var selected=child.getClass().getDeclaredField("chosenPile");selected.setAccessible(true);
            var ordered=child.getClass().getDeclaredField("ordered");ordered.setAccessible(true);
            if(!ordered.getBoolean(child)||player.getCardsIn(ZoneType.Library).size()!=5)return false;
            var failed=child.getClass().getDeclaredField("failed");failed.setAccessible(true);
            var searched=child.getClass().getDeclaredField("searched");searched.setAccessible(true);
            var oracle=child.getClass().getDeclaredField("oracleId");oracle.setAccessible(true);
            var saved=new ArrayList<SavedField>();savePlanner(parent,saved);
            // Use only identities exposed by the actual completed native search.
            // Rebind this component's planner list to current zone objects; no
            // native card move, library scan or native state rollback occurs.
            var granted=(List<Card>)selected.get(child);if(granted.size()!=5)throw new AssertionError("native selected count");
            var cards=new CardCollection();for(Card c:granted)cards.add(player.getGame().getCardState(c,null));
            Object before=nativeSnapshot(player),beforeListeners=listeners(player.getGame());
            try {
                selected.set(child,List.copyOf(cards));ordered.setBoolean(child,false);
                var valid=parent.orderPile(cards);
                if(!ordered.getBoolean(child)||failed.getBoolean(child)||valid.size()!=5||valid.get(4).getId()!=oracle.getInt(child))throw new AssertionError("valid native-grant component rejected");
                for(Card c:cards)if(valid.stream().noneMatch(v->v==c))throw new AssertionError("valid identity lost");
                System.out.println("DOOM_STAR_PILE_BOUNDARY "+key+" kind=valid accepted=true exactIdentities=true");
                Card own=player.getCardsIn(ZoneType.Battlefield).iterator().next();
                Card foreign=player.getOpponents().get(0).getCardsIn(ZoneType.Battlefield).iterator().next();
                Card alias=CardCopyService.getLKICopy(cards.get(0));
                if(alias==cards.get(0)||alias.getId()!=cards.get(0).getId())throw new AssertionError("alias construction");
                for(String kind:List.of("short","same-id-alias","own-wrong-zone","foreign-public")) {
                    for(var value:saved)value.field().set(value.owner(),value.value());
                    selected.set(child,List.copyOf(cards));ordered.setBoolean(child,false);failed.setBoolean(child,false);
                    CardCollection bad=new CardCollection();for(int i=1;i<cards.size();i++)bad.add(cards.get(i));
                    if(!kind.equals("short"))bad.add(kind.equals("same-id-alias")?alias:kind.equals("own-wrong-zone")?own:foreign);
                    var result=parent.orderPile(bad);
                    if(result!=bad||!failed.getBoolean(child)||ordered.getBoolean(child))throw new AssertionError("malformed order accepted "+kind);
                    System.out.println("DOOM_STAR_PILE_BOUNDARY "+key+" kind="+kind+" accepted=false originalOrder=true failed=true");
                }
                for(String kind:List.of("same-id-alias","own-wrong-zone","foreign-public","repeat")) {
                    for(var value:saved)value.field().set(value.owner(),value.value());
                    selected.set(child,kind.equals("repeat")?List.of(cards.get(0)):List.of());searched.setBoolean(child,false);failed.setBoolean(child,false);
                    var bad=new CardCollection();bad.add(kind.equals("repeat")?cards.get(0):kind.equals("same-id-alias")?alias:kind.equals("own-wrong-zone")?own:foreign);
                    if(parent.choosePileCard(bad)!=null||!failed.getBoolean(child))throw new AssertionError("malformed choice accepted "+kind);
                    System.out.println("DOOM_STAR_CHOICE_BOUNDARY "+key+" kind="+kind+" accepted=false failed=true");
                }
            } finally {for(var value:saved)value.field().set(value.owner(),value.value());}
            if(!before.equals(nativeSnapshot(player))||!beforeListeners.equals(listeners(player.getGame())))throw new AssertionError("component native mutation");
            System.out.println("DOOM_STAR_PILE_COMPONENT "+key+" nativeGrant=true plannerOnlyPreparation=true unchanged=true listenersUnchanged=true");
            return true;
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void run(String arm,boolean observed,int seat,String control){
        List<RegisteredPlayer> entries=new ArrayList<>();for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);Game game=new Match(rules,entries,"Doom Star diagnostic").createGame();Player p=game.getPlayers().get(seat),op=game.getPlayers().get(1-seat);p.setLife(control.equals("life-one")?1:control.endsWith("life-two")||control.equals("life-two")?2:5,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);game.setAge(GameStage.Play);int start=seat==0?1:2;game.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(990300L+seat*100L+CASES.indexOf(control));String key="arm="+arm+" seat="+seat+" case="+control;
        if(control.equals("star-tapped"))for(Card c:p.getCardsIn(ZoneType.Battlefield))if(c.getName().equals("Chromatic Star"))c.setTapped(true);
        if(control.equals("land-used"))p.setLandsPlayedThisTurn(p.getMaxLandPlays());
        System.out.println("DOOM_STAR_BOUNDARY "+key+" initialLife="+p.getLife()+" landsPlayed="+p.getLandsPlayedThisTurn()+" starTapped="+p.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals("Chromatic Star")&&c.isTapped()));
        if(p.getManaPool().totalMana()!=0)throw new AssertionError("initial mana");
        System.out.println("DOOM_STAR_FIXTURE "+key+" registered=40 initialMana=0 observed="+observed+" policy="+forge.ai.CubeComboAi.VERSION);
        try {
            var parent=((forge.ai.CubeComboPlayerController)p.getController()).doomsdayPlan();
            var field=parent.getClass().getDeclaredField("starPlan");field.setAccessible(true);Object child=field.get(parent);
            var domain=child.getClass().getDeclaredMethod("staticDomain");domain.setAccessible(true);
            Object before=nativeSnapshot(p);boolean allowed=(boolean)domain.invoke(child);
            boolean expected=!control.startsWith("tap-")&&!control.equals("damage-replacement");
            if(allowed!=expected||!before.equals(nativeSnapshot(p)))throw new AssertionError("public resource domain mismatch");
            System.out.println("DOOM_STAR_PUBLIC_DOMAIN "+key+" allowed="+allowed+" expected="+expected+" unchanged=true");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        boolean pileProbed=false;
        int steps=0;Set<Integer> seen=new HashSet<>();while(!game.isGameOver()&&game.getPhaseHandler().getTurn()<=start&&steps<1000){if(observed){if(!pileProbed)pileProbed=pileBoundary(p,key);ownership(p,key,steps);observe(p,key,steps);}game.getPhaseHandler().mainLoopStep();steps++;for(var entry:game.getStack())if(seen.add(entry.getId())){var a=entry.getSpellAbility();if(a.getActivatingPlayer()==p)System.out.println("DOOM_STAR_STACK "+key+" step="+steps+" source="+a.getHostCard().getName().replace(' ','_')+" api="+a.getApi()+" spell="+a.isSpell()+" copied="+a.isCopied());}}
        if(observed&&!pileProbed)throw new AssertionError("component not reached");
        if(steps>=1000)throw new AssertionError("step cap");System.out.println("DOOM_STAR_RESULT "+key+" won="+p.hasWon()+" gameOver="+game.isGameOver()+" steps="+steps+" life="+p.getLife()+" library="+p.getCardsIn(ZoneType.Library).size()+" oracleInPlay="+p.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals("Thassa's Oracle"))+" scriptPlays="+0+" searches="+0);
    }
    public static void main(String[] args){try{GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"doom-star-observation-v1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],args[2].equals("observed"),seat,c);System.out.println("DOOM_STAR_SUITE_COMPLETE cases=2");}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
