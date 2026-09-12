package forge.bench;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.*;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import java.util.*;

/** Actual-script regression: expand each cast permission exactly once. No games. */
public final class PermissionEnumerationEngineSmoke {
    private static int checks;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    private static Card card(String name, Player player, ZoneType zone) {
        StaticData.instance().attemptToLoadCard(name);
        var paper = Objects.requireNonNull(FModel.getMagicDb().getCommonCards().getCard(name), name);
        var card = Card.fromPaperCard(paper, player);
        card.setGameTimestamp(player.getGame().getNextTimestamp());
        player.getZone(zone).add(card); card.setSickness(false); return card;
    }
    private static Game game() {
        var players = List.of(new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P0", 0, 0, null, "Default")),
            new RegisteredPlayer(new Deck()).setPlayer(GamePlayerUtil.createAiPlayer("P1", 1, 0, null, "Default")));
        var game = new Match(new GameRules(GameType.Constructed), players, "Permission fixture").createGame();
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, game.getPlayers().get(0));
        return game;
    }
    private static String semantic(SpellAbility ability) {
        var permission = ability.getMayPlayOption();
        return (permission == null ? "none" : permission.getHost().getId() + ":" + permission.getAbility().getId())
            + "|" + ability.getPayCosts().toSimpleString() + "|" + ability.getCardStateName()
            + "|" + ability.getOptionalCosts();
    }
    private static List<SpellAbility> compare(Card spell, Player player, boolean kicker) {
        // Independent stock single-pass rules API, not the benchmark enumerator.
        // Its normal actor writes/ID allocation precede the purity snapshot.
        var nativeBase = spell.getAllPossibleAbilities(player, false).stream().filter(SpellAbility::isSpell).toList();
        var nativeAll = new ArrayList<>(nativeBase);
        if (kicker) for (var ability : nativeBase) {
            var options = GameActionUtil.getOptionalCostValues(ability);
            check(options.size() == 1, "actual Burst Lightning has exactly one kicker option");
            nativeAll.add(GameActionUtil.addOptionalCosts(ability, options));
        }
        var state = BenchMenuStateAudit.capture(player.getGame()); var rng = BenchRandomAudit.begin();
        var actual = BenchmarkAbilityEnumeration.spells(List.of(spell), player).stream().filter(SpellAbility::isSpell).toList();
        BenchMenuStateAudit.assertUnchanged(state, player.getGame());
        BenchRandomAudit.assertUnchanged(rng, "permission enumeration");
        check(nativeAll.stream().map(PermissionEnumerationEngineSmoke::semantic).sorted().toList()
                .equals(actual.stream().map(PermissionEnumerationEngineSmoke::semantic).sorted().toList()),
            spell.getName() + " benchmark variants equal independent one-pass engine variants: " + actual.stream().map(PermissionEnumerationEngineSmoke::semantic).toList());
        check(actual.stream().allMatch(SpellAbility::canPlay), "every retained permission is engine-playable");
        return actual;
    }
    private static void execute(SpellAbility ability, Player player, int life, int tapped) {
        if (ability.usesTargeting()) ability.getTargets().add(player.getGame().getPlayers().get(1));
        var result = RulesCostFeasibility.assess(player, ability);
        check(result.status() == RulesCostFeasibility.Status.PAYABLE && result.space().life() == life,
            "chosen permission's actual adjusted payment has exact life " + life);
        // Fixture explicitly chooses the feasibility witness. Production receives
        // the policy-selected symbolic witness (covered by production fixtures).
        var payment = new RulesPaymentExecutor(player, ability, result.witness());
        player.dangerouslySetController(new forge.ai.PlayerControllerAi(player.getGame(), player, player.getLobbyPlayer()) {
            @Override public boolean playTrigger(Card host, forge.game.trigger.WrappedAbility wrapper, boolean mandatory) {
                return payment.duringMandatoryTrigger(host, wrapper, mandatory, () -> super.playTrigger(host, wrapper, mandatory));
            }
            @Override public boolean payManaCost(forge.card.mana.ManaCost cost, forge.game.cost.CostPartMana part,
                    SpellAbility actual, String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
                if (matrix != null) throw new AssertionError("Unexpected conversion");
                return payment.pay(cost, part, actual, effect);
            }
        });
        int before = player.getLife();
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(player, ability, null, payment::decisions), "engine executes selected permission");
        payment.assertPaid();
        check(player.getLife() == before - life && ability.getAmountLifePaid() == life, "actual life receipt matches permission, no repeated charge");
        check(ability.getHostCard().isInZone(ZoneType.Stack), "permission spell actually on stack");
        check(player.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isTapped).count() == tapped,
            "actual source tap count " + tapped);
    }
    private static void library(boolean twoProviders, boolean kicker, boolean chooseManaOrKicker) {
        var game = game(); var player = game.getPlayers().get(0);
        var citadel = card("Bolas's Citadel", player, ZoneType.Battlefield);
        Card future = twoProviders ? card("Future Sight", player, ZoneType.Battlefield) : null;
        for (int i = 0; i < (kicker ? 4 : 1); i++) card("Plains", player, ZoneType.Battlefield);
        var spell = card(kicker ? "Burst Lightning" : "Sol Ring", player, ZoneType.Library);
        card("Forest", player, ZoneType.Library);
        game.getAction().checkStateEffects(true);
        var variants = compare(spell, player, kicker);
        check(variants.size() == (twoProviders || kicker ? 2 : 1), "exact legal permission/optional-cost variant count");
        check(variants.stream().allMatch(a -> RulesCostFeasibility.assess(player, a).space().life() <= 1), "no double-applied Citadel life2 variant");
        var chosen = variants.stream().filter(a -> twoProviders
            ? a.getMayPlayOption().getHost() == (chooseManaOrKicker ? future : citadel)
            : kicker ? a.getPayCosts().getTotalMana().getCMC() == (chooseManaOrKicker ? 4 : 0) : true).findFirst().orElseThrow();
        execute(chosen, player, twoProviders && chooseManaOrKicker ? 0 : 1,
            chooseManaOrKicker ? (kicker ? 4 : 1) : 0);
    }
    private static void faceDownExile() {
        // Isolate the real DBEffect permission and its generated cleanup trigger.
        // This is NOT a complete Expensive Taste cast: the full Dig/adventure
        // path has a separately tracked prospective-characteristic-layer gate.
        var game = game(); var player = game.getPlayers().get(0);
        var source = card("Decadent Dragon", player, ZoneType.Battlefield);
        source.setState(forge.card.CardStateName.Secondary, false);
        var spell = card("Savannah Lions", game.getPlayers().get(1), ZoneType.Exile);
        spell.turnFaceDown(true); source.addRemembered(spell);
        var effect = forge.game.ability.AbilityFactory.getAbility(source.getSVar("DBEffect"), source);
        effect.setActivatingPlayer(player); forge.game.ability.AbilityUtils.resolve(effect);
        card("Plains", player, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        var variants = compare(spell, player, false);
        check(variants.size() == 1, "actual Expensive Taste face-down exile permission expanded exactly once");
        check(RulesCostFeasibility.assess(player, variants.get(0)).status() == RulesCostFeasibility.Status.PAYABLE,
            "face-down permission has supported W payment");
        execute(variants.get(0), player, 0, 1);
    }

    private static forge.game.trigger.WrappedAbility trigger(Card host, Player player, String cost, boolean optional) {
        var ability = forge.game.ability.AbilityFactory.getAbility("AB$ Pump | Cost$ " + cost, host);
        ability.setActivatingPlayer(player);
        var trigger = forge.game.trigger.TriggerHandler.parseTrigger("Mode$ ChangesZone | Origin$ Exile | Destination$ Any | Static$ True", host, true);
        return new forge.game.trigger.WrappedAbility(trigger, ability, optional ? player : null);
    }
    private static void refuses(Runnable run, String label) {
        try { run.run(); } catch (RulesCostFeasibility.Unsupported expected) { check(true, label); return; }
        throw new AssertionError("Accepted " + label);
    }
    private static void nestedPaymentBoundaries() {
        var game = game(); var player = game.getPlayers().get(0);
        var card = card("Memnite", player, ZoneType.Hand);
        var selected = card.getFirstSpellAbility(); selected.setActivatingPlayer(player);
        var payment = new RulesPaymentExecutor(player, selected, RulesCostFeasibility.assess(player, selected).witness());
        var zero = forge.card.mana.ManaCost.ZERO;
        var part = new forge.game.cost.CostPartMana(zero, null);
        var wrapper = trigger(card, player, "0", false);
        var other = trigger(card, player, "0", false);
        refuses(() -> payment.pay(zero, part, wrapper, true), "unregistered zero trigger rejected");
        refuses(() -> payment.duringMandatoryTrigger(card, wrapper, false, () -> true), "nonmandatory scope rejected");
        refuses(() -> payment.duringMandatoryTrigger(card, trigger(card, player, "0", true), true, () -> true), "optional-decider scope rejected");
        refuses(() -> payment.duringMandatoryTrigger(card, trigger(card, player, "1", false), true, () -> true), "nonzero scope rejected");
        refuses(() -> payment.duringMandatoryTrigger(card, trigger(card, player, "X", false), true, () -> true), "X scope rejected");
        refuses(() -> payment.duringMandatoryTrigger(card, trigger(card, player, "0 PayLife<1>", false), true, () -> true), "nonmana scope rejected");
        refuses(() -> payment.duringMandatoryTrigger(card, trigger(card, game.getPlayers().get(1), "0", false), true, () -> true), "wrong-actor scope rejected");
        var otherHost = card("Memnite", player, ZoneType.Hand);
        refuses(() -> payment.duringMandatoryTrigger(otherHost, wrapper, true, () -> true), "wrong-host scope rejected");
        var before = BenchMenuStateAudit.capture(game); var rng = BenchRandomAudit.begin();
        payment.duringMandatoryTrigger(card, wrapper, true, () -> {
            refuses(() -> payment.pay(zero, part, other, true), "same-host unrelated wrapper rejected");
            refuses(() -> payment.pay(zero, part, selected, true), "unrelated original action with effect flag rejected");
            refuses(() -> payment.pay(zero, part, selected, false), "unrelated original action cannot execute inside trigger scope");
            refuses(() -> payment.pay(zero, part, wrapper, false), "non-effect nested callback rejected");
            wrapper.setActivatingPlayer(game.getPlayers().get(1));
            refuses(() -> payment.pay(zero, part, wrapper, true), "changed callback actor rejected");
            wrapper.setActivatingPlayer(player);
            wrapper.setHostCard(otherHost);
            refuses(() -> payment.pay(zero, part, wrapper, true), "changed callback host rejected");
            wrapper.setHostCard(card);
            wrapper.setOptionalTrigger(true);
            refuses(() -> payment.pay(zero, part, wrapper, true), "callback made optional rejected");
            wrapper.setOptionalTrigger(false);
            for (String restriction : List.of("Exiled", "EnchantedCost", "NumTimes", "XMin1")) {
                var modified = new forge.game.cost.CostPartMana(zero, restriction);
                refuses(() -> payment.pay(zero, modified, wrapper, true), "derived zero " + restriction + " rejected");
            }
            var waterbend = new forge.game.cost.CostPartMana(zero, null); waterbend.setMaxWaterbend("1");
            refuses(() -> payment.pay(zero, waterbend, wrapper, true), "waterbend zero rejected");
            var one = new forge.game.cost.Cost("1", false).getTotalMana();
            refuses(() -> payment.pay(one, part, wrapper, true), "repriced callback rejected");
            check(payment.pay(zero, part, wrapper, true), "exact registered wrapper zero accepted");
            refuses(() -> payment.pay(zero, part, wrapper, true), "repeated wrapper payment rejected");
            check(payment.pay(zero, part, wrapper.getWrappedAbility(), true), "exact wrapped ability zero accepted");
            refuses(() -> payment.pay(zero, part, wrapper.getWrappedAbility(), true), "repeated wrapped payment rejected");
            return true;
        });
        BenchMenuStateAudit.assertUnchanged(before, game); BenchRandomAudit.assertUnchanged(rng, "nested literal zero payment");
        check(true, "nested zero callbacks preserve game state and RNG");
        refuses(() -> payment.pay(zero, part, wrapper, true), "scope does not leak after return");
        try { payment.duringMandatoryTrigger(card, wrapper, true, () -> { throw new IllegalStateException("fixture"); }); }
        catch (IllegalStateException expected) { check(expected.getMessage().equals("fixture"), "trigger exception propagates"); }
        refuses(() -> payment.pay(zero, part, wrapper, true), "scope does not leak after exception");
        refuses(payment::assertPaid, "nested zero cannot satisfy original selected action receipt");
        check(payment.pay(zero, part, selected, false), "original action payment still executes"); payment.assertPaid();
        refuses(() -> payment.pay(zero, part, selected, false), "original action remains single-use");
    }
    public static void main(String[] args) {
        try {
            GuiBase.setInterface((IGuiBase) java.lang.reflect.Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                new Class<?>[]{IGuiBase.class}, (proxy, method, values) -> switch (method.getName()) {
                    case "getAssetsDir" -> args[0] + "/forge-gui/";
                    case "isRunningOnDesktop", "isLibgdxPort", "isGuiThread", "hasNetGame" -> false;
                    case "getCurrentVersion" -> "permission-fixture";
                    default -> throw new AssertionError("Unexpected GUI call " + method.getName());
                }));
            FModel.initialize(null, prefs -> { prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false); prefs.setPref(FPref.UI_LANGUAGE, "en-US"); return null; });
            BenchRandomAudit.install(301);
            nestedPaymentBoundaries();
            library(false, false, false);
            library(true, false, false); library(true, false, true);
            library(false, true, false); library(false, true, true);
            faceDownExile();
            System.out.println("PASS all " + checks + " permission enumeration checks; development only"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
