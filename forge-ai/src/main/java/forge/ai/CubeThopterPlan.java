package forge.ai;

import forge.StaticData;
import forge.card.CardEdition;
import forge.game.ability.AbilityKey;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityDisableTriggers;
import forge.game.staticability.StaticAbilityMode;
import forge.game.zone.ZoneType;
import forge.item.PaperToken;

/** Finite army-building plan, not an abstract infinite-combo shortcut or a
 * forced-win proof. Urza taps the recurring Sword, then Foundry spends that
 * mana and sacrifices Sword. Native triggers return/attach it. Every step
 * rechecks current legality and every resolution must make real progress. */
public final class CubeThopterPlan {
    private static final String URZA="Urza, Lord High Artificer", FOUNDRY="Thopter Foundry", SWORD="Sword of the Meek";
    private static final String TOKEN="u_1_1_a_thopter_flying";
    private final Player player;
    private int turn=-1, actions, failedTurn=-1, beforeTokens, beforeLife;
    private SpellAbility selected, pending;
    private Card paymentSword, paymentTap;

    public CubeThopterPlan(Player player) { this.player=player; }

    private Card find(String name) {
        for(Card card:player.getCardsIn(ZoneType.Battlefield))
            if(!card.isFaceDown() && name.equals(card.getName()))return card;
        return null;
    }
    private SpellAbility ability(Card card, ApiType api) {
        if(card!=null)for(SpellAbility original:card.getSpellAbilities())
            if(original.getApi()==api)return original.copy(player);
        return null;
    }
    private int tokens() {
        return (int)player.getCardsIn(ZoneType.Battlefield).stream()
                .filter(c->c.isToken() && c.getType().hasSubtype("Thopter") && c.getNetPower()>0).count();
    }

    /** Use native token rules without allocating a live card ID, choosing
     * random artwork, or modifying the game's token-edition pin cache. */
    private Card preview() {
        var rules=StaticData.instance().getAllTokens().getRules().get(TOKEN);
        if(rules==null)return null;
        PaperToken paper=new PaperToken(rules,CardEdition.UNKNOWN,TOKEN,"","") {
            @Override public String getImageKey(boolean alternate) { return getImageKey(0); }
        };
        Card card=CardFactory.getCard(paper,player,-1,player.getGame());
        card.setLastKnownZone(player.getZone(ZoneType.Battlefield));
        ComputerUtilCard.applyStaticContPT(player.getGame(),card,null);
        return card;
    }

    private boolean recursionAvailable(Card sword) {
        Card token=preview();
        if(token==null || token.getNetPower()!=1 || token.getNetToughness()!=1)return false;
        var event=AbilityKey.mapFromCard(token);
        event.put(AbilityKey.Origin,"None"); event.put(AbilityKey.Destination,"Battlefield");
        for(var trigger:sword.getTriggers()) {
            if(trigger.isSuppressed() || !"Graveyard".equals(trigger.getParam("TriggerZones"))
                    || !"Battlefield".equals(trigger.getParam("Destination")))continue;
            boolean disabled=false;
            for(Card source:player.getGame().getCardsIn(java.util.List.of(ZoneType.Battlefield,ZoneType.Command))) {
                if(source.isFaceDown())continue;
                for(var st:source.getStaticAbilities())
                    if(st.checkConditions(StaticAbilityMode.DisableTriggers)
                            && StaticAbilityDisableTriggers.isDisabled(st,trigger,event))disabled=true;
                // A public graveyard replacement invalidates the recurring
                // resource. Fail closed for matching replacements rather than
                // assume their optional or conditional result is favourable.
                for(var re:source.getReplacementEffects())
                    if(!re.isSuppressed() && "Moved".equals(re.getParam("Event"))
                            && "Graveyard".equals(re.getParam("Destination"))
                            && re.zonesCheck(source.getZone()) && re.requirementsCheck(player.getGame())
                            && re.matchesValidParam("ValidCard",sword))return false;
            }
            if(!disabled)return true;
        }
        return false;
    }

    public SpellAbility nextAction() {
        var game=player.getGame();
        int currentTurn=game.getPhaseHandler().getTurn();
        if(currentTurn!=turn) { turn=currentTurn; actions=0; selected=null; pending=null; }
        if(failedTurn==turn || actions>=160 || !game.getStack().isEmpty() || player.cantWin())return null;
        if(pending!=null) {
            boolean failed=tokens()<=beforeTokens || find(SWORD)==null || player.getLife()<beforeLife;
            pending=null;
            if(failed) {
                failedTurn=turn;
                System.err.println("CUBE_THOPTER_PLAN stopped-no-progress turn="+turn);
                return null;
            }
        }
        Card urza=find(URZA),foundry=find(FOUNDRY),sword=find(SWORD);
        if(urza==null || foundry==null || sword==null || !sword.isArtifact() || sword.isToken())return null;
        long budget=0;
        for(Player opponent:player.getOpponents())
            budget+=2L+Math.max(0,opponent.getLife())+opponent.getCreaturesInPlay().size();
        if(tokens()>=Math.min(64,budget))return null;
        SpellAbility make=ability(foundry,ApiType.Token),mana=ability(urza,ApiType.Mana);
        if(make==null || mana==null || !TOKEN.equals(make.getParam("TokenScript"))
                || !CubeComboAi.canPlayNative(make,player) || mana.isSuppressed() || !mana.isLegalAfterStack()
                || !sword.canBeSacrificedBy(make,false) || !recursionAvailable(sword))return null;
        var cost=ComputerUtilMana.calculateManaCost(make.getPayCosts(),make,player,true,0,false);
        if(cost.getConvertedManaCost()!=1 || cost.getGenericManaAmount()!=1)return null;
        if(mana.getPayCosts().getCostParts().size()!=1
                || !(mana.getPayCosts().getCostParts().get(0) instanceof CostTapType tap)
                || tap.getAbilityAmount(mana)!=1 || !"Artifact".equals(tap.getType()))return null;
        paymentSword=sword;
        paymentTap=sword.canTap()?sword:foundry.isArtifact() && foundry.canTap()?foundry:null;
        SpellAbility next;
        if(player.getManaPool().totalMana()<1) {
            if(paymentTap==null)return null;
            next=mana;
        } else next=make;
        if(!CubeComboAi.canPlayNative(next,player) || !CubeComboAi.canPayCost(next,player,false))return null;
        selected=next; actions++; return next;
    }

    public boolean owns(SpellAbility sa) { return sa==selected; }
    public boolean play(SpellAbility sa) {
        beforeTokens=tokens(); beforeLife=player.getLife();
        int beforeMana=player.getManaPool().totalMana();
        boolean played=ComputerUtil.handlePlayingSpellAbility(player,sa,null,current->new AiCostDecision(player,current,false) {
            @Override public PaymentDecision visit(CostTapType cost) {
                if(sa.getApi()==ApiType.Mana && current==sa && cost.getAbilityAmount(current)==1
                        && "Artifact".equals(cost.getType()) && paymentTap!=null && paymentTap.canTap()
                        && paymentTap.isInZone(ZoneType.Battlefield) && paymentTap.getController()==player
                        && paymentTap.isValid(cost.getType().split(";"),player,current.getHostCard(),current))return PaymentDecision.card(paymentTap);
                return super.visit(cost);
            }
            @Override public PaymentDecision visit(CostSacrifice cost) {
                if(sa.getApi()==ApiType.Token && current==sa && cost.getAbilityAmount(current)==1
                        && "Artifact.!token".equals(cost.getType()) && paymentSword!=null
                        && paymentSword.isInZone(ZoneType.Battlefield) && paymentSword.getController()==player
                        && paymentSword.isValid(cost.getType().split(";"),player,current.getHostCard(),current)
                        && paymentSword.canBeSacrificedBy(current,false))return PaymentDecision.card(paymentSword);
                return super.visit(cost);
            }
        });
        if(!played || sa.isManaAbility() && player.getManaPool().totalMana()<=beforeMana)failedTurn=turn;
        if(played && sa.getApi()==ApiType.Token)pending=sa;
        System.err.println("CUBE_THOPTER_PLAN "+(played?"played":"native-payment-failed")
                +" turn="+turn+" api="+sa.getApi()+" tokens="+tokens()+" mana="+player.getManaPool().totalMana());
        return played;
    }
    public boolean waitingForOwnSpell() {
        var stack=player.getGame().getStack();
        if(selected==null || turn!=player.getGame().getPhaseHandler().getTurn() || stack.isEmpty())return false;
        var top=stack.peekAbility();
        return top!=null && top.getActivatingPlayer()==player
                && (FOUNDRY.equals(top.getHostCard().getName()) || SWORD.equals(top.getHostCard().getName()));
    }
}
