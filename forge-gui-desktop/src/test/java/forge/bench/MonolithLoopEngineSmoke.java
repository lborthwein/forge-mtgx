package forge.bench;

import forge.StaticData;
import forge.ai.PlayerControllerAi;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.game.*;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.*;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaConversionMatrix;
import forge.game.phase.PhaseType;
import forge.game.player.*;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.List;

/** Fixed actions through native costs/stack/triggers, NOT a policy or bridge certificate. */
public final class MonolithLoopEngineSmoke {
    private static int checks;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    private static Card card(String name, Player p) {
        StaticData.instance().attemptToLoadCard(name);
        var c = Card.fromPaperCard(FModel.getMagicDb().getCommonCards().getCard(name), p);
        c.setGameTimestamp(p.getGame().getNextTimestamp()); p.getZone(ZoneType.Battlefield).add(c); c.setSickness(false);
        return c;
    }
    private static final class Controller extends PlayerControllerAi {
        int manaTriggers;
        RulesPaymentExecutor auditedPayment;
        Controller(Game g, Player p) { super(g, p, p.getLobbyPlayer()); }
        @Override public forge.game.staticability.StaticAbility chooseSingleStaticAbility(List<forge.game.staticability.StaticAbility> choices) {
            check(choices.size() == 1, "fixture must not hide a cost-reducer ordering decision");
            return choices.get(0);
        }
        @Override public boolean payManaCost(ManaCost cost, CostPartMana part, SpellAbility sa,
                String prompt, ManaConversionMatrix matrix, boolean effect) {
            check(matrix == null, "unexpected conversion");
            if (auditedPayment != null) return auditedPayment.pay(cost, part, sa, effect);
            var remaining = new ManaCostBeingPaid(cost);
            check(CostAdjustment.adjust(remaining, sa, getPlayer(), null, false, effect), "native cost adjustment");
            check(remaining.getGenericManaAmount() == remaining.getConvertedManaCost(), "fixture admits only generic payment");
            for (var mana : com.google.common.collect.Lists.newArrayList(getPlayer().getManaPool())) {
                if (remaining.isPaid()) break;
                check(getPlayer().getManaPool().tryPayCostWithMana(sa, remaining, mana, false), "actual pool token consumed");
                sa.getPayingMana().add(mana);
            }
            return remaining.isPaid();
        }
        @Override public boolean playTrigger(Card host, WrappedAbility wrapper, boolean mandatory) {
            check(mandatory && wrapper.getTrigger().isStatic() && wrapper.getDecider() == null
                    && wrapper.getApi() == ApiType.ManaReflected, "only actual mandatory reflected-mana trigger");
            manaTriggers++;
            wrapper.resolve(); // Native requirements checks and native no-stack callback.
            return true;
        }
        @Override public void playSpellAbilityNoStack(SpellAbility sa, boolean mayChooseNewTargets) {
            check(sa.getApi() == ApiType.ManaReflected && !sa.usesTargeting(), "unexpected no-stack choice");
            check(sa.getPayCosts() == null || sa.getPayCosts().getCostParts().stream()
                    .allMatch(p -> p instanceof CostPartMana m && m.isUnmodifiedZero()), "mana trigger is free");
            AbilityUtils.resolve(sa);
        }
    }
    private static void run(String name, String partner, int output, int cost, int seat) {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("A", 0, 0, null, "Default")),
                new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("B", 1, 0, null, "Default")));
        var g = new Match(new GameRules(GameType.Constructed), players, "Monolith rules fixture").createGame();
        g.setAge(GameStage.Play); var p = g.getPlayers().get(seat);
        g.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        var controller = new Controller(g, p); p.dangerouslySetController(controller);
        var partners = partner == null ? List.<String>of() : List.of(partner.split(" \\+ "));
        var mono = card(name, p); for (var partnerName : partners) card(partnerName, p);
        g.getAction().checkStateEffects(true); g.getTriggerHandler().resetActiveTriggers();
        var mana = mono.getManaAbilities().get(0); mana.setActivatingPlayer(p);
        var untap = mono.getSpellAbilities().stream().filter(a -> a.getApi() == ApiType.Untap).findFirst().orElseThrow();
        untap.setActivatingPlayer(p);
        check(untap.getPayCosts().getCostParts().stream().noneMatch(c -> c instanceof CostUntap), "untap is effect, not Q cost");
        int seed = Math.max(0, cost - output);
        if (seed > 0) p.getManaPool().addMana(new forge.game.mana.Mana((byte)forge.card.mana.ManaAtom.COLORLESS, mono, mana.getManaPart(), p));
        for (int lap = 0; lap < (cost > output ? 1 : 3); lap++) {
            int before = p.getManaPool().totalMana();
            var priorTokens = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<forge.game.mana.Mana, Boolean>());
            p.getManaPool().forEach(priorTokens::add);
            check(mana.canPlay(), "tap action playable");
            check(new CostPayment(mana.getPayCosts(), mana).payComputerCosts(new RulesCostDecisionMaker(p, mana)), "native tap cost");
            g.getStack().addAndUnfreeze(mana);
            check(mono.isTapped() && g.getStack().isEmpty(), "mana resolves immediately");
            check(p.getManaPool().totalMana() == before + output, "actual mana yield");
            var emitted = com.google.common.collect.Lists.newArrayList(p.getManaPool()).stream().filter(m -> !priorTokens.contains(m)).toList();
            check(emitted.size() == output && emitted.stream().allMatch(m -> m.getColor() == forge.card.mana.ManaAtom.COLORLESS), "actual new colorless token identities");
            // Forge can retain an LKI source snapshot on a mana token; card ID
            // identifies the physical producer, Java object identity does not.
            check(emitted.stream().filter(m -> m.getSourceCard().getId() == mono.getId()).count() == 3,
                    "Monolith itself produces exactly three: " + emitted.stream().map(m -> m.getSourceCard().getName() + ":" + m.getSourceCard().getId()).toList());
            check(emitted.stream().filter(m -> m.getSourceCard().getName().equals("Kinnan, Bonder Prodigy")).count() == output - 3,
                    "bonus token belongs to Kinnan, not Monolith");
            var audited = RulesCostFeasibility.assess(p, untap);
            check(audited.status() == RulesCostFeasibility.Status.PAYABLE,
                    "record honest audit scope, not just native engine success: " + audited);
            check(untap.canPlay(), "native untap action playable");
            {
                var choices = new RulesPaymentChoices(p, untap);
                var answer = new com.google.gson.JsonObject();
                answer.addProperty("choice", 0);
                answer.add("sourceOrder", choices.request().getAsJsonArray("menu").get(0).getAsJsonObject().getAsJsonArray("sources").deepCopy());
                controller.auditedPayment = new RulesPaymentExecutor(p, untap, choices.select(answer));
            }
            check(new CostPayment(untap.getPayCosts(), untap).payComputerCosts(new RulesCostDecisionMaker(p, untap)), "native full untap payment");
            if (controller.auditedPayment != null) { controller.auditedPayment.assertPaid(); controller.auditedPayment = null; }
            g.getStack().addAndUnfreeze(untap);
            check(mono.isTapped() && g.getStack().size() == 1, "untap waits on stack");
            check(!mana.canPlay(), "cannot retap before untap resolves");
            check(p.getManaPool().totalMana() == before + output - cost, "actual reduced/full cost spent");
            g.getStack().resolveStack();
            check(!mono.isTapped() && g.getStack().isEmpty(), "native untap resolution");
            check(p.getManaPool().totalMana() == before + output - cost, "resolution creates no extra mana");
        }
        check(controller.manaTriggers == (partners.contains("Kinnan, Bonder Prodigy") ? 3 : 0), "actual Kinnan trigger count");
        System.out.println("MONOLITH " + name + " partner=" + partner + " seat=" + seat + " output=" + output + " cost=" + cost
                + " net=" + (output-cost) + " pool=" + p.getManaPool().totalMana() + " audit=PAYABLE; policy not certified");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase)java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(), new Class<?>[]{IGuiBase.class},
                    (p,m,v) -> switch(m.getName()) {
                        case "getAssetsDir" -> args[0] + "/forge-gui/";
                        case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                        case "getCurrentVersion" -> "monolith-fixture";
                        default -> throw new AssertionError("unexpected GUI: " + m.getName());
                    }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            for (int seat = 0; seat < 2; seat++) {
                run("Basalt Monolith", "Zirda, the Dawnwaker", 3, 1, seat);
                run("Grim Monolith", "Zirda, the Dawnwaker", 3, 2, seat);
                run("Basalt Monolith", "Kinnan, Bonder Prodigy", 4, 3, seat);
                run("Grim Monolith", "Kinnan, Bonder Prodigy", 4, 4, seat);
                run("Basalt Monolith", "Kinnan, Bonder Prodigy + Zirda, the Dawnwaker", 4, 1, seat);
                run("Grim Monolith", "Kinnan, Bonder Prodigy + Zirda, the Dawnwaker", 4, 2, seat);
                run("Basalt Monolith", null, 3, 3, seat);
                run("Grim Monolith", null, 3, 4, seat);
            }
            System.out.println("PASS " + checks + " Monolith native execution checks; audit combo support NOT CERTIFIED");
            System.exit(0);
        } catch(Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
