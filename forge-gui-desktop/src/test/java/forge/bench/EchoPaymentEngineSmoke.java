package forge.bench;

import forge.game.cost.*;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.collect.FCollectionView;
import java.util.List;
import static forge.bench.OptionalTriggerExecutionSmoke.*;

/** Native upkeep -> real bridge choice -> exact payment -> native sacrifice.
 * Scripted native control fixes the yes/no decision, retaining Forge payment.
 * This proves execution correspondence, not a whole-policy strength claim. */
public final class EchoPaymentEngineSmoke {
    private record Outcome(long alive, long grave, long tapped, int pool, int life) {}
    private static Outcome run(int seat, int lands, boolean yes, boolean zirda, boolean bridge, String fault) {
        var c = context(seat, bridge ? "BRIDGE" : "native", null);
        c.game().getPhaseHandler().devModeSet(PhaseType.UPKEEP, c.actor());
        var hermit = card("Deranged Hermit", c);
        for (int i=0;i<lands;i++) card("Forest",c);
        if (zirda) card("Zirda, the Dawnwaker",c);
        if (!bridge) c.actor().dangerouslySetController(new forge.ai.PlayerControllerAi(c.game(), c.actor(), c.actor().getLobbyPlayer()) {
            @Override public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> payers) {
                check(sa.hasParam("Echo"),"native control reaches echo callback");
                if (!yes || !forge.ai.ComputerUtilCost.canPayCost(cost,sa,c.actor(),true)) return false;
                return new CostPayment(cost,sa).payComputerCosts(new forge.ai.AiCostDecision(c.actor(),sa,true));
            }
        });
        ready(c); c.host().echo=true; c.host().yes=yes; c.host().fault=fault;
        var wrapper = queue(c);
        check(wrapper != null && wrapper.getWrappedAbility().hasParam("Echo"),"real echo upkeep trigger queued");
        check(wrapper.getPayCosts().getTotalMana().isZero() && wrapper.getWrappedAbility().getPayCosts().getTotalMana().isZero(),
            "echo insertion/effect ability costs remain zero");
        check(c.host().kinds.isEmpty(),"no echo decision or payment on insertion");
        var effect = wrapper.getWrappedAbility();
        if (fault.startsWith("mutate-")) c.host().beforeAnswer = () -> {
            if (fault.equals("mutate-cost")) effect.getMapParams().put("Echo","1");
            else if (fault.equals("mutate-source")) hermit.setGameTimestamp(c.game().getNextTimestamp());
            else if (fault.equals("mutate-effect")) effect.getMapParams().put("SacValid","Creature.YouCtrl");
            else if (fault.equals("mutate-copy")) effect.setCopied(true);
            else throw new AssertionError(fault);
        };
        if (!fault.isEmpty()) {
            reject(() -> drain(c),"invalid echo decision/payment: " + fault);
            check(c.session().integrityFailure(c.game()) != null,"echo failure invalidates game");
            check(c.actor().getCardsIn(ZoneType.Battlefield).stream().filter(x -> x.getName().equals("Forest")).noneMatch(x -> x.isTapped()),
                "malformed echo decision spends no mana sources");
            return null;
        }
        drain(c);
        boolean paid = lands >= 5 && yes;
        var outcome = new Outcome(
            c.actor().getCardsIn(ZoneType.Battlefield).stream().filter(x -> x.getName().equals("Deranged Hermit")).count(),
            c.actor().getCardsIn(ZoneType.Graveyard).stream().filter(x -> x.getName().equals("Deranged Hermit")).count(),
            c.actor().getCardsIn(ZoneType.Battlefield).stream().filter(x -> x.getName().equals("Forest") && x.isTapped()).count(),
            c.actor().getManaPool().totalMana(), c.actor().getLife());
        check(outcome.equals(new Outcome(paid?1:0,paid?0:1,paid?5:0,0,20)),"echo keeps/sacrifices source and spends exact cost");
        if (bridge) {
            check(c.host().kinds.equals(lands < 5 ? List.of() : yes ? List.of("confirm","payment") : List.of("confirm")),
                "host owns optional decision and one full payment; unpayable forced");
            check(c.session().integrityFailure(c.game()) == null,"healthy echo has no integrity failure");
            var methods=((PlayerControllerBridge)c.actor().getController()).getCounters().toJson()
                .getAsJsonObject("controllerCoverage").getAsJsonObject("methods");
            for (var entry:methods.entrySet()) {
                check(entry.getValue().getAsJsonObject().get("stock").getAsInt()==0
                    && entry.getValue().getAsJsonObject().get("unclassified").getAsInt()==0,"echo owns callback " + entry.getKey());
            }
        }
        check(wrapper.getPayCosts().getTotalMana().isZero() && effect.getPayCosts().getTotalMana().isZero(),
            "resolution payment never overwrites native trigger cost");
        return outcome;
    }
    private static void unscoped(int seat) {
        var c=context(seat,"BRIDGE",null); c.game().getPhaseHandler().devModeSet(PhaseType.UPKEEP,c.actor());
        card("Deranged Hermit",c);for(int i=0;i<5;i++)card("Forest",c);ready(c);
        var wrapper=queue(c);var effect=wrapper.getWrappedAbility();
        reject(() -> c.actor().getController().payCostToPreventEffect(new Cost(effect.getParam("Echo"),true),effect,false,new PlayerCollection(c.actor())),
            "echo callback outside actual stack resolution rejected");
        check(c.host().kinds.isEmpty(),"unscoped echo never asks or pays");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),new Class<?>[]{IGuiBase.class},
                (p,m,v)->switch(m.getName()) {
                    case "getAssetsDir"->args[0]+"/forge-gui/";
                    case "isRunningOnDesktop","isLibgdxPort","isGuiThread","hasNetGame"->false;
                    case "getCurrentVersion"->"echo-payment-fixture";
                    default->throw new AssertionError(m.getName());
                }));
            FModel.initialize(null,prefs->{prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY,false);prefs.setPref(FPref.UI_LANGUAGE,"en-US");return null;});
            for(int seat=0;seat<2;seat++) {
                for(int lands:List.of(0,4,5,6)) for(boolean yes:List.of(false,true)) {
                    var nativeResult=run(seat,lands,yes,true,false,"");
                    check(nativeResult.equals(run(seat,lands,yes,true,true,"")),"native and bridge echo receipts agree seat="+seat);
                }
                for(String fault:List.of("delegate","string","missing","payment-delegate","payment-missing","payment-overspend",
                    "mutate-cost","mutate-source","mutate-effect","mutate-copy")) run(seat,5,true,false,true,fault);
                unscoped(seat);
            }
            System.out.println("PASS "+checks+" echo native/bridge execution checks");System.exit(0);
        } catch(Throwable failure) {failure.printStackTrace();System.exit(1);}
    }
}
