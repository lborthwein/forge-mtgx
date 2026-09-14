package forge.ai;

import forge.game.GameEntity;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import java.util.List;
import java.util.Map;

/** Finite native Kiki / uncopied Metamorph conversion through Syr and Reservoir.
 * No fabricated tokens, counters, life, zone changes or hidden-zone forecasts. */
final class CubeKikiSyrPlan {
    private static final String KIKI="Kiki-Jiki, Mirror Breaker", META="Phyrexian Metamorph",
            SYR="Syr Ginger, the Meal Ender", SHOT="Aetherflux Reservoir";
    private final Player player;
    private Card kiki,meta,syr,outlet,selectedHost;
    private long metaStamp,syrStamp,outletStamp,kikiStamp,selectedStamp;
    private SpellAbility selected,pending;
    private forge.game.cost.Cost selectedCost;
    private String selectedCostText;
    private Map<String,String> selectedParams=Map.of();
    private List<?> selectedTargets=List.of();
    private int turn=-1,actions,newToken=-1,beforeLife,beforeCounters,expectedCounters,gainPower;
    private boolean active,failed,cloneChosen,legendChosen;
    private ApiType pendingApi,selectedApi;
    CubeKikiSyrPlan(Player p){player=p;}
    private boolean current(Card c,long stamp){return c!=null&&c.getGameTimestamp()==stamp&&c==player.getGame().getCardState(c,null)
            &&c.isInZone(ZoneType.Battlefield)&&c.getController()==player&&c.getOwner()==player&&!c.isFaceDown()&&!c.isPhasedOut();}
    private Card find(String name){for(Card c:player.getCardsIn(ZoneType.Battlefield))if(c.getName().equals(name)&&current(c,c.getGameTimestamp()))return c;return null;}
    private SpellAbility stop(){System.err.println("CUBE_KIKI_SYR_PLAN stopped stage="+pendingApi+" token="+newToken+" cloned="+cloneChosen+" legend="+legendChosen);failed=true;selected=null;return null;}
    private boolean window(){return player.getGame().getPhaseHandler().getTurn()==turn&&player.getGame().getPhaseHandler().is(PhaseType.MAIN1,player);}
    private boolean payable(SpellAbility a){return a!=null&&CubeComboAi.canPlayNative(a,player)&&CubeComboAi.canPayCost(a,player,false);}
    private SpellAbility ability(Card c,ApiType api,Card target){
        if(c==null)return null;
        for(SpellAbility original:c.getSpellAbilities()){
            if(!original.isActivatedAbility()||original.getApi()!=api||original.getSubAbility()!=null)continue;
            SpellAbility a=original.copy(player);
            if(target!=null){if(!a.canTarget(target))continue;a.getTargets().add(target);}
            if(api==ApiType.DealDamage){Player op=player.getOpponents().iterator().next();if(!a.canTarget(op))continue;a.getTargets().add(op);}
            if(payable(a))return a;
        }return null;
    }
    private SpellAbility select(SpellAbility a){
        selected=a;if(a==null)return null;selectedHost=a.getHostCard();selectedStamp=selectedHost.getGameTimestamp();
        selectedCost=a.getPayCosts();selectedCostText=selectedCost.toString();selectedApi=a.getApi();
        selectedParams=Map.copyOf(a.getMapParams());selectedTargets=List.copyOf(a.getTargets());return a;
    }
    boolean owns(SpellAbility a){return a!=null&&a==selected;}
    private boolean bound(SpellAbility a){
        if(!owns(a)||!window()||!current(selectedHost,selectedStamp)||a.getActivatingPlayer()!=player||a.isCopied()||a.isWrapper()
                ||a.getRootAbility()!=a||a.getSubAbility()!=null||a.getHostCard()!=selectedHost||a.getApi()!=selectedApi
                ||a.getMayPlay()!=null||a.getPayCosts()!=selectedCost||!a.getPayCosts().toString().equals(selectedCostText)
                ||!a.getMapParams().equals(selectedParams)||a.getTargets().size()!=selectedTargets.size())return false;
        for(int i=0;i<selectedTargets.size();i++)if(a.getTargets().get(i)!=selectedTargets.get(i))return false;
        return true;
    }
    /** Inspect only public active life-gain replacements; future native gain remains authoritative. */
    private boolean gainUnreplaced(){
        if(syr==null||!player.canGainLife())return false;
        var params=forge.game.ability.AbilityKey.mapFromAffected(player);
        params.put(forge.game.ability.AbilityKey.Source,syr);
        params.put(forge.game.ability.AbilityKey.LifeGained,Math.max(syr.getNetPower(),51-player.getLife()));
        for(ZoneType zone:new ZoneType[]{ZoneType.Battlefield,ZoneType.Command})for(Card c:player.getGame().getCardsIn(zone)){
            if(c.isFaceDown())continue;
            for(var re:c.getReplacementEffects())if(re.modeCheck(forge.game.replacement.ReplacementType.GainLife,params)
                    &&re.zonesCheck(c.getZone())&&re.requirementsCheck(player.getGame())&&re.canReplace(params))return false;
        }
        return true;
    }
    private boolean stable(){return current(meta,metaStamp)&&meta.getName().equals(META)&&!meta.getType().isLegendary()&&meta.getNetToughness()>0
            &&current(outlet,outletStamp)&&outlet.getName().equals(SHOT)&&!player.cantWin()&&player.getLife()>0;}
    private boolean ready(SpellAbility a){
        if(!stable())return false;
        if(a.getApi()==ApiType.DealDamage)return true;
        if(!current(syr,syrStamp)||!gainUnreplaced())return false;
        return a.getApi()!=ApiType.CopyPermanent||current(kiki,kikiStamp)&&ability(syr,ApiType.GainLife,null)!=null;
    }
    private boolean activeStack(){
        if(pending==null)return false;
        for(var e:player.getGame().getStack())if(e.getSpellAbility()==pending)return true;
        return false;
    }
    boolean waitingForOwnSpell(){
        if(!active||failed||!window())return false;
        if(activeStack())return true;
        if(pendingApi==ApiType.CopyPermanent)for(var e:player.getGame().getStack()){
            var a=e.getSpellAbility();if(a.isTrigger()&&a.getActivatingPlayer()==player&&a.getHostCard()==syr)return true;
        }
        return false;
    }
    SpellAbility nextAction(){
        int now=player.getGame().getPhaseHandler().getTurn();
        if(now!=turn){turn=now;active=false;failed=false;selected=null;pending=null;pendingApi=null;actions=0;}
        if(failed||!window()||!player.getGame().getStack().isEmpty())return null;
        if(selected!=null)return bound(selected)&&ready(selected)&&payable(selected)?selected:stop();
        if(active&&pendingApi!=null){
            if(pendingApi==ApiType.CopyPermanent){
                Card next=null;for(Card c:player.getCardsIn(ZoneType.Battlefield))if(c.getId()==newToken)next=c;
                if(!stable()||!current(syr,syrStamp)||!cloneChosen||!legendChosen||next==null||!current(next,next.getGameTimestamp())
                        ||next==kiki||!next.isToken()||!next.isArtifact()||!next.getName().equals(KIKI)||next.isTapped()||next.isAbilitySick()
                        ||kiki.isInZone(ZoneType.Battlefield)||player.getLife()!=beforeLife||syr.getCounters(CounterEnumType.P1P1)!=expectedCounters)return stop();
                kiki=next;kikiStamp=next.getGameTimestamp();
            }else if(pendingApi==ApiType.GainLife){
                if(!stable()||syr.isInZone(ZoneType.Battlefield)||player.getLife()!=beforeLife+gainPower)return stop();
            }else return stop();
            pending=null;pendingApi=null;
        }
        if(!active){
            meta=find(META);syr=find(SYR);outlet=find(SHOT);kiki=find(KIKI);
            if(meta==null||syr==null||outlet==null||kiki==null||meta.getType().isLegendary()||meta.getNetToughness()<=0||!player.canGainLife())return null;
            metaStamp=meta.getGameTimestamp();syrStamp=syr.getGameTimestamp();outletStamp=outlet.getGameTimestamp();kikiStamp=kiki.getGameTimestamp();
            if(!stable()||!gainUnreplaced()||ability(syr,ApiType.GainLife,null)==null)return null;
            active=true;
        }
        if(!stable()||actions>=64)return stop();
        if(player.getLife()>50)return select(ability(outlet,ApiType.DealDamage,null));
        if(!current(syr,syrStamp)||!current(kiki,kikiStamp)||!gainUnreplaced())return stop();
        SpellAbility gain=ability(syr,ApiType.GainLife,null);if(gain==null)return stop();
        if((long)player.getLife()+syr.getNetPower()>50)return select(gain);
        return select(ability(kiki,ApiType.CopyPermanent,meta));
    }
    boolean play(SpellAbility a){
        if(!bound(a)||!ready(a)||!payable(a)){stop();return false;}
        beforeLife=player.getLife();beforeCounters=syr==null?0:syr.getCounters(CounterEnumType.P1P1);
        expectedCounters=beforeCounters+(kiki!=null&&kiki.isArtifact()?1:0);gainPower=syr==null?0:syr.getNetPower();
        cloneChosen=false;legendChosen=false;newToken=-1;
        if(!ComputerUtil.handlePlayingSpellAbility(player,a,null,current->new AiCostDecision(player,current,false))){stop();return false;}
        actions++;pendingApi=a.getApi();pending=null;
        for(var e:player.getGame().getStack()){
            var n=e.getSpellAbility();if(n.getOriginalAbility()==a&&!n.isCopied()&&n.getHostCard()==a.getHostCard()&&n.getActivatingPlayer()==player){
                if(pending!=null){stop();return false;}pending=n;
            }
        }
        if(pending==null){stop();return false;}
        if(pendingApi==ApiType.GainLife){
            java.util.List<Card> paid=new java.util.ArrayList<>();a.getPaidList("Sacrificed").forEach(paid::add);if(paid.size()!=1||paid.get(0).getId()!=syr.getId()||paid.get(0).getNetPower()!=gainPower||a.getPayingMana().size()!=2){stop();return false;}
        }
        selected=null;System.err.println("CUBE_KIKI_SYR_PLAN played action="+actions+" source="+a.getHostCard().getName().replace(' ','_')+" api="+pendingApi);return true;
    }
    private boolean cloneEvent(SpellAbility a){
        if(!active||failed||!window()||pendingApi!=ApiType.CopyPermanent||!activeStack()||!player.getGame().getStack().isResolving()
                ||a==null||a.getApi()!=ApiType.Clone||!a.isReplacementAbility()||a.isCopied()||a.isWrapper()
                ||a.getRootAbility()!=a||a.getSubAbility()!=null||!a.getMapParams().equals(a.getOriginalMapParams())
                ||a.getReplacementEffect()==null||a.getReplacementEffect().getOverridingAbility()!=a
                ||a.getActivatingPlayer()!=null&&a.getActivatingPlayer()!=player||!current(kiki,kikiStamp)||!current(meta,metaStamp))return false;
        Card host=a.getHostCard();
        if(host==null||a.getReplacementEffect().getHostCard()!=host)return false;
        boolean attached=false;for(var re:host.getReplacementEffects())if(re==a.getReplacementEffect())attached=true;
        return attached&&host.isToken()&&host.getOwner()==player&&host.getController()==player
                &&host.getName().equals(META)&&host.getId()!=meta.getId()&&!host.isInZone(ZoneType.Battlefield)
                &&pending.getTargets().size()==1&&pending.getTargetCard()==meta;
    }
    boolean confirmClone(SpellAbility a){return cloneEvent(a);}
    <T extends GameEntity>T choose(FCollectionView<T> options,SpellAbility a){
        if(cloneEvent(a)&&(!cloneChosen||newToken==a.getHostCard().getId())){
            for(T e:options)if(e==kiki){newToken=a.getHostCard().getId();cloneChosen=true;return e;}
        }
        if(active&&!failed&&window()&&pendingApi==ApiType.CopyPermanent&&cloneChosen&&a!=null
                &&a.getApi()==ApiType.InternalLegendaryRule&&a instanceof SpellAbility.EmptySa&&!a.isCopied()&&!a.isWrapper()
                &&a.getRootAbility()==a&&a.getSubAbility()==null&&a.getHostCard()!=null&&a.getHostCard().getId()==-1
                &&a.getActivatingPlayer()==player&&current(kiki,kikiStamp)){
            T next=null;boolean old=false;
            for(T e:options){
                if(e==kiki)old=true;
                if(e instanceof Card c&&c.getId()==newToken&&current(c,c.getGameTimestamp())&&c.isToken()&&c.isArtifact()&&c.getName().equals(KIKI)&&!c.isTapped())next=e;
            }
            if(old&&next!=null){legendChosen=true;return next;}
        }
        return null;
    }
}
