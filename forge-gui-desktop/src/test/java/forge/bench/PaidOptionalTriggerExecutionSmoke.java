package forge.bench;

import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import java.util.*;
import static forge.bench.OptionalTriggerExecutionSmoke.*;

/** Actual Mana Vault upkeep, wrapper insertion, confirmation and full payment. */
public final class PaidOptionalTriggerExecutionSmoke {
    private record Outcome(boolean tapped,long lands,int mana,int life,Object rng) { }
    private static Outcome nativeControl(int seat,String mode,Boolean scripted) {
        var c=context(seat,mode,scripted);c.game().getPhaseHandler().devModeSet(PhaseType.UPKEEP,c.actor());
        var vault=card("Mana Vault",c);vault.setTapped(true);
        for(int i=0;i<4;i++)card("Plains",c);
        ready(c);queue(c);drain(c);
        var rng=((BenchRandomAudit.AuditedRandom)forge.util.MyRandom.getRandom()).snapshot().deepCopy();rng.remove("purityChecks");
        return new Outcome(vault.isTapped(),c.actor().getCardsIn(ZoneType.Battlefield).stream()
                .filter(x->x.getName().equals("Plains")&&x.isTapped()).count(),c.actor().getManaPool().totalMana(),c.actor().getLife(),rng);
    }
    private static void scopeMisuse(int seat) {
        var c=context(seat,"BRIDGE",null);c.game().getPhaseHandler().devModeSet(PhaseType.UPKEEP,c.actor());
        var vault=card("Mana Vault",c);vault.setTapped(true);for(int i=0;i<4;i++)card("Plains",c);
        ready(c);var wrapper=queue(c);var underlying=wrapper.getWrappedAbility();
        var scope=new OptionalManaTriggerExecution.Resolution(c.actor(),wrapper);
        reject(()->scope.requirePayment(c.actor(),underlying),"quote authority is not payment authority");
        scope.requireConfirmation(c.actor(),wrapper);scope.answer(true);
        reject(()->scope.requirePayment(c.actor(),underlying),"confirmation alone cannot authorize execution");
        scope.consume(c.actor(),underlying);scope.requirePayment(c.actor(),underlying);
        reject(()->scope.consume(c.actor(),underlying),"native callback authorization single use");
        var original=underlying.getPayCosts();underlying.setPayCosts(new forge.game.cost.Cost("4",true));
        reject(()->scope.requirePayment(c.actor(),underlying),"equal-valued replacement cost object is stale");
        underlying.setPayCosts(original);scope.finish();scope.close();
        reject(()->scope.requirePayment(c.actor(),underlying),"closed payment scope rejected");
        var declined=new OptionalManaTriggerExecution.Resolution(c.actor(),wrapper);declined.answer(false);
        reject(()->declined.consume(c.actor(),underlying),"decline does not authorize paid effect");declined.close();
        var omitted=new OptionalManaTriggerExecution.Resolution(c.actor(),wrapper);omitted.answer(true);
        reject(omitted::finish,"accepted trigger cannot omit native effect callback");omitted.close();
    }
    private static void vault(int seat, int lands, boolean accept, String fault) {
        var c=context(seat,"BRIDGE",null);
        c.game().getPhaseHandler().devModeSet(PhaseType.UPKEEP,c.actor());
        var vault=card("Mana Vault",c);vault.setTapped(true);
        for(int i=0;i<lands;i++)card("Plains",c);
        ready(c); c.host().yes=accept;c.host().fault=fault;
        var wrapper=queue(c);
        check(wrapper!=null&&wrapper.getPayCosts().getTotalMana().isZero(),"native wrapper costs zero");
        check(wrapper.getWrappedAbility().getPayCosts().getTotalMana().getGenericCost()==4,"underlying native ability still costs four");
        check(c.host().kinds.isEmpty()&&vault.isTapped(),"stack insertion neither asks nor pays nor untaps");
        check(c.actor().getCardsIn(ZoneType.Battlefield).stream().filter(x->x.getName().equals("Plains")).noneMatch(x->x.isTapped()),"lands untouched before resolution");
        var bridge=(PlayerControllerBridge)c.actor().getController();
        reject(()->bridge.playSpellAbilityNoStack(wrapper.getWrappedAbility(),false),"paid trigger cannot execute outside resolution");
        // That deliberate illegal callback latches failure in the session. Use a
        // fresh context for the healthy native lifecycle, never continue it.
    }
    private static void resolve(int seat,int lands,boolean accept,String fault) {
        var c=context(seat,"BRIDGE",null);c.game().getPhaseHandler().devModeSet(PhaseType.UPKEEP,c.actor());
        var vault=card("Mana Vault",c);vault.setTapped(true);
        for(int i=0;i<lands;i++)card("Plains",c);
        ready(c);c.host().yes=accept;c.host().fault=fault;
        var wrapper=queue(c);
        check(c.host().kinds.isEmpty()&&vault.isTapped(),"free insertion before optional payment");
        if(!fault.isEmpty()) {
            reject(()->drain(c),"invalid full-cost answer fails closed: "+fault);
            check(c.session().integrityFailure(c.game())!=null,"paid failure latches game invalidity");
            check(vault.isTapped(),"invalid payment cannot resolve untap");
            check(c.actor().getCardsIn(ZoneType.Battlefield).stream().filter(x->x.getName().equals("Plains")).noneMatch(x->x.isTapped()),"malformed witness spends no sources");
            reject(()->((PlayerControllerBridge)c.actor().getController()).chooseSpellAbilityToPlay(),"failed paid trigger cannot continue game");
            return;
        }
        drain(c);
        boolean paid=lands>=4&&accept;
        check(vault.isTapped()!=paid,"Mana Vault untaps exactly when its cost is paid");
        check(c.actor().getManaPool().totalMana()==0,"no invented or unspent mana");
        long tapped=c.actor().getCardsIn(ZoneType.Battlefield).stream().filter(x->x.getName().equals("Plains")&&x.isTapped()).count();
        check(tapped==(paid?4:0),"exactly four actual lands pay, otherwise none");
        check(c.host().kinds.equals(lands<4?List.of():accept?List.of("confirm","payment"):List.of("confirm")),"native confirmation then single whole-cost payment");
        check(c.session().integrityFailure(c.game())==null,"healthy paid/declined/unaffordable trigger has no integrity failure");
        check(bucket(c,"confirmTrigger",lands<4?"forced":"host")==1,"confirmation ownership distinguishes unavailable from host choice");
        reject(()->((PlayerControllerBridge)c.actor().getController()).playSpellAbilityNoStack(wrapper.getWrappedAbility(),false),"paid/declined/forced scope cannot be reused");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},(p,m,v)->switch(m.getName()){
                case "getAssetsDir"->args[0]+"/forge-gui/";case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;case "getCurrentVersion"->"paid-optional-trigger";default->throw new AssertionError(m.getName());}));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++) {
                var nativeResult=nativeControl(seat,"native",null);
                check(nativeResult.equals(nativeControl(seat,"NULL",null)),"Default/null paid-trigger state and RNG unchanged");
                check(nativeResult.equals(nativeControl(seat,"NULL_PROBE",null)),"Default/null-probe paid-trigger state and RNG unchanged");
                var accepted=nativeControl(seat,"native",true);var declined=nativeControl(seat,"native",false);
                check(!accepted.tapped()&&accepted.lands()==4&&accepted.mana()==0,"scripted native acceptance pays four then untaps");
                check(declined.tapped()&&declined.lands()==0&&declined.mana()==0,"scripted native decline pays nothing");
                scopeMisuse(seat);
                vault(seat,4,true,"");
                resolve(seat,4,true,"");resolve(seat,4,false,"");resolve(seat,3,true,"");resolve(seat,0,true,"");
                for(String fault:List.of("missing","delegate","string","payment-delegate","payment-missing","payment-overspend"))resolve(seat,4,true,fault);
            }
            System.out.println("PASS "+checks+" paid optional trigger checks; NOT CERTIFIED");System.exit(0);
        }catch(Throwable failure){failure.printStackTrace();System.exit(1);}
    }
}
