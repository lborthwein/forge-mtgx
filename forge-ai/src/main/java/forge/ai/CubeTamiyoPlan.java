package forge.ai;

import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.player.Player;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import java.util.*;

/** Finite own-visible Tamiyo/Kitten recurrence. Forge pays and resolves every
 * step; no library contents or opposing hidden zones enter this plan. */
final class CubeTamiyoPlan {
    private static final String TAMIYO="Tamiyo, Collector of Tales", KITTEN="Displacer Kitten", OUTLET="Aetherflux Reservoir";
    private final Player player;
    private Card tamiyo,kitten,outlet,artifact;
    private long tamiyoStamp,kittenStamp,outletStamp,artifactStamp;
    private int turn=-1,actions,lifeBefore,expectedGain;
    private boolean active,failed,castOutstanding;
    private SpellAbility selected,played,pending;
    private Kind kind;
    private Bound choice;
    private final IdentityHashMap<SpellAbility,Bound> triggerChoices=new IdentityHashMap<>();
    private enum Kind { RETURN, CAST, SACRIFICE }
    private record Bound(SpellAbility action,Card host,long stamp,Object cost,String costText,
                         Map<String,String> params,Map<String,String> original,List<Object> targets,Object permission,
                         Object mana,String manaChoice,ApiType api) {
        static Bound of(SpellAbility a) {
            return new Bound(a,a.getHostCard(),a.getHostCard().getGameTimestamp(),a.getPayCosts(),String.valueOf(a.getPayCosts()),
                    Map.copyOf(a.getMapParams()),Map.copyOf(a.getOriginalMapParams()),new ArrayList<>(a.getTargets()),a.getMayPlay(),
                    a.getManaPart(),a.getManaPart()==null?null:a.getManaPart().getExpressChoice(),a.getApi());
        }
        boolean matches(SpellAbility a,boolean targetsMayChange) {
            if(a!=action||a.getApi()!=api||a.isCopied()||a.isWrapper()||a.getRootAbility()!=a||a.getHostCard()!=host||host.getGameTimestamp()!=stamp
                    ||a.getPayCosts()!=cost||!String.valueOf(a.getPayCosts()).equals(costText)||a.getMayPlay()!=permission
                    ||!a.getMapParams().equals(params)||!a.getOriginalMapParams().equals(original)||a.getManaPart()!=mana
                    ||!Objects.equals(manaChoice,a.getManaPart()==null?null:a.getManaPart().getExpressChoice()))return false;
            if(!targetsMayChange){if(a.getTargets().size()!=targets.size())return false;for(int i=0;i<targets.size();i++)if(a.getTargets().get(i)!=targets.get(i))return false;}
            return true;
        }
    }
    CubeTamiyoPlan(Player p){player=p;}
    private boolean current(Card c,ZoneType z){return c!=null&&c==player.getGame().getCardState(c,null)&&c.isInZone(z)&&c.getOwner()==player&&c.getController()==player&&!c.isFaceDown()&&!c.isPhasedOut();}
    private Card find(String name,ZoneType z){for(Card c:player.getCardsIn(z))if(name.equals(c.getName())&&current(c,z))return c;return null;}
    private SpellAbility stop(){failed=true;selected=null;choice=null;triggerChoices.clear();return null;}
    private boolean window(){return player.getGame().getPhaseHandler().getTurn()==turn&&player.getGame().getPhaseHandler().is(PhaseType.MAIN1,player);}
    private boolean board(){return current(kitten,ZoneType.Battlefield)&&kitten.getGameTimestamp()==kittenStamp&&current(outlet,ZoneType.Battlefield)&&outlet.getGameTimestamp()==outletStamp
            &&current(tamiyo,ZoneType.Battlefield)&&tamiyo.getGameTimestamp()==tamiyoStamp&&player.getLife()>0&&player.canGainLife()&&!player.cantWin();}
    // Diagnostic only: report the first rejecting own/public precondition without
    // repeating a native query or changing the selected action.
    private boolean decline(String reason) {
        System.err.println("CUBE_PLAN_DECLINE family=tamiyo reason="+reason);
        return false;
    }
    private boolean nativeLoyaltyEntry(Card c, forge.game.replacement.ReplacementEffect e) {
        if(c!=tamiyo||!c.isPlaneswalker()||!"5".equals(c.getCurrentState().getBaseLoyalty())
                ||e!=c.getCurrentState().getLoyaltyRep()||e.getHostCard()!=c||!e.isIntrinsic())return false;
        Map<String,String> params=new HashMap<>(e.getMapParams());params.remove("Description");
        if(!params.equals(Map.of("Event","Moved","ValidCard","Card.Self","Destination","Battlefield","Secondary","True","ReplacementResult","Updated")))return false;
        SpellAbility a=e.getOverridingAbility();
        return a!=null&&a.getHostCard()==c&&a.isIntrinsic()&&!a.isCopied()&&!a.isWrapper()&&!a.isTrigger()
                &&a.getApi()==ApiType.PutCounter&&a.getSubAbility()==null&&a.getPayCosts()==null&&!a.usesTargeting()
                &&a.getMapParams().equals(Map.of("DB","PutCounter","Defined","Self","CounterType","LOYALTY","ETB","True","CounterNum","5"))
                &&a.getOriginalMapParams().equals(a.getMapParams());
    }
    private boolean domain(){
        for(ZoneType z:List.of(ZoneType.Battlefield,ZoneType.Command,ZoneType.Graveyard))for(Card c:player.getGame().getCardsIn(z))if(!c.isFaceDown()&&!c.isPhasedOut()){
            for(var e:c.getReplacementEffects())if(e.zonesCheck(c.getZone())&&e.requirementsCheck(player.getGame())&&Set.of("Moved","GainLife","LifeReduced","DamageDone","PayLife").contains(e.getParamOrDefault("Event",""))&&!nativeLoyaltyEntry(c,e))return decline("replacement:"+c.getName().replace(' ','_')+":"+e.getParamOrDefault("Event",""));
            for(var s:c.getStaticAbilities())if(s.zonesCheck()&&Set.of("RaiseCost","ReduceCost","SetCost","CantBeCast","CantBeActivated","CantSacrifice","CantPayLife","DisableTriggers").contains(s.getParamOrDefault("Mode",""))){
                if(c==tamiyo&&"CantSacrifice".equals(s.getParam("Mode"))&&"False".equals(s.getParam("ForCost"))&&"SpellAbility.OppCtrl".equals(s.getParam("ValidCause")))continue;
                return decline("static:"+c.getName().replace(' ','_')+":"+s.getParam("Mode"));
            }
            for(var t:c.getTriggers())if(!t.isSuppressed()&&t.getParamOrDefault("TriggerZones","Battlefield").contains(z.name())){
                if((c==kitten||c==outlet)&&t.isIntrinsic()&&"SpellCast".equals(t.getMode().name()))continue;
                if(Set.of("SpellCast","SpellAbilityCast","AbilityCast","AbilityResolves","AbilityTriggered","ChangesZone","ChangesZoneAll","Sacrificed","SacrificedOnce","LifeGained","LifeLost","Always").contains(t.getMode().name()))return decline("trigger:"+c.getName().replace(' ','_')+":"+t.getMode().name());
            }
        }return true;
    }
    private SpellAbility ability(Card c,ApiType api){if(c!=null)for(var original:c.getSpellAbilities())if(original.isActivatedAbility()&&original.getApi()==api&&original.getSubAbility()==null)return original.copy(player);return null;}
    private boolean payable(SpellAbility a){return a!=null&&a.getPayCosts()!=null&&CubeComboAi.canPlayNative(a,player)&&CubeComboAi.canPayCost(a,player,false);}
    private boolean target(SpellAbility a,forge.game.GameEntity target){if(a==null||!a.canTarget(target))return false;a.resetTargets();a.getTargets().add(target);return a.isTargetNumberValid()&&forge.game.staticability.StaticAbilityMustTarget.meetsMustTargetRestriction(a);}
    private boolean finishAvailable(){
        if(player.getOpponents().size()!=1)return decline("opponent-count");Player op=player.getOpponents().get(0);SpellAbility a=ability(outlet,ApiType.DealDamage);
        if(op.getLife()<=0||op.getLife()>50||op.cantLose()||op.cantLoseForZeroOrLessLife()||!op.canLoseLife()||!target(a,op)||!"50".equals(a.getParam("NumDmg")))return decline("shot-target-or-shape");
        var parts=a.getPayCosts().getCostParts();
        return parts.size()==1&&parts.get(0) instanceof forge.game.cost.CostPayLife cost&&cost.getAbilityAmount(a)==50&&!a.isSuppressed()&&!outlet.isDetained()
                &&a.getRestrictions().canPlay(outlet,a)&&a.isLegalAfterStack()&&a.checkRestrictions(outlet,player)
                &&ComputerUtilCombat.predictDamageTo(op,50,outlet,false)>=op.getLife() || decline("shot-cost-or-restrictions:"+a.getPayCosts());
    }
    private boolean canReturn(){SpellAbility a=ability(tamiyo,ApiType.ChangeZone);return a!=null&&"Graveyard".equals(a.getParam("Origin"))&&"Hand".equals(a.getParam("Destination"))
            &&tamiyo.getCounters(CounterEnumType.LOYALTY)>3&&tamiyo.getPlaneswalkerAbilityActivated()==0&&!a.isSuppressed()&&!tamiyo.isDetained()
            &&a.getRestrictions().canPlay(tamiyo,a)&&a.isLegalAfterStack()&&a.checkRestrictions(tamiyo,player);}
    private SpellAbility cast(){
        if(!current(artifact,ZoneType.Hand))return null;
        for(var original:artifact.getAllPossibleAbilities(player,false,null,true)){var a=original.copy(player);if(a.isSpell()&&!a.isCopied()&&a.getSubAbility()==null&&payable(a))return a;}return null;
    }
    private SpellAbility select(SpellAbility a,Kind k){if(a==null)return stop();selected=a;kind=k;choice=Bound.of(a);return a;}
    private SpellAbility nativePending(SpellAbility a){for(var e:player.getGame().getStack()){var b=e.getSpellAbility();if(b.getOriginalAbility()==a&&b.getActivatingPlayer()==player&&!b.isCopied()&&b.getHostCard()==a.getHostCard())return b;}return null;}
    private boolean pendingCast(){return castOutstanding&&played!=null&&played.isSpell()&&nativePending(played)!=null;}
    SpellAbility nextAction(){
        int now=player.getGame().getPhaseHandler().getTurn();if(now!=turn){turn=now;active=failed=castOutstanding=false;actions=0;selected=played=pending=null;choice=null;triggerChoices.clear();}
        if(failed||!window()||!player.getGame().getStack().isEmpty())return null;
        if(selected!=null)return choice.matches(selected,false)&&board()&&domain()&&payable(selected)?selected:stop();
        if(!active){
            tamiyo=find(TAMIYO,ZoneType.Battlefield);kitten=find(KITTEN,ZoneType.Battlefield);outlet=find(OUTLET,ZoneType.Battlefield);
            if(tamiyo==null||kitten==null||outlet==null)return null;tamiyoStamp=tamiyo.getGameTimestamp();kittenStamp=kitten.getGameTimestamp();outletStamp=outlet.getGameTimestamp();
            if(!board()){decline("initial-board");return null;}
            if(!domain()||!finishAvailable())return null;
            artifact=null;for(String name:List.of("Lotus Petal","Lion's Eye Diamond")){for(ZoneType z:List.of(ZoneType.Hand,ZoneType.Graveyard,ZoneType.Battlefield)){artifact=find(name,z);if(artifact!=null)break;}if(artifact!=null)break;}
            if(artifact==null){decline("no-artifact");return null;}
            if(!current(artifact,ZoneType.Hand)&&!canReturn()){decline("return-restrictions");return null;}
            artifactStamp=artifact.getGameTimestamp();active=true;
        }
        if(played!=null){
            Card fresh=player.getGame().getCardState(artifact,null);
            if(kind==Kind.RETURN){if(!current(fresh,ZoneType.Hand)||fresh.getGameTimestamp()==artifactStamp||player.getLife()!=lifeBefore)return stop();}
            else if(kind==Kind.CAST){
                Card freshTamiyo=player.getGame().getCardState(tamiyo,null);
                if(!current(fresh,ZoneType.Battlefield)||fresh.getGameTimestamp()==artifactStamp||!current(freshTamiyo,ZoneType.Battlefield)||freshTamiyo.getGameTimestamp()==tamiyoStamp
                        ||freshTamiyo.getCounters(CounterEnumType.LOYALTY)!=5||freshTamiyo.getPlaneswalkerAbilityActivated()!=0||player.getLife()!=lifeBefore+expectedGain)return stop();
                tamiyo=freshTamiyo;tamiyoStamp=tamiyo.getGameTimestamp();castOutstanding=false;
            }else if(!current(fresh,ZoneType.Graveyard)||fresh.getGameTimestamp()==artifactStamp||player.getLife()!=lifeBefore)return stop();
            artifact=fresh;artifactStamp=artifact.getGameTimestamp();played=pending=null;
        }
        if(actions>=64||!board()||!domain()||!finishAvailable()||artifact.getGameTimestamp()!=artifactStamp)return stop();
        // The earlier Top plan owns the native Reservoir shot.
        if(player.getLife()>50)return null;
        if(current(artifact,ZoneType.Hand))return select(cast(),Kind.CAST);
        if(!canReturn())return stop();
        if(current(artifact,ZoneType.Graveyard)){SpellAbility a=ability(tamiyo,ApiType.ChangeZone);return select(target(a,artifact)&&payable(a)?a:null,Kind.RETURN);}
        if(current(artifact,ZoneType.Battlefield)){SpellAbility a=ability(artifact,ApiType.Mana);if(a!=null)a.getManaPart().setExpressChoice("U");return select(payable(a)?a:null,Kind.SACRIFICE);}
        return stop();
    }
    private boolean resourceReady(){return current(artifact,kind==Kind.RETURN?ZoneType.Graveyard:kind==Kind.CAST?ZoneType.Hand:ZoneType.Battlefield)&&artifact.getGameTimestamp()==artifactStamp;}
    boolean owns(SpellAbility a){return a!=null&&a==selected;}
    boolean play(SpellAbility a){
        if(!owns(a)||!choice.matches(a,false)||a.getSubAbility()!=null||a.getActivatingPlayer()!=player||!window()||!board()||!domain()||!finishAvailable()||!resourceReady()||!payable(a)){stop();return false;}
        lifeBefore=player.getLife();int loyalty=tamiyo.getCounters(CounterEnumType.LOYALTY),mana=player.getManaPool().totalMana();
        Set<Integer> hand=new HashSet<>();for(Card c:player.getCardsIn(ZoneType.Hand))hand.add(c.getId());
        expectedGain=(int)player.getGame().getStack().getSpellCardsCastThisTurn().stream().filter(c->c.getController()==player).count()+1;
        played=a;castOutstanding=kind==Kind.CAST;
        boolean ok=ComputerUtil.handlePlayingSpellAbility(player,a,null,current->new AiCostDecision(player,current,false));
        if(ok&&kind==Kind.RETURN)ok=tamiyo.getCounters(CounterEnumType.LOYALTY)==loyalty-3;
        if(ok&&kind==Kind.SACRIFICE){
            int count=0,tracked=0;for(Card c:a.getPaidList("Sacrificed")){count++;if(c.getId()==artifact.getId())tracked++;}
            Set<Integer> discarded=new HashSet<>();for(Card c:a.getPaidList("Discarded"))discarded.add(c.getId());
            boolean led="Lion's Eye Diamond".equals(artifact.getName());ok=count==1&&tracked==1&&player.getManaPool().totalMana()==mana+(led?3:1)
                    &&(!led||discarded.equals(hand)&&player.getCardsIn(ZoneType.Hand).isEmpty());
        }else if(ok){pending=nativePending(a);ok=pending!=null;}
        selected=null;choice=null;if(ok)actions++;else stop();
        System.err.println("CUBE_TAMIYO_PLAN played="+ok+" kind="+kind+" actions="+actions);return ok;
    }
    void withTriggers(List<SpellAbility> offered,Runnable nativeWork){
        triggerChoices.clear();
        try{if(active&&!failed&&window()&&pendingCast())for(SpellAbility wrapper:offered)if(wrapper instanceof WrappedAbility w){SpellAbility a=w.getWrappedAbility();if(blinkShape(a))triggerChoices.put(a,Bound.of(a));}nativeWork.run();}
        finally{triggerChoices.clear();}
    }
    private boolean blinkShape(SpellAbility a){
        if(a==null||a.isCopied()||a.isWrapper()||a.getRootAbility()!=a||a.getActivatingPlayer()!=player||a.getHostCard()!=kitten||!current(kitten,ZoneType.Battlefield)||kitten.getGameTimestamp()!=kittenStamp
                ||a.getApi()!=ApiType.ChangeZone||!a.isTrigger()||!"Battlefield".equals(a.getParam("Origin"))||!"Exile".equals(a.getParam("Destination"))||a.getMinTargets()!=0||a.getMaxTargets()!=1)return false;
        Object cause=a.getTriggeringObject(AbilityKey.SpellAbility);SpellAbility actual=nativePending(played);if(actual==null||cause!=played&&cause!=actual)return false;
        var sub=a.getSubAbility();if(sub==null||sub.getApi()!=ApiType.ChangeZone||!"Exile".equals(sub.getParam("Origin"))||!"Battlefield".equals(sub.getParam("Destination"))||!"Remembered".equals(sub.getParam("Defined")))return false;
        return sub.getSubAbility()!=null&&sub.getSubAbility().getApi()==ApiType.Cleanup&&sub.getSubAbility().getSubAbility()==null;
    }
    boolean chooseBlink(SpellAbility a){Bound b=triggerChoices.get(a);if(b==null||!b.matches(a,true)||!blinkShape(a)||!board()||!domain()||!pendingCast())return false;return target(a,tamiyo);}
    boolean waitingForOwnSpell(){if(!active||failed||!window()||player.getGame().getStack().isEmpty())return false;SpellAbility a=player.getGame().getStack().peekAbility();return a==pending||a!=null&&a.getActivatingPlayer()==player&&a.isWrapper()&&castOutstanding&&(a.getHostCard()==kitten||a.getHostCard()==outlet);}
}
