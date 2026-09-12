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
    private static boolean oracleFront, propertySuite;
    private static final List<String> BASE_CONTROLS=List.of("none","no-kitten","no-teferi","no-oracle",
        "null-rod","rule-law","narset","torpor-orb","short-blue");
    /** Controls the property rows register as MUST-NOT-MOVE: the plan must take
     * no rock action at all and the seat must not win. Asserted only under
     * -Dforge.test.requireKittenProperty, so the same fixture can be run
     * unasserted against the matched v47 classes as a control. */
    private static final List<String> MUST_NOT_MOVE=List.of("no-kitten","no-teferi","no-oracle",
        "no-counters","no-counters-no-kitten");
    /** Mox Opal costs {0}, so one iteration needs no mana at all and the loop
     * runs on free replays whether or not metalcraft holds. What the 2-artifact
     * row does show is that the plan never taps the Opal while metalcraft is
     * denied: rockMana must stay 0 (registration amendment 2). */
    private static final List<String> MUST_NOT_TAP=List.of("two-artifacts");
    /** -1 means the classes under test have no such counter at all, which is
     * what the matched v47 control arm reports: its CubeKittenPlan predates it. */
    private static int planRockActions(boolean reset) {
        try {
            java.lang.reflect.Field field;
            try {field=forge.ai.CubeKittenPlan.class.getDeclaredField("planRockActions");}
            catch(NoSuchFieldException absent) {return -1;}
            field.setAccessible(true);
            int value=field.getInt(null);
            if(reset) field.setInt(null,0);
            return value;
        } catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    private static forge.item.PaperCard paper(String name) {
        if (loaded.add(name)) StaticData.instance().attemptToLoadCard(name);
        return Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name),name);
    }
    private static List<Entry> layout(String rock, boolean hand, boolean hiddenOracle, String control) {
        List<Entry> cards = new ArrayList<>();
        cards.add(new Entry(control.contains("no-kitten") ? "Forest" : "Displacer Kitten",ZoneType.Battlefield));
        cards.add(new Entry(control.equals("no-teferi") ? "Forest" : "Teferi, Time Raveler",ZoneType.Battlefield));
        cards.add(new Entry(rock,hand ? ZoneType.Hand : ZoneType.Battlefield));
        cards.add(new Entry("Island",ZoneType.Battlefield));
        cards.add(new Entry(control.equals("short-blue") ? "Plains" : "Island",ZoneType.Battlefield));
        cards.add(new Entry("Plains",ZoneType.Battlefield));
        // A Sunburst replay is paid in colours, so a Prism row keeps a
        // three-colour free mana base instead of three Plains. The two reserved
        // blue sources are untouched.
        cards.add(new Entry(rock.equals("Pentad Prism") ? "Mountain" : "Plains",ZoneType.Battlefield));
        cards.add(new Entry(rock.equals("Pentad Prism") ? "Forest" : "Plains",ZoneType.Battlefield));
        // Metalcraft counts three artifacts including the rock itself; the two
        // fillers have no mana ability and cost more than they could ever make,
        // so the predicate never selects them.
        if(rock.equals("Mox Opal")) {
            cards.add(new Entry("Glass of the Guildpact",ZoneType.Battlefield));
            if(!control.equals("two-artifacts")) cards.add(new Entry("Ruby Medallion",ZoneType.Battlefield));
        }
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
    private static void populate(Player player,List<Entry> entries,String control) {
        for(Entry e:entries) {
            Card c=Card.fromPaperCard(paper(e.name()),player); c.setGameTimestamp(player.getGame().getNextTimestamp());
            player.getZone(e.zone()).add(c); c.setSickness(false);
            if(c.getName().equals("Teferi, Time Raveler")) c.setCounters(CounterEnumType.LOYALTY,4);
            if(c.getName().equals("Narset, Parter of Veils")) c.setCounters(CounterEnumType.LOYALTY,5);
            if(c.getName().equals("Pentad Prism")&&!control.contains("no-counters")&&e.zone()==ZoneType.Battlefield)
                c.setCounters(CounterEnumType.CHARGE,2);
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
        populate(p,own,control); populate(opp,other,control); game.getAction().checkStateEffects(true); game.getTriggerHandler().resetActiveTriggers();
        BenchRandomAudit.install(96100+seat*100+rock.length()+control.length());
        planRockActions(true);
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
        int rockActions=planRockActions(true);
        if(propertySuite) System.out.println("KITTEN_PLAN_ROCKS "+key+" rockActions="+rockActions);
        if(steps>=900) throw new AssertionError("Kitten step budget");
        if(expect&&(control.equals("none")||control.equals("null-rod")&&rock.equals("Mana Crypt"))&&(!p.hasWon()||!oracleWin||oracleCasts!=1||bounces<3||teferiTimestamps.size()<3)) throw new AssertionError("Expected native Kitten Oracle win");
        // short-blue removes one of two Islands. For the four colourless rocks that
        // is the only blue we have; a rock that makes mana of ANY colour is itself
        // a blue source, so the control does not remove the resource and the
        // property rows record it instead of asserting it (registration amendment 1).
        if(!propertySuite&&List.of("no-kitten","no-teferi","no-oracle","short-blue").contains(control)&&p.hasWon()) throw new AssertionError("Missing-resource control win");
        // Property rows: a MUST-MOVE row has to reach the same registered Oracle
        // contract the four named rocks already meet, and a MUST-NOT-MOVE row has
        // to leave the rock alone entirely, not merely fail to win.
        if(propertySuite&&candidate&&Boolean.getBoolean("forge.test.requireKittenProperty")) {
            if(control.equals("none")&&(!p.hasWon()||!oracleWin||oracleCasts!=1||bounces<3||teferiTimestamps.size()<3||rockActions<1))
                throw new AssertionError("Expected property-rock Oracle win");
            if(MUST_NOT_MOVE.contains(control)&&(p.hasWon()||rockActions!=0))
                throw new AssertionError("Property control moved: "+key+" rockActions="+rockActions);
            if(MUST_NOT_TAP.contains(control)&&rockMana!=0)
                throw new AssertionError("Property control tapped a denied ability: "+key+" rockMana="+rockMana);
        }
        return initialPublicDecision;
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()) {
                case "getAssetsDir" -> args[0]+"/forge-gui/"; case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame" -> false;
                case "getCurrentVersion" -> "kitten-native-v1"; default -> throw new AssertionError(m.getName()); }));
            FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            boolean candidate=args.length<2||!args[1].equals("baseline"), expect=candidate&&args.length>3&&args[3].equals("complete");
            if(args.length>3&&args[3].equals("property")) {
                propertySuite=true;
                int cases=0;
                for(int seat=0;seat<2;seat++) for(String rock:List.of("Mox Opal","Pentad Prism"))
                    for(boolean hand:List.of(false,true)) for(boolean hidden:List.of(false,true)) {
                        List<String> controls=new ArrayList<>(BASE_CONTROLS);
                        if(rock.equals("Mox Opal")) controls.add("two-artifacts");
                        // The counter rows need the Prism already on the
                        // battlefield; from hand it enters with Sunburst counters.
                        if(rock.equals("Pentad Prism")&&!hand) {controls.add("no-counters");controls.add("no-counters-no-kitten");}
                        for(String control:controls) {run(seat,rock,hand,hidden,control,candidate,false);cases++;}
                    }
                System.out.println("KITTEN_PROPERTY_COMPLETE cases="+cases);return;
            }
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
