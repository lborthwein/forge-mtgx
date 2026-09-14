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
public final class CubeDoomStarFloatingSmoke {
    private static final List<String> CASES=List.of("float-black","float-bbb","float-blue","float-mixed","float-colorless","float-all","float-short","float-restricted");
    private record Placement(String name,ZoneType zone) {}
    private static List<Placement> placements(boolean own,String control) {
        List<Placement> out=new ArrayList<>();
        if(own){
            for(String name:List.of("Swamp","Swamp","Urborg, Tomb of Yawgmoth",control.equals("float-restricted")?"Ancient Ziggurat":"Underground Sea","Talisman of Dominance","Snapcaster Mage","Vendilion Clique","True-Name Nemesis"))out.add(new Placement(name,ZoneType.Battlefield));
            for(String name:List.of("Doomsday","Chromatic Star"))out.add(new Placement(name,ZoneType.Hand));
            if(control.equals("float-all"))out.add(new Placement("Mountain",ZoneType.Battlefield));
            else if(!control.equals("float-short"))out.add(new Placement("Island",ZoneType.Hand));
            out.add(new Placement("Thassa's Oracle",ZoneType.Library));
        }
        while(out.size()<40)out.add(new Placement("Forest",ZoneType.Library));return out;
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
            for(int i=0;i<3;i++){List<String> rows=new ArrayList<>();for(ZoneType zone:List.of(ZoneType.Hand,ZoneType.Battlefield))for(Card c:player.getCardsIn(zone))if(Set.of("Chromatic Star","Doomsday","Thassa's Oracle").contains(c.getName()))for(var original:c.getSpellAbilities()){var a=original.copy(player);rows.add(c.getId()+":"+a.getApi()+":"+forge.ai.CubeComboAi.canPlayNative(a,player)+":"+forge.ai.CubeComboAi.canPayCost(a,player,false));}String receipt=rows.toString();if(first==null)first=receipt;else if(!first.equals(receipt))throw new AssertionError("query drift");if(!before.equals(m.invoke(null,player))){Object after=m.invoke(null,player);var old=(java.util.Map<?,?>)before;var now=(java.util.Map<?,?>)after;for(var field:old.keySet())if(!java.util.Objects.equals(old.get(field),now.get(field)))System.out.println("DOOM_STAR_QUERY_DIFF "+key+" field="+field+" before="+old.get(field)+" after="+now.get(field));throw new AssertionError("native/RNG mutation");}}
            System.out.println("DOOM_STAR_QUERY "+key+" step="+step+" repeats=3 unchanged=true");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    public static final class PoolEvents {
        int count;
        @com.google.common.eventbus.Subscribe
        public void mana(forge.game.event.GameEventManaPool event) { count++; }
    }
    private static void exactPool(Player player, Object container, List<forge.game.mana.Mana> tokens) {
        try {
            var field=forge.game.mana.ManaPool.class.getDeclaredField("floatingMana");field.setAccessible(true);
            var current=java.util.stream.StreamSupport.stream(player.getManaPool().spliterator(),false).toList();
            if(field.get(player.getManaPool())!=container||current.size()!=tokens.size())throw new AssertionError("pool container/count changed");
            for(int i=0;i<tokens.size();i++)if(tokens.get(i)!=current.get(i))throw new AssertionError("pool token identity/order changed");
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static PoolEvents poolBoundary(Player player,String key) {
        try {
            var field=forge.game.mana.ManaPool.class.getDeclaredField("floatingMana");field.setAccessible(true);
            var pool=player.getManaPool();Object original=field.get(pool),before=nativeSnapshot(player);
            var tokens=java.util.stream.StreamSupport.stream(pool.spliterator(),false).toList();
            if(tokens.isEmpty())throw new AssertionError("declared pool required");
            PoolEvents events=new PoolEvents();player.getGame().subscribeToEvents(events);
            RuntimeException sentinel=new RuntimeException("registered probe unwind");
            forge.ai.CubeComboAi.probePayment(player,()->{
                if(!pool.removeMana(tokens.get(0)))throw new AssertionError("probe did not remove token");
                pool.addMana(tokens.get(0));
                Object outer;
                try{outer=field.get(pool);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
                var outerTokens=java.util.stream.StreamSupport.stream(pool.spliterator(),false).toList();
                try{forge.ai.CubeComboAi.probePayment(player,()->{pool.removeMana(tokens.get(0));throw sentinel;});throw new AssertionError("exception lost");}
                catch(RuntimeException e){if(e!=sentinel)throw e;}
                exactPool(player,outer,outerTokens);
                if(!forge.ai.CubeComboAi.isPaymentProbeFor(player))throw new AssertionError("outer probe flag lost");
                return true;
            });
            exactPool(player,original,tokens);
            try{forge.ai.CubeComboAi.probePayment(player,()->{pool.removeMana(tokens.get(0));throw sentinel;});throw new AssertionError("outer exception lost");}
            catch(RuntimeException e){if(e!=sentinel)throw e;}
            exactPool(player,original,tokens);
            if(forge.ai.CubeComboAi.isPaymentProbeFor(player)||events.count!=0||!before.equals(nativeSnapshot(player)))throw new AssertionError("probe unwind leaked state/events");
            System.out.println("DOOM_STAR_POOL_BOUNDARY "+key+" nested=true exceptional=true container=true tokenIdentity=true order=true unchanged=true events=0");
            return events;
        }catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void run(String arm,boolean observed,int seat,String control){
        List<RegisteredPlayer> entries=new ArrayList<>();for(int s=0;s<2;s++){forge.LobbyPlayer lobby=s==seat?new forge.ai.LobbyPlayerCubeComboAi("Candidate-"+s):new forge.ai.LobbyPlayerAi("Default-"+s,null);entries.add(new RegisteredPlayer(deck(s==seat,control)).setPlayer(lobby));}
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);Game game=new Match(rules,entries,"Doom Star diagnostic").createGame();Player p=game.getPlayers().get(seat),op=game.getPlayers().get(1-seat);p.setLife(control.equals("life-one")?1:control.endsWith("life-two")||control.equals("life-two")?2:5,null);op.setLife(20,null);populate(p,true,control);populate(op,false,control);game.setAge(GameStage.Play);int start=seat==0?1:2;game.getPhaseHandler().setupFirstTurn(seat==0?p:op,()->game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p,start));game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(989900L+seat*100L+CASES.indexOf(control));String key="arm="+arm+" seat="+seat+" case="+control;
        // Declared prepared initial state: each floating unit names its own
        // registered, tapped native producer. Production itself is not evidence
        // of autonomous play; all spending after this point is native.
        List<String> colors=control.equals("float-bbb")?List.of("B","B","B")
                :control.equals("float-mixed")?List.of("B","U")
                :control.equals("float-all")?List.of("B","B","B","C","U","R")
                :control.equals("float-colorless")?List.of("C")
                :control.equals("float-blue")||control.equals("float-restricted")?List.of("U"):List.of("B");
        for(String color:colors){Card source=null;SpellAbility ability=null;
            producer: for(Card card:p.getCardsIn(ZoneType.Battlefield))if(card.isUntapped())for(var original:card.getManaAbilities()){
                var a=original.copy(p);if(a.getManaPart()!=null&&a.canProduce(color)&&a.getSubAbility()==null){source=card;ability=a;break producer;}
            }
            if(source==null)throw new AssertionError("missing declared floating producer");source.setTapped(true);
            byte mask=switch(color){case "B"->forge.card.MagicColor.BLACK;case "U"->forge.card.MagicColor.BLUE;case "R"->forge.card.MagicColor.RED;default->(byte)forge.card.mana.ManaAtom.COLORLESS;};
            p.getManaPool().addMana(new forge.game.mana.Mana(mask,source,ability.getManaPart(),p));
        }
        if(control.equals("float-all"))p.setLandsPlayedThisTurn(1);
        if(p.getManaPool().totalMana()!=colors.size())throw new AssertionError("prepared floating count");
        System.out.println("DOOM_STAR_FLOATING "+key+" declared=true initialLife="+p.getLife()+" mana="+colors.toString().replace(" ","")+" count="+colors.size()+" landsPlayed="+p.getLandsPlayedThisTurn());
        System.out.println("DOOM_STAR_FIXTURE "+key+" registered=40 observed="+observed+" policy="+forge.ai.CubeComboAi.VERSION);
        PoolEvents poolEvents=observed?poolBoundary(p,key):null;
        int steps=0;Set<Integer> seen=new HashSet<>();while(!game.isGameOver()&&game.getPhaseHandler().getTurn()<=start&&steps<1000){if(observed){ownership(p,key,steps);observe(p,key,steps);}game.getPhaseHandler().mainLoopStep();steps++;for(var entry:game.getStack())if(seen.add(entry.getId())){var a=entry.getSpellAbility();if(a.getActivatingPlayer()==p)System.out.println("DOOM_STAR_STACK "+key+" step="+steps+" source="+a.getHostCard().getName().replace(' ','_')+" api="+a.getApi()+" spell="+a.isSpell()+" copied="+a.isCopied());}}
        if(poolEvents!=null){if(p.hasWon()&&poolEvents.count==0)throw new AssertionError("actual payment events suppressed");System.out.println("DOOM_STAR_REAL_POOL_EVENTS "+key+" count="+poolEvents.count+" won="+p.hasWon());}
        if(steps>=1000)throw new AssertionError("step cap");System.out.println("DOOM_STAR_RESULT "+key+" won="+p.hasWon()+" gameOver="+game.isGameOver()+" steps="+steps+" life="+p.getLife()+" library="+p.getCardsIn(ZoneType.Library).size()+" oracleInPlay="+p.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals("Thassa's Oracle"))+" scriptPlays="+0+" searches="+0);
    }
    public static void main(String[] args){try{GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"doom-star-observation-v1";default->throw new AssertionError("Unexpected GUI call "+method.getName());}));FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});Set<String> names=new LinkedHashSet<>();for(String c:CASES)for(boolean own:List.of(true,false))for(var p:placements(own,c))names.add(p.name());for(String name:names)StaticData.instance().attemptToLoadCard(name);for(String c:CASES)for(int seat=0;seat<2;seat++)run(args[1],args[2].equals("observed"),seat,c);System.out.println("DOOM_STAR_SUITE_COMPLETE cases=16");}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
