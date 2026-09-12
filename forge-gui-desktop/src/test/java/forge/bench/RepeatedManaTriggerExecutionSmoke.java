package forge.bench;

import forge.game.card.CounterEnumType;
import forge.game.phase.PhaseType;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import static forge.bench.OptionalTriggerExecutionSmoke.*;

/** Actual bridge callbacks, native payments and native reflexive trigger. */
public final class RepeatedManaTriggerExecutionSmoke {
    private static void run(int seat, int lands, int accepted, boolean zirda, String fault) {
        var c = context(seat, "BRIDGE", null);
        c.game().getPhaseHandler().devModeSet(PhaseType.MAIN1, c.actor());
        var original = card("Intrepid Adversary", c);
        var hand = c.game().getAction().moveToHand(original, null);
        for (int i = 0; i < lands; i++) card("Plains", c);
        if (zirda) card("Zirda, the Dawnwaker", c);
        ready(c); c.host().yes = true; c.host().repeatedLimit = accepted; c.host().fault = fault;
        var live = c.game().getAction().moveToPlay(hand, c.actor(), null, new java.util.HashMap<>());
        c.game().getAction().checkStateEffects(true);
        c.game().getTriggerHandler().runWaitingTriggers();
        c.game().getStack().addAllTriggeredAbilitiesToStack();
        check(c.game().getStack().size() == 1, "one real ETB trigger");
        var wrapper = (WrappedAbility)c.game().getStack().peekAbility();
        if (fault.startsWith("mutate-")) c.host().beforeAnswer = () -> {
            if (c.host().kinds.stream().filter("payment"::equals).count() != 1
                    || !c.host().kinds.get(c.host().kinds.size() - 1).equals("confirm")) return;
            var underlying = wrapper.getWrappedAbility();
            if (fault.equals("mutate-host")) underlying.getHostCard().setSVar("NumTimes", "Number$7");
            else if (fault.equals("mutate-ability")) underlying.setSVar("NumTimes", "Number$7");
            else if (fault.equals("mutate-execute")) underlying.getAdditionalAbility("Execute").getMapParams().put("CounterType", "P1P1");
            else throw new AssertionError("unknown mutation");
            c.host().beforeAnswer = null;
        };
        check(!wrapper.getTrigger().hasParam("OptionalDecider") && wrapper.isOptionalTrigger(), "native implicit optional cost trigger");
        check(c.host().kinds.isEmpty(), "no confirmation or payment at insertion");
        if (!fault.isEmpty()) {
            reject(() -> c.game().getStack().resolveStack(), "repeated payment fails closed: " + fault);
            check(c.session().integrityFailure(c.game()) != null, "failure latches invalidity");
            check(live.getCounters(CounterEnumType.VALOR) == 0, "no counters after failed payment");
            return;
        }
        c.game().getStack().resolveStack();
        int paid = Math.min(lands / 2, accepted);
        check(live.getCounters(CounterEnumType.VALOR) == 0, "payment does not place counters inline");
        check(c.actor().getCardsIn(ZoneType.Battlefield).stream().filter(x -> x.getName().equals("Plains") && x.isTapped()).count() == paid * 2L,
            "each resolution payment uses two actual lands, regardless of Zirda");
        check(c.host().kinds.stream().filter("payment"::equals).count() == paid, "one exact payment request per accepted repetition");
        check(c.actor().getManaPool().totalMana() == 0, "no fabricated or extra mana");
        drain(c);
        check(live.getCounters(CounterEnumType.VALOR) == paid, "native reflexive trigger remembers completed payment count");
        check(c.session().integrityFailure(c.game()) == null, "healthy repeated payment not invalidated");
        System.out.println("REPEATED seat=" + seat + " lands=" + lands + " limit=" + accepted + " zirda=" + zirda + " paid=" + paid);
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                (p,m,v) -> switch(m.getName()) {
                    case "getAssetsDir" -> args[0] + "/forge-gui/";
                    case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                    case "getCurrentVersion" -> "repeated-mana-trigger-fixture";
                    default -> throw new AssertionError(m.getName());
                }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            for (int seat = 0; seat < 2; seat++) {
                run(seat, 0, 2, false, ""); run(seat, 1, 2, true, "");
                run(seat, 6, 0, false, ""); run(seat, 6, 1, false, "");
                run(seat, 6, 2, true, ""); run(seat, 4, 3, false, "");
                for (String fault : java.util.List.of("delegate", "string", "missing", "payment-delegate", "payment-missing", "payment-overspend", "mutate-host", "mutate-ability", "mutate-execute"))
                    run(seat, 4, 2, false, fault);
            }
            System.out.println("PASS repeated native payment and reflexive trigger fixtures; policy correspondence NOT CERTIFIED");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
