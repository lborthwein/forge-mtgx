package forge.bench;

import forge.StaticData;
import forge.ai.ComputerUtil;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.*;
import forge.game.card.*;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.zone.*;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Exact trusted-continuation identity and failed-announcement scope, not a full transaction proof. */
final class CastingAuthorizationEngineSmoke {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS AUTH "+label);}
    private static void rejects(Runnable action,String label){try{action.run();throw new AssertionError("accepted "+label);}catch(RulesCostFeasibility.Unsupported expected){check(true,label);}}
    private record Context(Game game,Player actor,Card source,SpellAbility ability,Zone originalZone,List<Card> lands){}
    private static Card card(String name,Player owner,ZoneType zone){
        StaticData.instance().attemptToLoadCard(name);var out=Card.fromPaperCard(Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name)),owner);
        out.setGameTimestamp(owner.getGame().getNextTimestamp());owner.getZone(zone).add(out);out.setSickness(false);return out;
    }
    private static Context setup(int seat,String kind){
        var p0=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0",0,0,null,"Default"));
        var p1=new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1",1,0,null,"Default"));
        var game=new Match(new GameRules(GameType.Constructed),List.of(p0,p1),"Casting authorization "+kind).createGame();
        var actor=game.getPlayers().get(seat);var opponent=game.getPlayers().get(1-seat);
        game.setAge(GameStage.Play);game.getPhaseHandler().devModeSet(PhaseType.MAIN1,actor);
        var lands=new ArrayList<Card>();lands.add(card("Plains",actor,ZoneType.Battlefield));lands.add(card("Plains",actor,ZoneType.Battlefield));
        boolean exile=kind.startsWith("exile"),top=kind.equals("top-tax"),life=kind.equals("citadel");
        card("Forest",actor,ZoneType.Hand);
        Card source;
        if(exile){
            source=card("Savannah Lions",opponent,kind.equals("exile-known")?ZoneType.Library:ZoneType.Exile);
            if(kind.equals("exile-known")){
                // Full native Adventure cast/resolve grants real WithMayLook and play permissions.
                card("Forest",opponent,ZoneType.Library);card("Mind Twist",opponent,ZoneType.Library);
                for(int n=0;n<3;n++)card("Swamp",actor,ZoneType.Battlefield);
                var dragon=card("Decadent Dragon",actor,ZoneType.Hand);dragon.setState(forge.card.CardStateName.Secondary,false);
                game.getAction().checkStateEffects(true);var dig=dragon.getSpellAbilities().stream().filter(a->a.getApi()==ApiType.Dig).findFirst().orElseThrow();
                dig.setActivatingPlayer(actor);dig.getTargets().add(opponent);BenchRandomAudit.install(90461);
                check(ComputerUtil.handlePlayingSpellAbility(actor,dig,null),"real Adventure setup casts");game.getStack().resolveStack();game.getAction().checkStateEffects(true);
                final int fid=source.getId();source=opponent.getCardsIn(ZoneType.Exile).stream().filter(c->c.getId()==fid).findFirst().orElseThrow();
            }else{
                // Isolated no-look permission control: deliberately not a visibility/full-effect fixture.
                var dragon=card("Decadent Dragon",actor,ZoneType.Battlefield);dragon.setState(forge.card.CardStateName.Secondary,false);
                source.turnFaceDown(true);dragon.addRemembered(source);var effect=AbilityFactory.getAbility(dragon.getSVar("DBEffect"),dragon);effect.setActivatingPlayer(actor);AbilityUtils.resolve(effect);
            }
        }else{
            if(top){card("Future Sight",actor,ZoneType.Battlefield);card("Thalia, Guardian of Thraben",opponent,ZoneType.Battlefield);}
            if(life)card("Bolas's Citadel",actor,ZoneType.Battlefield);
            source=card(kind.equals("activation")?"Figure of Destiny":top||life?"Sol Ring":"Savannah Lions",actor,kind.equals("activation")?ZoneType.Battlefield:top||life?ZoneType.Library:ZoneType.Hand);
        }
        card("Island",actor,ZoneType.Hand);
        if(top||life){card("Mountain",actor,ZoneType.Library);card("Forest",actor,ZoneType.Library);}
        game.getAction().checkStateEffects(true);
        var candidates=BenchmarkAbilityEnumeration.spells(List.of(source),actor).stream().filter(a->{a.setActivatingPlayer(actor);return a.canPlay()&&RulesCostFeasibility.requirePayable(actor,a);}).toList();
        check(candidates.size()==1,"unique real payable action "+kind+" seat="+seat);
        return new Context(game,actor,source,candidates.get(0),source.getZone(),lands);
    }
    private static Card move(Context c){var returned=c.game.getAction().moveToStack(c.source,c.ability);c.ability.setHostCard(returned);return returned;}
    private static void identities(int seat,String kind){
        var c=setup(seat,kind);var other=c.game.getPlayers().get(1-seat);
        var originalPermission=c.ability.getMayPlayOption();
        if(originalPermission!=null){
            var permissions=c.source.getMayPlay();c.source.removeMayPlay(originalPermission.getAbility());
            rejects(()->RulesCastingAuthorization.capture(c.actor,c.ability),"revoked original permission cannot be captured");c.source.setMayPlay(permissions);c.source.updateMayPlay();
            c.ability.setMayPlay(new CardPlayOption(c.actor,originalPermission.getAbility(),false,originalPermission.getAltManaCost(),originalPermission.isWithFlash(),originalPermission.grantsZonePermissions()));
            rejects(()->RulesCastingAuthorization.capture(c.actor,c.ability),"equivalent but nonidentical original permission rejected");c.ability.setMayPlay(originalPermission);
        }
        var auth=RulesCastingAuthorization.capture(c.actor,c.ability);
        rejects(()->auth.require(c.actor,c.ability),"unbound authorization rejected");
        rejects(()->auth.bind(c.source,c.source,c.ability),"pre-move source rejected");
        var returned=move(c);var permission=c.ability.getMayPlayOption();var clone=new CardCopyService(returned).copyCard(false);
        check(clone!=returned&&clone.getId()==returned.getId(),"same-fid distinct object fixture");
        rejects(()->auth.bind(clone,returned,c.ability),"same-fid wrong original rejected");
        rejects(()->auth.bind(c.source,clone,c.ability),"same-fid wrong returned object rejected");
        auth.bind(c.source,returned,c.ability);auth.require(c.actor,c.ability);
        check(RulesCostFeasibility.assess(c.actor,c.ability,auth).status()==RulesCostFeasibility.Status.PAYABLE,"scoped actual returned source remains payable "+kind);
        if(c.ability.getMayPlayOption()!=null)check(RulesCostFeasibility.assess(c.actor,c.ability).status()!=RulesCostFeasibility.Status.PAYABLE,"original strict entry still rejects moved permission");
        rejects(()->auth.bind(c.source,returned,c.ability),"second bind rejected");
        rejects(()->auth.require(other,c.ability),"wrong actor rejected");
        var copy=c.ability.copyForEnumeration(c.actor);rejects(()->auth.require(c.actor,copy),"different root ability object rejected");
        c.ability.setHostCard(clone);rejects(()->auth.require(c.actor,c.ability),"same fid substituted live host rejected");c.ability.setHostCard(returned);
        c.ability.setActivatingPlayer(other);rejects(()->auth.require(c.actor,c.ability),"changed root actor rejected");c.ability.setActivatingPlayer(c.actor);
        if(permission!=null){
            c.ability.setMayPlay(null);rejects(()->auth.require(c.actor,c.ability),"changed exact play option rejected");
            c.ability.setMayPlay(new CardPlayOption(c.actor,permission.getAbility(),false,permission.getAltManaCost(),permission.isWithFlash(),permission.grantsZonePermissions()));
            rejects(()->auth.require(c.actor,c.ability),"equivalent but nonidentical post-move permission rejected");c.ability.setMayPlay(permission);
        }
        rejects(()->RulesCastingAuthorization.capture(c.actor,c.ability),"cannot recapture moved spell as original-zone action");
        c.game.getStackZone().remove(returned);rejects(()->auth.require(c.actor,c.ability),"same object absent stack membership rejected");c.game.getStackZone().add(returned);
        auth.close();rejects(()->auth.require(c.actor,c.ability),"closed authorization cannot be reused");
        rejects(()->RulesCostFeasibility.assess(c.actor,c.ability,auth),"closed authorization cannot price payment");
        // A closed certificate can never be resurrected in another game or seat.
        var different=setup(1-seat,"hand");rejects(()->auth.require(different.actor,different.ability),"different game and root rejected");
    }
    private static final class DeliberateRejection extends RuntimeException {}
    private static List<Integer> ids(Zone zone){return zone.getCards().stream().map(Card::getId).toList();}
    private static void activationIdentity(int seat){
        var c=setup(seat,"activation");var auth=RulesCastingAuthorization.capture(c.actor,c.ability);
        c.originalZone.remove(c.source);rejects(()->auth.bind(c.source,c.source,c.ability),"activation removed before bind rejected explicitly");
        c.originalZone.add(c.source);auth.bind(c.source,c.source,c.ability);auth.require(c.actor,c.ability);
        c.originalZone.remove(c.source);rejects(()->auth.require(c.actor,c.ability),"activation removed after bind rejected explicitly");
        c.originalZone.add(c.source,0);rejects(()->auth.require(c.actor,c.ability),"activation original position changed rejected");auth.close();
        var fresh=setup(seat,"hand");var closed=RulesCastingAuthorization.capture(fresh.actor,fresh.ability);closed.close();var moved=move(fresh);
        rejects(()->closed.bind(fresh.source,moved,fresh.ability),"closed-before-bind authorization cannot be used");
    }
    private static void rollback(int seat,String kind){
        var c=setup(seat,kind);
        if(kind.equals("activation")){
            check(ComputerUtil.handlePlayingSpellAbility(c.actor,c.ability,null),"actual prior Figure activation paid");
            c.game.getStack().resolveStack();
            check(!c.ability.getPayingMana().isEmpty()&&!c.ability.getPayingManaAbilities().isEmpty(),"actual old activation retains mana receipt");
            check(c.ability.canPlay(),"same activated ability may be used again");
        }
        var originalOrder=ids(c.originalZone);int position=c.originalZone.getCards().indexOf(c.source);
        var lookers=c.game.getPlayers().stream().map(c.source::mayPlayerLook).toList();var views=c.game.getPlayers().stream().map(p->String.valueOf(StateEncoder.encodeCard(c.source,p.getView()))).toList();
        var libraryLookers=new HashMap<Integer,List<Boolean>>();for(var libraryCard:c.game.getCardsIn(ZoneType.Library))libraryLookers.put(libraryCard.getId(),c.game.getPlayers().stream().map(libraryCard::mayPlayerLook).toList());
        var tapped=c.lands.stream().map(Card::isTapped).toList();int life=c.actor.getLife();int pool=c.actor.getManaPool().totalMana();
        BenchRandomAudit.install(90462);var stamp=BenchRandomAudit.begin();
        var internalBefore=BenchMenuStateAudit.capture(c.game);
        boolean[] prepared={false};
        try(var auth=RulesCastingAuthorization.capture(c.actor,c.ability)){
            try{ComputerUtil.handlePlayingSpellAbilityControlled(c.actor,c.ability,new ComputerUtil.ControlledAnnouncement(){
                @Override public void sourceMoved(Card original,Card returned,SpellAbility actual){
                    auth.bind(original,returned,actual);check(original==c.source&&actual==c.ability,"callback binds exact original source and root");
                    check(actual.getHostCard()==returned&&returned.isInZone(actual.isSpell()?ZoneType.Stack:ZoneType.Battlefield),"callback binds exact actual returned source card");
                    check(c.game.getStack().isEmpty()&&!c.game.getStack().isFrozen(),"announcement not yet MagicStack and not frozen");
                }
                @Override public CostDecisionMakerBase preparePayment(SpellAbility actual){
                    auth.require(c.actor,actual);prepared[0]=true;
                    check(RulesCostFeasibility.assess(c.actor,actual,auth).status()==RulesCostFeasibility.Status.PAYABLE,"actual post-move pricing succeeds before deliberate rejection");
                    if(kind.equals("top-tax")||kind.equals("citadel")){
                        check(c.game.getTopLibForPlayer(c.actor)==c.source,"announcement retains exact original visible library top");
                        for(var viewer:c.game.getPlayers()){
                            var next=c.actor.getCardsIn(ZoneType.Library).get(0);
                            check(!next.mayPlayerLook(viewer)&&StateEncoder.encodeCard(next,viewer.getView())==null,"next library card not disclosed during announcement to seat="+viewer.getId());
                        }
                    }
                    throw new DeliberateRejection();
                }
            });throw new AssertionError("deliberate failure accepted");}catch(DeliberateRejection expected){check(prepared[0],"failure came from requested announcement boundary");}
        }
        var restored=c.game.getCardState(c.source);
        check(ids(c.originalZone).equals(originalOrder)&&c.originalZone.getCards().get(position)==restored,"original zone ordering and source position restored "+kind+" seat="+seat);
        check(c.game.getStackZone().isEmpty()&&c.game.getStack().isEmpty()&&!c.game.getStack().isFrozen(),"no orphan, real stack entry or frozen stack after rejected announcement");
        check(c.actor.getLife()==life&&c.lands.stream().map(Card::isTapped).toList().equals(tapped)&&c.actor.getManaPool().totalMana()==pool,"pre-payment rejection spends no life or mana");
        if(kind.equals("activation"))check(c.ability.getPayingMana().isEmpty()&&c.ability.getPayingManaAbilities().isEmpty(),"rejected reused activation cannot refund the prior activation's receipt");
        check(c.actor.getPaidForSA()==null&&c.game.getTopLibForPlayer(c.actor)==null,"cost lifecycle and top-library lock released");
        var restoredLookers=c.game.getPlayers().stream().map(restored::mayPlayerLook).toList();
        System.out.println("ROLLBACK_VISIBILITY_DIAGNOSTIC kind="+kind+" seat="+seat+" before="+lookers+" after="+restoredLookers);
        if(kind.equals("top-tax")||kind.equals("citadel")){
            for(int viewer=0;viewer<lookers.size();viewer++)check(!restoredLookers.get(viewer)||lookers.get(viewer),"failed top-card rollback grants no new viewer access");
            // Moving to Stack recomputes continuous permissions; native rollback
            // does not rebuild them. Exact library placement is proved above,
            // but loss of a prior look permission is a named failed-game limit.
        }else check(restoredLookers.equals(lookers),"original per-seat look permission preserved "+kind);
        if(kind.startsWith("exile")){
            check(restored.isFaceDown(),"original face-down exile state restored");
            check(c.game.getPlayers().stream().map(p->String.valueOf(StateEncoder.encodeCard(restored,p.getView()))).toList().equals(views),"both-seat hidden/known exile views preserved exactly");
        }
        for(var libraryCard:c.game.getCardsIn(ZoneType.Library)){
            var prior=libraryLookers.get(libraryCard.getId());for(int viewer=0;viewer<c.game.getPlayers().size();viewer++)
                check(!libraryCard.mayPlayerLook(c.game.getPlayers().get(viewer))||prior.get(viewer),"rollback grants no new access to any library identity");
        }
        BenchRandomAudit.assertUnchanged(stamp,"rejected controlled announcement "+kind);check(true,"no random consumption in tested rejected announcement");
        var after=BenchMenuStateAudit.capture(c.game);var changed=new ArrayList<String>();for(var key:internalBefore.keySet())if(!Objects.equals(internalBefore.get(key),after.get(key)))changed.add(key);
        System.out.println("ROLLBACK_INTERNAL_DIAGNOSTIC kind="+kind+" seat="+seat+" changedKeys="+changed+" missingKeys="+internalBefore.keySet().stream().filter(k->!after.containsKey(k)).toList());
        // Diagnostics are intentionally not reduced to equality: native rollback
        // allocates IDs, can replace card objects, and does not restore all fields.
    }
    static void runAll(){for(int seat=0;seat<2;seat++){for(String kind:List.of("hand","top-tax","citadel","exile-hidden","exile-known")){identities(seat,kind);rollback(seat,kind);}rollback(seat,"activation");activationIdentity(seat);}System.out.println("PASS "+checks+" exact announcement authorization and bounded rollback checks");}
}
