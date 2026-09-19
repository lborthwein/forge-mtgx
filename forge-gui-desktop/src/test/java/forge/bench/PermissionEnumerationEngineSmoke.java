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
    // ---------------------------------------------------------------------
    // Nonmana cost coverage (rules-nonmana-cost-v1). Expectations first: the
    // layer may only say PAYABLE where Forge's own canPay accepts a payment,
    // a forced payment must execute and leave an exact receipt, a cost that
    // competes with the mana witness must stay UNSUPPORTED, and a genuine card
    // choice must stay a choice that names the host ask it needs.
    // ---------------------------------------------------------------------
    private static void refusesWith(Runnable run, String fragment, String label) {
        try { run.run(); }
        catch (RulesCostFeasibility.Unsupported expected) {
            check(expected.getMessage().contains(fragment), label + " (got: " + expected.getMessage() + ")");
            return;
        }
        throw new AssertionError("Accepted " + label);
    }
    private static SpellAbility ability(Card card, Player player, String contains) {
        var found = card.getSpellAbilities().stream()
                .filter(a -> a.getPayCosts() != null && (String.valueOf(a.getPayCosts()).contains(contains)
                        || a.getPayCosts().toSimpleString().contains(contains)
                        || a.getPayCosts().getCostParts().stream()
                            .anyMatch(p -> p.getClass().getSimpleName().equals("Cost" + contains))))
                .findFirst().orElseThrow(() -> new AssertionError("no ability with cost " + contains + " on " + card));
        found.setActivatingPlayer(player);
        return found;
    }
    private static RulesCostFeasibility.Result assessed(Player player, SpellAbility ability, RulesCostFeasibility.Status status, String label) {
        var result = RulesCostFeasibility.assess(player, ability);
        check(result.status() == status, label + " is " + status + " (got " + result.status() + " " + result.reason() + ")");
        return result;
    }
    private static void refusedAssessment(Player player, SpellAbility ability, String fragment, String label) {
        var result = RulesCostFeasibility.assess(player, ability);
        check(result.status() == RulesCostFeasibility.Status.UNSUPPORTED && result.reason().contains(fragment),
            label + " refused as '" + fragment + "' (got " + result.status() + " " + result.reason() + ")");
    }
    /** Pay and execute the selected action exactly as production does, then let
     * the executor prove its own receipt. No AI cost visitor is involved. */
    private static void executeAction(Player player, SpellAbility selected, java.util.function.Consumer<SpellAbility> aim, String label) {
        var result = RulesCostFeasibility.assess(player, selected);
        check(result.status() == RulesCostFeasibility.Status.PAYABLE, label + " is payable before execution: " + result.reason());
        if (aim != null) aim.accept(selected);
        var payment = new RulesPaymentExecutor(player, selected, result.witness());
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
        check(forge.ai.ComputerUtil.handlePlayingSpellAbility(player, selected, null, payment::decisions), label + " executes");
        payment.assertPaid();
        check(true, label + " receipt verified by the executor");
    }
    /** Relic of Progenitus: {1}, Exile Relic of Progenitus. Self-exile of the
     * source, one generic; no choice exists anywhere in the payment. */
    private static void selfExileCost() {
        var game = game(); var player = game.getPlayers().get(0);
        var relic = card("Relic of Progenitus", player, ZoneType.Battlefield);
        card("Plains", player, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        var selected = ability(relic, player, "Exile");
        executeAction(player, selected, null, "self-exile cost (Relic of Progenitus)");
        check(relic.isInZone(ZoneType.Exile), "the exiled source actually left the battlefield for exile");
    }
    /** Grim Lavamancer: {R}, {T}, Exile two cards from your graveyard. Two cards
     * in the graveyard leave one legal selection; three leave a real choice. */
    private static void forcedAndChosenExileCost(boolean forced) {
        var game = game(); var player = game.getPlayers().get(0);
        var lavamancer = card("Grim Lavamancer", player, ZoneType.Battlefield);
        card("Mountain", player, ZoneType.Battlefield);
        var graveyard = new ArrayList<Card>();
        for (int i = 0; i < (forced ? 2 : 3); i++) graveyard.add(card("Grizzly Bears", player, ZoneType.Graveyard));
        game.getAction().checkStateEffects(true);
        var selected = ability(lavamancer, player, "Exile");
        if (forced) {
            executeAction(player, selected, sa -> sa.getTargets().add(game.getPlayers().get(1)),
                "tap + forced graveyard exile (Grim Lavamancer)");
            check(graveyard.stream().allMatch(c -> c.isInZone(ZoneType.Exile)), "both forced graveyard cards actually exiled");
            check(lavamancer.isTapped(), "the joint tap cost was actually paid");
        } else {
            // The menu answer is a rules fact: some legal payment exists. WHICH
            // two cards to exile is play, so it is not decided here.
            var result = assessed(player, selected, RulesCostFeasibility.Status.PAYABLE, "three-card graveyard exile menu entry");
            check(RulesCostFeasibility.forcedSelection(player, selected, selected.getPayCosts().getCostParts().stream()
                    .filter(p -> p instanceof forge.game.cost.CostExile).findFirst().orElseThrow()) == null,
                "a three-card graveyard leaves no forced exile selection");
            selected.getTargets().add(game.getPlayers().get(1));
            var payment = new RulesPaymentExecutor(player, selected, result.witness());
            var decisions = payment.decisions(selected);
            var exile = selected.getPayCosts().getCostParts().stream()
                    .filter(p -> p instanceof forge.game.cost.CostExile).findFirst().orElseThrow();
            refusesWith(() -> exile.accept(decisions), "requires explicit host card selection (exileCost)",
                "a real exile choice names the host ask instead of picking a card");
        }
    }
    /** Elvish Reclaimer: {2}, {T}, Sacrifice a land. Every candidate funds mana,
     * so no payment avoids the mana witness and the verdict stays UNSUPPORTED. */
    private static void sacrificeCompetingWithMana() {
        var game = game(); var player = game.getPlayers().get(0);
        var reclaimer = card("Elvish Reclaimer", player, ZoneType.Battlefield);
        for (int i = 0; i < 3; i++) card("Plains", player, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        refusedAssessment(player, ability(reclaimer, player, "Sac"),
            "nonmana cost competes with mana sources: CostSacrifice", "sacrifice of a land");
    }
    /** Goblin Engineer: {R}, {T}, Sacrifice an artifact. One non-mana artifact
     * forces the selection; a mana rock as the only artifact competes instead. */
    private static void forcedAndCompetingSacrifice(boolean manaRock) {
        var game = game(); var player = game.getPlayers().get(0);
        var engineer = card("Goblin Engineer", player, ZoneType.Battlefield);
        card("Mountain", player, ZoneType.Battlefield);
        var artifact = card(manaRock ? "Sol Ring" : "Memnite", player, ZoneType.Battlefield);
        var target = card("Memnite", player, ZoneType.Graveyard);
        game.getAction().checkStateEffects(true);
        var selected = ability(engineer, player, "Sac");
        if (manaRock) {
            refusedAssessment(player, selected, "nonmana cost competes with mana sources: CostSacrifice",
                "sacrifice whose only candidate is a mana source");
            return;
        }
        var sacrifice = selected.getPayCosts().getCostParts().stream()
                .filter(p -> p instanceof forge.game.cost.CostSacrifice).findFirst().orElseThrow();
        check(List.of(artifact).equals(RulesCostFeasibility.forcedSelection(player, selected, sacrifice)),
            "the one non-mana artifact is the forced sacrifice selection");
        executeAction(player, selected, sa -> sa.getTargets().add(target), "tap + forced artifact sacrifice (Goblin Engineer)");
        check(!artifact.isInZone(ZoneType.Battlefield), "the forced sacrifice actually left the battlefield");
    }
    /** Bomat Courier: {R}, Discard your hand, Sacrifice Bomat Courier. Three
     * resources at once — mana, the whole hand and the source — none shared. */
    private static void jointDiscardHandAndSelfSacrifice() {
        var game = game(); var player = game.getPlayers().get(0);
        var courier = card("Bomat Courier", player, ZoneType.Battlefield);
        card("Mountain", player, ZoneType.Battlefield);
        var hand = List.of(card("Grizzly Bears", player, ZoneType.Hand), card("Savannah Lions", player, ZoneType.Hand));
        game.getAction().checkStateEffects(true);
        var selected = ability(courier, player, "Discard");
        executeAction(player, selected, null, "joint discard-hand + self-sacrifice (Bomat Courier)");
        check(player.getCardsIn(ZoneType.Hand).isEmpty() && hand.stream().allMatch(c -> c.isInZone(ZoneType.Graveyard)),
            "the whole hand was actually discarded");
        check(!courier.isInZone(ZoneType.Battlefield), "the source was actually sacrificed");
    }
    /** Walking Ballista: remove a +1/+1 counter. A non-loyalty counter on the
     * source is its own resource; the amount must be a bounded literal. */
    private static void nonLoyaltyCounterCost() {
        var game = game(); var player = game.getPlayers().get(0);
        var ballista = card("Walking Ballista", player, ZoneType.Battlefield);
        ballista.addCounterInternal(forge.game.card.CounterType.getType("P1P1"), 2,
            player, false, new forge.game.GameEntityCounterTable(), forge.game.ability.AbilityKey.newMap());
        game.getAction().checkStateEffects(true);
        var selected = ability(ballista, player, "RemoveCounter");
        executeAction(player, selected, sa -> sa.getTargets().add(game.getPlayers().get(1)),
            "non-loyalty source counter cost (Walking Ballista)");
        check(ballista.getCounters(forge.game.card.CounterEnumType.P1P1) == 1, "exactly one counter was actually removed");
        check(!game.getStack().isEmpty(), "the paid ability actually reached the stack");
    }
    /** Two consuming parts cannot spend one card: Hall's condition, exactly. */
    private static void sharedResourceRefusal() {
        var game = game(); var player = game.getPlayers().get(0);
        var courier = card("Bomat Courier", player, ZoneType.Battlefield);
        card("Mountain", player, ZoneType.Battlefield);
        game.getAction().checkStateEffects(true);
        // An empty hand needs no card, so the conjunction is still satisfiable;
        // this pins that an empty selection is not silently treated as a gap.
        var selected = ability(courier, player, "Discard");
        assessed(player, selected, RulesCostFeasibility.Status.PAYABLE, "discard of an empty hand plus self-sacrifice");
        var exile = new forge.game.cost.CostExile("2", "Card", null, ZoneType.Graveyard);
        check(RulesCostFeasibility.forcedSelection(player, selected, exile) == null,
            "an unsatisfiable exile cost yields no forced selection");
    }
    private static void nonManaCostCoverage() {
        selfExileCost();
        forcedAndChosenExileCost(true);
        forcedAndChosenExileCost(false);
        sacrificeCompetingWithMana();
        forcedAndCompetingSacrifice(false);
        forcedAndCompetingSacrifice(true);
        jointDiscardHandAndSelfSacrifice();
        nonLoyaltyCounterCost();
        sharedResourceRefusal();
    }
    /** Card.mayPlay is an ORDERING input to play: mayPlay(Player) returns its
     * values as a List and GameActionUtil.getMayPlaySpellOptions builds the
     * AI's alternative play options from that list. With a HashMap the order
     * followed StaticAbility.hashCode(), which folds in the Class object's
     * identity hash and is therefore not stable between binaries. Pinned here:
     * the order is INSERTION order, for every permutation of insertion. */
    private static void mayPlayOrderIsInsertionOrder() {
        final int N = 6;
        for (int rotation = 0; rotation < N; rotation++) {
            var game = game();
            var player = game.getPlayers().get(0);
            var subject = card("Lightning Bolt", player, ZoneType.Graveyard);
            var grantors = new java.util.ArrayList<forge.game.staticability.StaticAbility>();
            for (int n = 0; n < N; n++) {
                var host = card("Serra Paragon", player, ZoneType.Battlefield);
                grantors.add(host.getStaticAbilities().iterator().next());
            }
            // Insert under a rotated permutation; the readback must follow it.
            var order = new java.util.ArrayList<forge.game.staticability.StaticAbility>();
            for (int n = 0; n < N; n++) order.add(grantors.get((n + rotation) % N));
            for (var sta : order) subject.setMayPlay(player, false, null, false, false, sta);
            var seen = subject.mayPlay(player);
            check(seen.size() == N, "every grant is present rotation=" + rotation);
            boolean ordered = true;
            for (int n = 0; n < N; n++)
                if (seen.get(n).getAbility() != order.get(n)) ordered = false;
            check(ordered, "may-play option order is insertion order rotation=" + rotation);
            var saved = subject.getMayPlay();
            check(new java.util.ArrayList<>(saved.keySet()).equals(order),
                "permission snapshot preserves order rotation=" + rotation);
            subject.setMayPlay(saved);
            saved.clear();
            check(subject.mayPlay(player).stream().map(o -> o.getAbility()).toList().equals(order),
                "restore preserves order and owns its map rotation=" + rotation);
            var copy = forge.game.card.CardCopyService.getLKICopy(subject);
            check(copy.mayPlay(player).stream().map(o -> o.getAbility()).toList().equals(order),
                "LKI copy preserves permission order rotation=" + rotation);
            subject.removeMayPlay(order.get(0));
            check(copy.mayPlay(player).size() == N,
                "LKI permission map is independent rotation=" + rotation);
            subject.setMayPlay(player, false, null, false, false, order.get(0));
            var reinserted = new java.util.ArrayList<>(order.subList(1, N));
            reinserted.add(order.get(0));
            check(subject.mayPlay(player).stream().map(o -> o.getAbility()).toList().equals(reinserted),
                "remove and reinsert appends permission rotation=" + rotation);
        }
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
            nonManaCostCoverage();
            mayPlayOrderIsInsertionOrder();
            System.out.println("PASS all " + checks + " permission enumeration checks; development only"); System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
}
