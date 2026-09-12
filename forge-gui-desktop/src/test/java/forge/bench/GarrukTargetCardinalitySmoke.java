package forge.bench;

import forge.StaticData;
import forge.ai.ComputerUtil;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.ApiType;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Pinned-engine rules/execution fixture, not Default's strategic willingness. */
public final class GarrukTargetCardinalitySmoke {
    private static int checks;
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
    private record Context(Game game,Player actor,Card garruk,List<Card> lands,SpellAbility plus){}
    private static Card card(String name,Player actor){
        StaticData.instance().attemptToLoadCard(name);
        var card=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),actor);
        card.setGameTimestamp(actor.getGame().getNextTimestamp());actor.getZone(ZoneType.Battlefield).add(card);card.setSickness(false);return card;
    }
    private static Context context(int seat,int count){
        var first=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Seat0",0,0,null,"Default"));
        var second=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("Seat1",1,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),List.of(first,second),"Garruk cardinality rules fixture").createGame();
        var actor=game.getPlayers().get(seat);game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var garruk=card("Garruk Wildspeaker",actor);garruk.setCounters(CounterEnumType.LOYALTY,3);
        var lands=new ArrayList<Card>();for(int i=0;i<count;i++){var land=card("Forest",actor);land.setTapped(true);lands.add(land);}
        game.getAction().checkStateEffects(true);
        var plus=garruk.getSpellAbilities().stream().filter(sa->sa.getApi()==ApiType.Untap).findFirst().orElseThrow();plus.setActivatingPlayer(actor);
        return new Context(game,actor,garruk,lands,plus);
    }
    private static void run(int seat,int landCount,String selection){
        var c=context(seat,landCount);var sa=c.plus;
        check(sa.getMinTargets()==2&&sa.getMaxTargets()==2,"actual Garruk +1 requires exactly two targets");
        check(c.garruk.getCounters(CounterEnumType.LOYALTY)==3,"actual initial loyalty3");
        int candidates=sa.getTargetRestrictions().getNumCandidates(sa);
        check(candidates==landCount,"actual legal candidate domain count="+landCount);
        check((candidates>=sa.getMinTargets())==(landCount>=2),"enough distinct land targets iff at least two exist");
        check(sa.canPlay(),"timing/cost canPlay is true, independently of target completeness");
        var chosen=new ArrayList<Card>();
        switch(selection){
            case "none"->{}
            case "one"->chosen.add(c.lands.get(0));
            case "duplicate"->{chosen.add(c.lands.get(0));chosen.add(c.lands.get(0));}
            case "two"->{chosen.add(c.lands.get(0));chosen.add(c.lands.get(landCount-1));}
            case "three"->chosen.addAll(c.lands);
            default->throw new AssertionError(selection);
        }
        boolean accepted=ComputerUtil.handlePlayingSpellAbility(c.actor,sa,ability->{
            ability.resetTargets();
            for(int i=0;i<chosen.size();i++){
                var target=chosen.get(i);check(ability.canTarget(target),"chosen entity is a land legal for ability");
                boolean added=ability.getTargets().add(target);
                check(added==!(selection.equals("duplicate")&&i==1),"native target collection rejects repeated identity");
            }
            check(ability.isTargetNumberValid()==selection.equals("two"),"native target cardinality gate for "+selection);
        });
        check(accepted==selection.equals("two"),"actual execution acceptance for "+selection);
        if(!accepted){
            check(c.game.getStack().isEmpty(),"invalid declaration never enters stack");
            check(c.garruk.getCounters(CounterEnumType.LOYALTY)==3,"invalid declaration does not pay loyalty cost");
            check(c.lands.stream().allMatch(Card::isTapped),"invalid declaration untaps nothing");
        }else{
            check(!c.game.getStack().isEmpty(),"valid declaration enters actual stack");
            check(c.garruk.getCounters(CounterEnumType.LOYALTY)==4,"actual +1 cost raises loyalty3 to4 before resolution");
            check(c.lands.stream().allMatch(Card::isTapped),"untap waits for actual resolution");
            c.game.getStack().resolveStack();
            for(var land:c.lands)check(land.isTapped()!=chosen.contains(land),"actual resolution untaps exactly selected land fid="+land.getId());
            check(c.garruk.getCounters(CounterEnumType.LOYALTY)==4,"resolution does not add loyalty a second time");
        }
        System.out.println("GARRUK_RESULT seat="+seat+" lands="+landCount+" selection="+selection+" candidates="+candidates+" selected="+chosen.stream().map(Card::getId).toList()+" accepted="+accepted+" loyalty="+c.garruk.getCounters(CounterEnumType.LOYALTY));
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(proxy,method,values)->switch(method.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"garruk-target-cardinality";default->throw new AssertionError(method.getName());
        }));
        FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++){
            run(seat,0,"none");run(seat,1,"one");run(seat,3,"one");run(seat,3,"duplicate");run(seat,3,"three");run(seat,2,"two");run(seat,3,"two");
        }
        System.out.println("PASS "+checks+" pinned Garruk rules/execution checks; no AI willingness or policy parity claim");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
