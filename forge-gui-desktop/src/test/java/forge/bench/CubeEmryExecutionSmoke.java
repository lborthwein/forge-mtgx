package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Observation-only native Emry recurrence diagnosis; no forced actions. */
public final class CubeEmryExecutionSmoke {
    private static final String EMRY = "Emry, Lurker of the Loch", KITTEN = "Displacer Kitten";
    private static final List<String> PARTNERS = List.of("Lightning Greaves", "Pestermite", "Deceiver Exarch", "Zealous Conscripts");
    private static final List<String> ARTIFACTS = List.of("Lotus Petal", "Mishra's Bauble", "Lion's Eye Diamond");
    private static final List<String> CONTROLS = List.of("no-emry", "no-kitten", "no-partner", "no-outlet", "null-rod", "rest-in-peace", "cursed-totem");
    private static final List<String> BOUNDARIES = List.of("life50", "life51", "short-library", "empty-library", "held-counterspell", "held-oracle", "rule-of-law", "jailer", "tainted-remedy", "emry-sick", "emry-tapped", "prevent-damage");
    private static final List<String> BATTLEFIELD_CASES = List.of("complete", "emry-tapped", "emry-sick", "cursed-totem", "null-rod", "rest-in-peace", "rule-of-law", "no-partner");
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone, boolean tapped) { }
    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i=0;i<count;i++) into.add(new Placement(name,zone,false));
    }
    private static List<Placement> own(String name) {
        String[] key=name.split(":"); int partner=Integer.parseInt(key[0]), artifact=Integer.parseInt(key[1]); String control=key[2].replaceFirst("^bf-", "");
        List<Placement> result=new ArrayList<>();
        if (!control.equals("no-emry")) add(result,1,EMRY,ZoneType.Battlefield);
        if (!control.equals("no-kitten")) add(result,1,KITTEN,ZoneType.Battlefield);
        if (!control.equals("no-partner")) add(result,1,PARTNERS.get(partner),ZoneType.Battlefield);
        if (!control.equals("no-outlet")) add(result,1,"Aetherflux Reservoir",ZoneType.Battlefield);
        add(result,3,"Island",ZoneType.Battlefield); add(result,1,ARTIFACTS.get(artifact),key[2].startsWith("bf-")?ZoneType.Battlefield:ZoneType.Graveyard);
        add(result,1,control.equals("held-counterspell")?"Counterspell":control.equals("held-oracle")?"Thassa's Oracle":"Forest",ZoneType.Hand);
        add(result,control.equals("empty-library")?0:control.equals("short-library")?1:20,"Forest",ZoneType.Library);
        add(result,40-result.size(),"Forest",ZoneType.Exile); return result;
    }
    private static List<Placement> other(String name) {
        String control=name.split(":")[2].replaceFirst("^bf-", ""); List<Placement> result=new ArrayList<>();
        if (control.equals("null-rod")) add(result,1,"Null Rod",ZoneType.Battlefield);
        if (control.equals("rest-in-peace")) add(result,1,"Rest in Peace",ZoneType.Battlefield);
        if (control.equals("cursed-totem")) add(result,1,"Cursed Totem",ZoneType.Battlefield);
        if (control.equals("rule-of-law")) add(result,1,"Rule of Law",ZoneType.Battlefield);
        if (control.equals("jailer")) add(result,1,"Soulless Jailer",ZoneType.Battlefield);
        if (control.equals("tainted-remedy")) add(result,1,"Tainted Remedy",ZoneType.Battlefield);
        if (control.equals("prevent-damage")) add(result,1,"Glacial Chasm",ZoneType.Battlefield);
        add(result,40-result.size(),"Forest",ZoneType.Library); return result;
    }
    private static List<Placement> placements(boolean owner,String name) {return owner?own(name):other(name);}
    private static Deck deck(boolean owner, String name) {
        Deck result = new Deck("emry native fixture");
        for (Placement p : placements(owner, name)) result.getMain().add(p.name(), 1);
        return result;
    }

    private static void populate(Player player, boolean owner, String name) {
        for (ZoneType z : ZoneType.values()) if (player.getZone(z) != null) player.getZone(z).removeAllCards(true);
        for (Placement p : placements(owner, name)) {
            Card card = Card.fromPaperCard(Objects.requireNonNull(
                    FModel.getMagicDb().getCommonCards().getCard(p.name()), p.name()), player);
            card.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(p.zone()).add(card);
            card.setSickness(owner && name.endsWith("emry-sick") && p.name().equals(EMRY));
            if (owner && name.endsWith("emry-tapped") && p.name().equals(EMRY)) card.setTapped(true);
            if (p.tapped()) card.setTapped(true);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch " + name + " actual=" + actual + " registered=" + registered);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
    }


    private static Map<String, Object> snapshot(Player player) {
        Map<String, Object> state = new LinkedHashMap<>(); Game game = player.getGame();
        state.put("timestamp", game.getTimestamp());
        state.put("rng", ((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().toString());
        state.put("mana", java.util.stream.StreamSupport.stream(player.getManaPool().spliterator(), false).toList());
        state.put("conversion", java.util.stream.IntStream.range(0, 6)
                .map(i -> player.getManaPool().getPossibleColorUses((byte)(1 << i))).boxed().toList());
        state.put("snow", player.getManaPool().isSnowForColor());
        for (var memory : forge.ai.AiCardMemory.MemorySet.values())
            state.put(memory.name(), forge.ai.AiCardMemory.getMemorySet(player, memory).stream().map(Card::getId).sorted().toList());
        for (ZoneType zone : new ZoneType[]{ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile}) {
            state.put(zone.name(), player.getCardsIn(zone).stream().map(c -> c.getId() + ":" + c.getGameTimestamp()
                    + ":" + c.isTapped() + ":" + c.isSick() + ":" + c.getAttachedTo() + ":" + c.getView().isTapped() + ":" + c.getPlaneswalkerAbilityActivated() + ":" + c.getCounters() + ":" + c.getCastFrom() + ":" + c.getCastSA()).toList());
            state.put(zone.name() + "Abilities", player.getCardsIn(zone).stream().flatMap(c -> c.getSpellAbilities().stream())
                    .map(sa -> sa.getHostCard().getId() + ":" + sa.getActivatingPlayer() + ":" + sa.getTargets() + ":" + System.identityHashCode(sa.getTargets())
                            + ":" + (sa.getManaPart() == null ? "null" : sa.getManaPart().getExpressChoice())).toList());
        }
        state.put("librarySize", player.getCardsIn(ZoneType.Library).size());
        state.put("history", game.getStack().getSpellCardsCastThisTurn().stream().map(c -> c.getId() + ":" + c.getCastFrom()).toList());
        for (Player p : game.getPlayers()) {
            state.put("public-" + p.getId(), p.getLife() + ":" + p.getPreventNextDamageTotalShields());
            state.put("command-" + p.getId(), p.getCardsIn(ZoneType.Command).stream()
                    .map(c -> c.getId() + ":" + c.getGameTimestamp() + ":" + c.getSVars() + ":" + c.getReplacementEffects()).toList());
        }
        return state;
    }
    private static String available(Player player) {
        List<String> rows=new ArrayList<>();
        for (ZoneType zone:List.of(ZoneType.Hand,ZoneType.Graveyard,ZoneType.Battlefield)) for(Card card:player.getCardsIn(zone)) {
            if(card.isFaceDown() || !(ARTIFACTS.contains(card.getName()) || card.getName().equals(EMRY) || card.getName().equals("Lightning Greaves")))continue;
            String prefix=card.getId()+":"+card.getName().replace(' ','_')+":"+zone+":tapped="+card.isTapped()+":sick="+card.isSick()+":shroud="+card.hasKeyword(forge.game.keyword.Keyword.SHROUD)+":attached="+(card.getAttachedTo()==null?"none":card.getAttachedTo().getId());
            rows.add(prefix);
            for(var original:card.getAllPossibleAbilities(player,false,null,true)) {
                var ability=original.copy(player);
                boolean legal=forge.ai.CubeComboAi.canPlayNative(ability,player);
                boolean pay=ability.getPayCosts()!=null && forge.ai.CubeComboAi.canPayCost(ability,player,false);
                List<String> targets=new ArrayList<>();
                if(ability.usesTargeting()) {
                    if(ability.canTarget(player))targets.add("self");
                    for(Card target:player.getCardsIn(ZoneType.Battlefield))
                        if(!target.isFaceDown() && (target.getName().equals(EMRY)||target.getName().equals(KITTEN)) && ability.canTarget(target))targets.add("own-"+target.getId());
                    for(Card target:player.getCardsIn(ZoneType.Graveyard))
                        if(!target.isFaceDown() && ARTIFACTS.contains(target.getName()) && ability.canTarget(target))targets.add("own-"+target.getId());
                }
                rows.add(prefix+":api="+ability.getApi()+":spell="+ability.isSpell()+":legal="+legal+":pay="+pay+":targets="+targets);
            }
        }
        return String.join(";",rows);
    }
    private static void observePlan(Player player, String key, int step) {
        if (!(player.getController() instanceof forge.ai.CubeComboPlayerController)) return;
        try {
            var field = forge.ai.CubeComboPlayerController.class.getDeclaredField("emryPlan"); field.setAccessible(true);
            Object plan = field.get(player.getController());
            var next = plan.getClass().getDeclaredMethod("nextAction"); next.setAccessible(true);
            Map<java.lang.reflect.Field,Object> saved = new LinkedHashMap<>();
            for (var f : plan.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || java.lang.reflect.Modifier.isFinal(f.getModifiers())) continue;
                f.setAccessible(true); saved.put(f,f.get(plan));
            }
            var before = snapshot(player); String first = null;
            try {
                for (int i=0;i<3;i++) {
                    for (var entry:saved.entrySet()) entry.getKey().set(plan,entry.getValue());
                    var action = (forge.game.spellability.SpellAbility)next.invoke(plan);
                    String receipt = action == null ? "none" : action.getHostCard().getId()+":"+action.getApi()+":"+action.getTargets()
                            +":"+(action.getManaPart()==null?"none":action.getManaPart().getExpressChoice());
                    if (first==null) first=receipt; else if (!first.equals(receipt)) throw new AssertionError("plan query drift "+key);
                    if (!before.equals(snapshot(player))) throw new AssertionError("plan query mutated native state/RNG "+key+" step="+step);
                }
            } finally {
                // These are dry queries of the real controller-owned planner.
                // Restore its own selection/ownership counters before gameplay.
                for (var entry:saved.entrySet()) entry.getKey().set(plan,entry.getValue());
            }
            System.out.println("EMRY_PLAN_QUERY "+key+" step="+step+" repeats=3 unchanged=true action="+first);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static String observe(Player player,String key,int step,String previous) {
        Game game=player.getGame();
        if(!game.getStack().isEmpty() || game.isGameOver() || !game.getPhaseHandler().is(PhaseType.MAIN1,player) && !game.getPhaseHandler().is(PhaseType.MAIN2,player))return previous;
        observePlan(player,key,step);
        var before=snapshot(player);String first=null;
        for(int i=0;i<3;i++) {
            String receipt=available(player);
            if(first==null)first=receipt;else if(!first.equals(receipt))throw new AssertionError("availability query drift "+key);
            if(!before.equals(snapshot(player)))throw new AssertionError("availability query mutated state/RNG "+key);
        }
        if(!first.equals(previous))System.out.println("EMRY_AVAILABLE "+key+" step="+step+" repeats=3 unchanged=true "+first);
        return first;
    }
    private static void run(boolean improved,int seat,String name,int index) {
        List<RegisteredPlayer> players=new ArrayList<>();
        for (int s=0;s<2;s++) {
            var stock=new forge.ai.LobbyPlayerAi("Default-"+s,null); stock.setAiProfile("Default");
            players.add(new RegisteredPlayer(deck(s==seat,name)).setPlayer(improved && s==seat ? new forge.ai.LobbyPlayerCubeComboAi("Combo-"+s) : stock));
        }
        GameRules rules=new GameRules(GameType.Constructed);rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);rules.setAllowCheatShuffle(false);
        Game game=new Match(rules,players,"native Emry recurrence diagnosis").createGame();
        Player player=game.getPlayers().get(seat),opponent=game.getPlayers().get(1-seat);
        populate(player,true,name);player.setLife(name.endsWith(":life50")?50:name.endsWith(":life51")?51:40,null);
        populate(opponent,false,name);opponent.setLife(20,null);game.setAge(GameStage.Play);
        int startTurn=seat==0?1:2;
        game.getPhaseHandler().setupFirstTurn(seat==0?player:opponent,()->game.getPhaseHandler().devModeSet(PhaseType.MAIN1,player,startTurn));
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(990300L+100L*seat+index);
        String key="arm="+(improved?"improved":"baseline")+" seat="+seat+" case="+name;
        System.out.println("EMRY_FIXTURE "+key+" policy="+forge.ai.CubeComboAi.VERSION+" registered=40 initialMana=0 ownLife="+player.getLife()+" opponentLife=20");
        Set<Integer> seen=new HashSet<>();int steps=0,artifactCasts=0,emryActions=0,kittenActions=0,shots=0;String previous="",previousAvailable="";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn()<=startTurn+2 && steps<1500) {
            previousAvailable=observe(player,key,steps,previousAvailable);
            game.getPhaseHandler().mainLoopStep();steps++;
            for (var item:game.getStack()) if (seen.add(item.getId())) {
                var sa=item.getSpellAbility();if(sa.getActivatingPlayer()!=player)continue;
                String host=sa.getHostCard().getName();
                if(sa.isSpell()&&!sa.isCopied()&&ARTIFACTS.contains(host))artifactCasts++;
                if(host.equals(EMRY))emryActions++;if(host.equals(KITTEN))kittenActions++;
                if(host.equals("Aetherflux Reservoir")&&sa.getApi()==forge.game.ability.ApiType.DealDamage)shots++;
                System.out.println("EMRY_STACK "+key+" step="+steps+" source="+host.replace(' ','_')+" api="+sa.getApi()+" spell="+sa.isSpell()+" copied="+sa.isCopied()+" sourceId="+sa.getHostCard().getId()+" castFrom="+sa.getHostCard().getCastFrom()+" targets="+sa.getTargets());
            }
            String state="turn="+game.getPhaseHandler().getTurn()+" phase="+game.getPhaseHandler().getPhase()+" library="+player.getCardsIn(ZoneType.Library).size()+" hand="+player.getCardsIn(ZoneType.Hand).size()+" graveyard="+player.getCardsIn(ZoneType.Graveyard).size()+" mana="+player.getManaPool().totalMana()+" ownLife="+player.getLife()+" opponentLife="+opponent.getLife();
            if(!state.equals(previous))System.out.println("EMRY_STATE "+key+" step="+steps+" "+state);previous=state;
        }
        if(steps>=1500)throw new AssertionError("native step budget exhausted "+key);
        System.out.println("EMRY_RESULT "+key+" won="+player.hasWon()+" gameOver="+game.isGameOver()+" steps="+steps+" artifactCasts="+artifactCasts+" emryActions="+emryActions+" kittenActions="+kittenActions+" shots="+shots+" ownLibrary="+player.getCardsIn(ZoneType.Library).size()+" ownLife="+player.getLife()+" opponentLife="+opponent.getLife());
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()) {
                case "getAssetsDir" -> args[0]+"/forge-gui/";
                case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "emry-native-diagnosis-v1";
                default -> throw new AssertionError(method.getName());
            }));
            FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            List<String> names=new ArrayList<>(PARTNERS);names.addAll(ARTIFACTS);names.addAll(List.of(EMRY,KITTEN,"Aetherflux Reservoir","Island","Forest","Null Rod","Rest in Peace","Cursed Totem","Counterspell","Thassa's Oracle","Rule of Law","Soulless Jailer","Tainted Remedy","Glacial Chasm"));
            for(String name:names)StaticData.instance().attemptToLoadCard(name);
            List<String> cases=new ArrayList<>();
            for(int p=0;p<PARTNERS.size();p++)for(int a=0;a<ARTIFACTS.size();a++)cases.add(p+":"+a+":complete");
            for(int a=0;a<ARTIFACTS.size();a++)for(String control:CONTROLS)cases.add("0:"+a+":"+control);
            for(int p=1;p<PARTNERS.size();p++)for(int a=0;a<ARTIFACTS.size();a++)for(String control:CONTROLS)cases.add(p+":"+a+":"+control);
            for(int p=0;p<PARTNERS.size();p++)for(int a=0;a<ARTIFACTS.size();a++)for(String control:BOUNDARIES)cases.add(p+":"+a+":"+control);
            for(int p=0;p<PARTNERS.size();p++)for(int a=0;a<ARTIFACTS.size();a++)for(String control:BATTLEFIELD_CASES)cases.add(p+":"+a+":bf-"+control);
            if(cases.size()!=336)throw new AssertionError("case count");
            int executed=0;
            for(int i=0;i<cases.size();i++) {
                if(args.length>2 && !cases.get(i).endsWith(":"+args[2]))continue;
                for(int seat=0;seat<2;seat++) {run(args[1].equals("improved"),seat,cases.get(i),i);executed++;}
            }
            System.out.println("EMRY_SUITE_COMPLETE cases="+executed);
        } catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
