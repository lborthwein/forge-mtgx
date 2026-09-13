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
    private static final List<ZoneType> ZONES = List.of(ZoneType.Battlefield, ZoneType.Hand, ZoneType.Library, ZoneType.Graveyard, ZoneType.Exile);
    private record Placement(String name, ZoneType zone, boolean tapped) { }
    private static void add(List<Placement> into, int count, String name, ZoneType zone) {
        for (int i=0;i<count;i++) into.add(new Placement(name,zone,false));
    }
    private static List<Placement> own(String name) {
        String[] key=name.split(":"); int partner=Integer.parseInt(key[0]), artifact=Integer.parseInt(key[1]); String control=key[2];
        List<Placement> result=new ArrayList<>();
        if (!control.equals("no-emry")) add(result,1,EMRY,ZoneType.Battlefield);
        if (!control.equals("no-kitten")) add(result,1,KITTEN,ZoneType.Battlefield);
        if (!control.equals("no-partner")) add(result,1,PARTNERS.get(partner),ZoneType.Battlefield);
        if (!control.equals("no-outlet")) add(result,1,"Aetherflux Reservoir",ZoneType.Battlefield);
        add(result,3,"Island",ZoneType.Battlefield); add(result,1,ARTIFACTS.get(artifact),ZoneType.Graveyard);
        add(result,1,"Forest",ZoneType.Hand); add(result,20,"Forest",ZoneType.Library);
        add(result,40-result.size(),"Forest",ZoneType.Exile); return result;
    }
    private static List<Placement> other(String name) {
        String control=name.split(":")[2]; List<Placement> result=new ArrayList<>();
        if (control.equals("null-rod")) add(result,1,"Null Rod",ZoneType.Battlefield);
        if (control.equals("rest-in-peace")) add(result,1,"Rest in Peace",ZoneType.Battlefield);
        if (control.equals("cursed-totem")) add(result,1,"Cursed Totem",ZoneType.Battlefield);
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
            card.setSickness(false);
            if (p.tapped()) card.setTapped(true);
        }
        TreeMap<String, Integer> actual = new TreeMap<>(), registered = new TreeMap<>();
        for (ZoneType z : ZONES) for (Card c : player.getCardsIn(z)) actual.merge(c.getName(), 1, Integer::sum);
        for (var c : player.getRegisteredPlayer().getDeck().getMain()) registered.merge(c.getKey().getName(), c.getValue(), Integer::sum);
        if (!actual.equals(registered) || actual.values().stream().mapToInt(Integer::intValue).sum() != 40)
            throw new AssertionError("registration mismatch " + name + " actual=" + actual + " registered=" + registered);
        if (player.getManaPool().totalMana() != 0) throw new AssertionError("initial mana must be empty");
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
        populate(player,true,name);populate(opponent,false,name);player.setLife(40,null);opponent.setLife(20,null);game.setAge(GameStage.Play);
        int startTurn=seat==0?1:2;
        game.getPhaseHandler().setupFirstTurn(seat==0?player:opponent,()->game.getPhaseHandler().devModeSet(PhaseType.MAIN1,player,startTurn));
        game.getAction().checkStateEffects(true);game.getTriggerHandler().resetActiveTriggers();BenchRandomAudit.install(990300L+100L*seat+index);
        String key="arm="+(improved?"improved":"baseline")+" seat="+seat+" case="+name;
        System.out.println("EMRY_FIXTURE "+key+" policy="+forge.ai.CubeComboAi.VERSION+" registered=40 initialMana=0 ownLife=40 opponentLife=20");
        Set<Integer> seen=new HashSet<>();int steps=0,artifactCasts=0,emryActions=0,kittenActions=0,shots=0;String previous="";
        while (!game.isGameOver() && game.getPhaseHandler().getTurn()<=startTurn+2 && steps<1500) {
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
            List<String> names=new ArrayList<>(PARTNERS);names.addAll(ARTIFACTS);names.addAll(List.of(EMRY,KITTEN,"Aetherflux Reservoir","Island","Forest","Null Rod","Rest in Peace","Cursed Totem"));
            for(String name:names)StaticData.instance().attemptToLoadCard(name);
            List<String> cases=new ArrayList<>();
            for(int p=0;p<PARTNERS.size();p++)for(int a=0;a<ARTIFACTS.size();a++)cases.add(p+":"+a+":complete");
            for(int a=0;a<ARTIFACTS.size();a++)for(String control:CONTROLS)cases.add("0:"+a+":"+control);
            if(cases.size()!=33)throw new AssertionError("case count");
            for(int i=0;i<cases.size();i++)for(int seat=0;seat<2;seat++)run(args[1].equals("improved"),seat,cases.get(i),i);
            System.out.println("EMRY_SUITE_COMPLETE cases=66");
        } catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
