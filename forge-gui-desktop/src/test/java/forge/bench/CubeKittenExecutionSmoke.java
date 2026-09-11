package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.lang.reflect.Proxy;
import java.util.*;

/** Finite native assembled Kitten/Teferi games. No host policy answers. */
public final class CubeKittenExecutionSmoke {
    private record Entry(String name, ZoneType zone) {}
    private static final Set<String> loaded = new HashSet<>();
    private static boolean oracleFront;
    private static forge.item.PaperCard paper(String name) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name),name);
    }
    private static List<Entry> layout(String rock, boolean hand, boolean hiddenOracle, String control) {
        List<Entry> cards = new ArrayList<>();
        cards.add(new Entry(control.equals("no-kitten") ? "Forest" : "Displacer Kitten",ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-teferi") ? "Forest" : "Teferi, Time Raveler",ZoneType.Battlefield));
        cards.add(new Entry(rock,hand ? ZoneType.Hand : ZoneType.Battlefield));
        cards.add(new Entry("Island",ZoneType.Battlefield));
        cards.add(new Entry(control.equals("short-blue") ? "Plains" : "Island",ZoneType.Battlefield));
        cards.add(new Entry("Plains",ZoneType.Battlefield));
        cards.add(new Entry("Plains",ZoneType.Battlefield));
        cards.add(new Entry("Plains",ZoneType.Battlefield));
        for(int i=0;i<15;i++) cards.add(new Entry("Forest",ZoneType.Library));
        cards.add(new Entry(control.equals("no-oracle") ? "Forest" : "Thassa's Oracle", hiddenOracle ? ZoneType.Library : ZoneType.Hand));
        if(hiddenOracle&&oracleFront&&!control.equals("no-oracle")) {
            Entry oracle=cards.remove(cards.size()-1);
            int first=0;while(cards.get(first).zone()!=ZoneType.Library) first++;
            cards.add(first,oracle);
        }
        while(cards.size()<40) cards.add(new Entry("Forest",ZoneType.Graveyard));
        return cards;
    }
    private static List<Entry> opposing(String control) {
        List<Entry> cards=new ArrayList<>();
        String hate=switch(control) {
            case "null-rod" -> "Null Rod"; case "rule-law" -> "Rule of Law";
            case "narset" -> "Narset, Parter of Veils"; case "torpor-orb" -> "Torpor Orb";
            default -> null;
        };
        if(hate!=null) cards.add(new Entry(hate,ZoneType.Battlefield));
        for(int i=0;i<30;i++) cards.add(new Entry("Forest",ZoneType.Library));
        while(cards.size()<40) cards.add(new Entry("Forest",ZoneType.Graveyard));
        return cards;
    }
    private static Deck deck(List<Entry> entries) {
        Deck deck=new Deck(); for(Entry e:entries) deck.getMain().add(paper(e.name()),1); return deck;
    }
    private static void populate(Player player,List<Entry> entries) {
        for(Entry e:entries) {
            Card c=Card.fromPaperCard(paper(e.name()),player); c.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(e.zone()).add(c); c.setSickness(false);
            if(c.getName().equals("Teferi, Time Raveler")) c.setCounters(CounterEnumType.LOYALTY,4);
            if(c.getName().equals("Narset, Parter of Veils")) c.setCounters(CounterEnumType.LOYALTY,5);
        }
    }
    private static String run(int seat,String rock,boolean hand,boolean hiddenOracle,String control,boolean candidate,boolean expect) {
        List<Entry> own=layout(rock,hand,hiddenOracle,control), other=opposing(control);
        List<RegisteredPlayer> players=new ArrayList<>();
        for(int s=0;s<2;s++) players.add(new RegisteredPlayer(deck(s==seat?own:other)).setPlayer(candidate&&s==seat
            ? new forge.ai.LobbyPlayerCubeComboAi("Combo-"+s) : GamePlayerUtil.createAiPlayer("Default-"+s,s,0,null,"Default")));
        GameRules rules=new GameRules(GameType.Constructed); rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR); rules.setAllowCheatShuffle(false);
        Game game=new Match(rules,players,"Kitten native fixture").createGame(); game.setAge(GameStage.Play);
        Player p=game.getPlayers().get(seat), opp=game.getPlayers().get(1-seat);
        game.getPhaseHandler().setupFirstTurn(p,()->game.getPhaseHandler().devModeSet(PhaseType.MAIN1,p));
        populate(p,own); populate(opp,other); game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(96100+seat*100+rock.length()+control.length());
        String key="seat="+seat+" rock="+rock.replace(' ','_')+" hand="+hand+" hiddenOracle="+hiddenOracle+" control="+control;
        System.out.println("KITTEN_FIXTURE "+key+" candidate="+candidate+" policy="+forge.ai.CubeComboAi.VERSION);
        Set<Integer> seen=new HashSet<>(); Set<Long> teferiTimestamps=new HashSet<>();
        int steps=0,casts=0,blinks=0,bounces=0,oracleCasts=0,lowestLibrary=100,rockMana=0;
        String initialPublicDecision=null;
        while(!game.isGameOver() && game.getPhaseHandler().getTurn()<=2 && steps<900) {
            steps++; game.getPhaseHandler().mainLoopStep();
            if(steps==1) {
                List<String> stack=new ArrayList<>();
                for(var item:game.getStack()) {
                    var sa=item.getSpellAbility();
                    stack.add(sa.getHostCard().getName()+":"+sa.getApi()+":"+sa.getTargets().getTargetCards().stream().map(Card::getName).toList());
                }
                initialPublicDecision="mana="+p.getManaPool().totalMana()+" stack="+stack;
                System.out.println("KITTEN_INITIAL "+key+" oracleFront="+oracleFront+" decision="+initialPublicDecision);
            }
            lowestLibrary=Math.min(lowestLibrary,p.getCardsIn(ZoneType.Library).size());
            for(Card c:p.getCardsIn(ZoneType.Battlefield)) if(c.getName().equals("Teferi, Time Raveler")) teferiTimestamps.add(c.getGameTimestamp());
            if(game.getPhaseHandler().getTurn()==1) rockMana=Math.max(rockMana,(int)game.getStack().getAbilityActivatedThisTurn().stream()
                .filter(a->a.getActivatingPlayer()==p&&a.isManaAbility()&&a.getHostCard().getName().equals(rock)).count());
            // Opposing permanents can be legally bounced; presence, not the
            // initial fixture label, determines current restrictions.
            if(control.equals("null-rod")&&opp.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals("Null Rod"))
                &&rockMana>0) throw new AssertionError("Null Rod allowed mana");
            if(control.equals("rule-law")&&opp.getCardsIn(ZoneType.Battlefield).stream().anyMatch(c->c.getName().equals("Rule of Law"))
                &&game.getStack().getSpellsCastThisTurn().stream().filter(a->a.getActivatingPlayer()==p).count()>1) throw new AssertionError("Rule of Law exceeded");
            for(var item:game.getStack()) if(seen.add(item.getId())) {
                var sa=item.getSpellAbility(); if(sa.getActivatingPlayer()!=p) continue;
                String name=sa.getHostCard().getName();
                if(sa.isSpell()&&!sa.isCopied()) { casts++; if(name.equals("Thassa's Oracle")) oracleCasts++; }
                if(name.equals("Displacer Kitten")&&sa.getApi()==forge.game.ability.ApiType.ChangeZone) blinks++;
                if(name.equals("Teferi, Time Raveler")&&sa.getApi()==forge.game.ability.ApiType.ChangeZone) bounces++;
                System.out.println("KITTEN_STACK step="+steps+" id="+item.getId()+" card="+name+" targets="+sa.getTargets());
            }
        }
        boolean oracleWin=p.getOutcome()!=null&&"Thassa's Oracle".equals(p.getOutcome().altWinSourceName);
        System.out.println("KITTEN_RESULT "+key+" won="+p.hasWon()+" oracleWin="+oracleWin+" steps="+steps+" casts="+casts+" blinks="+blinks+" bounces="+bounces
            +" oracleCasts="+oracleCasts+" teferiObjects="+teferiTimestamps.size()+" rockMana="+rockMana+" lowestLibrary="+lowestLibrary+" outcome="+game.getOutcome());
        if(steps>=900) throw new AssertionError("Kitten step budget");
        if(expect&&(control.equals("none")||control.equals("null-rod")&&rock.equals("Mana Crypt"))&&(!p.hasWon()||!oracleWin||oracleCasts!=1||bounces<3||teferiTimestamps.size()<3)) throw new AssertionError("Expected native Kitten Oracle win");
        if(List.of("no-kitten","no-teferi","no-oracle","short-blue").contains(control)&&p.hasWon()) throw new AssertionError("Missing-resource control win");
        return initialPublicDecision;
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
                case "getAssetsDir" -> args[0]+"/forge-gui/"; case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "kitten-native-v1"; default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            boolean candidate=args.length<2||!args[1].equals("baseline"), expect=candidate&&args.length>3&&args[3].equals("complete");
            if(args.length>3&&args[3].equals("privacy")) {
                int pairs=0;
                for(int seat=0;seat<2;seat++) for(String rock:List.of("Sol Ring","Mana Crypt","Grim Monolith","Basalt Monolith")) for(boolean hand:List.of(false,true)) {
                    oracleFront=false;String bottom=run(seat,rock,hand,true,"none",candidate,true);
                    oracleFront=true;String front=run(seat,rock,hand,true,"none",candidate,true);
                    if(!bottom.equals(front)) throw new AssertionError("Hidden Oracle order changed initial public decision");
                    pairs++;
                }
                System.out.println("KITTEN_PRIVACY_COMPLETE pairs="+pairs);return;
            }
            for(int seat=0;seat<2;seat++) for(String rock:List.of("Sol Ring","Mana Crypt","Grim Monolith","Basalt Monolith"))
                for(boolean hand:List.of(false,true)) for(boolean hidden:List.of(false,true))
                    for(String control:List.of("none","no-kitten","no-teferi","no-oracle","null-rod","rule-law","narset","torpor-orb","short-blue"))
                        run(seat,rock,hand,hidden,control,candidate,expect);
            System.out.println("KITTEN_SUITE_COMPLETE");
        } catch(Throwable t) {t.printStackTrace();System.exit(1);}
    }
}
