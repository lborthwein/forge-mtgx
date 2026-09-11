package forge.ai;

import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Native Kitten/Teferi artifact replay plan. Library identities/order are not
 * inspected: Oracle availability comes from the player's own registered deck
 * minus known unavailable cards. Each draw, bounce and blink resolves normally. */
public final class CubeKittenPlan {
    private static final String KITTEN="Displacer Kitten", TEFERI="Teferi, Time Raveler", ORACLE="Thassa's Oracle";
    private static final List<String> ROCKS=List.of("Sol Ring","Mana Crypt","Grim Monolith","Basalt Monolith");
    private final Player player;
    private int turn=-1, actions, failedTurn=-1, libraryBefore;
    private SpellAbility selected, pending;
    private boolean active;
    private String rockName;
    private long teferiBefore;
    public CubeKittenPlan(Player player) {this.player=player;}

    private Card find(String name,ZoneType zone) {
        for(Card c:player.getCardsIn(zone)) if(!c.isFaceDown()&&c.getName().equals(name)) return c;
        return null;
    }
    private boolean knownOracle() {
        if(find(ORACLE,ZoneType.Hand)!=null) return true;
        return CubeComboAi.ownCopyOutside(player,ORACLE,
            ZoneType.Graveyard,ZoneType.Exile,ZoneType.Battlefield,ZoneType.Command,ZoneType.Stack);
    }
    private <T> T reserveBlue(SpellAbility sa,Supplier<T> task) {
        if(sa.getHostCard().getName().equals(ORACLE)) return task.get();
        List<Card> added=new ArrayList<>(); int count=0;
        var memory=AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL;
        for(Card c:player.getCardsIn(ZoneType.Battlefield)) {
            if(!c.isLand()||c.isTapped()) continue;
            boolean blue=c.getManaAbilities().stream().anyMatch(a->a.copy(player).canProduce("U"));
            if(!blue) continue;
            if(!AiCardMemory.isRememberedCard(player,c,memory)) {AiCardMemory.rememberCard(player,c,memory);added.add(c);}
            if(++count==2) break;
        }
        try{return task.get();}finally{for(Card c:added) AiCardMemory.forgetCard(player,c,memory);}
    }
    private boolean payable(SpellAbility sa) {
        return sa!=null&&CubeComboAi.canPlayNative(sa,player)&&reserveBlue(sa,()->CubeComboAi.canPayCost(sa,player,false));
    }
    private SpellAbility spell(Card card) {
        if(card==null) return null;
        for(SpellAbility original:card.getAllPossibleAbilities(player,false,null,true)) {
            SpellAbility sa=original.copy(player); if(sa.isSpell()&&payable(sa)) return sa;
        }
        return null;
    }
    private boolean target(SpellAbility sa,Card card) {
        sa.resetTargets(); if(!sa.canTarget(card)) return false; sa.getTargets().add(card);
        return sa.isTargetNumberValid()&&StaticAbilityMustTarget.meetsMustTargetRestriction(sa);
    }
    private SpellAbility bounce(Card teferi,Card rock) {
        for(SpellAbility original:teferi.getSpellAbilities()) {
            SpellAbility sa=original.copy(player);
            if(sa.getApi()==ApiType.ChangeZone&&"Hand".equals(sa.getParam("Destination"))&&target(sa,rock)&&payable(sa)) return sa;
        }
        return null;
    }
    private SpellAbility choose(SpellAbility sa) {selected=sa;actions++;active=true;return sa;}

    public SpellAbility nextAction() {
        var game=player.getGame();var phase=game.getPhaseHandler();
        if(turn!=phase.getTurn()) {turn=phase.getTurn();actions=0;active=false;selected=null;pending=null;rockName=null;}
        if(failedTurn==turn||actions>=200||player.cantWin()||!game.getStack().isEmpty()
                ||!(phase.is(PhaseType.MAIN1,player)||phase.is(PhaseType.MAIN2,player))) return null;
        int library=player.getCardsIn(ZoneType.Library).size();
        Card kitten=find(KITTEN,ZoneType.Battlefield), teferi=find(TEFERI,ZoneType.Battlefield);
        if(pending!=null) {
            boolean stalled=pending.getHostCard().getName().equals(TEFERI) && library>=libraryBefore
                || ROCKS.contains(pending.getHostCard().getName())&&pending.isSpell()
                   && (teferi==null||teferi.getGameTimestamp()==teferiBefore);
            pending=null;
            if(stalled) {failedTurn=turn;active=false;System.err.println("CUBE_KITTEN_PLAN stopped-no-progress turn="+turn);return null;}
        }
        if(kitten==null||teferi==null||!knownOracle()) {active=false;return null;}
        // Do not draw toward a finisher whose colored cost cannot currently
        // be funded. This tests resources only; it does not inspect Oracle's
        // position in the library or pretend it is presently castable.
        if(!CubeComboAi.canPayCost(new forge.game.cost.Cost("U U",false),
                teferi.getSpellAbilities().get(0).copy(player),player,false)) {active=false;return null;}
        // <=2 is conservative Oracle's own UU devotion, not an estimate of
        // hidden cards or an assertion that its trigger cannot be stopped.
        if(library<=2) {
            SpellAbility finish=spell(find(ORACLE,ZoneType.Hand));
            if(finish!=null) return choose(finish);
        }
        if(library==0||!player.canDrawAmount(1)) {active=false;return null;}
        for(String name:ROCKS) {
            Card rock=find(name,ZoneType.Battlefield);
            if(rock!=null) {
                SpellAbility bounce=bounce(teferi,rock);
                if(bounce==null) continue;
                // Capture useful rock mana BEFORE returning it to hand.
                for(SpellAbility original:rock.getManaAbilities()) {
                    SpellAbility mana=original.copy(player);
                    if(payable(mana)) {rockName=name;return choose(mana);}
                }
                // An ordinary replay must be affordable without consuming
                // Oracle's reserved blue sources. Current pool covers the
                // named rock's printed cost; actual cast checks follow.
                if(player.getManaPool().totalMana()>=rock.getManaCost().getCMC()) {rockName=name;return choose(bounce);}
            }
            SpellAbility cast=spell(find(name,ZoneType.Hand));
            if(cast!=null) {rockName=name;return choose(cast);}
        }
        active=false;return null;
    }

    public boolean chooseBlink(SpellAbility sa) {
        if(!active||turn!=player.getGame().getPhaseHandler().getTurn()||sa.getActivatingPlayer()!=player
            ||!sa.getHostCard().getName().equals(KITTEN)||sa.getApi()!=ApiType.ChangeZone
            ||!"Exile".equals(sa.getParam("Destination"))) return false;
        Object cause=sa.getRootAbility().getTriggeringObject(forge.game.ability.AbilityKey.SpellAbility);
        if(selected==null||!selected.isSpell()||!ROCKS.contains(selected.getHostCard().getName())
            ||!(cause instanceof SpellAbility cast)||cast.getActivatingPlayer()!=player
            ||cast.getHostCard()!=selected.getHostCard()) return false;
        Card teferi=find(TEFERI,ZoneType.Battlefield);
        if(teferi==null||!target(sa,teferi)) return false;
        System.err.println("CUBE_KITTEN_PLAN blink-teferi turn="+turn+" target="+teferi.getId());
        return true;
    }
    public boolean owns(SpellAbility sa) {return sa==selected;}
    public boolean play(SpellAbility sa) {
        libraryBefore=player.getCardsIn(ZoneType.Library).size();
        Card teferi=find(TEFERI,ZoneType.Battlefield);teferiBefore=teferi==null?-1:teferi.getGameTimestamp();
        boolean ok=reserveBlue(sa,()->ComputerUtil.handlePlayingSpellAbility(player,sa,null,current->new AiCostDecision(player,current,false)));
        if(ok&&!sa.isManaAbility()) pending=sa;
        if(!ok) {failedTurn=turn;active=false;}
        System.err.println("CUBE_KITTEN_PLAN "+(ok?"played":"native-payment-failed")+" turn="+turn+" card="+sa.getHostCard().getName()+" api="+sa.getApi()+" library="+libraryBefore);
        return ok;
    }
    public boolean waitingForOwnSpell() {
        var stack=player.getGame().getStack();if(!active||turn!=player.getGame().getPhaseHandler().getTurn()||stack.isEmpty()) return false;
        var top=stack.peekAbility();
        return top!=null&&top.getActivatingPlayer()==player&&(top.getHostCard().getName().equals(KITTEN)
            ||top.getHostCard().getName().equals(TEFERI)||top.getHostCard().getName().equals(ORACLE)
            ||top.getHostCard().getName().equals(rockName));
    }
}
