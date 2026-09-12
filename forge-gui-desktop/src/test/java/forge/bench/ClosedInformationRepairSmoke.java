package forge.bench;

import forge.StaticData;
import forge.ai.AiKnownCardObservations;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityFactory;
import forge.game.card.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;
import java.util.*;

/** Paired hidden-world and real reveal controls for the identified repair only. */
public final class ClosedInformationRepairSmoke {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private record World(Game game,Player actor,Player foe,Card source,Card secret){}
    private static Card card(String name,Player owner,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);var c=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),owner);
        c.setGameTimestamp(owner.getGame().getNextTimestamp());owner.getZone(zone).add(c);return c;
    }
    private static World world(int seat,boolean closed,String sourceName,String secretName,ZoneType zone){
        var rules=new GameRules(GameType.Constructed);
        if(closed)rules.setAiInformationPolicy(GameRules.AiInformationPolicy.CLOSED_REPAIR);
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1",1,0,null,"Default")));
        var game=new Match(rules,players,"Closed information repair").createGame();game.setAge(GameStage.Play);
        var actor=game.getPlayers().get(seat);var foe=game.getPlayers().get(1-seat);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var source=card(sourceName,actor,ZoneType.Battlefield);var secret=card(secretName,foe,zone);
        return new World(game,actor,foe,source,secret);
    }
    private static String name(World w){
        var sa=AbilityFactory.getAbility(w.source.getSVar("DBNameCard"),w.source);sa.setActivatingPlayer(w.actor);
        MyRandom.setRandom(new Random(91610));sa.resolve();return w.source.getNamedCard();
    }
    private static void reveal(World w){
        var sa=AbilityFactory.getAbility("DB$ RevealHand | Defined$ Opponent",w.source);sa.setActivatingPlayer(w.actor);sa.resolve();
    }
    private static void revoker(int seat,ZoneType zone){
        var stock=new ArrayList<String>();var closed=new ArrayList<String>();
        for(String hidden:List.of("Walking Ballista","Griselbrand")){
            var baseline=world(seat,false,"Phyrexian Revoker",hidden,zone);stock.add(name(baseline));
            var repaired=world(seat,true,"Phyrexian Revoker",hidden,zone);
            check(!repaired.secret.getView().canBeShownTo(repaired.actor.getView()),"hidden alternative is not visible");
            check(AiKnownCardObservations.cardsKnownTo(repaired.actor,repaired.foe).isEmpty(),"no unseen opponent identities admitted");
            closed.add(name(repaired));
            check(!repaired.secret.getView().canBeShownTo(repaired.actor.getView()),"repair grants no visibility");
        }
        check(stock.equals(List.of("Walking Ballista","Griselbrand")),"disarmed repair retains stock defect as negative control");
        check(closed.get(0).equals(closed.get(1)),"closed Revoker decision invariant to unseen opponent identity");
        System.out.println("CLOSED_REVOKER seat="+seat+" zone="+zone+" stock="+stock+" repaired="+closed);
    }
    private static void publicAndRevealed(int seat,String hidden){
        var pub=world(seat,true,"Phyrexian Revoker",hidden,ZoneType.Battlefield);
        check(name(pub).equals(hidden),"public activated card still informs native scoring");
        var w=world(seat,true,"Phyrexian Revoker",hidden,ZoneType.Hand);reveal(w);
        check(!w.secret.getView().canBeShownTo(w.actor.getView()),"real reveal does not grant permanent viewer permission");
        var known=AiKnownCardObservations.cardsKnownTo(w.actor,w.foe);
        check(known.size()==1&&known.getFirst()!=w.secret&&known.getFirst().getName().equals(hidden),"real reveal captured detached face snapshot");
        check(name(w).equals(hidden),"real revealed hand informs native naming");
        w.actor.getController().resetAtEndOfTurn();
        check(name(w).equals(hidden),"legal hand knowledge survives strategic memory reset");
        var unseen=card(hidden.equals("Walking Ballista")?"Griselbrand":"Walking Ballista",w.foe,ZoneType.Library);
        w.game.getAction().moveToHand(unseen,null);
        check(AiKnownCardObservations.cardsKnownTo(w.actor,w.foe).size()==1,"newly drawn hand identity stays hidden");
        var moved=w.game.getAction().moveToGraveyard(w.secret,null);
        w.game.getAction().moveToHand(moved,null);
        check(AiKnownCardObservations.cardsKnownTo(w.actor,w.foe).isEmpty(),"old hand-visit receipt cannot identify re-entered hidden card");
        var other=world(1-seat,true,"Phyrexian Revoker",hidden,ZoneType.Hand);
        check(AiKnownCardObservations.cardsKnownTo(other.actor,other.foe).isEmpty(),"knowledge cannot cross games or controllers");
    }
    private static void metadataAndVisibility(int seat){
        var stock=new ArrayList<String>();var closed=new ArrayList<String>();
        for(String key:List.of("Walking Ballista","Griselbrand"))for(boolean repaired:List.of(false,true)){
            var w=world(seat,repaired,"Phyrexian Revoker","Walking Ballista",ZoneType.Battlefield);
            card("Griselbrand",w.foe,ZoneType.Battlefield);w.foe.getRegisteredPlayer().getDeck().addKeyCard(key);
            (repaired?closed:stock).add(name(w));
        }
        check(stock.equals(List.of("Walking Ballista","Griselbrand")),"stock control reads private registered key-card metadata");
        check(closed.get(0).equals(closed.get(1)),"repair ignores private key-card metadata even for public cards");
        var w=world(seat,true,"Phyrexian Revoker","Walking Ballista",ZoneType.Library);
        long stamp=w.game.getNextTimestamp(),visit=w.secret.getGameTimestamp();
        w.secret.addMayLookAt(stamp,List.of(w.actor));
        check(AiKnownCardObservations.cardsKnownTo(w.actor,w.foe).size()==1,"current legal library-look permission admitted");
        w.secret.removeMayLookAt(stamp);
        check(w.secret.getGameTimestamp()==visit&&AiKnownCardObservations.cardsKnownTo(w.actor,w.foe).isEmpty(),"permission loss evaluated live without zone change or stale private face");
    }
    private static void revokerNameLegality(int seat,boolean closed){
        // No opposing activated abilities: the own-card penalty loop used to
        // reintroduce lands, which Revoker's actual NameCard effect accepted.
        var w=world(seat,closed,"Phyrexian Revoker","Grizzly Bears",ZoneType.Hand);
        card("Steam Vents",w.actor,ZoneType.Hand);
        var chosen=name(w);
        check(forge.card.CardFacePredicates.valid("Card.nonLand").test(
            StaticData.instance().getCommonCards().getFaceByName(chosen)),
            "actual Revoker choice must satisfy effect predicate; closed="+closed+" chosen="+chosen);
    }
    private static String gatekeeper(int seat,boolean closed,String hidden,ZoneType zone){
        var w=world(seat,closed,"Cemetery Gatekeeper",hidden,zone);
        card("Grizzly Bears",w.foe,ZoneType.Graveyard);card("Sol Ring",w.foe,ZoneType.Graveyard);
        var sa=AbilityFactory.getAbility(w.source.getSVar("TrigExile"),w.source);sa.setActivatingPlayer(w.actor);
        MyRandom.setRandom(new Random(91610));sa.resolve();
        check(w.game.getCardsIn(ZoneType.Exile).size()==1,"actual repaired exile executes exactly one legal choice");
        return w.game.getCardsIn(ZoneType.Exile).getFirst().getName();
    }
    public static void main(String[] args){try{
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"closed-information-repair-v1";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        check(GameRules.AiInformationPolicy.parse("stock-default-v1")==GameRules.AiInformationPolicy.STOCK,"stock lineage explicit");
        check(GameRules.AiInformationPolicy.parse("closed-decklist-repair-v1")==GameRules.AiInformationPolicy.CLOSED_REPAIR,"repair lineage explicit");
        try{GameRules.AiInformationPolicy.parse("closed");throw new AssertionError("Unknown lineage accepted");}catch(IllegalArgumentException expected){checks++;}
        for(int seat=0;seat<2;seat++){
            revokerNameLegality(seat,false);revokerNameLegality(seat,true);
            for(var zone:List.of(ZoneType.Hand,ZoneType.Library)){
                revoker(seat,zone);
                check(!gatekeeper(seat,false,"Llanowar Elves",zone).equals(gatekeeper(seat,false,"Lotus Petal",zone)),"stock Gatekeeper negative control still diverges");
                check(gatekeeper(seat,true,"Llanowar Elves",zone).equals(gatekeeper(seat,true,"Lotus Petal",zone)),"repaired Gatekeeper hidden-world invariance");
            }
            publicAndRevealed(seat,"Walking Ballista");publicAndRevealed(seat,"Griselbrand");
            metadataAndVisibility(seat);
        }
        System.out.println("PASS "+checks+" scoped closed-information repair checks; NOT complete Forge information certification");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
