package forge.bench;

import forge.StaticData;
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

/** NEGATIVE evidence: reproduce stock Forge's closed-decklist violation.
 * Compile/run against the pinned jar ONLY, not the bridge overlay. A successful
 * witness establishes a defect, not a passing noninterference certification.
 * Cemetery Gatekeeper is outside current cube540; Phyrexian Revoker is inside.
 * Neither witness establishes incidence or outcome impact in historical runs.
 */
public final class ClosedDecklistExilePreferenceWitness {
    private static int checks;
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    private static Card card(String name,Player owner,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);
        var paper=Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name));
        var card=Card.fromPaperCard(paper,owner);
        card.setGameTimestamp(owner.getGame().getNextTimestamp());owner.getZone(zone).add(card);return card;
    }
    private record Outcome(String publicPosition,String ownInformation,String chosen,String rulesExiled){}
    private static void revokerPair(int seat,ZoneType zone){
        var positions=new ArrayList<String>();var names=new ArrayList<String>();
        for(String hiddenName:List.of("Walking Ballista","Griselbrand")){
            var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0",0,0,null,"Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1",1,0,null,"Default")));
            var game=new Match(new GameRules(GameType.Constructed),players,"Closed-list Revoker witness").createGame();game.setAge(GameStage.Play);
            var actor=game.getPlayers().get(seat);var foe=game.getPlayers().get(1-seat);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
            var source=card("Phyrexian Revoker",actor,ZoneType.Battlefield);var hidden=card(hiddenName,foe,zone);
            check(foe.getRegisteredPlayer().getDeck().getKeyCards().isEmpty(),"no opponent key-card metadata supplied");
            check(!hidden.getView().canBeShownTo(actor.getView()),"Revoker alternative is an unrevealed opponent card");
            // No other cards, public actions, or reveals in either position.
            positions.add(source.getId()+"/"+source.getGameTimestamp()+"/"+source.getName()+"/"+actor.getLife()+"/"+foe.getLife()+"/"+
                actor.getCardsIn(ZoneType.Hand).size()+"/"+actor.getCardsIn(ZoneType.Library).size()+"/"+
                foe.getCardsIn(ZoneType.Hand).size()+"/"+foe.getCardsIn(ZoneType.Library).size());
            var ability=AbilityFactory.getAbility(source.getSVar("DBNameCard"),source);ability.setActivatingPlayer(actor);
            check(ability.getParam("AILogic").equals("PhyrexianRevoker"),"native Revoker name-card script used");
            MyRandom.setRandom(new Random(91610));ability.resolve();
            names.add(source.getNamedCard());
            check(hidden.isInZone(zone)&&!hidden.getView().canBeShownTo(actor.getView()),"naming neither moves nor reveals hidden card");
            System.out.println("REVOKER_WITNESS seat="+seat+" hiddenZone="+zone+" hidden="+hiddenName+" named="+source.getNamedCard());
        }
        check(positions.get(0).equals(positions.get(1)),"Revoker paired public and own information identical");
        check(names.equals(List.of("Walking Ballista","Griselbrand")),"same RNG and naming domain, unseen identity changes actual Revoker naming");
    }
    private static Outcome run(int seat,boolean creatureWorld,ZoneType hiddenZone){
        var players=List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0",0,0,null,"Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1",1,0,null,"Default")));
        var game=new Match(new GameRules(GameType.Constructed),players,"Closed-list witness").createGame();
        game.setAge(GameStage.Play);var actor=game.getPlayers().get(seat);var foe=game.getPlayers().get(1-seat);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var source=card("Cemetery Gatekeeper",actor,ZoneType.Battlefield);
        var creature=card("Grizzly Bears",foe,ZoneType.Graveyard);var artifact=card("Sol Ring",foe,ZoneType.Graveyard);
        var hidden=card(creatureWorld?"Llanowar Elves":"Lotus Petal",foe,hiddenZone);
        check(!hidden.getView().canBeShownTo(actor.getView()),"altered opponent face is not visible");
        // All positions are freshly constructed with the same empty reveal/history
        // and identical public card IDs/timestamps/owners/order. Only the hidden
        // card's identity differs. Neither opponent decklist is supplied to actor.
        String publicPosition=game.getPlayers().stream().map(p->p.getName()+":"+p.getLife()+":"+
            p.getCardsIn(ZoneType.Hand).size()+":"+p.getCardsIn(ZoneType.Library).size()+":"+
            List.of(ZoneType.Battlefield,ZoneType.Graveyard,ZoneType.Exile).stream().map(z->z+"="+
                p.getCardsIn(z).stream().map(c->c.getId()+"/"+c.getGameTimestamp()+"/"+c.getName()).toList()).toList()).toList().toString();
        String ownInformation=actor.getCardsIn(ZoneType.Hand).toString()+actor.getCardsIn(ZoneType.Library).toString();
        var ability=AbilityFactory.getAbility(source.getSVar("TrigExile"),source);ability.setActivatingPlayer(actor);
        check(ability.getParam("AILogic").equals("ExilePreference:MostProminentOppType"),"real card requests known-deck preference");
        var choices=new CardCollection();choices.add(creature);choices.add(artifact);
        MyRandom.setRandom(new Random(91610));
        var selected=actor.getController().chooseSingleCardForZoneChange(ZoneType.Exile,List.of(ZoneType.Graveyard),ability,choices,null,"Select a card in a graveyard",false,actor);
        check(selected==creature||selected==artifact,"choice is an exact legal public card");
        check(hidden.isInZone(hiddenZone)&&!hidden.getView().canBeShownTo(actor.getView()),"choice reveals/moves no hidden card");
        // Also execute the printed effect, not only its AI helper. Same seed and
        // state: native ChangeZoneEffect must take the same preference branch.
        MyRandom.setRandom(new Random(91610));ability.resolve();
        var exiled=game.getCardsIn(ZoneType.Exile);
        check(exiled.size()==1&&exiled.getFirst().getName().equals(selected.getName()),"actual printed effect exiles the selected public card");
        System.out.println("WITNESS seat="+seat+" hiddenZone="+hiddenZone+" hiddenType="+(creatureWorld?"Creature":"Artifact")+" selected="+selected.getName());
        return new Outcome(publicPosition,ownInformation,selected.getName(),exiled.getFirst().getName());
    }
    public static void main(String[] args){try{
        for(var type:List.of(forge.ai.PlayerControllerAi.class,forge.ai.ability.ChangeZoneAi.class,forge.game.ability.effects.ChangeZoneEffect.class,
                forge.ai.SpecialCardAi.class,forge.game.ability.effects.ChooseCardNameEffect.class)){
            var location=type.getProtectionDomain().getCodeSource().getLocation().toURI();
            check(new java.io.File(location).getCanonicalPath().equals(new java.io.File(args[1]).getCanonicalPath()),"tested class comes from pinned jar: "+type.getName());
        }
        GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
            case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
            case "getCurrentVersion"->"closed-list-negative-witness";default->throw new AssertionError(m.getName());}));
        FModel.initialize(null,p->{p.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);p.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
        for(int seat=0;seat<2;seat++)for(var zone:List.of(ZoneType.Hand,ZoneType.Library)){
            var creatures=run(seat,true,zone);var artifacts=run(seat,false,zone);
            check(creatures.publicPosition.equals(artifacts.publicPosition),"paired worlds have identical public positions");
            check(creatures.ownInformation.equals(artifacts.ownInformation),"paired worlds have identical own private information");
            check(creatures.chosen.equals("Grizzly Bears")&&artifacts.chosen.equals("Sol Ring"),"same RNG and legal choices, hidden opponent identity changes stock choice");
            revokerPair(seat,zone);
        }
        System.out.println("REPRODUCED CLOSED-DECKLIST VIOLATION: "+checks+" witness checks; NEGATIVE evidence, NOT a fair-play pass or benchmark-impact claim");System.exit(0);
    }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
}
